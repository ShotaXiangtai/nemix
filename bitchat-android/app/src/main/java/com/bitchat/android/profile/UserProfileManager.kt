package com.bitchat.android.profile

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.bitchat.android.services.ContactDirectory
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the user's display name, tightly compressed profile avatar (~2-5 KB WebP, EXIF stripped),
 * and peer avatar synchronization across both Local Mesh and Internet relays.
 */
object UserProfileManager {
    private const val TAG = "UserProfileManager"
    const val PREFS_NAME = "nemix_profile_prefs"
    private const val KEY_SETUP_COMPLETED = "profile_setup_completed"
    private const val KEY_MY_AVATAR_B64 = "my_avatar_b64"
    private const val KEY_MY_AVATAR_HASH = "my_avatar_hash"
    const val PROFILE_PACKET_PREFIX = "[NEMIX_PROFILE:"

    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null

    private val _isSetupCompleted = MutableStateFlow(false)
    val isSetupCompleted: StateFlow<Boolean> = _isSetupCompleted.asStateFlow()

    private val _myAvatarBitmap = MutableStateFlow<Bitmap?>(null)
    val myAvatarBitmap: StateFlow<Bitmap?> = _myAvatarBitmap.asStateFlow()

    private val _myAvatarSizeBytes = MutableStateFlow(0)
    val myAvatarSizeBytes: StateFlow<Int> = _myAvatarSizeBytes.asStateFlow()

    private val _peerAvatars = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val peerAvatars: StateFlow<Map<String, Bitmap>> = _peerAvatars.asStateFlow()

    // Tracks which profile hash we have already sent to each peer during this session
    private val lastSyncedHashByPeer = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val sp = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = sp
        _isSetupCompleted.value = sp.getBoolean(KEY_SETUP_COMPLETED, false)

