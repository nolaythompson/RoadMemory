package com.roadmemory.app.sdk

import android.content.Context
import android.util.Log
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import com.roadmemory.app.core.PlaceLearner
import com.roadmemory.app.data.LocationSnapshot

/**
 * FusedLocation adapter. The Vehicle SDK exposes no speed signal, so speed and
 * acceleration are derived from consecutive fused positions.
 */
class LocationTracker(
    context: Context,
    private val onChange: (LocationSnapshot) -> Unit,
) {
    private val fused = FusedLocation(context.applicationContext)

    @Volatile
    var snapshot = LocationSnapshot()
        private set

    private val listener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            accept(location.latitude, location.longitude, location.timestampMillis)
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "location error: ${exception.message}")
        }
    }

    fun start() {
        try {
            fused.initialize()
            if (!fused.isSupported()) {
                Log.w(TAG, "FusedLocation not supported on this device")
            }
            fused.getLastKnownLocation(listener)
            fused.registerFusedLocationCallback(listener)
        } catch (t: Throwable) {
            Log.w(TAG, "FusedLocation unavailable: ${t.message}")
        }
    }

    fun stop() {
        runCatching { fused.unregisterFusedLocationCallback(listener) }
        runCatching { fused.release() }
    }

    /** Also used by the demo simulator. */
    fun accept(lat: Double, lon: Double, timestampMillis: Long) {
        val prev = snapshot
        val ts = if (timestampMillis > 0) timestampMillis else System.currentTimeMillis()
        var speed = prev.speedKmh
        var accel = 0.0
        if (prev.isValid && ts > prev.timestampMillis) {
            val dtSec = (ts - prev.timestampMillis) / 1000.0
            val meters = PlaceLearner.distanceMeters(prev.latitude, prev.longitude, lat, lon)
            val raw = meters / dtSec * 3.6
            // light smoothing against GPS jitter
            speed = if (prev.speedKmh == 0.0) raw else prev.speedKmh * 0.4 + raw * 0.6
            accel = (speed - prev.speedKmh) / dtSec
        }
        snapshot = LocationSnapshot(lat, lon, speed, accel, ts)
        onChange(snapshot)
    }

    companion object {
        private const val TAG = "RM/Location"
    }
}
