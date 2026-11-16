package com.gezimos.katapult

import org.junit.Assert.*
import org.junit.Test

class FixtureCleanupInstrumentedTest {
    @Test fun bodyFailureRemainsPrimaryAndEveryRestorationRuns() {
        val body = AssertionError("body")
        val first = AssertionError("first cleanup")
        val second = AssertionError("second cleanup")
        val restored = mutableListOf<Int>()
        val actual = runCatching {
            withFixtureCleanup(
                { restored.add(1); throw first },
                { restored.add(2); throw second },
                { restored.add(3) },
            ) { throw body }
        }.exceptionOrNull()
        assertSame(body, actual)
        assertEquals(listOf(first, second), body.suppressed.toList())
        assertEquals(listOf(1, 2, 3), restored)
    }

    @Test fun successfulBodyPropagatesCleanupFailureAfterAllRestorations() {
        val first = AssertionError("first cleanup")
        val second = AssertionError("second cleanup")
        var restored = false
        val actual = runCatching {
            withFixtureCleanup({ throw first }, { throw second }, { restored = true }) { 42 }
        }.exceptionOrNull()
        assertSame(first, actual)
        assertArrayEquals(arrayOf(second), first.suppressed)
        assertTrue(restored)
    }

    @Test fun successfulBodyReturnsItsValueAndCannotSuppressItself() {
        assertEquals(42, withFixtureCleanup({}) { 42 })
        val body = AssertionError("same failure")
        val actual = runCatching { withFixtureCleanup({ throw body }) { throw body } }.exceptionOrNull()
        assertSame(body, actual)
        assertTrue(body.suppressed.isEmpty())
    }
}
