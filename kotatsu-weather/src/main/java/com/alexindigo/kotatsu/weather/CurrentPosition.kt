package com.alexindigo.kotatsu.weather

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.CancellationSignal
import android.os.SystemClock

/**
 * One position fix from the phone's own location providers, only when asked (no background tracking, no Google
 * services). A fix the phone already has from the last few minutes answers at once; otherwise a fresh one is
 * requested from the fused provider (Android's own, built on GPS here) or GPS directly.
 */
object CurrentPosition {
    sealed interface Result {
        data class Found(val latitude: Double, val longitude: Double) : Result
        /** Location access isn't granted to this app. */
        object Denied : Result
        /** Location is switched off on the phone, or it has no usable provider. */
        object Off : Result
        /** The providers gave up without a fix, e.g. GPS indoors. */
        object NotFound : Result
    }

    /** Android's own fused provider; the constant exists only from API 31, the provider itself before that. */
    private const val FUSED = "fused"
    private const val RECENT_NANOS = 10 * 60 * 1_000_000_000L

    fun granted(context: Context): Boolean =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Calls [done] once on the main thread; cancel the returned signal to stop waiting. */
    @SuppressLint("MissingPermission") // Checked by granted() first.
    fun find(context: Context, done: (Result) -> Unit): CancellationSignal? {
        if (!granted(context)) { done(Result.Denied); return null }
        val manager = context.getSystemService(LocationManager::class.java)
        if (!manager.isLocationEnabled) { done(Result.Off); return null }
        val providers = listOf(FUSED, LocationManager.GPS_PROVIDER)
            .filter { it in manager.allProviders && manager.isProviderEnabled(it) }
        val now = SystemClock.elapsedRealtimeNanos()
        val recent = (providers + LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { now - it.elapsedRealtimeNanos < RECENT_NANOS }
            .maxByOrNull { it.elapsedRealtimeNanos }
        if (recent != null) { done(Result.Found(recent.latitude, recent.longitude)); return null }
        val provider = providers.firstOrNull() ?: run { done(Result.Off); return null }
        val signal = CancellationSignal()
        manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
            if (!signal.isCanceled) done(location?.let { Result.Found(it.latitude, it.longitude) } ?: Result.NotFound)
        }
        return signal
    }
}
