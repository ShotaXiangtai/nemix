package com.bitchat.android.call

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import com.bitchat.android.services.AppStateStore

/**
 * Incoming-call notification with the standard Android call style and a full-screen intent,
 * so a ringing call shows on the lock screen and turns the screen on (like the phone app).
 * The ringtone itself is played by [WirelessCallManager]; this channel is silent.
 */
object IncomingCallNotifier {
    private const val CHANNEL_ID = "nemix_incoming_call"
    private const val NOTIFICATION_ID = 0x4E43 // "NC"
    const val ACTION_ANSWER = "im.nemix.android.call.ANSWER"
    const val ACTION_DECLINE = "im.nemix.android.call.DECLINE"
    const val EXTRA_SHOW_INCOMING_CALL = "nemix_show_incoming_call"

    fun show(context: Context, callerName: String) {
        val ctx = context.applicationContext
        ensureChannel(ctx)

        val openCall = Intent(ctx, MainActivity::class.java)
            .putExtra(EXTRA_SHOW_INCOMING_CALL, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val fullScreen = PendingIntent.getActivity(
            ctx, 1, openCall, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val answer = PendingIntent.getBroadcast(
            ctx, 2, Intent(ctx, CallActionReceiver::class.java).setAction(ACTION_ANSWER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val decline = PendingIntent.getBroadcast(
            ctx, 3, Intent(ctx, CallActionReceiver::class.java).setAction(ACTION_DECLINE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val caller = Person.Builder()
            .setName(callerName.ifBlank { "nemix" })
            .setImportant(true)
            .build()

        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(callerName.ifBlank { "nemix" })
            .setContentText("nemix 暗号化音声通話の着信")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
            .addPerson(caller)
            .build()

        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIFICATION_ID, notification) }
    }

    fun cancel(context: Context) {
        runCatching { NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID) }
    }

    /** Android 14+ lets users revoke full-screen intents; without it the call shows as a heads-up only. */
    fun canUseFullScreenIntent(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun openFullScreenIntentSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "着信", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "nemix の音声通話の着信"
            setSound(null, null) // ringtone is played by WirelessCallManager
            enableVibration(false) // vibration is handled by WirelessCallManager
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }
}

/** Handles the Answer / Decline buttons of the incoming-call notification. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        IncomingCallNotifier.cancel(ctx)
        when (intent.action) {
            IncomingCallNotifier.ACTION_ANSWER -> {
                val myNick = AppStateStore.nickname.value.ifBlank { "User" }
                WirelessCallManager.acceptIncomingCall(ctx, myNick)
                NemixTelecomHelper.markActive()
                // Bring up the in-call screen (over the lock screen if locked).
                runCatching {
                    ctx.startActivity(
                        Intent(ctx, MainActivity::class.java)
                            .putExtra(IncomingCallNotifier.EXTRA_SHOW_INCOMING_CALL, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                }
            }
            IncomingCallNotifier.ACTION_DECLINE -> {
                WirelessCallManager.endCall(ctx)
                NemixTelecomHelper.markDisconnected()
            }
        }
    }
}
