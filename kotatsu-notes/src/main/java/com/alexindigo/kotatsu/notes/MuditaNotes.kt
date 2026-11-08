package com.alexindigo.kotatsu.notes

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri

data class TextNote(val id: Long, val title: String, val content: String)
interface TextNotesPort {
    fun textChoices(): List<TaskListChoice>?
    fun readText(id: Long): TextNote?
}

class MuditaNotes(private val resolver: ContentResolver) : NotesPort, TextNotesPort {
    override fun choices(): List<TaskListChoice>? = choices(TYPE)
    override fun textChoices(): List<TaskListChoice>? = choices("TEXT")
    private fun choices(type: String): List<TaskListChoice>? = resolver.query(URI, arrayOf("id", "title"),
        "note_type = ?", arrayOf(type), "updated_at DESC, id ASC")?.use { cursor ->
        buildList { while (cursor.moveToNext()) add(TaskListChoice(cursor.getLong(0), cursor.getString(1).orEmpty())) }
    }

    override fun readText(id: Long): TextNote? {
        if (id < 0) return null
        return resolver.query(URI, arrayOf("id", "title", "content"),
            "id = ? AND note_type = ?", arrayOf(id.toString(), "TEXT"), null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else TextNote(cursor.getLong(0), cursor.getString(1).orEmpty(),
                cursor.getString(2).orEmpty()).takeIf { it.id == id }
        }
    }

    override fun read(id: Long): TaskListSnapshot? {
        if (id < 0) return null
        return resolver.query(URI, arrayOf("id", "title", "content", "updated_at"),
            "id = ? AND note_type = ?", arrayOf(id.toString(), TYPE), null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else TaskListSnapshot(cursor.getLong(0), cursor.getString(1).orEmpty(),
                cursor.getString(2), cursor.getLong(3)).takeIf { it.id == id && it.items != null }
        }
    }

    override fun compareAndSet(old: TaskListSnapshot, content: String, updatedAt: Long): Int =
        resolver.update(URI, ContentValues().apply {
            put("content", content); put("updated_at", updatedAt)
        }, "id = ? AND note_type = ? AND content = ? AND updated_at = ?",
            arrayOf(old.id.toString(), TYPE, old.content, old.updatedAt.toString()))

    companion object {
        val URI: Uri = Uri.parse("content://com.mudita.notes/notes")
        const val TYPE = "CHECKLIST"
    }
}
