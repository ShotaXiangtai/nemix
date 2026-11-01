package com.bitchat.android.geohash

import android.content.Context

/**
 * F-Droid / privacy build: Android LocationManager only. No Google Play Services.
 */
internal object LocationProviderFactory {
    fun create(context: Context): LocationProvider = SystemLocationProvider(context)
}
