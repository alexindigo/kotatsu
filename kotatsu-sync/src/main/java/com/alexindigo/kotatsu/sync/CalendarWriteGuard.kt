package com.alexindigo.kotatsu.sync

import com.alexindigo.kotatsu.sync.WritePermission.*

/** One authorization boundary for both new proposals and replay. */
internal class CalendarWriteGuard(
    private val store: MappingStore,
    private val observed: CalendarObservation,
    private val assessment: CalendarAssessment,
) {
    private val initial = store.continuity
    private val plans = observed.mapped.associateBy { it.pair.standardKey }
    private val candidates = assessment.candidates.mapValues { (_, members) -> members.associateBy { it.pair } }
    private val labels = initial.standard.mapValues { it.value.calendar.label } + observed.calendars.mapValues { it.value.label }
    private val holds: List<CalendarHold>

    init {
        val existing = initial.holds.mapNotNull { hold ->
            if (hold.resolution != null) hold else replacement(hold, hold.members.mapNotNull { current(hold, it) })
        }
        holds = existing + assessment.losses.mapNotNull { (direction, candidates) ->
            val used = existing.filter { it.direction == direction }.flatMap { it.members }.toSet()
            val extra = candidates.filterNot { it in used }
            if (extra.isEmpty()) null else newHold(direction, extra, labels)
        }
        if (holds != initial.holds) store.continuity = initial.copy(holds = holds)
    }

    /** An ID never names a different review snapshot. Changed evidence needs a new decision. */
    private fun replacement(hold: CalendarHold, fresh: List<LossMember>): CalendarHold? = when {
        fresh.isEmpty() -> null
        fresh == hold.members && hold.resolution == null -> hold
        else -> newHold(hold.direction, fresh, hold.calendars + labels)
    }
    private fun current(hold: CalendarHold, member: LossMember): LossMember? =
        candidates.getValue(hold.direction)[member.pair]

    private val frozen = holds.flatMap { it.members }.map { it.pair }.toSet()
    private val frozenPhone = frozen.map { it.muditaId }.toSet()
    private val frozenStandard = frozen.map { it.standardKey }.toSet()
    private val phoneLoss = holds.any { it.direction == LossDirection.PHONE }

    fun reasons() = store.continuity.holds.filter { it.resolution == null }.map { it.reason }
    fun canUpload(id: Long, calendar: Long?) = !assessment.resetPhone && id !in frozenPhone &&
        id !in initial.phoneOnly && calendar !in assessment.unavailable
    fun canImport(calendar: Long) = !assessment.resetPhone && calendar !in assessment.unavailable

    fun permission(intent: WriteIntent): WritePermission {
        val row = intent.mapping
        if (intent.lossId != null) {
            val hold = holds.singleOrNull { it.id == intent.lossId }
            val member = hold?.members?.singleOrNull { it.pair == row }
            if (hold == null || member == null) return obsolete(intent)
            return approved(hold, member, intent)
        }
        if (assessment.resetPhone || row in frozen || intent.standardBefore?.key in frozenStandard ||
            intent.phoneBefore?.id in frozenPhone) return BLOCK
        val calendar = row?.calendarId ?: intent.standardBefore?.calendarId ?: intent.standardAfter?.calendarId
        if (intent.kind == WriteKind.UPLOAD) {
            val target = calendar?.let { observed.calendars[it] }
            if (target == null || !target.eligible || calendar !in observed.selected || calendar in assessment.unavailable ||
                intent.uploadTarget != null && intent.uploadTarget != target.identity) return obsolete(intent)
        }
        if (calendar in assessment.unavailable) return BLOCK
        // A recovered edit can advance fingerprints in this pass. Window and
        // selection retirement depend on ownership, not those content hashes.
        val plan = row?.let { plans[it.standardKey] }?.takeIf {
            it.pair.muditaId == row.muditaId && it.pair.calendarId == row.calendarId
        }
        return when (intent.kind) {
            WriteKind.STANDARD_DELETE -> when {
                phoneLoss || plan == null || plan.pair != row || plan.phone != null || calendar !in observed.selected -> BLOCK
                plan.standard == null && (intent.confirmed || intent.attempted) -> ACKNOWLEDGE_DELETE
                plan.standard != null && plan.standard == intent.standardBefore -> RECONCILE
                else -> BLOCK
            }
            WriteKind.PHONE_DELETE -> when {
                plan == null -> BLOCK
                intent.confirmed || intent.attempted && plan.phone == null -> ACKNOWLEDGE_DELETE
                assessment.retirements[plan.pair]?.let { it != ChangeCause.UNEXPLAINED } == true -> RETIRE
                else -> RECONCILE // Can drop an obsolete retirement, but not remove its copy.
            }
            else -> RECONCILE
        }
    }

    private fun obsolete(intent: WriteIntent) = if (!intent.attempted && !intent.confirmed) DISCARD else BLOCK

    private fun approved(hold: CalendarHold, member: LossMember, intent: WriteIntent): WritePermission {
        val row = member.pair
        val live = plans[row.standardKey]?.takeIf { it.pair == row } ?: return obsolete(intent)
        if (row.calendarId !in observed.selected || observed.discordantPhone ||
            row.calendarId in observed.discordantCalendars || live.calendar != member.calendar) return obsolete(intent)
        return when (hold.resolution) {
            LossResolution.RESTORE -> if (intent.kind == WriteKind.IMPORT && intent.standardBefore == member.standard &&
                live == member && member.phone == null && row.calendarId !in assessment.unavailable) RECONCILE else obsolete(intent)
            LossResolution.DELETE_FROM_SERVER -> when {
                intent.kind != WriteKind.STANDARD_DELETE || intent.standardBefore != member.standard || member.phone != null ||
                    live.phone != null || row.calendarId in assessment.unavailable -> obsolete(intent)
                live.standard == null && (intent.confirmed || intent.attempted) -> ACKNOWLEDGE_DELETE
                live == member -> RECONCILE
                else -> obsolete(intent)
            }
            LossResolution.REMOVE -> when {
                intent.kind != WriteKind.PHONE_DELETE || intent.phoneBefore != member.phone ||
                    intent.standardBefore != member.standard || live.standard != member.standard || assessment.resetPhone -> obsolete(intent)
                live.phone == null && (intent.confirmed || intent.attempted) -> ACKNOWLEDGE_DELETE
                live == member -> RETIRE
                else -> obsolete(intent)
            }
            LossResolution.KEEP, null -> BLOCK
        }
    }

    fun resolveApproved(journal: WriteJournal, scope: SyncScope, mapper: EventMapper,
        phone: MuditaCalendarPort, standard: StandardCalendarPort): Boolean {
        val approved = holds.filter { it.resolution != null }
        if (approved.isEmpty()) return false
        for (hold in approved) {
            val currentPairs = store.all().toSet()
            val remaining = hold.members.filter { it.pair in currentPairs }
            val keep = mutableSetOf<Long>()
            val liveEvidence = mutableMapOf<Mapping, LossMember>()
            for (member in remaining) {
                val row = member.pair
                val observedMember = current(hold, member) ?: continue
                if (observedMember != member) continue
                // Existing, unresolved journal ownership cannot be displaced by an approval.
                if (journal.reserved(row.muditaId) || journal.reserved(row.standardKey)) continue
                if (observed.discordantPhone || row.calendarId in observed.discordantCalendars ||
                    hold.direction == LossDirection.STANDARD && assessment.resetPhone) continue
                val phones = phone.all()
                val matching = phones.filter { it.id == row.muditaId }
                if (matching.size > 1) continue
                val livePhone = matching.singleOrNull()
                val liveStandard = standard.read(row.standardKey)
                val liveCalendar = standard.calendars().singleOrNull { it.id == row.calendarId }?.witness()
                val live = LossMember(row, livePhone, liveStandard, liveCalendar)
                liveEvidence[row] = live
                if (live != member) continue
                val intent = when (hold.resolution) {
                    LossResolution.KEEP -> { keep.add(row.muditaId); continue }
                    LossResolution.REMOVE -> WriteIntent(kind = WriteKind.PHONE_DELETE, mapping = row,
                        phoneBefore = member.phone, standardBefore = member.standard, lossId = hold.id)
                    LossResolution.RESTORE -> {
                        val source = liveStandard?.takeIf { livePhone == null && scope.includes(it, row.standardKey) } ?: continue
                        WriteIntent(kind = WriteKind.IMPORT, mapping = row, phoneAfter = mapper.toMudita(source),
                            standardBefore = source, phoneIdsBefore = phones.map { it.id }.toSet(), lossId = hold.id)
                    }
                    LossResolution.DELETE_FROM_SERVER -> {
                        val source = liveStandard?.takeIf { livePhone == null && scope.identity(it, row.standardKey) } ?: continue
                        WriteIntent(kind = WriteKind.STANDARD_DELETE, mapping = row, standardBefore = source, lossId = hold.id)
                    }
                    null -> error("Missing resolution")
                }
                journal.write(intent)
            }
            if (keep.isNotEmpty()) store.keepPhoneOnly(keep)
            val after = store.all().toSet()
            val pending = store.pending().filter { it.lossId == hold.id }
            val state = store.continuity
            val pendingMembers = remaining.filter { member -> member.pair in after && pending.any {
                it.mapping == member.pair && approved(hold, member, it) in setOf(RECONCILE, RETIRE, ACKNOWLEDGE_DELETE)
            } }
            val otherHolds = state.holds.filterNot { it.id == hold.id }
            val used = otherHolds.filter { it.direction == hold.direction }.flatMap { it.members }.toSet()
            val fresh = remaining.filter { it.pair in after && it !in pendingMembers }.mapNotNull { member ->
                current(hold, member)?.let { liveEvidence[member.pair] ?: it }
            }.filterNot { it in used }
            val review = replacement(hold, fresh)
            // Only in-flight, still-authorized operations can keep an approved ID.
            val inFlight = hold.takeIf { pendingMembers.isNotEmpty() }?.copy(members = pendingMembers)
            store.continuity = state.copy(holds = otherHolds + listOfNotNull(inFlight, review))
        }
        return true
    }

    fun recordTrusted(phone: List<MuditaEvent>, firstRun: Boolean) {
        val state = store.continuity
        val pairs = store.all()
        val phoneIds = phone.map { it.id }.toSet()
        val baselines = state.standard.toMutableMap()
        for ((id, calendar) in observed.calendars) {
            if (id !in observed.selected || id in assessment.unavailable || holds.any { h ->
                    h.direction == LossDirection.STANDARD && h.members.any { it.pair.calendarId == id }
                }) continue
            val keys = pairs.filter { it.calendarId == id }.map { it.standardKey }.toSet()
            val live = observed.remote.values + observed.mapped.mapNotNull { it.standard }
            baselines[id] = CalendarBaseline(calendar, keys, live.filter { it.calendarId == id && it.key in keys }
                .mapNotNull { e -> e.syncId?.let { e.id to it } }.toMap())
        }
        val phoneBaseline = if (phoneLoss || assessment.resetPhone || phone.isEmpty() && state.phone == null) state.phone
        else MuditaBaseline(phone.size, phoneIds.maxOrNull() ?: 0L, firstRun,
            pairs.map { it.muditaId }.filter { it in phoneIds }.toSet(), phoneIds)
        val updated = state.copy(phone = phoneBaseline, standard = baselines,
            phoneOnly = if (phoneLoss || assessment.resetPhone) state.phoneOnly else state.phoneOnly.intersect(phoneIds))
        if (updated != state) store.continuity = updated
    }
}
