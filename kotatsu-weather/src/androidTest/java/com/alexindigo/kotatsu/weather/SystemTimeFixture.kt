package com.alexindigo.kotatsu.weather

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import java.time.ZoneId

/** Only the isolated API31 emulator. Restores observed system state even when the test fails. */
internal class SystemTimeFixture : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val wall = System.currentTimeMillis()
    private val elapsed = SystemClock.elapsedRealtime()
    private val zone = shell("getprop persist.sys.timezone").trim()
    private val autoTime = shell("settings get global auto_time").trim()
    private val autoZone = shell("settings get global auto_time_zone").trim()

    fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    fun disableAutomaticTime() {
        shell("settings put global auto_time 0")
        shell("settings put global auto_time_zone 0")
        assertEquals("0", shell("settings get global auto_time").trim())
        assertEquals("0", shell("settings get global auto_time_zone").trim())
    }

    fun setTime(millis: Long) {
        // The test image's su is confined to this owned emulator, as in WidgetProviderTestFixture.
        shell("su 0 cmd alarm set-time $millis")
        assertTrue("System time not set", kotlin.math.abs(System.currentTimeMillis() - millis) < 3000)
    }

    fun setZone(id: String) {
        ZoneId.of(id) // Accept only a real zone before putting it into a shell command.
        require(id.matches(Regex("[A-Za-z0-9_+./-]+")))
        shell("su 0 cmd alarm set-timezone $id")
        await("System timezone $id") {
            shell("getprop persist.sys.timezone").trim() == id && ZoneId.systemDefault().id == id
        }
    }

    fun await(message: String, timeout: Long = 15_000, ready: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!ready() && SystemClock.elapsedRealtime() < end) Thread.sleep(25)
        assertTrue(message, ready())
    }

    override fun close() {
        // Try every restoration even if an earlier restore operation fails; propagate all failures.
        var failure: Throwable? = null
        fun restore(operation: () -> Unit) { try { operation() } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        } }
        restore { setZone(zone) }
        restore { setTime(wall + SystemClock.elapsedRealtime() - elapsed) }
        for ((key, value) in listOf("auto_time" to autoTime, "auto_time_zone" to autoZone)) {
            restore {
                shell(if (value == "null") "settings delete global $key" else "settings put global $key $value")
                assertEquals("Restore $key", value, shell("settings get global $key").trim())
            }
        }
        restore {
            assertEquals(zone, shell("getprop persist.sys.timezone").trim())
            assertEquals(zone, ZoneId.systemDefault().id)
            assertTrue("Restore wall time", kotlin.math.abs(System.currentTimeMillis() - (wall + SystemClock.elapsedRealtime() - elapsed)) < 5000)
            Log.i("SystemTimeFixture", "RESTORED time/zone=$zone/auto_time=$autoTime/auto_time_zone=$autoZone")
        }
        failure?.let { throw it }
    }
}
