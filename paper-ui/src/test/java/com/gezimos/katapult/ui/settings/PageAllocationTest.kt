package com.gezimos.katapult.ui.settings

import org.junit.Assert.*
import org.junit.Test

class PageAllocationTest {
    @Test fun equalPageCountMustNotLeaveSpaceForTheNextWholeGroup() {
        // Pixel measurements from the real Edge swipes chapter at the emulator's
        // 360dp width, including each group's normal 9dp gap and the measured Notice.
        assertEquals(listOf(0..1, 2..3),
            paginateGroups(listOf(2, 2, 1, 1), listOf(282, 177, 170, 139), 512))
    }

    @Test fun aLaterSingletonMustNotPushAFittingGroupOffTheFirstPage() {
        assertEquals(listOf(0..1, 2..2), paginateGroups(listOf(5, 1, 1), listOf(250, 50, 50), 400))
    }

    @Test fun candidateNoticeAndWrappedHelpUseTheirActualMeasuredHeight() {
        val counts = listOf(2, 2, 1, 1)
        val heights = listOf(280, 170, 180, 150)
        assertEquals(listOf(0..1, 2..3), paginateGroups(counts, heights) { if (1 in it) 490 else 550 })
        // Enlarged/localized help must move the complete second group, including its heading.
        assertEquals(listOf(0..0, 1..2, 3..3),
            paginateGroups(counts, listOf(280, 300, 180, 150)) { if (1 in it) 490 else 550 })
    }

    @Test fun aBoundaryMeansTheNextBlockCannotFitWithinHeightAndEntryLimits() {
        for (capacity in listOf(0, 150, 250, 400, 600, 1000)) {
            val counts = listOf(2, 1, 3, 1, 2, 1)
            val heights = listOf(250, 90, 300, 80, 160, 100)
            val pages = paginateGroups(counts, heights, capacity)
            assertEquals(counts.indices.toList(), pages.flatMap { it.toList() })
            for (page in pages) {
                assertTrue(page.sumOf { counts[it] } <= 6)
                assertTrue(page.count() == 1 || page.sumOf { heights[it] } <= capacity)
                if (page.last < counts.lastIndex) {
                    val extended = page.first..page.last + 1
                    assertTrue("Deferred a fitting group: $page at $capacity",
                        extended.sumOf { counts[it] } > 6 || extended.sumOf { heights[it] } > capacity)
                }
            }
        }
    }
}
