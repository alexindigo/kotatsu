package com.gezimos.katapult.ui.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsModelTest {
    @Test fun footerCapacityBelongsOnlyToItsCandidatePage() {
        val counts = listOf(3, 2, 3, 1)
        val heights = listOf(239, 180, 230, 90)
        // The five-row first page has the ordinary pager, the second owns the Notice.
        assertEquals(listOf(0..1, 2..3), paginateGroups(counts, heights) { range ->
            if (2 in range) 411 else 444
        })
        // A genuinely taller Notice can move whole groups, never overlap the rows.
        assertEquals(listOf(0..1, 2..2, 3..3), paginateGroups(counts, heights) { range ->
            if (2 in range) 250 else 444
        })
    }

    @Test fun chaptersKeepBalancedWholeGroupPagesWhenNoticeIsDismissed() {
        // A tall viewport must not turn Screensaver 4/4 or System 4/3 into an arbitrary
        // short first page just because several equally small page-count solutions fit.
        assertEquals(listOf(0..1, 2..3), paginateGroups(listOf(3, 1, 3, 1), listOf(230, 90, 230, 90), 450))
        assertEquals(listOf(0..1, 2..3), paginateGroups(listOf(2, 2, 1, 2), listOf(160, 160, 90, 160), 450))
        assertEquals(listOf(0..1, 2..3), paginateGroups(listOf(3, 2, 3, 1), listOf(230, 160, 230, 90), 450))
    }

    @Test fun appearanceKeepsIconsApartFromScreenAndEink() {
        assertEquals(listOf(0..0, 1..2), paginateGroups(listOf(3, 3, 2), listOf(230, 230, 160), 411))
    }

    @Test fun sixEntryLimitAppliesEvenWhenThereIsRoom() {
        val counts = listOf(3, 2, 2, 4, 2)
        val pages = paginateGroups(counts, List(counts.size) { 1 }, 100)
        assertEquals(counts.indices.toList(), pages.flatMap { it.toList() })
        assertTrue(pages.all { page -> page.sumOf { counts[it] } <= 6 })
    }

    @Test fun avoidsAnOrphanWithoutSplittingAGroup() {
        assertEquals(listOf(0..0, 1..2), paginateGroups(listOf(5, 1, 1), listOf(5, 1, 1), 100))
    }

    @Test fun unavoidableSingletonRemainsReachable() {
        assertEquals(listOf(0..0, 1..1), paginateGroups(listOf(6, 1), listOf(10, 10), 100))
    }

    @Test fun measuredHeightMovesWholeGroupsAtLargerText() {
        assertEquals(listOf(0..0, 1..1, 2..2), paginateGroups(listOf(3, 3, 2), listOf(300, 300, 240), 411))
    }

    @Test fun aGroupTallerThanViewportIsNotLostOrSplit() {
        assertEquals(listOf(0..0, 1..1), paginateGroups(listOf(3, 2), listOf(900, 100), 400))
    }

    @Test fun emptyCategoryHasNoPhantomPage() {
        assertTrue(paginateGroups(emptyList(), emptyList(), 400).isEmpty())
    }

    @Test fun noticeDismissalSurvivesRepeatedOffObservations() {
        val initial = ActionServiceNoticeState(false, false)
        assertTrue(initial.visible)
        val dismissed = initial.dismiss()
        assertFalse(dismissed.visible)
        assertFalse(dismissed.observe(false).observe(false).visible)
        val on = dismissed.observe(true)
        assertFalse(on.visible)
        assertFalse(on.dismissed)
        assertTrue(on.observe(false).visible)
        val changed = on.observe(false)
        assertTrue(changed.announcementPending)
        assertFalse(changed.announced().observe(false).announcementPending)
    }

    @Test fun persistedDismissalIsRespectedOnRecreation() {
        assertFalse(ActionServiceNoticeState(false, true).visible)
        assertFalse(ActionServiceNoticeState(false, true).observe(true).dismissed)
    }

    @Test fun summariesUseTheActualValuesIncludingAnOffDock() {
        assertEquals("Circle · Dark · E-ink Reading",
            SettingsSummaries.appearance("%1\$s · %2\$s · E-ink %3\$s", "Circle", "Dark", "Reading"))
        assertEquals("Alarm off · Dock +0", SettingsSummaries.home("Alarm off · Dock +%1\$d", 0))
        assertEquals("Alarm on · Dock +3", SettingsSummaries.home("Alarm on · Dock +%1\$d", 3))
        assertEquals("Alarme désactivée · Dock +0", SettingsSummaries.home("Alarme désactivée · Dock +%1\$d", 0))
    }
}
