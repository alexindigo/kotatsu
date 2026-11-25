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
    fun canConsiderPhone(id: Long) = !assessment.resetPhone && id !in frozenPhone && id !in initial.phoneOnly
    fun canUpload(id: Long, calendar: Long) = canConsiderPhone(id) && calendar !in assessment.unavailable
    fun canImport(calendar: Long) = !assessment.resetPhone && calendar !in assessment.unavailable
    fun witnessStillCurrent(intent: WriteIntent, calendars: List<SyncCalendar>): Boolean {
        val id = intent.calendarId ?: return false
        val matches = calendars.filter { it.id == id }
        return matches.size <= 1 && matches.singleOrNull()?.witness() == observed.calendars[id]?.witness()
    }

    /** Prepared work from a previous pass has ownership but never replay authority. */
    fun recovery(intent: WriteIntent): WritePermission {
        // Durable success can be consumed without issuing another provider
        // mutation, even when a later pass sees different continuity evidence.
        if (intent.phase == AttemptPhase.CONFIRMED && intent.kind in mutationKinds &&
            plans[intent.mapping?.standardKey]?.pair == intent.mapping && intent.mapping != null) return ACKNOWLEDGE
        if (intent.phase != AttemptPhase.PREPARED) return permission(intent)
        if (intent.lossId != null || intent.kind == WriteKind.UPLOAD) return DISCARD
        return if (frozen(intent)) BLOCK else DISCARD
    }

    private fun frozen(intent: WriteIntent) = intent.mapping?.muditaId in frozenPhone ||
        intent.mapping?.standardKey in frozenStandard || intent.standardBefore?.key in frozenStandard ||
        intent.phoneBefore?.id in frozenPhone

    fun permission(intent: WriteIntent): WritePermission {
        val row = intent.mapping
        if (intent.lossId != null) {
            val hold = holds.singleOrNull { it.id == intent.lossId }
            val member = hold?.members?.singleOrNull { it.pair == row }
            if (hold == null || member == null) return BLOCK
            return approved(hold, member, intent)
        }
        if (assessment.resetPhone || frozen(intent)) return BLOCK
        val calendar = intent.calendarId
        if (intent.kind == WriteKind.UPLOAD) {
            val target = calendar?.let { observed.calendars[it] }
            if (target == null || !target.eligible || calendar !in observed.selected || calendar in assessment.unavailable ||
                intent.uploadTarget != null && intent.uploadTarget != target.identity) return BLOCK
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
                intent.confirmed || plan.standard == null && intent.attempted -> ACKNOWLEDGE
                plan.standard != null && plan.standard == intent.standardBefore -> RECONCILE
                else -> BLOCK
            }
            WriteKind.PHONE_DELETE -> when {
                plan == null -> BLOCK
                intent.confirmed || intent.attempted && plan.phone == null -> ACKNOWLEDGE
                assessment.retirements[plan.pair]?.let { it != ChangeCause.UNEXPLAINED } == true -> RETIRE
                intent.phase == AttemptPhase.ATTEMPTED && intent.phoneBefore != null && plan.phone == intent.phoneBefore -> CANCEL_RETIREMENT
                else -> BLOCK
            }
            else -> RECONCILE
        }
    }

    private fun approved(hold: CalendarHold, member: LossMember, intent: WriteIntent): WritePermission {
        val row = member.pair
        val live = plans[row.standardKey]?.takeIf { it.pair == row } ?: return BLOCK
        if (row.calendarId !in observed.selected || observed.discordantPhone ||
            row.calendarId in observed.discordantCalendars || live.calendar != member.calendar) return BLOCK
        return when (hold.resolution) {
            LossResolution.RESTORE -> if (intent.kind == WriteKind.IMPORT && intent.standardBefore == member.standard &&
                live == member && member.phone == null && row.calendarId !in assessment.unavailable) RECONCILE else BLOCK
            LossResolution.DELETE_FROM_SERVER -> when {
                intent.kind != WriteKind.STANDARD_DELETE || intent.standardBefore != member.standard || member.phone != null ||
                    live.phone != null || row.calendarId in assessment.unavailable -> BLOCK
                live.standard == null && (intent.confirmed || intent.attempted) -> ACKNOWLEDGE
                live == member && member.standard == null -> RETIRE
                live == member -> RECONCILE
                else -> BLOCK
            }
            LossResolution.REMOVE -> when {
                intent.kind != WriteKind.PHONE_DELETE || intent.phoneBefore != member.phone ||
                    intent.standardBefore != member.standard || live.standard != member.standard || assessment.resetPhone -> BLOCK
                live.phone == null && (intent.confirmed || intent.attempted) -> ACKNOWLEDGE
                live == member -> RETIRE
                else -> BLOCK
            }
            LossResolution.KEEP, null -> BLOCK
        }
    }

    fun resolveApproved(journal: WriteJournal, scope: SyncScope, mapper: EventMapper,
        phone: MuditaCalendarPort, standard: StandardCalendarPort): Boolean {
        val approved = holds.filter { it.resolution != null }
        if (approved.isEmpty()) return false
        var didResolve = false
        for (hold in approved) {
            val currentPairs = store.all().toSet()
            val remaining = hold.members.filter { it.pair in currentPairs }
            val keep = mutableSetOf<Long>()
            val liveEvidence = mutableMapOf<Mapping, LossMember>()
            val waitingForEvidence = mutableSetOf<LossMember>()
            for (member in remaining) {
                val row = member.pair
                val observedMember = current(hold, member) ?: continue
                if (observedMember != member) continue
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
                    LossResolution.KEEP -> null
                    LossResolution.REMOVE -> WriteIntent(kind = WriteKind.PHONE_DELETE, mapping = row,
                        phoneBefore = member.phone, standardBefore = member.standard, lossId = hold.id)
                    LossResolution.RESTORE -> {
                        val source = liveStandard?.takeIf { livePhone == null && scope.includes(it, row) } ?: continue
                        WriteIntent(kind = WriteKind.IMPORT, mapping = row, phoneAfter = mapper.toMudita(source),
                            standardBefore = source, phoneIdsBefore = phones.map { it.id }.toSet(), lossId = hold.id)
                    }
                    LossResolution.DELETE_FROM_SERVER -> {
                        if (livePhone != null || liveStandard != null && !scope.owns(liveStandard, row)) continue
                        WriteIntent(kind = WriteKind.STANDARD_DELETE, mapping = row, standardBefore = liveStandard, lossId = hold.id)
                    }
                    null -> error("Missing resolution")
                }
                if (intent != null && permission(intent) !in setOf(RECONCILE, RETIRE)) continue
                if (!journal.supersedePrepared(row)) {
                    // Retain exact approval while independent write evidence is awaited.
                    waitingForEvidence.add(member)
                    continue
                }
                if (intent == null) keep.add(row.muditaId) else journal.write(intent)
                didResolve = true
            }
            if (keep.isNotEmpty()) store.keepPhoneOnly(keep)
            val after = store.all().toSet()
            val allPending = store.pending()
            val pending = allPending.filter { it.lossId == hold.id }
            val state = store.continuity
            val pendingMembers = remaining.filter { member -> member.pair in after && (member in waitingForEvidence || pending.any {
                it.mapping == member.pair && approved(hold, member, it) in setOf(RECONCILE, RETIRE, ACKNOWLEDGE)
            }) }
            val otherHolds = state.holds.filterNot { it.id == hold.id }
            val used = otherHolds.filter { it.direction == hold.direction }.flatMap { it.members }.toSet()
            val fresh = remaining.filter { it.pair in after && it !in pendingMembers }.mapNotNull { member ->
                current(hold, member)?.let { liveEvidence[member.pair] ?: it }
            }.filterNot { it in used }
            val review = replacement(hold, fresh)
            // Only in-flight, still-authorized operations can keep an approved ID.
            val inFlight = hold.takeIf { pendingMembers.isNotEmpty() }?.copy(members = pendingMembers)
            val baselines = state.standard.toMutableMap()
            if (hold.direction == LossDirection.STANDARD) {
                for ((id, members) in hold.members.groupBy { it.pair.calendarId }) {
                    val observedCalendar = observed.calendars[id] ?: continue
                    if (id !in observed.selected || id !in assessment.unavailable || id in observed.discordantCalendars ||
                        !observedCalendar.eligible || members.any { it.calendar != observedCalendar.witness() } ||
                        after.any { it.calendarId == id } || allPending.any { it.calendarId == id }) continue
                    // Complete the acceptance together with finishing the hold.
                    baselines[id] = CalendarBaseline(observedCalendar, emptySet(), emptyMap())
                }
            }
            store.continuity = state.copy(standard = baselines, holds = otherHolds + listOfNotNull(inFlight, review))
        }
        return didResolve
    }

    fun recordTrusted(phone: List<MuditaEvent>, journal: WriteJournal, firstRun: Boolean) {
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
        val trusted = !phoneLoss && !assessment.resetPhone && phoneIds.size == phone.size &&
            phoneIds == journal.expectedPhoneIds(observed.phone.keys)
        val phoneBaseline = if (!trusted || phone.isEmpty() && state.phone == null) state.phone
        else MuditaBaseline(phone.size, phoneIds.maxOrNull() ?: 0L, firstRun,
            pairs.map { it.muditaId }.filter { it in phoneIds }.toSet(), phoneIds)
        val updated = state.copy(phone = phoneBaseline, standard = baselines,
            phoneOnly = if (!trusted) state.phoneOnly else state.phoneOnly.intersect(phoneIds))
        if (updated != state) store.continuity = updated
    }
}

private val mutationKinds = setOf(WriteKind.PHONE_DELETE, WriteKind.STANDARD_DELETE, WriteKind.PHONE_UPDATE, WriteKind.STANDARD_UPDATE)
