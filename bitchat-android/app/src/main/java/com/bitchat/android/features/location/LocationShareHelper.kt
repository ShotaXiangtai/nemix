package com.bitchat.android.features.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Privacy-preserving, ultra-lightweight one-shot location sharing using ONLY Android OS standard APIs
 * (`LocationManager`, optional `Geocoder`, and standard `geo:` Intent).
 * Zero third-party map SDKs, zero background tracking, zero tile-server IP leaks.
 */
object LocationShareHelper {
    const val PREFIX_LOCATION = "[NEMIX_LOC:"

    data class SharedLocation(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Int,
        val label: String
    )

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun encode(location: SharedLocation): String {
        val obj = JsonObject().apply {
            addProperty("lat", location.latitude)
            addProperty("lng", location.longitude)
            addProperty("acc", location.accuracyMeters)
            addProperty("label", location.label)
        }
        return "$PREFIX_LOCATION$obj]"
    }

    fun isLocationMessage(content: String): Boolean =
        content.startsWith(PREFIX_LOCATION) && content.endsWith("]")

    fun parse(content: String): SharedLocation? {
        if (!isLocationMessage(content)) return null
        return try {
            val raw = content.removePrefix(PREFIX_LOCATION).removeSuffix("]")
            val obj = JsonParser.parseString(raw).asJsonObject
            val lat = obj.get("lat")?.asDouble ?: return null
            val lng = obj.get("lng")?.asDouble ?: return null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
            val acc = obj.get("acc")?.asInt ?: 0
            val label = obj.get("label")?.asString ?: ""
            SharedLocation(lat, lng, acc, label)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Acquires a single on-demand location fix from the OS `LocationManager`.
     */
    @SuppressLint("MissingPermission")
    suspend fun fetchCurrentLocationOnce(context: Context): Result<SharedLocation> {
        if (!hasLocationPermission(context)) {
            return Result.failure(SecurityException("位置情報の権限が許可されていません"))
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return Result.failure(IllegalStateException("OSの位置情報サービスを取得できません"))

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).filter { provider ->
            runCatching { lm.isProviderEnabled(provider) }.getOrDefault(false)
        }

        if (providers.isEmpty()) {
            return Result.failure(IllegalStateException("端末の位置情報（GPS）がオフになっています"))
        }

        // 1. Try fresh fix (5.5s max wait) from best enabled provider
        val primaryProviders = providers.filter { it != LocationManager.PASSIVE_PROVIDER }
            .ifEmpty { providers }

        var bestFix: Location? = withTimeoutOrNull(5_500L) {
            requestSingleOsFix(context, lm, primaryProviders.first())
        }

        // 2. Fallback to freshest last-known OS location (e.g. indoors / airplane mode cached GPS)
        if (bestFix == null) {
            bestFix = providers
                .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                .maxByOrNull { it.time }
        }

        val loc = bestFix
            ?: return Result.failure(IllegalStateException("現在地を測位できませんでした（屋外や窓際でお試しください）"))

        // 3. Optional OS Geocoder area label (strictly best-effort, skipped cleanly when offline)
        val areaLabel = withContext(Dispatchers.IO) {
            withTimeoutOrNull(1_500L) {
                resolveOptionalOsAddressLabel(context, loc.latitude, loc.longitude)
            } ?: ""
        }

        return Result.success(
            SharedLocation(
                latitude = loc.latitude,
                longitude = loc.longitude,
                accuracyMeters = loc.accuracy.toInt().coerceAtLeast(1),
                label = areaLabel
            )
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleOsFix(
        context: Context,
        lm: LocationManager,
        provider: String
    ): Location? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cancelSignal = CancellationSignal()
            cont.invokeOnCancellation { cancelSignal.cancel() }
            runCatching {
                lm.getCurrentLocation(
                    provider,
                    cancelSignal,
                    ContextCompat.getMainExecutor(context)
                ) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
        } else {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    runCatching { lm.removeUpdates(this) }
                    if (cont.isActive) cont.resume(location)
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                override fun onProviderEnabled(provider: String) = Unit
                override fun onProviderDisabled(provider: String) {
                    runCatching { lm.removeUpdates(this) }
                    if (cont.isActive) cont.resume(null)
                }
            }
            cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
            runCatching {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }.onFailure {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveOptionalOsAddressLabel(context: Context, lat: Double, lng: Double): String {
        return try {
            if (!Geocoder.isPresent()) return ""
            val geocoder = Geocoder(context, Locale.getDefault())
            val list = geocoder.getFromLocation(lat, lng, 1) ?: return ""
            val addr = list.firstOrNull() ?: return ""
            listOfNotNull(addr.adminArea, addr.locality, addr.subLocality)
                .distinct()
                .joinToString("")
                .takeIf { it.isNotBlank() }
                ?.let { "${it}付近" }
                ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Opens the coordinates in the user's OS default map app using standard `geo:` URI intent.
     */
    fun openInOsMap(context: Context, location: SharedLocation) {
        val latStr = "%.6f".format(Locale.US, location.latitude)
        val lngStr = "%.6f".format(Locale.US, location.longitude)
        val displayTitle = location.label.ifBlank { "共有された位置 ($latStr, $lngStr)" }
        val geoUri = Uri.parse("geo:$latStr,$lngStr?q=$latStr,$lngStr(${Uri.encode(displayTitle)})")
        val geoIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(geoIntent)
        } catch (_: Exception) {
            // Fallback to standard browser OSM link only if no geo: handler exists on device
            runCatching {
                val webUri = Uri.parse("https://www.openstreetmap.org/?mlat=$latStr&mlon=$lngStr#map=16/$latStr/$lngStr")
                context.startActivity(Intent(Intent.ACTION_VIEW, webUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }.onFailure {
                Toast.makeText(context, "地図アプリが見つかりません ($latStr, $lngStr)", Toast.LENGTH_LONG).show()
            }
        }
    }
}

