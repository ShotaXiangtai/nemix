package com.bitchat.android.migration

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object MigrationClient {
    private const val TAG = "MigrationClient"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun downloadAndRestore(
        context: Context,
        info: MigrationConnectionInfo,
        onProgress: (Float) -> Unit = {}
    ): Result<MigrationPayload> = withContext(Dispatchers.IO) {
        try {
            val url = "http://${info.ip}:${info.port}/migrate?token=${info.token}"
            Log.i(TAG, "Requesting migration data from $url")

            val request = Request.Builder()
                .url(url)
                .addHeader("Accept", "application/octet-stream")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    IllegalStateException("HTTP ${response.code}: ${response.message}")
                )
            }

            val body = response.body ?: return@withContext Result.failure(
                IllegalStateException("Empty response body from migration server")
            )

            val contentLength = body.contentLength()
            val inputStream = body.byteStream()
            val outputStream = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytesRead = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytesRead += bytesRead
                if (contentLength > 0) {
                    onProgress(totalBytesRead.toFloat() / contentLength.toFloat())
                }
            }

            val encryptedBytes = outputStream.toByteArray()
            Log.i(TAG, "Downloaded ${encryptedBytes.size} bytes of encrypted payload")

            // Decrypt
            val keyBytes = Base64.decode(info.keyBase64, Base64.DEFAULT or Base64.URL_SAFE)
            val payload = MigrationDataPackager.decryptWithKey(encryptedBytes, keyBytes)
            Log.i(TAG, "Successfully decrypted payload for user: ${payload.nickname}")

            // Restore
            val restored = MigrationDataPackager.restoreData(context, payload)
            if (!restored) {
                return@withContext Result.failure(IllegalStateException("Failed to restore decrypted data to database"))
            }

            // Send confirmation back to old device
            try {
                val confirmUrl = "http://${info.ip}:${info.port}/confirm?token=${info.token}"
                val confirmRequest = Request.Builder().url(confirmUrl).build()
                httpClient.newCall(confirmRequest).execute().close()
                Log.i(TAG, "Confirmation sent to migration server")
            } catch (e: Exception) {
                Log.w(TAG, "Non-fatal: Failed to send confirmation to sender", e)
            }

            Result.success(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed migration download", e)
            Result.failure(e)
        }
    }
}

