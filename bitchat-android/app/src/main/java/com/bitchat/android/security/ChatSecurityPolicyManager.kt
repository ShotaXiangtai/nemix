package com.bitchat.android.security

import android.content.Context
import android.util.Log
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.MessageRouter
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Date
import java.util.UUID

/**
 * Manages selectable mutual screenshot protection (`FLAG_SECURE` enforced on BOTH self and peer)
 * and synchronized 1-on-1 / broadcast chat & message deletion over P2P E2EE.
 */
object ChatSecurityPolicyManager {
    private const val TAG = "ChatSecurityPolicy"
    const val PREFS_NAME = "nemix_chat_policy_prefs"
    private const val KEY_GLOBAL_SS_BLOCK = "global_screenshot_block"
    private const val KEY_MY_BLOCKED_CHATS = "my_blocked_chats"
    private const val KEY_PEER_BLOCKED_CHATS = "peer_blocked_chats"

    private const val POLICY_PACKET_PREFIX = "[NEMIX_CHAT_POLICY:"
    private const val DELETE_PACKET_PREFIX = "[NEMIX_CHAT_DELETE:"

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var appContext: Context? = null

    private val _globalScreenshotBlocked = MutableStateFlow(false)
    val globalScreenshotBlocked: StateFlow<Boolean> = _globalScreenshotBlocked.asStateFlow()

    private val _myBlockedChats = MutableStateFlow<Set<String>>(emptySet())
    val myBlockedChats: StateFlow<Set<String>> = _myBlockedChats.asStateFlow()

    private val _peerBlockedChats = MutableStateFlow<Set<String>>(emptySet())
    val peerBlockedChats: StateFlow<Set<String>> = _peerBlockedChats.asStateFlow()

    // Active target currently displayed on screen: null (home), "mesh_broadcast", peerID, or "group:<id>"
    private val _activeViewingTarget = MutableStateFlow<String?>(null)
    val activeViewingTarget: StateFlow<String?> = _activeViewingTarget.asStateFlow()

    // External group screenshot block lookup registered by P2PGroupManager
    @Volatile
    var groupScreenshotBlockChecker: ((String) -> Boolean)? = null

    data class ChatPolicyPacket(
        val v: Int = 1,
        val ssBlock: Boolean,
        val actor: String = ""
    )

