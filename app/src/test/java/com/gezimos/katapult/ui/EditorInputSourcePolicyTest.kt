package com.gezimos.katapult.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class EditorInputSourcePolicyTest {
    private val root = generateSequence(File(checkNotNull(System.getProperty("user.dir"))).absoluteFile) { it.parentFile }
        .first { File(it, "paper-ui/src/main").isDirectory }

    @Test fun launcherEditorsUseTheWindowOwnedAdapter() {
        val sources = File(root, "app/src/main").walkTopDown().filter { it.extension in setOf("kt", "java") }.toList()
        assertTrue(sources.isNotEmpty())
        assertEquals(emptyList<String>(), sources.filter { Regex("\\bBasicTextField\\s*\\(").containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).path })
        val uses = sources.sumOf { Regex("\\bPaperTextField\\s*\\(").findAll(it.readText()).count() }
        assertEquals("Message/side name, rename, search and interval must share the adapter", 4, uses)
    }
}
