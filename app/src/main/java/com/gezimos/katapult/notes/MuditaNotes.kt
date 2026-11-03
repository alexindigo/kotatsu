package com.gezimos.katapult.notes

import android.content.ContentResolver
import android.content.ContentValues
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper

class MuditaNotes(private val resolver: ContentResolver) : NotesPort {
    override fun choices(): List<TaskListChoice>? = resolver.query(URI, arrayOf("id", "title"),
        "note_type = ?", arrayOf(TYPE), "updated_at DESC, id ASC")?.use { cursor ->
        buildList { while (cursor.moveToNext()) add(TaskListChoice(cursor.getLong(0), cursor.getString(1).orEmpty())) }
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

    override fun observe(changed: () -> Unit): AutoCloseable {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = changed()
        }
        resolver.registerContentObserver(URI, true, observer)
        return AutoCloseable { resolver.unregisterContentObserver(observer) }
    }

    companion object {
        val URI: Uri = Uri.parse("content://com.mudita.notes/notes")
        const val TYPE = "CHECKLIST"
    }
}
