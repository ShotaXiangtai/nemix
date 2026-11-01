package com.bitchat.android.net

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preferences for optional Internet Relay communication, Custom Server / Tor (.onion) relays,
 * and Serverless Mesh-to-Internet Gateway Bridging.
 */
object InternetRelayPreferences {
    private const val PREFS_NAME = "nemix_internet_prefs"
    private const val KEY_INTERNET_ENABLED = "internet_enabled"
    private const val KEY_MESH_BRIDGE_ENABLED = "mesh_internet_bridge_enabled"
    private const val KEY_CUSTOM_RELAY_URL = "custom_relay_url"
    private const val KEY_CUSTOM_RELAY_ONLY = "custom_relay_only"

    val BUILTIN_PUBLIC_RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://relay.primal.net",
        "wss://nos.lol",
        "wss://offchain.pub"
    )

    private var prefs: SharedPreferences? = null

    private val _internetEnabled = MutableStateFlow(true)
    val internetEnabled: StateFlow<Boolean> = _internetEnabled.asStateFlow()

    private val _meshBridgeEnabled = MutableStateFlow(true)
    val meshBridgeEnabled: StateFlow<Boolean> = _meshBridgeEnabled.asStateFlow()

    private val _customRelayUrl = MutableStateFlow("")
    val customRelayUrl: StateFlow<String> = _customRelayUrl.asStateFlow()

    private val _customRelayOnly = MutableStateFlow(false)
    val customRelayOnly: StateFlow<Boolean> = _customRelayOnly.asStateFlow()

    fun init(context: Context) {
        val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        _internetEnabled.value = sp.getBoolean(KEY_INTERNET_ENABLED, true)
        _meshBridgeEnabled.value = sp.getBoolean(KEY_MESH_BRIDGE_ENABLED, true)
        _customRelayUrl.value = sp.getString(KEY_CUSTOM_RELAY_URL, "") ?: ""
        _customRelayOnly.value = sp.getBoolean(KEY_CUSTOM_RELAY_ONLY, false)
    }

    fun isInternetEnabled(): Boolean = _internetEnabled.value

    fun setInternetEnabled(context: Context, enabled: Boolean) {
        if (prefs == null) init(context)
        prefs?.edit()?.putBoolean(KEY_INTERNET_ENABLED, enabled)?.apply()
        _internetEnabled.value = enabled
    }

    fun isMeshBridgeEnabled(): Boolean = _meshBridgeEnabled.value

    fun setMeshBridgeEnabled(context: Context, enabled: Boolean) {
        if (prefs == null) init(context)
        prefs?.edit()?.putBoolean(KEY_MESH_BRIDGE_ENABLED, enabled)?.apply()
        _meshBridgeEnabled.value = enabled
    }

    fun getCustomRelayUrl(): String = _customRelayUrl.value

    fun setCustomRelayConfig(context: Context, url: String, customOnly: Boolean) {
        if (prefs == null) init(context)
        val cleaned = url.trim()
        prefs?.edit()
            ?.putString(KEY_CUSTOM_RELAY_URL, cleaned)
            ?.putBoolean(KEY_CUSTOM_RELAY_ONLY, customOnly)
            ?.apply()
        _customRelayUrl.value = cleaned
        _customRelayOnly.value = customOnly
    }

    fun isCustomRelayOnly(): Boolean = _customRelayOnly.value

    fun getActiveRelays(): List<String> {
        if (!_internetEnabled.value) return emptyList()
        val custom = _customRelayUrl.value.trim()
            .split(",", "\n", " ")
            .map { it.trim() }
            .filter { it.startsWith("wss://", ignoreCase = true) || it.startsWith("ws://", ignoreCase = true) }

        return if (_customRelayOnly.value && custom.isNotEmpty()) {
            custom.distinct()
        } else {
            (custom + BUILTIN_PUBLIC_RELAYS).distinct()
        }
    }

    fun hasActiveNetworkConnection(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            false
        }
    }
}

