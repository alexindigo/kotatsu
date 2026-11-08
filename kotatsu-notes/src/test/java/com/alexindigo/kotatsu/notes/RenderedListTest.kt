package com.alexindigo.kotatsu.notes

import org.junit.Assert.*
import org.junit.Test

class RenderedListTest {
    private val old = TaskListSnapshot(4, "Shopping", TaskListJson.encode(listOf(TaskItem("Milk", false), TaskItem("Milk", true))), 100)
    private val rendered = RenderedList("generation-a", old)
    private class Fake(var current: TaskListSnapshot?) : NotesPort {
        var writes = 0
        override fun choices() = emptyList<TaskListChoice>()
        override fun read(id: Long) = current?.takeIf { it.id == id }
        override fun compareAndSet(old: TaskListSnapshot, content: String, updatedAt: Long): Int {
            writes++
            if (current != old) return 0
            current = old.copy(content = content, updatedAt = updatedAt)
            return 1
        }
    }
    @Test fun onlyTheRenderedSelectionAndValidIndexCanTick() {
        assertTrue(acceptsTick(4, rendered, WidgetTick("generation-a", 0, true)))
        listOf(WidgetTick("old", 0), WidgetTick("generation-a", -1), WidgetTick("generation-a", 2),
            WidgetTick("generation-a", 0, false)).forEach { assertFalse(acceptsTick(4, rendered, it)) }
        assertFalse(acceptsTick(5, rendered, WidgetTick("generation-a", 0)))
        assertFalse(acceptsTick(null, rendered, WidgetTick("generation-a", 0)))
        assertFalse(acceptsTick(4, null, WidgetTick("generation-a", 0)))
    }
    @Test fun observerRefreshCannotRetargetAnOlderRow() {
        val fresh = old.copy(content = TaskListJson.encode(old.items!!.reversed()), updatedAt = 101)
        val notes = Fake(fresh)
        assertEquals(TaskToggleResult(TaskWrite.STALE, fresh), tickRenderedList(notes, 4, rendered, WidgetTick("generation-a", 0), 200))
        assertEquals(0, notes.writes)
    }
    @Test fun replacedRenderingOrSelectionDoesNotWrite() {
        val notes = Fake(old)
        tickRenderedList(notes, 4, rendered.copy(token = "generation-b"), WidgetTick("generation-a", 0), 200)
        tickRenderedList(notes, 5, rendered, WidgetTick("generation-a", 0), 200)
        assertEquals(0, notes.writes)
    }
    @Test fun validTickReturnsAuthoritativeReadback() {
        val notes = Fake(old)
        val result = tickRenderedList(notes, 4, rendered, WidgetTick("generation-a", 0, true), 200)
        assertEquals(TaskWrite.ACCEPTED, result.outcome)
        assertEquals(listOf(TaskItem("Milk", true), TaskItem("Milk", true)), result.persisted!!.items)
        assertEquals(1, notes.writes)
    }
    @Test fun notificationsRefreshAllAndOnlyAffectedSelections() {
        val selected = mapOf(10 to 4L, 11 to 5L, 12 to 4L, 13 to null)
        assertEquals(setOf(10, 12), affectedWidgets(selected, setOf(4)))
        assertEquals(selected.keys, affectedWidgets(selected, null))
        assertEquals(emptySet<Int>(), affectedWidgets(selected, emptySet()))
    }
    @Test fun iconRoutingDependsOnLiveWidgetCountAndSortsChoices() {
        assertEquals(SettingsTarget.NoWidget, settingsTarget(intArrayOf()))
        assertEquals(SettingsTarget.One(8), settingsTarget(intArrayOf(8)))
        assertEquals(SettingsTarget.Choose(listOf(3, 8)), settingsTarget(intArrayOf(8, 3)))
    }
}