    data class ChatDeletePacket(
        val v: Int = 1,
        val scope: String, // "ALL" or "MSG"
        val msgId: String? = null,
        val actor: String = ""
    )

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _globalScreenshotBlocked.value = prefs.getBoolean(KEY_GLOBAL_SS_BLOCK, false)
        _myBlockedChats.value = prefs.getStringSet(KEY_MY_BLOCKED_CHATS, emptySet())?.toSet() ?: emptySet()
        _peerBlockedChats.value = prefs.getStringSet(KEY_PEER_BLOCKED_CHATS, emptySet())?.toSet() ?: emptySet()
    }

    fun setActiveViewingTarget(target: String?) {
        _activeViewingTarget.value = target
    }

    fun setGlobalScreenshotBlocked(context: Context, blocked: Boolean) {
        _globalScreenshotBlocked.value = blocked
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GLOBAL_SS_BLOCK, blocked)
            .apply()
    }

    private fun candidateKeysFor(peerOrConversationID: String): Set<String> {
        val raw = peerOrConversationID.trim().lowercase()
        if (raw.isEmpty()) return emptySet()
        val keys = linkedSetOf(raw)
        runCatching {
            val canonical = ContactDirectory.canonicalConversationId(peerOrConversationID).lowercase()
            keys.add(canonical)
            ContactDirectory.aliasesForConversation(peerOrConversationID).forEach { keys.add(it.lowercase()) }
            ContactDirectory.aliasesForConversation(canonical).forEach { keys.add(it.lowercase()) }
            ContactDirectory.resolve(peerOrConversationID).meshPeerID?.lowercase()?.let { keys.add(it) }
        }
        return keys
    }

    fun isBlockedByMe(peerOrConversationID: String): Boolean {
        val candidates = candidateKeysFor(peerOrConversationID)
        val blocked = _myBlockedChats.value
        return candidates.any { blocked.contains(it) }
    }

    fun isBlockedByPeer(peerOrConversationID: String): Boolean {
        val candidates = candidateKeysFor(peerOrConversationID)
        val blocked = _peerBlockedChats.value
        return candidates.any { blocked.contains(it) }
    }

    fun isScreenshotBlockedForConversation(peerOrConversationID: String?): Boolean {
        if (_globalScreenshotBlocked.value) return true
        val target = peerOrConversationID?.takeIf { it.isNotBlank() } ?: return false
        if (target.startsWith("group:")) {
            val groupId = target.removePrefix("group:")
            return groupScreenshotBlockChecker?.invoke(groupId) == true
        }
        if (target == "mesh_broadcast") return false
        return isBlockedByMe(target) || isBlockedByPeer(target)
    }

    /**
     * Toggles screenshot blocking for a 1-on-1 chat and synchronizes it over E2EE to the peer
     * so BOTH devices block screenshots and screen recording.
     */
    fun setMyChatScreenshotBlock(
        context: Context,
        peerOrConversationID: String,
        peerDisplayName: String,
        myNickname: String,
        blocked: Boolean
    ) {
        val appCtx = context.applicationContext
        appContext = appCtx
        val candidates = candidateKeysFor(peerOrConversationID)
        val canonical = canonicalKey(peerOrConversationID)
        val updated = _myBlockedChats.value.toMutableSet()
        if (blocked) {
            updated.add(canonical)
        } else {
            updated.removeAll(candidates)
        }
        _myBlockedChats.value = updated

        appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_MY_BLOCKED_CHATS, updated)
            .apply()

        val pkt = ChatPolicyPacket(ssBlock = blocked, actor = myNickname)
        val wire = "$POLICY_PACKET_PREFIX${gson.toJson(pkt)}]"
        runCatching {
            val mesh = MeshServiceHolder.getUnifiedOrCreate(appCtx)
            MessageRouter.getInstance(appCtx, mesh).sendPrivate(
                wire,
                peerOrConversationID,
                peerDisplayName,
                UUID.randomUUID().toString().uppercase()
            )
        }.onFailure { e ->
            Log.w(TAG, "Failed to sync screenshot policy: ${e.message}")
        }

        val peerStillBlocking = isBlockedByPeer(peerOrConversationID)
        val noticeText = when {
            blocked -> "スクリーンショット禁止をONにしました（相手側の端末でもスクショ・画面録画が禁止されます）"
            peerStillBlocking -> "自分のスクショ禁止設定をOFFにしました（相手側がONのため保護は継続中）"
            else -> "スクリーンショット禁止をOFFにしました"
        }
        postSystemMessage(canonical, noticeText)
    }

    /**
     * Sends a remote deletion command to the peer over E2EE when `bothSides = true`.
     */
    fun sendPeerDeleteCommand(
        context: Context,
        peerOrConversationID: String,
        peerDisplayName: String,
        myNickname: String,
        deleteAll: Boolean,
        messageId: String? = null
    ) {
        val appCtx = context.applicationContext
        appContext = appCtx
        val pkt = ChatDeletePacket(
            scope = if (deleteAll) "ALL" else "MSG",
            msgId = messageId,
            actor = myNickname
        )
        val wire = "$DELETE_PACKET_PREFIX${gson.toJson(pkt)}]"
        runCatching {
            val mesh = MeshServiceHolder.getUnifiedOrCreate(appCtx)
            MessageRouter.getInstance(appCtx, mesh).sendPrivate(
                wire,
                peerOrConversationID,
                peerDisplayName,
                UUID.randomUUID().toString().uppercase()
            )
        }.onFailure { e ->
            Log.w(TAG, "Failed to send remote delete command: ${e.message}")
        }
    }

    fun isSecurityControlPacket(content: String): Boolean {
        val trimmed = content.trim()
        return (trimmed.startsWith(POLICY_PACKET_PREFIX) || trimmed.startsWith(DELETE_PACKET_PREFIX)) &&
            trimmed.endsWith("]")
    }

    /**
     * Silently replays any historical `[NEMIX_CHAT_POLICY:...]` rows stored in SQLite by an older APK
     * before filtering them out of visible chat bubbles.
     */
    fun applyHistoricalPolicyIfPresent(conversationID: String, content: String) {
        val trimmed = content.trim()
        if (!trimmed.startsWith(POLICY_PACKET_PREFIX) || !trimmed.endsWith("]")) return
        val json = trimmed.removePrefix(POLICY_PACKET_PREFIX).removeSuffix("]")
        val pkt = runCatching { gson.fromJson(json, ChatPolicyPacket::class.java) }.getOrNull() ?: return
        val canonical = canonicalKey(conversationID)
        val candidates = candidateKeysFor(conversationID)
        val updated = _peerBlockedChats.value.toMutableSet()
        if (pkt.ssBlock) {
            updated.add(canonical)
        } else {
            updated.removeAll(candidates)
        }
        if (updated != _peerBlockedChats.value) {
            _peerBlockedChats.value = updated
            appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.putStringSet(KEY_PEER_BLOCKED_CHATS, updated)
                ?.apply()
        }
    }

    fun handleIncomingSecurityPacket(
        context: Context,
        senderPeerOrConvID: String,
        senderNickname: String,
        content: String
    ) {
        val appCtx = context.applicationContext
        appContext = appCtx
        val trimmed = content.trim()
        val canonical = canonicalKey(senderPeerOrConvID)
        val candidates = candidateKeysFor(senderPeerOrConvID)

        if (trimmed.startsWith(POLICY_PACKET_PREFIX) && trimmed.endsWith("]")) {
            val json = trimmed.removePrefix(POLICY_PACKET_PREFIX).removeSuffix("]")
            val pkt = runCatching { gson.fromJson(json, ChatPolicyPacket::class.java) }.getOrNull() ?: return
            val updated = _peerBlockedChats.value.toMutableSet()
            if (pkt.ssBlock) {
                updated.add(canonical)
            } else {
                updated.removeAll(candidates)
            }
            _peerBlockedChats.value = updated

            appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_PEER_BLOCKED_CHATS, updated)
                .apply()

            val actorName = pkt.actor.ifBlank { senderNickname.ifBlank { "相手" } }
            val myBlocking = isBlockedByMe(senderPeerOrConvID)
            val notice = when {
                pkt.ssBlock -> "$actorName さんがスクリーンショット禁止をONにしました（このチャットの画面キャプチャは保護されます）"
                myBlocking -> "$actorName さんがスクショ禁止をOFFにしました（あなたの設定がONのため保護は継続中）"
                else -> "$actorName さんがスクリーンショット禁止をOFFにしました"
            }
            postSystemMessage(canonical, notice)
            return
        }

        if (trimmed.startsWith(DELETE_PACKET_PREFIX) && trimmed.endsWith("]")) {
            val json = trimmed.removePrefix(DELETE_PACKET_PREFIX).removeSuffix("]")
            val pkt = runCatching { gson.fromJson(json, ChatDeletePacket::class.java) }.getOrNull() ?: return
            when (pkt.scope) {
                "ALL" -> {
                    scope.launch {
                        runCatching {
                            AppStateStore.deletePrivateConversationAndWait(canonical)
                        }
                    }
                }
                "MSG" -> {
                    val targetMsgId = pkt.msgId?.takeIf { it.isNotBlank() } ?: return
                    runCatching {
                        AppStateStore.removePrivateMessage(targetMsgId)
                    }
                }
            }
        }
    }

    fun clearAll(context: Context) {
        _globalScreenshotBlocked.value = false
        _myBlockedChats.value = emptySet()
        _peerBlockedChats.value = emptySet()
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun canonicalKey(peerOrConversationID: String): String =
        runCatching { ContactDirectory.canonicalConversationId(peerOrConversationID).lowercase() }
            .getOrDefault(peerOrConversationID.trim().lowercase())

    private fun postSystemMessage(conversationID: String, text: String) {
        val msg = BitchatMessage(
            sender = "system",
            content = text,
            timestamp = Date(),
            isRelay = false,
            isPrivate = true,
            senderPeerID = conversationID
        )
        AppStateStore.addPrivateMessage(conversationID, msg, forceRead = false)
    }
}
