package com.bitchat.android.groups

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.features.media.ImageUtils
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.net.InternetRelayPreferences
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrFilter
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrKind
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.security.ChatSecurityPolicyManager
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.MessageRouter
import com.bitchat.android.util.hexEncodedString
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class P2PGroupStyle {
    STREAM, // Stream-format unified message bubble stream
    CHANNELS   // Channel-format server with categorized #channels & flat feed
}

data class P2PGroupChannel(
    val id: String,
    val name: String,
    val category: String = "テキストチャンネル",
    val topic: String = ""
)

data class P2PGroupMember(
    val id: String,           // canonical fingerprint or mesh peer ID
    val nickname: String,
    val noiseKeyHex: String? = null,
    val npub: String? = null,
    val role: String = "MEMBER", // "OWNER", "ADMIN", "MEMBER"
    val joinedAt: Long = System.currentTimeMillis()
)

data class P2PGroup(
    val id: String,
    val name: String,
    val description: String = "",
    val style: P2PGroupStyle = P2PGroupStyle.STREAM,
    val groupKeyBase64: String,
    val creatorId: String,
    val channels: List<P2PGroupChannel>,
    val members: List<P2PGroupMember>,
    val pinnedNotice: String? = null,
    val screenshotBlocked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class P2PGroupMessage(
    val id: String,
    val groupId: String,
    val channelId: String = "general",
    val senderId: String,
    val senderName: String,
    val senderNoiseHex: String? = null,
    val content: String,       // text, location payload, or local image file path
    val type: String = "TEXT", // "TEXT", "LOCATION", "IMAGE", "SYSTEM"
    val replyToId: String? = null,
    val replyPreview: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 100% Serverless P2P End-to-End Encrypted Group Engine for nemix.
 *
 * Supports switching per-group between:
 * - [P2PGroupStyle.STREAM]: Stream-format message bubble chat
 * - [P2PGroupStyle.CHANNELS]: Channel-format server with categorized `#channels` & member roles
 *
 * Transport is strictly P2P (zero central group server):
 * 1. Pairwise Noise / NIP-17 Gift-Wrap E2EE fan-out via [MessageRouter]
 * 2. Local BLE / Wi-Fi Aware opaque AES-256-GCM multicast (`[NEMIX_GRP_MCAST:...]`)
 * 3. Zero-knowledge capability-tagged ephemeral Nostr relay multicast (sender-anonymous)
 */
object P2PGroupManager {
    private const val TAG = "P2PGroupManager"
    private const val STORAGE_FILE_NAME = "nemix_p2p_groups_v1.enc"
    private const val SECURE_STORAGE_KEY_NAME = "nemix_local_groups_aes_key"
    private const val MAX_MESSAGES_PER_GROUP = 600

    private const val INVITE_PACKET_PREFIX = "[NEMIX_GRP_INVITE:"
    private const val GROUP_PKT_PREFIX = "[NEMIX_GRP_PKT:"
    private const val GROUP_MCAST_PREFIX = "[NEMIX_GRP_MCAST:"
    private const val RELAY_SUB_ID = "nemix-p2p-groups-sub"

    // Unknown or legacy style values (older builds / other clients) fall back to STREAM instead of
    // leaving a null in a non-null Kotlin field. Wire names "STREAM"/"CHANNELS" match the iOS client.
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(
            P2PGroupStyle::class.java,
            JsonDeserializer { json, _, _ ->
                runCatching { P2PGroupStyle.valueOf(json.asString) }.getOrDefault(P2PGroupStyle.STREAM)
            }
        )
        .create()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val seenEnvelopeIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var appContext: Context? = null

    private val _groups = MutableStateFlow<List<P2PGroup>>(emptyList())
    val groups: StateFlow<List<P2PGroup>> = _groups.asStateFlow()

    /** Shareable invite links: https://nemi-x.com/g#<code>. The fragment never reaches the server. */
    const val WEB_INVITE_PREFIX = "https://nemi-x.com/g#"

    /** An invitation the user has not accepted yet (received directly or opened from a link). */
    data class PendingGroupInvite(
        val id: String,
        val groupId: String,
        val groupName: String,
        val inviterName: String,
        val memberCount: Int,
        internal val payloadJson: String
    )

    // Invitations are never joined automatically: anyone could otherwise add you to a group.
    private val _pendingInvites = MutableStateFlow<List<PendingGroupInvite>>(emptyList())
    val pendingInvites: StateFlow<List<PendingGroupInvite>> = _pendingInvites.asStateFlow()

    // groupId -> chronological list of messages across all channels in that group
    private val _groupMessages = MutableStateFlow<Map<String, List<P2PGroupMessage>>>(emptyMap())
    val groupMessages: StateFlow<Map<String, List<P2PGroupMessage>>> = _groupMessages.asStateFlow()

    // groupId -> unread count
    private val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadCounts: StateFlow<Map<String, Int>> = _unreadCounts.asStateFlow()

    @Volatile
    private var currentlyOpenGroupId: String? = null

    private data class PersistedGroupStore(
        val groups: List<P2PGroup> = emptyList(),
        val messages: Map<String, List<P2PGroupMessage>> = emptyMap(),
        val unreadCounts: Map<String, Int> = emptyMap()
    )

    private data class GroupInvitePayload(
        val v: Int = 1,
        val group: P2PGroup,
        val inviterName: String
    )

    private data class GroupWireEnvelope(
        val v: Int = 1,
        val envId: String = UUID.randomUUID().toString(),
        val groupId: String,
        val action: String, // "MSG", "IMAGE", "STYLE", "CHANNEL_ADD", "POLICY", "PIN", "DELETE_MSG", "JOIN", "LEAVE", "SYNC_META"
        val channelId: String = "general",
        val senderId: String,
        val senderName: String,
        val senderNoiseHex: String? = null,
        val senderNpub: String? = null,
        val messageId: String? = null,
        val text: String? = null,
        val msgType: String? = null,
        val imageB64: String? = null,
        val replyToId: String? = null,
        val replyPreview: String? = null,
        val style: String? = null,
        val channel: P2PGroupChannel? = null,
        val ssBlock: Boolean? = null,
        val groupSnapshot: P2PGroup? = null,
        val ts: Long = System.currentTimeMillis()
    )

    fun init(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        loadFromEncryptedDisk(appCtx)

        ChatSecurityPolicyManager.groupScreenshotBlockChecker = { groupId ->
            _groups.value.firstOrNull { it.id == groupId }?.screenshotBlocked == true
        }

        refreshRelayGroupSubscriptions(appCtx)
    }

    fun myMemberIdentity(context: Context, myNickname: String): P2PGroupMember {
        val appCtx = context.applicationContext
        val mesh = MeshServiceHolder.getUnifiedOrCreate(appCtx)
        val noiseBytes = mesh.getStaticNoisePublicKey()
        val noiseHex = noiseBytes?.hexEncodedString()?.lowercase()
        val myId = noiseBytes?.let { ContactIdentityResolver.fingerprintHex(it) } ?: mesh.myPeerID
        val npub = runCatching { NostrIdentityBridge.getCurrentNostrIdentity(appCtx)?.npub }.getOrNull()
        return P2PGroupMember(
            id = myId,
            nickname = myNickname.ifBlank { "nemix" },
            noiseKeyHex = noiseHex,
            npub = npub,
            role = "MEMBER"
        )
    }

    fun setOpenGroup(groupId: String?) {
        currentlyOpenGroupId = groupId
        if (groupId != null) {
            markGroupRead(groupId)
        }
    }

    fun markGroupRead(groupId: String) {
        val current = _unreadCounts.value
        if ((current[groupId] ?: 0) == 0) return
        val updated = current.toMutableMap()
        updated.remove(groupId)
        _unreadCounts.value = updated
        appContext?.let { saveToEncryptedDisk(it) }
    }

    /**
     * Creates a new 100% P2P E2EE group with selectable Stream or Channel format
     * and dispatches E2EE invites to all selected initial members.
     */
    fun createGroup(
        context: Context,
        name: String,
        description: String,
        style: P2PGroupStyle,
        screenshotBlocked: Boolean,
        myNickname: String,
        initialPeerTargets: List<String>
    ): P2PGroup {
        val appCtx = context.applicationContext
        val groupId = "grp_" + UUID.randomUUID().toString().replace("-", "").take(20)
        val keyBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val keyB64 = Base64.encodeToString(keyBytes, Base64.NO_WRAP)

        val meOwner = myMemberIdentity(appCtx, myNickname).copy(role = "OWNER")
        val resolvedMembers = mutableListOf(meOwner)

        initialPeerTargets.distinct().forEach { peerTarget ->
            val member = buildMemberFromPeerTarget(appCtx, peerTarget)
            if (member != null && resolvedMembers.none { it.id.equals(member.id, ignoreCase = true) }) {
                resolvedMembers.add(member)
            }
        }

        val defaultChannels = listOf(
            P2PGroupChannel(id = "general", name = "一般", category = "テキストチャンネル", topic = "メインの会話チャンネル"),
            P2PGroupChannel(id = "announcements", name = "お知らせ", category = "インフォメーション", topic = "重要連絡・アナウンス"),
            P2PGroupChannel(id = "random", name = "雑談・共有", category = "テキストチャンネル", topic = "自由な話題・位置情報・画像共有")
        )

        val now = System.currentTimeMillis()
        val group = P2PGroup(
            id = groupId,
            name = name.trim().ifBlank { "P2Pグループ" },
            description = description.trim(),
            style = style,
            groupKeyBase64 = keyB64,
            creatorId = meOwner.id,
            channels = defaultChannels,
            members = resolvedMembers,
            pinnedNotice = description.trim().takeIf { it.isNotBlank() },
            screenshotBlocked = screenshotBlocked,
            createdAt = now,
            updatedAt = now
        )

        synchronized(this) {
            _groups.value = listOf(group) + _groups.value.filterNot { it.id == group.id }
        }

        val styleLabel = if (style == P2PGroupStyle.CHANNELS) "チャンネル形式 (トピック別)" else "ストリーム形式 (単一スレッド)"
        appendLocalSystemMessage(
            groupId = group.id,
            text = "🔐 完全P2P暗号化グループ「${group.name}」を作成しました（$styleLabel · AES-256-GCM）"
        )
        if (screenshotBlocked) {
            appendLocalSystemMessage(
                groupId = group.id,
                text = "🔒 グループ全体のスクリーンショット禁止が有効です（参加者全員の画面に適用）"
            )
        }

        saveToEncryptedDisk(appCtx)
        refreshRelayGroupSubscriptions(appCtx)

        // Dispatch pairwise E2EE invites to all initial members
        initialPeerTargets.distinct().forEach { target ->
            sendDirectGroupInvite(appCtx, group, target, myNickname)
        }

        return group
    }

    /**
     * Invites an existing contact or nearby mesh peer directly over pairwise E2EE.
     */
    fun invitePeerToGroup(
        context: Context,
        groupId: String,
        peerTarget: String,
        myNickname: String
    ): MessageRouter.RouteResult {
        val appCtx = context.applicationContext
        val group = _groups.value.firstOrNull { it.id == groupId } ?: return MessageRouter.RouteResult.DROPPED
        val newMember = buildMemberFromPeerTarget(appCtx, peerTarget) ?: return MessageRouter.RouteResult.DROPPED

        val updatedMembers = if (group.members.any { it.id.equals(newMember.id, ignoreCase = true) }) {
            group.members
        } else {
            group.members + newMember
        }
        val updatedGroup = group.copy(members = updatedMembers, updatedAt = System.currentTimeMillis())
        replaceGroup(appCtx, updatedGroup)

        val route = sendDirectGroupInvite(appCtx, updatedGroup, peerTarget, myNickname)

        // Notify existing members of updated roster
        val me = myMemberIdentity(appCtx, myNickname)
        dispatchGroupEnvelope(
            context = appCtx,
            group = updatedGroup,
            envelope = GroupWireEnvelope(
                groupId = updatedGroup.id,
                action = "SYNC_META",
                senderId = me.id,
                senderName = me.nickname,
                senderNoiseHex = me.noiseKeyHex,
                senderNpub = me.npub,
                groupSnapshot = updatedGroup
            )
        )
        appendLocalSystemMessage(updatedGroup.id, "👋 ${newMember.nickname} さんをグループに招待しました")
        return route
    }

    /**
     * Generates a shareable invite link (`https://nemi-x.com/g#...`) containing the group key and
     * metadata. The secret part is in the URL fragment, which browsers never send to the server.
     */
    fun buildInviteUri(groupId: String, inviterNickname: String): String? {
        val group = _groups.value.firstOrNull { it.id == groupId } ?: return null
        val payload = GroupInvitePayload(group = group, inviterName = inviterNickname)
        val b64 = Base64.encodeToString(
            gson.toJson(payload).toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return WEB_INVITE_PREFIX + b64
    }

    /** True for group invite links this app can open (https://nemi-x.com/g#…, nemix://group…). */
    fun isInviteLink(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return (scheme == "nemix" && host == "group") ||
            (scheme == "https" && (host == "nemi-x.com" || host == "www.nemi-x.com") &&
                uri.path?.trimEnd('/') == "/g" && !uri.fragment.isNullOrBlank())
    }

    /**
     * Parses any supported invite form: https://nemi-x.com/g#code, nemix://group#code,
     * legacy nemix://group?d=code, or the raw code (URL-safe or standard Base64).
     */
    private fun parseInvite(rawInvite: String): GroupInvitePayload {
        val trimmed = rawInvite.trim()
        val encoded = when {
            trimmed.contains("#") -> trimmed.substringAfter("#")
            trimmed.startsWith("nemix://group", ignoreCase = true) ->
                Uri.parse(trimmed).getQueryParameter("d")
                    ?: throw IllegalArgumentException("招待リンクの形式が不正です")
            else -> trimmed
        }.trim()
        val bytes = runCatching {
            Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrElse { Base64.decode(encoded, Base64.DEFAULT) }
        val invite = gson.fromJson(String(bytes, StandardCharsets.UTF_8), GroupInvitePayload::class.java)
            ?: throw IllegalArgumentException("招待データを解析できませんでした")
        if (sanitizeGroups(listOf(invite.group)).isEmpty()) {
            throw IllegalArgumentException("招待データが壊れているか、グループ暗号鍵が含まれていません")
        }
        return invite
    }

    /** Queues an invite opened from a link so the user can confirm before joining. */
    fun offerInviteLink(rawInvite: String): Result<PendingGroupInvite> = runCatching {
        val invite = parseInvite(rawInvite)
        addPendingInvite(invite, invite.inviterName)
    }

    private fun addPendingInvite(invite: GroupInvitePayload, fallbackInviter: String): PendingGroupInvite {
        val pending = PendingGroupInvite(
            id = invite.group.id,
            groupId = invite.group.id,
            groupName = invite.group.name,
            inviterName = invite.inviterName.ifBlank { fallbackInviter },
            memberCount = invite.group.members.size,
            payloadJson = gson.toJson(invite)
        )
        _pendingInvites.value = _pendingInvites.value.filterNot { it.id == pending.id } + pending
        return pending
    }

    fun acceptPendingInvite(context: Context, inviteId: String, myNickname: String): Result<P2PGroup> {
        val pending = _pendingInvites.value.firstOrNull { it.id == inviteId }
            ?: return Result.failure(IllegalArgumentException("招待が見つかりません"))
        _pendingInvites.value = _pendingInvites.value.filterNot { it.id == inviteId }
        return runCatching { gson.fromJson(pending.payloadJson, GroupInvitePayload::class.java) }
            .fold(
                onSuccess = { joinFromInvite(context, it, myNickname) },
                onFailure = { Result.failure(IllegalArgumentException("招待データを解析できませんでした")) }
            )
    }

    fun declinePendingInvite(inviteId: String) {
        _pendingInvites.value = _pendingInvites.value.filterNot { it.id == inviteId }
    }

    /**
     * Joins a P2P group from an invite link or raw invite code (explicit user action).
     */
    fun joinGroupFromInviteCode(
        context: Context,
        rawInvite: String,
        myNickname: String
    ): Result<P2PGroup> {
        val invite = try {
            parseInvite(rawInvite)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("招待コードが無効です: ${e.message}"))
        }
        _pendingInvites.value = _pendingInvites.value.filterNot { it.id == invite.group.id }
        return joinFromInvite(context, invite, myNickname)
    }

    private fun joinFromInvite(
        context: Context,
        invite: GroupInvitePayload,
        myNickname: String
    ): Result<P2PGroup> {
        val appCtx = context.applicationContext
        return try {
            val incomingGroup = invite.group
            if (incomingGroup.id.isBlank() || incomingGroup.groupKeyBase64.isBlank()) {
                return Result.failure(IllegalArgumentException("グループ暗号鍵が含まれていません"))
            }

            val me = myMemberIdentity(appCtx, myNickname)
            val mergedMembers = incomingGroup.members
                .filterNot { it.id.equals(me.id, ignoreCase = true) } + me
            val joinedGroup = incomingGroup.copy(
                members = mergedMembers,
                updatedAt = System.currentTimeMillis()
            )

            // Register known members' Noise<->Nostr mappings so pairwise P2P routing works immediately
            registerMemberIdentities(appCtx, joinedGroup.members, me.id)

            replaceGroup(appCtx, joinedGroup)
            refreshRelayGroupSubscriptions(appCtx)

            appendLocalSystemMessage(
                joinedGroup.id,
                "🔐 招待コードからP2Pグループ「${joinedGroup.name}」に参加しました"
            )

            // Announce our join to all group peers via P2P
            dispatchGroupEnvelope(
                context = appCtx,
                group = joinedGroup,
                envelope = GroupWireEnvelope(
                    groupId = joinedGroup.id,
                    action = "JOIN",
                    senderId = me.id,
                    senderName = me.nickname,
                    senderNoiseHex = me.noiseKeyHex,
                    senderNpub = me.npub
                )
            )

            Result.success(joinedGroup)
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("招待コードが無効です: ${e.message}"))
        }
    }

    /**
     * Switches a group between Stream format and Channel format and syncs to all P2P members.
     */
    fun updateGroupStyle(
        context: Context,
        groupId: String,
        newStyle: P2PGroupStyle,
        myNickname: String
    ) {
        val appCtx = context.applicationContext
        val group = _groups.value.firstOrNull { it.id == groupId } ?: return
        if (group.style == newStyle) return

        val updated = group.copy(style = newStyle, updatedAt = System.currentTimeMillis())
        replaceGroup(appCtx, updated)

        val me = myMemberIdentity(appCtx, myNickname)
        dispatchGroupEnvelope(
            context = appCtx,
            group = updated,
            envelope = GroupWireEnvelope(
                groupId = updated.id,
                action = "STYLE",
                senderId = me.id,
                senderName = me.nickname,
                style = newStyle.name
            )
        )

        val label = if (newStyle == P2PGroupStyle.CHANNELS) "チャンネル形式 (トピック別表示)" else "ストリーム形式 (単一スレッド表示)"
        appendLocalSystemMessage(groupId, "🎨 $myNickname さんがグループ表示を「$label」に切り替えました")
    }

    /**
     * Adds a new `#channel` (used in Channel format, also preserved across styles) and syncs P2P.
     */
    fun addGroupChannel(
        context: Context,
        groupId: String,
        channelNameRaw: String,
        categoryRaw: String,
        topicRaw: String,
        myNickname: String
    ): P2PGroupChannel? {
        val appCtx = context.applicationContext
        val group = _groups.value.firstOrNull { it.id == groupId } ?: return null
        val cleanName = channelNameRaw.trim().removePrefix("#").trim()
        if (cleanName.isBlank()) return null

        val slug = "ch_" + UUID.randomUUID().toString().replace("-", "").take(10)
        val newChannel = P2PGroupChannel(
            id = slug,
            name = cleanName,
            category = categoryRaw.trim().ifBlank { "テキストチャンネル" },
            topic = topicRaw.trim()
        )
        val updated = group.copy(
            channels = group.channels + newChannel,
            updatedAt = System.currentTimeMillis()
        )
        replaceGroup(appCtx, updated)

        val me = myMemberIdentity(appCtx, myNickname)
        dispatchGroupEnvelope(
            context = appCtx,
            group = updated,
            envelope = GroupWireEnvelope(
                groupId = updated.id,
                action = "CHANNEL_ADD",
                channelId = newChannel.id,
                senderId = me.id,
                senderName = me.nickname,
                channel = newChannel
            )
        )
        appendLocalSystemMessage(groupId, "➕ チャンネル #${newChannel.name} が追加されました", channelId = newChannel.id)
        return newChannel
    }

    /**
     * Toggles mutual screenshot blocking for the entire group (enforced on ALL members' devices).
     */
    fun setGroupScreenshotBlocked(
        context: Context,
        groupId: String,
        blocked: Boolean,
        myNickname: String
    ) {
        val appCtx = context.applicationContext
        val group = _groups.value.firstOrNull { it.id == groupId } ?: return
        if (group.screenshotBlocked == blocked) return

        val updated = group.copy(screenshotBlocked = blocked, updatedAt = System.currentTimeMillis())
        replaceGroup(appCtx, updated)

        val me = myMemberIdentity(appCtx, myNickname)
        dispatchGroupEnvelope(
            context = appCtx,
            group = updated,
            envelope = GroupWireEnvelope(
                groupId = updated.id,
                action = "POLICY",
                senderId = me.id,
                senderName = me.nickname,
                ssBlock = blocked
            )
        )

        val notice = if (blocked) {
            "🔒 $myNickname さんがグループのスクリーンショット禁止をONにしました（メンバー全員の画面が保護されます）"
        } else {
            "🔓 $myNickname さんがグループのスクリーンショット禁止をOFFにしました"
        }
        appendLocalSystemMessage(groupId, notice)
    }

    /**
     * Updates pinned announcement banner (Stream / Channel announcement bar) and syncs P2P.
     */
    fun setPinnedNotice(
        context: Context,
        groupId: String,
        noticeText: String?,
        myNickname: String
    ) {
        val appCtx = context.applicationContext
        val group = _groups.value.firstOrNull { it.id == groupId } ?: return
        val clean = noticeText?.trim()?.takeIf { it.isNotBlank() }
        val updated = group.copy(pinnedNotice = clean, updatedAt = System.currentTimeMillis())
        replaceGroup(appCtx, updated)

        val me = myMemberIdentity(appCtx, myNickname)
        dispatchGroupEnvelope(
            context = appCtx,
            group = updated,
            envelope = GroupWireEnvelope(
                groupId = updated.id,
                action = "PIN",
                senderId = me.id,
                senderName = me.nickname,
                text = clean ?: ""
            )
        )
    }

    /**
     * Sends an E2EE text or location message to a group channel.
     */
    fun sendGroupTextMessage(
        context: Context,
        groupId: String,
        channelId: String,
        content: String,
        myNickname: String,
        isLocation: Boolean = false,
        replyTo: P2PGroupMessage? = null
    ): P2PGroupMessage? {
        val appCtx = context.applicationContext
        val group = _groups.value.firstOrNull { it.id == groupId } ?: return null
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null

        val me = myMemberIdentity(appCtx, myNickname)
        val msgId = UUID.randomUUID().toString().uppercase()
        val now = System.currentTimeMillis()
        val msgType = if (isLocation) "LOCATION" else "TEXT"

        val localMsg = P2PGroupMessage(
            id = msgId,
            groupId = groupId,
            channelId = channelId.ifBlank { "general" },
            senderId = me.id,
            senderName = me.nickname,
            senderNoiseHex = me.noiseKeyHex,
            content = trimmed,
            type = msgType,
            replyToId = replyTo?.id,
            replyPreview = replyTo?.let { "${it.senderName}: ${it.content.take(40)}" },
            timestamp = now
        )

        appendGroupMessage(appCtx, localMsg, incrementUnread = false)

        dispatchGroupEnvelope(
            context = appCtx,
            group = group,
            envelope = GroupWireEnvelope(
                groupId = groupId,
                action = "MSG",
                channelId = localMsg.channelId,
                senderId = me.id,
                senderName = me.nickname,
                senderNoiseHex = me.noiseKeyHex,
                senderNpub = me.npub,
                messageId = msgId,
                text = trimmed,
                msgType = msgType,
                replyToId = localMsg.replyToId,
                replyPreview = localMsg.replyPreview,
                ts = now
            )
        )
        return localMsg
    }

    /**
     * Compresses an image cleanly (EXIF stripped, WebP/JPEG <= ~42 KB) and sends it E2EE to all P2P group members.
     */
    fun sendGroupImageMessage(
        context: Context,
        groupId: String,
        channelId: String,
        sourcePath: String,
        myNickname: String
    ) {
        val appCtx = context.applicationContext
        val group = _groups.value.firstOrNull { it.id == groupId } ?: return
        scope.launch {
            try {
                val srcBmp = ImageUtils.loadBitmapWithExifOrientation(sourcePath) ?: return@launch
                val scaled = scaleDownBitmap(srcBmp, maxDim = 720)
                val compressedBytes = compressBitmapCompact(scaled)

                val mediaDir = File(appCtx.filesDir, "group_media").apply { mkdirs() }
                val msgId = UUID.randomUUID().toString().uppercase()
                val localFile = File(mediaDir, "grp_${msgId.lowercase()}.webp")
                FileOutputStream(localFile).use { it.write(compressedBytes) }

                val me = myMemberIdentity(appCtx, myNickname)
                val now = System.currentTimeMillis()
                val localMsg = P2PGroupMessage(
                    id = msgId,
                    groupId = groupId,
                    channelId = channelId.ifBlank { "general" },
                    senderId = me.id,
                    senderName = me.nickname,
                    senderNoiseHex = me.noiseKeyHex,
                    content = localFile.absolutePath,
                    type = "IMAGE",
                    timestamp = now
                )
                appendGroupMessage(appCtx, localMsg, incrementUnread = false)

                val b64 = Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
                dispatchGroupEnvelope(
                    context = appCtx,
                    group = group,
                    envelope = GroupWireEnvelope(
                        groupId = groupId,
                        action = "IMAGE",
                        channelId = localMsg.channelId,
                        senderId = me.id,
                        senderName = me.nickname,
                        senderNoiseHex = me.noiseKeyHex,
                        senderNpub = me.npub,
                        messageId = msgId,
                        imageB64 = b64,
                        ts = now
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send group image: ${e.message}", e)
            }
        }
    }

    /**
     * Deletes a single message from a group locally, and optionally across all P2P group members.
     */
    fun deleteGroupMessage(
        context: Context,
        groupId: String,
        messageId: String,
        forEveryone: Boolean,
        myNickname: String
    ) {
        val appCtx = context.applicationContext
        removeGroupMessageLocally(appCtx, groupId, messageId)
        if (forEveryone) {
            val group = _groups.value.firstOrNull { it.id == groupId } ?: return
            val me = myMemberIdentity(appCtx, myNickname)
            dispatchGroupEnvelope(
                context = appCtx,
                group = group,
                envelope = GroupWireEnvelope(
                    groupId = groupId,
                    action = "DELETE_MSG",
                    senderId = me.id,
                    senderName = me.nickname,
                    messageId = messageId
                )
            )
        }
    }

    /**
     * Leaves and removes a P2P group from this device (notifying members over P2P).
     */
    fun leaveAndDeleteGroup(
        context: Context,
        groupId: String,
        myNickname: String
    ) {
        val appCtx = context.applicationContext
        val group = _groups.value.firstOrNull { it.id == groupId }
        if (group != null) {
            val me = myMemberIdentity(appCtx, myNickname)
            dispatchGroupEnvelope(
                context = appCtx,
                group = group,
                envelope = GroupWireEnvelope(
                    groupId = groupId,
                    action = "LEAVE",
                    senderId = me.id,
                    senderName = me.nickname
                )
            )
        }
        synchronized(this) {
            _groups.value = _groups.value.filterNot { it.id == groupId }
            val msgs = _groupMessages.value.toMutableMap()
            msgs.remove(groupId)
            _groupMessages.value = msgs
            val unread = _unreadCounts.value.toMutableMap()
            unread.remove(groupId)
            _unreadCounts.value = unread
        }
        saveToEncryptedDisk(appCtx)
        refreshRelayGroupSubscriptions(appCtx)
    }

    /**
     * Clears local message history of a group without leaving the group.
     */
    fun clearGroupHistoryLocally(context: Context, groupId: String) {
        val appCtx = context.applicationContext
        synchronized(this) {
            val msgs = _groupMessages.value.toMutableMap()
            msgs[groupId] = emptyList()
            _groupMessages.value = msgs
            val unread = _unreadCounts.value.toMutableMap()
            unread.remove(groupId)
            _unreadCounts.value = unread
        }
        saveToEncryptedDisk(appCtx)
    }

    // ---- Incoming Wire Interception ----

    fun isGroupPacket(content: String): Boolean =
        (content.startsWith(INVITE_PACKET_PREFIX) ||
            content.startsWith(GROUP_PKT_PREFIX) ||
            content.startsWith(GROUP_MCAST_PREFIX)) && content.endsWith("]")

    fun handleIncomingGroupPacket(
        context: Context,
        senderPeerID: String,
        senderNickname: String,
        content: String
    ) {
        val appCtx = context.applicationContext
        appContext = appCtx

        when {
            content.startsWith(INVITE_PACKET_PREFIX) && content.endsWith("]") -> {
                val b64 = content.removePrefix(INVITE_PACKET_PREFIX).removeSuffix("]")
                val json = runCatching {
                    String(Base64.decode(b64, Base64.NO_WRAP), StandardCharsets.UTF_8)
                }.getOrNull() ?: return
                val invite = runCatching {
                    gson.fromJson(json, GroupInvitePayload::class.java)
                }.getOrNull() ?: return
                val incoming = invite.group
                if (sanitizeGroups(listOf(incoming)).isEmpty()) return

                // Not a member yet: ask the user instead of joining automatically.
                if (_groups.value.none { it.id == incoming.id }) {
                    addPendingInvite(invite, senderNickname)
                    return
                }

                val myNick = com.bitchat.android.services.AppStateStore.nickname.value.ifBlank { "nemix" }
                val me = myMemberIdentity(appCtx, myNick)
                registerMemberIdentities(appCtx, incoming.members, me.id)

                val existing = _groups.value.firstOrNull { it.id == incoming.id }
                val mergedMembers = (incoming.members + (existing?.members ?: emptyList()) + me)
                    .distinctBy { it.id.lowercase() }
                val mergedGroup = incoming.copy(
                    members = mergedMembers,
                    updatedAt = System.currentTimeMillis()
                )
                replaceGroup(appCtx, mergedGroup)
                refreshRelayGroupSubscriptions(appCtx)

                if (existing == null) {
                    appendLocalSystemMessage(
                        mergedGroup.id,
                        "🎉 ${invite.inviterName.ifBlank { senderNickname }} さんからP2Pグループ「${mergedGroup.name}」に招待されました",
                        incrementUnread = true
                    )
                }
            }

            content.startsWith(GROUP_PKT_PREFIX) && content.endsWith("]") -> {
                val body = content.removePrefix(GROUP_PKT_PREFIX).removeSuffix("]")
                val sep = body.indexOf(':')
                if (sep <= 0) return
                val groupId = body.substring(0, sep)
                val cipherB64 = body.substring(sep + 1)
                val group = _groups.value.firstOrNull { it.id == groupId } ?: return
                val decrypted = decryptWithGroupKey(group.groupKeyBase64, cipherB64) ?: return
                processDecryptedEnvelope(appCtx, group, decrypted)
            }

            content.startsWith(GROUP_MCAST_PREFIX) && content.endsWith("]") -> {
                val body = content.removePrefix(GROUP_MCAST_PREFIX).removeSuffix("]")
                val sep = body.indexOf(':')
                if (sep <= 0) return
                val tag16 = body.substring(0, sep)
                val cipherB64 = body.substring(sep + 1)
                val group = _groups.value.firstOrNull {
                    roomTagHex(it).startsWith(tag16, ignoreCase = true)
                } ?: return
                val decrypted = decryptWithGroupKey(group.groupKeyBase64, cipherB64) ?: return
                processDecryptedEnvelope(appCtx, group, decrypted)
            }
        }
    }

    private fun processDecryptedEnvelope(
        context: Context,
        group: P2PGroup,
        envelopeJson: String
    ) {
        val env = runCatching { gson.fromJson(envelopeJson, GroupWireEnvelope::class.java) }.getOrNull() ?: return
        if (env.groupId != group.id) return
        if (!seenEnvelopeIds.add(env.envId)) return
        if (seenEnvelopeIds.size > 4000) {
            val iter = seenEnvelopeIds.iterator()
            repeat(1000) { if (iter.hasNext()) { iter.next(); iter.remove() } }
        }

        val myNick = com.bitchat.android.services.AppStateStore.nickname.value.ifBlank { "nemix" }
        val me = myMemberIdentity(context, myNick)
        if (env.senderId.equals(me.id, ignoreCase = true)) return

        // Auto-register sender in group roster if they joined via invite link
        var currentGroup = _groups.value.firstOrNull { it.id == group.id } ?: group
        if (env.senderId.isNotBlank() && currentGroup.members.none { it.id.equals(env.senderId, ignoreCase = true) } && env.action != "LEAVE") {
            val newMember = P2PGroupMember(
                id = env.senderId,
                nickname = env.senderName.ifBlank { "Peer" },
                noiseKeyHex = env.senderNoiseHex,
                npub = env.senderNpub,
                role = "MEMBER"
            )
            registerMemberIdentities(context, listOf(newMember), me.id)
            currentGroup = currentGroup.copy(
                members = currentGroup.members + newMember,
                updatedAt = System.currentTimeMillis()
            )
            replaceGroup(context, currentGroup)
        }

        when (env.action) {
            "MSG" -> {
                val msgId = env.messageId ?: UUID.randomUUID().toString().uppercase()
                val text = env.text ?: return
                val msg = P2PGroupMessage(
                    id = msgId,
                    groupId = currentGroup.id,
                    channelId = env.channelId.ifBlank { "general" },
                    senderId = env.senderId,
                    senderName = env.senderName.ifBlank { "Peer" },
                    senderNoiseHex = env.senderNoiseHex,
                    content = text,
                    type = env.msgType ?: "TEXT",
                    replyToId = env.replyToId,
                    replyPreview = env.replyPreview,
                    timestamp = env.ts
                )
                appendGroupMessage(context, msg, incrementUnread = currentlyOpenGroupId != currentGroup.id)
            }

            "IMAGE" -> {
                val b64 = env.imageB64 ?: return
                val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: return
                val msgId = env.messageId ?: UUID.randomUUID().toString().uppercase()
                val mediaDir = File(context.filesDir, "group_media").apply { mkdirs() }
                val file = File(mediaDir, "grp_${msgId.lowercase()}.webp")
                runCatching { FileOutputStream(file).use { it.write(bytes) } }.onFailure { return }

                val msg = P2PGroupMessage(
                    id = msgId,
                    groupId = currentGroup.id,
                    channelId = env.channelId.ifBlank { "general" },
                    senderId = env.senderId,
                    senderName = env.senderName.ifBlank { "Peer" },
                    senderNoiseHex = env.senderNoiseHex,
                    content = file.absolutePath,
                    type = "IMAGE",
                    timestamp = env.ts
                )
                appendGroupMessage(context, msg, incrementUnread = currentlyOpenGroupId != currentGroup.id)
            }

            "STYLE" -> {
                val parsed = runCatching { P2PGroupStyle.valueOf(env.style.orEmpty()) }.getOrNull() ?: return
                val updated = currentGroup.copy(style = parsed, updatedAt = System.currentTimeMillis())
                replaceGroup(context, updated)
                val label = if (parsed == P2PGroupStyle.CHANNELS) "チャンネル形式 (トピック別)" else "ストリーム形式 (単一スレッド)"
                appendLocalSystemMessage(currentGroup.id, "🎨 ${env.senderName} さんがグループ表示を「$label」に変更しました")
            }

            "CHANNEL_ADD" -> {
                val ch = env.channel ?: return
                if (currentGroup.channels.none { it.id == ch.id }) {
                    val updated = currentGroup.copy(
                        channels = currentGroup.channels + ch,
                        updatedAt = System.currentTimeMillis()
                    )
                    replaceGroup(context, updated)
                    appendLocalSystemMessage(currentGroup.id, "➕ ${env.senderName} さんが #${ch.name} を作成しました", channelId = ch.id)
                }
            }

            "POLICY" -> {
                val blocked = env.ssBlock ?: return
                val updated = currentGroup.copy(screenshotBlocked = blocked, updatedAt = System.currentTimeMillis())
                replaceGroup(context, updated)
                val text = if (blocked) {
                    "🔒 ${env.senderName} さんがスクリーンショット禁止をONにしました"
                } else {
                    "🔓 ${env.senderName} さんがスクリーンショット禁止をOFFにしました"
                }
                appendLocalSystemMessage(currentGroup.id, text)
            }

            "PIN" -> {
                val pinText = env.text?.takeIf { it.isNotBlank() }
                val updated = currentGroup.copy(pinnedNotice = pinText, updatedAt = System.currentTimeMillis())
                replaceGroup(context, updated)
            }

            "DELETE_MSG" -> {
                val msgId = env.messageId ?: return
                removeGroupMessageLocally(context, currentGroup.id, msgId)
            }

            "JOIN" -> {
                appendLocalSystemMessage(currentGroup.id, "👋 ${env.senderName} さんがグループに参加しました")
            }

            "LEAVE" -> {
                val remaining = currentGroup.members.filterNot { it.id.equals(env.senderId, ignoreCase = true) }
                replaceGroup(context, currentGroup.copy(members = remaining, updatedAt = System.currentTimeMillis()))
                appendLocalSystemMessage(currentGroup.id, "👋 ${env.senderName} さんがグループを退出しました")
            }

            "SYNC_META" -> {
                val snap = env.groupSnapshot ?: return
                if (snap.id == currentGroup.id) {
                    registerMemberIdentities(context, snap.members, me.id)
                    val mergedMembers = (currentGroup.members + snap.members).distinctBy { it.id.lowercase() }
                    val mergedChannels = (currentGroup.channels + snap.channels).distinctBy { it.id }
                    replaceGroup(
                        context,
                        currentGroup.copy(
                            name = snap.name,
                            description = snap.description,
                            style = snap.style,
                            channels = mergedChannels,
                            members = mergedMembers,
                            pinnedNotice = snap.pinnedNotice,
                            screenshotBlocked = snap.screenshotBlocked,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    // ---- Outbound Multi-Transport P2P Dispatch ----

    private fun sendDirectGroupInvite(
        context: Context,
        group: P2PGroup,
        peerTarget: String,
        inviterNickname: String
    ): MessageRouter.RouteResult {
        return try {
            val payload = GroupInvitePayload(group = group, inviterName = inviterNickname)
            val b64 = Base64.encodeToString(
                gson.toJson(payload).toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP
            )
            val wire = "$INVITE_PACKET_PREFIX$b64]"
            val mesh = MeshServiceHolder.getUnifiedOrCreate(context)
            val peerName = ContactDirectory.resolve(peerTarget).displayName ?: peerTarget.take(8)
            MessageRouter.getInstance(context, mesh).sendPrivate(
                wire,
                peerTarget,
                peerName,
                UUID.randomUUID().toString().uppercase()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send direct group invite: ${e.message}")
            MessageRouter.RouteResult.DROPPED
        }
    }

    private fun dispatchGroupEnvelope(
        context: Context,
        group: P2PGroup,
        envelope: GroupWireEnvelope
    ) {
        seenEnvelopeIds.add(envelope.envId)
        val json = gson.toJson(envelope)
        val cipherB64 = encryptWithGroupKey(group.groupKeyBase64, json) ?: return

        scope.launch {
            val mesh = MeshServiceHolder.getUnifiedOrCreate(context)
            val router = MessageRouter.getInstance(context, mesh)
            val myNoiseHex = mesh.getStaticNoisePublicKey()?.hexEncodedString()?.lowercase()
            val myFp = mesh.getStaticNoisePublicKey()?.let { ContactIdentityResolver.fingerprintHex(it) }

            // 1. Pairwise Noise / NIP-17 E2EE Fan-out to known members
            val pairwiseWire = "$GROUP_PKT_PREFIX${group.id}:$cipherB64]"
            group.members.forEach { member ->
                val isSelf = (myFp != null && member.id.equals(myFp, ignoreCase = true)) ||
                    (myNoiseHex != null && member.noiseKeyHex.equals(myNoiseHex, ignoreCase = true)) ||
                    member.id.equals(mesh.myPeerID, ignoreCase = true)
                if (!isSelf) {
                    val target = when {
                        !member.noiseKeyHex.isNullOrBlank() ->
                            ContactIdentityResolver.bytesFromHex(member.noiseKeyHex)
                                ?.let { ContactIdentityResolver.contactConversationIdForNoiseKey(it) }
                                ?: member.noiseKeyHex
                        else -> member.id
                    }
                    runCatching {
                        router.sendPrivate(
                            pairwiseWire,
                            target,
                            member.nickname,
                            UUID.randomUUID().toString().uppercase()
                        )
                    }
                }
            }

            // 2. Local BLE / Wi-Fi Aware Opaque Mesh Multicast (for nearby group peers)
            if (mesh.getActivePeerCount() > 0) {
                val tag16 = roomTagHex(group).take(16)
                val mcastWire = "$GROUP_MCAST_PREFIX$tag16:$cipherB64]"
                runCatching {
                    mesh.sendMessage(mcastWire, emptyList(), null)
                }
            }

            // 3. Zero-Knowledge Ephemeral-Key P2P Relay Multicast (when Internet or Mesh Bridge is active)
            if (InternetRelayPreferences.isInternetEnabled() || InternetRelayPreferences.isMeshBridgeEnabled()) {
                runCatching {
                    val ephemeralIdentity = NostrIdentity.generate()
                    val fullTag = roomTagHex(group)
                    val unsigned = NostrEvent(
                        pubkey = ephemeralIdentity.publicKeyHex,
                        createdAt = (System.currentTimeMillis() / 1000L).toInt(),
                        kind = NostrKind.TEXT_NOTE,
                        tags = listOf(listOf("t", fullTag)),
                        content = cipherB64
                    )
                    val signed = ephemeralIdentity.signEvent(unsigned)
                    NostrRelayManager.getInstance(context).sendEvent(signed)
                }
            }
        }
    }

    /**
     * Subscribes to capability-derived opaque `#t` tags on active P2P relays so online members
     * receive encrypted group packets with zero server-side membership leakage.
     */
    fun refreshRelayGroupSubscriptions(context: Context) {
        val appCtx = context.applicationContext
        val relayMgr = NostrRelayManager.getInstance(appCtx)
        runCatching { relayMgr.unsubscribe(RELAY_SUB_ID) }

        val currentGroups = _groups.value
        if (currentGroups.isEmpty()) return

        val tagToGroup = currentGroups.associateBy { roomTagHex(it) }
        val filter = NostrFilter.Builder()
            .kinds(NostrKind.TEXT_NOTE)
            .tag("t", *tagToGroup.keys.toTypedArray())
            .since(System.currentTimeMillis() - 24L * 3600L * 1000L)
            .limit(200)
            .build()

        relayMgr.subscribe(
            filter = filter,
            id = RELAY_SUB_ID,
            observeOnly = true,
            handler = { event ->
                try {
                    if (!event.isValid()) return@subscribe
                    val tTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "t" }?.get(1) ?: return@subscribe
                    val matchedGroup = tagToGroup[tTag]
                        ?: _groups.value.firstOrNull { roomTagHex(it) == tTag }
                        ?: return@subscribe
                    val decrypted = decryptWithGroupKey(matchedGroup.groupKeyBase64, event.content) ?: return@subscribe
                    processDecryptedEnvelope(appCtx, matchedGroup, decrypted)
                } catch (_: Exception) {
                }
            }
        )
    }

    // ---- Helpers & Encrypted Persistence ----

    private fun buildMemberFromPeerTarget(context: Context, peerTarget: String): P2PGroupMember? {
        val mesh = MeshServiceHolder.getUnifiedOrCreate(context)
        val resolution = ContactDirectory.resolve(peerTarget)
        val peerInfo = resolution.meshPeerID?.let { mesh.getPeerInfo(it) } ?: mesh.getPeerInfo(peerTarget)
        val noiseBytes = resolution.noisePublicKey ?: peerInfo?.noisePublicKey
        val noiseHex = noiseBytes?.hexEncodedString()?.lowercase()
        val memberId = noiseBytes?.let { ContactIdentityResolver.fingerprintHex(it) } ?: peerTarget
        val nick = resolution.displayName
            ?: peerInfo?.nickname
            ?: mesh.getPeerNicknames()[peerTarget]
            ?: peerTarget.take(8)
        val npub = resolution.nostrPubkey
            ?: noiseBytes?.let {
                runCatching { FavoritesPersistenceService.shared.findNostrPubkey(it) }.getOrNull()
            }

        return P2PGroupMember(
            id = memberId,
            nickname = nick,
            noiseKeyHex = noiseHex,
            npub = npub,
            role = "MEMBER"
        )
    }

    private fun registerMemberIdentities(
        context: Context,
        members: List<P2PGroupMember>,
        myId: String
    ) {
        FavoritesPersistenceService.initialize(context)
        val idMgr = SecureIdentityStateManager(context)
        members.forEach { member ->
            if (member.id.equals(myId, ignoreCase = true)) return@forEach
            val noiseHex = member.noiseKeyHex ?: return@forEach
            val noiseBytes = ContactIdentityResolver.bytesFromHex(noiseHex)?.takeIf { it.size == 32 } ?: return@forEach
            runCatching {
                val existing = FavoritesPersistenceService.shared.getFavoriteStatus(noiseBytes)
                if (existing == null) {
                    FavoritesPersistenceService.shared.updateFavoriteStatus(noiseBytes, member.nickname, false)
                }
                if (!member.npub.isNullOrBlank()) {
                    FavoritesPersistenceService.shared.updateNostrPublicKey(noiseBytes, member.npub)
                }
                val fp = ContactIdentityResolver.fingerprintHex(noiseBytes)
                idMgr.cacheFingerprintNickname(fp, member.nickname)
                idMgr.cacheNoiseFingerprint(noiseHex.lowercase(), fp)
            }
        }
    }

    private fun replaceGroup(context: Context, updated: P2PGroup) {
        synchronized(this) {
            val current = _groups.value.toMutableList()
            val idx = current.indexOfFirst { it.id == updated.id }
            if (idx >= 0) {
                current[idx] = updated
            } else {
                current.add(0, updated)
            }
            _groups.value = current.sortedByDescending { it.updatedAt }
        }
        saveToEncryptedDisk(context)
    }

    private fun appendLocalSystemMessage(
        groupId: String,
        text: String,
        channelId: String = "general",
        incrementUnread: Boolean = false
    ) {
        val ctx = appContext ?: return
        val sys = P2PGroupMessage(
            id = UUID.randomUUID().toString().uppercase(),
            groupId = groupId,
            channelId = channelId,
            senderId = "system",
            senderName = "system",
            content = text,
            type = "SYSTEM",
            timestamp = System.currentTimeMillis()
        )
        appendGroupMessage(ctx, sys, incrementUnread)
    }

    private fun appendGroupMessage(
        context: Context,
        msg: P2PGroupMessage,
        incrementUnread: Boolean
    ) {
        synchronized(this) {
            val all = _groupMessages.value.toMutableMap()
            val list = all[msg.groupId].orEmpty().toMutableList()
            if (list.any { it.id == msg.id }) return
            list.add(msg)
            val trimmed = if (list.size > MAX_MESSAGES_PER_GROUP) {
                list.takeLast(MAX_MESSAGES_PER_GROUP)
            } else list
            all[msg.groupId] = trimmed
            _groupMessages.value = all

            // Bump group updatedAt so active groups float to the top
            val currentGroups = _groups.value.map { g ->
                if (g.id == msg.groupId) g.copy(updatedAt = maxOf(g.updatedAt, msg.timestamp)) else g
            }.sortedByDescending { it.updatedAt }
            _groups.value = currentGroups

            if (incrementUnread && currentlyOpenGroupId != msg.groupId) {
                val unread = _unreadCounts.value.toMutableMap()
                unread[msg.groupId] = (unread[msg.groupId] ?: 0) + 1
                _unreadCounts.value = unread
            }
        }
        saveToEncryptedDisk(context)
    }

    private fun removeGroupMessageLocally(context: Context, groupId: String, messageId: String) {
        synchronized(this) {
            val all = _groupMessages.value.toMutableMap()
            val list = all[groupId] ?: return
            val target = list.firstOrNull { it.id == messageId }
            if (target?.type == "IMAGE") {
                runCatching { File(target.content).delete() }
            }
            all[groupId] = list.filterNot { it.id == messageId }
            _groupMessages.value = all
        }
        saveToEncryptedDisk(context)
    }

    private fun roomTagHex(group: P2PGroup): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("nemix-grp-room-v1:${group.id}:${group.groupKeyBase64}").toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun encryptWithGroupKey(keyBase64: String, plaintext: String): String? {
        return try {
            val keyBytes = Base64.decode(keyBase64, Base64.DEFAULT)
            if (keyBytes.size != 32) return null
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
            val out = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(encrypted, 0, out, iv.size, encrypted.size)
            Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptWithGroupKey(keyBase64: String, cipherBase64: String): String? {
        return try {
            val keyBytes = Base64.decode(keyBase64, Base64.DEFAULT)
            if (keyBytes.size != 32) return null
            val raw = Base64.decode(cipherBase64, Base64.DEFAULT)
            if (raw.size <= 12) return null
            val iv = raw.copyOfRange(0, 12)
            val cipherBytes = raw.copyOfRange(12, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun getOrCreateLocalStorageKey(context: Context): String {
        val idMgr = SecureIdentityStateManager(context)
        val existing = idMgr.getSecureValue(SECURE_STORAGE_KEY_NAME)
        if (!existing.isNullOrBlank()) return existing
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val b64 = Base64.encodeToString(key, Base64.NO_WRAP)
        idMgr.storeSecureValue(SECURE_STORAGE_KEY_NAME, b64)
        return b64
    }

    private fun saveToEncryptedDisk(context: Context) {
        try {
            val keyB64 = getOrCreateLocalStorageKey(context.applicationContext)
            val snapshot = synchronized(this) {
                PersistedGroupStore(
                    groups = _groups.value,
                    messages = _groupMessages.value,
                    unreadCounts = _unreadCounts.value
                )
            }
            val encrypted = encryptWithGroupKey(keyB64, gson.toJson(snapshot)) ?: return
            val file = File(context.applicationContext.filesDir, STORAGE_FILE_NAME)
            file.writeText(encrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving encrypted groups: ${e.message}")
        }
    }

    private fun loadFromEncryptedDisk(context: Context) {
        try {
            val file = File(context.applicationContext.filesDir, STORAGE_FILE_NAME)
            if (!file.exists()) return
            val keyB64 = getOrCreateLocalStorageKey(context.applicationContext)
            val encrypted = file.readText(StandardCharsets.UTF_8)
            val json = decryptWithGroupKey(keyB64, encrypted) ?: return
            val restored = gson.fromJson(json, PersistedGroupStore::class.java) ?: return
            synchronized(this) {
                val validGroups = sanitizeGroups(restored.groups)
                _groups.value = validGroups.sortedByDescending { it.updatedAt }
                _groupMessages.value = sanitizeMessages(restored.messages, validGroups)
                _unreadCounts.value = sanitizeUnread(restored.unreadCounts)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed loading encrypted groups: ${e.message}")
        }
    }

    // Gson fills non-null Kotlin fields with null when the stored JSON does not match (for
    // example data written by older builds whose field names were obfuscated by R8). Such
    // entries are dropped instead of crashing the UI later.
    private fun sanitizeGroups(raw: List<P2PGroup>?): List<P2PGroup> =
        (raw ?: emptyList<P2PGroup>()).filter { g ->
            runCatching {
                @Suppress("SENSELESS_COMPARISON")
                (g as Any?) is P2PGroup && g.id.isNotBlank() && g.groupKeyBase64.isNotBlank() &&
                    g.name != null && g.creatorId != null && g.channels != null && g.members != null &&
                    g.style != null && g.channels.all { it.id != null && it.name != null } &&
                    g.members.all { it.id != null && it.nickname != null }
            }.getOrDefault(false)
        }

    private fun sanitizeMessages(
        raw: Map<String, List<P2PGroupMessage>>?,
        groups: List<P2PGroup>
    ): Map<String, List<P2PGroupMessage>> {
        val ids = groups.map { it.id }.toSet()
        return (raw ?: emptyMap()).mapNotNull { (groupId, list) ->
            @Suppress("SENSELESS_COMPARISON")
            if (groupId == null || groupId !in ids || list == null) return@mapNotNull null
            groupId to list.filter { m ->
                runCatching {
                    @Suppress("SENSELESS_COMPARISON")
                    (m as Any?) is P2PGroupMessage && m.id != null && m.groupId != null &&
                        m.senderId != null && m.senderName != null && m.content != null &&
                        m.type != null && m.channelId != null
                }.getOrDefault(false)
            }
        }.toMap()
    }

    private fun sanitizeUnread(raw: Map<String, Int>?): Map<String, Int> =
        (raw ?: emptyMap()).filter { (k, v) -> runCatching { k != null && (v as Any?) is Int }.getOrDefault(false) }

    fun exportMigrationJson(): String = synchronized(this) {
        gson.toJson(
            PersistedGroupStore(
                groups = _groups.value,
                messages = _groupMessages.value,
                unreadCounts = _unreadCounts.value
            )
        )
    }

    fun restoreFromMigrationJson(context: Context, json: String?) {
        if (json.isNullOrBlank()) return
        runCatching {
            val parsed = gson.fromJson(json, PersistedGroupStore::class.java) ?: return
            synchronized(this) {
                val validGroups = sanitizeGroups(parsed.groups)
                _groups.value = validGroups.sortedByDescending { it.updatedAt }
                _groupMessages.value = sanitizeMessages(parsed.messages, validGroups)
                _unreadCounts.value = sanitizeUnread(parsed.unreadCounts)
            }
            saveToEncryptedDisk(context.applicationContext)
            refreshRelayGroupSubscriptions(context.applicationContext)
        }
    }

    fun clearAll(context: Context) {
        synchronized(this) {
            _groups.value = emptyList()
            _groupMessages.value = emptyMap()
            _unreadCounts.value = emptyMap()
            seenEnvelopeIds.clear()
        }
        runCatching { File(context.applicationContext.filesDir, STORAGE_FILE_NAME).delete() }
        runCatching { File(context.applicationContext.filesDir, "group_media").deleteRecursively() }
        runCatching { NostrRelayManager.getInstance(context.applicationContext).unsubscribe(RELAY_SUB_ID) }
    }

    private fun scaleDownBitmap(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val ratio = minOf(maxDim.toFloat() / w.toFloat(), maxDim.toFloat() / h.toFloat())
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun compressBitmapCompact(bmp: Bitmap): ByteArray {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        var quality = 74
        var outBytes: ByteArray
        do {
            val baos = ByteArrayOutputStream()
            bmp.compress(format, quality, baos)
            outBytes = baos.toByteArray()
            quality -= 12
        } while (outBytes.size > 44_000 && quality >= 35)
        return outBytes
    }
}
