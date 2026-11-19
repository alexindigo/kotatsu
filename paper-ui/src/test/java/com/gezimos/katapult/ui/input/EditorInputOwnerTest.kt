package com.gezimos.katapult.ui.input

import org.junit.Assert.*
import org.junit.Test

class EditorInputOwnerTest {
    private val events = mutableListOf<String>()
    private val queued = mutableListOf<() -> Unit>()
    private val owner = EditorInputOwner({ queued += it }, { events += "hide" })
    private fun field(name: String, eligible: () -> Boolean = { true }) = owner.register(
        requestFocus = { events += "focus $name" }, clearFocus = { events += "clear $name" }, eligible = eligible)
    private fun drain() { while (queued.isNotEmpty()) queued.removeAt(0)() }

    @Test fun exitClearsBeforeHidingAndDoesNotRetainDraftState() {
        val a = field("a")
        owner.focused(a)
        owner.unregister(a)
        assertEquals(listOf("clear a"), events)
        drain()
        assertEquals(listOf("clear a", "hide"), events)
        assertFalse(owner.hasFocusedEditor)
    }

    @Test fun oldDisposalAndPostedHideCannotDisturbSuccessor() {
        val a = field("a"); val b = field("b")
        owner.focused(a)
        owner.blurred(a)
        owner.focused(b)
        owner.unregister(a)
        drain()
        assertTrue(events.isEmpty())
        assertTrue(owner.hasFocusedEditor)
        owner.unregister(b)
        drain()
        assertEquals(listOf("clear b", "hide"), events)
    }

    @Test fun blurDoesNotReentrantlyClearDuringHandoff() {
        val a = field("a")
        owner.focused(a)
        owner.blurred(a)
        assertTrue(events.isEmpty())
        drain()
        assertEquals(listOf("hide"), events)
    }

    @Test fun reentrantSuccessorClaimInvalidatesExitHide() {
        val b = field("b")
        val a = owner.register({}, { owner.focused(b) })
        owner.focused(a)
        owner.unregister(a)
        drain()
        assertTrue(owner.hasFocusedEditor)
        assertTrue(events.isEmpty())
    }

    @Test fun stoppedHostResumesOnlyStillRegisteredEligibleEditor() {
        var visible = true
        val a = field("a") { visible }
        owner.focused(a)
        owner.stop(); owner.stop()
        drain()
        assertEquals(listOf("clear a", "hide"), events)
        owner.resume()
        assertEquals("focus a", events.last())
        owner.focused(a)
        owner.stop()
        visible = false
        owner.resume()
        assertEquals(1, events.count { it == "focus a" })
    }

    @Test fun unregisteredStoppedEditorCannotReturn() {
        val a = field("a")
        owner.focused(a); owner.stop(); owner.unregister(a); owner.resume(); drain()
        assertFalse(events.any { it.startsWith("focus") })
    }

    @Test fun delayedPageRetirementCannotReleaseNewClaim() {
        val a = field("a"); val b = field("b")
        owner.focused(a)
        owner.retireAfterTransition()
        owner.focused(b)
        drain()
        assertTrue(owner.hasFocusedEditor)
        assertTrue(events.isEmpty())
    }

    @Test fun pageRetirementClearsAnEditorThatRemainsRegistered() {
        val a = field("a")
        owner.focused(a); owner.retireAfterTransition(); drain()
        assertEquals(listOf("clear a", "hide"), events)
        owner.stop(); owner.resume()
        assertFalse(events.any { it.startsWith("focus") })
    }

    @Test fun closeIsIdempotentAndInvalidatesPendingWork() {
        val a = field("a")
        owner.focused(a); owner.blurred(a)
        owner.close(); owner.close(); drain()
        assertEquals(listOf("hide"), events)
        owner.focused(a)
        assertFalse(owner.hasFocusedEditor)
    }

    @Test fun windowLifetimesAreIndependent() {
        var replacementHides = 0
        val replacement = EditorInputOwner({ queued += it }, { replacementHides++ })
        val a = field("old")
        val b = replacement.register({}, {})
        owner.focused(a); owner.close()
        replacement.focused(b); drain()
        assertTrue(replacement.hasFocusedEditor)
        assertEquals(0, replacementHides)
    }

    @Test fun unplacedEditorRetiresWithoutAPageIndexTransition() {
        val a = field("numeric")
        owner.focused(a)
        owner.retireIfUnplaced(a) { false }
        drain()
        assertEquals(listOf("clear numeric", "hide"), events)
    }

    @Test fun placementReportAndSuccessorProtectInputFromOldPagePass() {
        val a = field("a"); val b = field("b")
        var placed = false
        owner.focused(a)
        owner.retireIfUnplaced(a) { placed }
        placed = true
        drain()
        assertTrue(events.isEmpty())
        owner.retireIfUnplaced(a) { false }
        owner.focused(b)
        drain()
        assertTrue(events.isEmpty())
        assertTrue(owner.hasFocusedEditor)
    }
}
