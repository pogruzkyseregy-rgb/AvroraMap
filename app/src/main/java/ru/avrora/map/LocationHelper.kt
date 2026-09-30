package ru.avrora.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import org.maplibre.android.geometry.LatLng

/** Геолокация через системный LocationManager (без сервисов Google). */
class LocationHelper(private val ctx: Context, private val onFix: (LatLng) -> Unit) {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) = onFix(LatLng(l.latitude, l.longitude))
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    fun hasPermission() =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Возвращает false, если геолокация на телефоне выключена. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (!hasPermission()) return false
        stop()
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return false
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { onFix(LatLng(it.latitude, it.longitude)) }
        providers.forEach { lm.requestLocationUpdates(it, 3000L, 5f, listener, Looper.getMainLooper()) }
        return true
    }

    fun stop() = lm.removeUpdates(listener)
}
