package com.alexindigo.kotatsu.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class BridgeStore(context: Context) : SQLiteOpenHelper(context, "calendar-bridge.db", null, 1), MappingStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pairs (mudita_id INTEGER PRIMARY KEY, event_id INTEGER NOT NULL, instance_start INTEGER, calendar_id INTEGER NOT NULL, mudita_fp TEXT NOT NULL, standard_fp TEXT NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX event_pair ON pairs(event_id, COALESCE(instance_start, -1))")
        db.execSQL("CREATE TABLE state (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported bridge schema upgrade")
    override fun all(): List<Mapping> = readableDatabase.rawQuery("SELECT * FROM pairs ORDER BY mudita_id", null).use { c ->
        buildList { while (c.moveToNext()) add(Mapping(c.getLong(0), EventKey(c.getLong(1),
            if (c.isNull(2)) null else c.getLong(2)), c.getLong(3), c.getString(4), c.getString(5))) }
    }
    override fun save(mapping: Mapping) {
        val values = ContentValues().apply {
            put("mudita_id", mapping.muditaId); put("event_id", mapping.standardKey.eventId)
            put("instance_start", mapping.standardKey.instanceStart); put("calendar_id", mapping.calendarId)
            put("mudita_fp", mapping.muditaFingerprint); put("standard_fp", mapping.standardFingerprint)
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (db.update("pairs", values, "mudita_id = ?", arrayOf(mapping.muditaId.toString())) == 0)
                db.insertOrThrow("pairs", null, values)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    override fun remove(muditaId: Long) { writableDatabase.delete("pairs", "mudita_id = ?", arrayOf(muditaId.toString())) }
    private fun get(name: String): String? = readableDatabase.rawQuery("SELECT value FROM state WHERE name = ?", arrayOf(name)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    private fun set(name: String, value: String) {
        writableDatabase.insertWithOnConflict("state", null, ContentValues().apply {
            put("name", name); put("value", value)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    override var firstRunDone: Boolean
        get() = get("first_run_done") == "true"
        set(value) = set("first_run_done", value.toString())
    override var status: String
        get() = get("status").orEmpty()
        set(value) = set("status", value)
}

class PreferenceSyncConfig(context: Context) : SyncConfig {
    private val prefs = context.getSharedPreferences("calendar-sync", Context.MODE_PRIVATE)
    override fun read(): SyncSettings = SyncSettings(
        prefs.getStringSet("calendars", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
        if (prefs.contains("new_event_calendar")) prefs.getLong("new_event_calendar", -1) else null,
        prefs.getInt("horizon_weeks", 8),
    )
    fun write(settings: SyncSettings) {
        prefs.edit().putStringSet("calendars", settings.calendars.map { it.toString() }.toSet())
            .putInt("horizon_weeks", settings.horizonWeeks).apply {
                if (settings.newEventCalendar == null) remove("new_event_calendar")
                else putLong("new_event_calendar", settings.newEventCalendar)
            }.commit().also { check(it) { "Could not save calendar configuration" } }
    }
}