        // Restore self avatar
        val b64 = sp.getString(KEY_MY_AVATAR_B64, null)
        if (!b64.isNullOrBlank()) {
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                _myAvatarBitmap.value = bmp
                _myAvatarSizeBytes.value = bytes.size
            }
        } else {
            val selfFile = File(avatarsDir(ctx), "self.webp")
            if (selfFile.exists()) {
                runCatching {
                    val bytes = selfFile.readBytes()
                    _myAvatarBitmap.value = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    _myAvatarSizeBytes.value = bytes.size
                }
            }
        }

        // Load cached peer avatars from disk
        loadCachedPeerAvatars(ctx)
    }

    private fun avatarsDir(context: Context): File =
        File(context.filesDir, "avatars").apply { mkdirs() }

    private fun loadCachedPeerAvatars(context: Context) {
        runCatching {
            val dir = avatarsDir(context)
            val map = mutableMapOf<String, Bitmap>()
            dir.listFiles()?.forEach { file ->
                if (file.name.startsWith("peer_") && file.name.endsWith(".webp")) {
                    val keyHex = file.name.removePrefix("peer_").removeSuffix(".webp")
                    val decodedKey = runCatching {
                        String(Base64.decode(keyHex, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
                    }.getOrNull() ?: keyHex
                    BitmapFactory.decodeFile(file.absolutePath)?.let { bmp ->
                        map[decodedKey] = bmp
                    }
                }
            }
            if (map.isNotEmpty()) {
                _peerAvatars.value = map
            }
        }
    }

    fun isProfileSetupCompleted(context: Context): Boolean {
        if (prefs == null) init(context)
        return _isSetupCompleted.value
    }

    fun markProfileSetupCompleted(context: Context) {
        if (prefs == null) init(context)
        prefs?.edit()?.putBoolean(KEY_SETUP_COMPLETED, true)?.apply()
        _isSetupCompleted.value = true
    }

    /**
     * Compresses an arbitrary user-picked image into a non-disruptive (~2-5 KB) square WebP icon,
     * stripping 100% of EXIF location/camera metadata.
     */
    fun compressAvatarFromUri(context: Context, uri: Uri): ByteArray? {
        return try {
            val resolver = context.contentResolver

            // 1. Read EXIF rotation before decoding
            val rotation = resolver.openInputStream(uri)?.use { stream ->
                runCatching {
                    val exif = ExifInterface(stream)
                    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
                        else -> 0
                    }
                }.getOrDefault(0)
            } ?: 0

            // 2. Probe bounds to avoid OOM on large camera photos
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
            val maxSrc = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
            var sampleSize = 1
            while (maxSrc / (sampleSize * 2) >= 256) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null

            val oriented = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            } else {
                decoded
            }

            // 3. Center-crop 1:1 square
            val side = minOf(oriented.width, oriented.height)
            val cropX = (oriented.width - side) / 2
            val cropY = (oriented.height - side) / 2
            val square = Bitmap.createBitmap(oriented, cropX, cropY, side, side)

            // 4. First pass: 96x96 WebP @ quality 72 (~2.5 - 4.5 KB)
            var compressed = encodeSquareWebp(square, targetPx = 96, quality = 72)

            // 5. Second pass if noisy image exceeds 5.2 KB: 80x80 WebP @ quality 64
            if (compressed.size > 5_200) {
                compressed = encodeSquareWebp(square, targetPx = 80, quality = 64)
            }
            // 6. Final safety cap at 64x64 quality 58 so BLE & Internet relays never lag
            if (compressed.size > 6_000) {
                compressed = encodeSquareWebp(square, targetPx = 64, quality = 58)
            }

            if (square !== oriented) runCatching { square.recycle() }
            if (oriented !== decoded) runCatching { oriented.recycle() }
            runCatching { decoded.recycle() }

            compressed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress avatar: ${e.message}")
            null
        }
    }

    private fun encodeSquareWebp(square: Bitmap, targetPx: Int, quality: Int): ByteArray {
        val scaled = Bitmap.createScaledBitmap(square, targetPx, targetPx, true)
        val out = ByteArrayOutputStream()
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        scaled.compress(format, quality, out)
        if (scaled !== square) runCatching { scaled.recycle() }
        return out.toByteArray()
    }

    fun saveMyAvatar(context: Context, compressedBytes: ByteArray?) {
        if (prefs == null) init(context)
        val dir = avatarsDir(context)
        val selfFile = File(dir, "self.webp")

        if (compressedBytes == null || compressedBytes.isEmpty()) {
            if (selfFile.exists()) selfFile.delete()
            prefs?.edit()
                ?.remove(KEY_MY_AVATAR_B64)
                ?.remove(KEY_MY_AVATAR_HASH)
                ?.apply()
            _myAvatarBitmap.value = null
            _myAvatarSizeBytes.value = 0
        } else {
            runCatching { selfFile.writeBytes(compressedBytes) }
            val b64 = Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
            val hash = sha256Short(compressedBytes)
            prefs?.edit()
                ?.putString(KEY_MY_AVATAR_B64, b64)
                ?.putString(KEY_MY_AVATAR_HASH, hash)
                ?.apply()
            _myAvatarBitmap.value = BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.size)
            _myAvatarSizeBytes.value = compressedBytes.size
        }
        // Invalidate sync cache so peers receive our updated avatar
        lastSyncedHashByPeer.clear()
    }

    fun notifyNicknameChanged() {
        lastSyncedHashByPeer.clear()
    }

    fun getPeerAvatar(avatarsMap: Map<String, Bitmap>, peerID: String?, nickname: String? = null): Bitmap? {
        if (!peerID.isNullOrBlank()) {
            avatarsMap[peerID]?.let { return it }
            val canonical = runCatching { ContactDirectory.canonicalConversationId(peerID) }.getOrNull()
            if (canonical != null) {
                avatarsMap[canonical]?.let { return it }
            }
        }
        if (!nickname.isNullOrBlank()) {
            avatarsMap["name:$nickname"]?.let { return it }
        }
        return null
    }

    /**
     * Builds a `[NEMIX_PROFILE:...]` sync packet if our current profile state hasn't been synced
     * to [peerID] yet in this session. Returns null if already up to date.
     */
    fun buildSyncPacketIfNeeded(context: Context, peerID: String, myNickname: String): String? {
        if (prefs == null) init(context)
        val b64 = prefs?.getString(KEY_MY_AVATAR_B64, "") ?: ""
        val avatarHash = prefs?.getString(KEY_MY_AVATAR_HASH, "none") ?: "none"
        val combinedHash = sha256Short("$myNickname|$avatarHash".toByteArray(Charsets.UTF_8))

        val canonical = runCatching { ContactDirectory.canonicalConversationId(peerID) }.getOrDefault(peerID)
        if (lastSyncedHashByPeer[canonical] == combinedHash) {
            return null
        }
        lastSyncedHashByPeer[canonical] = combinedHash

        val json = JsonObject().apply {
            addProperty("v", 1)
            addProperty("name", myNickname)
            addProperty("hash", avatarHash)
            addProperty("avatar", b64)
        }
        return "$PROFILE_PACKET_PREFIX$json]"
    }

    private val syncScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    /**
     * Sends our nickname + avatar to [peerID] if they don't have the current version yet.
     * Called whenever we message someone and when a contact comes into mesh range, so it works
     * from every screen (previously only the classic 1:1 chat screen synced profiles).
     * Anonymous location-channel (geohash) DMs never receive the avatar.
     */
    fun syncProfileTo(context: Context, peerID: String) {
        if (peerID.isBlank() || com.bitchat.android.nostr.GeohashAliasRegistry.contains(peerID)) return
        val ctx = context.applicationContext
        syncScope.launch {
            val nickname = com.bitchat.android.services.AppStateStore.nickname.value
            if (nickname.isBlank()) return@launch
            val packet = buildSyncPacketIfNeeded(ctx, peerID, nickname) ?: return@launch
            val route = runCatching {
                val mesh = com.bitchat.android.service.MeshServiceHolder.getUnifiedOrCreate(ctx)
                val name = ContactDirectory.resolve(peerID).displayName ?: peerID.take(8)
                com.bitchat.android.services.MessageRouter.getInstance(ctx, mesh).sendPrivate(
                    packet, peerID, name, java.util.UUID.randomUUID().toString().uppercase()
                )
            }.getOrNull()
            // Not sent at all: allow a retry on the next opportunity instead of marking it synced.
            if (route == null || route == com.bitchat.android.services.MessageRouter.RouteResult.DROPPED) {
                forgetSync(peerID)
            }
        }
    }

    private fun forgetSync(peerID: String) {
        val canonical = runCatching { ContactDirectory.canonicalConversationId(peerID) }.getOrDefault(peerID)
        lastSyncedHashByPeer.remove(canonical)
    }

    fun isProfileSyncPacket(content: String): Boolean =
        content.startsWith(PROFILE_PACKET_PREFIX) && content.endsWith("]")

    /**
     * Parses an incoming `[NEMIX_PROFILE:...]` packet and caches the peer's compressed avatar.
     */
    fun handleIncomingProfilePacket(
        context: Context,
        senderPeerID: String?,
        senderNostrPubkey: String?,
        fallbackNickname: String?,
        content: String
    ): Boolean {
        if (!isProfileSyncPacket(content)) return false
        return try {
            if (prefs == null) init(context)
            val rawJson = content.removePrefix(PROFILE_PACKET_PREFIX).removeSuffix("]")
            val obj = JsonParser.parseString(rawJson).asJsonObject
            val peerName = obj.get("name")?.asString?.takeIf { it.isNotBlank() } ?: fallbackNickname
            val avatarB64 = obj.get("avatar")?.asString ?: ""

            val keys = buildSet {
                senderPeerID?.takeIf { it.isNotBlank() }?.let { pid ->
                    add(pid)
                    runCatching { ContactDirectory.canonicalConversationId(pid) }.getOrNull()?.let(::add)
                }
                senderNostrPubkey?.takeIf { it.isNotBlank() }?.let { pub ->
                    add("nostr_${pub.lowercase().take(16)}")
                }
                peerName?.takeIf { it.isNotBlank() }?.let { add("name:$it") }
            }

            if (keys.isEmpty()) return true

            val current = _peerAvatars.value.toMutableMap()
            if (avatarB64.isBlank()) {
                keys.forEach { key ->
                    current.remove(key)
                    deletePeerAvatarFile(context, key)
                }
            } else {
                val bytes = Base64.decode(avatarB64, Base64.DEFAULT)
                if (bytes.size <= 32_768) { // Reject oversized spoofed payloads
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        keys.forEach { key -> current[key] = bmp }
                        val primaryKey = keys.first()
                        savePeerAvatarFile(context, primaryKey, bytes)
                    }
                }
            }
            _peerAvatars.value = current
            // They shared their profile with us: share ours back if they don't have it yet.
            senderPeerID?.takeIf { it.isNotBlank() }?.let { syncProfileTo(context, it) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse profile packet: ${e.message}")
            true
        }
    }

    private fun savePeerAvatarFile(context: Context, key: String, bytes: ByteArray) {
        runCatching {
            val safeKey = Base64.encodeToString(
                key.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            File(avatarsDir(context), "peer_$safeKey.webp").writeBytes(bytes)
        }
    }

    private fun deletePeerAvatarFile(context: Context, key: String) {
        runCatching {
            val safeKey = Base64.encodeToString(
                key.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val f = File(avatarsDir(context), "peer_$safeKey.webp")
            if (f.exists()) f.delete()
        }
    }

    fun reloadAfterMigration(context: Context) {
        prefs = null
        init(context)
    }

    fun clearAll(context: Context) {
        runCatching {
            avatarsDir(context).deleteRecursively()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        }
        _myAvatarBitmap.value = null
        _myAvatarSizeBytes.value = 0
        _peerAvatars.value = emptyMap()
        _isSetupCompleted.value = false
        lastSyncedHashByPeer.clear()
    }

    private fun sha256Short(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }
}

