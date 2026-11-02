package com.bitchat.android.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.call.CallTransportMode
import com.bitchat.android.call.CallTransportPickerDialog
import com.bitchat.android.call.WirelessCallManager
import com.bitchat.android.features.location.LocationMessageCard
import com.bitchat.android.features.location.LocationShareHelper
import com.bitchat.android.features.media.ImageUtils
import com.bitchat.android.features.voice.VoiceRecorder
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.net.InternetRelayPreferences
import com.bitchat.android.profile.UserProfileManager
import com.bitchat.android.security.ChatSecurityPolicyManager
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.MessageRouter
import com.bitchat.android.ui.media.FullScreenImageViewer
import com.bitchat.android.ui.media.VoiceNotePlayer
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.LocalBitchatPalette
import com.bitchat.android.ui.theme.ClassicColors
import com.bitchat.android.ui.theme.colorForPeer
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Classic-format Chat Detail Screen for nemix Messenger
 * Supports:
 * - 1-on-1 Direct Device-to-Device & Internet/Tor E2EE Chats
 * - Selectable Mutual Screenshot Blocking (enforced on BOTH self and peer via E2EE policy sync)
 * - Chat & Message Deletion (local deletion + two-sided synchronized P2P deletion)
 * - Wireless E2EE Voice Calling (Local Wireless vs. Internet Selectable)
 * - Rich E2EE Image Transfer (Local + Internet), Voice Notes, and OS-Standard Location Cards
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ClassicChatScreen(
    chatTarget: String, // peerID or "mesh_broadcast"
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onShowSecurityVerification: () -> Unit
) {
    val isBroadcast = chatTarget == "mesh_broadcast"
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red < 0.5f

    val topBarBg = if (isDark) ClassicColors.DarkTeal else ClassicColors.Teal
    val chatWallpaper = if (isDark) ClassicColors.ChatBackgroundDark else ClassicColors.ChatBackgroundLight

    // State collections
    val publicMessages by viewModel.messages.collectAsStateWithLifecycle()
    val privateChats by viewModel.privateChats.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val peerSessionStates by viewModel.peerSessionStates.collectAsStateWithLifecycle()
    val favoritePeers by viewModel.favoritePeers.collectAsStateWithLifecycle()
    val peerRSSI by viewModel.peerRSSI.collectAsStateWithLifecycle()
    val peerDirect by viewModel.peerDirect.collectAsStateWithLifecycle()
    val archivedIds by com.bitchat.android.services.ChatArchiveManager.archivedIds.collectAsStateWithLifecycle()

    val peerAvatars by UserProfileManager.peerAvatars.collectAsStateWithLifecycle()
    val myAvatarBitmap by UserProfileManager.myAvatarBitmap.collectAsStateWithLifecycle()
    val internetEnabled by InternetRelayPreferences.internetEnabled.collectAsStateWithLifecycle()

    val myBlockedChats by ChatSecurityPolicyManager.myBlockedChats.collectAsStateWithLifecycle()
    val peerBlockedChats by ChatSecurityPolicyManager.peerBlockedChats.collectAsStateWithLifecycle()
    val globalScreenshotBlocked by ChatSecurityPolicyManager.globalScreenshotBlocked.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val unifiedMesh = remember(context) { MeshServiceHolder.getUnifiedOrCreate(context) }
    val router = remember(context, unifiedMesh) { MessageRouter.getInstance(context, unifiedMesh) }

    val blockedByMe = remember(isBroadcast, chatTarget, myBlockedChats) {
        !isBroadcast && ChatSecurityPolicyManager.isBlockedByMe(chatTarget)
    }
    val blockedByPeer = remember(isBroadcast, chatTarget, peerBlockedChats) {
        !isBroadcast && ChatSecurityPolicyManager.isBlockedByPeer(chatTarget)
    }
    val isScreenshotProtected = remember(isBroadcast, chatTarget, myBlockedChats, peerBlockedChats, globalScreenshotBlocked) {
        ChatSecurityPolicyManager.isScreenshotBlockedForConversation(chatTarget)
    }

    // 1-on-1 chat initialization
    LaunchedEffect(chatTarget) {
        if (!isBroadcast) {
            viewModel.startPrivateChat(chatTarget)
        }
    }

    val canonicalTarget = remember(chatTarget) {
        if (isBroadcast) chatTarget
        else com.bitchat.android.services.ContactDirectory.canonicalConversationId(chatTarget)
    }

    val isArchived = remember(isBroadcast, chatTarget, archivedIds) {
        !isBroadcast && com.bitchat.android.services.ChatArchiveManager.isArchived(chatTarget)
    }

    val messages = remember(isBroadcast, chatTarget, canonicalTarget, publicMessages, privateChats) {
        if (isBroadcast) {
            publicMessages
        } else {
            privateChats[chatTarget]
                ?: privateChats[canonicalTarget]
                ?: emptyList()
        }
    }

    val resolvedContact = remember(isBroadcast, chatTarget, connectedPeers, favoritePeers, peerNicknames) {
        if (isBroadcast) null else com.bitchat.android.services.ContactDirectory.resolve(chatTarget)
    }
    val liveMeshPeerID = resolvedContact?.meshPeerID

    // Title and Online / Route status
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

    val hasLocalRoute = remember(isBroadcast, chatTarget, isConnected, peerSessionStates) {
        !isBroadcast && (isConnected || router.hasMeshRoute(chatTarget))
    }
    val hasInternetRoute = remember(isBroadcast, chatTarget, favoritePeers, internetEnabled) {
        !isBroadcast && internetEnabled && router.hasInternetRoute(chatTarget)
    }

    // Automatically synchronize our compressed profile icon & nickname silently over E2EE
    LaunchedEffect(chatTarget, isConnected, hasInternetRoute, nickname, myAvatarBitmap) {
        if (!isBroadcast && (isConnected || hasInternetRoute)) {
            val pkt = UserProfileManager.buildSyncPacketIfNeeded(context, chatTarget, nickname)
            if (pkt != null) {
                runCatching {
                    router.sendPrivate(
                        pkt,
                        chatTarget,
                        title,
                        UUID.randomUUID().toString().uppercase()
                    )
                }
            }
        }
    }

    val currentRssi = peerRSSI[chatTarget] ?: liveMeshPeerID?.let { peerRSSI[it] }
    val isDirect = peerDirect[chatTarget] ?: liveMeshPeerID?.let { peerDirect[it] } ?: true

    val proximityText = remember(isConnected, hasInternetRoute, currentRssi, isDirect, connectedPeers.size, isBroadcast) {
        when {
            isBroadcast -> "${connectedPeers.size}台の端末が参加中 (P2P全員)"
            isConnected && currentRssi != null && currentRssi != 0 -> when {
                currentRssi >= -65 -> "至近距離 (${currentRssi}dBm · 電波 強)"
                currentRssi >= -80 -> "中距離 (${currentRssi}dBm · 電波 中)"
                else -> "遠距離 (${currentRssi}dBm · 電波 弱)"
            }
            isConnected && !isDirect -> "メッシュ中継接続 (P2Pリレー)"
            isConnected -> "近接接続中 (BLE/Wi-Fi · E2EE暗号化)"
            hasInternetRoute -> "オンライン到達可能 (ネット経由 · E2EE暗号化)"
            else -> "オフライン (圏外 · 近づくと自動送信)"
        }
    }

    val palette = LocalBitchatPalette.current
    val assignedColor = remember(isBroadcast, chatTarget) {
        if (isBroadcast) ClassicColors.Teal
        else {
            val peerIdentity = viewModel.peerIdentityForMeshPeer(chatTarget)
            colorForPeer(peerIdentity, palette)
        }
    }

    val peerAvatar = remember(isBroadcast, peerAvatars, chatTarget, title) {
        if (isBroadcast) null else UserProfileManager.getPeerAvatar(peerAvatars, chatTarget, title)
    }

    val isFavorite = remember(favoritePeers, chatTarget) {
        !isBroadcast && viewModel.isFavorite(chatTarget)
    }

    var messageText by remember(chatTarget) {
        mutableStateOf(TextFieldValue(if (!isBroadcast) viewModel.conversationDraft(chatTarget) else ""))
    }

    var showMenu by remember { mutableStateOf(false) }
    var showCallTransportDialog by remember { mutableStateOf(false) }
    var showDeleteChatDialog by remember { mutableStateOf(false) }
    var selectedMessageForAction by remember { mutableStateOf<BitchatMessage?>(null) }
    var pendingCallMode by remember { mutableStateOf<CallTransportMode?>(null) }
    var isLocating by remember { mutableStateOf(false) }
    var activeViewerImagePath by remember { mutableStateOf<String?>(null) }

    val allImagePaths = remember(messages) {
        messages
            .filter { it.type == BitchatMessageType.Image }
            .map { it.content.trim() }
            .filter { it.isNotEmpty() && File(it).exists() }
    }

    // Microphone permission launcher for outgoing wireless calls
    val callMicPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val mode = pendingCallMode
        pendingCallMode = null
        if (granted && mode != null) {
            val res = WirelessCallManager.startOutgoingCall(
                context = context,
                peerID = chatTarget,
                peerName = title,
                myNickname = nickname,
                mode = mode
            )
            res.onFailure { err ->
                Toast.makeText(context, err.message ?: "発信できませんでした", Toast.LENGTH_LONG).show()
            }
        } else if (!granted) {
            Toast.makeText(context, "無線通話にはマイク権限が必要です", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareCurrentLocation() {
        if (isLocating) return
        isLocating = true
        Toast.makeText(context, "OS標準測位で現在地を取得しています...", Toast.LENGTH_SHORT).show()
        coroutineScope.launch {
            val result = LocationShareHelper.fetchCurrentLocationOnce(context)
            isLocating = false
            result.onSuccess { sharedLoc ->
                val encoded = LocationShareHelper.encode(sharedLoc)
                viewModel.sendMessage(encoded)
            }.onFailure { err ->
                Toast.makeText(
                    context,
                    err.message ?: "位置情報を取得できませんでした",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val ok = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (ok) {
            shareCurrentLocation()
        } else {
            Toast.makeText(context, "位置情報の共有には位置情報権限が必要です", Toast.LENGTH_SHORT).show()
        }
    }

    val listState = rememberLazyListState()

    // Auto-scroll to latest message on arrival
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    // Wireless Call Transport Mode Picker (Local Wireless vs. Internet)
    if (showCallTransportDialog && !isBroadcast) {
        CallTransportPickerDialog(
            peerName = title,
            hasLocalRoute = hasLocalRoute,
            hasInternetRoute = hasInternetRoute,
            onSelectMode = { mode ->
                showCallTransportDialog = false
                if (WirelessCallManager.hasAudioPermission(context)) {
                    val res = WirelessCallManager.startOutgoingCall(
                        context = context,
                        peerID = chatTarget,
                        peerName = title,
                        myNickname = nickname,
                        mode = mode
                    )
                    res.onFailure { err ->
                        Toast.makeText(context, err.message ?: "発信できませんでした", Toast.LENGTH_LONG).show()
                    }
                } else {
                    pendingCallMode = mode
                    callMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            onDismiss = { showCallTransportDialog = false }
        )
    }

    // Delete Conversation Confirmation Dialog
    if (showDeleteChatDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteChatDialog = false },
            title = {
                Text(
                    text = if (isBroadcast) "全体チャット履歴を削除" else "「$title」のチャットを削除",
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isBroadcast) {
                            "この端末に保存されているメッシュ全体チャットの表示履歴をすべて削除しますか？"
                        } else {
                            "削除範囲を選択してください。「相手の端末からも削除」を選ぶと、P2P暗号化コマンドで相手側の履歴も同時に消去します。"
                        },
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    OutlinedButton(
                        onClick = {
                            showDeleteChatDialog = false
                            if (isBroadcast) {
                                AppStateStore.clearPublicTimelines()
                                Toast.makeText(context, "全体チャット履歴を消去しました", Toast.LENGTH_SHORT).show()
                            } else {
                                coroutineScope.launch {
                                    viewModel.deletePrivateConversation(chatTarget)
                                }
                                Toast.makeText(context, "この端末からチャットを削除しました", Toast.LENGTH_SHORT).show()
                                onBack()
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.DeleteOutline, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("この端末のみ削除")
                    }

                    if (!isBroadcast) {
                        Button(
                            onClick = {
                                showDeleteChatDialog = false
                                ChatSecurityPolicyManager.sendPeerDeleteCommand(
                                    context = context,
                                    peerOrConversationID = chatTarget,
                                    peerDisplayName = title,
                                    myNickname = nickname,
                                    deleteAll = true
                                )
                                coroutineScope.launch {
                                    viewModel.deletePrivateConversation(chatTarget)
                                }
                                Toast.makeText(context, "双方の端末からチャットを削除しました", Toast.LENGTH_SHORT).show()
                                onBack()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.DeleteForever, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("相手の端末からも削除（双方削除）")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDeleteChatDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    // Single Message Long-Press Action Dialog
    selectedMessageForAction?.let { targetMsg ->
        AlertDialog(
            onDismissRequest = { selectedMessageForAction = null },
            title = { Text("メッセージ操作", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    if (targetMsg.type == BitchatMessageType.Message) {
                        ListItem(
                            headlineContent = { Text("テキストをコピー") },
                            leadingContent = { Icon(Icons.Filled.ContentCopy, null) },
                            modifier = Modifier.clickable {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("nemix-msg", targetMsg.content))
                                Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
                                selectedMessageForAction = null
                            }
                        )
                    }
                    ListItem(
                        headlineContent = {
                            Text(
                                "この端末から削除",
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        leadingContent = {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        modifier = Modifier.clickable {
                            if (isBroadcast) {
                                AppStateStore.removePublicMessage(targetMsg.id)
                            } else {
                                AppStateStore.removePrivateMessage(targetMsg.id)
                            }
                            selectedMessageForAction = null
                            Toast.makeText(context, "メッセージを削除しました", Toast.LENGTH_SHORT).show()
                        }
                    )
                    if (!isBroadcast && targetMsg.sender != "system") {
                        ListItem(
                            headlineContent = {
                                Text(
                                    "相手の端末からも削除（双方削除）",
                                    color = MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.SemiBold
                                )
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Filled.DeleteForever,
                                    null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            },
                            modifier = Modifier.clickable {
                                ChatSecurityPolicyManager.sendPeerDeleteCommand(
                                    context = context,
                                    peerOrConversationID = chatTarget,
                                    peerDisplayName = title,
                                    myNickname = nickname,
                                    deleteAll = false,
                                    messageId = targetMsg.id
                                )
                                AppStateStore.removePrivateMessage(targetMsg.id)
                                selectedMessageForAction = null
                                Toast.makeText(context, "双方の端末からメッセージを削除しました", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { selectedMessageForAction = null }) {
                    Text("閉じる")
                }
            }
        )
    }

    // Full-screen swipeable image viewer
    activeViewerImagePath?.let { path ->
        val viewerPaths = if (allImagePaths.contains(path)) allImagePaths else listOf(path)
        val initialIdx = viewerPaths.indexOf(path).coerceAtLeast(0)
        FullScreenImageViewer(
            imagePaths = viewerPaths,
            initialIndex = initialIdx,
            onClose = { activeViewerImagePath = null }
        )
    }

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
                        .height(60.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Back button
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }

                    // Avatar
                    if (isBroadcast) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(ClassicColors.AccentGreen),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Groups,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else {
                        ClassicAvatar(
                            name = title,
                            backgroundColor = assignedColor,
                            isOnline = isConnected || hasInternetRoute,
                            avatarBitmap = peerAvatar,
                            size = 40.dp
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    // Name and Online subtitle
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                if (!isBroadcast) onShowSecurityVerification()
                            }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = title,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (isScreenshotProtected) {
                                Spacer(modifier = Modifier.width(5.dp))
                                Icon(
                                    imageVector = Icons.Filled.ScreenLockPortrait,
                                    contentDescription = "スクショ禁止",
                                    tint = ClassicColors.AccentGreen,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isConnected || isBroadcast -> ClassicColors.AccentGreen
                                            hasInternetRoute -> Color(0xFF64B5F6)
                                            else -> Color.LightGray
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = proximityText,
                                color = Color.White.copy(alpha = 0.88f),
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Action icons (Wireless Call + Mutual Screenshot Protection + E2EE Security)
                    if (!isBroadcast) {
                        IconButton(onClick = { showCallTransportDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.Call,
                                contentDescription = "無線通話",
                                tint = Color.White,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                val next = !blockedByMe
                                ChatSecurityPolicyManager.setMyChatScreenshotBlock(
                                    context = context,
                                    peerOrConversationID = chatTarget,
                                    peerDisplayName = title,
                                    myNickname = nickname,
                                    blocked = next
                                )
                                val msg = if (next) {
                                    "スクショ禁止をONにしました（相手側にも適用）"
                                } else {
                                    "自分のスクショ禁止設定をOFFにしました"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ScreenLockPortrait,
                                contentDescription = "スクショ禁止切替",
                                tint = if (isScreenshotProtected) ClassicColors.AccentGreen else Color.White.copy(alpha = 0.78f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More",
                                tint = Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            if (!isBroadcast) {
                                DropdownMenuItem(
                                    text = { Text("無線通話（ローカル / ネット）") },
                                    leadingIcon = { Icon(Icons.Filled.Call, null) },
                                    onClick = {
                                        showMenu = false
                                        showCallTransportDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (blockedByMe) "スクショ禁止をOFF（相手側にも同期）"
                                            else "スクショ禁止をON（相手側にも適用）"
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.ScreenLockPortrait,
                                            null,
                                            tint = if (isScreenshotProtected) ClassicColors.AccentGreen else LocalContentColor.current
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        ChatSecurityPolicyManager.setMyChatScreenshotBlock(
                                            context = context,
                                            peerOrConversationID = chatTarget,
                                            peerDisplayName = title,
                                            myNickname = nickname,
                                            blocked = !blockedByMe
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (isFavorite) "連絡先登録（お気に入り）を解除" else "連絡先（お気に入り）に登録") },
                                    leadingIcon = {
                                        Icon(
                                            if (isFavorite) Icons.Filled.Star else Icons.Filled.StarOutline,
                                            null,
                                            tint = if (isFavorite) Color(0xFFFFB300) else LocalContentColor.current
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        viewModel.toggleFavorite(chatTarget)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("現在地を送信") },
                                    leadingIcon = { Icon(Icons.Filled.LocationOn, null) },
                                    onClick = {
                                        showMenu = false
                                        if (LocationShareHelper.hasLocationPermission(context)) {
                                            shareCurrentLocation()
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
                                DropdownMenuItem(
                                    text = { Text("暗号化ステータス・検証") },
                                    leadingIcon = { Icon(Icons.Filled.Security, null) },
                                    onClick = {
                                        showMenu = false
                                        onShowSecurityVerification()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (isArchived) "アーカイブを解除" else "チャットをアーカイブ") },
                                    leadingIcon = {
                                        Icon(
                                            if (isArchived) Icons.Filled.Unarchive else Icons.Filled.Archive,
                                            null
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        val nowArchived = com.bitchat.android.services.ChatArchiveManager
                                            .toggleArchived(context, canonicalTarget)
                                        Toast.makeText(
                                            context,
                                            if (nowArchived) "チャットをアーカイブしました" else "アーカイブを解除しました",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        if (nowArchived) onBack()
                                    }
                                )
                                HorizontalDivider()
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "チャット履歴を削除",
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.DeleteOutline,
                                        null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    showDeleteChatDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("チャットを閉じる") },
                                leadingIcon = { Icon(Icons.Filled.Close, null) },
                                onClick = {
                                    showMenu = false
                                    onBack()
                                }
                            )
                        }
                    }
                }

                // Active Mutual Screenshot Protection Banner
                if (isScreenshotProtected) {
                    val reasonText = when {
                        globalScreenshotBlocked -> "全チャット強制保護ON: スクリーンショット・画面録画は無効化されています"
                        blockedByMe && blockedByPeer -> "双方でスクショ禁止ON: 両端末の画面キャプチャが保護されています"
                        blockedByPeer -> "相手側がスクショ禁止をONに設定中: 画面キャプチャ・録画は禁止されています"
                        else -> "スクショ禁止保護中: あなたと相手の双方で画面キャプチャが禁止されています"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.24f))
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ScreenLockPortrait,
                            contentDescription = null,
                            tint = ClassicColors.AccentGreen,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = reasonText,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = BitchatFontFamily
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                // The scaffold already reserved the navigation bar; without this the
                // composer pads for it a second time (big gap on 3-button-nav phones).
                .consumeWindowInsets(paddingValues)
                .background(chatWallpaper)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Messages List (reverseLayout=true so latest is at bottom)
                Box(modifier = Modifier.weight(1f)) {
                    val reversedMessages = remember(messages) { messages.asReversed() }

                    if (messages.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (isDark) Color(0xFF182229) else Color(0xFFFFF9C4),
                                shadowElevation = 1.dp,
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Lock,
                                        contentDescription = null,
                                        tint = if (isDark) Color(0xFF8696A0) else Color(0xFF795548),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = if (isBroadcast) {
                                            "メッセージはインターネットを介さず、周辺の端末へBluetooth / Wi-Fiメッシュで直接届きます。"
                                        } else {
                                            "このチャット・通話・画像転送は完全P2Pでエンドツーエンド暗号化されています。右上のアイコンから相手側も含めたスクショ禁止や履歴削除が可能です。"
                                        },
                                        fontSize = 12.sp,
                                        color = if (isDark) Color(0xFF8696A0) else Color(0xFF5D4037),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            reverseLayout = true,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            itemsIndexed(
                                items = reversedMessages,
                                key = { _, msg -> msg.id }
                            ) { _, msg ->
                                if (msg.sender == "system") {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isDark) Color(0xFF182229) else Color(0xFFE9F5FE)
                                        ) {
                                            Text(
                                                text = msg.content,
                                                fontSize = 11.sp,
                                                color = if (isDark) Color(0xFF9FB3C8) else Color(0xFF2C5282),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                                fontFamily = BitchatFontFamily
                                            )
                                        }
                                    }
                                } else {
                                    val isSelf = msg.sender == nickname || msg.senderPeerID == viewModel.myPeerID
                                    ClassicMessageBubble(
                                        message = msg,
                                        isSelf = isSelf,
                                        showSenderName = isBroadcast && !isSelf,
                                        isDark = isDark,
                                        viewModel = viewModel,
                                        onOpenImage = { clickedPath -> activeViewerImagePath = clickedPath },
                                        onLongPress = { selectedMessageForAction = msg }
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                }

                // Classic Composer Section
                ClassicMessageComposer(
                    text = messageText,
                    onTextChanged = {
                        messageText = it
                        if (!isBroadcast) {
                            viewModel.setConversationDraft(chatTarget, it.text)
                        }
                    },
                    onSend = {
                        val trimmed = messageText.text.trim()
                        if (trimmed.isNotEmpty()) {
                            viewModel.sendMessage(trimmed) { accepted ->
                                if (accepted) {
                                    messageText = TextFieldValue("")
                                    if (!isBroadcast) {
                                        viewModel.setConversationDraft(chatTarget, "")
                                    }
                                }
                            }
                        }
                    },
                    onSendImage = { path ->
                        viewModel.sendImageNote(
                            if (isBroadcast) null else chatTarget,
                            null,
                            path
                        )
                    },
                    onSendFile = { path ->
                        viewModel.sendFileNote(
                            if (isBroadcast) null else chatTarget,
                            null,
                            path
                        )
                    },
                    onSendVoice = { path ->
                        viewModel.sendVoiceNote(
                            if (isBroadcast) null else chatTarget,
                            null,
                            path
                        )
                    },
                    onShareLocation = {
                        if (LocationShareHelper.hasLocationPermission(context)) {
                            shareCurrentLocation()
                        } else {
                            locationPermissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        }
                    },
                    isLocating = isLocating,
                    recorderFactory = {
                        viewModel.createVoiceRecorder(
                            if (isBroadcast) null else chatTarget,
                            null
                        )
                    },
                    isDark = isDark
                )
            }
        }
    }
}

/**
 * Classic-format Message Bubble with tail, rich media/location rendering, long-press actions, and bottom-right timestamp + checkmark
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ClassicMessageBubble(
    message: BitchatMessage,
    isSelf: Boolean,
    showSenderName: Boolean,
    isDark: Boolean,
    viewModel: ChatViewModel,
    onOpenImage: (String) -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeText = timeFormatter.format(message.timestamp)

    val bubbleShape = if (isSelf) {
        RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 2.dp)
    } else {
        RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 2.dp, bottomEnd = 14.dp)
    }

    val bubbleBg = when {
        isSelf && isDark -> ClassicColors.OutgoingBubbleDark
        isSelf && !isDark -> ClassicColors.OutgoingBubbleLight
        !isSelf && isDark -> ClassicColors.IncomingBubbleDark
        else -> ClassicColors.IncomingBubbleLight
    }

    val textColor = when {
        isDark -> ClassicColors.TextPrimaryDark
        else -> ClassicColors.TextPrimaryLight
    }

    val timeColor = when {
        isDark -> ClassicColors.TextSecondaryDark
        else -> ClassicColors.TextSecondaryLight
    }

    val palette = LocalBitchatPalette.current
    val senderColor = remember(message.sender) {
        val peerId = message.senderPeerID ?: message.sender
        val identity = viewModel.peerIdentityForMeshPeer(peerId)
        colorForPeer(identity, palette)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isSelf) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleBg,
            shadowElevation = 1.dp,
            modifier = Modifier
                .widthIn(min = 64.dp, max = 310.dp)
                .clip(bubbleShape)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onLongPress
                )
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                if (showSenderName) {
                    Text(
                        text = message.sender,
                        color = senderColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }

                when (message.type) {
                    BitchatMessageType.Image -> {
                        val imagePath = message.content.trim()
                        val fileExists = remember(imagePath) { File(imagePath).exists() }
                        val bmp = remember(imagePath, fileExists) {
                            if (fileExists) ImageUtils.loadBitmapWithExifOrientation(imagePath) else null
                        }

                        if (bmp != null) {
                            Column {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = "暗号化画像",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(232.dp)
                                        .heightIn(min = 120.dp, max = 240.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .combinedClickable(
                                            onClick = { onOpenImage(imagePath) },
                                            onLongClick = onLongPress
                                        )
                                )
                                val partial = message.deliveryStatus as? DeliveryStatus.PartiallyDelivered
                                if (partial != null && partial.total > 0 && partial.reached < partial.total) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val frac = (partial.reached.toFloat() / partial.total.toFloat()).coerceIn(0f, 1f)
                                    LinearProgressIndicator(
                                        progress = { frac },
                                        modifier = Modifier
                                            .width(232.dp)
                                            .height(3.dp)
                                            .clip(RoundedCornerShape(2.dp)),
                                        color = ClassicColors.Teal
                                    )
                                    Text(
                                        text = "暗号化転送中... ${(frac * 100).toInt()}%",
                                        fontSize = 10.sp,
                                        color = timeColor,
                                        fontFamily = BitchatFontFamily
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "📷 画像メッセージ",
                                color = textColor,
                                fontSize = 14.sp,
                                fontFamily = BitchatFontFamily
                            )
                        }
                    }

                    BitchatMessageType.Audio -> {
                        val audioPath = message.content.trim()
                        if (File(audioPath).exists()) {
                            VoiceNotePlayer(
                                path = audioPath,
                                modifier = Modifier.width(228.dp)
                            )
                        } else {
                            Text(
                                text = "🎤 ボイスメッセージ",
                                color = textColor,
                                fontSize = 14.sp,
                                fontFamily = BitchatFontFamily
                            )
                        }
                    }

                    BitchatMessageType.File -> {
                        val filePath = message.content.trim()
                        val fileObj = File(filePath)
                        val sizeText = if (fileObj.exists()) {
                            val kb = (fileObj.length() / 1024L).coerceAtLeast(1L)
                            "${kb} KB"
                        } else ""
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = if (isDark) 0.22f else 0.06f))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.InsertDriveFile,
                                contentDescription = null,
                                tint = ClassicColors.Teal,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = fileObj.name.ifBlank { filePath.substringAfterLast('/') },
                                    color = textColor,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontFamily = BitchatFontFamily
                                )
                                if (sizeText.isNotEmpty()) {
                                    Text(
                                        text = sizeText,
                                        color = timeColor,
                                        fontSize = 11.sp,
                                        fontFamily = BitchatFontFamily
                                    )
                                }
                            }
                        }
                    }

                    else -> {
                        val sharedLocation = remember(message.content) {
                            LocationShareHelper.parse(message.content)
                        }
                        if (sharedLocation != null) {
                            LocationMessageCard(
                                location = sharedLocation,
                                isDark = isDark
                            )
                        } else {
                            Text(
                                text = message.content,
                                color = textColor,
                                fontSize = 15.sp,
                                fontFamily = BitchatFontFamily,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Bottom row: Time + Classic double checkmark
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeText,
                        fontSize = 11.sp,
                        color = timeColor,
                        fontFamily = BitchatFontFamily
                    )
                    if (isSelf) {
                        Spacer(modifier = Modifier.width(4.dp))
                        ClassicCheckmark(
                            status = message.deliveryStatus ?: DeliveryStatus.Delivered("peer", message.timestamp),
                            isOutgoing = true
                        )
                    }
                }
            }
        }
    }
}

/**
 * Classic-format Composer with File, Text, OS-Standard Location Pin, Camera/Image, and Send/Voice
 */
@Composable
fun ClassicMessageComposer(
    text: TextFieldValue,
    onTextChanged: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onSendImage: (String) -> Unit,
    onSendFile: (String) -> Unit,
    onSendVoice: (String) -> Unit,
    onShareLocation: () -> Unit,
    isLocating: Boolean = false,
    recorderFactory: () -> VoiceRecorder,
    isDark: Boolean
) {
    val hasText = text.text.trim().isNotEmpty()
    val pillBg = if (isDark) Color(0xFF202C33) else Color.White
    val contentColor = if (isDark) Color.White else Color(0xFF111B21)
    val iconColor = if (isDark) Color(0xFF8696A0) else Color(0xFF54656F)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .windowInsetsPadding(WindowInsets.ime),
        verticalAlignment = Alignment.Bottom
    ) {
        // Left Rounded Pill
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = pillBg,
            shadowElevation = 1.dp,
            modifier = Modifier.weight(1f)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Attachment (File)
                com.bitchat.android.ui.media.FilePickerButton(
                    onFileReady = onSendFile
                )

                // Input Field
                TextField(
                    value = text,
                    onValueChange = onTextChanged,
                    placeholder = {
                        Text(
                            text = "メッセージ",
                            color = iconColor,
                            fontSize = 16.sp
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = contentColor,
                        unfocusedTextColor = contentColor,
                        cursorColor = ClassicColors.Teal,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    maxLines = 5,
                    modifier = Modifier.weight(1f)
                )

                // OS-Standard Privacy-Preserving Location Share Button
                IconButton(
                    onClick = onShareLocation,
                    enabled = !isLocating,
                    modifier = Modifier.size(34.dp)
                ) {
                    if (isLocating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = ClassicColors.Teal
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.LocationOn,
                            contentDescription = "現在地を共有",
                            tint = iconColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Camera / Image picker
                com.bitchat.android.ui.media.ImagePickerButton(
                    onImageReady = onSendImage
                )
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        // Right Circular Action Button (Send if text present, Mic button if empty)
        if (hasText) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(ClassicColors.Teal)
                    .clickable(onClick = onSend),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Send,
                    contentDescription = "Send",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        } else {
            // Classic Voice Record button with hold & slide to cancel
            VoiceRecordButton(
                recorderFactory = recorderFactory,
                onStart = { },
                onAmplitude = { _, _ -> },
                onFinish = onSendVoice,
                onCancel = { },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(ClassicColors.Teal)
            )
        }
    }
}
