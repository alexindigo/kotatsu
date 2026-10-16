package com.gezimos.katapult.lockscreen

import org.junit.Assert.*
import org.junit.Test

class LockscreenWidgetsTest {
    @Test fun exactFitKeepsRequestedHeights() {
        assertEquals(listOf(64, 112), lockscreenBoxLayout(listOf(64, 112), 200))
    }

    @Test fun refusesInsteadOfShrinking() {
        assertNull(lockscreenBoxLayout(listOf(64, 112), 199))
        assertNull(lockscreenBoxLayout(listOf(160, 160, 160), 400))
    }

    @Test fun includesOnlyInterWidgetGapsAndBottomPadding() {
        assertEquals(listOf(64), lockscreenBoxLayout(listOf(64), 80))
        assertNull(lockscreenBoxLayout(listOf(64), 79))
        assertEquals(listOf(64, 64, 64), lockscreenBoxLayout(listOf(64, 64, 64), 224))
        assertNull(lockscreenBoxLayout(listOf(64, 64, 64), 223))
    }

    @Test fun emptyAndInvalidHeightsAreBounded() {
        assertEquals(emptyList<Int>(), lockscreenBoxLayout(emptyList(), 16))
        assertNull(lockscreenBoxLayout(listOf(0), 100))
        assertNull(lockscreenBoxLayout(listOf(-1), 100))
        assertNull(lockscreenBoxLayout(listOf(Int.MAX_VALUE, Int.MAX_VALUE), Int.MAX_VALUE))
    }

    @Test fun providerMinimumSelectsSmallestSufficientSizeOrLarge() {
        for ((minimum, expected) in listOf(0f to LockscreenWidgetSize.S, 64f to LockscreenWidgetSize.S,
            64.1f to LockscreenWidgetSize.M, 100f to LockscreenWidgetSize.M, 112f to LockscreenWidgetSize.M,
            112.1f to LockscreenWidgetSize.L, 200f to LockscreenWidgetSize.L)) {
            assertEquals(expected, defaultLockscreenWidgetSize(minimum))
        }
    }

    @Test fun jsonRoundTripPreservesOrderAndAllSizes() {
        val list = listOf(LockscreenWidget(80, LockscreenWidgetSize.L), LockscreenWidget(2, LockscreenWidgetSize.S),
            LockscreenWidget(9, LockscreenWidgetSize.M))
        assertEquals(list, LockscreenWidgetList.decode(LockscreenWidgetList.encode(list)))
        assertEquals("[]", LockscreenWidgetList.encode(emptyList()))
        assertEquals(emptyList<LockscreenWidget>(), LockscreenWidgetList.decode("[]"))
    }

    @Test fun badEntriesAreDroppedWithoutDiscardingValidNeighbors() {
        val json = """[{"id":9,"size":"M"},null,4,{},[],{"id":"3","size":"S"},{"id":0,"size":"S"},
            {"id":-1,"size":"L"},{"id":2.5,"size":"S"},{"id":2147483648,"size":"M"},
            {"id":3,"size":"XL"},{"id":4,"size":true},{"id":7,"size":"S"}]"""
        assertEquals(listOf(LockscreenWidget(9, LockscreenWidgetSize.M), LockscreenWidget(7, LockscreenWidgetSize.S)),
            LockscreenWidgetList.decode(json))
    }

    @Test fun duplicateIdsCannotCreateTwoHostViews() {
        assertEquals(listOf(LockscreenWidget(3, LockscreenWidgetSize.S)),
            LockscreenWidgetList.decode("""[{"id":3,"size":"S"},{"id":3,"size":"L"}]"""))
    }

    @Test fun malformedDocumentsHaveNoHostIds() {
        for (json in listOf("", "broken", "{}", "null", "[", "[{", "[{\"id\":1}] trailing", "[1,]")) {
            assertTrue(json, LockscreenWidgetList.decode(json).isEmpty())
        }
    }

    @Test fun jsonWhitespaceFieldOrderAndUnknownFieldsDoNotChangeIdentity() {
        assertEquals(listOf(LockscreenWidget(42, LockscreenWidgetSize.L)), LockscreenWidgetList.decode(
            """[ { "size" : "L", "ignored": {"nested":[true,false,null,"a\\b"]}, "id" : 42 } ]"""))
    }
}
