package com.bitchat.android.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.net.InternetRelayPreferences
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrFilter
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.MessageRouter
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class CallTransportMode(
    val wireCode: String,
    val displayLabel: String,
    val shortLabel: String
) {
    LOCAL_WIRELESS("LOCAL", "近距離無線 (ローカルP2P)", "近距離無線"),
    INTERNET_RELAY("NET", "ネット経由 (E2EE暗号化)", "ネット経由");

    companion object {
        fun fromWire(code: String?): CallTransportMode =
            if (code == "NET") INTERNET_RELAY else LOCAL_WIRELESS
    }
}

enum class WirelessCallPhase {
    IDLE,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    ACTIVE
}

data class WirelessCallUiState(
    val phase: WirelessCallPhase = WirelessCallPhase.IDLE,
    val callId: String = "",
    val peerID: String = "",
    val peerName: String = "",
    val transportMode: CallTransportMode = CallTransportMode.LOCAL_WIRELESS,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val durationSeconds: Int = 0
)

/**
 * OS-Standard Real-Time End-to-End Encrypted Wireless Voice Call Manager:
 * - OS Telecom stack: `NemixConnectionService` / `TelecomManager` self-managed `PhoneAccount`
 * - OS Audio stack: `AudioFocusRequest`, `AudioManager.MODE_IN_COMMUNICATION`, `setCommunicationDevice`,
 *   OS DSP (`AcousticEchoCanceler`, `NoiseSuppressor`, `AutomaticGainControl`), low-latency `AudioTrack`
 * - 3-Tier OS Transport Engine:
 *   1. Direct OS `DatagramSocket` UDP over Wi-Fi / LAN / Hotspot / Wi-Fi Aware IP candidates (80ms frames)
 *   2. Lightweight ephemeral Nostr events (`kind = 25050`) over Internet Relays (never stored on relays)
 *   3. OS Hardware `MediaCodec` AMR-NB (`audio/3gpp`, 4.75 kbps) over binary BLE `VOICE_FRAME`
 */
object WirelessCallManager {
    private const val TAG = "WirelessCallManager"
    const val PREFIX_SIGNAL = "[NEMIX_CALL:"
    const val PREFIX_AUDIO = "[NEMIX_CALL_AUDIO:"

    private const val SAMPLE_RATE = 8_000
    private const val AMR_FRAME_SAMPLES = 160 // 20ms @ 8kHz (OS AMR-NB standard frame)
    private const val UDP_SAMPLES_PER_PACKET = 640 // 80ms @ 8kHz for crisp low-latency UDP
    private const val RELAY_SAMPLES_PER_PACKET = 1_600 // 200ms @ 8kHz for Internet Relay
    private const val BLE_AMR_FRAMES_PER_PACKET = 8 // 160ms @ 8kHz (~104 bytes AMR-NB!)
    private const val RING_TIMEOUT_MS = 35_000L
    private const val EPHEMERAL_CALL_KIND = 25050

    private const val MAGIC_B0: Byte = 0x4E // 'N'
    private const val MAGIC_B1: Byte = 0x43 // 'C'
    private const val CODEC_ADPCM: Byte = 0x01
    private const val CODEC_AMR_NB: Byte = 0x02
    private const val CODEC_PING: Byte = 0x7F

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val secureRandom = SecureRandom()

    private val _uiState = MutableStateFlow(WirelessCallUiState())
    val uiState: StateFlow<WirelessCallUiState> = _uiState.asStateFlow()

    @Volatile private var sessionAesKey: ByteArray? = null
    @Volatile private var callStreamToken: String = ""
    @Volatile private var activeRelaySubId: String? = null

    // Direct OS UDP DatagramSocket state
    @Volatile private var udpSocket: DatagramSocket? = null
    @Volatile private var remoteUdpCandidates: List<InetSocketAddress> = emptyList()
    @Volatile private var verifiedRemoteUdpEndpoint: InetSocketAddress? = null
    private val lastUdpReceivedAtMs = AtomicLong(0L)
    private var udpReceiveThread: Thread? = null

    // OS Audio & Codec state
    private val streamingActive = AtomicBoolean(false)
    private var captureThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile private var amrDecoder: OsAmrNbDecoder? = null
    private val decoderLock = Any()

    private var ringTimeoutJob: Job? = null
    private var timerJob: Job? = null
    private var activeRingtone: Ringtone? = null
    @Volatile private var appContext: Context? = null

    private const val OFFER_MAX_AGE_MS = 60_000L
    private const val SEEN_OFFERS_PREFS = "nemix_call_seen_offers"
    private const val SEEN_OFFERS_KEY = "ids"
    private const val SEEN_OFFERS_MAX = 300

