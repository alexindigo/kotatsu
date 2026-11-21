package com.alexindigo.kotatsu.sync

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import java.time.Duration

/** Application writes, never sync-adapter writes: CalendarProvider owns dirty/deleted flags. */
class StandardCalendar(private val resolver: ContentResolver) : StandardCalendarPort {
    override fun calendars(): List<SyncCalendar> = query(Calendars.CONTENT_URI,
        arrayOf(Calendars._ID, Calendars.ACCOUNT_TYPE, Calendars.SYNC_EVENTS), null, null) { c ->
        SyncCalendar(c.getLong(0), c.getString(1).orEmpty(), c.getInt(2) != 0)
    }

    override fun events(calendarIds: Set<Long>): List<StandardEvent> {
        if (calendarIds.isEmpty()) return emptyList()
        return rawEvents("${Events.CALENDAR_ID} IN (${marks(calendarIds.size)}) AND ${Events.DELETED} = 0",
            calendarIds.map { it.toString() }.toTypedArray())
            .filter { it.originalId == null && it.status != Events.STATUS_CANCELED }.map { it.event }
    }

    override fun instances(calendarIds: Set<Long>, start: Long, end: Long): List<StandardEvent> {
        if (calendarIds.isEmpty()) return emptyList()
        return query(instanceUri(start, end), arrayOf(Instances.EVENT_ID, Instances.BEGIN, Instances.END),
            "${Instances.CALENDAR_ID} IN (${marks(calendarIds.size)})", calendarIds.map { it.toString() }.toTypedArray()) { c ->
            Triple(c.getLong(0), c.getLong(1), c.getLong(2))
        }.mapNotNull { (id, begin, finish) ->
            val raw = raw(id) ?: return@mapNotNull null
            if (raw.deleted || raw.status == Events.STATUS_CANCELED || !raw.event.recurring && raw.originalId == null) null
            else raw.event.copy(id = raw.originalId ?: id, start = begin, end = finish,
                recurring = true, instanceStart = raw.originalTime ?: begin)
        }
    }

    override fun read(key: EventKey): StandardEvent? {
        val base = raw(key.eventId)?.takeUnless { it.deleted || it.status == Events.STATUS_CANCELED } ?: return null
        val start = key.instanceStart ?: return base.event
        val exception = exception(key)
        if (exception != null) return exception.takeUnless { it.deleted || it.status == Events.STATUS_CANCELED }
            ?.event?.copy(id = key.eventId, recurring = true, instanceStart = start)
        return query(instanceUri(start, start + 1), arrayOf(Instances.BEGIN, Instances.END),
            "${Instances.EVENT_ID} = ? AND ${Instances.BEGIN} = ?", arrayOf(key.eventId.toString(), start.toString())) { c ->
            base.event.copy(start = c.getLong(0), end = c.getLong(1), instanceStart = start)
        }.singleOrNull()
    }

    override fun insert(calendarId: Long, event: StandardEvent): Long? =
        resolver.insert(Events.CONTENT_URI, fields(event).apply {
            put(Events.CALENDAR_ID, calendarId); put(Events.EVENT_TIMEZONE, if (event.allDay) "UTC" else event.timeZone)
        })?.let(ContentUris::parseId)

