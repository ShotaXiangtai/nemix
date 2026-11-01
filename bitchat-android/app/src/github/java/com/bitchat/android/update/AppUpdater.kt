package com.bitchat.android.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.bitchat.android.BuildConfig
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.OkHttpProvider
import com.bitchat.android.util.UniversalApkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * GitHub / privacy build: keeps the app up to date from GitHub Releases.
 *
 * 1. Reads the small `nemix-android-version.json` attached to the latest release
 *    (versionCode, versionName, tag) and compares versionCode with this install.
 * 2. Downloads `nemix-android-universal.apk` with [UniversalApkManager], which resumes
 *    partial downloads and rejects any APK not signed with this app's certificate.
 * 3. Hands the verified APK to the system package installer (the user confirms).
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val VERSION_URL =
        "https://github.com/ShotaXiangtai/nemix/releases/latest/download/nemix-android-version.json"
    private const val ROUTE_TIMEOUT_MS = 60_000L

    const val isSupported: Boolean = true

    suspend fun check(context: Context): Result<AvailableUpdate?> = withContext(Dispatchers.IO) {
        runCatching {
            if (!ArtiTorManager.getInstance().awaitSelectedRoute(ROUTE_TIMEOUT_MS)) {
                error("network route not ready")
            }
            val client = OkHttpProvider.routedHttpClient().client
            val request = Request.Builder()
                .url(VERSION_URL)
                .header("User-Agent", "nemix-Android")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code == 404) return@runCatching null // no release with version info yet
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body?.string()?.take(4096) ?: error("empty response")
                val json = JSONObject(body)
                val remoteCode = json.getLong("versionCode")
                val remoteName = json.optString("versionName")
                val tag = json.optString("tag")
                val current = currentVersionCode(context)
                Log.i(TAG, "Latest release $tag (code $remoteCode), installed code $current")
                if (remoteCode > current) {
                    AvailableUpdate(
                        versionName = tag.removePrefix("v").ifBlank { remoteName },
                        versionCode = remoteCode,
                        releaseUrl = "https://github.com/ShotaXiangtai/nemix/releases/tag/$tag"
                    )
                } else {
                    null
                }
            }
        }
    }

    suspend fun download(context: Context, onProgress: (Int) -> Unit): Result<File> {
        val result = UniversalApkManager(context.applicationContext)
            .downloadUniversalApk(progressCallback = onProgress)
        return result.mapCatching { file ->
            // Never "update" to the same or an older build.
            val archiveCode = archiveVersionCode(context, file)
            if (archiveCode <= currentVersionCode(context)) {
                error("downloaded APK is not newer than the installed version")
            }
            file
        }
    }

    /** Whether the user still has to allow nemix to install apps (Android 8+). */
    fun needsInstallPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    fun install(context: Context, apk: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.onFailure { Log.e(TAG, "Could not start the package installer", it) }.getOrDefault(false)

    private fun currentVersionCode(context: Context): Long =
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
            else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrDefault(BuildConfig.VERSION_CODE.toLong())

    private fun archiveVersionCode(context: Context, apk: File): Long {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return -1
        if (info.packageName != context.packageName) return -1
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }
}
