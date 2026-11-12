package com.gezimos.katapult.ui.settings

import org.junit.Assert.*
import org.junit.Test

class ChoicePresentationTest {
    private fun entry(count: Int, appBuilt: Boolean = false) = SettingsEntry.Value("test", "Test", "", "0",
        List(count) { SettingsChoice(it.toString(), "Option $it") }, {}, "0", appBuiltChoices = appBuilt)

    @Test fun onlyShortNonemptyStaticListsUseSheets() {
        for (count in 0..8) {
            assertEquals("static $count", count in 1..4, entry(count).usesSheet)
            assertFalse("app-built $count", entry(count, true).usesSheet)
        }
    }

    @Test fun sheetParentAndFollowOnDestinationShareTheStack() {
        val stack = SettingsNavigation(listOf("root"))
        stack.push("chapter")
        stack.push("choice")
        stack.push("choice")
        assertEquals("chapter", stack.previous)
        stack.pop()
        stack.push("editor")
        assertEquals("chapter", stack.previous)
        stack.pop()
        assertEquals("chapter", stack.current)
        stack.pop()
        assertEquals("root", stack.current)
        assertFalse(stack.canGoBack)
    }
}
