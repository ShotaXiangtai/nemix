package com.bitchat.android.call

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

private const val PREFS = "nemix_call_prefs"
private const val KEY_FSI_PROMPTED = "full_screen_intent_prompted"

/**
 * One-time explanation on Android 14+ when the "full-screen notifications" permission is off:
 * without it an incoming call is only a heads-up notification and never shows on the lock screen.
 * Settings keeps a permanent row for the same action.
 */
@Composable
fun FullScreenCallPrompt() {
    val context = LocalContext.current
    var visible by remember {
        mutableStateOf(
            !IncomingCallNotifier.canUseFullScreenIntent(context) &&
                !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FSI_PROMPTED, false)
        )
    }
    if (!visible) return

    fun close() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_FSI_PROMPTED, true).apply()
        visible = false
    }

    AlertDialog(
        onDismissRequest = ::close,
        title = { Text("ロック画面に着信を表示") },
        text = {
            Text(
                "この端末では「全画面通知」が許可されていないため、画面ロック中に通話が来ても着信画面が表示されません。\n\n" +
                    "次の画面で nemix の「全画面通知」をオンにしてください。（あとから 設定 でも変更できます）"
            )
        },
        confirmButton = {
            TextButton(onClick = {
                close()
                IncomingCallNotifier.openFullScreenIntentSettings(context)
            }) { Text("許可する") }
        },
        dismissButton = { TextButton(onClick = ::close) { Text("後で") } }
    )
}
