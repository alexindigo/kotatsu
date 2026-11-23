package com.alexindigo.kotatsu.sync

import java.util.UUID

enum class LossDirection { PHONE, STANDARD }
enum class LossResolution { RESTORE, DELETE_FROM_SERVER, KEEP, REMOVE }

data class LossMember(val pair: Mapping, val phone: MuditaEvent?, val standard: StandardEvent?)
data class CalendarHold(
    val id: String, val direction: LossDirection, val members: List<LossMember>,
    val calendars: Map<Long, String>, val resolution: LossResolution? = null,
) {
    val count: Int get() = members.size
    val reason: HeldCalendarLoss get() = HeldCalendarLoss(id, direction, count, calendars.values.toList())
}

data class HeldCalendarLoss(val id: String, val direction: LossDirection, val count: Int, val calendars: List<String>)

data class CalendarBaseline(val calendar: SyncCalendar, val keys: Set<EventKey>, val syncIds: Map<Long, String>)
data class MuditaBaseline(val count: Int, val maxId: Long, val firstPassDone: Boolean, val keys: Set<Long>)
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
        phone.isNotEmpty() && max < old.maxId && phone.keys.any { it !in old.keys &&
            mapped.none { row -> row.pair.muditaId == it } } && mapped.any { it.phone == null }
    } == true
    val reused = mapped.filter { member ->
        val row = member.pair
        val oldId = baseline.standard[row.calendarId]?.syncIds?.get(row.standardKey.eventId)
        member.standard?.let { actual -> oldId != null && oldId != actual.syncId } == true
    }
    unavailable += reused.map { it.pair.calendarId }
    val serverCandidates = mapped.filter { it.pair.calendarId in selected &&
        (!scope.identity(it.standard, it.pair.standardKey) || it.standard?.calendarId != it.pair.calendarId ||
            it.pair.calendarId in unavailable) }
    val unexplained = serverCandidates.filterNot { it.pair.standardKey.eventId in missingSeries }
    val serverLoss = serverCandidates.groupBy { it.pair.calendarId }.values.flatMap { cohort ->
        val cal = cohort.first().pair.calendarId
        val uncertain = unexplained.filter { it.pair.calendarId == cal }
        val series = cohort.filter { it.pair.standardKey.eventId in missingSeries }.map { it.pair.standardKey.eventId }.toSet()
        val total = maxOf(baseline.standard[cal]?.keys?.size ?: 0, mapped.count { it.pair.calendarId == cal })
        if (cal in unavailable) cohort
        else if (uncertain.size + series.size >= BULK || (uncertain.isNotEmpty() || series.size > 1) &&
            cohort.size == total && total >= 2) cohort
        else emptyList()
    }
    // Include absence even when the remote was edited. Reconciliation must not
    // repopulate a suspicious phone store as a side effect of diff ordering.
    val phoneCandidates = mapped.filter { it.pair.calendarId in selected && it.phone == null }
    val totalPhone = maxOf(baseline.phone?.keys?.size ?: 0, mapped.size)
    val globalPhoneLoss = resetPhone || phoneCandidates.size >= BULK || phone.isEmpty() && totalPhone >= 2
    val phoneLoss = if (resetPhone && phoneCandidates.isEmpty()) mapped.filter { it.pair.calendarId in selected }
        else if (resetPhone) phoneCandidates
        else if (globalPhoneLoss) phoneCandidates else emptyList()
    val retirements = buildMap {
        for (member in mapped) {
            val row = member.pair
            val cause = when {
                row.calendarId !in selected -> ChangeCause.EXPLICIT_SELECTION
                member in serverLoss -> ChangeCause.UNEXPLAINED
                row.standardKey.eventId in missingSeries -> ChangeCause.SERIES_REMOVED
                !scope.identity(member.standard, row.standardKey) || member.standard?.calendarId != row.calendarId -> ChangeCause.ONE_CHANGE
                !scope.includes(member.standard, row.standardKey) -> ChangeCause.WINDOW
                else -> continue
            }
            put(row, cause)
        }
    }
    CalendarAssessment(mapOf(LossDirection.PHONE to phoneLoss, LossDirection.STANDARD to serverLoss),
        retirements, unavailable, resetPhone)
}

internal fun newHold(direction: LossDirection, members: List<LossMember>, labels: Map<Long, String>) =
    CalendarHold(UUID.randomUUID().toString(), direction, members, labels.filterKeys { id ->
        members.any { it.pair.calendarId == id }
    })

internal const val BULK = 3
