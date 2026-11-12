package com.gezimos.katapult.weather

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import com.gezimos.katapult.weather.api.Weather
import com.gezimos.katapult.weather.api.WeatherCondition
import java.util.UUID

/** Owns one remote gate, its observations and teardown, independently of provider-process lifetime. */
internal class SleepingWeatherFixture(private val context: Context) : AutoCloseable {
    companion object {
        val URI: Uri = Uri.parse("content://com.alexindigo.kotatsu.debug.test.sleepingweather/current")
        fun status(context: Context): Bundle = checkNotNull(context.contentResolver.call(URI, "state", null, null))
    }
    // Hold a stable provider reference; concurrent IPC still uses ContentResolver's own clients.
    private val providerLease = checkNotNull(context.contentResolver.acquireContentProviderClient(URI))
    val id: String = UUID.randomUUID().toString()
    private val lock = Any()
    private var closed = false
    private var reads = 0
    val providerPid: Int
    init {
        try {
            val started = command("begin")
            check(started.getBoolean("active")) { "Provider did not begin the owned lifetime" }
            check(started.getInt("entered") == 0 && started.getInt("inFlight") == 0 && !started.getBoolean("released"))
            providerPid = started.getInt("pid")
            check(providerPid > 0) { "Provider did not identify its process" }
        } catch (error: Throwable) {
            try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }
    private fun command(method: String): Bundle = checkNotNull(context.contentResolver.call(URI, method, id, null))
    private fun state(): Bundle = command("state").also {
        check(it.getBoolean("active") && it.getInt("pid") == providerPid) { "Owned provider lifetime disappeared" }
    }
    fun read(): Weather? {
        synchronized(lock) { check(!closed); reads++ }
        try {
            return context.contentResolver.query(URI, null, null, arrayOf(id), null)?.use { cursor ->
                check(cursor.moveToFirst())
                Weather(cursor.getInt(0), "C", WeatherCondition.FEW_CLOUDS, false)
            }
        } finally {
            synchronized(lock) { reads-- }
        }
    }
    fun awaitBlocked() {
        await("Current provider query did not reach its unreleased gate") {
            val value = state()
            value.getInt("entered") > 0 && value.getInt("inFlight") > 0 &&
                value.getInt("completed") == 0 && !value.getBoolean("released")
        }
    }
    fun release() {
        synchronized(lock) { check(!closed) }
        val value = command("release")
        check(value.getBoolean("active") && value.getBoolean("released")) { "Provider rejected the owned release" }
    }
    private fun await(message: String, ready: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) {
            if (ready()) return
            Thread.sleep(25)
        }
        throw AssertionError(message)
    }
    override fun close() {
        synchronized(lock) { if (closed) return; closed = true }
        var failure: Throwable? = null
        fun restore(action: () -> Unit) { try { action() } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        } }
        restore { command("release") }
        restore { await("Fixture readers did not drain") { synchronized(lock) { reads == 0 } } }
        restore { await("Provider lifetime did not drain") { command("state").getInt("inFlight") == 0 } }
        restore {
            val ended = command("end")
            check(!ended.getBoolean("active") && ended.getInt("entered") == 0 &&
                ended.getInt("inFlight") == 0 && ended.getInt("completed") == 0 &&
                !ended.getBoolean("released")) { "Provider lifetime was not reset" }
        }
        restore { providerLease.close() }
        failure?.let { throw it }
    }
}