    /**
     * True if this OFFER must be ignored: it is older than [OFFER_MAX_AGE_MS] (or from far in
     * the future), or its call id was already handled. Seen ids are persisted so replays after
     * an app restart are ignored too (older clients do not send "ts").
     */
    @Synchronized
    private fun isStaleOrSeenOffer(context: Context, callId: String, sentAtMs: Long?): Boolean {
        val now = System.currentTimeMillis()
        if (sentAtMs != null && (now - sentAtMs > OFFER_MAX_AGE_MS || sentAtMs - now > OFFER_MAX_AGE_MS)) {
            return true
        }
        val prefs = context.getSharedPreferences(SEEN_OFFERS_PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getString(SEEN_OFFERS_KEY, "").orEmpty().split(",").filter { it.isNotBlank() }
        if (callId in seen) return true
        val updated = (seen + callId).takeLast(SEEN_OFFERS_MAX)
        prefs.edit().putString(SEEN_OFFERS_KEY, updated.joinToString(",")).apply()
        return false
    }

    fun hasAudioPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun isCallPacket(content: String): Boolean {
        val t = content.trim()
        return (t.startsWith(PREFIX_SIGNAL) || t.startsWith(PREFIX_AUDIO)) && t.endsWith("]")
    }

    /**
     * Initiates an outgoing wireless call using OS Telecom + user-selected transport mode.
     */
    fun startOutgoingCall(
        context: Context,
        peerID: String,
        peerName: String,
        myNickname: String,
        mode: CallTransportMode
    ): Result<Unit> {
        val ctx = context.applicationContext
        appContext = ctx
        if (!hasAudioPermission(ctx)) {
            return Result.failure(SecurityException("マイク権限が必要です"))
        }
        if (_uiState.value.phase != WirelessCallPhase.IDLE) {
            return Result.failure(IllegalStateException("既に通話中です"))
        }

        val mesh = MeshServiceHolder.getUnifiedOrCreate(ctx)
        val router = MessageRouter.getInstance(ctx, mesh)

        if (mode == CallTransportMode.LOCAL_WIRELESS && !router.hasMeshRoute(peerID)) {
            return Result.failure(IllegalStateException("相手が近距離無線の圏内にいないか、暗号化セッション準備中です"))
        }
        if (mode == CallTransportMode.INTERNET_RELAY && !router.hasInternetRoute(peerID)) {
            return Result.failure(IllegalStateException("相手のインターネット公開鍵が未登録です（QRコードまたはUUID追加で交換されます）"))
        }

        val callId = UUID.randomUUID().toString().replace("-", "").take(14)
        val keyBytes = ByteArray(32).also(secureRandom::nextBytes)
        sessionAesKey = keyBytes
        callStreamToken = deriveCallStreamToken(callId, keyBytes)
        val localUdpPort = openUdpSocket()
        val localCandidates = collectLocalUdpCandidates(localUdpPort)

        val canonicalPeer = ContactDirectory.canonicalConversationId(peerID)
        _uiState.value = WirelessCallUiState(
            phase = WirelessCallPhase.OUTGOING_RINGING,
            callId = callId,
            peerID = canonicalPeer,
            peerName = peerName,
            transportMode = mode,
            isMuted = false,
            isSpeakerOn = false,
            durationSeconds = 0
        )

        NemixTelecomHelper.reportOutgoingCall(ctx, peerName)
        if (mode == CallTransportMode.INTERNET_RELAY) {
            subscribeEphemeralRelayAudio(ctx)
        }

        val offer = JsonObject().apply {
            addProperty("cmd", "OFFER")
            addProperty("id", callId)
            // Send time, so receivers can ignore offers replayed later by Nostr relays.
            addProperty("ts", System.currentTimeMillis())
            addProperty("mode", mode.wireCode)
            addProperty("key", Base64.encodeToString(keyBytes, Base64.NO_WRAP))
            addProperty("name", myNickname)
            if (localCandidates.isNotEmpty()) {
                val arr = JsonArray()
                localCandidates.forEach { arr.add(it) }
                add("udp", arr)
            }
        }
        sendControlPacket(ctx, canonicalPeer, peerName, mode, "$PREFIX_SIGNAL$offer]")

        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            if (_uiState.value.phase == WirelessCallPhase.OUTGOING_RINGING &&
                _uiState.value.callId == callId
            ) {
                endCall(ctx, postSummary = true, customSummary = "応答なし (${mode.shortLabel})")
            }
        }
        return Result.success(Unit)
    }

    fun acceptIncomingCall(context: Context, myNickname: String) {
        val ctx = context.applicationContext
        appContext = ctx
        val current = _uiState.value
        if (current.phase != WirelessCallPhase.INCOMING_RINGING) return

        stopRingingAlert(ctx)
        ringTimeoutJob?.cancel()

        val localUdpPort = openUdpSocket()
        val localCandidates = collectLocalUdpCandidates(localUdpPort)
        if (current.transportMode == CallTransportMode.INTERNET_RELAY) {
            subscribeEphemeralRelayAudio(ctx)
        }

        val accept = JsonObject().apply {
            addProperty("cmd", "ACCEPT")
            addProperty("id", current.callId)
            addProperty("name", myNickname)
            if (localCandidates.isNotEmpty()) {
                val arr = JsonArray()
                localCandidates.forEach { arr.add(it) }
                add("udp", arr)
            }
        }
        sendControlPacket(ctx, current.peerID, current.peerName, current.transportMode, "$PREFIX_SIGNAL$accept]")

        _uiState.value = current.copy(phase = WirelessCallPhase.ACTIVE, durationSeconds = 0)
        NemixTelecomHelper.markActive()
        startAudioPipeline(ctx)
        startDurationTimer()
    }

    fun endCall(
        context: Context,
        notifyRemote: Boolean = true,
        postSummary: Boolean = true,
        customSummary: String? = null
    ) {
        val ctx = context.applicationContext
        val current = _uiState.value
        if (current.phase == WirelessCallPhase.IDLE) return

        stopRingingAlert(ctx)
        ringTimeoutJob?.cancel()
        timerJob?.cancel()
        unsubscribeEphemeralRelayAudio(ctx)
        stopAudioPipeline(ctx)
        closeUdpSocket()
        NemixTelecomHelper.markDisconnected(remote = !notifyRemote)

        if (notifyRemote && current.peerID.isNotBlank()) {
            val cmd = if (current.phase == WirelessCallPhase.INCOMING_RINGING) "REJECT" else "END"
            val pkt = JsonObject().apply {
                addProperty("cmd", cmd)
                addProperty("id", current.callId)
            }
            sendControlPacket(ctx, current.peerID, current.peerName, current.transportMode, "$PREFIX_SIGNAL$pkt]")
        }

        if (postSummary && current.peerID.isNotBlank()) {
            val shortMode = current.transportMode.shortLabel
            val summaryText = customSummary ?: when (current.phase) {
                WirelessCallPhase.ACTIVE -> {
                    val mins = current.durationSeconds / 60
                    val secs = current.durationSeconds % 60
                    String.format("音声通話 (%s) · %02d:%02d", shortMode, mins, secs)
                }
                WirelessCallPhase.INCOMING_RINGING -> "着信を拒否 ($shortMode)"
                WirelessCallPhase.OUTGOING_RINGING -> "発信キャンセル ($shortMode)"
                WirelessCallPhase.IDLE -> ""
            }
            if (summaryText.isNotBlank()) {
                postCallHistoryNotice(ctx, current.peerID, summaryText)
            }
        }

        sessionAesKey = null
        callStreamToken = ""
        remoteUdpCandidates = emptyList()
        verifiedRemoteUdpEndpoint = null
        lastUdpReceivedAtMs.set(0L)
        _uiState.value = WirelessCallUiState()
    }

