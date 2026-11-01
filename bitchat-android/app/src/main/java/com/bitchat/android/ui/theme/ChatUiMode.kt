package com.bitchat.android.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Overall application UI style selectable on first launch and anytime in Settings:
 * - [CLASSIC]: Classic messenger UI (bottom/top tabs, chat list, rounded bubbles)
 * - [CHANNELS]: Channel-format UI (left server/DM icon rail, channel/DM drawer, flat dark stream)
 */
enum class AppUiStyle(val label: String) {
    CLASSIC("クラシック形式"),
    CHANNELS("チャンネル形式");

    val isClassic: Boolean get() = this == CLASSIC
    val isChannelMode: Boolean get() = this == CHANNELS
}

object AppUiStyleManager {
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_APP_UI_STYLE = "nemix_app_ui_style"
    private const val KEY_APP_UI_STYLE_SELECTED = "nemix_app_ui_style_selected"

    private val _styleFlow = MutableStateFlow(AppUiStyle.CLASSIC)
    val styleFlow: StateFlow<AppUiStyle> = _styleFlow.asStateFlow()

    private val _hasSelectedStyle = MutableStateFlow(false)
    val hasSelectedStyle: StateFlow<Boolean> = _hasSelectedStyle.asStateFlow()

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_APP_UI_STYLE, AppUiStyle.CLASSIC.name)
        _styleFlow.value = runCatching { AppUiStyle.valueOf(saved!!) }.getOrDefault(AppUiStyle.CLASSIC)
        _hasSelectedStyle.value = prefs.getBoolean(KEY_APP_UI_STYLE_SELECTED, false)
    }

    fun set(context: Context, style: AppUiStyle) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_APP_UI_STYLE, style.name)
            .putBoolean(KEY_APP_UI_STYLE_SELECTED, true)
            .apply()
        _styleFlow.value = style
        _hasSelectedStyle.value = true
    }

    fun clear(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_APP_UI_STYLE)
            .remove(KEY_APP_UI_STYLE_SELECTED)
            .commit()
        _styleFlow.value = AppUiStyle.CLASSIC
        _hasSelectedStyle.value = false
    }
}

/**
 * Chat transcript presentation.
 */
enum class ChatUiMode {
    Matrix,
    Bubbles;

    val isMatrix: Boolean get() = this == Matrix
    val isBubbles: Boolean get() = this == Bubbles
}

object ChatUiModeManager {
    private const val PREFS_NAME = "bitchat_settings"
    private const val KEY_CHAT_UI_MODE = "chat_ui_mode"

    private val _modeFlow = MutableStateFlow(ChatUiMode.Bubbles)
    val modeFlow: StateFlow<ChatUiMode> = _modeFlow

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_CHAT_UI_MODE, ChatUiMode.Bubbles.name)
        _modeFlow.value = runCatching { ChatUiMode.valueOf(saved!!) }.getOrDefault(ChatUiMode.Bubbles)
    }

    fun set(context: Context, mode: ChatUiMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CHAT_UI_MODE, mode.name).apply()
        _modeFlow.value = mode
    }
}
