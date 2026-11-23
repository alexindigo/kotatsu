package com.alexindigo.kotatsu.sync

import android.content.Context
import org.json.JSONObject

class PreferenceSyncConfig(context: Context) : SyncConfig {
    private val prefs = context.getSharedPreferences("calendar-sync", Context.MODE_PRIVATE)
    override fun read(): SyncSettings {
        val id = prefs.getLong("new_event_calendar", -1).takeUnless { it == -1L }
        val identity = prefs.getString("new_event_identity", null)?.let { raw ->
            val j = JSONObject(raw)
            CalendarIdentity(j.getLong("id"), j.getString("name"), j.getString("type"),
                if (j.isNull("collection")) null else j.getString("collection"))
        }
        return SyncSettings(prefs.getStringSet("calendars", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
            id, prefs.getInt("horizon_weeks", 8), readTargetMode(prefs.getString("new_event_mode", null), id != null), identity)
    }
    fun write(settings: SyncSettings) {
        val explicit = settings.targetMode == TargetMode.EXPLICIT
        val identity = settings.targetIdentity?.takeIf { explicit }?.let { id -> JSONObject().apply {
            put("id", id.id); put("name", id.accountName); put("type", id.accountType); put("collection", id.collectionId)
        }.toString() }
        prefs.edit().putStringSet("calendars", settings.calendars.map { it.toString() }.toSet())
            .putInt("horizon_weeks", settings.horizonWeeks).putString("new_event_mode", settings.targetMode.name)
            .putString("new_event_identity", identity).apply {
                if (!explicit || settings.newEventCalendar == null) remove("new_event_calendar")
                else putLong("new_event_calendar", settings.newEventCalendar)
            }.commit().also { check(it) { "Could not save calendar configuration" } }
    }
}

// Older debug builds did not record intent. Preserve a saved destination as explicit.
internal fun readTargetMode(value: String?, saved: Boolean) = if (!saved || value == TargetMode.AUTOMATIC.name)
    TargetMode.AUTOMATIC else TargetMode.EXPLICIT