    fun toggleMute() {
        val cur = _uiState.value
        if (cur.phase == WirelessCallPhase.IDLE) return
        _uiState.value = cur.copy(isMuted = !cur.isMuted)
    }

    fun toggleSpeaker(context: Context) {
        val cur = _uiState.value
        if (cur.phase == WirelessCallPhase.IDLE) return
        val next = !cur.isSpeakerOn
        _uiState.value = cur.copy(isSpeakerOn = next)
        applyAudioRouting(context, next)
    }

    /**
     * Handles binary `NoisePayloadType.VOICE_FRAME` packets received directly over BLE / Wi-Fi Aware.
     */
    fun handleIncomingBinaryVoiceFrame(payload: ByteArray): Boolean {
        if (payload.size < 18 || payload[0] != MAGIC_B0 || payload[1] != MAGIC_B1) return false
        if (!streamingActive.get() || _uiState.value.phase != WirelessCallPhase.ACTIVE) return true
        decryptAndPlayBinaryPacket(payload, fromUdpEndpoint = null)
        return true
    }

    /**
     * Handles incoming `[NEMIX_CALL:...]` and legacy `[NEMIX_CALL_AUDIO:...]` packets.
     */
    fun handleIncomingCallPacket(
        context: Context,
        senderPeerID: String,
        senderNickname: String,
        content: String,
        originIsInternet: Boolean
    ): Boolean {
        val trimmed = content.trim()
        if (!isCallPacket(trimmed)) return false
        val ctx = context.applicationContext
        appContext = ctx

        try {
            if (trimmed.startsWith(PREFIX_AUDIO)) {
                handleLegacyTextAudioFrame(trimmed)
                return true
            }

            val rawJson = trimmed.removePrefix(PREFIX_SIGNAL).removeSuffix("]")
            val obj = JsonParser.parseString(rawJson).asJsonObject
            val cmd = obj.get("cmd")?.asString ?: return true
            val callId = obj.get("id")?.asString ?: return true
            val canonicalPeer = ContactDirectory.canonicalConversationId(senderPeerID)

            when (cmd) {
                "OFFER" -> {
                    // Nostr relays re-deliver stored events after every reconnect: an old OFFER
                    // must never ring again ("ghost" incoming calls).
                    if (isStaleOrSeenOffer(ctx, callId, obj.get("ts")?.asLong)) return true
                    if (_uiState.value.phase != WirelessCallPhase.IDLE) {
                        val mode = CallTransportMode.fromWire(obj.get("mode")?.asString)
                        val reject = JsonObject().apply {
                            addProperty("cmd", "REJECT")
                            addProperty("id", callId)
                        }
                        sendControlPacket(ctx, canonicalPeer, senderNickname, mode, "$PREFIX_SIGNAL$reject]")
                        return true
                    }
                    val keyB64 = obj.get("key")?.asString ?: return true
                    val keyBytes = Base64.decode(keyB64, Base64.DEFAULT)
                    if (keyBytes.size != 32) return true
                    sessionAesKey = keyBytes
                    callStreamToken = deriveCallStreamToken(callId, keyBytes)
                    remoteUdpCandidates = parseUdpCandidates(obj)
                    verifiedRemoteUdpEndpoint = null
                    lastUdpReceivedAtMs.set(0L)

                    val callerName = obj.get("name")?.asString?.takeIf { it.isNotBlank() } ?: senderNickname
                    val mode = if (originIsInternet) {
                        CallTransportMode.INTERNET_RELAY
                    } else {
                        CallTransportMode.fromWire(obj.get("mode")?.asString)
                    }

                    _uiState.value = WirelessCallUiState(
                        phase = WirelessCallPhase.INCOMING_RINGING,
                        callId = callId,
                        peerID = canonicalPeer,
                        peerName = callerName,
                        transportMode = mode
                    )
                    NemixTelecomHelper.reportIncomingCall(ctx, callerName)
                    // Also post directly: some devices never call onShowIncomingCallUi.
                    IncomingCallNotifier.show(ctx, callerName)
                    startRingingAlert(ctx)

                    ringTimeoutJob?.cancel()
                    ringTimeoutJob = scope.launch {
                        delay(RING_TIMEOUT_MS)
                        if (_uiState.value.phase == WirelessCallPhase.INCOMING_RINGING &&
                            _uiState.value.callId == callId
                        ) {
                            endCall(
                                ctx,
                                notifyRemote = false,
                                postSummary = true,
                                customSummary = "不在着信 (${mode.shortLabel})"
                            )
                        }
                    }
                }

                "ACCEPT" -> {
                    val cur = _uiState.value
                    if (cur.phase == WirelessCallPhase.OUTGOING_RINGING && cur.callId == callId) {
                        ringTimeoutJob?.cancel()
                        remoteUdpCandidates = parseUdpCandidates(obj)
                        _uiState.value = cur.copy(phase = WirelessCallPhase.ACTIVE, durationSeconds = 0)
                        NemixTelecomHelper.markActive()
                        startAudioPipeline(ctx)
                        startDurationTimer()
                    }
                }

                "REJECT" -> {
                    val cur = _uiState.value
                    if (cur.callId == callId && cur.phase != WirelessCallPhase.IDLE) {
                        endCall(
                            ctx,
                            notifyRemote = false,
                            postSummary = true,
                            customSummary = "相手が応答できませんでした (${cur.transportMode.shortLabel})"
                        )
                    }
                }

                "END" -> {
                    val cur = _uiState.value
                    if (cur.callId == callId && cur.phase != WirelessCallPhase.IDLE) {
                        val wasMissed = cur.phase == WirelessCallPhase.INCOMING_RINGING
                        endCall(
                            ctx,
                            notifyRemote = false,
                            postSummary = true,
                            customSummary = if (wasMissed) "不在着信 (${cur.transportMode.shortLabel})" else null
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed handling call packet: ${e.message}")
        }
        return true
    }

    private fun deriveCallStreamToken(callId: String, keyBytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("nemix-call-v2:$callId:".toByteArray(Charsets.UTF_8))
        md.update(keyBytes)
        return md.digest().take(16).joinToString("") { "%02x".format(it) }
    }

    private fun openUdpSocket(): Int {
        closeUdpSocket()
        return try {
            val sock = DatagramSocket(0).apply {
                reuseAddress = true
                trafficClass = 0xB8 // Expedited Forwarding (VoIP DSCP EF)
            }
            udpSocket = sock
            startUdpReceiveLoop(sock)
            sock.localPort
        } catch (e: Exception) {
            Log.w(TAG, "UDP socket bind skipped: ${e.message}")
            0
        }
    }

    private fun startUdpReceiveLoop(sock: DatagramSocket) {
        udpReceiveThread = Thread({
            val buf = ByteArray(2048)
            while (!sock.isClosed) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    if (pkt.length >= 18 && buf[0] == MAGIC_B0 && buf[1] == MAGIC_B1) {
                        val copy = buf.copyOfRange(0, pkt.length)
                        val senderAddr = InetSocketAddress(pkt.address, pkt.port)
                        decryptAndPlayBinaryPacket(copy, fromUdpEndpoint = senderAddr)
                    }
                } catch (_: Exception) {
                    if (sock.isClosed) break
                }
            }
        }, "nemix-call-udp-rx").apply {
            isDaemon = true
            start()
        }
    }

    private fun closeUdpSocket() {
        runCatching { udpSocket?.close() }
        udpSocket = null
        udpReceiveThread = null
    }

    private fun collectLocalUdpCandidates(port: Int): List<String> {
        if (port <= 0) return emptyList()
        val result = linkedSetOf<String>()
        runCatching {
            // Also include any active Wi-Fi Aware peer interface addresses
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (ifaces.hasMoreElements()) {
                val nif = ifaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr.isLoopbackAddress) continue
                    if (addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (!ip.startsWith("127.")) {
                            result.add("$ip:$port")
                        }
                    }
                }
            }
        }
        return result.take(6)
    }

