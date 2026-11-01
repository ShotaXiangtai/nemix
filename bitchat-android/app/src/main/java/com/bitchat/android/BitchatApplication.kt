package com.bitchat.android

import android.app.Application
import com.bitchat.android.directory.NemixUuidDirectoryManager
import com.bitchat.android.groups.P2PGroupManager
import com.bitchat.android.legal.TermsOfServiceManager
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.InternetRelayPreferences
import com.bitchat.android.nostr.NostrBackgroundRuntime
import com.bitchat.android.profile.UserProfileManager
import com.bitchat.android.security.AppLockManager
import com.bitchat.android.security.ChatSecurityPolicyManager
import com.bitchat.android.services.NemixProtocolInterceptor
import com.bitchat.android.ui.theme.ThemePreferenceManager

/**
 * Main application class for nemix - Serverless P2P Mesh + Optional E2EE Internet/Tor SNS
 */
class BitchatApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Start the single process-wide power policy before transport components are constructed.
        com.bitchat.android.mesh.PowerManager.getInstance(this).start()

        // Initialize nemix core managers & control-plane interceptor before transports start
        NemixProtocolInterceptor.init(this)
        TermsOfServiceManager.init(this)
        UserProfileManager.init(this)
        AppLockManager.init(this)
        ChatSecurityPolicyManager.init(this)
        InternetRelayPreferences.init(this)

        // Initialize built-in Arti Tor manager (remains OFF unless enabled in Settings)
        runCatching { ArtiTorManager.getInstance().init(this) }

        // Initialize favorites persistence early so MessageRouter can use it on startup
        try {
            com.bitchat.android.favorites.FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // Restore private conversations before background transports can deliver new messages.
        try {
            com.bitchat.android.services.AppStateStore.initializeConversationPersistence(this)
        } catch (_: Exception) { }

        // Restore the spam filter toggle before transports start admitting public messages
        com.bitchat.android.spam.SpamFilterPreferences.init(this)

        // Initialize theme preference
        ThemePreferenceManager.init(this)

        // Initialize chat UI mode and app-wide UI style (クラシック形式 / チャンネル形式)
        com.bitchat.android.ui.theme.ChatUiModeManager.init(this)
        com.bitchat.android.ui.theme.AppUiStyleManager.init(this)

        // Initialize chat archive manager
        runCatching { com.bitchat.android.services.ChatArchiveManager.init(this) }

        // Initialize mesh service preferences early so nearby visibility is respected
        try { com.bitchat.android.service.MeshServicePreferences.init(this) } catch (_: Exception) { }

        // Initialize debug preference manager
        try { com.bitchat.android.ui.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        // Initialize Wi‑Fi Aware controller with persisted default AND nearby visibility policy
        try {
            val nearbyVisible = com.bitchat.android.service.MeshServicePreferences.isNearbyVisible(true)
            val enabled = nearbyVisible && com.bitchat.android.ui.debug.DebugPreferenceManager.getWifiAwareEnabled(true)
            com.bitchat.android.wifiaware.WifiAwareController.initialize(this, enabled)
        } catch (_: Exception) { }

        // Start Internet Nostr background runtime if Internet communication is enabled
        if (InternetRelayPreferences.isInternetEnabled()) {
            runCatching { NostrBackgroundRuntime.initialize(this) }
        }

        // Initialize P2P Random UUID directory & 100% P2P Groups (Stream & Channel formats)
        runCatching { NemixUuidDirectoryManager.init(this) }
        runCatching { P2PGroupManager.init(this) }

        // Proactively start the foreground service to keep Bluetooth & Wi-Fi Aware mesh alive
        try { com.bitchat.android.service.MeshForegroundService.start(this) } catch (_: Exception) { }
    }
}
