package com.bitchat.android.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.call.CallTransportMode
import com.bitchat.android.call.CallTransportPickerDialog
import com.bitchat.android.call.WirelessCallManager
import com.bitchat.android.directory.NemixUuidDirectoryManager
import com.bitchat.android.features.location.LocationMessageCard
import com.bitchat.android.features.location.LocationShareHelper
import com.bitchat.android.features.media.ImageUtils
import com.bitchat.android.groups.P2PGroupManager
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.net.InternetRelayPreferences
import com.bitchat.android.profile.ProfileSetupDialog
import com.bitchat.android.profile.UserProfileManager
import com.bitchat.android.security.ChatSecurityPolicyManager
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.service.MeshServicePreferences
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ChatArchiveManager
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.MessageRouter
import com.bitchat.android.ui.theme.AppUiStyle
import com.bitchat.android.ui.theme.AppUiStyleManager
import com.bitchat.android.ui.theme.BitchatFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private object ChannelModeColors {
    val RailBg = Color(0xFF1E1F22)
    val SidebarBg = Color(0xFF2B2D31)
    val ChatBg = Color(0xFF313338)
    val UserBarBg = Color(0xFF232428)
    val InputBg = Color(0xFF383A40)
    val Blurple = Color(0xFF5865F2)
    val Green = Color(0xFF23A55A)
    val TextPrimary = Color(0xFFF2F3F5)
    val TextMuted = Color(0xFF949BA4)
}

