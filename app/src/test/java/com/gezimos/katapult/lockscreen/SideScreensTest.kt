package com.gezimos.katapult.lockscreen
import com.gezimos.katapult.ui.settings.RailThumb
import com.gezimos.katapult.ui.settings.railThumb

import org.junit.Assert.*
import org.junit.Test

class SideScreensTest {
    @Test fun screenListRoundTripsNamesLayoutsAndDormantCells() {
        val screens = listOf(SideScreen(name = "A \"quote\"\\\n", slots = listOf(41, 42)),
            SideScreen("later", "Other", SideLayout.SPLIT, listOf(43, null)))
        assertEquals(screens, SideScreenList.decode(SideScreenList.encode(screens)))
        assertEquals(listOf(41), screens.first().placements)
        assertEquals(listOf(41, 42), screens.first().copy(layout = SideLayout.SPLIT).placements)
    }
    @Test fun corruptOrAliasedIdsNeverBecomePlacements() {
        listOf("bad", "{}", "[{\"id\":\"side\",\"name\":\"\",\"layout\":\"FULL\",\"slots\":[1,1]}]",
            "[{\"id\":\"side\",\"name\":\"\",\"layout\":\"FULL\",\"slots\":[-1,null]}]",
            "[{\"id\":\"side\",\"name\":\"\",\"layout\":\"FULL\",\"slots\":[1.5,null]}]").forEach {
            assertTrue(it, SideScreenList.decode(it).isEmpty())
        }
    }
    @Test fun collectionRailUsesVisibleFractionsAndDisappearsWhenContentFits() {
        assertNull(listRail(2, 0, 1, 0, 50, 100, 50, 500))
        assertEquals(ListRail(4000, 12000, 0), listRail(16, 0, 3, 0, 50, 200, 50, 200))
        assertEquals(ListRail(4000, 12000, 12000), listRail(16, 12, 15, 0, 50, 200, 50, 200))
        assertEquals(ListRail(4000, 12000, 500), listRail(16, 0, 4, -25, 50, 225, 50, 200))
    }
    @Test fun proportionalThumbAndMinimumUseTheSameBoundedTravel() {
        assertEquals(RailThumb(0f, 100f, 300f), railThumb(400f, 200, 600, 0, 24f))
        assertEquals(RailThumb(300f, 100f, 300f), railThumb(400f, 200, 600, 600, 24f))
        assertEquals(RailThumb(76f, 24f, 76f), railThumb(100f, 1, 999, 999, 24f))
    }
    @Test fun nativeEndpointsRemainExactWithFractionalVisibleRows() {
        val end = listRail(24, 16, 23, -55, 85, 625, 85, 625)!!
        assertEquals(end.maximum, end.position)
        val start = listRail(24, 0, 7, 0, 85, 680, 85, 625)!!
        assertEquals(0, start.position)
    }
    @Test fun sideScreenIsTheLeftDefaultAndCameraIsTheRightDefault() {
        assertEquals(EdgeAction.SIDE_SCREEN, EdgeAction.fromId("side_screen"))
        assertEquals(EdgeAction.SIDE_SCREEN, EdgeActionStore.defaultAction(ScreenEdge.LEFT))
        assertEquals(EdgeAction.CAMERA, EdgeActionStore.defaultAction(ScreenEdge.RIGHT))
    }
}
