package com.gezimos.katapult.lockscreen

import org.junit.Assert.*
import org.junit.Test

class LockscreenInspectionTest {
    private fun window(own: Boolean = false, active: Boolean = false, focused: Boolean = false) =
        LockscreenVisibility.InspectionWindow(own, active, focused)

    @Test fun focusedOwnPageCannotCoverTheBareLockscreenOrHideAnAlarm() {
        assertEquals(1, LockscreenVisibility.inspectionWindow(listOf(window(true, true, true), window())))
        assertEquals(1, LockscreenVisibility.inspectionWindow(listOf(window(true, true, true), window(active = true), window())))
        assertEquals(2, LockscreenVisibility.inspectionWindow(listOf(window(true), window(), window(focused = true))))
        assertEquals(0, LockscreenVisibility.inspectionWindow(listOf(window(active = true), window(true))))
        assertNull(LockscreenVisibility.inspectionWindow(listOf(window(true, true, true))))
        assertNull(LockscreenVisibility.inspectionWindow(emptyList()))
    }
}
