package com.bitchat.android.service

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object MeshServicePreferences {
    private const val PREFS_NAME = "bitchat_mesh_service_prefs"
    private const val KEY_AUTO_START = "auto_start_on_boot"
    private const val KEY_BACKGROUND_ENABLED = "background_enabled"
    private const val KEY_NEARBY_NOTIFY_FAVORITES_ONLY = "nearby_notify_favorites_only"
    private const val KEY_NEARBY_VISIBLE = "nemix_nearby_visible"

    private lateinit var prefs: SharedPreferences

    private val _nearbyVisibleFlow = MutableStateFlow(true)
    val nearbyVisibleFlow: StateFlow<Boolean> = _nearbyVisibleFlow.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _nearbyVisibleFlow.value = prefs.getBoolean(KEY_NEARBY_VISIBLE, true)
    }

    fun isAutoStartEnabled(default: Boolean = true): Boolean {
        return if (::prefs.isInitialized) prefs.getBoolean(KEY_AUTO_START, default) else default
    }

    fun setAutoStartEnabled(enabled: Boolean) {
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    fun isBackgroundEnabled(default: Boolean = true): Boolean {
        return if (::prefs.isInitialized) prefs.getBoolean(KEY_BACKGROUND_ENABLED, default) else default
    }

    fun setBackgroundEnabled(enabled: Boolean) {
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_BACKGROUND_ENABLED, enabled).apply()
    }

    fun isNearbyNotifyFavoritesOnly(default: Boolean = true): Boolean {
        return if (::prefs.isInitialized) prefs.getBoolean(KEY_NEARBY_NOTIFY_FAVORITES_ONLY, default) else default
    }

    fun setNearbyNotifyFavoritesOnly(enabled: Boolean) {
        if (::prefs.isInitialized) prefs.edit().putBoolean(KEY_NEARBY_NOTIFY_FAVORITES_ONLY, enabled).apply()
    }

    /**
     * Controls whether this device exposes itself to nearby devices over BLE Advertising /
     * Wi-Fi Aware / Mesh public announcements.
     * When `false` ("公開しない"), contact addition is restricted strictly to QR Code or UUID.
     */
    fun isNearbyVisible(default: Boolean = true): Boolean {
        return if (::prefs.isInitialized) prefs.getBoolean(KEY_NEARBY_VISIBLE, default) else default
    }

    fun setNearbyVisible(enabled: Boolean) {
        _nearbyVisibleFlow.value = enabled
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(KEY_NEARBY_VISIBLE, enabled).apply()
        }
        runCatching {
            if (!enabled) {
                com.bitchat.android.wifiaware.WifiAwareController.setEnabled(false)
            } else {
                val dbgWifi = runCatching {
                    com.bitchat.android.ui.debug.DebugPreferenceManager.getWifiAwareEnabled(true)
                }.getOrDefault(true)
                if (dbgWifi) {
                    com.bitchat.android.wifiaware.WifiAwareController.setEnabled(true)
                }
            }
        }
    }
}
