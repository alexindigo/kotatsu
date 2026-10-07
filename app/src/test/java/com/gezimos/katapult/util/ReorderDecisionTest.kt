package com.gezimos.katapult.util

import org.junit.Assert.*
import org.junit.Test

class ReorderDecisionTest {
    private val original = listOf("phone", "calendar", "settings")
    private val changed = listOf("settings", "calendar", "phone")

    @Test fun selectingWithoutSwappingIsClean() {
        assertFalse(ReorderBaseline(original, null).isDirty(original.toList()))
    }

    @Test fun reversedSwapsDoNotRequireSave() {
        val baseline = ReorderBaseline(original, null)
        assertTrue(baseline.isDirty(changed))
        assertFalse(baseline.isDirty(changed.reversed()))
        assertFalse(baseline.resolve(ReorderChoice.SAVE, original)!!.persist)
    }

    @Test fun dirtySaveUsesWorkingKeysAndExistingPersistenceRoute() {
        val result = ReorderBaseline(original, original).resolve(ReorderChoice.SAVE, changed)!!
        assertEquals(changed, result.keys)
        assertTrue(result.persist)
    }

    @Test fun discardReturnsEffectiveBaselineWithoutPreferenceWrite() {
        val result = ReorderBaseline(original, null).resolve(ReorderChoice.DISCARD, changed)!!
        assertEquals(original, result.keys)
        assertFalse(result.persist)
    }

    @Test fun cancelProvidesNoExitOrPersistenceDecision() {
        assertNull(ReorderBaseline(original, original).resolve(ReorderChoice.CANCEL, changed))
    }

    @Test fun absentAndPersistedOrdersRemainDistinct() {
        val absent = ReorderBaseline(original, null)
        val persisted = ReorderBaseline(original, emptyList())
        assertNull(absent.persistedKeys)
        assertEquals(emptyList<String>(), persisted.persistedKeys)
        assertFalse(absent.resolve(ReorderChoice.DISCARD, changed)!!.persist)
        assertFalse(persisted.resolve(ReorderChoice.DISCARD, changed)!!.persist)
    }

    @Test fun shortcutKeysAndDuplicateLabelsAreNotCollapsed() {
        val baseline = listOf("package", "package|shortcut-one", "package|shortcut-two")
        val swapped = listOf("package", "package|shortcut-two", "package|shortcut-one")
        assertTrue(ReorderBaseline(baseline, baseline).isDirty(swapped))
        assertEquals(swapped, ReorderBaseline(baseline, baseline).resolve(ReorderChoice.SAVE, swapped)!!.keys)
    }
}
