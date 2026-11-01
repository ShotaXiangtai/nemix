package com.bitchat.android.update

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class AppUpdateState {
    data object Idle : AppUpdateState()
    data object Checking : AppUpdateState()
    data object UpToDate : AppUpdateState()
    data class Available(val update: AvailableUpdate) : AppUpdateState()
    data class Downloading(val update: AvailableUpdate, val percent: Int) : AppUpdateState()
    data class NeedsInstallPermission(val apk: File) : AppUpdateState()
    data class ReadyToInstall(val apk: File) : AppUpdateState()
    data class Failed(val message: String) : AppUpdateState()
}

/**
 * Drives the in-app update flow (GitHub build only; the Play build reports no updates).
 * Automatic checks run at most every 12 hours and stay silent unless an update exists.
 */
object AppUpdateController {
    private const val PREFS = "nemix_app_update"
    private const val KEY_LAST_CHECK = "last_check_ms"
    private const val KEY_SKIPPED_CODE = "skipped_version_code"
    private const val AUTO_CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()
    private var job: Job? = null

    fun checkAutomatically(context: Context) {
        if (!AppUpdater.isSupported || job?.isActive == true) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < AUTO_CHECK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        job = scope.launch {
            val update = AppUpdater.check(context.applicationContext).getOrNull() ?: return@launch
            if (update.versionCode == prefs.getLong(KEY_SKIPPED_CODE, -1L)) return@launch
            _state.value = AppUpdateState.Available(update)
        }
    }

    fun checkNow(context: Context) {
        if (!AppUpdater.isSupported) return
        job?.cancel()
        _state.value = AppUpdateState.Checking
        job = scope.launch {
            AppUpdater.check(context.applicationContext)
                .onSuccess { update ->
                    _state.value = if (update != null) AppUpdateState.Available(update) else AppUpdateState.UpToDate
                }
                .onFailure {
                    _state.value = AppUpdateState.Failed("更新を確認できませんでした。通信状態を確認して、もう一度お試しください。")
                }
        }
    }

    fun later(context: Context, update: AvailableUpdate) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SKIPPED_CODE, update.versionCode).apply()
        _state.value = AppUpdateState.Idle
    }

    fun startDownload(context: Context, update: AvailableUpdate) {
        job?.cancel()
        _state.value = AppUpdateState.Downloading(update, 0)
        val appContext = context.applicationContext
        job = scope.launch {
            AppUpdater.download(appContext) { percent ->
                scope.launch { _state.value = AppUpdateState.Downloading(update, percent.coerceIn(0, 100)) }
            }.onSuccess { apk ->
                install(appContext, apk)
            }.onFailure {
                _state.value = AppUpdateState.Failed(
                    "ダウンロードに失敗したか、署名を確認できませんでした。改ざんを防ぐため、nemix の正式な署名がある更新だけをインストールします。"
                )
            }
        }
    }

    fun install(context: Context, apk: File) {
        if (AppUpdater.needsInstallPermission(context)) {
            _state.value = AppUpdateState.NeedsInstallPermission(apk)
            return
        }
        _state.value = if (AppUpdater.install(context, apk)) AppUpdateState.Idle else AppUpdateState.ReadyToInstall(apk)
    }

    fun dismiss() {
        _state.value = AppUpdateState.Idle
    }
}

/** Dialogs for the in-app update flow. Place once near the root of the UI. */
@Composable
fun AppUpdatePrompt() {
    val context = LocalContext.current
    val state by AppUpdateController.state.collectAsStateWithLifecycle()

    when (val s = state) {
        AppUpdateState.Idle -> Unit

        AppUpdateState.Checking -> AlertDialog(
            onDismissRequest = { AppUpdateController.dismiss() },
            title = { Text("アップデートを確認中") },
            text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
            confirmButton = {}
        )

        AppUpdateState.UpToDate -> AlertDialog(
            onDismissRequest = { AppUpdateController.dismiss() },
            title = { Text("最新版です") },
            text = { Text("お使いの nemix は最新です。") },
            confirmButton = { TextButton(onClick = { AppUpdateController.dismiss() }) { Text("OK") } }
        )

        is AppUpdateState.Available -> AlertDialog(
            onDismissRequest = { AppUpdateController.dismiss() },
            title = { Text("アップデートがあります") },
            text = {
                Text("nemix ${s.update.versionName} が利用できます。ダウンロードしてインストールしますか？\n\nダウンロードした更新は、nemix の正式な署名があることを確認してからインストールします。")
            },
            confirmButton = {
                TextButton(onClick = { AppUpdateController.startDownload(context, s.update) }) { Text("更新する") }
            },
            dismissButton = {
                TextButton(onClick = { AppUpdateController.later(context, s.update) }) { Text("今はしない") }
            }
        )

        is AppUpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("ダウンロード中 (${s.percent}%)") },
            text = {
                Column {
                    LinearProgressIndicator(
                        progress = { s.percent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("nemix ${s.update.versionName}")
                }
            },
            confirmButton = {
                TextButton(onClick = { AppUpdateController.dismiss() }) { Text("バックグラウンドで続ける") }
            }
        )

        is AppUpdateState.NeedsInstallPermission -> AlertDialog(
            onDismissRequest = { AppUpdateController.dismiss() },
            title = { Text("インストールの許可が必要です") },
            text = {
                Text("アップデートをインストールするには、設定で nemix に「この提供元のアプリを許可」を有効にしてください。許可したら「インストール」を押してください。")
            },
            confirmButton = {
                TextButton(onClick = { AppUpdateController.install(context, s.apk) }) { Text("インストール") }
            },
            dismissButton = {
                TextButton(onClick = { AppUpdater.openInstallPermissionSettings(context) }) { Text("設定を開く") }
            }
        )

        is AppUpdateState.ReadyToInstall -> AlertDialog(
            onDismissRequest = { AppUpdateController.dismiss() },
            title = { Text("インストールの準備ができました") },
            text = { Text("インストーラーを開けませんでした。もう一度お試しください。") },
            confirmButton = {
                TextButton(onClick = { AppUpdateController.install(context, s.apk) }) { Text("インストール") }
            },
            dismissButton = {
                TextButton(onClick = { AppUpdateController.dismiss() }) { Text("閉じる") }
            }
        )

        is AppUpdateState.Failed -> AlertDialog(
            onDismissRequest = { AppUpdateController.dismiss() },
            title = { Text("アップデートできませんでした") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { AppUpdateController.dismiss() }) { Text("OK") } }
        )
    }
}
