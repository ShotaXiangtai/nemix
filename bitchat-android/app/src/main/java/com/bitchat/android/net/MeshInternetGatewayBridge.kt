package com.bitchat.android.net

import android.app.Application
import android.content.Context
import android.util.Base64
import android.util.Log
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrFilter
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.service.MeshServiceHolder
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentHashMap

/**
 * Serverless P2P Mesh <-> Internet Gateway Bridge.
 *
 * Allows an offline device (no cellular/Wi-Fi Internet) to communicate with distant peers
 * through any nearby `nemix` device that IS connected to the Internet and has bridging enabled.
 *
 * Security / Privacy:
 * - Only NIP-17 / NIP-59 Gift-Wrapped E2EE `NostrEvent` envelopes are relayed.
 * - Intermediate bridging devices have ZERO knowledge of plaintext, sender identity, or keys.
 */
object MeshInternetGatewayBridge {
    private const val TAG = "MeshInternetBridge"
    const val PREFIX_GW_TX = "[NEMIX_GW_TX:"
    const val PREFIX_GW_RX = "[NEMIX_GW_RX:"
    const val PREFIX_GW_SUB = "[NEMIX_GW_SUB:"

    private const val CHUNK_CHARS = 320 // Fits comfortably inside BLE / Wi-Fi Aware mesh packets
    private val gson = Gson()

    private data class ChunkBuffer(
        val total: Int,
        val parts: ConcurrentHashMap<Int, String> = ConcurrentHashMap(),
        val createdAtMs: Long = System.currentTimeMillis()
    )

    private val inboundBuffers = ConcurrentHashMap<String, ChunkBuffer>()
    private val processedEventIds = ConcurrentHashMap<String, Long>()
    private val bridgedPubkeySubscriptions = ConcurrentHashMap<String, String>() // pubkeyHex -> subId

    fun isGatewayPacket(content: String): Boolean =
        content.startsWith(PREFIX_GW_TX) ||
            content.startsWith(PREFIX_GW_RX) ||
            content.startsWith(PREFIX_GW_SUB)

    /**
     * Called by an offline device when it wants to send a Gift-Wrapped E2EE NostrEvent
     * through nearby internet-connected nemix peers.
     */
    fun relayOutboundEventOverMesh(context: Context, event: NostrEvent): Boolean {
        if (!InternetRelayPreferences.isMeshBridgeEnabled()) return false
        return try {
            val mesh = MeshServiceHolder.getUnifiedOrCreate(context.applicationContext)
            val jsonBytes = gson.toJson(event).toByteArray(Charsets.UTF_8)
            val b64 = Base64.encodeToString(jsonBytes, Base64.NO_WRAP)
            sendChunkedGatewayFrames(mesh, PREFIX_GW_TX, event.id.take(16), b64)
            // Also register our recipient pubkey with nearby bridges so replies come back!
            announceMyMailboxToNearbyGateways(context)
            Log.d(TAG, "Relayed E2EE Nostr event ${event.id.take(10)} over local mesh gateway")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to relay outbound event over mesh: ${e.message}")
            false
        }
    }

    /**
     * Broadcasts our Nostr public key hex to nearby gateway peers when we lack direct Internet,
     * allowing nearby online nemix nodes to forward incoming Gift-Wraps back onto the local mesh.
     */
    fun announceMyMailboxToNearbyGateways(context: Context) {
        if (!InternetRelayPreferences.isMeshBridgeEnabled()) return
        if (InternetRelayPreferences.hasActiveNetworkConnection(context)) return
        runCatching {
            val myIdentity = NostrIdentityBridge.getCurrentNostrIdentity(context) ?: return
            val mesh = MeshServiceHolder.getUnifiedOrCreate(context.applicationContext)
            mesh.sendMessage("$PREFIX_GW_SUB${myIdentity.publicKeyHex}]", emptyList(), null)
        }
    }

    private fun sendChunkedGatewayFrames(
        mesh: com.bitchat.android.mesh.MeshService,
        prefix: String,
        transferId: String,
        b64Payload: String
    ) {
        val chunks = b64Payload.chunked(CHUNK_CHARS)
        val total = chunks.size
        chunks.forEachIndexed { idx, part ->
            val obj = JsonObject().apply {
                addProperty("id", transferId)
                addProperty("i", idx)
                addProperty("n", total)
                addProperty("d", part)
            }
            mesh.sendMessage("$prefix$obj]", emptyList(), null)
        }
    }

