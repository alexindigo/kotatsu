package com.gezimos.katapult.util

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ImportFieldListTest {
    @Test fun everyDeclaredPreferenceKeyHasAnImportTypeOrLocalPreservation() {
        // Inspect the declaration inventory independently of the hand-maintained
        // import list, without constructing Android SharedPreferences in a JVM test.
        val relative = "src/main/java/com/gezimos/katapult/util/PrefsManager.kt"
        val source = listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
        val declared = Regex("const val (KEY_[A-Z_]+)\\s*=")
            .findAll(source).map { it.groupValues[1] }.toSet()
        val importTypes = source.substringAfter("private fun declaredImportDefault(key: String)")
            .substringBefore("private fun matchesStoredType")
        val localKeys = source.substringAfter("private val LOCAL_IMPORT_KEYS = setOf(").substringBefore(")")
        val covered = Regex("\\bKEY_[A-Z_]+\\b").findAll(importTypes + localKeys)
            .map { it.value }.toSet()
        assertTrue("Preference declaration inventory was empty", declared.isNotEmpty())
        assertEquals("Declared preferences missing from import types/local preservation", emptySet<String>(), declared - covered)
    }
}
