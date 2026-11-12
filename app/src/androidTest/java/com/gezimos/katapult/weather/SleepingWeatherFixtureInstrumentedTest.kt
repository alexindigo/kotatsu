package com.gezimos.katapult.weather

import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.weather.api.Weather
import com.gezimos.katapult.weather.api.WeatherCondition
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class SleepingWeatherFixtureInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val expected = Weather(21, "C", WeatherCondition.FEW_CLOUDS, false)

    @Test fun consecutiveLifetimesBlockUntilTheirOwnReleaseInTheSameProviderProcess() {
        val worker = Executors.newSingleThreadExecutor()
        val process = checkNotNull(context.contentResolver.acquireContentProviderClient(SleepingWeatherFixture.URI))
        var previousId: String? = null
        var pid: Int? = null
        try {
            repeat(2) {
                SleepingWeatherFixture(context).use { provider ->
                    if (pid == null) pid = provider.providerPid else assertEquals(pid, provider.providerPid)
                    val result = worker.submit<Weather?> { provider.read() }
                    provider.awaitBlocked()
                    assertFalse("An unreleased lifetime returned a query", result.isDone)
                    previousId?.let { old ->
                        val stale = checkNotNull(context.contentResolver.call(SleepingWeatherFixture.URI, "release", old, null))
                        assertFalse("An old lifetime must not release the current query", stale.getBoolean("active"))
                        provider.awaitBlocked()
                        assertFalse(result.isDone)
                    }
                    provider.release()
                    assertEquals(expected, result.get(15, TimeUnit.SECONDS))
                    previousId = provider.id
                }
                val state = SleepingWeatherFixture.status(context)
                assertEquals("Teardown must remove every owned gate/counter", 0, state.getInt("lifetimes"))
                assertEquals(pid, state.getInt("pid"))
            }
        } finally {
            try { worker.shutdown(); assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS)) }
            finally { process.close() }
        }
    }

    @Test fun exceptionalBodyReleasesDrainsAndResetsTheRemoteLifetime() {
        val worker = Executors.newSingleThreadExecutor()
        var query: Future<Weather?>? = null
        val expectedFailure = IllegalStateException("fixture body failed")
        try {
            val failure = runCatching {
                SleepingWeatherFixture(context).use { provider ->
                    query = worker.submit<Weather?> { provider.read() }
                    provider.awaitBlocked()
                    assertFalse(query!!.isDone)
                    throw expectedFailure
                }
            }.exceptionOrNull()
            assertSame(expectedFailure, failure)
            assertTrue("Teardown must not suppress a cleanup failure", expectedFailure.suppressed.isEmpty())
            assertEquals(expected, query!!.get(15, TimeUnit.SECONDS))
            assertEquals(0, SleepingWeatherFixture.status(context).getInt("lifetimes"))
        } finally { worker.shutdown(); assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS)) }
    }

    @Test fun releasingOneActiveLifetimeCannotCompleteAnotherLifetimesQuery() {
        val worker = Executors.newFixedThreadPool(2)
        try {
            SleepingWeatherFixture(context).use { first ->
                SleepingWeatherFixture(context).use { second ->
                    assertEquals(first.providerPid, second.providerPid)
                    val a = worker.submit<Weather?> { first.read() }
                    val b = worker.submit<Weather?> { second.read() }
                    first.awaitBlocked(); second.awaitBlocked()
                    first.release()
                    assertEquals(expected, a.get(15, TimeUnit.SECONDS))
                    second.awaitBlocked()
                    assertFalse("Another lifetime's valid release completed this query", b.isDone)
                    second.release()
                    assertEquals(expected, b.get(15, TimeUnit.SECONDS))
                }
            }
            assertEquals(0, SleepingWeatherFixture.status(context).getInt("lifetimes"))
        } finally { worker.shutdown(); assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS)) }
    }
}
