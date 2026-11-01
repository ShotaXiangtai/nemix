package com.bitchat.android.services

import android.content.Context
import com.bitchat.android.call.WirelessCallManager
import com.bitchat.android.directory.NemixUuidDirectoryManager
import com.bitchat.android.features.media.InternetMediaTransfer
import com.bitchat.android.groups.P2PGroupManager
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.net.MeshInternetGatewayBridge
import com.bitchat.android.profile.UserProfileManager
import com.bitchat.android.security.ChatSecurityPolicyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Central control-plane interceptor for nemix 100% P2P E2EE protocol extensions:
 * - Compressed Profile & Avatar synchronization (`[NEMIX_PROFILE:...]`)
 * - Serverless Mesh <-> Internet E2EE Gateway Bridging (`[NEMIX_GW_*]`)
 * - Real-time E2EE Wireless Voice Calling (`[NEMIX_CALL:*]`, `[NEMIX_CALL_AUDIO:*]`)
 * - Chunked AES-256-GCM Internet Media Transfer (`[NEMIX_MEDIA_*]`)
 * - UUID Search Mutual Friend Add Handshake (`[NEMIX_FRIEND_ADD:...]`)
 * - Mutual Chat Screenshot Block & Synchronized Chat Deletion (`[NEMIX_CHAT_POLICY:...]`, `[NEMIX_CHAT_DELETE:...]`)
 * - 100% P2P Encrypted Groups — Stream & Channel formats (`[NEMIX_GRP_*]`)
 *
 * Returns `true` when a packet is a control/streaming/group frame and must NOT be stored as a raw 1-on-1/public bubble.
 */
object NemixProtocolInterceptor {
    @Volatile private var appContext: Context? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isControlPacket(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return false
        return UserProfileManager.isProfileSyncPacket(trimmed) ||
            MeshInternetGatewayBridge.isGatewayPacket(trimmed) ||
            WirelessCallManager.isCallPacket(trimmed) ||
            InternetMediaTransfer.isMediaTransferPacket(trimmed) ||
            NemixUuidDirectoryManager.isFriendAddPacket(trimmed) ||
            ChatSecurityPolicyManager.isSecurityControlPacket(trimmed) ||
            P2PGroupManager.isGroupPacket(trimmed) ||
            (trimmed.startsWith("[NEMIX_") && trimmed.endsWith("]"))
    }

    fun interceptMeshMessage(message: BitchatMessage): Boolean {
        val content = message.content.trim()
        if (!isControlPacket(content)) return false
        val ctx = appContext ?: return true

        val senderPeer = message.senderPeerID ?: ""
        val senderName = message.sender

        when {
            UserProfileManager.isProfileSyncPacket(content) -> {
                UserProfileManager.handleIncomingProfilePacket(
                    context = ctx,
                    senderPeerID = senderPeer,
                    senderNostrPubkey = message.senderNostrPubkey,
                    fallbackNickname = senderName,
                    content = content
                )
            }
            MeshInternetGatewayBridge.isGatewayPacket(content) -> {
                MeshInternetGatewayBridge.handleIncomingGatewayFrame(ctx, content)
            }
            WirelessCallManager.isCallPacket(content) -> {
                if (senderPeer.isNotBlank()) {
                    WirelessCallManager.handleIncomingCallPacket(
                        context = ctx,
                        senderPeerID = senderPeer,
                        senderNickname = senderName,
                        content = content,
                        originIsInternet = false
                    )
                }
            }
            InternetMediaTransfer.isMediaTransferPacket(content) -> {
                if (senderPeer.isNotBlank()) {
                    scope.launch {
                        InternetMediaTransfer.handleIncomingMediaPacket(
                            context = ctx,
                            conversationID = senderPeer,
                            senderNickname = senderName,
                            senderNostrPubkey = message.senderNostrPubkey,
                            myNickname = AppStateStore.nickname.value,
                            content = content
                        )
                    }
                }
            }
            NemixUuidDirectoryManager.isFriendAddPacket(content) -> {
                NemixUuidDirectoryManager.handleIncomingFriendAddPacket(
                    context = ctx,
                    fallbackSenderName = senderName,
                    senderNostrPubkey = message.senderNostrPubkey,
                    content = content
                )
            }
            ChatSecurityPolicyManager.isSecurityControlPacket(content) -> {
                if (senderPeer.isNotBlank()) {
                    ChatSecurityPolicyManager.handleIncomingSecurityPacket(
                        context = ctx,
                        senderPeerOrConvID = senderPeer,
                        senderNickname = senderName,
                        content = content
                    )
                }
            }
            P2PGroupManager.isGroupPacket(content) -> {
                P2PGroupManager.handleIncomingGroupPacket(
                    context = ctx,
                    senderPeerID = senderPeer,
                    senderNickname = senderName,
                    content = content
                )
            }
        }
        return true
    }

    suspend fun interceptNostrPrivateContent(
        context: Context,
        conversationID: String,
        senderNickname: String,
        senderPubkeyHex: String,
        myNickname: String?,
        content: String
    ): Boolean {
        val trimmed = content.trim()
        if (!isControlPacket(trimmed)) return false
        val ctx = context.applicationContext
        appContext = ctx

        when {
            UserProfileManager.isProfileSyncPacket(trimmed) -> {
                UserProfileManager.handleIncomingProfilePacket(
                    context = ctx,
                    senderPeerID = conversationID,
                    senderNostrPubkey = senderPubkeyHex,
                    fallbackNickname = senderNickname,
                    content = trimmed
                )
            }
            WirelessCallManager.isCallPacket(trimmed) -> {
                WirelessCallManager.handleIncomingCallPacket(
                    context = ctx,
                    senderPeerID = conversationID,
                    senderNickname = senderNickname,
                    content = trimmed,
                    originIsInternet = true
                )
            }
            InternetMediaTransfer.isMediaTransferPacket(trimmed) -> {
                InternetMediaTransfer.handleIncomingMediaPacket(
                    context = ctx,
                    conversationID = conversationID,
                    senderNickname = senderNickname,
                    senderNostrPubkey = senderPubkeyHex,
                    myNickname = myNickname,
                    content = trimmed
                )
            }
            MeshInternetGatewayBridge.isGatewayPacket(trimmed) -> {
                MeshInternetGatewayBridge.handleIncomingGatewayFrame(ctx, trimmed)
            }
            NemixUuidDirectoryManager.isFriendAddPacket(trimmed) -> {
                NemixUuidDirectoryManager.handleIncomingFriendAddPacket(
                    context = ctx,
                    fallbackSenderName = senderNickname,
                    senderNostrPubkey = senderPubkeyHex,
                    content = trimmed
                )
            }
            ChatSecurityPolicyManager.isSecurityControlPacket(trimmed) -> {
                ChatSecurityPolicyManager.handleIncomingSecurityPacket(
                    context = ctx,
                    senderPeerOrConvID = conversationID,
                    senderNickname = senderNickname,
                    content = trimmed
                )
            }
            P2PGroupManager.isGroupPacket(trimmed) -> {
                P2PGroupManager.handleIncomingGroupPacket(
                    context = ctx,
                    senderPeerID = conversationID,
                    senderNickname = senderNickname,
                    content = trimmed
                )
            }
        }
        return true
    }
}
