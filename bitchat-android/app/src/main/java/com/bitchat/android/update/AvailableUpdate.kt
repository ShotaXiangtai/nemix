package com.bitchat.android.update

/** A newer nemix release found by [AppUpdater]. */
data class AvailableUpdate(
    val versionName: String,
    val versionCode: Long,
    val releaseUrl: String
)
