package com.bitchat.android.contact

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.bitchat.android.directory.NemixUuidDirectoryManager
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.profile.UserProfileManager
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.util.hexEncodedString
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID

/**
 * 100% P2P Serverless Contact Exchange via QR & URL:
 * - Permanent QR / URL: Valid permanently, but can be revoked and regenerated anytime.
 *   Uses a local revision salt stored in SharedPreferences. Regenerating changes the salt,
 *   which immediately invalidates older QR codes / URLs.
 * - Time-Limited QR / URL: Preset expiration (10 min, 1 hr, 24 hr). Self-expires when current time > exp.
 * - Deep Link URI: `nemix://contact/add?v=1&name=...&pk=...&ed=...&uuid=...&type=perm|exp&exp=...&salt=...`
 * - Web URL: `https://nemix.im/c#v=1&name=...`
 */
object ContactExchangeManager {
    private const val TAG = "ContactExchangeManager"
    private const val PREFS_NAME = "nemix_contact_exchange_prefs"
    private const val KEY_PERMANENT_SALT = "permanent_salt"

    const val SCHEME_NEMIX = "nemix"
    const val HOST_CONTACT = "contact"
    const val PATH_ADD = "/add"
    const val WEB_INVITE_PREFIX = "https://nemix.im/c#"

    enum class InviteType {
        PERMANENT,
        EXPIRING
    }

    data class ContactInvitePayload(
        val version: Int = 1,
        val nickname: String,
        val nostrPubkeyHex: String,
        val edPublicKeyHex: String,
        val uuid: String,
        val type: InviteType,
        val createdAtMs: Long,
        val expiresAtMs: Long, // 0 for permanent
        val salt: String
    )

    sealed class ValidationResult {
        data class Valid(val payload: ContactInvitePayload) : ValidationResult()
        data class Expired(val expiredAtMs: Long) : ValidationResult()
        data class Revoked(val reason: String) : ValidationResult()
        data class Error(val message: String) : ValidationResult()
    }

    private var prefs: SharedPreferences? = null
    private val gson = Gson()
    private val secureRandom = SecureRandom()

    private val _permanentSalt = MutableStateFlow("")
    val permanentSalt: StateFlow<String> = _permanentSalt.asStateFlow()

    fun init(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        var salt = sp.getString(KEY_PERMANENT_SALT, null)
        if (salt.isNullOrBlank()) {
            salt = generateRandomSalt()
            sp.edit().putString(KEY_PERMANENT_SALT, salt).apply()
        }
        _permanentSalt.value = salt
    }

    private fun generateRandomSalt(): String {
        val bytes = ByteArray(8)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /**
     * Reissues (regenerates) the permanent invite salt, instantly revoking all previously shared permanent URLs/QRs.
     */
    fun regeneratePermanentInvite(context: Context): String {
        val sp = prefs ?: context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val newSalt = generateRandomSalt()
        sp.edit().putString(KEY_PERMANENT_SALT, newSalt).apply()
        _permanentSalt.value = newSalt
        Log.i(TAG, "Reissued permanent invite salt; older permanent URLs are now revoked.")
        return newSalt
    }

    /**
     * Builds an invite payload and returns both the `nemix://` deep link and `https://` web link.
     */
    fun createInvite(
        context: Context,
        type: InviteType,
        durationMs: Long = 3600_000L // 1 hour default for expiring
    ): ContactInvitePayload {
        init(context)
        val appCtx = context.applicationContext
        val nickname = AppStateStore.nickname.value.ifBlank {
            appCtx.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE)
                .getString("nickname", "nemix-user") ?: "nemix-user"
        }
        val nostrId = NostrIdentityBridge.getCurrentNostrIdentity(appCtx)
        val nostrPubkey = nostrId?.publicKeyHex.orEmpty()
        val edPubkey = runCatching {
            SecureIdentityStateManager(appCtx).loadSigningKey()?.second?.hexEncodedString().orEmpty()
        }.getOrDefault("")
        val uuid = NemixUuidDirectoryManager.myUuid.value.ifBlank {
            NemixUuidDirectoryManager.init(appCtx)
            NemixUuidDirectoryManager.myUuid.value
        }

        val now = System.currentTimeMillis()
        val (expiresAt, salt) = when (type) {
            InviteType.PERMANENT -> 0L to _permanentSalt.value
            InviteType.EXPIRING -> (now + durationMs) to generateRandomSalt()
        }

        return ContactInvitePayload(
            version = 1,
            nickname = nickname,
            nostrPubkeyHex = nostrPubkey,
            edPublicKeyHex = edPubkey,
            uuid = uuid,
            type = type,
            createdAtMs = now,
            expiresAtMs = expiresAt,
            salt = salt
        )
    }

    fun payloadToNemixUri(payload: ContactInvitePayload): String {
        val uri = Uri.Builder()
            .scheme(SCHEME_NEMIX)
            .authority(HOST_CONTACT)
            .path("add")
            .appendQueryParameter("v", payload.version.toString())
            .appendQueryParameter("name", payload.nickname)
            .appendQueryParameter("pk", payload.nostrPubkeyHex)
            .appendQueryParameter("ed", payload.edPublicKeyHex)
            .appendQueryParameter("uuid", payload.uuid)
            .appendQueryParameter("type", if (payload.type == InviteType.PERMANENT) "perm" else "exp")
            .appendQueryParameter("ts", payload.createdAtMs.toString())
            .appendQueryParameter("exp", payload.expiresAtMs.toString())
            .appendQueryParameter("salt", payload.salt)
            .build()
        return uri.toString()
    }

