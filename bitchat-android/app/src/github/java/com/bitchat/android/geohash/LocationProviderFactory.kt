package com.bitchat.android.geohash

import android.content.Context

/**
 * GitHub / privacy build: Android LocationManager only. No Google Play Services.
 */
internal object LocationProviderFactory {
    fun create(context: Context): LocationProvider = SystemLocationProvider(context)
}
