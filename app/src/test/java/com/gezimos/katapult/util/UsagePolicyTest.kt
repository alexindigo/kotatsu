package com.gezimos.katapult.util

import org.junit.Assert.*
import org.junit.Test

class UsagePolicyTest {
    @Test fun optOutRevokeAndManualModeCannotReadCacheOrQuery() {
        for (reason in listOf("opt-out", "grant revocation", "manual mode")) {
            var allowed = true
            var queries = 0
            val cache = PolicyCache<List<Int>>(60_000)
            val query = { queries++; listOf(2, 1) }
            assertEquals(listOf(2, 1), cache.read(1, { allowed }, query))
            allowed = false
            val manual = listOf(1, 2)
            assertEquals(reason, manual, cache.read(2, { allowed }, query) ?: manual)
            assertEquals(1, queries)
            allowed = true
            assertEquals(listOf(2, 1), cache.read(3, { allowed }, query))
            assertEquals(2, queries)
        }
    }

    @Test fun invalidatedLateQueryCannotRepopulateEvenAfterReenable() {
        val cache = PolicyCache<Int>(60_000)
        assertNull(cache.read(1, { true }) { cache.invalidate(); 7 })
        assertEquals(0, cache.read(2, { true }) { 0 })
        assertNull(cache.read(3, { false }) { fail("Disabled query"); 9 })
    }
}