    fun payloadToWebUrl(payload: ContactInvitePayload): String {
        val json = gson.toJson(payload)
        val b64 = Base64.encodeToString(json.toByteArray(StandardCharsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)
        return "$WEB_INVITE_PREFIX$b64"
    }

    /**
     * Validates and parses a raw URL, deep link, or base64 QR payload.
     */
    fun validateAndParse(rawString: String): ValidationResult {
        val trimmed = rawString.trim()
        if (trimmed.isEmpty()) {
            return ValidationResult.Error("招待コードまたはURLが空です")
        }

        try {
            // Check if web format with base64 fragment
            if (trimmed.contains("#")) {
                val fragment = trimmed.substringAfter("#")
                val decodedJson = String(Base64.decode(fragment, Base64.URL_SAFE), StandardCharsets.UTF_8)
                val payload = gson.fromJson(decodedJson, ContactInvitePayload::class.java)
                return checkPayloadValidity(payload)
            }

            // Check if deep link uri
            val uri = Uri.parse(trimmed)
            if (uri.scheme.equals(SCHEME_NEMIX, ignoreCase = true) && uri.authority.equals(HOST_CONTACT, ignoreCase = true)) {
                val name = uri.getQueryParameter("name") ?: "nemix-user"
                val pk = uri.getQueryParameter("pk") ?: ""
                val ed = uri.getQueryParameter("ed") ?: ""
                val uuid = uri.getQueryParameter("uuid") ?: ""
                val typeStr = uri.getQueryParameter("type") ?: "perm"
                val ts = uri.getQueryParameter("ts")?.toLongOrNull() ?: System.currentTimeMillis()
                val exp = uri.getQueryParameter("exp")?.toLongOrNull() ?: 0L
                val salt = uri.getQueryParameter("salt") ?: ""

                val payload = ContactInvitePayload(
                    version = uri.getQueryParameter("v")?.toIntOrNull() ?: 1,
                    nickname = name,
                    nostrPubkeyHex = pk,
                    edPublicKeyHex = ed,
                    uuid = uuid,
                    type = if (typeStr == "exp") InviteType.EXPIRING else InviteType.PERMANENT,
                    createdAtMs = ts,
                    expiresAtMs = exp,
                    salt = salt
                )
                return checkPayloadValidity(payload)
            }

            // Fallback: try raw json
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                val payload = gson.fromJson(trimmed, ContactInvitePayload::class.java)
                return checkPayloadValidity(payload)
            }

            return ValidationResult.Error("nemixの連絡先交換URL形式ではありません")
        } catch (e: Exception) {
            return ValidationResult.Error("URLの解析に失敗しました: ${e.message}")
        }
    }

    private fun checkPayloadValidity(payload: ContactInvitePayload): ValidationResult {
        if (payload.nostrPubkeyHex.isBlank() && payload.edPublicKeyHex.isBlank() && payload.uuid.isBlank()) {
            return ValidationResult.Error("暗号鍵情報が含まれていません")
        }

        // Check expiration for time-limited invites
        if (payload.type == InviteType.EXPIRING && payload.expiresAtMs > 0L) {
            val now = System.currentTimeMillis()
            if (now > payload.expiresAtMs) {
                return ValidationResult.Expired(payload.expiresAtMs)
            }
        }

        return ValidationResult.Valid(payload)
    }

    /**
     * Executes contact registration on the local device and dispatches mutual friend handshake.
     */
    fun registerContactFromPayload(context: Context, payload: ContactInvitePayload): Boolean {
        return try {
            val appCtx = context.applicationContext
            val peerKeyHex = payload.edPublicKeyHex.ifBlank { payload.nostrPubkeyHex }
            val keyBytes = ContactIdentityResolver.bytesFromHex(peerKeyHex)
                ?: ContactIdentityResolver.bytesFromHex(payload.nostrPubkeyHex)
            val cleanName = payload.nickname.trim().ifBlank { "nemix-friend" }

            if (keyBytes != null && keyBytes.size == 32) {
                FavoritesPersistenceService.initialize(appCtx)
                FavoritesPersistenceService.shared.updateFavoriteStatus(keyBytes, cleanName, true)
                if (payload.nostrPubkeyHex.isNotBlank()) {
                    val npub = ContactIdentityResolver.npubFromHex(payload.nostrPubkeyHex)
                        ?: payload.nostrPubkeyHex
                    FavoritesPersistenceService.shared.updateNostrPublicKey(keyBytes, npub)
                }
                FavoritesPersistenceService.shared.updatePeerFavoritedUs(keyBytes, true)

                val fingerprint = ContactIdentityResolver.fingerprintHex(keyBytes)
                runCatching {
                    val prefs = appCtx.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE)
                    val currentFavs = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
                    currentFavs.add(fingerprint)
                    prefs.edit().putStringSet("favorites", currentFavs).apply()
                }

                runCatching {
                    val idMgr = SecureIdentityStateManager(appCtx)
                    idMgr.cacheFingerprintNickname(fingerprint, cleanName)
                }
            }

            // Also register in UUID directory if provided
            if (payload.uuid.isNotBlank() && com.bitchat.android.net.InternetRelayPreferences.isInternetEnabled()) {
                val nick = AppStateStore.nickname.value.ifBlank { "nemix-user" }
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching {
                        NemixUuidDirectoryManager.lookupAndAddByUuid(
                            context = appCtx,
                            targetUuidRaw = payload.uuid,
                            myNickname = nick
                        )
                    }
                }
            }
            Log.i(TAG, "Registered contact from invite: ${payload.nickname} (${payload.uuid})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register contact: ${e.message}", e)
            false
        }
    }

    /**
     * Generates a sharp QR code bitmap with black modules on white background.
     */
    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate QR code bitmap: ${e.message}", e)
            null
        }
    }
}
