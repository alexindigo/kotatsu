package com.gezimos.katapult.notes

import org.junit.Assert.*
import org.junit.Test

class TaskListTest {
    private val items = listOf(TaskItem("same\n\"text\"\\\t\u0000\u00e9\ud83d\ude42", false), TaskItem("same", true), TaskItem("same", false))
    private val old = TaskListSnapshot(42, "Shopping", TaskListJson.encode(items), 100)

    private class Fake(var current: TaskListSnapshot?) : NotesPort {
        var rows = 1
        var failWrite = false
        var failRead = false
        var writes = 0
        var reads = 0
        var written: String? = null
        override fun choices() = emptyList<TaskListChoice>()
        override fun read(id: Long): TaskListSnapshot? { reads++; if (failRead) error("unreadable"); return current }
        override fun compareAndSet(old: TaskListSnapshot, content: String, updatedAt: Long): Int {
            writes++; written = content
            if (failWrite) error("write denied")
            if (rows == 1) current = old.copy(content = content, updatedAt = updatedAt)
            return rows
        }
        override fun observe(changed: () -> Unit) = AutoCloseable {}
    }

    @Test fun roundTripPreservesEveryTextCharacterDuplicateAndPosition() {
        assertEquals(items, TaskListJson.decode(TaskListJson.encode(items)))
        assertEquals(emptyList<TaskItem>(), TaskListJson.decode(" [ ] \r\n"))
        assertEquals(listOf(TaskItem("a/b\b\u000c\r", true)), TaskListJson.decode("""[{"checked":true,"text":"a\/b\b\f\r"}]"""))
    }

    @Test fun malformedContentCannotBecomeAnEmptyWritableList() {
        listOf("", "null", "{}", "[", "[{}]", "[null]", "[1]", "[]x", "[{},]",
            """[{"text":"a","checked":"false"}]""", """[{"text":null,"checked":false}]""",
            """[{"text":"a","checked":false,"checked":true}]""", """[{"text":"a","checked":false,"id":1}]""",
            """[{"text":"\u+123","checked":false}]""", """[{"text":"\q","checked":false}]""",
            "[{\"text\":\"a\n\",\"checked\":false}]").forEach { assertNull(it, TaskListJson.decode(it)) }
    }

    @Test fun acceptedWriteChangesOnlyTheChosenDuplicateAndReturnsPersistedSnapshot() {
        val notes = Fake(old)
        val result = toggleTask(notes, old, 2, 200)
        assertEquals(TaskWrite.ACCEPTED, result.outcome)
        assertEquals(items.mapIndexed { i, item -> if (i == 2) item.copy(checked = true) else item }, result.persisted!!.items)
        assertEquals(200L, result.persisted.updatedAt)
        assertEquals(1, notes.reads)
        assertEquals(1, notes.writes)
    }

    @Test fun timestampOnlyConflictReconcilesUnchangedContentRatherThanOptimisticCheck() {
        val current = old.copy(updatedAt = 101)
        val notes = Fake(current).apply { rows = 0 }
        val result = toggleTask(notes, old, 0, 200)
        assertEquals(TaskWrite.STALE, result.outcome)
        assertEquals(current, result.persisted)
        assertFalse(result.persisted!!.items!![0].checked)
    }

    @Test fun conflictDoesNotRebaseIndexOntoNewTextOrOrder() {
        val fresh = old.copy(content = TaskListJson.encode(items.reversed()), updatedAt = 101)
        val notes = Fake(fresh).apply { rows = 0 }
        assertEquals(fresh, toggleTask(notes, old, 2, 200).persisted)
        assertEquals(1, notes.writes)
    }

    @Test fun deletionOrTypeChangeNeverFallsBackToAnotherList() {
        val notes = Fake(null).apply { rows = 0 }
        assertNull(toggleTask(notes, old, 0, 200).persisted)
        notes.current = old.copy(id = 43)
        assertNull(toggleTask(notes, old, 0, 200).persisted)
    }

    @Test fun writeFailureStillReadsBackAndReadFailureShowsUnavailable() {
        val notes = Fake(old).apply { failWrite = true }
        val failed = toggleTask(notes, old, 0, 200)
        assertEquals(TaskWrite.FAILED, failed.outcome)
        assertEquals(old, failed.persisted)
        notes.failRead = true
        assertNull(toggleTask(notes, old, 0, 200).persisted)
    }

    @Test fun invalidIndexOrJsonCannotWriteAndStillReconciles() {
        val notes = Fake(old)
        listOf(-1, 3).forEach { assertEquals(TaskWrite.FAILED, toggleTask(notes, old, it, 200).outcome) }
        assertEquals(TaskWrite.FAILED, toggleTask(notes, old.copy(content = "bad"), 0, 200).outcome)
        assertEquals(0, notes.writes)
        assertEquals(3, notes.reads)
    }

    @Test fun unexpectedRowCountAndMalformedReadBackAreNotSuccess() {
        val notes = Fake(old.copy(content = "bad")).apply { rows = 2 }
        val result = toggleTask(notes, old, 0, 200)
        assertEquals(TaskWrite.FAILED, result.outcome)
        assertNull(result.persisted)
    }

    @Test fun backwardClockCannotRecreateAnOlderCasVersionAfterCheckUncheck() {
        val notes = Fake(old)
        val first = toggleTask(notes, old, 0, 50).persisted!!
        val second = toggleTask(notes, first, 0, 50).persisted!!
        assertEquals(old.items, second.items)
        assertEquals(101L, first.updatedAt)
        assertEquals(102L, second.updatedAt)
    }

    @Test fun exhaustedTimestampCannotWrapAndStillReadsBack() {
        val exhausted = old.copy(updatedAt = Long.MAX_VALUE)
        val notes = Fake(exhausted)
        assertEquals(TaskToggleResult(TaskWrite.FAILED, exhausted), toggleTask(notes, exhausted, 0, 100))
        assertEquals(0, notes.writes)
    }
}
