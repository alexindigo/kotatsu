package com.alexindigo.kotatsu.sync

import org.junit.Assert.*
import org.junit.Test

class SyncArmingTest {
    @Test fun registersOnlyMissingJobsAndIsIdempotent() {
        val pending = mutableSetOf(2)
        val scheduled = mutableListOf<Int>()
        fun arm() = SyncArming.ensure(listOf(1, 2, 3), pending::contains) { pending.add(it); scheduled.add(it) }
        arm(); arm()
        assertEquals(listOf(1, 3), scheduled)
        pending.remove(1); arm()
        assertEquals(listOf(1, 3, 1), scheduled)
    }
    @Test fun immediatePassIsAttemptedEvenWhenRecurringRegistrationFails() {
        var queued = false
        try { PassLifecycle.boot({ error("scheduler failed") }, { queued = true }); fail() }
        catch (_: IllegalStateException) { }
        assertTrue(queued)
    }
}
