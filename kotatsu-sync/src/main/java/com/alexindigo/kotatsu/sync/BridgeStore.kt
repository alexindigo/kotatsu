package com.alexindigo.kotatsu.sync

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class BridgeStore(context: Context) : SQLiteOpenHelper(context, "calendar-bridge.db", null, 2), MappingStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE pairs (mudita_id INTEGER PRIMARY KEY, event_id INTEGER NOT NULL, instance_start INTEGER, calendar_id INTEGER NOT NULL, mudita_fp TEXT NOT NULL, standard_fp TEXT NOT NULL, generated_location INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX event_pair ON pairs(event_id, COALESCE(instance_start, -1))")
        db.execSQL("CREATE TABLE state (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
        createIntents(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported bridge schema upgrade")
    private fun createIntents(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE write_intent (singleton INTEGER PRIMARY KEY CHECK(singleton = 1), payload TEXT NOT NULL)")
    }
    override fun all(): List<Mapping> = readableDatabase.rawQuery("SELECT * FROM pairs ORDER BY mudita_id", null).use { c ->
        buildList { while (c.moveToNext()) add(Mapping(c.getLong(0), EventKey(c.getLong(1),
            if (c.isNull(2)) null else c.getLong(2)), c.getLong(3), c.getString(4), c.getString(5),
            c.getInt(6) != 0)) }
    }
    override fun save(mapping: Mapping) {
        val values = ContentValues().apply {
            put("mudita_id", mapping.muditaId); put("event_id", mapping.standardKey.eventId)
            put("instance_start", mapping.standardKey.instanceStart); put("calendar_id", mapping.calendarId)
            put("mudita_fp", mapping.muditaFingerprint); put("standard_fp", mapping.standardFingerprint)
            put("generated_location", mapping.generatedLocation)
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
    override fun pending(): WriteIntent? = readableDatabase.rawQuery("SELECT payload FROM write_intent", null).use {
        if (it.moveToFirst()) IntentJson.decode(it.getString(0)) else null
    }
    override fun prepare(intent: WriteIntent) {
        val existing = pending()
        check(existing == null || existing.id == intent.id) { "A calendar write is already pending" }
        writableDatabase.insertWithOnConflict("write_intent", null, ContentValues().apply {
            put("singleton", 1); put("payload", IntentJson.encode(intent))
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }
    override fun acknowledge(intentId: String, mapping: Mapping?, removeMuditaId: Long?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(pending()?.id == intentId) { "Calendar write identity changed" }
            if (removeMuditaId != null) remove(removeMuditaId)
            if (mapping != null) save(mapping)
            db.delete("write_intent", "singleton = 1", null)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
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

/** Versioned by the owning database schema; no provider models or callbacks are serialized. */
private object IntentJson {
    fun encode(i: WriteIntent): String = JSONObject().apply {
        put("id", i.id); put("kind", i.kind.name); put("attempted", i.attempted); put("inserted", i.insertedId)
        put("phoneIds", JSONArray(i.phoneIdsBefore.toList()))
        put("mapping", i.mapping?.let { m -> JSONObject().apply {
            put("phone", m.muditaId); put("event", m.standardKey.eventId); put("instance", m.standardKey.instanceStart)
            put("calendar", m.calendarId); put("phoneFp", m.muditaFingerprint); put("standardFp", m.standardFingerprint)
            put("location", m.generatedLocation)
        } })
        put("phoneBefore", phone(i.phoneBefore)); put("phoneAfter", phone(i.phoneAfter))
        put("standardBefore", standard(i.standardBefore)); put("standardAfter", standard(i.standardAfter))
    }.toString()
    private fun phone(p: MuditaEvent?): JSONObject? = p?.let { JSONObject().apply {
        put("id", p.id); put("title", p.title); put("description", p.description)
        put("start", p.start); put("end", p.end); put("allDay", p.allDay); put("reminder", p.preReminder)
    } }
    private fun standard(s: StandardEvent?): JSONObject? = s?.let { JSONObject().apply {
        put("id", s.id); put("calendar", s.calendarId); put("title", s.title); put("description", s.description)
        put("location", s.location); put("start", s.start); put("end", s.end); put("allDay", s.allDay)
        put("recurring", s.recurring); put("zone", s.timeZone); put("instance", s.instanceStart)
    } }
    private fun JSONObject.longOrNull(key: String) = if (isNull(key)) null else getLong(key)
    private fun phone(p: JSONObject?) = p?.let { MuditaEvent(it.getLong("id"), it.getString("title"),
        it.getString("description"), it.getLong("start"), it.getLong("end"), it.getBoolean("allDay"), it.getBoolean("reminder")) }
    private fun standard(s: JSONObject?) = s?.let { StandardEvent(it.getLong("id"), it.getLong("calendar"),
        it.getString("title"), it.getString("description"), it.getString("location"), it.getLong("start"), it.getLong("end"),
        it.getBoolean("allDay"), it.getBoolean("recurring"), it.getString("zone"), it.longOrNull("instance")) }
    fun decode(value: String): WriteIntent {
        val j = JSONObject(value)
        val mapping = j.optJSONObject("mapping")?.let { Mapping(it.getLong("phone"), EventKey(it.getLong("event"),
            it.longOrNull("instance")), it.getLong("calendar"), it.getString("phoneFp"), it.getString("standardFp"),
            it.getBoolean("location")) }
        val ids = j.getJSONArray("phoneIds")
        return WriteIntent(j.getString("id"), WriteKind.valueOf(j.getString("kind")), mapping,
            phone(j.optJSONObject("phoneBefore")), phone(j.optJSONObject("phoneAfter")),
            standard(j.optJSONObject("standardBefore")), standard(j.optJSONObject("standardAfter")),
            (0 until ids.length()).map { ids.getLong(it) }.toSet(), j.getBoolean("attempted"), j.longOrNull("inserted"))
    }
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
