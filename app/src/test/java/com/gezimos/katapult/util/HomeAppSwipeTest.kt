package com.gezimos.katapult.util

import org.junit.Assert.*
import org.junit.Test

class HomeAppSwipeTest {
    @Test fun freshAndOmittedImportsShareOneDefault() {
        assertEquals(HomeAppSwipe.UP_AND_LEFT, HomeAppSwipe.DEFAULT)
        assertEquals(HomeAppSwipe.DEFAULT, HomeAppSwipe.resolve(null, false))
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
            assertEquals(choice in listOf(HomeAppSwipe.UP, HomeAppSwipe.UP_AND_LEFT), choice.opens(0f, -150f))
            assertEquals(choice in listOf(HomeAppSwipe.LEFT, HomeAppSwipe.UP_AND_LEFT), choice.opens(-150f, 0f))
            assertFalse(choice.opens(150f, 150f))
            assertFalse(choice.opens(-99f, -99f))
            assertFalse(choice.opens(-100f, -100f))
        }
    }
}
