package com.alexindigo.kotatsu.sync

/** Only the fields the phone can carry; reminder state is opaque and never synced. */
data class MuditaEvent(
    val id: Long = 0, val title: String, val description: String,
    val start: Long, val end: Long, val allDay: Boolean = false,
    val preReminder: Boolean = false,
)

data class SyncCalendar(val id: Long, val accountType: String, val syncing: Boolean) {
    val eligible: Boolean get() = accountType.isNotBlank() && accountType != "LOCAL" && syncing
}

/** An occurrence retains the series id and its original start even after an exception moves it. */
data class EventKey(val eventId: Long, val instanceStart: Long? = null)

data class StandardEvent(
    val id: Long = 0, val calendarId: Long, val title: String, val description: String,
    val location: String = "", val start: Long, val end: Long, val allDay: Boolean = false,
    val recurring: Boolean = false, val timeZone: String = "UTC",
    val instanceStart: Long? = null,
) {
    val key: EventKey get() = EventKey(id, instanceStart)
}

interface MuditaCalendarPort {
    fun schemaOk(): Boolean
    fun all(): List<MuditaEvent>
    fun insert(event: MuditaEvent): Long?
    fun update(id: Long, event: MuditaEvent): Boolean
    fun delete(id: Long): Boolean
}

interface StandardCalendarPort {
    fun calendars(): List<SyncCalendar>
    fun events(calendarIds: Set<Long>): List<StandardEvent>
    fun instances(calendarIds: Set<Long>, start: Long, end: Long): List<StandardEvent>
    fun read(key: EventKey): StandardEvent?
    fun insert(calendarId: Long, event: StandardEvent, intentId: String? = null): Long?
    /** Includes tombstoned rows, so recovery cannot resurrect an already deleted upload. */
    fun findInsert(intentId: String): Long?
    fun update(key: EventKey, event: StandardEvent): Boolean
    fun delete(key: EventKey): Boolean
}

data class Mapping(
    val muditaId: Long, val standardKey: EventKey, val calendarId: Long,
    val muditaFingerprint: String, val standardFingerprint: String,
    val generatedLocation: Boolean = false,
)

interface MappingStore {
    fun all(): List<Mapping>
    fun save(mapping: Mapping)
    fun remove(muditaId: Long)
    fun pending(): WriteIntent?
    fun prepare(intent: WriteIntent)
    /** Mapping changes and intent removal are one durable transaction. */
    fun acknowledge(intentId: String, mapping: Mapping?, removeMuditaId: Long?)
    var firstRunDone: Boolean
    var status: String
}

data class SyncSettings(val calendars: Set<Long>, val newEventCalendar: Long?, val horizonWeeks: Int = 8)
interface SyncConfig { fun read(): SyncSettings }
