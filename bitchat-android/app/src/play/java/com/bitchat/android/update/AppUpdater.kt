package com.bitchat.android.update

import android.content.Context
import java.io.File

/**
 * Google Play build: updates are delivered by Google Play only.
 * Self-updating outside Play is not allowed by Play policy, so this is a no-op.
 */
object AppUpdater {
    const val isSupported: Boolean = false

    suspend fun check(context: Context): Result<AvailableUpdate?> = Result.success(null)

    suspend fun download(context: Context, onProgress: (Int) -> Unit): Result<File> =
        Result.failure(UnsupportedOperationException("Updates come from Google Play"))

    fun needsInstallPermission(context: Context): Boolean = false

    fun openInstallPermissionSettings(context: Context) = Unit

    fun install(context: Context, apk: File): Boolean = false
}
