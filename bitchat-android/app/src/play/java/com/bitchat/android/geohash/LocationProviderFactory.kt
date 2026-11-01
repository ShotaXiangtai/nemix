package com.bitchat.android.geohash

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Google Play build: prefers Google Play Services' fused location provider,
 * falling back to the Android LocationManager when Play Services is unavailable.
 */
internal object LocationProviderFactory {
    private const val TAG = "LocationProviderFactory"

    fun create(context: Context): LocationProvider {
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        return if (availability == ConnectionResult.SUCCESS) {
            Log.i(TAG, "Using FusedLocationProvider (Google Play Services)")
            FusedLocationProvider(context)
        } else {
            Log.i(TAG, "Using SystemLocationProvider (Native LocationManager)")
            SystemLocationProvider(context)
        }
    }
}
