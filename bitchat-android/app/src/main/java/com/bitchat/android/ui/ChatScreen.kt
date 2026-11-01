package com.bitchat.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.R
import com.bitchat.android.call.WirelessCallOverlay
import com.bitchat.android.contact.ContactExchangeManager
import com.bitchat.android.groups.P2PGroupManager
import com.bitchat.android.legal.TermsOfServiceManager
import com.bitchat.android.profile.ProfileSetupDialog
import com.bitchat.android.profile.UserProfileManager
import com.bitchat.android.security.ChatSecurityPolicyManager
import com.bitchat.android.services.ChatArchiveManager
import com.bitchat.android.ui.theme.AppUiStyle
import com.bitchat.android.ui.theme.AppUiStyleManager

/**
 * Main ChatScreen coordinator for nemix Messenger
 * Orchestrates Classic-format & Channel-format Home/Chat Screens,
 * 100% P2P Encrypted Group Screen (Stream & Channel formats), First-Launch Setup,
 * and Real-Time OS-Standard Wireless Voice Call Overlay.
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme

    // Active chat target: null for Home, "mesh_broadcast", "group:<groupId>", or peerID for 1-on-1
    var activeChatTarget by remember { mutableStateOf<String?>(null) }
    var verificationInitialTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(activeChatTarget) {
        ChatSecurityPolicyManager.setActiveViewingTarget(activeChatTarget)
    }

    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val showAppInfo by viewModel.showAppInfo.collectAsStateWithLifecycle()
    val showVerificationSheet by viewModel.showVerificationSheet.collectAsStateWithLifecycle()
    val showSecurityVerificationSheet by viewModel.showSecurityVerificationSheet.collectAsStateWithLifecycle()
    val appUiStyle by AppUiStyleManager.styleFlow.collectAsStateWithLifecycle()

    val isProfileSetupCompleted by UserProfileManager.isSetupCompleted.collectAsStateWithLifecycle()
    val isTosAccepted by TermsOfServiceManager.acceptedFlow.collectAsStateWithLifecycle()
    val incomingInvite by ContactExchangeManager.pendingIncomingInvite.collectAsStateWithLifecycle()

    var showPanicConfirmation by remember { mutableStateOf(false) }
    var showDebugSheet by remember { mutableStateOf(false) }

    // Single-press Back navigation: close any open sheet first, otherwise return from chat to Home in 1 press
    BackHandler(
        enabled = showDebugSheet ||
            showSecurityVerificationSheet ||
            showVerificationSheet ||
            showAppInfo ||
            activeChatTarget != null
    ) {
        when {
            showDebugSheet -> showDebugSheet = false
            showSecurityVerificationSheet -> viewModel.hideSecurityVerificationSheet()
            showVerificationSheet -> {
                verificationInitialTab = 0
                viewModel.hideVerificationSheet()
            }
            showAppInfo -> viewModel.hideAppInfo()
            activeChatTarget != null -> {
                activeChatTarget?.let {
                    if (it != "mesh_broadcast" && !it.startsWith("group:")) {
                        viewModel.endPrivateChat()
                    }
                }
                activeChatTarget = null
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
    ) {
        AnimatedContent(
            targetState = activeChatTarget,
            transitionSpec = {
                if (targetState != null) {
                    slideInHorizontally { width -> width } + fadeIn() togetherWith
                        slideOutHorizontally { width -> -width / 3 } + fadeOut()
                } else {
                    slideInHorizontally { width -> -width / 3 } + fadeIn() togetherWith
                        slideOutHorizontally { width -> width } + fadeOut()
                }
            },
            label = "ScreenTransition"
        ) { target ->
            when {
                target == null -> {
                    if (appUiStyle == AppUiStyle.CHANNELS) {
                        ChannelModeHomeScreen(
                            viewModel = viewModel,
                            onOpenBroadcastChat = {
                                activeChatTarget = "mesh_broadcast"
                            },
                            onOpenPrivateChat = { peerID ->
                                activeChatTarget = peerID
                            },
                            onOpenGroupChat = { groupId ->
                                activeChatTarget = "group:$groupId"
                            },
                            onOpenUuidSearch = {
                                verificationInitialTab = 2
                                viewModel.showVerificationSheet()
                            },
                            onShowSettings = {
                                viewModel.showAppInfo()
                            }
                        )
                    } else {
                        ClassicHomeScreen(
                            viewModel = viewModel,
                            onOpenBroadcastChat = {
                                activeChatTarget = "mesh_broadcast"
                            },
                            onOpenPrivateChat = { peerID ->
                                activeChatTarget = peerID
                            },
                            onOpenGroupChat = { groupId ->
                                activeChatTarget = "group:$groupId"
                            },
                            onOpenUuidSearch = {
                                verificationInitialTab = 2
                                viewModel.showVerificationSheet()
                            },
                            onShowSettings = {
                                viewModel.showAppInfo()
                            }
                        )
                    }
                }

                target.startsWith("group:") -> {
                    P2PGroupScreen(
                        groupId = target.removePrefix("group:"),
                        viewModel = viewModel,
                        onBack = { activeChatTarget = null }
                    )
                }

                else -> {
                    if (appUiStyle == AppUiStyle.CHANNELS) {
                        ChannelModeChatScreen(
                            chatTarget = target,
                            viewModel = viewModel,
                            onBack = {
                                if (target != "mesh_broadcast") {
                                    viewModel.endPrivateChat()
                                }
                                activeChatTarget = null
                            },
                            onShowSecurityVerification = {
                                viewModel.showSecurityVerificationSheet()
                            }
                        )
                    } else {
                        ClassicChatScreen(
                            chatTarget = target,
                            viewModel = viewModel,
                            onBack = {
                                if (target != "mesh_broadcast") {
                                    viewModel.endPrivateChat()
                                }
                                activeChatTarget = null
                            },
                            onShowSecurityVerification = {
                                viewModel.showSecurityVerificationSheet()
                            }
                        )
                    }
                }
            }
        }
    }

    // Contact invite link tapped outside the app (nemix://contact/... or https://nemi-x.com/c#...)
    val invite = incomingInvite
    if (invite != null && isProfileSetupCompleted && isTosAccepted) {
        key(invite) {
            ContactExchangeSheet(
                onDismiss = { ContactExchangeManager.consumeIncomingInvite() },
                initialInviteUrl = invite
            )
        }
    }

    // First-Launch Profile (Name + Compressed Icon + UI Style + Nearby Visibility) & Terms of Service Setup
    if (!isProfileSetupCompleted || !isTosAccepted) {
        ProfileSetupDialog(
            initialNickname = nickname,
            isFirstLaunch = true,
            onSaveProfile = { newNickname ->
                viewModel.setNickname(newNickname)
            },
            onDismiss = {}
        )
    }

    // Real-Time Wireless Call Overlay (active across all screens)
    WirelessCallOverlay(myNickname = nickname)

    // App Info / Settings Sheet
    AboutSheet(
        isPresented = showAppInfo,
        onDismiss = { viewModel.hideAppInfo() },
        onShowDebug = { showDebugSheet = true }
    )
    if (showDebugSheet) {
        com.bitchat.android.ui.debug.DebugSettingsSheet(
            isPresented = showDebugSheet,
            onDismiss = { showDebugSheet = false },
            meshService = viewModel.meshService
        )
    }

    // Security Verification & UUID Search Sheet
    if (showVerificationSheet) {
        VerificationSheet(
            isPresented = showVerificationSheet,
            onDismiss = {
                verificationInitialTab = 0
                viewModel.hideVerificationSheet()
            },
            viewModel = viewModel,
            initialTab = verificationInitialTab,
            onOpenTargetChat = { targetId ->
                verificationInitialTab = 0
                viewModel.hideVerificationSheet()
                activeChatTarget = targetId
            }
        )
    }

    if (showSecurityVerificationSheet) {
        SecurityVerificationSheet(
            isPresented = showSecurityVerificationSheet,
            onDismiss = viewModel::hideSecurityVerificationSheet,
            viewModel = viewModel
        )
    }

    // Panic clear confirmation dialog
    if (showPanicConfirmation) {
        AlertDialog(
            onDismissRequest = { showPanicConfirmation = false },
            title = { Text(stringResource(R.string.panic_confirm_title)) },
            text = { Text(stringResource(R.string.panic_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPanicConfirmation = false
                        UserProfileManager.clearAll(context)
                        ChatSecurityPolicyManager.clearAll(context)
                        ChatArchiveManager.clearAll(context)
                        AppUiStyleManager.clear(context)
                        P2PGroupManager.clearAll(context)
                        viewModel.panicClearAllData()
                        activeChatTarget = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.panic_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPanicConfirmation = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}
