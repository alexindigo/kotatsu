package com.alexindigo.kotatsu.weather

import org.junit.Assert.*
import org.junit.Test

class WeatherHostFixtureInstrumentedTest {
    @Test fun failedTeardownBodyRestoresOriginalFetcherAfterSuccessfulDrain() {
        val original = RefreshJobService.fetcher
        try {
            // Do not introduce a real network worker while arranging a teardown failure.
            RefreshJobService.fetcher = { _, _, _ -> throw java.io.IOException("Fixture response") }
            val previous = RefreshJobService.fetcher
            val expected = AssertionError("Injected teardown body failure")
            val fixture = WeatherHostFixture { throw expected }
            RefreshJobService.fetcher = { _, _, _ -> throw java.io.IOException("Fixture fetcher leaked") }
            assertSame(expected, runCatching { fixture.close() }.exceptionOrNull())
            assertEquals("Real ingress cleanup must finish", 0, expected.suppressed.size)
            assertSame("Original fetcher must be restored even if drain fails", previous, RefreshJobService.fetcher)
        } finally {
            RefreshJobService.fetcher = original
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun failedCloseKeepsIngressClosedAndSurfacesDrainFailure() = assertFailedClose(false)

    @Test fun failedBodyAndCloseKeepIngressClosedAndPreserveBothFailures() = assertFailedClose(true)

    private fun assertFailedClose(failBody: Boolean) {
        val original = RefreshJobService.fetcher
        try {
            RefreshJobService.fetcher = { _, _, _ -> throw java.io.IOException("Original fetcher reopened") }
            val workerFailure = AssertionError("Injected controlled worker failure")
            val bodyFailure = AssertionError("Injected teardown body failure")
            var heldFetcher: ((Location, Boolean, String) -> String)? = null
            val fixture = WeatherHostFixture { ingress ->
                heldFetcher = RefreshJobService.fetcher
                // Exercise the real close/drain failure path after native workers go quiet.
                ingress.workerFailed(workerFailure)
                if (failBody) throw bodyFailure
            }
            val failure = runCatching { fixture.close() }.exceptionOrNull()
            assertNotNull("Failed drain must surface", failure)
            if (failBody) {
                assertSame(bodyFailure, failure)
                assertEquals(1, failure!!.suppressed.size)
                assertSame(workerFailure, failure.suppressed.single().cause)
            } else {
                assertSame(workerFailure, failure!!.cause)
                assertEquals(0, failure.suppressed.size)
            }
            assertSame("Failed drain must retain the held ingress", heldFetcher, RefreshJobService.fetcher)
            val rejected = runCatching {
                RefreshJobService.fetcher(Location("Test", "Fixture", 0.0, 0.0), false, "UTC")
            }.exceptionOrNull()
            assertTrue(rejected is java.io.IOException)
            assertEquals("Fixture owns refresh ingress", rejected!!.message)
        } finally {
            // Only a recorded worker error was injected; native workers really finished.
            RefreshJobService.fetcher = original
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.dropShellPermissionIdentity()
        }
    }
}
