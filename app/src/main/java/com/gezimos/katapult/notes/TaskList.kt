package com.gezimos.katapult.notes

data class TaskItem(val text: String, val checked: Boolean)
data class TaskListSnapshot(val id: Long, val title: String, val content: String, val updatedAt: Long) {
    val items: List<TaskItem>? get() = TaskListJson.decode(content)
}
data class TaskListChoice(val id: Long, val title: String)

/** Strict, Android-independent codec. Bad content is unavailable, never an empty writable list. */
object TaskListJson {
    fun encode(items: List<TaskItem>): String = items.joinToString(",", "[", "]") {
        "{\"text\":${quote(it.text)},\"checked\":${it.checked}}"
    }

    fun decode(json: String): List<TaskItem>? = runCatching { Reader(json).read() }.getOrNull()

    private fun quote(text: String): String = buildString {
        append('"')
        text.forEach { c -> when (c) {
            '"', '\\' -> { append('\\'); append(c) }
            in '\u0000'..'\u001f' -> { append("\\u"); append(c.code.toString(16).padStart(4, '0')) }
            else -> append(c)
        } }
        append('"')
    }

    private class Reader(private val text: String) {
        private var index = 0
        private fun space() { while (index < text.length && text[index] in " \r\n\t") index++ }
        private fun peek(): Char { space(); return text[index] }
        private fun take(c: Char) { require(peek() == c); index++ }
        fun read(): List<TaskItem> {
            take('[')
            val rows = mutableListOf<TaskItem>()
            if (peek() != ']') while (true) {
                take('{')
                var label: String? = null
                var checked: Boolean? = null
                val keys = mutableSetOf<String>()
                while (true) {
                    val key = string(); require(keys.add(key)); take(':')
                    when (key) {
                        "text" -> label = string()
                        "checked" -> checked = boolean()
                        else -> error("Unknown checklist field")
                    }
                    if (peek() != ',') break
                    take(',')
                }
                take('}')
                rows += TaskItem(requireNotNull(label), requireNotNull(checked))
                if (peek() != ',') break
                take(',')
            }
            take(']'); space(); require(index == text.length)
            return rows
        }
        private fun boolean(): Boolean {
            space()
            val value = when {
                text.startsWith("true", index) -> true
                text.startsWith("false", index) -> false
                else -> error("Expected Boolean")
            }
            index += if (value) 4 else 5
            return value
        }
        private fun string(): String {
            take('"')
            return buildString {
                while (true) {
                    val c = text[index++]
                    if (c == '"') break
                    if (c != '\\') { require(c >= ' '); append(c); continue }
                    append(when (val escaped = text[index++]) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> text.substring(index, index + 4).also { require(it.all { c -> c in "0123456789abcdefABCDEF" }) }
                            .toInt(16).toChar().also { index += 4 }
                        else -> error("Invalid JSON escape")
                    })
                }
            }
        }
    }
}

/** Only the provider adapter knows Android. All callers execute this port on their I/O dispatcher. */
interface NotesPort {
    fun choices(): List<TaskListChoice>?
    fun read(id: Long): TaskListSnapshot?
    fun compareAndSet(old: TaskListSnapshot, content: String, updatedAt: Long): Int
    fun observe(changed: () -> Unit): AutoCloseable
}

enum class TaskWrite { ACCEPTED, STALE, FAILED }
data class TaskToggleResult(val outcome: TaskWrite, val persisted: TaskListSnapshot?)

/** A stale index is never applied to a newer document. Read-back, not row count, owns the displayed state. */
fun toggleTask(notes: NotesPort, old: TaskListSnapshot, index: Int, now: Long): TaskToggleResult {
    val items = old.items
    val outcome = if (items == null || index !in items.indices || old.updatedAt == Long.MAX_VALUE) TaskWrite.FAILED else try {
        val next = items.mapIndexed { i, item -> if (i == index) item.copy(checked = !item.checked) else item }
        // A backward clock must not let check/uncheck recreate an older CAS version (ABA).
        when (notes.compareAndSet(old, TaskListJson.encode(next), maxOf(now, old.updatedAt + 1))) {
            1 -> TaskWrite.ACCEPTED
            0 -> TaskWrite.STALE
            else -> TaskWrite.FAILED
        }
    } catch (_: RuntimeException) { TaskWrite.FAILED }
    val persisted = try { notes.read(old.id)?.takeIf { it.id == old.id && it.items != null } }
        catch (_: RuntimeException) { null }
    return TaskToggleResult(outcome, persisted)
}
