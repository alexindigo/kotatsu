package com.alexindigo.kotatsu.sync

import org.junit.Assert.*
import org.junit.Test

class PassLifecycleTest {
    @Test fun schedulingFailureStillCompletesOnceWithRetry() {
        val completions = mutableListOf<Boolean>()
        try { PassLifecycle.run({}, { error("Scheduler rejected successor") }, completions::add) }
        catch (_: IllegalStateException) { }
        assertEquals(listOf(true), completions)
    }
    @Test fun successfulSuccessorPrecedesCompletion() {
        val order = mutableListOf<String>()
        PassLifecycle.run({ order.add("pass") }, { order.add("successor") }, { order.add("finish:$it") })
        assertEquals(listOf("pass", "successor", "finish:false"), order)
    }
    @Test fun providerFailureStillArmsSuccessorAndRequestsRetry() {
        val order = mutableListOf<String>()
        PassLifecycle.run({ throw Exception("Provider batch failed") }, { order.add("successor") }, { order.add("finish:$it") })
        assertEquals(listOf("successor", "finish:true"), order)
    }
    @Test fun bootOnlySchedulesAndNeverRunsAProviderPassInBroadcast() {
        val scheduled = mutableListOf<String>()
        PassLifecycle.boot({ scheduled.add("recurring") }, { scheduled.add("boot") })
        assertEquals(listOf("recurring", "boot"), scheduled)
    }
    @Test fun recurringSchedulingFailureStillSchedulesTheBootPass() {
        var scheduledBoot = false
        try { PassLifecycle.boot({ error("Recurring rejected") }, { scheduledBoot = true }) }
        catch (_: IllegalStateException) { }
        assertTrue(scheduledBoot)
    }
}
