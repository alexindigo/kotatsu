package com.alexindigo.kotatsu.notes

import org.junit.Assert.*
import org.junit.Test

class TextTaskListTest {
    @Test fun asciiPunctuationInsideTextCannotBecomeAWritableMarker() {
        for (heading in listOf("--- divider", "-5\u00b0C", "*Important*")) {
            val source = "\t$heading\r\n\t[ ]\tBuy food\r\n"
            assertEquals(TaskItem(heading, false, TaskRowKind.HEADING), TextTaskList.rows(source)[0].item)
            assertNull(TextTaskList.toggle(source, 0))
            val checked = TextTaskList.toggle(source, 1)!!
            assertEquals(source.replace("[ ]", "[x]"), checked)
            assertEquals(source, TextTaskList.toggle(checked, 1))
        }
        for (marker in listOf("-", "*")) for (suffix in listOf("", " ", "\tTask", " Task")) {
            val source = "\t$marker$suffix\r\n"
            assertEquals(TaskRowKind.ITEM, TextTaskList.rows(source).single().item.kind)
            val checked = TextTaskList.toggle(source, 0)!!
            assertEquals("\t\u2713$suffix\r\n", checked)
            assertEquals("\t\u25e6$suffix\r\n", TextTaskList.toggle(checked, 0))
            assertEquals(checked, TextTaskList.toggle(TextTaskList.toggle(checked, 0)!!, 0))
        }
    }

    @Test fun markersTabsHeadingsAndBlankRuns() {
        val source = "Market\r\n\r\n \t\r\n\t\u25e6\tPotato\r\n • Fruit\n- Milk\n* Eggs\r· Tea\n[ ] Rice\n[x] Salt\n[X] Sugar\n✓ Bread"
        val rows = TextTaskList.rows(source).map { it.item }
        assertEquals(TaskItem("Market", false, TaskRowKind.HEADING), rows[0])
        assertEquals(TaskRowKind.GAP, rows[1].kind)
        assertEquals(listOf("Potato", "Fruit", "Milk", "Eggs", "Tea", "Rice", "Salt", "Sugar", "Bread"), rows.drop(2).map { it.text })
        assertEquals(List(6) { false } + List(3) { true }, rows.drop(2).map { it.checked })
    }

    @Test fun onlyTheMarkerRangeChangesAndCanonicalMarkersRoundTrip() {
        for (marker in listOf("\u25e6", "•", "-", "*", "·", "[ ]", "[x]", "[X]", "✓")) {
            val source = "Heading\r\n\r\n\t$marker\tPotato 5-6  \r\n\r\nOther heading\n\t- Other\n"
            val checked = marker in listOf("[x]", "[X]", "✓")
            val replacement = if (marker.startsWith("[")) { if (checked) "[ ]" else "[x]" } else if (checked) "\u25e6" else "✓"
            val changed = TextTaskList.toggle(source, 2)!!
            assertEquals(source.replaceRange(source.indexOf(marker), source.indexOf(marker) + marker.length, replacement), changed)
            assertEquals(!checked, TextTaskList.rows(changed)[2].item.checked)
            if (marker in listOf("\u25e6", "[ ]", "[x]", "✓")) assertEquals(source, TextTaskList.toggle(changed, 2))
            assertNull(TextTaskList.toggle(source, 0))
            assertNull(TextTaskList.toggle(source, 1))
        }
    }

    @Test fun textWritesUseTheSameCasReadbackAndRejectNonItemsOrChangedType() {
        val old = TaskListSnapshot(8, "Shopping", "Heading\n\n\t\u25e6\tPotato\r\n", 100, "TEXT")
        var current = old
        var writes = 0
        val notes = object : NotesPort {
            override fun choices() = emptyList<TaskListChoice>()
            override fun read(id: Long) = current
            override fun compareAndSet(old: TaskListSnapshot, content: String, updatedAt: Long): Int {
                writes++
                if (current != old) return 0
                current = old.copy(content = content, updatedAt = updatedAt)
                return 1
            }
        }
        val rendered = RenderedList("text", old)
        assertFalse(acceptsTick(8, rendered, WidgetTick("text", 0)))
        assertFalse(acceptsTick(8, rendered, WidgetTick("text", 1)))
        assertEquals(TaskWrite.FAILED, toggleTask(notes, old, 0, 200).outcome)
        assertEquals(0, writes)
        val result = tickRenderedList(notes, 8, rendered, WidgetTick("text", 2), 200)
        assertEquals(TaskWrite.ACCEPTED, result.outcome)
        assertEquals(old.content.replace("\u25e6", "✓"), result.persisted!!.content)
        assertEquals("TEXT", result.persisted.noteType)
        assertEquals(TaskWrite.STALE, tickRenderedList(notes, 8, rendered, WidgetTick("text", 2), 201).outcome)
        assertEquals(1, writes)
    }
}
