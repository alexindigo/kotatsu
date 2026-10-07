package com.gezimos.katapult.util

import org.junit.Assert.*
import org.junit.Test

class SensitivePolicyTest {
    @Test fun nineTrueDefaultsAreIndependentFromGrantsAndDemand() {
        assertEquals(9, SensitiveChoice.entries.size)
        assertTrue(SensitivePolicy.effectiveChoices(emptyMap<String, Any>()).values.all { it })
        for (choice in SensitiveChoice.entries) {
            val values = SensitivePolicy.effectiveChoices(mapOf(choice.key to false))
            assertFalse(values.getValue(choice.key))
            assertEquals(8, values.values.count { it })
        }
        for (choice in listOf(false, true)) for (supported in listOf(false, true))
            for (granted in listOf(false, true)) for (demand in listOf(false, true)) {
                assertEquals(choice && supported && granted && demand,
                    SensitivePolicy.eligible(choice, supported, granted, demand))
            }
    }

    @Test fun everyPresentImportChoiceMustBeStrictlyBooleanBeforeMutation() {
        for (choice in SensitiveChoice.entries) {
            assertEquals(mapOf(choice.key to false), SensitivePolicy.importedChoices(mapOf(choice.key to false)))
            for (invalid in listOf<Any?>(null, "false", 0, 1, emptyList<String>(), emptyMap<String, Any>())) {
                try {
                    SensitivePolicy.importedChoices(mapOf(choice.key to invalid))
                    fail("Accepted ${choice.key} as $invalid")
                } catch (_: IllegalArgumentException) {}
            }
        }
    }

    @Test fun omissionUnconfirmedAndConcurrentChangesPreserveLocalOptOuts() {
        val local = SensitivePolicy.effectiveChoices(SensitiveChoice.entries.associate { it.key to false })
        assertEquals(local, SensitivePolicy.importChoices(local, emptyMap(), null))
        val incoming = SensitiveChoice.entries.associate { it.key to true }
        assertEquals(local, SensitivePolicy.importChoices(local, incoming, null))
        assertEquals(incoming, SensitivePolicy.importChoices(local, incoming, local))
        try {
            SensitivePolicy.importChoices(local, incoming, incoming)
            fail("Replayed a confirmation against changed local choices")
        } catch (_: IllegalArgumentException) {}
    }
}
