package com.bitchat.android.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.directory.NemixUuidDirectoryManager
import com.bitchat.android.features.location.LocationShareHelper
import com.bitchat.android.groups.P2PGroup
import com.bitchat.android.groups.P2PGroupManager
import com.bitchat.android.groups.P2PGroupMessage
import com.bitchat.android.groups.P2PGroupStyle
import com.bitchat.android.legal.TermsOfServiceManager
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.net.InternetRelayPreferences
import com.bitchat.android.profile.ProfileSetupDialog
import com.bitchat.android.profile.UserProfileManager
import com.bitchat.android.security.ChatSecurityPolicyManager
import com.bitchat.android.service.MeshServicePreferences
import com.bitchat.android.services.ChatArchiveManager
import com.bitchat.android.ui.theme.AppUiStyle
import com.bitchat.android.ui.theme.AppUiStyleManager
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.LocalBitchatPalette
import com.bitchat.android.ui.theme.ClassicColors
import com.bitchat.android.ui.theme.colorForPeer
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Classic-format Home Screen for nemix Messenger
 * Supports:
 * - 1-on-1 Direct & Internet E2EE Chats (with Mutual Screenshot Block & Chat Deletion)
 * - 100% P2P Encrypted Groups (Selectable Stream format & #Channel format)
 * - Random UUID Internet Discovery & Instant Friend Add
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ClassicHomeScreen(
    viewModel: ChatViewModel,
    onOpenBroadcastChat: () -> Unit,
    onOpenPrivateChat: (String) -> Unit,
    onOpenGroupChat: (String) -> Unit = {},
    onOpenUuidSearch: () -> Unit = {},
    onShowSettings: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val publicMessages by viewModel.messages.collectAsStateWithLifecycle()
    val peerRSSI by viewModel.peerRSSI.collectAsStateWithLifecycle()
    val peerDirect by viewModel.peerDirect.collectAsStateWithLifecycle()

    val myAvatarBitmap by UserProfileManager.myAvatarBitmap.collectAsStateWithLifecycle()
    val peerAvatars by UserProfileManager.peerAvatars.collectAsStateWithLifecycle()
    val internetEnabled by InternetRelayPreferences.internetEnabled.collectAsStateWithLifecycle()

    val p2pGroups by P2PGroupManager.groups.collectAsStateWithLifecycle()
    val p2pGroupMessages by P2PGroupManager.groupMessages.collectAsStateWithLifecycle()
    val p2pGroupUnreads by P2PGroupManager.unreadCounts.collectAsStateWithLifecycle()

    val myBlockedChats by ChatSecurityPolicyManager.myBlockedChats.collectAsStateWithLifecycle()
    val peerBlockedChats by ChatSecurityPolicyManager.peerBlockedChats.collectAsStateWithLifecycle()
    val globalScreenshotBlocked by ChatSecurityPolicyManager.globalScreenshotBlocked.collectAsStateWithLifecycle()
    val archivedIds by ChatArchiveManager.archivedIds.collectAsStateWithLifecycle()
    val nearbyVisible by MeshServicePreferences.nearbyVisibleFlow.collectAsStateWithLifecycle()

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showProfileDialog by remember { mutableStateOf(false) }
    var showNewChatSheet by remember { mutableStateOf(false) }
    var showMigrationSheet by remember { mutableStateOf(false) }
    var migrationInitialTab by remember { mutableIntStateOf(0) }
    var showCreateOrJoinGroupDialog by remember { mutableStateOf(false) }
    var showOldChatCleanupDialog by remember { mutableStateOf(false) }
    var showContactExchangeSheet by remember { mutableStateOf(false) }

    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.background.red < 0.5f
    val topBarBg = if (isDark) ClassicColors.DarkTeal else ClassicColors.Teal

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(topBarBg)
                    .windowInsetsPadding(WindowInsets.statusBars)
            ) {
                if (isSearchActive) {
                    // Search mode top bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            isSearchActive = false
                            searchQuery = ""
                        }) {
                            Icon(
                                imageVector = Icons.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text(
                                    text = "チャット・P2Pグループ・端末を検索...",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 15.sp
                                )
                            },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color.White,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Clear",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                } else {
                    // Regular Top Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Left side shrinks on narrow screens so the search / menu buttons
                        // (and the Settings entry inside the menu) always stay on screen.
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showProfileDialog = true }
                        ) {
                            ClassicAvatar(
                                name = nickname,
                                size = 34.dp,
                                backgroundColor = Color.White.copy(alpha = 0.22f),
                                avatarBitmap = myAvatarBitmap
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "nemix",
                                        color = Color.White,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = BitchatFontFamily
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(ClassicColors.AccentGreen)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = when {
                                            !nearbyVisible -> "ステルス (周辺非公開 · QR/UUIDのみ)"
                                            internetEnabled -> "完全P2Pメッシュ + ネットE2EE"
                                            else -> "オフライン 完全P2Pメッシュ"
                                        },
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 11.sp,
                                        fontFamily = BitchatFontFamily,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Connected peer count pill
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Black.copy(alpha = 0.2f),
                                modifier = Modifier.clickable { selectedTabIndex = 2 }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Bluetooth,
                                        contentDescription = "Connected Devices",
                                        tint = ClassicColors.AccentGreen,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${connectedPeers.size}台",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = "Search",
                                    tint = Color.White
                                )
                            }

                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = "Menu",
                                        tint = Color.White
                                    )
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("プロフィール設定 ($nickname)") },
                                        leadingIcon = { Icon(Icons.Filled.AccountCircle, null) },
                                        onClick = {
                                            showMenu = false
                                            showProfileDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("P2Pグループ作成・参加") },
                                        leadingIcon = { Icon(Icons.Filled.Groups, null) },
                                        onClick = {
                                            showMenu = false
                                            showCreateOrJoinGroupDialog = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("ID (UUID) でネットユーザー追加") },
                                        leadingIcon = { Icon(Icons.Filled.PersonSearch, null) },
                                        onClick = {
                                            showMenu = false
                                            onOpenUuidSearch()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("QRコード・招待URLで連絡先交換") },
                                        leadingIcon = { Icon(Icons.Filled.QrCode, null) },
                                        onClick = {
                                            showMenu = false
                                            showContactExchangeSheet = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("端末データ移行 (引き継ぎ)") },
                                        leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) },
                                        onClick = {
                                            showMenu = false
                                            migrationInitialTab = 0
                                            showMigrationSheet = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("チャンネル形式 (トピック別UI) に切替") },
                                        leadingIcon = { Icon(Icons.Filled.Tag, null) },
                                        onClick = {
                                            showMenu = false
                                            AppUiStyleManager.set(context, AppUiStyle.CHANNELS)
                                            Toast.makeText(context, "チャンネル形式UIに切り替えました", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (nearbyVisible) "周辺への公開: ON（非公開にする）"
                                                else "周辺への公開: OFF（公開に戻す）"
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                if (nearbyVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                                null
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            val next = !nearbyVisible
                                            MeshServicePreferences.setNearbyVisible(next)
                                            Toast.makeText(
                                                context,
                                                if (next) "周辺端末への公開をONにしました"
                                                else "周辺への公開をOFFにしました（QR・UUID追加のみ）",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("古いチャットを整理 (アーカイブ/削除)") },
                                        leadingIcon = { Icon(Icons.Filled.AutoDelete, null) },
                                        onClick = {
                                            showMenu = false
                                            showOldChatCleanupDialog = true
                                        }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text("設定 / 利用規約 / アプリ情報") },
                                        leadingIcon = { Icon(Icons.Filled.Settings, null) },
                                        onClick = {
                                            showMenu = false
                                            onShowSettings()
                                        }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                "データを全消去 (緊急)",
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Filled.DeleteForever,
                                                null,
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        },
                                        onClick = {
                                            showMenu = false
                                            UserProfileManager.clearAll(context)
                                            ChatSecurityPolicyManager.clearAll(context)
                                            ChatArchiveManager.clearAll(context)
                                            P2PGroupManager.clearAll(context)
                                            viewModel.panicClearAllData()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // 3 Tabs: チャット, グループ, 周辺端末
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = topBarBg,
                    contentColor = Color.White,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                            height = 3.dp,
                            color = Color.White
                        )
                    },
                    divider = {}
                ) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedTabIndex = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "チャット",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                val totalUnread = conversations.sumOf { it.unreadCount }
                                if (totalUnread > 0) {
                                    Spacer(modifier = Modifier.width(5.dp))
                                    ClassicUnreadBadge(count = totalUnread)
                                }
                            }
                        }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "グループ",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                val groupUnread = p2pGroupUnreads.values.sum()
                                if (groupUnread > 0) {
                                    Spacer(modifier = Modifier.width(5.dp))
                                    ClassicUnreadBadge(count = groupUnread)
                                } else if (p2pGroups.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.22f))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = p2pGroups.size.toString(),
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    )
                    Tab(
                        selected = selectedTabIndex == 2,
                        onClick = { selectedTabIndex = 2 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "周辺端末",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                if (connectedPeers.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.25f))
                                            .padding(horizontal = 5.dp, vertical = 1.dp)
                                    ) {
                                        Text(
                                            text = connectedPeers.size.toString(),
                                            color = Color.White,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (selectedTabIndex == 1) {
                        showCreateOrJoinGroupDialog = true
                    } else {
                        showNewChatSheet = true
                    }
                },
                containerColor = ClassicColors.AccentGreen,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.padding(16.dp)
            ) {
                Icon(
                    imageVector = if (selectedTabIndex == 1) Icons.Filled.GroupAdd else Icons.Filled.Chat,
                    contentDescription = "New Chat or Group",
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(colorScheme.surface)
        ) {
            when (selectedTabIndex) {
                0 -> {
                    ChatsTabContent(
                        conversations = conversations,
                        p2pGroups = p2pGroups,
                        p2pGroupMessages = p2pGroupMessages,
                        p2pGroupUnreads = p2pGroupUnreads,
                        publicMessages = publicMessages,
                        searchQuery = searchQuery,
                        connectedPeers = connectedPeers,
                        peerAvatars = peerAvatars,
                        myNickname = nickname,
                        myBlockedChats = myBlockedChats,
                        peerBlockedChats = peerBlockedChats,
                        globalScreenshotBlocked = globalScreenshotBlocked,
                        archivedIds = archivedIds,
                        nearbyVisible = nearbyVisible,
                        onOpenBroadcastChat = onOpenBroadcastChat,
                        onOpenPrivateChat = onOpenPrivateChat,
                        onOpenGroupChat = onOpenGroupChat,
                        onOpenCreateGroup = { showCreateOrJoinGroupDialog = true },
                        onOpenUuidSearch = onOpenUuidSearch,
                        onOpenOldChatCleanup = { showOldChatCleanupDialog = true },
                        viewModel = viewModel
                    )
                }
                1 -> {
                    GroupsTabContent(
                        groups = p2pGroups,
                        groupMessages = p2pGroupMessages,
                        unreadCounts = p2pGroupUnreads,
                        searchQuery = searchQuery,
                        myNickname = nickname,
                        globalScreenshotBlocked = globalScreenshotBlocked,
                        archivedIds = archivedIds,
                        onOpenGroupChat = onOpenGroupChat,
                        onCreateOrJoinClick = { showCreateOrJoinGroupDialog = true }
                    )
                }
                2 -> {
                    DevicesTabContent(
                        connectedPeers = connectedPeers,
                        peerNicknames = peerNicknames,
                        peerRSSI = peerRSSI,
                        peerDirect = peerDirect,
                        peerAvatars = peerAvatars,
                        searchQuery = searchQuery,
                        nearbyVisible = nearbyVisible,
                        onOpenPrivateChat = onOpenPrivateChat,
                        onOpenUuidSearch = onOpenUuidSearch,
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    // Editable Profile Dialog (Name + Tightly Compressed Avatar)
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

    // Create or Join 100% P2P Group Dialog (Stream vs. Channel format)
    if (showCreateOrJoinGroupDialog) {
        CreateOrJoinGroupDialog(
            myNickname = nickname,
            connectedPeers = connectedPeers,
            peerNicknames = peerNicknames,
            onGroupReady = { group ->
                showCreateOrJoinGroupDialog = false
                onOpenGroupChat(group.id)
            },
            onDismiss = { showCreateOrJoinGroupDialog = false }
        )
    }

    // Old Chat Bulk Archive / Delete Dialog
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

    // Signal-style Migration Sheet
    if (showMigrationSheet) {
        com.bitchat.android.migration.MigrationSheet(
            isPresented = showMigrationSheet,
            initialTab = migrationInitialTab,
            onDismiss = { showMigrationSheet = false },
            onMigrationSuccess = {
                UserProfileManager.reloadAfterMigration(context)
                TermsOfServiceManager.init(context)
                InternetRelayPreferences.init(context)
                ChatSecurityPolicyManager.init(context)
                ChatArchiveManager.init(context)
                NemixUuidDirectoryManager.init(context)
                P2PGroupManager.init(context)
                com.bitchat.android.services.AppStateStore.reloadConversationPersistence(
                    viewModel.getApplication()
                )
            }
        )
    }

    // New Chat Bottom Sheet
    if (showNewChatSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNewChatSheet = false },
            containerColor = colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "新しいチャット・P2Pグループを開始",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    fontFamily = BitchatFontFamily
                )
                Text(
                    text = "完全P2Pのグループ作成、UUID検索でのネット友達追加、または周辺の端末と直接会話できます",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // 1. P2P Encrypted Group (Stream / Topics style)
                NewChatActionRow(
                    icon = Icons.Filled.GroupAdd,
                    circleBg = Color(0xFF5865F2),
                    title = "P2P暗号化グループを作成 / 参加",
                    subtitle = "ストリーム形式・トピック別チャンネル形式を選択可能",
                    onClick = {
                        showNewChatSheet = false
                        showCreateOrJoinGroupDialog = true
                    }
                )

                // 2. Random UUID Search & Instant Friend Add
                NewChatActionRow(
                    icon = Icons.Filled.PersonSearch,
                    circleBg = Color(0xFF2C6BED),
                    title = "ID (UUID) でネット経由ユーザー追加",
                    subtitle = "ランダムUUID文字列で相手を検索し、即座に相互登録してE2EE接続",
                    onClick = {
                        showNewChatSheet = false
                        onOpenUuidSearch()
                    }
                )

                // 3. Broadcast option
                NewChatActionRow(
                    icon = Icons.Filled.Campaign,
                    circleBg = ClassicColors.AccentGreen,
                    title = "メッシュ全体チャット (全員)",
                    subtitle = "周辺の全端末へ同時に届くオフライン対応パブリック通信",
                    onClick = {
                        showNewChatSheet = false
                        onOpenBroadcastChat()
                    }
                )

                // 4. QR / Link Contact Exchange option
                NewChatActionRow(
                    icon = Icons.Filled.QrCode,
                    circleBg = ClassicColors.Teal,
                    title = "QRコード / 招待URLで連絡先交換",
                    subtitle = "永久リンク（再発行可能）または時限URL（10分・1時間・24時間）で相手を追加",
                    onClick = {
                        showNewChatSheet = false
                        showContactExchangeSheet = true
                    }
                )

                if (!nearbyVisible) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "現在「周辺に公開しない」設定のため、周辺への発信・自動公開は停止中です。\n上の「QRコード」または「UUID」から連絡先を追加できます。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            fontFamily = BitchatFontFamily
                        )
                    }
                } else if (connectedPeers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "周辺のデバイスを探しています...\nBluetoothやWi-Fiをオンにして相手に近づくか、UUID検索でネット経由追加できます",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center,
                            fontFamily = BitchatFontFamily
                        )
                    }
                } else {
                    Text(
                        text = "周辺の接続中デバイス",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = ClassicColors.Teal,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                    connectedPeers.forEach { peerID ->
                        val peerName = peerNicknames[peerID] ?: peerID.take(8)
                        val palette = LocalBitchatPalette.current
                        val peerIdentity = viewModel.peerIdentityForMeshPeer(peerID)
                        val assignedColor = colorForPeer(peerIdentity, palette)
                        val peerAvatar = UserProfileManager.getPeerAvatar(peerAvatars, peerID, peerName)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showNewChatSheet = false
                                    onOpenPrivateChat(peerID)
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ClassicAvatar(
                                name = peerName,
                                backgroundColor = assignedColor,
                                isOnline = true,
                                avatarBitmap = peerAvatar
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    text = peerName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = "オンライン (近距離メッシュ接続)",
                                    fontSize = 12.sp,
                                    color = ClassicColors.AccentGreen
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // QR & URL Contact Exchange Sheet (Permanent Revocable + Expiring Links)
    if (showContactExchangeSheet) {
        ContactExchangeSheet(
            onDismiss = { showContactExchangeSheet = false }
        )
    }
}

@Composable
private fun NewChatActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    circleBg: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(circleBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = Color.White,
                modifier = Modifier.size(23.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun OldChatCleanupDialog(
    onArchiveOlderThanDays: (Int) -> Unit,
    onDeleteOlderThanDays: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedDays by remember { mutableIntStateOf(30) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "古いチャットの整理",
                fontWeight = FontWeight.Bold,
                fontFamily = BitchatFontFamily
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "一定期間更新のない古いチャット・グループの履歴を一括でアーカイブ（一覧から隠す）または削除できます。",
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(7, 30, 90).forEach { days ->
                        FilterChip(
                            selected = selectedDays == days,
                            onClick = { selectedDays = days },
                            label = { Text("${days}日以上前", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Button(
                    onClick = { onArchiveOlderThanDays(selectedDays) },
                    colors = ButtonDefaults.buttonColors(containerColor = ClassicColors.Teal),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Archive, null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("${selectedDays}日以上前を一括アーカイブ", color = Color.White)
                }
                OutlinedButton(
                    onClick = { onDeleteOlderThanDays(selectedDays) },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.DeleteSweep, null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("${selectedDays}日以上前の履歴を一括削除")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        }
    )
}

/**
 * Chats Tab: Archived Drawer + Broadcast Channel + P2P Groups + Direct 1-on-1 Conversations
 */
@Composable
private fun ChatsTabContent(
    conversations: List<ConversationSummary>,
    p2pGroups: List<P2PGroup>,
    p2pGroupMessages: Map<String, List<P2PGroupMessage>>,
    p2pGroupUnreads: Map<String, Int>,
    publicMessages: List<BitchatMessage>,
    searchQuery: String,
    connectedPeers: List<String>,
    peerAvatars: Map<String, Bitmap>,
    myNickname: String,
    myBlockedChats: Set<String>,
    peerBlockedChats: Set<String>,
    globalScreenshotBlocked: Boolean,
    archivedIds: Set<String>,
    nearbyVisible: Boolean,
    onOpenBroadcastChat: () -> Unit,
    onOpenPrivateChat: (String) -> Unit,
    onOpenGroupChat: (String) -> Unit,
    onOpenCreateGroup: () -> Unit,
    onOpenUuidSearch: () -> Unit,
    onOpenOldChatCleanup: () -> Unit,
    viewModel: ChatViewModel
) {
    var showArchivedSection by remember { mutableStateOf(false) }

    val filteredConversations = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) conversations
        else conversations.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                it.latestMessagePreview.contains(searchQuery, ignoreCase = true)
        }
    }

    val filteredGroups = remember(p2pGroups, searchQuery) {
        if (searchQuery.isBlank()) p2pGroups
        else p2pGroups.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.description.contains(searchQuery, ignoreCase = true)
        }
    }

    val activeConversations = remember(filteredConversations, archivedIds) {
        filteredConversations.filterNot { ChatArchiveManager.isArchived(it.conversationID) }
    }
    val archivedConversations = remember(filteredConversations, archivedIds) {
        filteredConversations.filter { ChatArchiveManager.isArchived(it.conversationID) }
    }
    val activeGroups = remember(filteredGroups, archivedIds) {
        filteredGroups.filterNot { ChatArchiveManager.isArchived("group:${it.id}") }
    }
    val archivedGroups = remember(filteredGroups, archivedIds) {
        filteredGroups.filter { ChatArchiveManager.isArchived("group:${it.id}") }
    }
    val totalArchivedCount = archivedConversations.size + archivedGroups.size

    val latestPublicMessage = publicMessages.lastOrNull()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // 0. Classic-format Archived Chats expandable row + Old Chat Cleanup shortcut
        if (totalArchivedCount > 0 || conversations.size >= 4) {
            item(key = "archive_toolbar_row") {
                Column {
                    if (totalArchivedCount > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showArchivedSection = !showArchivedSection }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Archive,
                                contentDescription = "アーカイブ済み",
                                tint = ClassicColors.Teal,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(18.dp))
                            Text(
                                text = "アーカイブ済み",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = ClassicColors.Teal.copy(alpha = 0.14f)
                            ) {
                                Text(
                                    text = "$totalArchivedCount",
                                    color = ClassicColors.Teal,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = if (showArchivedSection) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                            thickness = 0.5.dp
                        )
                    }
                    if (showArchivedSection) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "アーカイブ済みのチャット（長押しで解除・削除）",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    TextButton(
                                        onClick = onOpenOldChatCleanup,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text("一括整理", fontSize = 11.sp, color = ClassicColors.Teal)
                                    }
                                }
                                archivedGroups.forEach { grp ->
                                    P2PGroupRow(
                                        group = grp,
                                        latestMessage = p2pGroupMessages[grp.id]?.lastOrNull(),
                                        unreadCount = p2pGroupUnreads[grp.id] ?: 0,
                                        myNickname = myNickname,
                                        globalScreenshotBlocked = globalScreenshotBlocked,
                                        isArchived = true,
                                        onClick = { onOpenGroupChat(grp.id) }
                                    )
                                }
                                archivedConversations.forEach { conv ->
                                    val isSs = ChatSecurityPolicyManager.isScreenshotBlockedForConversation(conv.conversationID)
                                    ClassicConversationRow(
                                        conversation = conv,
                                        peerAvatars = peerAvatars,
                                        myNickname = myNickname,
                                        isScreenshotBlocked = isSs,
                                        isArchived = true,
                                        onClick = { onOpenPrivateChat(conv.conversationID) },
                                        viewModel = viewModel
                                    )
                                }
                                HorizontalDivider(thickness = 1.dp)
                            }
                        }
                    }
                }
            }
        }

        // 1. Broadcast / Public Mesh row (hidden when Stealth Mode is active unless messages exist)
        if ((nearbyVisible || publicMessages.isNotEmpty()) &&
            (searchQuery.isBlank() || "全体".contains(searchQuery) || "broadcast".contains(searchQuery, ignoreCase = true))
        ) {
            item(key = "broadcast_row") {
                BroadcastChatRow(
                    latestMessage = latestPublicMessage,
                    connectedCount = connectedPeers.size,
                    onClick = onOpenBroadcastChat
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 76.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    thickness = 0.5.dp
                )
            }
        }

        // 2. Active P2P Encrypted Groups
        items(
            items = activeGroups,
            key = { "grp_${it.id}" }
        ) { group ->
            P2PGroupRow(
                group = group,
                latestMessage = p2pGroupMessages[group.id]?.lastOrNull(),
                unreadCount = p2pGroupUnreads[group.id] ?: 0,
                myNickname = myNickname,
                globalScreenshotBlocked = globalScreenshotBlocked,
                isArchived = false,
                onClick = { onOpenGroupChat(group.id) }
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 76.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
        }

        // 3. Active 1-on-1 Private Conversations
        if (activeConversations.isEmpty() && activeGroups.isEmpty() && publicMessages.isEmpty() && connectedPeers.isEmpty()) {
            item(key = "empty_state") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 56.dp, start = 28.dp, end = 28.dp, bottom = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(ClassicColors.Teal.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (nearbyVisible) Icons.Filled.BluetoothSearching else Icons.Filled.VisibilityOff,
                                contentDescription = null,
                                tint = ClassicColors.Teal,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (nearbyVisible) "完全P2P・サーバーレス通信待機中" else "ステルスモード（周辺非公開）",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (nearbyVisible) {
                                "Bluetooth / Wi-Fiでのオフライン直接通信、ネット経由のUUID友達追加、ストリーム形式やトピック別チャンネル形式のP2P暗号化グループに対応しています。"
                            } else {
                                "周辺端末への自動公開を停止しています。QRコード読み取りまたはUUID追加で信頼できる相手と安全に繋がれます。"
                            },
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = onOpenUuidSearch) {
                                Icon(Icons.Filled.PersonSearch, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("UUIDで友達追加", fontSize = 12.sp)
                            }
                            Button(
                                onClick = {
                                    if (nearbyVisible) onOpenCreateGroup() else viewModel.showVerificationSheet()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = ClassicColors.Teal)
                            ) {
                                Icon(
                                    if (nearbyVisible) Icons.Filled.GroupAdd else Icons.Filled.QrCode,
                                    null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (nearbyVisible) "P2Pグループ作成" else "QRコード追加", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        } else {
            items(
                items = activeConversations,
                key = { it.conversationID }
            ) { conversation ->
                val isSsBlocked = remember(
                    conversation.conversationID,
                    myBlockedChats,
                    peerBlockedChats,
                    globalScreenshotBlocked
                ) {
                    ChatSecurityPolicyManager.isScreenshotBlockedForConversation(conversation.conversationID)
                }
                ClassicConversationRow(
                    conversation = conversation,
                    peerAvatars = peerAvatars,
                    myNickname = myNickname,
                    isScreenshotBlocked = isSsBlocked,
                    isArchived = false,
                    onClick = { onOpenPrivateChat(conversation.conversationID) },
                    viewModel = viewModel
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 76.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    thickness = 0.5.dp
                )
            }
        }
    }
}

/**
 * Dedicated P2P Groups Tab (Stream & Channel formats)
 */
@Composable
private fun GroupsTabContent(
    groups: List<P2PGroup>,
    groupMessages: Map<String, List<P2PGroupMessage>>,
    unreadCounts: Map<String, Int>,
    searchQuery: String,
    myNickname: String,
    globalScreenshotBlocked: Boolean,
    archivedIds: Set<String> = emptySet(),
    onOpenGroupChat: (String) -> Unit,
    onCreateOrJoinClick: () -> Unit
) {
    val filteredGroups = remember(groups, searchQuery) {
        if (searchQuery.isBlank()) groups
        else groups.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.description.contains(searchQuery, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp)
    ) {
        item(key = "create_group_banner") {
            Surface(
                onClick = onCreateOrJoinClick,
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF5865F2)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GroupAdd,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "P2P暗号化グループを作成 / 招待コードで参加",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            fontFamily = BitchatFontFamily
                        )
                        Text(
                            text = "ストリーム形式・トピック別チャンネル形式を自由に選択・切替（100% P2P暗号化）",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        if (filteredGroups.isEmpty()) {
            item(key = "empty_groups") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 64.dp, start = 32.dp, end = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.Forum,
                            contentDescription = null,
                            tint = ClassicColors.Teal,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "まだP2Pグループがありません",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "中央サーバーを一切使わず、AES-256-GCM共有鍵とP2Pマルチキャストで安全なグループ会話ができます。用途に合わせてストリーム形式とトピック別チャンネル形式を選べます。",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        } else {
            items(
                items = filteredGroups,
                key = { it.id }
            ) { group ->
                val isGroupArchived = remember(group.id, archivedIds) {
                    ChatArchiveManager.isArchived("group:${group.id}")
                }
                P2PGroupRow(
                    group = group,
                    latestMessage = groupMessages[group.id]?.lastOrNull(),
                    unreadCount = unreadCounts[group.id] ?: 0,
                    myNickname = myNickname,
                    globalScreenshotBlocked = globalScreenshotBlocked,
                    isArchived = isGroupArchived,
                    onClick = { onOpenGroupChat(group.id) }
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 76.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    thickness = 0.5.dp
                )
            }
        }
    }
}

/**
 * Row item for a 100% P2P Encrypted Group (shows Stream or Channel format badge & long-press actions)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun P2PGroupRow(
    group: P2PGroup,
    latestMessage: P2PGroupMessage?,
    unreadCount: Int,
    myNickname: String,
    globalScreenshotBlocked: Boolean,
    isArchived: Boolean = false,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val ts = latestMessage?.timestamp ?: group.updatedAt
    val timeString = if (ts > 0) timeFormatter.format(Date(ts)) else ""

    val isChannelMode = group.style == P2PGroupStyle.CHANNELS
    val badgeColor = if (isChannelMode) Color(0xFF5865F2) else Color(0xFF2481CC)
    val badgeLabel = if (isChannelMode) "トピック · #${group.channels.size}" else "ストリーム"
    val isSsProtected = group.screenshotBlocked || globalScreenshotBlocked

    var showGroupMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showGroupMenu = true }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (isChannelMode) Color(0xFF70B647) else badgeColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = group.name.take(1).uppercase(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = group.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeColor.copy(alpha = 0.14f)
                    ) {
                        Text(
                            text = badgeLabel,
                            color = badgeColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                    if (isSsProtected) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.ScreenLockPortrait,
                            contentDescription = "スクショ禁止",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                if (timeString.isNotEmpty()) {
                    Text(
                        text = timeString,
                        fontSize = 12.sp,
                        color = if (unreadCount > 0) ClassicColors.AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (unreadCount > 0) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            // Topics preview row when multi-channel (matching screenshot)
            if (isChannelMode && group.channels.isNotEmpty()) {
                val topicsSummary = group.channels.take(3).joinToString(", ") { ch ->
                    if (ch.id == "general") "# ${ch.name}" else "💬 ${ch.name}"
                }
                Text(
                    text = topicsSummary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF2481CC),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            } else {
                Spacer(modifier = Modifier.height(3.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val preview = when {
                    latestMessage == null -> "${group.members.size}人のP2Pメンバー"
                    latestMessage.type == "IMAGE" -> "${latestMessage.senderName}: 📷 暗号化画像"
                    latestMessage.type == "LOCATION" -> "${latestMessage.senderName}: 📍 位置情報"
                    latestMessage.type == "SYSTEM" -> latestMessage.content
                    isChannelMode -> "#${latestMessage.channelId} · ${latestMessage.senderName}: ${latestMessage.content}"
                    else -> "${latestMessage.senderName}: ${latestMessage.content}"
                }
                Text(
                    text = preview,
                    fontSize = 13.sp,
                    color = if (unreadCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
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

    if (showGroupMenu) {
        val nextStyle = if (isChannelMode) P2PGroupStyle.STREAM else P2PGroupStyle.CHANNELS
        val nextLabel = if (isChannelMode) "ストリーム形式 (単一スレッド) に切替" else "チャンネル形式 (トピック別) に切替"
        AlertDialog(
            onDismissRequest = { showGroupMenu = false },
            title = { Text(group.name) },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text(nextLabel) },
                        leadingContent = { Icon(Icons.Filled.SwapHoriz, null) },
                        modifier = Modifier.clickable {
                            P2PGroupManager.updateGroupStyle(context, group.id, nextStyle, myNickname)
                            showGroupMenu = false
                        }
                    )
                    ListItem(
                        headlineContent = {
                            Text(
                                if (group.screenshotBlocked) "グループのスクショ禁止をOFF"
                                else "グループのスクショ禁止をON (全員適用)"
                            )
                        },
                        leadingContent = { Icon(Icons.Filled.ScreenLockPortrait, null) },
                        modifier = Modifier.clickable {
                            P2PGroupManager.setGroupScreenshotBlocked(
                                context = context,
                                groupId = group.id,
                                blocked = !group.screenshotBlocked,
                                myNickname = myNickname
                            )
                            showGroupMenu = false
                        }
                    )
                    ListItem(
                        headlineContent = {
                            Text(if (isArchived) "アーカイブを解除" else "グループをアーカイブ")
                        },
                        leadingContent = {
                            Icon(if (isArchived) Icons.Filled.Unarchive else Icons.Filled.Archive, null)
                        },
                        modifier = Modifier.clickable {
                            val nowArchived = ChatArchiveManager.toggleArchived(context, "group:${group.id}")
                            Toast.makeText(
                                context,
                                if (nowArchived) "「${group.name}」をアーカイブしました" else "アーカイブを解除しました",
                                Toast.LENGTH_SHORT
                            ).show()
                            showGroupMenu = false
                        }
                    )
                    ListItem(
                        headlineContent = {
                            Text("グループ内のチャット履歴のみ消去")
                        },
                        leadingContent = { Icon(Icons.Filled.CleaningServices, null) },
                        modifier = Modifier.clickable {
                            P2PGroupManager.clearGroupHistoryLocally(context, group.id)
                            Toast.makeText(context, "「${group.name}」の履歴を消去しました", Toast.LENGTH_SHORT).show()
                            showGroupMenu = false
                        }
                    )
                    ListItem(
                        headlineContent = {
                            Text(
                                "グループから退出して履歴を削除",
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
                            P2PGroupManager.leaveAndDeleteGroup(context, group.id, myNickname)
                            Toast.makeText(context, "「${group.name}」を削除しました", Toast.LENGTH_SHORT).show()
                            showGroupMenu = false
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showGroupMenu = false }) { Text("閉じる") }
            }
        )
    }
}

/**
 * Broadcast Group Chat Row (classic format)
 */
@Composable
private fun BroadcastChatRow(
    latestMessage: BitchatMessage?,
    connectedCount: Int,
    onClick: () -> Unit
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeString = latestMessage?.timestamp?.let { timeFormatter.format(it) } ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(ClassicColors.Teal),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Groups,
                contentDescription = "Broadcast",
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "メッシュ全体チャット (全員)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
                val previewText = when {
                    latestMessage == null -> "周辺の全端末へ配信 (タップして参加)"
                    LocationShareHelper.isLocationMessage(latestMessage.content) ->
                        "${latestMessage.sender}: 📍 現在地・位置情報"
                    latestMessage.type == BitchatMessageType.Image ->
                        "${latestMessage.sender}: 📷 画像"
                    latestMessage.type == BitchatMessageType.Audio ->
                        "${latestMessage.sender}: 🎤 ボイスメッセージ"
                    else -> "${latestMessage.sender}: ${latestMessage.content.take(60)}"
                }
                Text(
                    text = previewText,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (connectedCount > 0) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = ClassicColors.AccentGreen.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "${connectedCount}台接続中",
                            color = ClassicColors.AccentGreen,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 1-on-1 Conversation Row (classic format + Screenshot Lock Icon + Long-Press Delete)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClassicConversationRow(
    conversation: ConversationSummary,
    peerAvatars: Map<String, Bitmap>,
    myNickname: String,
    isScreenshotBlocked: Boolean,
    isArchived: Boolean = false,
    onClick: () -> Unit,
    viewModel: ChatViewModel
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val palette = LocalBitchatPalette.current
    val peerIdentity = viewModel.peerIdentityForMeshPeer(conversation.conversationID)
    val assignedColor = colorForPeer(peerIdentity, palette)
    val peerAvatar = remember(peerAvatars, conversation.conversationID, conversation.displayName) {
        UserProfileManager.getPeerAvatar(peerAvatars, conversation.conversationID, conversation.displayName)
    }

    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeString = if (conversation.latestMessageAt > 0) {
        timeFormatter.format(Date(conversation.latestMessageAt))
    } else ""

    var showActionDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showActionDialog = true }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ClassicAvatar(
            name = conversation.displayName,
            backgroundColor = assignedColor,
            isOnline = conversation.isConnected,
            avatarBitmap = peerAvatar,
            size = 52.dp
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = conversation.displayName,
                        fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isScreenshotBlocked) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Icon(
                            imageVector = Icons.Filled.ScreenLockPortrait,
                            contentDescription = "スクショ禁止保護中",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                if (timeString.isNotEmpty()) {
                    Text(
                        text = timeString,
                        fontSize = 12.sp,
                        color = if (conversation.unreadCount > 0) ClassicColors.AccentGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (conversation.unreadCount > 0) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (conversation.latestMessageIsOutgoing) {
                        ClassicCheckmark(
                            status = conversation.latestDeliveryStatus,
                            isOutgoing = true,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }

                    val previewText = when {
                        !conversation.draft.isNullOrBlank() -> "下書き: ${conversation.draft}"
                        conversation.latestMessageType == BitchatMessageType.Image -> "📷 画像"
                        conversation.latestMessageType == BitchatMessageType.Audio -> "🎤 ボイスメッセージ"
                        conversation.latestMessageType == BitchatMessageType.File -> "📎 ファイル"
                        LocationShareHelper.isLocationMessage(conversation.latestMessagePreview) -> "📍 現在地・位置情報"
                        else -> conversation.latestMessagePreview.ifBlank { "..." }
                    }

                    Text(
                        text = previewText,
                        fontSize = 14.sp,
                        color = if (conversation.unreadCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (conversation.unreadCount > 0) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conversation.isPinned) {
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = "Pinned",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }

                    if (conversation.unreadCount > 0) {
                        ClassicUnreadBadge(count = conversation.unreadCount)
                    }
                }
            }
        }
    }

    if (showActionDialog) {
        val blockedByMe = ChatSecurityPolicyManager.isBlockedByMe(conversation.conversationID)
        AlertDialog(
            onDismissRequest = { showActionDialog = false },
            title = { Text(conversation.displayName) },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text(if (conversation.isPinned) "ピン留めを解除" else "チャットをピン留め") },
                        leadingContent = { Icon(Icons.Filled.PushPin, null) },
                        modifier = Modifier.clickable {
                            viewModel.toggleConversationPinned(conversation.conversationID)
                            showActionDialog = false
                        }
                    )
                    ListItem(
                        headlineContent = { Text(if (conversation.isMuted) "通知のミュートを解除" else "通知をミュート") },
                        leadingContent = { Icon(Icons.Filled.NotificationsOff, null) },
                        modifier = Modifier.clickable {
                            viewModel.toggleConversationMuted(conversation.conversationID)
                            showActionDialog = false
                        }
                    )
                    ListItem(
                        headlineContent = {
                            Text(if (isArchived) "アーカイブを解除" else "チャットをアーカイブ")
                        },
                        leadingContent = {
                            Icon(if (isArchived) Icons.Filled.Unarchive else Icons.Filled.Archive, null)
                        },
                        modifier = Modifier.clickable {
                            val nowArchived = ChatArchiveManager.toggleArchived(context, conversation.conversationID)
                            Toast.makeText(
                                context,
                                if (nowArchived) "チャットをアーカイブしました" else "アーカイブを解除しました",
                                Toast.LENGTH_SHORT
                            ).show()
                            showActionDialog = false
                        }
                    )
                    ListItem(
                        headlineContent = {
                            Text(
                                if (blockedByMe) "スクショ禁止をOFF（相手側にも同期）"
                                else "スクショ禁止をON（相手側にも適用）"
                            )
                        },
                        leadingContent = { Icon(Icons.Filled.ScreenLockPortrait, null) },
                        modifier = Modifier.clickable {
                            ChatSecurityPolicyManager.setMyChatScreenshotBlock(
                                context = context,
                                peerOrConversationID = conversation.conversationID,
                                peerDisplayName = conversation.displayName,
                                myNickname = myNickname,
                                blocked = !blockedByMe
                            )
                            showActionDialog = false
                        }
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = {
                            Text(
                                "この端末からチャットを削除",
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
                            showActionDialog = false
                            coroutineScope.launch {
                                viewModel.deletePrivateConversation(conversation.conversationID)
                            }
                            Toast.makeText(context, "チャット履歴を削除しました", Toast.LENGTH_SHORT).show()
                        }
                    )
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
                            showActionDialog = false
                            ChatSecurityPolicyManager.sendPeerDeleteCommand(
                                context = context,
                                peerOrConversationID = conversation.conversationID,
                                peerDisplayName = conversation.displayName,
                                myNickname = myNickname,
                                deleteAll = true
                            )
                            coroutineScope.launch {
                                viewModel.deletePrivateConversation(conversation.conversationID)
                            }
                            Toast.makeText(context, "双方の端末からチャットを削除しました", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showActionDialog = false }) {
                    Text("閉じる")
                }
            }
        )
    }
}

/**
 * Devices Tab: Nearby BLE and Wi-Fi Aware connected peer list
 */
@Composable
private fun DevicesTabContent(
    connectedPeers: List<String>,
    peerNicknames: Map<String, String>,
    peerRSSI: Map<String, Int>,
    peerDirect: Map<String, Boolean>,
    peerAvatars: Map<String, Bitmap>,
    searchQuery: String,
    nearbyVisible: Boolean = true,
    onOpenPrivateChat: (String) -> Unit,
    onOpenUuidSearch: () -> Unit = {},
    viewModel: ChatViewModel
) {
    val context = LocalContext.current
    val filteredPeers = remember(connectedPeers, peerNicknames, searchQuery) {
        if (searchQuery.isBlank()) connectedPeers
        else connectedPeers.filter { peerID ->
            val name = peerNicknames[peerID] ?: peerID
            name.contains(searchQuery, ignoreCase = true)
        }
    }

    if (!nearbyVisible) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF455A64).copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.VisibilityOff,
                        contentDescription = null,
                        tint = Color(0xFF455A64),
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "周辺への公開: OFF（ステルス設定）",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "自分の存在を周辺の端末に公開していません（Bluetooth発信・Wi-Fi Aware公開を停止中）。\n新しい連絡先は「QRコード」または「UUID」から追加できます。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.showVerificationSheet() },
                        colors = ButtonDefaults.buttonColors(containerColor = ClassicColors.Teal)
                    ) {
                        Icon(Icons.Filled.QrCode, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("QRコード追加", fontSize = 12.sp, color = Color.White)
                    }
                    OutlinedButton(onClick = onOpenUuidSearch) {
                        Icon(Icons.Filled.PersonSearch, null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("UUIDで追加", fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        MeshServicePreferences.setNearbyVisible(true)
                        Toast.makeText(context, "周辺端末への公開をONにしました", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("周辺に自分を公開する設定に戻す", fontSize = 12.sp, color = ClassicColors.Teal)
                }
            }
        }
        return
    }

    if (filteredPeers.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(ClassicColors.Teal.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.BluetoothSearching,
                        contentDescription = null,
                        tint = ClassicColors.Teal,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "周辺に接続可能な端末がありません",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "他のスマホでnemixを開いて近づくと、自動的にBluetooth Low Energy / Wi-Fi Awareメッシュに接続されます。",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = "現在接続中の周辺デバイス (${filteredPeers.size}台)",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = ClassicColors.Teal,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                )
            }

            items(filteredPeers, key = { it }) { peerID ->
                val peerName = peerNicknames[peerID] ?: peerID.take(8)
                val isDirect = peerDirect[peerID] == true
                val rssi = peerRSSI[peerID]

                val palette = LocalBitchatPalette.current
                val peerIdentity = viewModel.peerIdentityForMeshPeer(peerID)
                val assignedColor = colorForPeer(peerIdentity, palette)
                val peerAvatar = UserProfileManager.getPeerAvatar(peerAvatars, peerID, peerName)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPrivateChat(peerID) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ClassicAvatar(
                        name = peerName,
                        backgroundColor = assignedColor,
                        isOnline = true,
                        avatarBitmap = peerAvatar,
                        size = 50.dp
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = peerName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isDirect) "直接接続 (Direct BLE)" else "メッシュ中継 (Mesh Relay)",
                                fontSize = 12.sp,
                                color = ClassicColors.AccentGreen
                            )
                            if (rssi != null && rssi != 0) {
                                Text(
                                    text = " · ${rssi}dBm",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = { onOpenPrivateChat(peerID) },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = ClassicColors.AccentGreen.copy(alpha = 0.15f),
                            contentColor = ClassicColors.AccentGreen
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Chat,
                            contentDescription = "Chat",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(start = 76.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    thickness = 0.5.dp
                )
            }
        }
    }
}
