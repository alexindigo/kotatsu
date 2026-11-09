package com.gezimos.katapult.ui.settings

import org.junit.Assert.*
import org.junit.Test

class WholeRowListTest {
    @Test fun wholeRowsAreBottomAnchoredAndTheRailUsesTheirMeasuredRange() {
        assertEquals(WholeRowWindow(0, 1, 0, 64), wholeRowWindow(listOf(64), 600, null, 3))
        val heights = List(20) { 64 }
        assertEquals(WholeRowWindow(12, 20, 12, 533), wholeRowWindow(heights, 550, null, 3))
        assertEquals(WholeRowWindow(0, 8, 12, 533), wholeRowWindow(heights, 550, 0, 3))
        assertEquals(WholeRowWindow(12, 20, 12, 533), wholeRowWindow(heights, 550, 100, 3))
    }
    @Test fun varyingHeightsNeverLeaveAPartialTopRowAndResizeRecomputesCapacity() {
        val heights = listOf(60, 100, 75, 90)
        assertEquals(WholeRowWindow(2, 4, 2, 168), wholeRowWindow(heights, 200, null, 3))
        assertEquals(WholeRowWindow(0, 2, 2, 163), wholeRowWindow(heights, 200, 0, 3))
        assertEquals(WholeRowWindow(0, 4, 0, 334), wholeRowWindow(heights, 400, 2, 3))
        assertEquals(WholeRowWindow(0, 0, 0, 0), wholeRowWindow(emptyList(), 400, null, 3))
    }
}
