package com.bitchat.android.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import java.util.concurrent.TimeUnit

/**
 * Background update check (GitHub build). About twice a day, when a network is available, it reads
 * the latest release's version file and posts one notification per new version. Tapping the
 * notification opens nemix with the update dialog. Nothing is sent except the plain download
 * request for the public version file.
 */
class UpdateCheckWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!AppUpdater.isSupported || !UpdateNotifications.isEnabled(applicationContext)) return Result.success()
        val update = AppUpdater.check(applicationContext).getOrElse { return Result.retry() }
            ?: return Result.success()
        UpdateNotifications.notifyIfNew(applicationContext, update)
        return Result.success()
    }
}

object UpdateNotifications {
    const val EXTRA_OPEN_UPDATE = "nemix_open_update"
    private const val WORK_NAME = "nemix_update_check"
    private const val CHANNEL_ID = "nemix_app_update"
    private const val NOTIFICATION_ID = 0x4E55 // "NU"
    private const val PREFS = "nemix_app_update"
    private const val KEY_NOTIFIED_CODE = "notified_version_code"
    private const val KEY_ENABLED = "update_notifications_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) schedule(context) else cancel(context)
    }

    /** Idempotent: call on every app start. */
    fun schedule(context: Context) {
        if (!AppUpdater.isSupported || !isEnabled(context)) return
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS, 2, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(1, TimeUnit.HOURS)
            .build()
        runCatching {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }

    private fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME) }
    }

    fun notifyIfNew(context: Context, update: AvailableUpdate) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_NOTIFIED_CODE, -1L) == update.versionCode) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 5,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("nemix ${update.versionName} が利用できます")
            .setContentText("タップしてアップデートします")
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        val posted = runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }.isSuccess
        if (posted) prefs.edit().putLong(KEY_NOTIFIED_CODE, update.versionCode).apply()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "アップデート", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "nemix の新しいバージョンのお知らせ" }
        )
    }
}
