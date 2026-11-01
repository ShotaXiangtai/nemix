package com.bitchat.android.migration

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.legal.TermsOfServiceManager
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.net.InternetRelayPreferences
import com.bitchat.android.profile.UserProfileManager
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ConversationRepository
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encapsulates the entire nemix user data for migration or backup.
 */
data class MigrationPayload(
    val version: Int = 2,
    val exportedAt: Long = System.currentTimeMillis(),
    val appName: String = "nemix",
    val nickname: String,
    val identityPrefs: Map<String, String>,      // EncryptedSharedPreferences (bitchat_identity)
    val cryptoPrefs: Map<String, String>,        // EncryptedSharedPreferences (bitchat_crypto_secure)
    val bitchatPrefs: Map<String, String>,       // bitchat_prefs (settings, nickname, etc.)
    val meshPrefs: Map<String, String>,          // bitchat_mesh_service_prefs
    val profilePrefs: Map<String, String>? = emptyMap(),  // nemix_profile_prefs (compressed avatar & setup)
    val internetPrefs: Map<String, String>? = emptyMap(), // nemix_internet_prefs (custom relay & bridge)
    val legalPrefs: Map<String, String>? = emptyMap(),    // nemix_legal_prefs (ToS acceptance)
    val uuidPrefs: Map<String, String>? = emptyMap(),     // nemix_uuid_prefs (random UUID)
    val policyPrefs: Map<String, String>? = emptyMap(),   // nemix_chat_policy_prefs (screenshot policy)
    val archivePrefs: Map<String, String>? = emptyMap(),  // nemix_chat_archive_prefs (archived chats)
    val uiSettingsPrefs: Map<String, String>? = emptyMap(), // bitchat_settings (Classic/Channel UI style)
    val p2pGroupsJson: String? = null,                    // Encrypted P2P groups (Stream & Channel formats)
    val conversations: Map<String, List<BitchatMessage>>, // Decrypted private conversations
    val readMessageIds: List<String>,
    val displayNames: Map<String, String>
)

/**
 * Connection info exchanged optically via QR code.
 */
data class MigrationConnectionInfo(
    val ip: String,
    val port: Int,
    val token: String,
    val keyBase64: String
)

object MigrationDataPackager {
    private const val TAG = "MigrationPackager"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val PBKDF2_ITERATIONS = 100_000
    private const val SALT_LENGTH = 16

    private val gson = Gson()

