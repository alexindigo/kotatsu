package com.alexindigo.kotatsu.weather

import android.app.ActivityManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.rules.ExternalResource
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap

/** Editor tests own refresh ingress before binding a widget, through the last cleanup write.
 * A fetch latch alone is not a drain: ForecastRefresher persists AFTER fetch returns. */
internal class RefreshQuiescenceFixture : ExternalResource() {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("weather", 0)
    private lateinit var before: Map<String, *>
    private lateinit var ingress: HeldRefreshIngress

    override fun before() {
        ingress = HeldRefreshIngress()
        // Pre-existing real fetches must finish before taking the preference snapshot. New
        // fetches are already held and cannot publish into the test's deliberate revision.
        try {
            ingress.awaitPreviouslyRunningWorkers()
            before = prefs.all
        } catch (error: Throwable) {
            try { ingress.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }

    fun attemptRefreshWhileHeld(location: Location, fahrenheit: Boolean, reply: Parsed.Valid,
        now: java.time.Instant, zone: java.time.ZoneId) = ingress.attemptRefreshWhileHeld(location, fahrenheit, reply, now, zone)

    override fun after() {
        ingress.use {
            it.drain()
            val edit = prefs.edit().clear()
            before.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
            } }
            assertTrue(edit.commit())
            assertEquals("Editor fixture restores coordinate-keyed forecast/status too", before, prefs.all)
        }
    }
}

/** One owner of fetcher replacement and the complete native JobService worker lifetime.
 * Host fixtures acquire this only during teardown, so their real-job assertions are unchanged. */
internal class HeldRefreshIngress(
    private val previous: (Location, Boolean, String) -> String = RefreshJobService.fetcher,
) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val release = CountDownLatch(1)
    private val held = ConcurrentHashMap.newKeySet<Thread>()
    private val staged = mutableListOf<Thread>()
    private val replies = ConcurrentHashMap<Thread, String>()
    private val failures = RefreshDrainFailures()
    private var drained = false

    init {
        RefreshJobService.fetcher = { _, _, _ ->
            held.add(Thread.currentThread())
            var interrupted = false
            // Cancellation is not completion. Do not let an interrupt write failure status
            // into the revision owned by the editor while a native DOWN callback is running.
            while (true) {
                try { release.await(); break } catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
            replies[Thread.currentThread()] ?: throw IOException("Fixture owns refresh ingress")
        }
        WeatherRuntime.cancel(context)
    }

    private fun worker(stack: Array<StackTraceElement>) = stack.any {
        it.className == ForecastRefresher::class.java.name || it.className.startsWith(RefreshJobService::class.java.name)
    }

    private fun await(message: String, ready: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < end) {
            instrumentation.waitForIdleSync()
            if (ready()) return
            SystemClock.sleep(25)
        }
        throw AssertionError(message)
    }

    fun awaitPreviouslyRunningWorkers() = await("Previous refresh worker did not go quiet") {
        Thread.getAllStackTraces().none { (thread, stack) -> thread !in held && worker(stack) }
    }

    /** Force the diagnosed writer at the native DOWN publication boundary. Its real refresh
     * must reach the owned ingress, but cannot replace the asserted HTTP503 revision. */
    fun attemptRefreshWhileHeld(location: Location, fahrenheit: Boolean, reply: Parsed.Valid,
        now: java.time.Instant, zone: java.time.ZoneId) {
        val hourly = org.json.JSONObject()
        for (key in listOf("time", "temperature_2m", "weather_code", "wind_gusts_10m")) {
            hourly.put(key, org.json.JSONArray().apply { reply.series.samples.forEach { sample -> put(when (key) {
                "time" -> sample.instant.epochSecond
                "temperature_2m" -> sample.temperature
                "weather_code" -> sample.code ?: org.json.JSONObject.NULL
                else -> sample.gust ?: org.json.JSONObject.NULL
            }) } })
        }
        val thread = Thread({
            try {
                val response = RefreshJobService.fetcher(location, fahrenheit, zone.id)
                // Replay the investigation's exact same-coordinate 72-sample response. It
                // reaches persistence only after the test has completed DOWN/UP assertions.
                assertEquals(ForecastStore.Result.INCOMPLETE, ForecastStore(context).accept(
                    location, fahrenheit, OpenMeteoParser.parse(response), now, zone))
            }
            catch (error: Throwable) { workerFailed(error) }
        }, "fixture-competing-refresh")
        replies[thread] = org.json.JSONObject().put("hourly", hourly).toString()
        staged += thread
        thread.start()
        val end = SystemClock.elapsedRealtime() + 5000
        while (thread !in held && thread.isAlive && SystemClock.elapsedRealtime() < end) SystemClock.sleep(10)
        assertTrue("Competing refresh reached the fixture-owned ingress", thread in held)
        assertTrue("Competing reply cannot finish inside DOWN publication", thread.isAlive)
    }

    internal fun workerFailed(error: Throwable) { failures.record(error) }

    fun drain() {
        if (drained) return
        WeatherRuntime.cancel(context)
        release.countDown()
        // Cancellation can return before onStopJob/onDestroy or a fetch's final status write.
        // Observe service destruction (executor.shutdownNow) AND the end of every refresh
        // stack, including the worker's jobFinished/reconcile post, before restoring state.
        failures.drain {
            await("Refresh service/worker did not finish before fixture restoration") {
                // Android O+ keeps this public debug API for the caller's own services.
                @Suppress("DEPRECATION")
                val services = context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
                services.none { it.service.className == RefreshJobService::class.java.name } &&
                    Thread.getAllStackTraces().values.none(::worker)
            }
            instrumentation.waitForIdleSync()
            WeatherRuntime.cancel(context)
            staged.forEach { it.join(5000); assertFalse("Controlled refresh worker finished", it.isAlive) }
            instrumentation.waitForIdleSync()
        }
        drained = true
    }

    override fun close() {
        // If drain fails, keep the controlled seam rather than reopen real ingress over dirty
        // fixture state. A failed teardown must fail the run, not silently leak a late writer.
        drain()
        RefreshJobService.fetcher = previous
    }
}

/** Collect worker errors independently of native quiescence, including when that wait fails. */
internal class RefreshDrainFailures {
    private val workers = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

    fun record(error: Throwable) { workers.add(error) }

    fun drain(quiesce: () -> Unit) {
        val quiescence = runCatching(quiesce).exceptionOrNull()
        val recorded = workers.toList()
        if (quiescence == null && recorded.isEmpty()) return
        val cause = recorded.firstOrNull() ?: quiescence!!
        throw AssertionError(if (quiescence == null) "Controlled refresh worker failed"
            else "Refresh ingress did not quiesce", cause).apply {
            recorded.drop(1).forEach(::addSuppressed)
            if (quiescence != null && quiescence !== cause) addSuppressed(quiescence)
        }
    }
}