    /**
     * Handles incoming `[NEMIX_GW_*]` frames received from the local mesh.
     * Always returns true if consumed so it never appears in chat timelines.
     */
    fun handleIncomingGatewayFrame(context: Context, content: String): Boolean {
        if (!isGatewayPacket(content)) return false
        if (!InternetRelayPreferences.isMeshBridgeEnabled()) return true

        cleanupStaleCaches()

        try {
            when {
                content.startsWith(PREFIX_GW_SUB) && content.endsWith("]") -> {
                    // A nearby offline peer asked online bridges to listen for its Gift-Wraps
                    if (!InternetRelayPreferences.isInternetEnabled() ||
                        !InternetRelayPreferences.hasActiveNetworkConnection(context)
                    ) return true

                    val pubkeyHex = content.removePrefix(PREFIX_GW_SUB).removeSuffix("]").trim().lowercase()
                    if (pubkeyHex.length == 64 && !bridgedPubkeySubscriptions.containsKey(pubkeyHex)) {
                        val subId = "gw-bridge-${pubkeyHex.take(12)}"
                        bridgedPubkeySubscriptions[pubkeyHex] = subId
                        val filter = NostrFilter.giftWrapsFor(
                            pubkey = pubkeyHex,
                            since = System.currentTimeMillis() - 3_600_000L
                        )
                        NostrRelayManager.getInstance(context).subscribe(
                            filter = filter,
                            id = subId,
                            handler = { giftWrapEvent ->
                                forwardGiftWrapBackToMesh(context, giftWrapEvent)
                            }
                        )
                    }
                }

                content.startsWith(PREFIX_GW_TX) && content.endsWith("]") -> {
                    // An offline peer wants an online bridge to publish an E2EE NostrEvent
                    if (!InternetRelayPreferences.isInternetEnabled() ||
                        !InternetRelayPreferences.hasActiveNetworkConnection(context)
                    ) return true

                    val assembledJson = collectChunk(content.removePrefix(PREFIX_GW_TX).removeSuffix("]"))
                        ?: return true
                    val event = gson.fromJson(assembledJson, NostrEvent::class.java) ?: return true
                    if (markEventSeen(event.id)) {
                        NostrRelayManager.getInstance(context).sendEvent(event)
                        Log.d(TAG, "🌐 Bridged mesh E2EE event ${event.id.take(10)} to Internet relays")
                    }
                }

                content.startsWith(PREFIX_GW_RX) && content.endsWith("]") -> {
                    // An online bridge forwarded an incoming Gift-Wrap back onto the mesh for us!
                    val assembledJson = collectChunk(content.removePrefix(PREFIX_GW_RX).removeSuffix("]"))
                        ?: return true
                    val event = gson.fromJson(assembledJson, NostrEvent::class.java) ?: return true
                    if (markEventSeen("rx_${event.id}")) {
                        val app = context.applicationContext as? Application ?: return true
                        com.bitchat.android.nostr.NostrBackgroundRuntime.deliverBridgedAccountEvent(app, event)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gateway frame handling error: ${e.message}")
        }
        return true
    }

    private fun forwardGiftWrapBackToMesh(context: Context, event: NostrEvent) {
        if (!markEventSeen("fwd_${event.id}")) return
        runCatching {
            val mesh = MeshServiceHolder.getUnifiedOrCreate(context.applicationContext)
            val jsonBytes = gson.toJson(event).toByteArray(Charsets.UTF_8)
            val b64 = Base64.encodeToString(jsonBytes, Base64.NO_WRAP)
            sendChunkedGatewayFrames(mesh, PREFIX_GW_RX, event.id.take(16), b64)
        }
    }

    private fun collectChunk(rawJson: String): String? {
        val obj = JsonParser.parseString(rawJson).asJsonObject
        val id = obj.get("id")?.asString ?: return null
        val idx = obj.get("i")?.asInt ?: return null
        val total = obj.get("n")?.asInt ?: return null
        val data = obj.get("d")?.asString ?: return null
        if (total <= 0 || total > 200 || idx < 0 || idx >= total) return null

        val buf = inboundBuffers.getOrPut(id) { ChunkBuffer(total) }
        buf.parts[idx] = data
        if (buf.parts.size < total) return null

        inboundBuffers.remove(id)
        val combinedB64 = (0 until total).joinToString("") { buf.parts[it].orEmpty() }
        val bytes = Base64.decode(combinedB64, Base64.DEFAULT)
        return String(bytes, Charsets.UTF_8)
    }

    private fun markEventSeen(key: String): Boolean {
        val now = System.currentTimeMillis()
        return processedEventIds.putIfAbsent(key, now) == null
    }

    private fun cleanupStaleCaches() {
        val now = System.currentTimeMillis()
        inboundBuffers.entries.removeIf { now - it.value.createdAtMs > 120_000L }
        if (processedEventIds.size > 1000) {
            processedEventIds.entries.removeIf { now - it.value > 600_000L }
        }
    }
}