    private fun parseUdpCandidates(obj: JsonObject): List<InetSocketAddress> {
        val arr = obj.getAsJsonArray("udp") ?: return emptyList()
        val out = mutableListOf<InetSocketAddress>()
        for (el in arr) {
            val raw = runCatching { el.asString }.getOrNull() ?: continue
            val idx = raw.lastIndexOf(':')
            if (idx <= 0) continue
            val host = raw.substring(0, idx)
            val port = raw.substring(idx + 1).toIntOrNull() ?: continue
            if (port in 1..65535) {
                runCatching { out.add(InetSocketAddress(host, port)) }
            }
        }
        return out
    }

    private fun subscribeEphemeralRelayAudio(context: Context) {
        val token = callStreamToken.takeIf { it.isNotBlank() } ?: return
        unsubscribeEphemeralRelayAudio(context)
        runCatching {
            val relayMgr = NostrRelayManager.getInstance(context)
            if (InternetRelayPreferences.isInternetEnabled()) {
                relayMgr.connect()
            }
            val subId = "call-audio-${token.take(10)}"
            activeRelaySubId = subId
            val filter = NostrFilter.Builder()
                .kinds(EPHEMERAL_CALL_KIND)
                .tag("d", token)
                .since((System.currentTimeMillis() / 1000L) - 10L)
                .build()
            val myPub = NostrIdentityBridge.getCurrentNostrIdentity(context)?.publicKeyHex?.lowercase()
            relayMgr.subscribe(
                filter = filter,
                id = subId,
                observeOnly = true,
                handler = { event ->
                    if (event.kind != EPHEMERAL_CALL_KIND) return@subscribe
                    if (myPub != null && event.pubkey.equals(myPub, ignoreCase = true)) return@subscribe
                    val raw = runCatching { Base64.decode(event.content, Base64.DEFAULT) }.getOrNull() ?: return@subscribe
                    if (raw.size >= 18 && raw[0] == MAGIC_B0 && raw[1] == MAGIC_B1) {
                        decryptAndPlayBinaryPacket(raw, fromUdpEndpoint = null)
                    }
                }
            )
        }
    }

    private fun unsubscribeEphemeralRelayAudio(context: Context) {
        val sub = activeRelaySubId ?: return
        activeRelaySubId = null
        runCatching {
            NostrRelayManager.getInstance(context).unsubscribe(sub)
        }
    }

    private fun sendControlPacket(
        context: Context,
        peerID: String,
        peerName: String,
        mode: CallTransportMode,
        payload: String
    ) {
        scope.launch {
            runCatching {
                val mesh = MeshServiceHolder.getUnifiedOrCreate(context)
                val router = MessageRouter.getInstance(context, mesh)
                router.sendPrivateExplicit(
                    content = payload,
                    toPeerID = peerID,
                    recipientNickname = peerName,
                    messageID = UUID.randomUUID().toString().uppercase(),
                    preferInternet = (mode == CallTransportMode.INTERNET_RELAY)
                )
            }
        }
    }

