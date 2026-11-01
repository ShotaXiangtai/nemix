package com.bitchat.android.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.button.CloseButton
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.core.ui.component.sheet.LocalSheetDismiss
import com.bitchat.android.directory.NemixUuidDirectoryManager
import com.bitchat.android.groups.P2PGroupManager
import com.bitchat.android.legal.TermsOfServiceDialog
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.InternetRelayPreferences
import com.bitchat.android.net.OkHttpProvider
import com.bitchat.android.net.TorMode
import com.bitchat.android.net.TorPreferenceManager
import com.bitchat.android.nostr.NostrBackgroundRuntime
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.security.AppLockManager
import com.bitchat.android.security.ChatSecurityPolicyManager
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.spam.SpamFilterPreferences
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.BitchatMotion
import com.bitchat.android.ui.theme.LocalBitchatPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Theme selection chip with Apple-like styling
 */
@Composable
private fun ThemeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    val containerColor by animateColorAsState(
        targetValue = if (selected) colorScheme.primary else colorScheme.surfaceVariant,
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "themeChipContainer"
    )
    val labelColor by animateColorAsState(
        targetValue = if (selected) Color.White else colorScheme.onSurfaceVariant,
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "themeChipLabel"
    )

    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = containerColor
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                fontFamily = BitchatFontFamily,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = labelColor
            )
        }
    }
}

@Composable
private fun LanguageSettingsRow(
    selectedLanguageName: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.about_app_language),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = selectedLanguageName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.UnfoldMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun LanguageMenuItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        onClick = onClick,
        trailingIcon = {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

/**
 * Unified settings toggle row with icon, title, subtitle, and switch
 */
@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    statusIndicator: (@Composable () -> Unit)? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current
    val interactionSource = remember { MutableInteractionSource() }

    val iconTint by animateColorAsState(
        targetValue = if (enabled) colorScheme.primary else palette.textTertiary,
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "settingsRowIcon"
    )
    val titleColor by animateColorAsState(
        targetValue = if (enabled) colorScheme.onSurface else palette.textTertiary,
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "settingsRowTitle"
    )
    val subtitleColor by animateColorAsState(
        targetValue = if (enabled) colorScheme.onSurfaceVariant else palette.textTertiary,
        animationSpec = tween(BitchatMotion.STANDARD_MS, easing = FastOutSlowInEasing),
        label = "settingsRowSubtitle"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled
            ) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(22.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    fontFamily = BitchatFontFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = titleColor
                )
                statusIndicator?.invoke()
            }
            Text(
                text = subtitle,
                fontFamily = BitchatFontFamily,
                fontSize = 12.sp,
                color = subtitleColor,
                lineHeight = 17.sp
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Switch(
            checked = checked,
            onCheckedChange = { if (enabled) onCheckedChange(it) },
            enabled = enabled,
            interactionSource = interactionSource,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = colorScheme.primary,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = colorScheme.surfaceVariant
            )
        )
    }
}

/**
 * Clickable navigation/action row inside a grouped settings card
 */
