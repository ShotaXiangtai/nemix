package com.bitchat.android.directory

import android.content.Context
import android.util.Base64
import android.util.Log
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.net.InternetRelayPreferences
import com.bitchat.android.nostr.NostrBackgroundRuntime
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrFilter
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.MessageRouter
import com.bitchat.android.util.hexEncodedString
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Privacy-preserving Serverless P2P Random UUID Directory & Instant Internet Friend Add.
 *
 * - Each user receives a random UUID string (e.g. `8f3a9c12-4b7e-4d2a-9e1f-6c8b2a5d0e3f`)
 *   that can be regenerated at any time.
 * - Directory records are published to P2P / Nostr relays as NIP-78 replaceable events (`kind = 30078`)
 *   indexed ONLY by `["d", "nemix-id:" + SHA-256(normalizedUuid)]` with payload encrypted using
 *   `AES-256-GCM(key = SHA-256("nemix-uuid-v1:" + normalizedUuid))`.
 * - Relays and third parties cannot enumerate users or read any keys/nicknames without knowing the UUID.
 * - Searching a friend's UUID resolves their Noise + Nostr public keys and simultaneously registers
 *   them as a mutual E2EE contact over the Internet while sending `[NEMIX_FRIEND_ADD:...]` so the
 *   friend's device automatically adds the requester back.
 */
object NemixUuidDirectoryManager {
    private const val TAG = "NemixUuidDirectory"
    const val PREFS_NAME = "nemix_uuid_prefs"
    private const val KEY_MY_UUID = "my_random_uuid"
    private const val KEY_LAST_PUBLISHED_AT = "last_published_at"
    private const val NIP78_APP_DATA_KIND = 30078
    private const val FRIEND_ADD_PREFIX = "[NEMIX_FRIEND_ADD:"

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _myUuid = MutableStateFlow("")
    val myUuid: StateFlow<String> = _myUuid.asStateFlow()

    private val _isPublishing = MutableStateFlow(false)
    val isPublishing: StateFlow<Boolean> = _isPublishing.asStateFlow()

    data class DirectoryRecordPayload(
        val v: Int = 1,
        val uuid: String,
        val name: String,
        val npub: String,
        val nostrPubHex: String,
        val noisePubHex: String,
        val updatedAt: Long = System.currentTimeMillis()
    )

    data class FriendAddPacketPayload(
        val v: Int = 1,
        val uuid: String,
        val name: String,
        val npub: String,
        val nostrPubHex: String,
        val noisePubHex: String
    )

    data class AddedInternetContact(
        val uuid: String,
        val nickname: String,
        val conversationID: String,
        val noisePubHex: String,
        val npub: String
    )

