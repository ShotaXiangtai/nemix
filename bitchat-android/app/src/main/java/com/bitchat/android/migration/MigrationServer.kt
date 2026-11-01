package com.bitchat.android.migration

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/**
 * Lightweight local HTTP server for serving encrypted migration payload over Wi-Fi.
 */
class MigrationServer(
    port: Int = DEFAULT_PORT,
    private val encryptedData: ByteArray,
    private val expectedToken: String,
    private val onTransferComplete: () -> Unit
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "MigrationServer"
        const val DEFAULT_PORT = 8988
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val params = session.parms

        val token = params["token"] ?: session.headers["authorization"]?.removePrefix("Bearer ")?.trim()

        // Constant-time comparison so the token cannot be recovered via timing side channels.
        val authorized = token != null && MessageDigest.isEqual(
            token.toByteArray(Charsets.UTF_8),
            expectedToken.toByteArray(Charsets.UTF_8)
        )
        if (!authorized) {
            Log.w(TAG, "Rejected unauthorized migration request: ${session.method} $uri")
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN,
                "text/plain",
                "Unauthorized migration request"
            )
        }

        return when (uri) {
            "/migrate" -> {
                Log.i(TAG, "Serving encrypted migration payload (${encryptedData.size} bytes)")
                newChunkedResponse(
                    Response.Status.OK,
                    "application/octet-stream",
                    ByteArrayInputStream(encryptedData)
                )
            }
            "/confirm" -> {
                Log.i(TAG, "Migration confirmed by recipient!")
                onTransferComplete()
                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"status":"success","message":"Migration completed"}"""
                )
            }
            else -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        }
    }
}

