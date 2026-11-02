package com.bitchat.android.call

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import com.bitchat.android.services.AppStateStore

/**
 * OS-standard Android Telecom `ConnectionService` + self-managed `PhoneAccount` integration.
 * Allows Android OS to recognize nemix VoIP calls natively (Bluetooth headset answer/hangup,
 * cellular call coexistence, and audio priority) while falling back gracefully on restricted OEM ROMs.
 */
class NemixConnectionService : ConnectionService() {

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val peerName = request?.extras?.getString(NemixTelecomHelper.EXTRA_PEER_NAME) ?: "nemix"
        val conn = NemixCallConnection(applicationContext, peerName, isIncoming = false)
        conn.setDialing()
        NemixTelecomHelper.activeConnection = conn
        return conn
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val peerName = request?.extras?.getString(NemixTelecomHelper.EXTRA_PEER_NAME) ?: "nemix"
        val conn = NemixCallConnection(applicationContext, peerName, isIncoming = true)
        conn.setRinging()
        NemixTelecomHelper.activeConnection = conn
        return conn
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.w("NemixConnectionService", "OS Telecom outgoing connection unavailable; continuing with OS AudioManager")
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.w("NemixConnectionService", "OS Telecom incoming connection unavailable; continuing with OS AudioManager")
    }
}

class NemixCallConnection(
    private val appContext: Context,
    private val peerDisplayName: String,
    isIncoming: Boolean
) : Connection() {

    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        audioModeIsVoip = true
        setCallerDisplayName(peerDisplayName, TelecomManager.PRESENTATION_ALLOWED)
        setAddress(
            Uri.fromParts("sip", peerDisplayName.ifBlank { "peer" }, null),
            TelecomManager.PRESENTATION_ALLOWED
        )
        if (isIncoming) {
            setInitializing()
        }
    }

    override fun onShowIncomingCallUi() {
        // Telecom asks a self-managed app to show its own incoming-call UI: a call-style
        // notification with a full-screen intent (shows on the lock screen, turns the screen on).
        IncomingCallNotifier.show(appContext, peerDisplayName)
    }

    override fun onAnswer() {
        val myNick = AppStateStore.nickname.value.ifBlank { "User" }
        WirelessCallManager.acceptIncomingCall(appContext, myNick)
        setActive()
    }

    override fun onReject() {
        WirelessCallManager.endCall(appContext)
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
    }

    override fun onDisconnect() {
        WirelessCallManager.endCall(appContext)
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
    }

    override fun onAbort() {
        WirelessCallManager.endCall(appContext)
        setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
        destroy()
    }
}

object NemixTelecomHelper {
    private const val TAG = "NemixTelecom"
    private const val ACCOUNT_ID = "nemix_p2p_voip"
    const val EXTRA_PEER_NAME = "nemix_peer_name"

    @Volatile
    var activeConnection: NemixCallConnection? = null

    private fun handle(context: Context): PhoneAccountHandle =
        PhoneAccountHandle(
            ComponentName(context.applicationContext, NemixConnectionService::class.java),
            ACCOUNT_ID
        )

    fun ensurePhoneAccountRegistered(context: Context): PhoneAccountHandle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return runCatching {
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return null
            val h = handle(context)
            val account = PhoneAccount.builder(h, "nemix")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .addSupportedUriScheme("sip")
                .build()
            tm.registerPhoneAccount(account)
            h
        }.onFailure { e ->
            Log.d(TAG, "Self-managed PhoneAccount registration skipped: ${e.message}")
        }.getOrNull()
    }

    fun reportOutgoingCall(context: Context, peerName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val h = ensurePhoneAccountRegistered(context) ?: return
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, h)
                putString(EXTRA_PEER_NAME, peerName)
            }
            val uri = Uri.fromParts("sip", peerName.ifBlank { "peer" }, null)
            tm.placeCall(uri, extras)
        }.onFailure { e ->
            Log.d(TAG, "Telecom placeCall fallback: ${e.message}")
        }
    }

    fun reportIncomingCall(context: Context, peerName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val h = ensurePhoneAccountRegistered(context) ?: return
            val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
            val extras = Bundle().apply {
                putString(EXTRA_PEER_NAME, peerName)
            }
            tm.addNewIncomingCall(h, extras)
        }.onFailure { e ->
            Log.d(TAG, "Telecom addNewIncomingCall fallback: ${e.message}")
        }
    }

    fun markActive() {
        runCatching { activeConnection?.setActive() }
    }

    fun markDisconnected(remote: Boolean = false) {
        runCatching {
            val conn = activeConnection ?: return
            activeConnection = null
            conn.setDisconnected(
                DisconnectCause(if (remote) DisconnectCause.REMOTE else DisconnectCause.LOCAL)
            )
            conn.destroy()
        }
    }
}

