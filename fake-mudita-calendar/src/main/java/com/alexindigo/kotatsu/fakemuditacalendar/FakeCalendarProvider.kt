package com.alexindigo.kotatsu.fakemuditacalendar

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Test-only 1.2.1 model. No notifications, and no row queries or partial updates. */
class FakeCalendarProvider : ContentProvider() {
    private val rows = linkedMapOf<Long, ContentValues>()
    private var nextId = 1L
    private var schema2 = false
    private var failInsert = false
    private var failQuery = false
    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI("com.mudita.calendar", "events", 1); addURI("com.mudita.calendar", "events/#", 2)
    }
    override fun onCreate() = true
    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.mudita.event"
    @Synchronized override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (failQuery) { failQuery = false; error("Injected provider query failure") }
        require(matcher.match(uri) == 1) { "Unknown URI: $uri" }
        val columns = COLUMNS.copyOf().apply { if (schema2) this[2] = "description" }
        return MatrixCursor(columns).apply {
            for ((_, row) in rows) addRow(COLUMNS.map { row.get(it) }.toTypedArray())
        }
    }
    @Synchronized override fun insert(uri: Uri, values: ContentValues?): Uri? {
        require(matcher.match(uri) == 1) { "Unknown URI: $uri" }
        val model = model(values) ?: return null
        if (failInsert) { failInsert = false; return null }
        val id = nextId++; model.put("id", id); rows[id] = model
        return ContentUris.withAppendedId(uri, id)
    }
    @Synchronized override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        require(matcher.match(uri) == 2) { "Unknown URI: $uri" }
        val model = model(values) ?: return 0
        // Real update uses the ContentValues id, not the numeric route or selection.
        val id = model.getAsLong("id")
        if (id !in rows) return 0
        rows[id] = model
        return 1
    }
    @Synchronized override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = when (matcher.match(uri)) {
        1 -> rows.size.also { rows.clear() }
        2 -> if (rows.remove(ContentUris.parseId(uri)) != null) 1 else 0
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }
    @Synchronized override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (BuildConfig.DEBUG && method == "schema") { schema2 = extras?.getBoolean("schema2", false) ?: false; return Bundle.EMPTY }
        if (BuildConfig.DEBUG && method == "failInsert") { failInsert = true; return Bundle.EMPTY }
        if (BuildConfig.DEBUG && method == "failQuery") { failQuery = true; return Bundle.EMPTY }
        return super.call(method, arg, extras)
    }
    private fun model(values: ContentValues?): ContentValues? {
        if (values == null || COLUMNS.filterNot { it == "reminder" }.any { values.get(it) == null }) return null
        val ids = JSONArray()
        val start = values.getAsLong("startDateTime")
        if (!values.getAsBoolean("isAllDayEvent") && start >= System.currentTimeMillis()) {
            ids.put(UUID.randomUUID().toString().hashCode())
            if (values.getAsBoolean("isPreReminderSet") && start - 300000 >= System.currentTimeMillis())
                ids.put(UUID.randomUUID().toString().hashCode())
        }
        return ContentValues(values).apply {
            put("isAllDayEvent", if (values.getAsBoolean("isAllDayEvent")) 1 else 0)
            put("isPreReminderSet", if (values.getAsBoolean("isPreReminderSet")) 1 else 0)
            // Gson's reflected H3.f fields are alarm-enabled and generated alarm ids.
            put("reminder", JSONObject().put("d", ids.length() > 0).put("e", ids).toString())
        }
    }
    companion object {
        private val COLUMNS = arrayOf("id", "title", "desc", "startDateTime", "endDateTime", "isAllDayEvent", "reminder", "isPreReminderSet")
    }
}