    private fun startDurationTimer() {
        timerJob?.cancel()
        timerJob = scope.launch {
            while (isActive && _uiState.value.phase == WirelessCallPhase.ACTIVE) {
                delay(1_000L)
                val cur = _uiState.value
                if (cur.phase == WirelessCallPhase.ACTIVE) {
                    _uiState.value = cur.copy(durationSeconds = cur.durationSeconds + 1)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioPipeline(context: Context) {
        if (streamingActive.getAndSet(true)) return
        val key = sessionAesKey ?: run {
            streamingActive.set(false)
            return
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        requestOsAudioFocus(audioManager)
        runCatching {
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            applyAudioRouting(context, _uiState.value.isSpeakerOn)
        }

        synchronized(decoderLock) {
            runCatching { amrDecoder?.close() }
            amrDecoder = runCatching { OsAmrNbDecoder() }.getOrNull()
        }

        // Start OS Low-Latency Speaker/Earpiece Playback AudioTrack
        runCatching {
            val minOutBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3_200)

            val trackBuilder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minOutBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            val track = trackBuilder.build()
            track.play()
            audioTrack = track
        }.onFailure {
            Log.e(TAG, "AudioTrack init failed: ${it.message}")
        }

        if (!hasAudioPermission(context)) return

        // Start OS AudioRecord + Hardware DSP + Adaptive Transport Encoder Loop
        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var record: AudioRecord? = null
            var aec: AcousticEchoCanceler? = null
            var ns: NoiseSuppressor? = null
            var agc: AutomaticGainControl? = null
            var amrEncoder: OsAmrNbEncoder? = null
            try {
                val minInBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(3_200)

                record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minInBuf * 2
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    return@Thread
                }
                val sessionId = record.audioSessionId
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }.getOrNull()
                }
                if (NoiseSuppressor.isAvailable()) {
                    ns = runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }.getOrNull()
                }
                if (AutomaticGainControl.isAvailable()) {
                    agc = runCatching { AutomaticGainControl.create(sessionId)?.apply { enabled = true } }.getOrNull()
                }

                amrEncoder = runCatching { OsAmrNbEncoder() }.getOrNull()

                record.startRecording()
                val mesh = MeshServiceHolder.getUnifiedOrCreate(context)
                val nostrId = runCatching { NostrIdentityBridge.getCurrentNostrIdentity(context) }.getOrNull()
                val relayMgr = NostrRelayManager.getInstance(context)

                val frame20ms = ShortArray(AMR_FRAME_SAMPLES)
                val pcmAccumulator = ShortArray(RELAY_SAMPLES_PER_PACKET)
                var pcmAccCount = 0
                val amrBatch = ByteArrayOutputStream(256)
                var amrFramesInBatch = 0
                var seq = 0
                var lastUdpPingMs = 0L

                while (streamingActive.get() && _uiState.value.phase == WirelessCallPhase.ACTIVE) {
                    val now = System.currentTimeMillis()
                    // Periodically probe remote UDP candidates so direct LAN/Wi-Fi P2P upgrades automatically
                    if (remoteUdpCandidates.isNotEmpty() && now - lastUdpPingMs >= 1_200L) {
                        lastUdpPingMs = now
                        sendUdpProbe(key, seq)
                    }

                    var readOffset = 0
                    while (readOffset < AMR_FRAME_SAMPLES && streamingActive.get()) {
                        val r = record.read(frame20ms, readOffset, AMR_FRAME_SAMPLES - readOffset)
                        if (r <= 0) break
                        readOffset += r
                    }
                    if (readOffset < AMR_FRAME_SAMPLES || !streamingActive.get()) continue
                    if (_uiState.value.isMuted) {
                        pcmAccCount = 0
                        amrBatch.reset()
                        amrFramesInBatch = 0
                        continue
                    }

                    val udpLive = verifiedRemoteUdpEndpoint != null && (now - lastUdpReceivedAtMs.get() < 4_500L)
                    val cur = _uiState.value

                    if (udpLive) {
                        // Tier 1: Direct OS DatagramSocket UDP (80ms crisp ADPCM)
                        System.arraycopy(frame20ms, 0, pcmAccumulator, pcmAccCount, AMR_FRAME_SAMPLES)
                        pcmAccCount += AMR_FRAME_SAMPLES
                        if (pcmAccCount >= UDP_SAMPLES_PER_PACKET) {
                            val slice = pcmAccumulator.copyOfRange(0, UDP_SAMPLES_PER_PACKET)
                            pcmAccCount = 0
                            val encoded = ImaAdpcmCodec.encode(slice)
                            val pkt = encryptBinaryVoicePacket(key, CODEC_ADPCM, seq++, encoded)
                            sendViaUdp(pkt)
                        }
                    } else if (cur.transportMode == CallTransportMode.INTERNET_RELAY) {
                        // Tier 2: Lightweight Ephemeral Nostr Event (kind 25050) over Internet Relays
                        if (amrEncoder != null) {
                            val amrBytes = amrEncoder.encode20ms(frame20ms)
                            if (amrBytes.isNotEmpty()) {
                                amrBatch.write(amrBytes)
                                amrFramesInBatch++
                            }
                            if (amrFramesInBatch >= 10) { // 200ms (~130 bytes AMR-NB!)
                                val payload = amrBatch.toByteArray()
                                amrBatch.reset()
                                amrFramesInBatch = 0
                                val pkt = encryptBinaryVoicePacket(key, CODEC_AMR_NB, seq++, payload)
                                sendViaEphemeralRelay(relayMgr, nostrId, pkt)
                            }
                        } else {
                            System.arraycopy(frame20ms, 0, pcmAccumulator, pcmAccCount, AMR_FRAME_SAMPLES)
                            pcmAccCount += AMR_FRAME_SAMPLES
                            if (pcmAccCount >= RELAY_SAMPLES_PER_PACKET) {
                                val slice = pcmAccumulator.copyOfRange(0, RELAY_SAMPLES_PER_PACKET)
                                pcmAccCount = 0
                                val encoded = ImaAdpcmCodec.encode(slice)
                                val pkt = encryptBinaryVoicePacket(key, CODEC_ADPCM, seq++, encoded)
                                sendViaEphemeralRelay(relayMgr, nostrId, pkt)
                            }
                        }
                    } else {
                        // Tier 3: Pure Offline BLE / Wi-Fi Aware Binary VOICE_FRAME + OS MediaCodec AMR-NB
                        val resolvedMeshPeer = resolveLiveMeshPeerId(cur.peerID)
                        if (amrEncoder != null) {
                            val amrBytes = amrEncoder.encode20ms(frame20ms)
                            if (amrBytes.isNotEmpty()) {
                                amrBatch.write(amrBytes)
                                amrFramesInBatch++
                            }
                            if (amrFramesInBatch >= BLE_AMR_FRAMES_PER_PACKET) { // 160ms (~104 bytes!)
                                val payload = amrBatch.toByteArray()
                                amrBatch.reset()
                                amrFramesInBatch = 0
                                val pkt = encryptBinaryVoicePacket(key, CODEC_AMR_NB, seq++, payload)
                                if (resolvedMeshPeer != null) {
                                    mesh.sendVoiceFrame(resolvedMeshPeer, pkt)
                                }
                            }
                        } else {
                            System.arraycopy(frame20ms, 0, pcmAccumulator, pcmAccCount, AMR_FRAME_SAMPLES)
                            pcmAccCount += AMR_FRAME_SAMPLES
                            if (pcmAccCount >= 1_280) {
                                val slice = pcmAccumulator.copyOfRange(0, 1_280)
                                pcmAccCount = 0
                                val encoded = ImaAdpcmCodec.encode(slice)
                                val pkt = encryptBinaryVoicePacket(key, CODEC_ADPCM, seq++, encoded)
                                if (resolvedMeshPeer != null) {
                                    mesh.sendVoiceFrame(resolvedMeshPeer, pkt)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Voice capture loop stopped: ${e.message}")
            } finally {
                runCatching { amrEncoder?.close() }
                runCatching { agc?.release() }
                runCatching { aec?.release() }
                runCatching { ns?.release() }
                runCatching { record?.stop() }
                runCatching { record?.release() }
            }
        }, "nemix-call-capture").also { it.start() }
    }

    private fun resolveLiveMeshPeerId(peerOrConvID: String): String? {
        if (ContactIdentityResolver.isMeshPeerId(peerOrConvID)) return peerOrConvID
        return runCatching { ContactDirectory.resolve(peerOrConvID).meshPeerID }.getOrNull()
    }

    private fun encryptBinaryVoicePacket(
        key: ByteArray,
        codec: Byte,
        seq: Int,
        plainAudio: ByteArray
    ): ByteArray {
        val iv = ByteArray(12).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val aad = byteArrayOf(MAGIC_B0, MAGIC_B1, codec, (seq shr 8).toByte(), seq.toByte())
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plainAudio)

        val out = ByteArray(5 + 12 + encrypted.size)
        System.arraycopy(aad, 0, out, 0, 5)
        System.arraycopy(iv, 0, out, 5, 12)
        System.arraycopy(encrypted, 0, out, 17, encrypted.size)
        return out
    }

    private fun sendUdpProbe(key: ByteArray, seq: Int) {
        val sock = udpSocket ?: return
        val targets = verifiedRemoteUdpEndpoint?.let { listOf(it) } ?: remoteUdpCandidates
        if (targets.isEmpty()) return
        runCatching {
            val probe = encryptBinaryVoicePacket(key, CODEC_PING, seq, byteArrayOf(0x01))
            for (addr in targets) {
                runCatching {
                    sock.send(DatagramPacket(probe, probe.size, addr))
                }
            }
        }
    }

    private fun sendViaUdp(packetBytes: ByteArray) {
        val sock = udpSocket ?: return
        val target = verifiedRemoteUdpEndpoint ?: return
        runCatching {
            sock.send(DatagramPacket(packetBytes, packetBytes.size, target))
        }
    }

    private fun sendViaEphemeralRelay(
        relayMgr: NostrRelayManager,
        nostrId: com.bitchat.android.nostr.NostrIdentity?,
        packetBytes: ByteArray
    ) {
        val id = nostrId ?: return
        val token = callStreamToken.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val b64 = Base64.encodeToString(packetBytes, Base64.NO_WRAP)
            val unsigned = NostrEvent(
                pubkey = id.publicKeyHex,
                createdAt = (System.currentTimeMillis() / 1000L).toInt(),
                kind = EPHEMERAL_CALL_KIND,
                tags = listOf(listOf("d", token)),
                content = b64
            )
            relayMgr.sendEvent(id.signEvent(unsigned))
        }
    }

    private fun decryptAndPlayBinaryPacket(
        packet: ByteArray,
        fromUdpEndpoint: InetSocketAddress?
    ) {
        val key = sessionAesKey ?: return
        if (packet.size <= 17 + 16) return
        try {
            val codec = packet[2]
            val aad = packet.copyOfRange(0, 5)
            val iv = packet.copyOfRange(5, 17)
            val ciphertext = packet.copyOfRange(17, packet.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(ciphertext)

            if (fromUdpEndpoint != null) {
                val wasFirst = verifiedRemoteUdpEndpoint == null
                verifiedRemoteUdpEndpoint = fromUdpEndpoint
                lastUdpReceivedAtMs.set(System.currentTimeMillis())
                if (wasFirst || codec == CODEC_PING) {
                    // Reply immediately so the peer also locks onto our symmetric NAT/LAN port
                    if (codec == CODEC_PING && plain.firstOrNull() == 0x01.toByte()) {
                        val pong = encryptBinaryVoicePacket(key, CODEC_PING, 0, byteArrayOf(0x02))
                        runCatching { udpSocket?.send(DatagramPacket(pong, pong.size, fromUdpEndpoint)) }
                    }
                }
            }
            if (codec == CODEC_PING) return
            if (!streamingActive.get() || _uiState.value.phase != WirelessCallPhase.ACTIVE) return
            val track = audioTrack ?: return

            when (codec) {
                CODEC_AMR_NB -> {
                    val pcm = synchronized(decoderLock) {
                        amrDecoder?.decodeBatch(plain)
                    }
                    if (pcm != null && pcm.isNotEmpty()) {
                        track.write(pcm, 0, pcm.size)
                    }
                }
                CODEC_ADPCM -> {
                    val sampleCount = (plain.size - 4).coerceAtLeast(0) * 2
                    if (sampleCount > 0) {
                        val pcm = ImaAdpcmCodec.decode(plain, sampleCount)
                        track.write(pcm, 0, pcm.size)
                    }
                }
            }
        } catch (_: Exception) {
            // Drop corrupted/unauthenticated radio packet
        }
    }

    private fun handleLegacyTextAudioFrame(content: String) {
        if (!streamingActive.get() || _uiState.value.phase != WirelessCallPhase.ACTIVE) return
        val key = sessionAesKey ?: return
        val track = audioTrack ?: return

        try {
            val body = content.removePrefix(PREFIX_AUDIO).removeSuffix("]")
            val firstColon = body.indexOf(':')
            val secondColon = body.indexOf(':', firstColon + 1)
            if (firstColon <= 0 || secondColon <= firstColon) return

            val callId = body.substring(0, firstColon)
            if (callId != _uiState.value.callId) return

            val b64 = body.substring(secondColon + 1)
            val wireBytes = Base64.decode(b64, Base64.DEFAULT)
            if (wireBytes.size <= 28) return

            val iv = wireBytes.copyOfRange(0, 12)
            val ciphertext = wireBytes.copyOfRange(12, wireBytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val adpcm = cipher.doFinal(ciphertext)

            val sampleCount = ((adpcm.size - 4).coerceAtLeast(0)) * 2
            val pcm = ImaAdpcmCodec.decode(adpcm, sampleCount.coerceAtLeast(1_280))
            track.write(pcm, 0, pcm.size)
        } catch (_: Exception) {
        }
    }

    private fun stopAudioPipeline(context: Context) {
        streamingActive.set(false)
        captureThread = null
        synchronized(decoderLock) {
            runCatching { amrDecoder?.close() }
            amrDecoder = null
        }
        runCatching {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        }
        audioTrack = null
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            applyAudioRouting(context, false)
            abandonOsAudioFocus(am)
            am?.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun requestOsAudioFocus(am: AudioManager?) {
        if (am == null) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
            }
        }
    }

    private fun abandonOsAudioFocus(am: AudioManager?) {
        if (am == null) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyAudioRouting(context: Context, speakerRequested: Boolean) {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = am.availableCommunicationDevices
                val target = if (speakerRequested) {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                } else {
                    devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                }
                if (target != null) {
                    am.setCommunicationDevice(target)
                } else {
                    am.clearCommunicationDevice()
                }
            } else {
                am.isSpeakerphoneOn = speakerRequested
            }
        }
    }

    private fun startRingingAlert(context: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            activeRingtone = RingtoneManager.getRingtone(context, uri)?.also { it.play() }
        }
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500, 500), -1))
        }
    }

    private fun stopRingingAlert(context: Context) {
        IncomingCallNotifier.cancel(context)
        runCatching { activeRingtone?.stop() }
        activeRingtone = null
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.cancel()
        }
    }

    private fun postCallHistoryNotice(context: Context, conversationID: String, text: String) {
        scope.launch {
            runCatching {
                val mesh = MeshServiceHolder.getUnifiedOrCreate(context)
                val msg = BitchatMessage(
                    id = UUID.randomUUID().toString().uppercase(),
                    sender = AppStateStore.nickname.value.ifBlank { "me" },
                    content = text,
                    timestamp = Date(),
                    isRelay = false,
                    isPrivate = true,
                    senderPeerID = mesh.myPeerID,
                    deliveryStatus = DeliveryStatus.Delivered("call", Date())
                )
                AppStateStore.addPrivateMessageDurably(conversationID, msg)
            }
        }
    }
}

