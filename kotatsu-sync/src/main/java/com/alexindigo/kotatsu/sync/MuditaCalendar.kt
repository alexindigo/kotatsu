package com.alexindigo.kotatsu.sync

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri

class MuditaCalendar(private val resolver: ContentResolver) : MuditaCalendarPort {
    override fun schemaOk(): Boolean =
        resolver.query(URI, null, null, null, null)?.use { it.columnNames.toList() == COLUMNS } ?: false

    override fun all(): List<MuditaEvent> = resolver.query(URI, null, null, null, null)?.use { cursor ->
        check(cursor.columnNames.toList() == COLUMNS) { "Mudita Calendar schema changed" }
        buildList {
            while (cursor.moveToNext()) {
                val (description, receipt) = InsertReceipt.decode(cursor.getString(2).orEmpty())
                add(MuditaEvent(cursor.getLong(0), cursor.getString(1).orEmpty(),
                    description, cursor.getLong(3), cursor.getLong(4), cursor.getInt(5) != 0,
                    cursor.getInt(7) != 0, receipt))
            }
        }
    } ?: error("Mudita Calendar provider is absent")

    override fun insert(event: MuditaEvent): Long? = resolver.insert(URI, values(event.copy(id = 0)))?.let(ContentUris::parseId)
    override fun update(id: Long, event: MuditaEvent): Boolean =
        resolver.update(ContentUris.withAppendedId(URI, id), values(event.copy(id = id)), null, null) == 1
    override fun delete(id: Long): Boolean = resolver.delete(ContentUris.withAppendedId(URI, id), null, null) == 1

    private fun values(event: MuditaEvent) = ContentValues().apply {
        put("id", event.id); put("title", event.title); put("desc", InsertReceipt.encode(event.description, event.insertReceipts))
        put("startDateTime", event.start); put("endDateTime", event.end)
        put("isAllDayEvent", event.allDay); put("isPreReminderSet", event.preReminder)
    }

    companion object {
        val URI: Uri = Uri.parse("content://com.mudita.calendar/events")
        val COLUMNS = listOf("id", "title", "desc", "startDateTime", "endDateTime", "isAllDayEvent", "reminder", "isPreReminderSet")
    }
}
