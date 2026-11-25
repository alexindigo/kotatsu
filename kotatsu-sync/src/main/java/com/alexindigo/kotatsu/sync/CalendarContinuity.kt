package com.alexindigo.kotatsu.sync

import java.util.UUID

enum class LossDirection { PHONE, STANDARD }
enum class LossResolution { RESTORE, DELETE_FROM_SERVER, KEEP, REMOVE }

data class CalendarWitness(val identity: CalendarIdentity, val eligible: Boolean)
internal fun SyncCalendar.witness() = CalendarWitness(identity, eligible)
data class LossMember(val pair: Mapping, val phone: MuditaEvent?, val standard: StandardEvent?,
    val calendar: CalendarWitness? = null)
data class CalendarHold(
    val id: String, val direction: LossDirection, val members: List<LossMember>,
    val calendars: Map<Long, String>, val resolution: LossResolution? = null,
) {
    val count: Int get() = members.size
    val reason: HeldCalendarLoss get() = HeldCalendarLoss(id, direction, count, calendars.values.toList())
}

data class HeldCalendarLoss(val id: String, val direction: LossDirection, val count: Int, val calendars: List<String>)

data class CalendarBaseline(val calendar: SyncCalendar, val keys: Set<EventKey>, val syncIds: Map<Long, String>)
data class MuditaBaseline(val count: Int, val maxId: Long, val firstPassDone: Boolean, val mappedIds: Set<Long>,
    val observedIds: Set<Long>? = null)
data class CalendarContinuity(
    val phone: MuditaBaseline? = null,
    val standard: Map<Long, CalendarBaseline> = emptyMap(),
    val holds: List<CalendarHold> = emptyList(),
    val phoneOnly: Set<Long> = emptySet(),
)

internal enum class ChangeCause { EXPLICIT_SELECTION, WINDOW, SERIES_REMOVED, ONE_CHANGE, UNEXPLAINED }

/** Observation first, journal second. No provider calls from assessment. */
internal data class CalendarObservation(
    val calendars: Map<Long, SyncCalendar>,
    val selected: Set<Long>,
    val phone: Map<Long, MuditaEvent>,
    val remote: Map<EventKey, StandardEvent>,
    val mapped: List<LossMember>,
    val missingSeries: Set<Long>,
    val discordantPhone: Boolean,
    val discordantCalendars: Set<Long>,
)

internal data class CalendarAssessment(
    val losses: Map<LossDirection, List<LossMember>>,
    val candidates: Map<LossDirection, List<LossMember>>,
    val retirements: Map<Mapping, ChangeCause>,
    val unavailable: Set<Long>,
    val resetPhone: Boolean,
)

internal fun assessCalendarChanges(
    observation: CalendarObservation, baseline: CalendarContinuity, scope: SyncScope,
): CalendarAssessment = with(observation) {
    val unavailable = selected.filterTo(mutableSetOf()) { id ->
        val current = calendars[id]
        val previous = baseline.standard[id]?.calendar
        current == null || !current.eligible || previous != null &&
            (current.accountName != previous.accountName || current.accountType != previous.accountType ||
                previous.collectionId != null && previous.collectionId != current.collectionId)
    }
    unavailable += discordantCalendars
    val resetPhone = discordantPhone || baseline.phone?.let { old ->
        val max = phone.keys.maxOrNull() ?: 0L
        // A falling high-water mark by itself is normal (the newest row can be
        // deleted). New, lower IDs together with a backwards high-water mark
        // are observable evidence of allocation restarting.
        phone.isNotEmpty() && max < old.maxId && old.observedIds?.let { previous ->
            phone.keys.any { it !in previous && mapped.none { row -> row.pair.muditaId == it } }
        } == true && mapped.any { it.phone == null }
    } == true
    val reused = mapped.filter { member ->
        val row = member.pair
        val oldId = baseline.standard[row.calendarId]?.syncIds?.get(row.standardKey.eventId)
        member.standard?.let { actual -> actual.calendarId == row.calendarId && oldId != null && oldId != actual.syncId } == true
    }
    unavailable += reused.map { it.pair.calendarId }
    val serverCandidates = mapped.filter { it.pair.calendarId in selected &&
        (!scope.owns(it.standard, it.pair) || it.pair.calendarId in unavailable) }
    // One removed series counts once, whether assessed locally or across healthy selected calendars.
    fun List<LossMember>.logicalLossCount() = map { member ->
        val key = member.pair.standardKey
        if (key.eventId in missingSeries) EventKey(key.eventId) else key
    }.distinct().size
    val healthyCandidates = serverCandidates.filterNot { it.pair.calendarId in unavailable }
    val bulkAcrossCalendars = healthyCandidates.logicalLossCount() >= BULK
    val mappedCounts = mapped.groupingBy { it.pair.calendarId }.eachCount()
    val serverLoss = serverCandidates.groupBy { it.pair.calendarId }.flatMap { (cal, cohort) ->
        val singleRemovedSeries = cohort.all { it.pair.standardKey.eventId in missingSeries } && cohort.logicalLossCount() == 1
        val total = maxOf(baseline.standard[cal]?.keys?.size ?: 0, mappedCounts.getValue(cal))
        if (cal in unavailable || bulkAcrossCalendars || !singleRemovedSeries && cohort.size == total && total >= 2) cohort
        else emptyList()
    }
    // Include absence even when the remote was edited. Reconciliation must not
    // repopulate a suspicious phone store as a side effect of diff ordering.
    val missingPhone = mapped.filter { it.pair.calendarId in selected && it.phone == null }
    val phoneCandidates = if (resetPhone && missingPhone.isEmpty()) mapped.filter { it.pair.calendarId in selected } else missingPhone
    val totalPhone = maxOf(baseline.phone?.mappedIds?.size ?: 0, mapped.size)
    val globalPhoneLoss = resetPhone || phoneCandidates.size >= BULK || phone.isEmpty() && totalPhone >= 2
    val phoneLoss = if (globalPhoneLoss) phoneCandidates else emptyList()
    val retirements = buildMap {
        for (member in mapped) {
            val row = member.pair
            val cause = when {
                row.calendarId !in selected -> ChangeCause.EXPLICIT_SELECTION
                member in serverLoss -> ChangeCause.UNEXPLAINED
                row.standardKey.eventId in missingSeries -> ChangeCause.SERIES_REMOVED
                !scope.owns(member.standard, row) -> ChangeCause.ONE_CHANGE
                !scope.includes(member.standard, row) -> ChangeCause.WINDOW
                else -> continue
            }
            put(row, cause)
        }
    }
    CalendarAssessment(mapOf(LossDirection.PHONE to phoneLoss, LossDirection.STANDARD to serverLoss),
        mapOf(LossDirection.PHONE to phoneCandidates, LossDirection.STANDARD to serverCandidates),
        retirements, unavailable, resetPhone)
}

internal fun newHold(direction: LossDirection, members: List<LossMember>, labels: Map<Long, String>) =
    CalendarHold(UUID.randomUUID().toString(), direction, members, labels.filterKeys { id ->
        members.any { it.pair.calendarId == id }
    })

internal const val BULK = 3
