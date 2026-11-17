package com.alexindigo.kotatsu.notes

import com.gezimos.katapult.withFixtureCleanup
import org.junit.Assert.*
import org.junit.Test

class NotesFixtureCleanupInstrumentedTest {
    @Test fun cleanupRunsOnTheNotesLaneAndReturnsToTheTestThread() {
        val testThread = Thread.currentThread()
        var cleanupThread: Thread? = null
        notesFixtureCleanup({ cleanupThread = Thread.currentThread() })
        assertNotNull(cleanupThread)
        assertNotSame(testThread, cleanupThread)
        assertSame(testThread, Thread.currentThread())
    }

    @Test fun runtimeFailureReturnsUnwrappedAfterEveryCleanupIsAttempted() {
        val expected = IllegalStateException("Delete failed")
        var refreshed = false
        val failure = runCatching {
            notesFixtureCleanup({ throw expected }, { refreshed = true })
        }.exceptionOrNull()
        assertSame(expected, failure)
        assertTrue(expected.suppressed.isEmpty())
        assertTrue("Refresh is independent of deletion", refreshed)
    }

    @Test fun bodyFailureKeepsQueuedCleanupFailuresSuppressedAndRestoresRemainingState() {
        val body = AssertionError("Body failed")
        val deletion = IllegalStateException("Delete failed")
        val refresh = IllegalArgumentException("Refresh failed")
        var restored = false
        val failure = runCatching {
            withFixtureCleanup(
                { notesFixtureCleanup({ throw deletion }, { throw refresh }) },
                { restored = true },
            ) { throw body }
        }.exceptionOrNull()
        assertSame(body, failure)
        assertArrayEquals(arrayOf<Throwable>(deletion), body.suppressed)
        assertArrayEquals(arrayOf<Throwable>(refresh), deletion.suppressed)
        assertTrue(restored)
    }

    @Test fun assertionFailureAlsoReturnsUnwrappedAndLeavesTheLaneUsable() {
        val expected = AssertionError("Cleanup assertion failed")
        val failure = runCatching { notesFixtureCleanup({ throw expected }) }.exceptionOrNull()
        assertSame(expected, failure)
        var restored = false
        notesFixtureCleanup({ restored = true })
        assertTrue(restored)
    }
}