private typealias Process = android.os.Process

/**
 * OS-Standard Hardware `MediaCodec` AMR-NB (`audio/3gpp`) Speech Encoder (8 kHz, 4.75 kbps -> ~13 bytes per 20ms!).
 */
internal class OsAmrNbEncoder : AutoCloseable {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AMR_NB)
    private val bufferInfo = MediaCodec.BufferInfo()
    private var ptsUs = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AMR_NB, 8_000, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 4_750)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun encode20ms(pcm160: ShortArray): ByteArray {
        val inIdx = codec.dequeueInputBuffer(5_000)
        if (inIdx >= 0) {
            val inBuf = codec.getInputBuffer(inIdx)
            if (inBuf != null) {
                inBuf.clear()
                val byteLen = pcm160.size * 2
                val bb = ByteBuffer.allocate(byteLen).order(ByteOrder.LITTLE_ENDIAN)
                bb.asShortBuffer().put(pcm160)
                inBuf.put(bb.array(), 0, byteLen)
                codec.queueInputBuffer(inIdx, 0, byteLen, ptsUs, 0)
                ptsUs += 20_000L
            }
        }
        val out = ByteArrayOutputStream(32)
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (outIdx < 0) break
            val outBuf = codec.getOutputBuffer(outIdx)
            if (outBuf != null && bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                val chunk = ByteArray(bufferInfo.size)
                outBuf.position(bufferInfo.offset)
                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                outBuf.get(chunk)
                out.write(chunk)
            }
            codec.releaseOutputBuffer(outIdx, false)
        }
        return out.toByteArray()
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}

