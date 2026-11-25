package com.alexindigo.kotatsu.sync

/** Only the fields the phone can carry; reminder state is opaque and never synced. */
data class MuditaEvent(
    val id: Long = 0, val title: String, val description: String,
    val start: Long, val end: Long, val allDay: Boolean = false,
    val preReminder: Boolean = false,
    val insertReceipts: Set<String> = emptySet(),
)

data class SyncCalendar(val id: Long, val accountType: String, val syncing: Boolean,
    val accountName: String = "", val label: String = id.toString(), val collectionId: String? = null) {
    val eligible: Boolean get() = accountType.isNotBlank() && accountType != "LOCAL" && syncing
    val identity: CalendarIdentity get() = CalendarIdentity(id, accountName, accountType, collectionId)
}

data class CalendarIdentity(val id: Long, val accountName: String, val accountType: String, val collectionId: String?)
enum class TargetMode { AUTOMATIC, EXPLICIT }

/** An occurrence retains the series id and its original start even after an exception moves it. */
data class EventKey(val eventId: Long, val instanceStart: Long? = null)

data class StandardEvent(
    val id: Long = 0, val calendarId: Long, val title: String, val description: String,
    val location: String = "", val start: Long, val end: Long, val allDay: Boolean = false,
    val recurring: Boolean = false, val timeZone: String = "UTC",
    val instanceStart: Long? = null,
    val syncId: String? = null,
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
    /** Positive base-row evidence; never deduce it from the expanding Instances query. */
    fun seriesRemoved(eventId: Long): Boolean = false
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
    fun pending(): List<WriteIntent>
    /** Atomically acquire a new intent and all its identities; false leaves the existing owner untouched. */
    fun tryPrepare(intent: WriteIntent): Boolean
    fun prepare(intent: WriteIntent)
    /** Mapping changes and intent removal are one durable transaction. */
    fun acknowledge(intentId: String, mapping: Mapping?, removeMuditaId: Long?)
    var firstRunDone: Boolean
    var status: String
    var continuity: CalendarContinuity
    fun keepPhoneOnly(ids: Set<Long>)
}

internal const val DEFAULT_HORIZON_WEEKS = 8
internal val calendarHorizonWeeks = listOf(2, 4, DEFAULT_HORIZON_WEEKS, 12)

data class SyncSettings(val calendars: Set<Long>, val newEventCalendar: Long? = null, val horizonWeeks: Int = DEFAULT_HORIZON_WEEKS,
    val targetMode: TargetMode = if (newEventCalendar == null) TargetMode.AUTOMATIC else TargetMode.EXPLICIT,
    val targetIdentity: CalendarIdentity? = null,
) {
    fun destination(available: Collection<SyncCalendar>): SyncCalendar? {
        val shown = available.filter { it.eligible && it.id in calendars }.sortedWith(calendarOrder)
        return if (targetMode == TargetMode.AUTOMATIC) preferPersonal(shown, SyncCalendar::label)
        else shown.singleOrNull { it.id == newEventCalendar && (targetIdentity == null || it.identity == targetIdentity) }
    }
}

internal val calendarOrder = compareBy<SyncCalendar, String>(String.CASE_INSENSITIVE_ORDER) { it.label }.thenBy { it.id }
internal fun <T> preferPersonal(choices: List<T>, label: (T) -> String): T? =
    choices.firstOrNull { label(it).contains("Personal", ignoreCase = true) } ?: choices.firstOrNull()

internal sealed interface CalendarSettingsIntent {
    fun reduce(old: SyncSettings, available: List<SyncCalendar>): SyncSettings

    data class Toggle(val id: Long) : CalendarSettingsIntent {
        override fun reduce(old: SyncSettings, available: List<SyncCalendar>) = when {
            id in old.calendars -> old.copy(calendars = old.calendars - id)
            available.any { it.id == id && it.eligible } -> old.copy(calendars = old.calendars + id)
            else -> old
        }
    }
    data class Choose(val id: Long) : CalendarSettingsIntent {
        override fun reduce(old: SyncSettings, available: List<SyncCalendar>) =
            available.singleOrNull { it.id == id && it.eligible && id in old.calendars }?.let {
                old.copy(newEventCalendar = id, targetMode = TargetMode.EXPLICIT, targetIdentity = it.identity)
            } ?: old
    }
    data class Weeks(val value: Int) : CalendarSettingsIntent {
        override fun reduce(old: SyncSettings, available: List<SyncCalendar>) = old.copy(horizonWeeks = value)
    }
}
interface SyncConfig { fun read(): SyncSettings }