    private fun readPlainPrefsMap(context: Context, prefsName: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        try {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            prefs.all.forEach { (k, v) ->
                if (v != null) out[k] = gson.toJson(v)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading $prefsName", e)
        }
        return out
    }

    private fun restorePlainPrefsMap(context: Context, prefsName: String, map: Map<String, String>?) {
        if (map.isNullOrEmpty()) return
        try {
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            map.forEach { (k, jsonVal) ->
                restorePrefValue(editor, k, jsonVal)
            }
            editor.commit()
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring $prefsName", e)
        }
    }

    /**
     * Package all user data into a clean serializable MigrationPayload.
     */
    fun packageData(context: Context): MigrationPayload {
        val appContext = context.applicationContext

        // 1. Read identity preferences (Noise static key, Ed25519 signing key, etc.)
        val identityMap = mutableMapOf<String, String>()
        try {
            val masterKey = MasterKey.Builder(appContext, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val identityPrefs = EncryptedSharedPreferences.create(
                appContext,
                "bitchat_identity",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            identityPrefs.all.forEach { (k, v) ->
                if (v != null) identityMap[k] = gson.toJson(v)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading bitchat_identity", e)
        }

        // 2. Read crypto preferences
        val cryptoMap = mutableMapOf<String, String>()
        try {
            val masterKey = MasterKey.Builder(appContext, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val cryptoPrefs = EncryptedSharedPreferences.create(
                appContext,
                "bitchat_crypto_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            cryptoPrefs.all.forEach { (k, v) ->
                if (v != null) cryptoMap[k] = gson.toJson(v)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading bitchat_crypto_secure", e)
        }

        // 3. Read standard & nemix preferences
        val bitchatPrefsMap = readPlainPrefsMap(appContext, "bitchat_prefs")
        val meshPrefsMap = readPlainPrefsMap(appContext, "bitchat_mesh_service_prefs")
        val profilePrefsMap = readPlainPrefsMap(appContext, "nemix_profile_prefs")
        val internetPrefsMap = readPlainPrefsMap(appContext, "nemix_internet_prefs")
        val legalPrefsMap = readPlainPrefsMap(appContext, "nemix_legal_prefs")
        val uuidPrefsMap = readPlainPrefsMap(appContext, "nemix_uuid_prefs")
        val policyPrefsMap = readPlainPrefsMap(appContext, "nemix_chat_policy_prefs")
        val archivePrefsMap = readPlainPrefsMap(appContext, "nemix_chat_archive_prefs")
        val uiSettingsPrefsMap = readPlainPrefsMap(appContext, "bitchat_settings")
        val p2pGroupsJson = runCatching {
            com.bitchat.android.groups.P2PGroupManager.exportMigrationJson()
        }.getOrNull()

        // 4. Read all private conversations
        val conversations = AppStateStore.privateMessages.value
        val readMessageIds = AppStateStore.readPrivateMessageIDs.value.toList()
        val displayNames = AppStateStore.privateConversationDisplayNames.value
        val currentNickname = AppStateStore.nickname.value.ifEmpty { "anon" }

        return MigrationPayload(
            nickname = currentNickname,
            identityPrefs = identityMap,
            cryptoPrefs = cryptoMap,
            bitchatPrefs = bitchatPrefsMap,
            meshPrefs = meshPrefsMap,
            profilePrefs = profilePrefsMap,
            internetPrefs = internetPrefsMap,
            legalPrefs = legalPrefsMap,
            uuidPrefs = uuidPrefsMap,
            policyPrefs = policyPrefsMap,
            archivePrefs = archivePrefsMap,
            uiSettingsPrefs = uiSettingsPrefsMap,
            p2pGroupsJson = p2pGroupsJson,
            conversations = conversations,
            readMessageIds = readMessageIds,
            displayNames = displayNames
        )
    }

    /**
     * Restore MigrationPayload onto the current device.
     */
    fun restoreData(context: Context, payload: MigrationPayload): Boolean {
        val appContext = context.applicationContext
        try {
            val masterKey = MasterKey.Builder(appContext, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            // 1. Restore identity preferences
            try {
                val identityPrefs = EncryptedSharedPreferences.create(
                    appContext,
                    "bitchat_identity",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                val editor = identityPrefs.edit()
                payload.identityPrefs.forEach { (k, jsonVal) ->
                    restorePrefValue(editor, k, jsonVal)
                }
                editor.commit()
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring identity prefs", e)
            }

            // 2. Restore crypto preferences
            try {
                val cryptoPrefs = EncryptedSharedPreferences.create(
                    appContext,
                    "bitchat_crypto_secure",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
                val editor = cryptoPrefs.edit()
                payload.cryptoPrefs.forEach { (k, jsonVal) ->
                    restorePrefValue(editor, k, jsonVal)
                }
                editor.commit()
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring crypto prefs", e)
            }

            // 3. Restore plain preferences (including profile icon, custom relays, ToS state)
            restorePlainPrefsMap(appContext, "bitchat_prefs", payload.bitchatPrefs)
            restorePlainPrefsMap(appContext, "bitchat_mesh_service_prefs", payload.meshPrefs)
            restorePlainPrefsMap(appContext, "nemix_profile_prefs", payload.profilePrefs)
            restorePlainPrefsMap(appContext, "nemix_internet_prefs", payload.internetPrefs)
            restorePlainPrefsMap(appContext, "nemix_legal_prefs", payload.legalPrefs)
            restorePlainPrefsMap(appContext, "nemix_uuid_prefs", payload.uuidPrefs)
            restorePlainPrefsMap(appContext, "nemix_chat_policy_prefs", payload.policyPrefs)
            restorePlainPrefsMap(appContext, "nemix_chat_archive_prefs", payload.archivePrefs)
            restorePlainPrefsMap(appContext, "bitchat_settings", payload.uiSettingsPrefs)
            runCatching {
                com.bitchat.android.groups.P2PGroupManager.restoreFromMigrationJson(
                    appContext,
                    payload.p2pGroupsJson
                )
            }

            // 4. Restore conversations into repository
            val repo = ConversationRepository.getInstance(appContext)
            val readIdsSet = payload.readMessageIds.toSet()

            payload.conversations.forEach { (convId, messages) ->
                val displayName = payload.displayNames[convId]
                messages.forEach { message ->
                    val isRead = readIdsSet.contains(message.id)
                    repo.upsertMessage(
                        conversationID = convId,
                        aliases = setOf(convId),
                        displayName = displayName,
                        message = message,
                        isRead = isRead
                    )
                    AppStateStore.addPrivateMessage(convId, message, isRead)
                }
            }

            // 5. Reload Favorites, Profile Avatar, Internet, Legal, UUID, Archive, UI Style & Security Policy State
            runCatching { FavoritesPersistenceService.initialize(appContext) }
            runCatching { UserProfileManager.reloadAfterMigration(appContext) }
            runCatching { InternetRelayPreferences.init(appContext) }
            runCatching { TermsOfServiceManager.init(appContext) }
            runCatching { com.bitchat.android.directory.NemixUuidDirectoryManager.init(appContext) }
            runCatching { com.bitchat.android.security.ChatSecurityPolicyManager.init(appContext) }
            runCatching { com.bitchat.android.services.ChatArchiveManager.init(appContext) }
            runCatching { com.bitchat.android.ui.theme.AppUiStyleManager.init(appContext) }
            runCatching { com.bitchat.android.ui.theme.ChatUiModeManager.init(appContext) }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore migration data", e)
            return false
        }
    }

    private fun restorePrefValue(editor: SharedPreferences.Editor, key: String, jsonVal: String) {
        try {
            if (jsonVal.startsWith("\"") && jsonVal.endsWith("\"")) {
                editor.putString(key, gson.fromJson(jsonVal, String::class.java))
            } else if (jsonVal == "true" || jsonVal == "false") {
                editor.putBoolean(key, jsonVal.toBoolean())
            } else if (jsonVal.toIntOrNull() != null) {
                editor.putInt(key, jsonVal.toInt())
            } else if (jsonVal.toLongOrNull() != null) {
                editor.putLong(key, jsonVal.toLong())
            } else if (jsonVal.startsWith("[") && jsonVal.endsWith("]")) {
                val setType = object : TypeToken<Set<String>>() {}.type
                val set = gson.fromJson<Set<String>>(jsonVal, setType)
                editor.putStringSet(key, set)
            } else {
                editor.putString(key, jsonVal)
            }
        } catch (e: Exception) {
            editor.putString(key, jsonVal)
        }
    }

    /**
     * Encrypt payload with AES-256-GCM using an ephemeral key.
     */
    fun encryptWithKey(payload: MigrationPayload, keyBytes: ByteArray): ByteArray {
        val jsonBytes = gson.toJson(payload).toByteArray(StandardCharsets.UTF_8)
        val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val cipherText = cipher.doFinal(jsonBytes)

        val result = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(cipherText, 0, result, iv.size, cipherText.size)
        return result
    }

    /**
     * Decrypt payload with AES-256-GCM.
     */
    fun decryptWithKey(encryptedBytes: ByteArray, keyBytes: ByteArray): MigrationPayload {
        require(encryptedBytes.size > GCM_IV_LENGTH) { "Payload too short" }
        val iv = ByteArray(GCM_IV_LENGTH)
        System.arraycopy(encryptedBytes, 0, iv, 0, GCM_IV_LENGTH)
        val cipherLength = encryptedBytes.size - GCM_IV_LENGTH
        val cipherText = ByteArray(cipherLength)
        System.arraycopy(encryptedBytes, GCM_IV_LENGTH, cipherText, 0, cipherLength)

        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        val decryptedBytes = cipher.doFinal(cipherText)
        val jsonString = String(decryptedBytes, StandardCharsets.UTF_8)
        return gson.fromJson(jsonString, MigrationPayload::class.java)
    }

    /**
     * Export to a password-encrypted file (.nemixbackup).
     */
    fun exportToFile(context: Context, targetFile: File, password: String): Boolean {
        try {
            val payload = packageData(context)
            val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
            val keyBytes = deriveKeyFromPassword(password, salt)
            val encryptedData = encryptWithKey(payload, keyBytes)

            val magic = "NEMIX".toByteArray(StandardCharsets.US_ASCII)
            FileOutputStream(targetFile).use { fos ->
                fos.write(magic)
                fos.write(salt)
                fos.write(encryptedData)
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export backup to file", e)
            return false
        }
    }

    /**
     * Import from a password-encrypted file (.nemixbackup).
     */
    fun importFromFile(context: Context, sourceFile: File, password: String): Boolean {
        try {
            FileInputStream(sourceFile).use { fis ->
                val magic = ByteArray(5)
                fis.read(magic)
                if (String(magic, StandardCharsets.US_ASCII) != "NEMIX") {
                    throw IllegalArgumentException("Invalid backup file magic header")
                }
                val salt = ByteArray(SALT_LENGTH)
                fis.read(salt)
                val encryptedData = fis.readBytes()
                val keyBytes = deriveKeyFromPassword(password, salt)
                val payload = decryptWithKey(encryptedData, keyBytes)
                return restoreData(context, payload)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import backup from file", e)
            return false
        }
    }

    private fun deriveKeyFromPassword(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    /**
     * Safely wipe all local data from old device after successful transfer.
     */
    fun wipeAllLocalData(context: Context) {
        val appContext = context.applicationContext
        try {
            listOf(
                "bitchat_identity",
                "bitchat_crypto_secure",
                "bitchat_prefs",
                "bitchat_mesh_service_prefs",
                "peer_availability_notifications",
                "nemix_profile_prefs",
                "nemix_internet_prefs",
                "nemix_legal_prefs",
                "nemix_security_prefs",
                "nemix_uuid_prefs",
                "nemix_chat_policy_prefs",
                "nemix_chat_archive_prefs",
                "bitchat_settings"
            ).forEach { name ->
                try {
                    appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                } catch (_: Exception) {}
            }

            runCatching { UserProfileManager.clearAll(appContext) }
            runCatching { com.bitchat.android.security.ChatSecurityPolicyManager.clearAll(appContext) }
            runCatching { com.bitchat.android.services.ChatArchiveManager.clearAll(appContext) }
            runCatching { com.bitchat.android.ui.theme.AppUiStyleManager.clear(appContext) }
            runCatching { com.bitchat.android.groups.P2PGroupManager.clearAll(appContext) }

            try {
                appContext.deleteDatabase("private_conversations.db")
            } catch (_: Exception) {}

            AppStateStore.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Error wiping local data", e)
        }
    }

    /**
     * Find local IP address on Wi-Fi or active network interface.
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: ""
                        if (host.isNotEmpty() && !host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error discovering local IP", e)
        }
        return "127.0.0.1"
    }
}
