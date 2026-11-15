package com.gezimos.katapult.util

import com.gezimos.katapult.ui.SwipeDirection
import com.gezimos.katapult.ui.SwipeGesture
import org.junit.Assert.*
import org.junit.Test

class HomeAppSwipeTest {
    @Test fun freshInstallKeepsItsIndependentDefault() {
        assertEquals(HomeAppSwipe.UP_AND_LEFT, HomeAppSwipe.DEFAULT)
        assertEquals(HomeAppSwipe.DEFAULT, HomeAppSwipe.resolve(null, false))
    }
    @Test fun backupsWithoutTheNewChoiceUseLegacyDefaultsForEveryOmittedKey() {
        for (enabled in listOf<Boolean?>(null, false, true)) for (vertical in listOf<Boolean?>(null, false, true)) {
            val expected = if (enabled == false) HomeAppSwipe.NONE else if (vertical == true) HomeAppSwipe.UP else HomeAppSwipe.LEFT
            assertEquals("Backup enabled=$enabled vertical=$vertical", expected, HomeAppSwipe.fromBackup(null, enabled, vertical))
        }
        for (choice in HomeAppSwipe.entries) {
            assertEquals(choice, HomeAppSwipe.fromBackup(choice.id, false, true))
        }
    }
    @Test fun allLegacyCombinationsKeepTheirDirection() {
        for (enabled in listOf(false, true)) for (vertical in listOf(false, true)) {
            assertEquals(if (!enabled) HomeAppSwipe.NONE else if (vertical) HomeAppSwipe.UP else HomeAppSwipe.LEFT,
                HomeAppSwipe.resolve(null, true, enabled, vertical))
        }
        assertEquals(HomeAppSwipe.LEFT, HomeAppSwipe.resolve(null, true))
        assertEquals(HomeAppSwipe.UP, HomeAppSwipe.resolve(null, true, vertical = true))
        assertEquals(HomeAppSwipe.NONE, HomeAppSwipe.resolve(null, true, enabled = false))
    }
    @Test fun explicitChoiceWinsOverLegacyAndEachDirectionIsIndependent() {
        for (choice in HomeAppSwipe.entries) {
            assertEquals(choice, HomeAppSwipe.resolve(choice.id, true, false, true))
            assertEquals(choice in listOf(HomeAppSwipe.UP, HomeAppSwipe.UP_AND_LEFT), choice.opens(SwipeDirection.UP))
            assertEquals(choice in listOf(HomeAppSwipe.LEFT, HomeAppSwipe.UP_AND_LEFT), choice.opens(SwipeDirection.LEFT))
            assertFalse(choice.opens(SwipeDirection.RIGHT))
            assertFalse(choice.opens(SwipeDirection.DOWN))
            assertFalse(choice.opens(null))
        }
    }
    @Test fun minorUpOrLeftTravelCannotOpenFromADifferentDominantDirection() {
        fun direction(dx: Float, dy: Float) = SwipeGesture(8f, 100f).finish(dx, dy)
        assertFalse(HomeAppSwipe.UP.opens(direction(-280f, -140f)))
        assertTrue(HomeAppSwipe.LEFT.opens(direction(-280f, -140f)))
        assertFalse(HomeAppSwipe.LEFT.opens(direction(-140f, -380f)))
        assertTrue(HomeAppSwipe.UP.opens(direction(-140f, -380f)))
        assertFalse(HomeAppSwipe.UP_AND_LEFT.opens(direction(280f, -140f)))
        assertFalse(HomeAppSwipe.UP_AND_LEFT.opens(direction(-140f, 380f)))
    }
}