    override fun update(key: EventKey, event: StandardEvent): Boolean {
        if (key.instanceStart == null) return resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, key.eventId),
            fields(event, includeZone = true), null, null) == 1
        val existing = exception(key)
        if (existing != null) return resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, existing.event.id),
            fields(event, includeZone = true), null, null) == 1
        val original = raw(key.eventId) ?: return false
        val base = original.event
        if (original.hasRule) return insertRuleException(key, event, canceled = false)
        return resolver.insert(Events.CONTENT_URI, fields(event).apply {
            put(Events.CALENDAR_ID, base.calendarId); put(Events.EVENT_TIMEZONE, event.timeZone)
            put(Events.EVENT_LOCATION, base.location)
            put(Events.ORIGINAL_ID, key.eventId); put(Events.ORIGINAL_INSTANCE_TIME, key.instanceStart)
            put(Events.ORIGINAL_SYNC_ID, original.syncId)
            put(Events.ORIGINAL_ALL_DAY, base.allDay)
        }) != null
    }

    override fun delete(key: EventKey): Boolean {
        if (key.instanceStart == null) return resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, key.eventId), null, null) == 1
        val existing = exception(key)
        if (existing != null) return resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, existing.event.id),
            ContentValues().apply { put(Events.STATUS, Events.STATUS_CANCELED) }, null, null) == 1
        val event = read(key) ?: return false
        val original = raw(key.eventId) ?: return false
        if (original.hasRule) return insertRuleException(key, event, canceled = true)
        return resolver.insert(Events.CONTENT_URI, fields(event).apply {
            put(Events.CALENDAR_ID, event.calendarId); put(Events.EVENT_TIMEZONE, event.timeZone)
            put(Events.ORIGINAL_ID, key.eventId); put(Events.ORIGINAL_INSTANCE_TIME, key.instanceStart)
            put(Events.ORIGINAL_SYNC_ID, original.syncId)
            put(Events.ORIGINAL_ALL_DAY, event.allDay); put(Events.STATUS, Events.STATUS_CANCELED)
        }) != null
    }

    private fun fields(event: StandardEvent, includeZone: Boolean = false) = ContentValues().apply {
        put(Events.TITLE, event.title); put(Events.DESCRIPTION, event.description)
        put(Events.DTSTART, event.start); put(Events.DTEND, event.end); put(Events.ALL_DAY, event.allDay)
        if (includeZone) put(Events.EVENT_TIMEZONE, event.timeZone)
    }
    private fun insertRuleException(key: EventKey, event: StandardEvent, canceled: Boolean): Boolean {
        // Let CalendarProvider clone unsupported fields and related tables. Reminders are
        // preserved by the platform, never interpreted or written by this bridge.
        val values = fields(event, includeZone = true).apply {
            remove(Events.DTEND); put(Events.DURATION, "PT${(event.end - event.start) / 1000}S")
            put(Events.ORIGINAL_INSTANCE_TIME, key.instanceStart)
            if (canceled) put(Events.STATUS, Events.STATUS_CANCELED)
        }
        return resolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, key.eventId), values) != null
    }
    private data class Raw(val event: StandardEvent, val originalId: Long?, val originalTime: Long?, val status: Int, val deleted: Boolean, val syncId: String?, val hasRule: Boolean)
    private fun raw(id: Long): Raw? = rawEvents("${Events._ID} = ?", arrayOf(id.toString())).singleOrNull()
    private fun exception(key: EventKey): Raw? = rawEvents(
        "${Events.ORIGINAL_ID} = ? AND ${Events.ORIGINAL_INSTANCE_TIME} = ? AND ${Events.DELETED} = 0",
        arrayOf(key.eventId.toString(), key.instanceStart.toString())).singleOrNull()
    private fun rawEvents(selection: String, args: Array<String>): List<Raw> = query(Events.CONTENT_URI, PROJECTION, selection, args) { c ->
        val recurring = !c.getString(9).isNullOrEmpty() || !c.getString(10).isNullOrEmpty()
        val begin = c.getLong(5)
        val end = if (!c.isNull(6)) c.getLong(6) else begin + Duration.parse(c.getString(11)).toMillis()
        Raw(StandardEvent(c.getLong(0), c.getLong(1), c.getString(2).orEmpty(), c.getString(3).orEmpty(),
            c.getString(4).orEmpty(), begin, end, c.getInt(7) != 0, recurring, c.getString(8).orEmpty()),
            if (c.isNull(12)) null else c.getLong(12), if (c.isNull(13)) null else c.getLong(13), c.getInt(14), c.getInt(15) != 0,
            c.getString(16), !c.getString(9).isNullOrEmpty())
    }
    private fun <T> query(uri: Uri, projection: Array<String>, selection: String?, args: Array<String>?, row: (Cursor) -> T): List<T> =
        checkNotNull(resolver.query(uri, projection, selection, args, null)) { "CalendarProvider returned no cursor" }.use { c ->
            buildList { while (c.moveToNext()) add(row(c)) }
        }
    private fun instanceUri(start: Long, end: Long): Uri = Instances.CONTENT_URI.buildUpon().apply {
        ContentUris.appendId(this, start); ContentUris.appendId(this, end)
    }.build()
    private fun marks(count: Int): String = List(count) { "?" }.joinToString(",")
    companion object {
        private val PROJECTION = arrayOf(Events._ID, Events.CALENDAR_ID, Events.TITLE, Events.DESCRIPTION,
            Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND, Events.ALL_DAY, Events.EVENT_TIMEZONE,
            Events.RRULE, Events.RDATE, Events.DURATION, Events.ORIGINAL_ID, Events.ORIGINAL_INSTANCE_TIME,
            Events.STATUS, Events.DELETED, Events._SYNC_ID)
    }
}
