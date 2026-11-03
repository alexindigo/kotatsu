package com.gezimos.katapult.lockscreen

import org.junit.Assert.*
import org.junit.Test

class LockscreenRootInspectionTest {
    private class Root(var observation: LockscreenRootInspection.Observation = LockscreenRootInspection.Observation(true, true, false)) {
        var refreshable = true
        var throwQuery = false
    }
    private class Fixture {
        var copies = 0
        var releases = 0
        var refreshes = 0
        val root = Root()
        val inspection = LockscreenRootInspection<Root>(
            copy = { copies++; it }, refresh = { refreshes++; it.refreshable },
            inspect = { if (it.throwQuery) error("Query failed") else it.observation }, release = { releases++ })
        fun seed() { assertFalse(inspection.covered(listOf(LockscreenRootInspection.Window(root, false, true, true)), false)) }
        fun ownOnly() = listOf<LockscreenRootInspection.Window<Root>>(
            LockscreenRootInspection.Window<Root>(null, true, true, true),
            LockscreenRootInspection.Window(null, false, false, false), // stale detached edge / passive system window
        )
    }

    @Test fun ownPageOmissionUsesFreshSystemUiQueriesNotTheLastEligibility() {
        val f = Fixture(); f.seed()
        assertFalse(f.inspection.covered(f.ownOnly(), false))
        assertFalse(f.inspection.covered(f.ownOnly(), false))
        assertEquals(2, f.refreshes)
        f.root.observation = f.root.observation.copy(pin = true)
        assertTrue(f.inspection.covered(f.ownOnly(), false))
        assertEquals(3, f.refreshes)
        assertEquals(1, f.releases)
        f.root.observation = f.root.observation.copy(pin = false)
        assertTrue("Rejected references cannot revive themselves", f.inspection.covered(f.ownOnly(), false))
    }

    @Test fun missingPanelOrFailedRefreshCannotKeepPageVisible() {
        val f = Fixture(); f.seed(); f.root.observation = f.root.observation.copy(panel = false)
        assertTrue(f.inspection.covered(f.ownOnly(), false))
        f.root.observation = f.root.observation.copy(panel = true); f.seed(); f.root.refreshable = false
        assertTrue(f.inspection.covered(f.ownOnly(), false))
        assertEquals(2, f.releases)
    }

    @Test fun enumerationOmissionWithoutAnIdentifiedFocusedOwnPageFailsClosed() {
        listOf(emptyList<LockscreenRootInspection.Window<Root>>(),
            listOf(LockscreenRootInspection.Window<Root>(null, true, false, false)),
            listOf(LockscreenRootInspection.Window<Root>(null, false, true, true))).forEach { snapshot ->
            val f = Fixture(); f.seed()
            assertTrue(f.inspection.covered(snapshot, false))
            assertEquals(0, f.refreshes)
            assertEquals(1, f.releases)
        }
    }

    @Test fun foreignApplicationsOrFocusedSystemWindowsNeverUseRetainedRoot() {
        listOf(
            LockscreenRootInspection.Window<Root>(null, false, false, false, application = true),
            LockscreenRootInspection.Window<Root>(null, false, true, false),
            LockscreenRootInspection.Window<Root>(null, false, false, true),
            LockscreenRootInspection.Window(Root(LockscreenRootInspection.Observation(false, false, false)), false, true, true, true),
        ).forEach { foreign ->
            val f = Fixture(); f.seed()
            assertTrue(f.inspection.covered(f.ownOnly() + foreign, false))
            assertEquals(0, f.refreshes)
        }
    }

    @Test fun pinFocusAndCurrentPinRootAlwaysReject() {
        val f = Fixture(); f.seed()
        assertTrue(f.inspection.covered(f.ownOnly(), true))
        f.seed()
        val pin = Root(LockscreenRootInspection.Observation(true, true, true))
        assertTrue(f.inspection.covered(f.ownOnly() + LockscreenRootInspection.Window(pin, false, true, true), false))
        assertEquals(0, f.refreshes)
    }

    @Test fun passiveSystemUiChromeCanCoexistWithTheFocusedOwnPage() {
        val f = Fixture(); f.seed()
        val chrome = Root(LockscreenRootInspection.Observation(true, false, false))
        assertFalse(f.inspection.covered(listOf(f.ownOnly().first(), LockscreenRootInspection.Window(chrome, false, false, false)), false))
        assertEquals(1, f.refreshes)
    }

    @Test fun queryExceptionsClearTheReferenceAndDemandTeardownReleasesIt() {
        val f = Fixture(); f.seed(); f.root.throwQuery = true
        assertTrue(f.inspection.covered(f.ownOnly(), false))
        assertEquals(1, f.releases)
        f.root.throwQuery = false; f.seed(); f.inspection.close()
        assertEquals(2, f.releases)
        assertTrue(f.inspection.covered(f.ownOnly(), false))
    }
}
