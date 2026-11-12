package com.alexindigo.kotatsu.weather

import org.junit.Assert.*
import org.junit.Test

class WeatherHostFixtureInstrumentedTest {
    @Test fun failedDrainRestoresOriginalFetcherAndPreservesFailure() {
        val original = RefreshJobService.fetcher
        try {
            // Do not introduce a real network worker while arranging a teardown failure.
            RefreshJobService.fetcher = { _, _, _ -> throw java.io.IOException("Fixture response") }
            val previous = RefreshJobService.fetcher
            val expected = AssertionError("Injected drain failure")
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
}