/**
 * Full Channel-format Home Screen for nemix Android:
 * - Left vertical Server / DM icon rail (`#1E1F22`)
 * - Channel / DM / Archived sidebar (`#2B2D31`) with Stealth Mode badge & Old-Chat Cleanup
 * - Bottom ChannelMode User Status Bar (`#232428`)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelModeHomeScreen(
    viewModel: ChatViewModel,
    onOpenBroadcastChat: () -> Unit,
    onOpenPrivateChat: (String) -> Unit,
    onOpenGroupChat: (String) -> Unit,
    onOpenUuidSearch: () -> Unit,
    onShowSettings: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val myAvatarBitmap by UserProfileManager.myAvatarBitmap.collectAsStateWithLifecycle()
    val peerAvatars by UserProfileManager.peerAvatars.collectAsStateWithLifecycle()
    val p2pGroups by P2PGroupManager.groups.collectAsStateWithLifecycle()
    val p2pGroupUnreads by P2PGroupManager.unreadCounts.collectAsStateWithLifecycle()
    val archivedIds by ChatArchiveManager.archivedIds.collectAsStateWithLifecycle()
    val nearbyVisible by MeshServicePreferences.nearbyVisibleFlow.collectAsStateWithLifecycle()
    val internetEnabled by InternetRelayPreferences.internetEnabled.collectAsStateWithLifecycle()
    val myBlockedChats by ChatSecurityPolicyManager.myBlockedChats.collectAsStateWithLifecycle()
    val peerBlockedChats by ChatSecurityPolicyManager.peerBlockedChats.collectAsStateWithLifecycle()
    val globalSsBlocked by ChatSecurityPolicyManager.globalScreenshotBlocked.collectAsStateWithLifecycle()

    var selectedRailId by remember { mutableStateOf("dms") }
    var searchQuery by remember { mutableStateOf("") }
    var showArchived by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showOldChatCleanupDialog by remember { mutableStateOf(false) }
    var selectedConvForMenu by remember { mutableStateOf<ConversationSummary?>(null) }

    val activeGroups = remember(p2pGroups, archivedIds) {
        p2pGroups.filterNot { ChatArchiveManager.isArchived("group:${it.id}") }
    }
    val archivedGroups = remember(p2pGroups, archivedIds) {
        p2pGroups.filter { ChatArchiveManager.isArchived("group:${it.id}") }
    }
    val activeConversations = remember(conversations, archivedIds, searchQuery) {
        conversations
            .filterNot { ChatArchiveManager.isArchived(it.conversationID) }
            .filter {
                searchQuery.isBlank() ||
                    it.displayName.contains(searchQuery, ignoreCase = true) ||
                    it.latestMessagePreview.contains(searchQuery, ignoreCase = true)
            }
    }
    val archivedConversations = remember(conversations, archivedIds) {
        conversations.filter { ChatArchiveManager.isArchived(it.conversationID) }
    }

    if (showProfileDialog) {
        ProfileSetupDialog(
            initialNickname = nickname,
            isFirstLaunch = false,
            onSaveProfile = { newName ->
                viewModel.setNickname(newName)
                if (InternetRelayPreferences.isInternetEnabled()) {
                    coroutineScope.launch {
                        NemixUuidDirectoryManager.publishMyDirectoryRecord(context, newName)
                    }
                }
                showProfileDialog = false
            },
            onDismiss = { showProfileDialog = false }
        )
    }

    if (showCreateGroupDialog) {
        CreateOrJoinGroupDialog(
            myNickname = nickname,
            connectedPeers = connectedPeers,
            peerNicknames = peerNicknames,
            onGroupReady = { grp ->
                showCreateGroupDialog = false
                onOpenGroupChat(grp.id)
            },
            onDismiss = { showCreateGroupDialog = false }
        )
    }

    if (showOldChatCleanupDialog) {
        OldChatCleanupDialog(
            onArchiveOlderThanDays = { days ->
                showOldChatCleanupDialog = false
                val count = ChatArchiveManager.archiveChatsOlderThan(
                    context,
                    days * 24L * 60L * 60L * 1000L
                )
                Toast.makeText(context, "${days}日以上前のチャットを ${count}件 アーカイブしました", Toast.LENGTH_SHORT).show()
            },
            onDeleteOlderThanDays = { days ->
                showOldChatCleanupDialog = false
                coroutineScope.launch {
                    val count = ChatArchiveManager.deleteChatsOlderThan(
                        context,
                        days * 24L * 60L * 60L * 1000L
                    )
                    Toast.makeText(context, "${days}日以上前のチャットを ${count}件 削除しました", Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showOldChatCleanupDialog = false }
        )
    }

    selectedConvForMenu?.let { conv ->
        val isArch = ChatArchiveManager.isArchived(conv.conversationID)
        val blockedByMe = ChatSecurityPolicyManager.isBlockedByMe(conv.conversationID)
        AlertDialog(
            onDismissRequest = { selectedConvForMenu = null },
            title = { Text("@${conv.displayName}") },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text(if (isArch) "アーカイブを解除" else "チャットをアーカイブ") },
                        leadingContent = { Icon(if (isArch) Icons.Filled.Unarchive else Icons.Filled.Archive, null) },
                        modifier = Modifier.clickable {
                            ChatArchiveManager.toggleArchived(context, conv.conversationID)
                            selectedConvForMenu = null
                        }
                    )
                    ListItem(
                        headlineContent = {
                            Text(if (blockedByMe) "スクショ禁止をOFF" else "スクショ禁止をON（相手側にも適用）")
                        },
                        leadingContent = { Icon(Icons.Filled.ScreenLockPortrait, null) },
                        modifier = Modifier.clickable {
                            ChatSecurityPolicyManager.setMyChatScreenshotBlock(
                                context = context,
                                peerOrConversationID = conv.conversationID,
                                peerDisplayName = conv.displayName,
                                myNickname = nickname,
                                blocked = !blockedByMe
                            )
                            selectedConvForMenu = null
                        }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("この端末からチャットを削除", color = MaterialTheme.colorScheme.error) },
                        leadingContent = { Icon(Icons.Filled.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable {
                            val id = conv.conversationID
                            selectedConvForMenu = null
                            coroutineScope.launch { viewModel.deletePrivateConversation(id) }
                        }
                    )
                    ListItem(
                        headlineContent = { Text("双方の端末から削除", color = MaterialTheme.colorScheme.error) },
                        leadingContent = { Icon(Icons.Filled.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable {
                            val id = conv.conversationID
                            val name = conv.displayName
                            selectedConvForMenu = null
                            ChatSecurityPolicyManager.sendPeerDeleteCommand(
                                context = context,
                                peerOrConversationID = id,
                                peerDisplayName = name,
                                myNickname = nickname,
                                deleteAll = true
                            )
                            coroutineScope.launch { viewModel.deletePrivateConversation(id) }
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { selectedConvForMenu = null }) { Text("閉じる") }
            }
        )
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(ChannelModeColors.RailBg)
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        // Left ChannelMode Server Rail
        LazyColumn(
            modifier = Modifier
                .width(68.dp)
                .fillMaxHeight()
                .background(ChannelModeColors.RailBg)
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // DM Home Button
            item(key = "rail_dms") {
                val active = selectedRailId == "dms"
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(if (active) 16.dp else 24.dp))
                        .background(if (active) ChannelModeColors.Blurple else ChannelModeColors.ChatBg)
                        .clickable { selectedRailId = "dms" },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Forum,
                        contentDescription = "ダイレクトメッセージ",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            item(key = "rail_divider_1") {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(2.dp)
                        .background(Color.White.copy(alpha = 0.12f))
                )
            }

            // Local Mesh Broadcast Server (when nearbyVisible)
            if (nearbyVisible) {
                item(key = "rail_mesh") {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF1F6F5E))
                            .clickable { onOpenBroadcastChat() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.WifiTethering,
                            contentDescription = "メッシュ全体",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            // P2P Group Servers
            items(activeGroups, key = { "rail_grp_${it.id}" }) { group ->
                val unread = p2pGroupUnreads[group.id] ?: 0
                Box(contentAlignment = Alignment.TopEnd) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(ChannelModeColors.Blurple.copy(alpha = 0.85f))
                            .clickable { onOpenGroupChat(group.id) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = group.name.take(2).uppercase(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            fontFamily = BitchatFontFamily
                        )
                    }
                    if (unread > 0) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFF23F43)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = unread.coerceAtMost(99).toString(),
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Add / Join P2P Server (+)
            item(key = "rail_add_group") {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(ChannelModeColors.ChatBg)
                        .clickable { showCreateGroupDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "サーバー追加",
                        tint = ChannelModeColors.Green,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // Switch to classic-format UI
            item(key = "rail_switch_wa") {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(ChannelModeColors.ChatBg)
                        .clickable {
                            AppUiStyleManager.set(context, AppUiStyle.CLASSIC)
                            Toast.makeText(context, "クラシックUIに切り替えました", Toast.LENGTH_SHORT).show()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.ChatBubbleOutline,
                        contentDescription = "クラシックUIへ切替",
                        tint = ChannelModeColors.TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Settings
            item(key = "rail_settings") {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(ChannelModeColors.ChatBg)
                        .clickable { onShowSettings() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "設定",
                        tint = ChannelModeColors.TextMuted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Right ChannelMode DM / Channel Drawer + Bottom User Status Bar
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topStart = 14.dp))
                .background(ChannelModeColors.SidebarBg)
        ) {
            // Drawer Header
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ChannelModeColors.SidebarBg)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ダイレクトメッセージ",
                        color = ChannelModeColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        fontFamily = BitchatFontFamily
                    )
                    // Stealth / Nearby Visibility Pill
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (nearbyVisible) ChannelModeColors.Green.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f),
                        modifier = Modifier.clickable {
                            val next = !nearbyVisible
                            MeshServicePreferences.setNearbyVisible(next)
                            Toast.makeText(
                                context,
                                if (next) "周辺公開: ON" else "ステルス: OFF（QR・UUID追加のみ）",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (nearbyVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = null,
                                tint = if (nearbyVisible) ChannelModeColors.Green else ChannelModeColors.TextMuted,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (nearbyVisible) "周辺公開 ON" else "ステルス",
                                color = if (nearbyVisible) ChannelModeColors.Green else ChannelModeColors.TextMuted,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Search Box
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text("会話を検索または開始", color = ChannelModeColors.TextMuted, fontSize = 13.sp)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = ChannelModeColors.RailBg,
                        unfocusedContainerColor = ChannelModeColors.RailBg,
                        focusedTextColor = ChannelModeColors.TextPrimary,
                        unfocusedTextColor = ChannelModeColors.TextPrimary,
                        focusedBorderColor = ChannelModeColors.Blurple,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(8.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Quick Action Chips: QR Add, UUID Add, Old-Chat Cleanup
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ChannelModeQuickButton(
                        icon = Icons.Filled.QrCode,
                        label = "QR追加",
                        onClick = { viewModel.showVerificationSheet() },
                        modifier = Modifier.weight(1f)
                    )
                    ChannelModeQuickButton(
                        icon = Icons.Filled.PersonSearch,
                        label = "UUID追加",
                        onClick = onOpenUuidSearch,
                        modifier = Modifier.weight(1f)
                    )
                    ChannelModeQuickButton(
                        icon = Icons.Filled.AutoDelete,
                        label = "古い履歴整理",
                        onClick = { showOldChatCleanupDialog = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            HorizontalDivider(color = Color.Black.copy(alpha = 0.28f), thickness = 1.dp)

            // Main DM & Nearby & Archive List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            ) {
                val archivedTotal = archivedConversations.size + archivedGroups.size
                if (archivedTotal > 0) {
                    item(key = "channels_archived_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showArchived = !showArchived }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Archive,
                                contentDescription = null,
                                tint = ChannelModeColors.TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "アーカイブ済み ($archivedTotal)",
                                color = ChannelModeColors.TextMuted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = if (showArchived) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = ChannelModeColors.TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    if (showArchived) {
                        items(archivedGroups, key = { "arch_grp_${it.id}" }) { grp ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onOpenGroupChat(grp.id) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Tag, null, tint = ChannelModeColors.TextMuted, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = grp.name,
                                    color = ChannelModeColors.TextMuted,
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { ChatArchiveManager.setArchived(context, "group:${grp.id}", false) }) {
                                    Text("解除", fontSize = 11.sp, color = ChannelModeColors.Blurple)
                                }
                            }
                        }
                        items(archivedConversations, key = { "arch_dm_${it.conversationID}" }) { conv ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .combinedClickable(
                                        onClick = { onOpenPrivateChat(conv.conversationID) },
                                        onLongClick = { selectedConvForMenu = conv }
                                    )
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.AlternateEmail, null, tint = ChannelModeColors.TextMuted, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = conv.displayName,
                                    color = ChannelModeColors.TextMuted,
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { ChatArchiveManager.setArchived(context, conv.conversationID, false) }) {
                                    Text("解除", fontSize = 11.sp, color = ChannelModeColors.Blurple)
                                }
                            }
                        }
                    }
                }

                if (nearbyVisible) {
                    item(key = "channels_broadcast_channel") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenBroadcastChat() }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Tag,
                                contentDescription = null,
                                tint = ChannelModeColors.Green,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "メッシュ全体チャット",
                                    color = ChannelModeColors.TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = "${connectedPeers.size}台の周辺ノードがオンライン",
                                    color = ChannelModeColors.TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }

                item(key = "dm_label") {
                    Text(
                        text = "ダイレクトメッセージ",
                        color = ChannelModeColors.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 10.dp, top = 12.dp, bottom = 4.dp)
                    )
                }

                if (activeConversations.isEmpty()) {
                    item(key = "empty_channel_dms") {
                        Text(
                            text = if (nearbyVisible) {
                                "まだDMがありません。上のQR追加・UUID追加、または周辺ノードから会話を始められます。"
                            } else {
                                "ステルスモード（周辺非公開）です。QR追加またはUUID追加からフレンドを追加してください。"
                            },
                            color = ChannelModeColors.TextMuted,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    items(activeConversations, key = { it.conversationID }) { conv ->
                        val peerAvatar = UserProfileManager.getPeerAvatar(peerAvatars, conv.conversationID, conv.displayName)
                        val isSs = remember(conv.conversationID, myBlockedChats, peerBlockedChats, globalSsBlocked) {
                            ChatSecurityPolicyManager.isScreenshotBlockedForConversation(conv.conversationID)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .combinedClickable(
                                    onClick = { onOpenPrivateChat(conv.conversationID) },
                                    onLongClick = { selectedConvForMenu = conv }
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ClassicAvatar(
                                name = conv.displayName,
                                size = 38.dp,
                                backgroundColor = ChannelModeColors.Blurple,
                                isOnline = conv.isConnected,
                                avatarBitmap = peerAvatar
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = conv.displayName,
                                        color = ChannelModeColors.TextPrimary,
                                        fontWeight = if (conv.unreadCount > 0) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (isSs) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Filled.ScreenLockPortrait,
                                            contentDescription = null,
                                            tint = ChannelModeColors.Green,
                                            modifier = Modifier.size(13.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = conv.latestMessagePreview.ifBlank { "E2EE ダイレクトメッセージ" },
                                    color = ChannelModeColors.TextMuted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (conv.unreadCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(Color(0xFFF23F43))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = conv.unreadCount.toString(),
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // Nearby discovered peers section (only when nearbyVisible == true)
                if (nearbyVisible && connectedPeers.isNotEmpty()) {
                    item(key = "nearby_peers_header") {
                        Text(
                            text = "周辺の接続中ノード (${connectedPeers.size})",
                            color = ChannelModeColors.TextMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 10.dp, top = 14.dp, bottom = 4.dp)
                        )
                    }
                    items(connectedPeers, key = { "near_$it" }) { peerId ->
                        val pName = peerNicknames[peerId] ?: peerId.take(8)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenPrivateChat(peerId) }
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(ChannelModeColors.Green)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = pName,
                                color = ChannelModeColors.TextPrimary,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "DMを開く",
                                color = ChannelModeColors.Blurple,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // Bottom ChannelMode User Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ChannelModeColors.UserBarBg)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .clickable { showProfileDialog = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ClassicAvatar(
                    name = nickname,
                    size = 34.dp,
                    backgroundColor = ChannelModeColors.Blurple,
                    isOnline = nearbyVisible || internetEnabled,
                    avatarBitmap = myAvatarBitmap
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = nickname.ifBlank { "nemix" },
                        color = ChannelModeColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                    Text(
                        text = when {
                            !nearbyVisible -> "ステルス (QR/UUIDのみ)"
                            internetEnabled -> "オンライン (P2P + ネット)"
                            else -> "ローカルP2Pメッシュ"
                        },
                        color = ChannelModeColors.TextMuted,
                        fontSize = 10.sp
                    )
                }
                IconButton(onClick = onShowSettings, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = "設定",
                        tint = ChannelModeColors.TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelModeQuickButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = ChannelModeColors.ChatBg,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = ChannelModeColors.TextPrimary, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                color = ChannelModeColors.TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

/**
 * Full Channel-format 1-on-1 / Broadcast Chat Screen (`#313338` flat dark stream)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelModeChatScreen(
    chatTarget: String,
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onShowSecurityVerification: () -> Unit
) {
    val isBroadcast = chatTarget == "mesh_broadcast"
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val publicMessages by viewModel.messages.collectAsStateWithLifecycle()
    val privateChats by viewModel.privateChats.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val peerSessionStates by viewModel.peerSessionStates.collectAsStateWithLifecycle()
    val favoritePeers by viewModel.favoritePeers.collectAsStateWithLifecycle()
    val peerAvatars by UserProfileManager.peerAvatars.collectAsStateWithLifecycle()
    val myAvatarBitmap by UserProfileManager.myAvatarBitmap.collectAsStateWithLifecycle()
    val internetEnabled by InternetRelayPreferences.internetEnabled.collectAsStateWithLifecycle()
    val myBlockedChats by ChatSecurityPolicyManager.myBlockedChats.collectAsStateWithLifecycle()
    val peerBlockedChats by ChatSecurityPolicyManager.peerBlockedChats.collectAsStateWithLifecycle()
    val globalSsBlocked by ChatSecurityPolicyManager.globalScreenshotBlocked.collectAsStateWithLifecycle()
    val archivedIds by ChatArchiveManager.archivedIds.collectAsStateWithLifecycle()

    val unifiedMesh = remember(context) { MeshServiceHolder.getUnifiedOrCreate(context) }
    val router = remember(context, unifiedMesh) { MessageRouter.getInstance(context, unifiedMesh) }

    LaunchedEffect(chatTarget) {
        if (!isBroadcast) viewModel.startPrivateChat(chatTarget)
    }

    val canonicalTarget = remember(chatTarget) {
        if (isBroadcast) chatTarget else ContactDirectory.canonicalConversationId(chatTarget)
    }
    val resolvedContact = remember(isBroadcast, chatTarget, connectedPeers, favoritePeers, peerNicknames) {
        if (isBroadcast) null else ContactDirectory.resolve(chatTarget)
    }
    val liveMeshPeerID = resolvedContact?.meshPeerID
    val isConnected = !isBroadcast && (
        connectedPeers.contains(chatTarget) ||
            (liveMeshPeerID != null && connectedPeers.contains(liveMeshPeerID))
        )

    val title = remember(isBroadcast, chatTarget, canonicalTarget, peerNicknames, resolvedContact, conversations) {
        if (isBroadcast) {
            "メッシュ全体チャット"
        } else {
            peerNicknames[chatTarget]
                ?: liveMeshPeerID?.let { peerNicknames[it] }
                ?: resolvedContact?.displayName
                ?: conversations.firstOrNull {
                    it.conversationID.equals(chatTarget, ignoreCase = true) ||
                        it.conversationID.equals(canonicalTarget, ignoreCase = true)
                }?.displayName
                ?: AppStateStore.privateConversationDisplayNames.value[canonicalTarget]
                ?: chatTarget.removePrefix("contact_").removePrefix("nostr_").take(8)
        }
    }

    val messages = remember(isBroadcast, chatTarget, canonicalTarget, publicMessages, privateChats) {
        if (isBroadcast) publicMessages
        else privateChats[chatTarget] ?: privateChats[canonicalTarget] ?: emptyList()
    }

    val hasLocalRoute = remember(isBroadcast, chatTarget, isConnected, peerSessionStates) {
        !isBroadcast && (isConnected || router.hasMeshRoute(chatTarget))
    }
    val hasInternetRoute = remember(isBroadcast, chatTarget, favoritePeers, internetEnabled) {
        !isBroadcast && internetEnabled && router.hasInternetRoute(chatTarget)
    }
    val blockedByMe = remember(isBroadcast, chatTarget, myBlockedChats) {
        !isBroadcast && ChatSecurityPolicyManager.isBlockedByMe(chatTarget)
    }
    val isSsProtected = remember(isBroadcast, chatTarget, myBlockedChats, peerBlockedChats, globalSsBlocked) {
        ChatSecurityPolicyManager.isScreenshotBlockedForConversation(chatTarget)
    }
    val isArchived = remember(isBroadcast, chatTarget, archivedIds) {
        !isBroadcast && ChatArchiveManager.isArchived(chatTarget)
    }

    var inputText by remember(chatTarget) { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showCallDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var pendingCallMode by remember { mutableStateOf<CallTransportMode?>(null) }

    val callMicLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val mode = pendingCallMode
        pendingCallMode = null
        if (granted && mode != null) {
            WirelessCallManager.startOutgoingCall(context, chatTarget, title, nickname, mode)
                .onFailure { Toast.makeText(context, it.message ?: "発信失敗", Toast.LENGTH_LONG).show() }
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val savedPath = withContext(Dispatchers.IO) {
                    ImageUtils.downscaleAndSaveToAppFiles(context, uri)
                }
                if (savedPath != null) {
                    viewModel.sendImageNote(if (isBroadcast) null else chatTarget, null, savedPath)
                }
            }
        }
    }

    val locationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            coroutineScope.launch {
                LocationShareHelper.fetchCurrentLocationOnce(context).onSuccess { loc ->
                    viewModel.sendMessage(LocationShareHelper.encode(loc))
                }
            }
        }
    }

    if (showCallDialog && !isBroadcast) {
        CallTransportPickerDialog(
            peerName = title,
            hasLocalRoute = hasLocalRoute,
            hasInternetRoute = hasInternetRoute,
            onSelectMode = { mode ->
                showCallDialog = false
                if (WirelessCallManager.hasAudioPermission(context)) {
                    WirelessCallManager.startOutgoingCall(context, chatTarget, title, nickname, mode)
                        .onFailure { Toast.makeText(context, it.message ?: "発信失敗", Toast.LENGTH_LONG).show() }
                } else {
                    pendingCallMode = mode
                    callMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onDismiss = { showCallDialog = false }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("「$title」の履歴を削除") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            showDeleteDialog = false
                            if (isBroadcast) {
                                AppStateStore.clearPublicTimelines()
                            } else {
                                coroutineScope.launch { viewModel.deletePrivateConversation(chatTarget) }
                                onBack()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("この端末からのみ削除") }
                    if (!isBroadcast) {
                        Button(
                            onClick = {
                                showDeleteDialog = false
                                ChatSecurityPolicyManager.sendPeerDeleteCommand(
                                    context = context,
                                    peerOrConversationID = chatTarget,
                                    peerDisplayName = title,
                                    myNickname = nickname,
                                    deleteAll = true
                                )
                                coroutineScope.launch { viewModel.deletePrivateConversation(chatTarget) }
                                onBack()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("相手の端末からも削除（双方削除）", color = Color.White) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("キャンセル") } }
        )
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ChannelModeColors.ChatBg)
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding()
    ) {
        // ChannelMode Top Channel / DM Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ChannelModeColors.SidebarBg)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, "戻る", tint = ChannelModeColors.TextPrimary)
            }
            Icon(
                imageVector = if (isBroadcast) Icons.Filled.Tag else Icons.Filled.AlternateEmail,
                contentDescription = null,
                tint = ChannelModeColors.TextMuted,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        color = ChannelModeColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isSsProtected) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Icon(
                            Icons.Filled.ScreenLockPortrait,
                            null,
                            tint = ChannelModeColors.Green,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                Text(
                    text = when {
                        isBroadcast -> "ローカルP2Pメッシュ全体チャンネル"
                        isConnected -> "近距離P2P接続中 · E2EE"
                        hasInternetRoute -> "オンライン (ネット経由 · E2EE)"
                        else -> "オフライン (近づくと自動送信)"
                    },
                    color = ChannelModeColors.TextMuted,
                    fontSize = 11.sp
                )
            }

            if (!isBroadcast) {
                IconButton(onClick = { showCallDialog = true }) {
                    Icon(Icons.Filled.PhoneInTalk, "音声通話", tint = ChannelModeColors.TextPrimary)
                }
                IconButton(
                    onClick = {
                        ChatSecurityPolicyManager.setMyChatScreenshotBlock(
                            context = context,
                            peerOrConversationID = chatTarget,
                            peerDisplayName = title,
                            myNickname = nickname,
                            blocked = !blockedByMe
                        )
                    }
                ) {
                    Icon(
                        Icons.Filled.ScreenLockPortrait,
                        "スクショ禁止",
                        tint = if (isSsProtected) ChannelModeColors.Green else ChannelModeColors.TextMuted
                    )
                }
            }

            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Filled.MoreVert, "メニュー", tint = ChannelModeColors.TextPrimary)
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    if (!isBroadcast) {
                        DropdownMenuItem(
                            text = { Text(if (isArchived) "アーカイブを解除" else "チャットをアーカイブ") },
                            leadingIcon = { Icon(if (isArchived) Icons.Filled.Unarchive else Icons.Filled.Archive, null) },
                            onClick = {
                                showMenu = false
                                val nowArch = ChatArchiveManager.toggleArchived(context, canonicalTarget)
                                if (nowArch) onBack()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("暗号化検証・セキュリティ") },
                            leadingIcon = { Icon(Icons.Filled.Security, null) },
                            onClick = {
                                showMenu = false
                                onShowSecurityVerification()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("チャット履歴を削除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(Icons.Filled.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            showDeleteDialog = true
                        }
                    )
                }
            }
        }

        HorizontalDivider(color = Color.Black.copy(alpha = 0.35f), thickness = 1.dp)

        // Flat ChannelMode Message Stream (reverseLayout = true so newest is at bottom)
        val reversed = remember(messages) { messages.asReversed() }
        val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

        LazyColumn(
            state = listState,
            reverseLayout = true,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(reversed, key = { it.id }) { msg ->
                if (msg.sender == "system") {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = ChannelModeColors.SidebarBg,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = msg.content,
                            color = ChannelModeColors.TextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }
                } else {
                    val isMe = msg.sender == nickname || msg.senderPeerID == viewModel.meshService.myPeerID
                    val displaySender = if (isMe) nickname.ifBlank { "自分" } else msg.sender.ifBlank { title }
                    val avatarBmp = if (isMe) myAvatarBitmap else UserProfileManager.getPeerAvatar(peerAvatars, chatTarget, displaySender)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        ClassicAvatar(
                            name = displaySender,
                            size = 38.dp,
                            backgroundColor = if (isMe) ChannelModeColors.Green else ChannelModeColors.Blurple,
                            avatarBitmap = avatarBmp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = displaySender,
                                    color = if (isMe) ChannelModeColors.Green else Color(0xFF99AAB5),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = timeFmt.format(msg.timestamp),
                                    color = ChannelModeColors.TextMuted,
                                    fontSize = 11.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            when {
                                msg.type == BitchatMessageType.Image -> {
                                    val bmp = remember(msg.content) {
                                        ImageUtils.loadBitmapWithExifOrientation(msg.content.trim())
                                    }
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = "画像",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .widthIn(max = 240.dp)
                                                .heightIn(max = 220.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                        )
                                    } else {
                                        Text("[暗号化画像]", color = ChannelModeColors.TextPrimary, fontSize = 14.sp)
                                    }
                                }
                                LocationShareHelper.isLocationMessage(msg.content) -> {
                                    val loc = LocationShareHelper.parse(msg.content)
                                    if (loc != null) {
                                        LocationMessageCard(location = loc, isDark = true)
                                    }
                                }
                                else -> {
                                    Text(
                                        text = msg.content,
                                        color = ChannelModeColors.TextPrimary,
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ChannelMode Message Input Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ChannelModeColors.ChatBg)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { imagePickerLauncher.launch("image/*") },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Filled.AddCircle, "画像送信", tint = ChannelModeColors.TextMuted)
            }
            IconButton(
                onClick = {
                    if (LocationShareHelper.hasLocationPermission(context)) {
                        coroutineScope.launch {
                            LocationShareHelper.fetchCurrentLocationOnce(context).onSuccess { loc ->
                                viewModel.sendMessage(LocationShareHelper.encode(loc))
                            }
                        }
                    } else {
                        locationPermLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Filled.LocationOn, "位置情報", tint = ChannelModeColors.TextMuted)
            }
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = {
                    Text(
                        text = if (isBroadcast) "#メッシュ全体 へメッセージを送信" else "@$title へメッセージを送信",
                        color = ChannelModeColors.TextMuted,
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = ChannelModeColors.InputBg,
                    unfocusedContainerColor = ChannelModeColors.InputBg,
                    focusedTextColor = ChannelModeColors.TextPrimary,
                    unfocusedTextColor = ChannelModeColors.TextPrimary,
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                )
            )
            Spacer(modifier = Modifier.width(6.dp))
            IconButton(
                onClick = {
                    val trimmed = inputText.trim()
                    if (trimmed.isNotEmpty()) {
                        viewModel.sendMessage(trimmed)
                        inputText = ""
                    }
                },
                enabled = inputText.isNotBlank()
            ) {
                Icon(
                    imageVector = Icons.Filled.Send,
                    contentDescription = "送信",
                    tint = if (inputText.isNotBlank()) ChannelModeColors.Blurple else ChannelModeColors.TextMuted
                )
            }
        }
    }
}
