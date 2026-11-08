package com.alexindigo.kotatsu.notes

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/** Selections survive direct boot. Notes content never leaves credential-protected storage. */
class NotesStore(context: Context) {
    private val selections = context.createDeviceProtectedStorageContext().getSharedPreferences("notes-selection", 0)
    private val app = context.applicationContext
    private fun renderings() = app.getSharedPreferences("notes-renderings", 0)

    fun selected(id: Int): Long? = if (selections.contains("widget_$id"))
        selections.getLong("widget_$id", -1).takeIf { it >= 0 } else null

    fun select(id: Int, noteId: Long): Boolean = noteId >= 0 && selections.edit().putLong("widget_$id", noteId).commit()

    fun rendered(id: Int): RenderedList? = runCatching {
        val json = JSONObject(renderings().getString("widget_$id", null) ?: return null)
        RenderedList(json.getString("token"), TaskListSnapshot(json.getLong("id"), json.getString("title"),
            json.getString("content"), json.getLong("updated_at"))).takeIf { it.snapshot.items != null }
    }.getOrNull()

    fun remember(id: Int, snapshot: TaskListSnapshot): RenderedList {
        val previous = rendered(id)
        if (previous?.snapshot == snapshot) return previous
        val rendered = RenderedList(UUID.randomUUID().toString(), snapshot)
        check(renderings().edit().putString("widget_$id", JSONObject().apply {
            put("token", rendered.token); put("id", snapshot.id); put("title", snapshot.title)
            put("content", snapshot.content); put("updated_at", snapshot.updatedAt)
        }.toString()).commit()) { "Could not retain rendered snapshot" }
        return rendered
    }

    fun forget(id: Int) { check(renderings().edit().remove("widget_$id").commit()) }
    fun delete(id: Int, unlocked: Boolean) {
        check(selections.edit().remove("widget_$id").commit())
        if (unlocked) forget(id)
    }
    fun restore(old: Int, new: Int) { selected(old)?.let { check(select(new, it)) }; delete(old, false) }
}