/**
 * OS-Standard Hardware `MediaCodec` AMR-NB (`audio/3gpp`) Speech Decoder -> 8 kHz 16-bit PCM.
 */
internal class OsAmrNbDecoder : AutoCloseable {
    private val codec: MediaCodec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AMR_NB)
    private val bufferInfo = MediaCodec.BufferInfo()
    private var ptsUs = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AMR_NB, 8_000, 1)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    fun decodeBatch(amrPayload: ByteArray): ShortArray {
        var offset = 0
        val pcmBytes = ByteArrayOutputStream(2_560)
        while (offset < amrPayload.size) {
            val toc = amrPayload[offset].toInt() and 0xFF
            val mode = (toc shr 3) and 0x0F
            val frameLen = amrFrameSizeForMode(mode)
            val chunkLen = if (frameLen > 0 && offset + frameLen <= amrPayload.size) {
                frameLen
            } else {
                amrPayload.size - offset
            }

            val inIdx = codec.dequeueInputBuffer(5_000)
            if (inIdx >= 0) {
                val inBuf = codec.getInputBuffer(inIdx)
                if (inBuf != null) {
                    inBuf.clear()
                    inBuf.put(amrPayload, offset, chunkLen)
                    codec.queueInputBuffer(inIdx, 0, chunkLen, ptsUs, 0)
                    ptsUs += 20_000L
                }
            }
            offset += chunkLen
            drainOutput(pcmBytes)
        }
        drainOutput(pcmBytes)
        val raw = pcmBytes.toByteArray()
        if (raw.size < 2) return ShortArray(0)
        val shorts = ShortArray(raw.size / 2)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    private fun drainOutput(pcmBytes: ByteArrayOutputStream) {
        while (true) {
            val outIdx = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (outIdx < 0) break
            val outBuf = codec.getOutputBuffer(outIdx)
            if (outBuf != null && bufferInfo.size > 0) {
                val chunk = ByteArray(bufferInfo.size)
                outBuf.position(bufferInfo.offset)
                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                outBuf.get(chunk)
                pcmBytes.write(chunk)
            }
            codec.releaseOutputBuffer(outIdx, false)
        }
    }

    private fun amrFrameSizeForMode(mode: Int): Int = when (mode) {
        0 -> 13 // 4.75 kbps
        1 -> 14 // 5.15 kbps
        2 -> 16 // 5.90 kbps
        3 -> 18 // 6.70 kbps
        4 -> 20 // 7.40 kbps
        5 -> 21 // 7.95 kbps
        6 -> 27 // 10.2 kbps
        7 -> 32 // 12.2 kbps
        8 -> 6  // SID
        15 -> 1 // NO_DATA
        else -> 0
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}

/**
 * Compact 4-bit IMA ADPCM speech encoder/decoder (4:1 compression, zero codec latency) used for low-latency Wi-Fi UDP.
 */
internal object ImaAdpcmCodec {
    private val indexTable = intArrayOf(
        -1, -1, -1, -1, 2, 4, 6, 8,
        -1, -1, -1, -1, 2, 4, 6, 8
    )
    private val stepTable = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31,
        34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143,
        157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658,
        724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024,
        3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
        15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
    )

    fun encode(pcm: ShortArray): ByteArray {
        var predictor = pcm.firstOrNull()?.toInt() ?: 0
        var stepIndex = 0
        val out = ByteArray(4 + (pcm.size + 1) / 2)
        ByteBuffer.wrap(out, 0, 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(predictor.toShort())
            put(stepIndex.toByte())
            put(0)
        }

        var outPos = 4
        var highNibble = false
        var currentByte = 0

        for (sample in pcm) {
            val step = stepTable[stepIndex]
            var diff = sample.toInt() - predictor
            var nibble = 0
            if (diff < 0) {
                nibble = 8
                diff = -diff
            }
            var vpdiff = step shr 3
            if (diff >= step) {
                nibble = nibble or 4
                diff -= step
                vpdiff += step
            }
            if (diff >= (step shr 1)) {
                nibble = nibble or 2
                diff -= (step shr 1)
                vpdiff += (step shr 1)
            }
            if (diff >= (step shr 2)) {
                nibble = nibble or 1
                vpdiff += (step shr 2)
            }

            if ((nibble and 8) != 0) predictor -= vpdiff else predictor += vpdiff
            predictor = predictor.coerceIn(-32768, 32767)
            stepIndex = (stepIndex + indexTable[nibble]).coerceIn(0, 88)

            if (!highNibble) {
                currentByte = nibble and 0x0F
                highNibble = true
            } else {
                out[outPos++] = (currentByte or ((nibble and 0x0F) shl 4)).toByte()
                highNibble = false
            }
        }
        if (highNibble && outPos < out.size) {
            out[outPos] = currentByte.toByte()
        }
        return out
    }

    fun decode(adpcm: ByteArray, sampleCount: Int): ShortArray {
        if (adpcm.size < 4) return ShortArray(sampleCount)
        val header = ByteBuffer.wrap(adpcm, 0, 4).order(ByteOrder.LITTLE_ENDIAN)
        var predictor = header.short.toInt()
        var stepIndex = (header.get().toInt() and 0xFF).coerceIn(0, 88)

        val pcm = ShortArray(sampleCount)
        var inPos = 4
        var highNibble = false
        var currentByte = 0

        for (i in 0 until sampleCount) {
            val nibble: Int
            if (!highNibble) {
                if (inPos >= adpcm.size) break
                currentByte = adpcm[inPos++].toInt() and 0xFF
                nibble = currentByte and 0x0F
                highNibble = true
            } else {
                nibble = (currentByte shr 4) and 0x0F
                highNibble = false
            }

            val step = stepTable[stepIndex]
            var vpdiff = step shr 3
            if ((nibble and 4) != 0) vpdiff += step
            if ((nibble and 2) != 0) vpdiff += (step shr 1)
            if ((nibble and 1) != 0) vpdiff += (step shr 2)

            if ((nibble and 8) != 0) predictor -= vpdiff else predictor += vpdiff
            predictor = predictor.coerceIn(-32768, 32767)
            stepIndex = (stepIndex + indexTable[nibble]).coerceIn(0, 88)
            pcm[i] = predictor.toShort()
        }
        return pcm
    }
}