@Composable
private fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    badge: (@Composable () -> Unit)? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    fontFamily = BitchatFontFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurface
                )
                badge?.invoke()
            }
            Text(
                text = subtitle,
                fontFamily = BitchatFontFamily,
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant,
                lineHeight = 17.sp
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * nemix About & Settings Sheet (Google Play Store compliant — zero sideload APK sharing)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(
    isPresented: Boolean,
    onDismiss: () -> Unit,
    onShowDebug: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val versionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    val lazyListState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0 || lazyListState.firstVisibleItemScrollOffset > 0
        }
    }
    val topBarAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 0.98f else 0f,
        animationSpec = tween(BitchatMotion.EMPHASIZED_MS, easing = FastOutSlowInEasing),
        label = "topBarAlpha"
    )

    val colorScheme = MaterialTheme.colorScheme
    val palette = LocalBitchatPalette.current
    var selectedTab by remember { mutableStateOf(AboutTab.Info) }
    val supportedLanguages = remember(context) {
        LanguagePreferenceManager.supportedLanguages(context)
    }
    var selectedLanguageTag by remember {
        mutableStateOf(LanguagePreferenceManager.currentLanguageTag())
    }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var showTermsDialog by remember { mutableStateOf(false) }
    var showCustomRelayDialog by remember { mutableStateOf(false) }
    var showContactExchangeSheet by remember { mutableStateOf(false) }

    if (showTermsDialog) {
        TermsOfServiceDialog(onDismiss = { showTermsDialog = false })
    }

    if (showCustomRelayDialog) {
        CustomRelayAndTorDialog(onDismiss = { showCustomRelayDialog = false })
    }

    if (showContactExchangeSheet) {
        ContactExchangeSheet(onDismiss = { showContactExchangeSheet = false })
    }

    if (isPresented) {
        BitchatBottomSheet(
            modifier = modifier,
            onDismissRequest = onDismiss,
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 72.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    // Header Section - App Identity
                    item(key = "hero") {
                        AboutHero(versionName = versionName ?: "")
                    }

                    item(key = "tabs") {
                        AboutTabBar(
                            selected = selectedTab,
                            onSelect = { selectedTab = it },
                            modifier = Modifier.padding(top = 24.dp)
                        )
                    }

                    if (selectedTab == AboutTab.Info) {
                        item(key = "features") {
                            Column {
                                AboutSectionLabel(text = stringResource(R.string.about_section_about))
                                AboutFeatureCard(onOpenTerms = { showTermsDialog = true })
                            }
                        }

                        item(key = "how_to_use") {
                            AboutHowToUseSection()
                        }
                    }

                    if (selectedTab == AboutTab.Settings) {
                        // Appearance Section
                        item(key = "appearance") {
                            Column {
                                AboutSectionLabel(text = stringResource(R.string.about_section_theme))
                                val themePref by com.bitchat.android.ui.theme.ThemePreferenceManager.themeFlow.collectAsState()
                                val chatUiMode by com.bitchat.android.ui.theme.ChatUiModeManager.modeFlow.collectAsState()
                                val appUiStyle by com.bitchat.android.ui.theme.AppUiStyleManager.styleFlow.collectAsState()
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = AboutHorizontalPadding),
                                    color = colorScheme.surface,
                                    shape = AboutCardShape
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            ThemeChip(
                                                label = "クラシック形式",
                                                selected = appUiStyle.isClassic,
                                                onClick = {
                                                    com.bitchat.android.ui.theme.AppUiStyleManager.set(
                                                        context,
                                                        com.bitchat.android.ui.theme.AppUiStyle.CLASSIC
                                                    )
                                                },
                                                modifier = Modifier.weight(1f)
                                            )
                                            ThemeChip(
                                                label = "チャンネル形式",
                                                selected = appUiStyle.isChannelMode,
                                                onClick = {
                                                    com.bitchat.android.ui.theme.AppUiStyleManager.set(
                                                        context,
                                                        com.bitchat.android.ui.theme.AppUiStyle.CHANNELS
                                                    )
                                                },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            ThemeChip(
                                                label = stringResource(R.string.about_system),
                                                selected = themePref.isSystem,
                                                onClick = { com.bitchat.android.ui.theme.ThemePreferenceManager.set(context, com.bitchat.android.ui.theme.ThemePreference.System) },
                                                modifier = Modifier.weight(1f)
                                            )
                                            ThemeChip(
                                                label = stringResource(R.string.about_light),
                                                selected = themePref.isLight,
                                                onClick = { com.bitchat.android.ui.theme.ThemePreferenceManager.set(context, com.bitchat.android.ui.theme.ThemePreference.Light) },
                                                modifier = Modifier.weight(1f)
                                            )
                                            ThemeChip(
                                                label = stringResource(R.string.about_dark),
                                                selected = themePref.isDark,
                                                onClick = { com.bitchat.android.ui.theme.ThemePreferenceManager.set(context, com.bitchat.android.ui.theme.ThemePreference.Dark) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            ThemeChip(
                                                label = stringResource(R.string.chat_ui_bubbles),
                                                selected = chatUiMode.isBubbles,
                                                onClick = { com.bitchat.android.ui.theme.ChatUiModeManager.set(context, com.bitchat.android.ui.theme.ChatUiMode.Bubbles) },
                                                modifier = Modifier.weight(1f)
                                            )
                                            ThemeChip(
                                                label = stringResource(R.string.chat_ui_matrix),
                                                selected = chatUiMode.isMatrix,
                                                onClick = { com.bitchat.android.ui.theme.ChatUiModeManager.set(context, com.bitchat.android.ui.theme.ChatUiMode.Matrix) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Language Section
                        item(key = "language") {
                            val selectedLanguageName = supportedLanguages
                                .firstOrNull { it.languageTag == selectedLanguageTag }
                                ?.endonym
                                ?: stringResource(R.string.about_system_default)

                            Column {
                                AboutSectionLabel(text = stringResource(R.string.about_language))
                                BoxWithConstraints(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = AboutHorizontalPadding),
                                ) {
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = colorScheme.surface,
                                        shape = AboutCardShape,
                                    ) {
                                        LanguageSettingsRow(
                                            selectedLanguageName = selectedLanguageName,
                                            onClick = { showLanguagePicker = true },
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showLanguagePicker,
                                        onDismissRequest = { showLanguagePicker = false },
                                        modifier = Modifier.width(maxWidth),
                                    ) {
                                        LanguageMenuItem(
                                            label = stringResource(R.string.about_system_default),
                                            selected = selectedLanguageTag.isEmpty(),
                                            onClick = {
                                                showLanguagePicker = false
                                                if (selectedLanguageTag.isNotEmpty()) {
                                                    selectedLanguageTag = ""
                                                    LanguagePreferenceManager.setLanguage("")
                                                }
                                            },
                                        )
                                        HorizontalDivider()
                                        supportedLanguages.forEach { language ->
                                            LanguageMenuItem(
                                                label = language.endonym,
                                                selected = selectedLanguageTag == language.languageTag,
                                                onClick = {
                                                    showLanguagePicker = false
                                                    if (language.languageTag != selectedLanguageTag) {
                                                        selectedLanguageTag = language.languageTag
                                                        LanguagePreferenceManager.setLanguage(language.languageTag)
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Network & Serverless Relay Section
                        item(key = "network_settings") {
                            val internetEnabled by InternetRelayPreferences.internetEnabled.collectAsState()
                            val meshBridgeEnabled by InternetRelayPreferences.meshBridgeEnabled.collectAsState()
                            val customRelayUrl by InternetRelayPreferences.customRelayUrl.collectAsState()
                            val torStatus by ArtiTorManager.getInstance().statusFlow.collectAsState()
                            val nearbyVisible by com.bitchat.android.service.MeshServicePreferences.nearbyVisibleFlow.collectAsState()

                            Column {
                                AboutSectionLabel(text = "完全P2P通信・周辺公開・リレー設定")
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = AboutHorizontalPadding),
                                    color = colorScheme.surface,
                                    shape = AboutCardShape
                                ) {
                                    Column {
                                        SettingsActionRow(
                                            icon = Icons.Filled.QrCode,
                                            title = "QR・URLで連絡先交換",
                                            subtitle = "いつでも再発行できる永久リンク / 10分〜24時間で消滅する時間制限付きリンク",
                                            onClick = { showContactExchangeSheet = true }
                                        )

                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 54.dp),
                                            thickness = 1.dp,
                                            color = colorScheme.outlineVariant
                                        )

                                        SettingsToggleRow(
                                            icon = Icons.Filled.Bluetooth,
                                            title = "周辺に自分を公開する",
                                            subtitle = if (nearbyVisible) {
                                                "周辺のnemix端末から検出・近距離メッシュ通信を許可しています"
                                            } else {
                                                "非公開モード（近距離公開を停止・QRコードまたはUUID追加のみ許可）"
                                            },
                                            checked = nearbyVisible,
                                            onCheckedChange = { enabled ->
                                                com.bitchat.android.service.MeshServicePreferences.setNearbyVisible(enabled)
                                            }
                                        )

                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 54.dp),
                                            thickness = 1.dp,
                                            color = colorScheme.outlineVariant
                                        )

                                        SettingsToggleRow(
                                            icon = Icons.Filled.Public,
                                            title = "インターネットP2P通信 (UUID / E2EE)",
                                            subtitle = "近距離無線に加え、ネット経由のUUIDユーザー追加・P2Pグループ・通話・画像転送を有効化",
                                            checked = internetEnabled,
                                            onCheckedChange = { enabled ->
                                                InternetRelayPreferences.setInternetEnabled(context, enabled)
                                                val relayMgr = NostrRelayManager.getInstance(context)
                                                if (enabled) {
                                                    (context.applicationContext as? Application)?.let { app ->
                                                        runCatching { NostrBackgroundRuntime.initialize(app) }
                                                    }
                                                    runCatching { relayMgr.connect() }
                                                    coroutineScope.launch(Dispatchers.IO) {
                                                        NemixUuidDirectoryManager.publishMyDirectoryRecord(
                                                            context,
                                                            AppStateStore.nickname.value
                                                        )
                                                        P2PGroupManager.refreshRelayGroupSubscriptions(context)
                                                    }
                                                } else {
                                                    runCatching { relayMgr.disconnect() }
                                                }
                                            }
                                        )

                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 54.dp),
                                            thickness = 1.dp,
                                            color = colorScheme.outlineVariant
                                        )

                                        SettingsToggleRow(
                                            icon = Icons.Filled.Hub,
                                            title = "P2P 遠隔リレー / メッシュ中継ノード",
                                            subtitle = "ネット接続時、周囲のオフライン端末をゼロ知識で支援し暗号化パケットを遠隔中継します（近くにいなくても届くメリット／微量のバッテリー・通信量消費）",
                                            checked = meshBridgeEnabled,
                                            onCheckedChange = { enabled ->
                                                InternetRelayPreferences.setMeshBridgeEnabled(context, enabled)
                                            }
                                        )

                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 54.dp),
                                            thickness = 1.dp,
                                            color = colorScheme.outlineVariant
                                        )

                                        val relaySummary = buildString {
                                            if (customRelayUrl.isNotBlank()) {
                                                append("カスタム: ")
                                                append(customRelayUrl.lineSequence().first().take(28))
                                            } else {
                                                append("標準P2Pリレー")
                                            }
                                            append(" · Tor: ")
                                            append(
                                                if (torStatus.mode == TorMode.ON) {
                                                    "ON (${torStatus.bootstrapPercent}%)"
                                                } else {
                                                    "OFF"
                                                }
                                            )
                                        }

                                        SettingsActionRow(
                                            icon = Icons.Filled.Dns,
                                            title = "カスタムP2Pリレー / 自前ノード / Tor設定",
                                            subtitle = relaySummary,
                                            onClick = { showCustomRelayDialog = true },
                                            badge = if (torStatus.mode == TorMode.ON) {
                                                { BitchatBadge(text = "TOR") }
                                            } else null
                                        )
                                    }
                                }
                            }
                        }

                        // Core Mesh & Notification Settings
                        item(key = "settings") {
                            val spamFilterEnabled by SpamFilterPreferences.enabled.collectAsState()
                            var backgroundEnabled by remember {
                                mutableStateOf(com.bitchat.android.service.MeshServicePreferences.isBackgroundEnabled(true))
                            }
                            var notifyFavoritesOnly by remember {
                                mutableStateOf(com.bitchat.android.service.MeshServicePreferences.isNearbyNotifyFavoritesOnly(true))
                            }
                            var liveVoiceEnabled by remember {
                                mutableStateOf(com.bitchat.android.features.voice.LiveVoicePreferences.isEnabled(context))
                            }

                            Column {
                                AboutSectionLabel(text = stringResource(R.string.about_section_settings))
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = AboutHorizontalPadding),
                                    color = colorScheme.surface,
                                    shape = AboutCardShape
                                ) {
                                    Column {
                                        SettingsToggleRow(
                                            icon = Icons.Filled.Bluetooth,
                                            title = stringResource(R.string.about_background_title),
                                            subtitle = stringResource(R.string.about_background_desc),
                                            checked = backgroundEnabled,
                                            onCheckedChange = { enabled ->
                                                backgroundEnabled = enabled
                                                com.bitchat.android.service.MeshServicePreferences.setBackgroundEnabled(enabled)
                                                if (!enabled) {
                                                    com.bitchat.android.service.MeshForegroundService.stop(context)
                                                } else {
                                                    com.bitchat.android.service.MeshForegroundService.start(context)
                                                }
                                            }
                                        )

                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 54.dp),
                                            thickness = 1.dp,
                                            color = colorScheme.outlineVariant
                                        )

                                        SettingsToggleRow(
                                            icon = Icons.Filled.Notifications,
                                            title = "近くの通知（登録した連絡先のみ）",
                                            subtitle = "お気に入り・連絡先に登録した人が近くに現れた時だけ通知します",
                                            checked = notifyFavoritesOnly,
                                            onCheckedChange = { enabled ->
                                                notifyFavoritesOnly = enabled
                                                com.bitchat.android.service.MeshServicePreferences.setNearbyNotifyFavoritesOnly(enabled)
                                            }
                                        )

                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 54.dp),
                                            thickness = 1.dp,
                                            color = colorScheme.outlineVariant
                                        )

                                        SettingsToggleRow(
                                            icon = Icons.Filled.Mic,
                                            title = "ライブ・プッシュトゥトーク",
                                            subtitle = "録音中にメッシュへリアルタイム音声ストリームを送信します",
                                            checked = liveVoiceEnabled,
                                            onCheckedChange = { enabled ->
                                                liveVoiceEnabled = enabled
                                                com.bitchat.android.features.voice.LiveVoicePreferences.setEnabled(context, enabled)
                                            }
                                        )

                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 54.dp),
                                            thickness = 1.dp,
                                            color = colorScheme.outlineVariant
                                        )

                                        SettingsToggleRow(
                                            icon = Icons.Filled.FilterAlt,
                                            title = stringResource(R.string.about_spam_filter_title),
                                            subtitle = stringResource(R.string.about_spam_filter_desc),
                                            checked = spamFilterEnabled,
                                            onCheckedChange = { SpamFilterPreferences.setEnabled(it) }
                                        )
                                    }
                                }
                            }
                        }

                        // Security, Screenshot Protection, Legal, GitHub & Play Store Update Section
                        item(key = "security_and_legal") {
                            val appLockEnabled by AppLockManager.isEnabled.collectAsState()
                            val globalSsBlock by ChatSecurityPolicyManager.globalScreenshotBlocked.collectAsState()

                            Column {
                                AboutSectionLabel(text = "セキュリティ・法務・開発者情報")
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = AboutHorizontalPadding),
                                    color = colorScheme.surface,
                                    shape = AboutCardShape
                                ) {
                                    Column {
                                        SettingsToggleRow(
                                            icon = Icons.Filled.Lock,
                                            title = "アプリロック（生体認証 / 端末PIN）",
                                            subtitle = "OS標準の生体認証・画面ロックで復帰時にプライバシーを保護します",
                                            checked = appLockEnabled,
                                            onCheckedChange = { enabled ->
                                                if (enabled && !AppLockManager.isDeviceSecure(context)) {
                                                    Toast.makeText(
                                                        context,
                                                        "OS設定で端末の画面ロック（PIN・指紋・顔認証）を先に設定してください",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                } else {
                                                    AppLockManager.setAppLockEnabled(context, enabled)
                                                }
                                            }
                                        )

                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 54.dp),
                                            thickness = 1.dp,
                                            color = colorScheme.outlineVariant
                                        )

                                        SettingsToggleRow(
                                            icon = Icons.Filled.ScreenLockPortrait,
                                            title = "全画面でスクショ禁止 (強制保護)",
                                            subtitle = "アプリ全体のスクリーンショットと画面録画を常時禁止します（個別チャットごとの相手側禁止も可能）",
                                            checked = globalSsBlock,
                                            onCheckedChange = { blocked ->
                                                ChatSecurityPolicyManager.setGlobalScreenshotBlocked(context, blocked)
                                            }
                                        )

                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 54.dp),
                                            thickness = 1.dp,
                                            color = colorScheme.outlineVariant
                                        )

                                        SettingsActionRow(
                                            icon = Icons.Filled.Gavel,
                                            title = "利用規約・プライバシーポリシー",
                                            subtitle = "アプリの目的（3つの課題）・電波法/航空法遵守・免責事項",
                                            onClick = { showTermsDialog = true }
                                        )

                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 54.dp),
                                            thickness = 1.dp,
                                            color = colorScheme.outlineVariant
                                        )

                                        SettingsActionRow(
                                            icon = Icons.Filled.Code,
                                            title = "開発者 GitHub (ShotaXiangtai)",
                                            subtitle = DEVELOPER_GITHUB_URL,
                                            onClick = {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(Intent.ACTION_VIEW, Uri.parse(DEVELOPER_GITHUB_URL))
                                                    )
                                                }
                                            }
                                        )

                                        HorizontalDivider(
                                            modifier = Modifier.padding(start = 54.dp),
                                            thickness = 1.dp,
                                            color = colorScheme.outlineVariant
                                        )

                                        SettingsActionRow(
                                            icon = Icons.Filled.SystemUpdate,
                                            title = "Google Play でアップデートを確認",
                                            subtitle = "アプリの更新は Google Play ストア経由で安全に提供されます",
                                            onClick = {
                                                val pkg = context.packageName
                                                val marketIntent = Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse("market://details?id=$pkg")
                                                )
                                                val webIntent = Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse("https://play.google.com/store/apps/details?id=$pkg")
                                                )
                                                runCatching { context.startActivity(marketIntent) }
                                                    .onFailure {
                                                        runCatching { context.startActivity(webIntent) }
                                                    }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    } // end Settings tab

                    // Footer
                    item(key = "footer") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = AboutHorizontalPadding,
                                    end = AboutHorizontalPadding,
                                    top = 24.dp
                                ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (selectedTab == AboutTab.Settings && onShowDebug != null) {
                                TextButton(onClick = onShowDebug) {
                                    Text(
                                        text = stringResource(R.string.about_debug_settings),
                                        fontSize = 13.sp,
                                        fontFamily = BitchatFontFamily,
                                        color = colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                text = stringResource(R.string.about_footer),
                                fontSize = 11.sp,
                                fontFamily = BitchatFontFamily,
                                color = palette.textTertiary
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                        }
                    }
                }

                // TopBar
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(64.dp)
                        .background(colorScheme.background.copy(alpha = topBarAlpha))
                ) {
                    val dismiss = LocalSheetDismiss.current
                    CloseButton(
                        onClick = { dismiss?.invoke() ?: onDismiss() },
                        modifier = modifier
                            .align(Alignment.CenterEnd)
                            .padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomRelayAndTorDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme

    var relayUrlInput by remember { mutableStateOf(InternetRelayPreferences.getCustomRelayUrl()) }
    var customOnlyInput by remember { mutableStateOf(InternetRelayPreferences.isCustomRelayOnly()) }
    val currentTorMode by TorPreferenceManager.modeFlow.collectAsState()
    var torEnabledInput by remember { mutableStateOf(currentTorMode == TorMode.ON) }
    val torStatus by ArtiTorManager.getInstance().statusFlow.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "カスタムP2Pサーバー / PCリレー / Tor設定",
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                fontFamily = BitchatFontFamily
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "PC版 nemix の内蔵P2Pサーバー (ws://PCのIP:7447)、自前の Ubuntu サーバー (wss://...)、または Tor Hidden Service (ws://xxx.onion) を指定できます。空欄の場合は標準リレーを使用します。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = colorScheme.onSurfaceVariant,
                    fontFamily = BitchatFontFamily
                )

                OutlinedTextField(
                    value = relayUrlInput,
                    onValueChange = { relayUrlInput = it },
                    label = { Text("リレーURL (ws://, wss:// または ws://*.onion)", fontSize = 12.sp) },
                    placeholder = { Text("例: ws://192.168.1.20:7447 または wss://relay.example.com", fontSize = 12.sp) },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { customOnlyInput = !customOnlyInput }
                ) {
                    Checkbox(
                        checked = customOnlyInput,
                        onCheckedChange = { customOnlyInput = it }
                    )
                    Text(
                        text = "指定したP2Pサーバーのみ使用（公開リレーに接続しない）",
                        fontSize = 12.sp,
                        color = colorScheme.onSurface,
                        fontFamily = BitchatFontFamily
                    )
                }

                HorizontalDivider()

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Tor 匿名ルーティング (Arti)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.onSurface,
                            fontFamily = BitchatFontFamily
                        )
                        Text(
                            text = if (torStatus.mode == TorMode.ON) {
                                "状態: ${torStatus.state} (${torStatus.bootstrapPercent}%)"
                            } else {
                                ".onion サーバー接続時やIP秘匿時に有効化"
                            },
                            fontSize = 11.sp,
                            color = colorScheme.onSurfaceVariant,
                            fontFamily = BitchatFontFamily
                        )
                    }
                    Switch(
                        checked = torEnabledInput,
                        onCheckedChange = { torEnabledInput = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    InternetRelayPreferences.setCustomRelayConfig(
                        context = context,
                        url = relayUrlInput,
                        customOnly = customOnlyInput
                    )
                    val desiredTor = if (torEnabledInput) TorMode.ON else TorMode.OFF
                    if (desiredTor != TorPreferenceManager.get(context)) {
                        (context.applicationContext as? Application)?.let { app ->
                            runCatching { ArtiTorManager.getInstance().init(app) }
                        }
                        TorPreferenceManager.set(context, desiredTor)
                        OkHttpProvider.reset()
                    }
                    val relayMgr = NostrRelayManager.getInstance(context)
                    relayMgr.disconnect()
                    if (InternetRelayPreferences.isInternetEnabled()) {
                        relayMgr.connect()
                        coroutineScope.launch(Dispatchers.IO) {
                            NemixUuidDirectoryManager.publishMyDirectoryRecord(
                                context,
                                AppStateStore.nickname.value
                            )
                            P2PGroupManager.refreshRelayGroupSubscriptions(context)
                        }
                    }
                    Toast.makeText(context, "P2P通信設定を保存しました", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }
            ) {
                Text("保存", fontFamily = BitchatFontFamily)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル", fontFamily = BitchatFontFamily)
            }
        },
        containerColor = colorScheme.surface
    )
}

/**
 * Password prompt dialog for password-protected channels
 */
@Composable
fun PasswordPromptDialog(
    show: Boolean,
    channelName: String?,
    passwordInput: String,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (show && channelName != null) {
        val colorScheme = MaterialTheme.colorScheme

        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = stringResource(R.string.pwd_prompt_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = colorScheme.onSurface
                )
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.pwd_prompt_message, channelName ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = onPasswordChange,
                        label = { Text(stringResource(R.string.pwd_label), style = MaterialTheme.typography.bodyMedium) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = BitchatFontFamily
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colorScheme.primary,
                            unfocusedBorderColor = colorScheme.outline
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text(
                        text = stringResource(R.string.join),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.primary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorScheme.onSurface
                    )
                }
            },
            containerColor = colorScheme.surface,
            tonalElevation = 8.dp
        )
    }
}
