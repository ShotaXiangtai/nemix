package com.bitchat.android.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.outlined.Lock
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
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.features.location.LocationMessageCard
import com.bitchat.android.features.location.LocationShareHelper
import com.bitchat.android.features.media.ImageUtils
import com.bitchat.android.groups.P2PGroup
import com.bitchat.android.groups.P2PGroupChannel
import com.bitchat.android.groups.P2PGroupManager
import com.bitchat.android.groups.P2PGroupMember
import com.bitchat.android.groups.P2PGroupMessage
import com.bitchat.android.groups.P2PGroupStyle
import com.bitchat.android.profile.UserProfileManager
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.ui.media.FullScreenImageViewer
import com.bitchat.android.ui.media.ImagePickerButton
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.LocalBitchatPalette
import com.bitchat.android.ui.theme.ClassicColors
import com.bitchat.android.ui.theme.colorForPeer
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 100% P2P Encrypted Group Screen supporting two switchable layouts:
 * - [P2PGroupStyle.STREAM]: Stream-format unified message bubble stream
 * - [P2PGroupStyle.CHANNELS]: Channel-format server layout with categorized `#channels`, flat feed & member roles
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P2PGroupScreen(
    groupId: String,
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val groups by P2PGroupManager.groups.collectAsStateWithLifecycle()
    val allGroupMessages by P2PGroupManager.groupMessages.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val peerAvatars by UserProfileManager.peerAvatars.collectAsStateWithLifecycle()

    val group = remember(groups, groupId) { groups.firstOrNull { it.id == groupId } }

    DisposableEffect(groupId) {
        P2PGroupManager.setOpenGroup(groupId)
        onDispose {
            P2PGroupManager.setOpenGroup(null)
        }
    }

    if (group == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val isChannelMode = group.style == P2PGroupStyle.CHANNELS
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red < 0.5f

    var selectedChannelId by remember(groupId) {
        mutableStateOf(group.channels.firstOrNull()?.id ?: "general")
    }
    var currentViewTopicId by remember(groupId) { mutableStateOf<String?>(null) }

    BackHandler(enabled = isChannelMode && currentViewTopicId != null) {
        currentViewTopicId = null
    }

    LaunchedEffect(group.channels) {
        if (group.channels.none { it.id == selectedChannelId }) {
            selectedChannelId = group.channels.firstOrNull()?.id ?: "general"
        }
    }

    val activeChannel = remember(group.channels, selectedChannelId, currentViewTopicId) {
        val targetId = currentViewTopicId ?: selectedChannelId
        group.channels.firstOrNull { it.id == targetId }
            ?: P2PGroupChannel("general", "一般", "テキストチャンネル")
    }

    val rawMessages = allGroupMessages[groupId].orEmpty()
    val visibleMessages = remember(rawMessages, isChannelMode, selectedChannelId, currentViewTopicId) {
        if (isChannelMode) {
            val targetId = currentViewTopicId ?: selectedChannelId
            rawMessages.filter {
                it.channelId == targetId ||
                    (targetId == "general" && it.channelId.isBlank())
            }
        } else {
            rawMessages
        }
    }

    var inputText by remember(groupId, selectedChannelId) { mutableStateOf("") }
    var replyTarget by remember(groupId, selectedChannelId) { mutableStateOf<P2PGroupMessage?>(null) }
    var showChannelDrawer by remember(groupId) { mutableStateOf(false) }
    var showMemberSheet by remember { mutableStateOf(false) }
    var showAddChannelDialog by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }
    var selectedMessageForAction by remember { mutableStateOf<P2PGroupMessage?>(null) }
    var activeViewerImagePath by remember { mutableStateOf<String?>(null) }
    var isLocating by remember { mutableStateOf(false) }

    val meMember = remember(context, nickname) {
        P2PGroupManager.myMemberIdentity(context, nickname)
    }

    val listState = rememberLazyListState()
    LaunchedEffect(visibleMessages.size, selectedChannelId) {
        if (visibleMessages.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    fun shareGroupLocation() {
        if (isLocating) return
        isLocating = true
        Toast.makeText(context, "OS標準測位で現在地を取得中...", Toast.LENGTH_SHORT).show()
        coroutineScope.launch {
            val res = LocationShareHelper.fetchCurrentLocationOnce(context)
            isLocating = false
            res.onSuccess { loc ->
                P2PGroupManager.sendGroupTextMessage(
                    context = context,
                    groupId = group.id,
                    channelId = if (isChannelMode) activeChannel.id else "general",
                    content = LocationShareHelper.encode(loc),
                    myNickname = nickname,
                    isLocation = true,
                    replyTo = replyTarget
                )
                replyTarget = null
            }.onFailure { err ->
                Toast.makeText(context, err.message ?: "位置情報を取得できませんでした", Toast.LENGTH_LONG).show()
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val ok = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) shareGroupLocation()
    }

    // Fullscreen image viewer
    activeViewerImagePath?.let { path ->
        val allImages = visibleMessages
            .filter { it.type == "IMAGE" && File(it.content).exists() }
            .map { it.content }
        val list = if (allImages.contains(path)) allImages else listOf(path)
        FullScreenImageViewer(
            imagePaths = list,
            initialIndex = list.indexOf(path).coerceAtLeast(0),
            onClose = { activeViewerImagePath = null }
        )
    }

    // Palette per style
    val channelModeHeaderBg = if (isDark) Color(0xFF2B2D31) else Color(0xFFF2F3F5)
    val channelModeBodyBg = if (isDark) Color(0xFF313338) else Color(0xFFFFFFFF)
    val channelModeRailBg = if (isDark) Color(0xFF1E1F22) else Color(0xFFE3E5E8)
    val channelModeAccent = Color(0xFF5865F2) // channel-format accent
    val streamHeaderBg = if (isDark) Color(0xFF1C2733) else Color(0xFF2481CC) // stream-format header
    val streamWallpaper = if (isDark) Color(0xFF0E1621) else Color(0xFFE6EBEE)

    val topBarBg = if (isChannelMode) channelModeHeaderBg else streamHeaderBg
    val topBarContentColor = if (isChannelMode && !isDark) Color(0xFF111214) else Color.White

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(topBarBg)
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        if (isChannelMode && currentViewTopicId != null) {
                            currentViewTopicId = null
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "戻る",
                            tint = topBarContentColor
                        )
                    }

                    if (isChannelMode && currentViewTopicId != null) {
                        // Inside Topic Chat Top Bar (Screenshot 3)
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(channelModeAccent.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "#",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = channelModeAccent
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "# ${activeChannel.name}",
                                    color = topBarContentColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (group.screenshotBlocked) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Filled.ScreenLockPortrait,
                                        contentDescription = "スクショ禁止",
                                        tint = Color(0xFFED4245),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                            Text(
                                text = "在 ${group.name}",
                                color = topBarContentColor.copy(alpha = 0.72f),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else if (isChannelMode) {
                        // Group Topics View Top Bar (Screenshot 2)
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF70B647)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = group.name.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = group.name,
                                    color = topBarContentColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (group.screenshotBlocked) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Filled.ScreenLockPortrait,
                                        contentDescription = "スクショ禁止",
                                        tint = Color(0xFFED4245),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                            Text(
                                text = "${group.members.size}人のメンバー · チャンネル形式",
                                color = topBarContentColor.copy(alpha = 0.72f),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    } else {
                        // Stream Single-Thread Top Bar
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = group.name.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 17.sp
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showMemberSheet = true }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = group.name,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (group.screenshotBlocked) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Filled.ScreenLockPortrait,
                                        contentDescription = "スクショ禁止",
                                        tint = Color(0xFFFFD54F),
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                            Text(
                                text = "${group.members.size}人のメンバー · ストリーム形式 (P2P E2EE)",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Quick 1-tap Style Switch Pill
                    IconButton(
                        onClick = {
                            val targetStyle = if (isChannelMode) P2PGroupStyle.STREAM else P2PGroupStyle.CHANNELS
                            P2PGroupManager.updateGroupStyle(context, group.id, targetStyle, nickname)
                        }
                    ) {
                        Icon(
                            imageVector = if (isChannelMode) Icons.Filled.ChatBubbleOutline else Icons.Filled.Forum,
                            contentDescription = "スタイル切替",
                            tint = topBarContentColor
                        )
                    }

                    IconButton(onClick = { showMemberSheet = true }) {
                        Icon(
                            imageVector = Icons.Filled.Group,
                            contentDescription = "メンバー",
                            tint = topBarContentColor
                        )
                    }

                    Box {
                        IconButton(onClick = { showTopMenu = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "メニュー",
                                tint = topBarContentColor
                            )
                        }
                        DropdownMenu(
                            expanded = showTopMenu,
                            onDismissRequest = { showTopMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isChannelMode) "ストリーム形式 (単一スレッド表示) に切替"
                                        else "チャンネル形式 (トピック別表示) に切替"
                                    )
                                },
                                leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) },
                                onClick = {
                                    showTopMenu = false
                                    val next = if (isChannelMode) P2PGroupStyle.STREAM else P2PGroupStyle.CHANNELS
                                    P2PGroupManager.updateGroupStyle(context, group.id, next, nickname)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("メンバー招待 / P2P招待コード") },
                                leadingIcon = { Icon(Icons.Filled.PersonAdd, null) },
                                onClick = {
                                    showTopMenu = false
                                    showInviteDialog = true
                                }
                            )
                            if (isChannelMode) {
                                DropdownMenuItem(
                                    text = { Text("トピック / チャンネルを追加") },
                                    leadingIcon = { Icon(Icons.Filled.Add, null) },
                                    onClick = {
                                        showTopMenu = false
                                        showAddChannelDialog = true
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("ピン留めお知らせを編集") },
                                leadingIcon = { Icon(Icons.Filled.PushPin, null) },
                                onClick = {
                                    showTopMenu = false
                                    showPinDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (group.screenshotBlocked) "スクショ禁止をOFF（全員同期）"
                                        else "スクショ禁止をON（参加者全員の画面を保護）"
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.ScreenLockPortrait,
                                        null,
                                        tint = if (group.screenshotBlocked) ClassicColors.AccentGreen else LocalContentColor.current
                                    )
                                },
                                onClick = {
                                    showTopMenu = false
                                    P2PGroupManager.setGroupScreenshotBlocked(
                                        context = context,
                                        groupId = group.id,
                                        blocked = !group.screenshotBlocked,
                                        myNickname = nickname
                                    )
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("グループを退出・削除", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(Icons.Filled.ExitToApp, null, tint = MaterialTheme.colorScheme.error)
                                },
                                onClick = {
                                    showTopMenu = false
                                    showLeaveConfirm = true
                                }
                            )
                        }
                    }
                }

                // Pinned Notice / Screenshot Protection Banner
                if (!group.pinnedNotice.isNullOrBlank() || group.screenshotBlocked) {
                    Surface(
                        color = if (isChannelMode) {
                            channelModeAccent.copy(alpha = 0.14f)
                        } else {
                            Color.Black.copy(alpha = 0.18f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (!group.pinnedNotice.isNullOrBlank()) Icons.Filled.PushPin else Icons.Filled.Shield,
                                contentDescription = null,
                                tint = topBarContentColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = buildString {
                                    if (!group.pinnedNotice.isNullOrBlank()) {
                                        append(group.pinnedNotice)
                                    }
                                    if (group.screenshotBlocked) {
                                        if (isNotEmpty()) append("  ·  ")
                                        append("🔒 スクショ禁止保護中")
                                    }
                                },
                                fontSize = 12.sp,
                                color = topBarContentColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        if (isChannelMode && currentViewTopicId == null) {
            // Group Topics View (Screenshot 2)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(channelModeBodyBg)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp)
                ) {
                    items(group.channels, key = { it.id }) { ch ->
                        val chMessages = rawMessages.filter {
                            it.channelId == ch.id || (ch.id == "general" && it.channelId.isBlank())
                        }
                        val lastMsg = chMessages.lastOrNull()
                        val timeFmt = SimpleDateFormat("MM/dd", Locale.getDefault())
                        val timeStr = if (lastMsg != null) timeFmt.format(Date(lastMsg.timestamp)) else ""

                        TopicListItemRow(
                            channel = ch,
                            lastMessage = lastMsg,
                            timeString = timeStr,
                            unreadCount = 0,
                            onClick = {
                                selectedChannelId = ch.id
                                currentViewTopicId = ch.id
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 72.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                            thickness = 0.5.dp
                        )
                    }
                }

                FloatingActionButton(
                    onClick = { showAddChannelDialog = true },
                    containerColor = channelModeAccent,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PostAdd,
                        contentDescription = "新規トピック作成"
                    )
                }
            }
        } else {
            // Topic Chat View / Stream Chat View (Screenshot 3)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(if (isChannelMode) channelModeBodyBg else streamWallpaper)
            ) {
                // Messages Feed
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val reversed = remember(visibleMessages) { visibleMessages.asReversed() }
                    if (reversed.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize().padding(28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (isDark) Color(0xFF222B36) else Color.White,
                                shadowElevation = 3.dp,
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 28.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(text = "🎉", fontSize = 48.sp)
                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text(
                                        text = "就快完成了！",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "发送消息以开始话题。\n(メッセージを送信して話題を始めましょう)",
                                        fontSize = 13.sp,
                                        color = colorScheme.onSurfaceVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        lineHeight = 18.sp
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            reverseLayout = true,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            items(reversed, key = { it.id }) { msg ->
                                val isSelf = msg.senderId.equals(meMember.id, ignoreCase = true) ||
                                    msg.senderName == nickname
                                val peerAvatar = UserProfileManager.getPeerAvatar(
                                    peerAvatars,
                                    msg.senderNoiseHex ?: msg.senderId,
                                    msg.senderName
                                )

                                if (msg.type == "SYSTEM") {
                                    GroupSystemNoticeRow(text = msg.content, isDark = isDark)
                                } else {
                                    StreamBubbleMessageRow(
                                        message = msg,
                                        isSelf = isSelf,
                                        avatarBitmap = peerAvatar,
                                        isDark = isDark,
                                        viewModel = viewModel,
                                        onOpenImage = { activeViewerImagePath = it },
                                        onLongClick = { selectedMessageForAction = msg }
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                            }
                        }
                    }
                }

                // Reply Preview Bar
                replyTarget?.let { rep ->
                    Surface(
                        color = if (isChannelMode) channelModeHeaderBg else colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Reply,
                                contentDescription = null,
                                tint = if (isChannelMode) channelModeAccent else Color(0xFF2481CC),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "返信先 ${rep.senderName}: ${rep.content.take(45)}",
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { replyTarget = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Filled.Close, null, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // Group Message Composer
                GroupMessageComposer(
                    text = inputText,
                    placeholder = if (isChannelMode) "#${activeChannel.name} へメッセージを送信" else "グループメッセージを入力",
                    isChannelMode = isChannelMode,
                    isDark = isDark,
                    isLocating = isLocating,
                    accentColor = if (isChannelMode) channelModeAccent else Color(0xFF2481CC),
                    onTextChange = { inputText = it },
                    onSend = {
                        val trimmed = inputText.trim()
                        if (trimmed.isNotEmpty()) {
                            val chId = if (isChannelMode) (currentViewTopicId ?: activeChannel.id) else "general"
                            P2PGroupManager.sendGroupTextMessage(
                                context = context,
                                groupId = group.id,
                                channelId = chId,
                                content = trimmed,
                                myNickname = nickname,
                                replyTo = replyTarget
                            )
                            inputText = ""
                            replyTarget = null
                        }
                    },
                    onSendImage = { path ->
                        val chId = if (isChannelMode) (currentViewTopicId ?: activeChannel.id) else "general"
                        P2PGroupManager.sendGroupImageMessage(
                            context = context,
                            groupId = group.id,
                            channelId = chId,
                            sourcePath = path,
                            myNickname = nickname
                        )
                    },
                    onShareLocation = {
                        if (LocationShareHelper.hasLocationPermission(context)) {
                            shareGroupLocation()
                        } else {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    }
                )
            }
        }
    }

    // Message Long-Press Action Dialog
    selectedMessageForAction?.let { targetMsg ->
        AlertDialog(
            onDismissRequest = { selectedMessageForAction = null },
            title = { Text("${targetMsg.senderName} のメッセージ", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text("返信する") },
                        leadingContent = { Icon(Icons.Filled.Reply, null) },
                        modifier = Modifier.clickable {
                            replyTarget = targetMsg
                            selectedMessageForAction = null
                        }
                    )
                    if (targetMsg.type == "TEXT") {
                        ListItem(
                            headlineContent = { Text("テキストをコピー") },
                            leadingContent = { Icon(Icons.Filled.ContentCopy, null) },
                            modifier = Modifier.clickable {
                                copyToClipboard(context, targetMsg.content)
                                Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
                                selectedMessageForAction = null
                            }
                        )
                        ListItem(
                            headlineContent = { Text("グループ上部にピン留め") },
                            leadingContent = { Icon(Icons.Filled.PushPin, null) },
                            modifier = Modifier.clickable {
                                P2PGroupManager.setPinnedNotice(context, group.id, targetMsg.content, nickname)
                                selectedMessageForAction = null
                            }
                        )
                    }
                    ListItem(
                        headlineContent = { Text("この端末から削除") },
                        leadingContent = { Icon(Icons.Filled.DeleteOutline, null) },
                        modifier = Modifier.clickable {
                            P2PGroupManager.deleteGroupMessage(context, group.id, targetMsg.id, forEveryone = false, myNickname = nickname)
                            selectedMessageForAction = null
                        }
                    )
                    ListItem(
                        headlineContent = { Text("全員の端末から削除 (P2P同期)", color = MaterialTheme.colorScheme.error) },
                        leadingContent = { Icon(Icons.Filled.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable {
                            P2PGroupManager.deleteGroupMessage(context, group.id, targetMsg.id, forEveryone = true, myNickname = nickname)
                            selectedMessageForAction = null
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { selectedMessageForAction = null }) { Text("閉じる") }
            }
        )
    }

    // Add ChannelMode Channel Dialog
    if (showAddChannelDialog) {
        var chName by remember { mutableStateOf("") }
        var chCategory by remember { mutableStateOf("テキストチャンネル") }
        var chTopic by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddChannelDialog = false },
            title = { Text("# 新しいチャンネルを作成", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = chName,
                        onValueChange = { chName = it },
                        label = { Text("チャンネル名 (例: 作戦会議)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = chCategory,
                        onValueChange = { chCategory = it },
                        label = { Text("カテゴリー") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = chTopic,
                        onValueChange = { chTopic = it },
                        label = { Text("トピック・説明 (任意)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val added = P2PGroupManager.addGroupChannel(
                            context = context,
                            groupId = group.id,
                            channelNameRaw = chName,
                            categoryRaw = chCategory,
                            topicRaw = chTopic,
                            myNickname = nickname
                        )
                        if (added != null) {
                            selectedChannelId = added.id
                            showAddChannelDialog = false
                        }
                    }
                ) { Text("作成") }
            },
            dismissButton = {
                TextButton(onClick = { showAddChannelDialog = false }) { Text("キャンセル") }
            }
        )
    }

    // Edit Pinned Notice Dialog
    if (showPinDialog) {
        var pinInput by remember { mutableStateOf(group.pinnedNotice.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showPinDialog = false },
            title = { Text("ピン留めお知らせ", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it },
                    label = { Text("全員に表示するお知らせ（空欄で解除）") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    P2PGroupManager.setPinnedNotice(context, group.id, pinInput, nickname)
                    showPinDialog = false
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) { Text("キャンセル") }
            }
        )
    }

    // Member List Bottom Sheet
    if (showMemberSheet) {
        ModalBottomSheet(onDismissRequest = { showMemberSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .padding(bottom = 28.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "${group.name} のメンバー (${group.members.size}人)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = if (isChannelMode) "チャンネル形式 · ロール管理" else "ストリーム形式 · P2P暗号化メンバー",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = {
                        showMemberSheet = false
                        showInviteDialog = true
                    }) {
                        Icon(Icons.Filled.PersonAdd, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("招待")
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                group.members.forEach { m ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val palette = LocalBitchatPalette.current
                        val color = colorForPeer(viewModel.peerIdentityForMeshPeer(m.id), palette)
                        ClassicAvatar(name = m.nickname, backgroundColor = color, size = 40.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(m.nickname, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(
                                text = "ID: ${m.id.take(12)}…",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (m.role == "OWNER" || m.role == "ADMIN") {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF5865F2).copy(alpha = 0.16f)
                            ) {
                                Text(
                                    text = m.role,
                                    color = Color(0xFF5865F2),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Invite Member / Copy P2P Invite Link Dialog
    if (showInviteDialog) {
        val inviteUri = remember(group.id, nickname) {
            P2PGroupManager.buildInviteUri(group.id, nickname).orEmpty()
        }
        val candidatePeers = remember(connectedPeers, peerNicknames) {
            val favs = runCatching {
                FavoritesPersistenceService.shared.getOurFavorites().map { rel ->
                    ContactIdentityResolver.contactConversationIdForNoiseKey(rel.peerNoisePublicKey) to rel.peerNickname
                }
            }.getOrDefault(emptyList())
            val nearby = connectedPeers.map { pid -> pid to (peerNicknames[pid] ?: pid.take(8)) }
            (favs + nearby).distinctBy { it.first }
        }

        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text("メンバーをグループに招待", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "① P2P暗号化招待コードを共有（ネット・別アプリ経由でも参加可能）",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    OutlinedButton(
                        onClick = {
                            copyToClipboard(context, inviteUri)
                            Toast.makeText(context, "P2Pグループ招待コードをコピーしました", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.ContentCopy, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("招待コードをコピー")
                    }

                    HorizontalDivider()
                    Text(
                        text = "② 連絡先・周辺の端末へ直接E2EE招待を送信",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (candidatePeers.isEmpty()) {
                        Text(
                            text = "連絡先または周辺接続中の端末がありません。招待コードをコピーして相手に送ってください。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        candidatePeers.take(6).forEach { (targetId, targetName) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val route = P2PGroupManager.invitePeerToGroup(context, group.id, targetId, nickname)
                                        val message = when (route) {
                                            com.bitchat.android.services.MessageRouter.RouteResult.MESH,
                                            com.bitchat.android.services.MessageRouter.RouteResult.NOSTR ->
                                                "$targetName さんに招待を送信しました"
                                            com.bitchat.android.services.MessageRouter.RouteResult.QUEUED ->
                                                "$targetName さんは今つながっていません。接続されたら招待を送信します"
                                            com.bitchat.android.services.MessageRouter.RouteResult.DROPPED ->
                                                "招待を送信できませんでした。招待リンクをコピーして送ってください"
                                        }
                                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                    }
                                    .padding(vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(targetName, fontWeight = FontWeight.Medium)
                                Text("送信", color = ClassicColors.AccentGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInviteDialog = false }) { Text("完了") }
            }
        )
    }

    // Leave & Delete Confirmation
    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("グループを退出・削除しますか？") },
            text = { Text("この端末から「${group.name}」の暗号鍵と履歴を削除し、メンバーへ退出を通知します。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLeaveConfirm = false
                        P2PGroupManager.leaveAndDeleteGroup(context, group.id, nickname)
                        onBack()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("退出して削除") }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) { Text("キャンセル") }
            }
        )
    }
}

@Composable
private fun ChannelModeChannelSidebar(
    group: P2PGroup,
    selectedChannelId: String,
    isDark: Boolean,
    railBg: Color,
    accent: Color,
    onSelectChannel: (String) -> Unit,
    onAddChannel: () -> Unit,
    onInvite: () -> Unit
) {
    val grouped = remember(group.channels) { group.channels.groupBy { it.category.ifBlank { "テキストチャンネル" } } }
    val textColor = if (isDark) Color(0xFFDBDEE1) else Color(0xFF313338)
    val mutedColor = if (isDark) Color(0xFF949BA4) else Color(0xFF5C5E66)

    Column(
        modifier = Modifier
            .width(215.dp)
            .fillMaxHeight()
            .background(railBg)
            .padding(12.dp)
    ) {
        Text(
            text = group.name,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "完全P2Pサーバー · ${group.members.size}人",
            fontSize = 11.sp,
            color = mutedColor
        )
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = mutedColor.copy(alpha = 0.25f))
        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            grouped.forEach { (category, channels) ->
                item(key = "cat_$category") {
                    Text(
                        text = "▾ ${category.uppercase()}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = mutedColor,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                }
                items(channels, key = { it.id }) { ch ->
                    val isSelected = ch.id == selectedChannelId
                    Surface(
                        onClick = { onSelectChannel(ch.id) },
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSelected) accent.copy(alpha = 0.22f) else Color.Transparent,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "#",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = if (isSelected) accent else mutedColor
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = ch.name,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) textColor else mutedColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        OutlinedButton(
            onClick = onAddChannel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("チャンネル追加", fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Button(
            onClick = onInvite,
            colors = ButtonDefaults.buttonColors(containerColor = accent),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.PersonAdd, null, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("メンバー招待", fontSize = 12.sp)
        }
    }
}

@Composable
private fun ChannelModeFeedMessageRow(
    message: P2PGroupMessage,
    role: String,
    avatarBitmap: android.graphics.Bitmap?,
    isDark: Boolean,
    viewModel: ChatViewModel,
    onOpenImage: (String) -> Unit,
    onLongClick: () -> Unit
) {
    val palette = LocalBitchatPalette.current
    val nameColor = remember(message.senderId) {
        colorForPeer(viewModel.peerIdentityForMeshPeer(message.senderId), palette)
    }
    val timeFmt = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
    val textColor = if (isDark) Color(0xFFDBDEE1) else Color(0xFF1E1F22)
    val subColor = if (isDark) Color(0xFF949BA4) else Color(0xFF5C5E66)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = {}, onLongClick = onLongClick)
            .padding(vertical = 4.dp, horizontal = 4.dp)
    ) {
        if (!message.replyPreview.isNullOrBlank()) {
            Text(
                text = "╭ ↩ ${message.replyPreview}",
                fontSize = 11.sp,
                color = subColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 46.dp, bottom = 2.dp)
            )
        }
        Row(verticalAlignment = Alignment.Top) {
            ClassicAvatar(
                name = message.senderName,
                backgroundColor = nameColor,
                avatarBitmap = avatarBitmap,
                size = 38.dp
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message.senderName,
                        color = nameColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    if (role == "OWNER" || role == "ADMIN") {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF5865F2).copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = role,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF5865F2),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = timeFmt.format(Date(message.timestamp)),
                        fontSize = 11.sp,
                        color = subColor
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                GroupMessagePayloadBody(
                    message = message,
                    textColor = textColor,
                    isDark = isDark,
                    onOpenImage = onOpenImage
                )
            }
        }
    }
}

@Composable
private fun StreamBubbleMessageRow(
    message: P2PGroupMessage,
    isSelf: Boolean,
    avatarBitmap: android.graphics.Bitmap?,
    isDark: Boolean,
    viewModel: ChatViewModel,
    onOpenImage: (String) -> Unit,
    onLongClick: () -> Unit
) {
    val palette = LocalBitchatPalette.current
    val senderColor = remember(message.senderId) {
        colorForPeer(viewModel.peerIdentityForMeshPeer(message.senderId), palette)
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    val bubbleBg = when {
        isSelf && isDark -> Color(0xFF2B5278) // stream-format dark outgoing
        isSelf && !isDark -> Color(0xFFEFFDDE) // stream-format light outgoing
        !isSelf && isDark -> Color(0xFF182533)
        else -> Color.White
    }
    val textColor = if (isDark) Color.White else Color(0xFF111B21)
    val timeColor = if (isDark) Color(0xFF7D8E9E) else Color(0xFF627D98)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isSelf) {
            ClassicAvatar(
                name = message.senderName,
                backgroundColor = senderColor,
                avatarBitmap = avatarBitmap,
                size = 32.dp
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isSelf) 16.dp else 4.dp,
                bottomEnd = if (isSelf) 4.dp else 16.dp
            ),
            color = bubbleBg,
            shadowElevation = 1.dp,
            modifier = Modifier
                .widthIn(min = 72.dp, max = 300.dp)
                .combinedClickable(onClick = {}, onLongClick = onLongClick)
        ) {
            Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp)) {
                if (!isSelf) {
                    Text(
                        text = message.senderName,
                        color = senderColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
                if (!message.replyPreview.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(senderColor.copy(alpha = 0.14f))
                            .border(1.dp, senderColor.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = message.replyPreview,
                            fontSize = 11.sp,
                            color = textColor.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                GroupMessagePayloadBody(
                    message = message,
                    textColor = textColor,
                    isDark = isDark,
                    onOpenImage = onOpenImage
                )

                Text(
                    text = timeFmt.format(Date(message.timestamp)),
                    fontSize = 10.sp,
                    color = timeColor,
                    modifier = Modifier.align(Alignment.End).padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun GroupMessagePayloadBody(
    message: P2PGroupMessage,
    textColor: Color,
    isDark: Boolean,
    onOpenImage: (String) -> Unit
) {
    when (message.type) {
        "IMAGE" -> {
            val path = message.content
            val exists = remember(path) { File(path).exists() }
            val bmp = remember(path, exists) {
                if (exists) ImageUtils.loadBitmapWithExifOrientation(path) else null
            }
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "暗号化グループ画像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(228.dp)
                        .heightIn(min = 120.dp, max = 230.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenImage(path) }
                )
            } else {
                Text("📷 暗号化画像", color = textColor, fontSize = 14.sp)
            }
        }
        "LOCATION" -> {
            val loc = remember(message.content) { LocationShareHelper.parse(message.content) }
            if (loc != null) {
                LocationMessageCard(location = loc, isDark = isDark)
            } else {
                Text(message.content, color = textColor, fontSize = 14.sp)
            }
        }
        else -> {
            val loc = remember(message.content) { LocationShareHelper.parse(message.content) }
            if (loc != null) {
                LocationMessageCard(location = loc, isDark = isDark)
            } else {
                Text(
                    text = message.content,
                    color = textColor,
                    fontSize = 15.sp,
                    lineHeight = 20.sp,
                    fontFamily = BitchatFontFamily
                )
            }
        }
    }
}

@Composable
private fun GroupSystemNoticeRow(text: String, isDark: Boolean) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (isDark) Color.Black.copy(alpha = 0.35f) else Color.Black.copy(alpha = 0.08f)
        ) {
            Text(
                text = text,
                fontSize = 11.sp,
                color = if (isDark) Color(0xFFB5BAC1) else Color(0xFF4E5058),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun GroupMessageComposer(
    text: String,
    placeholder: String,
    isChannelMode: Boolean,
    isDark: Boolean,
    isLocating: Boolean,
    accentColor: Color,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onSendImage: (String) -> Unit,
    onShareLocation: () -> Unit
) {
    val pillBg = when {
        isChannelMode && isDark -> Color(0xFF383A40)
        isChannelMode && !isDark -> Color(0xFFEBEDEF)
        isDark -> Color(0xFF1C2733)
        else -> Color.White
    }
    val contentColor = if (isDark) Color.White else Color(0xFF111B21)
    val iconColor = if (isDark) Color(0xFF949BA4) else Color(0xFF54656F)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .windowInsetsPadding(WindowInsets.ime),
        verticalAlignment = Alignment.Bottom
    ) {
        Surface(
            shape = RoundedCornerShape(if (isChannelMode) 12.dp else 24.dp),
            color = pillBg,
            shadowElevation = if (isChannelMode) 0.dp else 1.dp,
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ImagePickerButton(onImageReady = onSendImage)

                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = { Text(placeholder, color = iconColor, fontSize = 14.sp, maxLines = 1) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = contentColor,
                        unfocusedTextColor = contentColor,
                        cursorColor = accentColor,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    maxLines = 4,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = onShareLocation,
                    enabled = !isLocating,
                    modifier = Modifier.size(34.dp)
                ) {
                    if (isLocating) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = accentColor)
                    } else {
                        Icon(Icons.Filled.LocationOn, "現在地", tint = iconColor, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(accentColor)
                .clickable(onClick = onSend),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Send, "送信", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("nemix", text))
}

@Composable
private fun TopicListItemRow(
    channel: P2PGroupChannel,
    lastMessage: P2PGroupMessage?,
    timeString: String,
    unreadCount: Int,
    onClick: () -> Unit
) {
    val isGeneral = channel.id == "general"
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f
    val bubbleBg = if (isGeneral) Color(0xFF5865F2).copy(alpha = 0.16f) else Color(0xFF2481CC).copy(alpha = 0.16f)
    val iconColor = if (isGeneral) Color(0xFF5865F2) else Color(0xFF2481CC)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(bubbleBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isGeneral) Icons.Filled.Tag else Icons.Filled.ChatBubbleOutline,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(22.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isGeneral) "# ${channel.name}" else "💬 ${channel.name}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (timeString.isNotEmpty()) {
                    Text(
                        text = timeString,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val preview = when {
                    lastMessage == null -> "メッセージを送信して話題を始めましょう"
                    lastMessage.type == "IMAGE" -> "${lastMessage.senderName}: 📷 画像"
                    lastMessage.type == "LOCATION" -> "${lastMessage.senderName}: 📍 位置情報"
                    else -> "${lastMessage.senderName}: ${lastMessage.content}"
                }
                Text(
                    text = preview,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (unreadCount > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    ClassicUnreadBadge(count = unreadCount)
                }
            }
        }
    }
}