    fun init(context: Context) {
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var existing = prefs.getString(KEY_MY_UUID, null)?.trim().orEmpty()
        if (existing.isBlank()) {
            existing = generateRandomUuid()
            prefs.edit().putString(KEY_MY_UUID, existing).apply()
        }
        _myUuid.value = existing

        if (InternetRelayPreferences.isInternetEnabled()) {
            scope.launch {
                val nick = AppStateStore.nickname.value.ifBlank {
                    appCtx.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE)
                        .getString("nickname", "nemix") ?: "nemix"
                }
                publishMyDirectoryRecord(appCtx, nick)
            }
        }
    }

    fun regenerateUuid(context: Context, currentNickname: String): String {
        val appCtx = context.applicationContext
        val fresh = generateRandomUuid()
        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MY_UUID, fresh)
            .remove(KEY_LAST_PUBLISHED_AT)
            .apply()
        _myUuid.value = fresh
        if (InternetRelayPreferences.isInternetEnabled()) {
            scope.launch { publishMyDirectoryRecord(appCtx, currentNickname) }
        }
        return fresh
    }

    fun normalizeUuid(raw: String): String {
        return raw.trim()
            .removePrefix("nemix://id/")
            .removePrefix("nemix:")
            .trim()
            .lowercase()
    }

    fun isValidUuidFormat(raw: String): Boolean {
        val norm = normalizeUuid(raw)
        // Accept standard UUID v4 (36 chars with hyphens) or compact alphanumeric 16..64 chars
        val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        val compactRegex = Regex("^[0-9a-z-]{12,64}$")
        return uuidRegex.matches(norm) || compactRegex.matches(norm)
    }

    fun isFriendAddPacket(content: String): Boolean =
        content.startsWith(FRIEND_ADD_PREFIX) && content.endsWith("]")

    /**
     * Publishes our encrypted UUID record to active Internet / Custom P2P Relays.
     */
    fun publishMyDirectoryRecord(context: Context, nickname: String): Boolean {
        val appCtx = context.applicationContext
        val uuid = _myUuid.value.ifBlank {
            init(appCtx)
            _myUuid.value
        }
        val normUuid = normalizeUuid(uuid)
        if (normUuid.isBlank()) return false

        return try {
            _isPublishing.value = true
            val nostrId = NostrIdentityBridge.getCurrentNostrIdentity(appCtx) ?: return false
            val mesh = MeshServiceHolder.getUnifiedOrCreate(appCtx)
            val noisePubHex = mesh.getStaticNoisePublicKey()?.hexEncodedString()?.lowercase()
                ?: return false

            val payload = DirectoryRecordPayload(
                uuid = normUuid,
                name = nickname.ifBlank { "nemix" },
                npub = nostrId.npub,
                nostrPubHex = nostrId.publicKeyHex.lowercase(),
                noisePubHex = noisePubHex
            )

            val encryptedB64 = encryptForUuid(normUuid, gson.toJson(payload))
            val lookupTag = directoryTagForUuid(normUuid)

            val unsignedEvent = NostrEvent(
                pubkey = nostrId.publicKeyHex,
                createdAt = (System.currentTimeMillis() / 1000L).toInt(),
                kind = NIP78_APP_DATA_KIND,
                tags = listOf(listOf("d", lookupTag)),
                content = encryptedB64
            )
            val signedEvent = nostrId.signEvent(unsignedEvent)

            val relayMgr = NostrRelayManager.getInstance(appCtx)
            if (InternetRelayPreferences.isInternetEnabled()) {
                relayMgr.connect()
            }
            relayMgr.sendEvent(signedEvent)

            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_LAST_PUBLISHED_AT, System.currentTimeMillis())
                .apply()
            Log.i(TAG, "Published encrypted UUID directory record for ${normUuid.take(8)}…")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish UUID record: ${e.message}", e)
            false
        } finally {
            _isPublishing.value = false
        }
    }

    /**
     * Searches active P2P / Internet relays by UUID, decrypts the peer's public keys,
     * registers them immediately as an E2EE contact, and dispatches a mutual `[NEMIX_FRIEND_ADD:...]`
     * handshake over E2EE so both sides are immediately connected.
     */
    suspend fun lookupAndAddByUuid(
        context: Context,
        targetUuidRaw: String,
        myNickname: String
    ): Result<AddedInternetContact> {
        val appCtx = context.applicationContext
        val normTarget = normalizeUuid(targetUuidRaw)
        if (!isValidUuidFormat(normTarget)) {
            return Result.failure(IllegalArgumentException("有効なUUIDを入力してください"))
        }
        if (normTarget == normalizeUuid(_myUuid.value)) {
            return Result.failure(IllegalArgumentException("自分自身のUUIDは追加できません"))
        }

        // Ensure Internet relay transport is active so the lookup can reach P2P relays
        if (!InternetRelayPreferences.isInternetEnabled()) {
            InternetRelayPreferences.setInternetEnabled(appCtx, true)
            (appCtx as? android.app.Application)?.let { app ->
                runCatching { NostrBackgroundRuntime.initialize(app) }
            }
        }

        val relayMgr = NostrRelayManager.getInstance(appCtx)
        relayMgr.connect()

        // Also publish our own latest UUID record so the friend can verify/resolve us too
        runCatching { publishMyDirectoryRecord(appCtx, myNickname) }

        val lookupTag = directoryTagForUuid(normTarget)
        val filter = NostrFilter.Builder()
            .kinds(NIP78_APP_DATA_KIND)
            .tag("d", lookupTag)
            .limit(10)
            .build()

        val foundDeferred = CompletableDeferred<DirectoryRecordPayload>()
        val subId = "uuid-lookup-${UUID.randomUUID().toString().take(8)}"

        relayMgr.subscribe(
            filter = filter,
            id = subId,
            observeOnly = true,
            handler = { event ->
                try {
                    if (event.kind != NIP78_APP_DATA_KIND || !event.isValid()) return@subscribe
                    val decryptedJson = decryptForUuid(normTarget, event.content) ?: return@subscribe
                    val record = gson.fromJson(decryptedJson, DirectoryRecordPayload::class.java) ?: return@subscribe
                    if (normalizeUuid(record.uuid) != normTarget) return@subscribe
                    if (!ContactIdentityResolver.isNoiseKeyHex(record.noisePubHex)) return@subscribe
                    if (!foundDeferred.isCompleted) {
                        foundDeferred.complete(record)
                    }
                } catch (_: Exception) {
                }
            }
        )

        val resolved = try {
            withTimeoutOrNull(7_500L) {
                foundDeferred.await()
            }
        } finally {
            runCatching { relayMgr.unsubscribe(subId) }
        } ?: return Result.failure(
            IllegalStateException("該当するUUIDのユーザーが見つかりませんでした（相手がネット通信を一度ONにしてUUIDを公開しているか確認してください）")
        )

        val added = registerInternetContactLocally(
            context = appCtx,
            peerUuid = resolved.uuid,
            peerNickname = resolved.name,
            peerNoisePubHex = resolved.noisePubHex,
            peerNpubOrHex = resolved.npub.ifBlank { resolved.nostrPubHex },
            insertSystemNotice = "🌐 UUID検索 (${resolved.uuid.take(8)}…) から ${resolved.name} さんをネット経由で連絡先に追加しました。E2EE暗号化チャット・通話が可能です。"
        ) ?: return Result.failure(IllegalStateException("相手の公開鍵情報の登録に失敗しました"))

        // Simultaneously send E2EE mutual friend-add packet so peer adds us automatically!
        sendMutualFriendAddPacket(
            context = appCtx,
            targetConversationID = added.conversationID,
            targetNickname = added.nickname,
            myNickname = myNickname
        )

        return Result.success(added)
    }

    /**
     * Handles incoming `[NEMIX_FRIEND_ADD:...]` packet over Mesh or Nostr E2EE.
     */
    fun handleIncomingFriendAddPacket(
        context: Context,
        fallbackSenderName: String,
        senderNostrPubkey: String?,
        content: String
    ) {
        if (!isFriendAddPacket(content)) return
        val json = content.removePrefix(FRIEND_ADD_PREFIX).removeSuffix("]")
        val payload = runCatching {
            gson.fromJson(json, FriendAddPacketPayload::class.java)
        }.getOrNull() ?: return

        if (!ContactIdentityResolver.isNoiseKeyHex(payload.noisePubHex)) return
        val npub = payload.npub.ifBlank {
            payload.nostrPubHex.ifBlank { senderNostrPubkey.orEmpty() }
        }
        val peerName = payload.name.ifBlank { fallbackSenderName.ifBlank { "nemix user" } }

        registerInternetContactLocally(
            context = context.applicationContext,
            peerUuid = payload.uuid,
            peerNickname = peerName,
            peerNoisePubHex = payload.noisePubHex,
            peerNpubOrHex = npub,
            insertSystemNotice = "👋 $peerName さんがUUID検索からあなたを連絡先に追加しました（E2EE相互接続完了）"
        )
    }

    private fun registerInternetContactLocally(
        context: Context,
        peerUuid: String,
        peerNickname: String,
        peerNoisePubHex: String,
        peerNpubOrHex: String,
        insertSystemNotice: String?
    ): AddedInternetContact? {
        val noiseBytes = ContactIdentityResolver.bytesFromHex(peerNoisePubHex)
            ?.takeIf { it.size == 32 } ?: return null
        val normalizedNpub = ContactIdentityResolver.nostrPubkeyHex(peerNpubOrHex)
            ?.let { ContactIdentityResolver.npubFromHex(it) }
            ?: peerNpubOrHex
        val cleanName = peerNickname.trim().ifBlank { "Peer-${peerNoisePubHex.take(6)}" }

        FavoritesPersistenceService.initialize(context)
        FavoritesPersistenceService.shared.updateFavoriteStatus(noiseBytes, cleanName, true)
        if (normalizedNpub.isNotBlank()) {
            FavoritesPersistenceService.shared.updateNostrPublicKey(noiseBytes, normalizedNpub)
        }
        FavoritesPersistenceService.shared.updatePeerFavoritedUs(noiseBytes, true)

        val fingerprint = ContactIdentityResolver.fingerprintHex(noiseBytes)
        val conversationID = ContactIdentityResolver.contactConversationIdForNoiseKey(noiseBytes)
        val meshPeerAlias = ContactIdentityResolver.peerIdForNoiseKey(noiseBytes)

        // Persist in DataManager's `bitchat_prefs` "favorites" set as well
        runCatching {
            val prefs = context.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE)
            val currentFavs = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
            currentFavs.add(fingerprint)
            prefs.edit().putStringSet("favorites", currentFavs).apply()
        }

        // Cache identity & nickname in SecureIdentityStateManager so ContactDirectory resolves immediately
        runCatching {
            val idMgr = SecureIdentityStateManager(context)
            idMgr.cacheFingerprintNickname(fingerprint, cleanName)
            idMgr.cacheNoiseFingerprint(peerNoisePubHex.lowercase(), fingerprint)
            idMgr.cachePeerNoiseKey(meshPeerAlias, peerNoisePubHex.lowercase())
            idMgr.setVerifiedFingerprint(fingerprint, true)
        }

        runCatching {
            AppStateStore.updatePrivateConversationDisplayNames(mapOf(conversationID to cleanName))
        }

        if (!insertSystemNotice.isNullOrBlank()) {
            val msg = BitchatMessage(
                sender = "system",
                content = insertSystemNotice,
                timestamp = Date(),
                isRelay = false,
                isPrivate = true,
                senderPeerID = conversationID
            )
            AppStateStore.addPrivateMessage(conversationID, msg, forceRead = false)
        }

        return AddedInternetContact(
            uuid = peerUuid,
            nickname = cleanName,
            conversationID = conversationID,
            noisePubHex = peerNoisePubHex.lowercase(),
            npub = normalizedNpub
        )
    }

    private fun sendMutualFriendAddPacket(
        context: Context,
        targetConversationID: String,
        targetNickname: String,
        myNickname: String
    ) {
        try {
            val nostrId = NostrIdentityBridge.getCurrentNostrIdentity(context) ?: return
            val mesh = MeshServiceHolder.getUnifiedOrCreate(context)
            val myNoiseHex = mesh.getStaticNoisePublicKey()?.hexEncodedString()?.lowercase() ?: return
            val myUuidVal = normalizeUuid(_myUuid.value)

            val pkt = FriendAddPacketPayload(
                uuid = myUuidVal,
                name = myNickname.ifBlank { "nemix" },
                npub = nostrId.npub,
                nostrPubHex = nostrId.publicKeyHex.lowercase(),
                noisePubHex = myNoiseHex
            )
            val wire = "$FRIEND_ADD_PREFIX${gson.toJson(pkt)}]"
            val router = MessageRouter.getInstance(context, mesh)
            router.sendPrivate(
                wire,
                targetConversationID,
                targetNickname,
                UUID.randomUUID().toString().uppercase()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send mutual friend-add packet: ${e.message}")
        }
    }

    private fun generateRandomUuid(): String = UUID.randomUUID().toString().lowercase()

    private fun directoryTagForUuid(normalizedUuid: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("nemix-dir-tag-v1:$normalizedUuid").toByteArray(StandardCharsets.UTF_8))
        return "nemix-id:" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun deriveUuidKey(normalizedUuid: String): SecretKeySpec {
        val keyBytes = MessageDigest.getInstance("SHA-256")
            .digest(("nemix-uuid-v1:$normalizedUuid").toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun encryptForUuid(normalizedUuid: String, plaintext: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveUuidKey(normalizedUuid), GCMParameterSpec(128, iv))
        val cipherBytes = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decryptForUuid(normalizedUuid: String, encoded: String): String? {
        return try {
            val raw = Base64.decode(encoded, Base64.DEFAULT)
            if (raw.size <= 12) return null
            val iv = raw.copyOfRange(0, 12)
            val cipherBytes = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveUuidKey(normalizedUuid), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}

