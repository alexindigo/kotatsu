package com.alexindigo.kotatsu.sync

/** One authorization boundary for both new proposals and replay. */
internal class CalendarWriteGuard(
    private val store: MappingStore,
    private val observed: CalendarObservation,
    private val assessment: CalendarAssessment,
) {
    private val initial = store.continuity
    private val plans = observed.mapped.associateBy { it.pair.standardKey }
    private val holds: List<CalendarHold>

    init {
        val labels = observed.calendars.mapValues { it.value.label } + initial.standard.mapValues { it.value.calendar.label }
        val existing = initial.holds
        holds = existing + assessment.losses.mapNotNull { (direction, candidates) ->
            val used = existing.filter { it.direction == direction }.flatMap { h -> h.members.map { it.pair } }.toSet()
            val extra = candidates.filter { it.pair !in used }
            if (extra.isEmpty()) null else newHold(direction, extra, labels)
        }
        if (holds != existing) store.continuity = initial.copy(holds = holds)
    }

    private val frozen = holds.flatMap { it.members }.map { it.pair }.toSet()
    private val frozenPhone = frozen.map { it.muditaId }.toSet()
    private val frozenStandard = frozen.map { it.standardKey }.toSet()
    private val phoneLoss = holds.any { it.direction == LossDirection.PHONE }

    fun reasons() = store.continuity.holds.filter { it.resolution == null }.map { it.reason }
    fun canUpload(id: Long, calendar: Long?) = !assessment.resetPhone && id !in frozenPhone &&
        id !in initial.phoneOnly && calendar !in assessment.unavailable
    fun canImport(calendar: Long) = !assessment.resetPhone && calendar !in assessment.unavailable

    fun authorizes(intent: WriteIntent): Boolean {
        val row = intent.mapping
        if (intent.lossId != null) {
            row ?: return false
            val hold = holds.singleOrNull { it.id == intent.lossId } ?: return false
            val member = hold.members.singleOrNull { it.pair == row } ?: return false
            return when (hold.resolution) {
                LossResolution.RESTORE -> intent.kind == WriteKind.IMPORT && intent.standardBefore == member.standard &&
                    !observed.discordantPhone && member.phone == null && row.calendarId !in assessment.unavailable
                LossResolution.DELETE_FROM_SERVER -> intent.kind == WriteKind.STANDARD_DELETE && intent.standardBefore == member.standard &&
                    !observed.discordantPhone && member.phone == null && row.calendarId !in assessment.unavailable
                LossResolution.REMOVE -> intent.kind == WriteKind.PHONE_DELETE && intent.phoneBefore == member.phone &&
                    intent.standardBefore == member.standard && !assessment.resetPhone
                LossResolution.KEEP, null -> false
            }
        }
        if (assessment.resetPhone || row in frozen || intent.standardBefore?.key in frozenStandard ||
            intent.phoneBefore?.id in frozenPhone) return false
        val calendar = row?.calendarId ?: intent.standardBefore?.calendarId ?: intent.standardAfter?.calendarId
        if (calendar in assessment.unavailable) {
            // An unattempted insert whose destination is gone is safe to discard, never to issue.
            return intent.kind == WriteKind.UPLOAD && !intent.attempted
        }
        return when (intent.kind) {
            WriteKind.STANDARD_DELETE -> !phoneLoss && row != null && plans[row.standardKey]?.let {
                it.pair.muditaId == row.muditaId && it.phone == null &&
                    (it.standard == intent.standardBefore || intent.confirmed && it.standard == null) && row.calendarId in observed.selected
            } == true
            WriteKind.PHONE_DELETE -> row != null && plans[row.standardKey]?.pair?.muditaId == row.muditaId
            else -> true
        }
    }

    fun authorizesRetirement(intent: WriteIntent): Boolean {
        if (!authorizes(intent)) return false
        if (intent.lossId != null) return true
        val row = intent.mapping ?: return false
        val observedPair = plans[row.standardKey]?.pair ?: return false
        return assessment.retirements[observedPair]?.let { it != ChangeCause.UNEXPLAINED } == true
    }

    fun resolveApproved(journal: WriteJournal, scope: SyncScope, mapper: EventMapper,
        phone: MuditaCalendarPort, standard: StandardCalendarPort): Boolean {
        val approved = holds.filter { it.resolution != null }
        if (approved.isEmpty()) return false
        for (hold in approved) {
            val currentPairs = store.all().toSet()
            val remaining = hold.members.filter { it.pair in currentPairs }
            var keep = emptySet<Long>()
            for (member in remaining) {
                val row = member.pair
                // Existing, unresolved journal ownership cannot be displaced by an approval.
                if (journal.reserved(row.muditaId) || journal.reserved(row.standardKey)) continue
                val livePhone = phone.all().singleOrNull { it.id == row.muditaId }
                val liveStandard = standard.read(row.standardKey)
                if (livePhone != member.phone || liveStandard != member.standard) continue
                val intent = when (hold.resolution) {
                    LossResolution.KEEP -> { keep = keep + row.muditaId; continue }
                    LossResolution.REMOVE -> WriteIntent(kind = WriteKind.PHONE_DELETE, mapping = row,
                        phoneBefore = member.phone, standardBefore = member.standard, lossId = hold.id)
                    LossResolution.RESTORE -> {
                        val source = liveStandard?.takeIf { livePhone == null && scope.includes(it, row.standardKey) } ?: continue
                        WriteIntent(kind = WriteKind.IMPORT, mapping = row, phoneAfter = mapper.toMudita(source),
                            standardBefore = source, phoneIdsBefore = phone.all().map { it.id }.toSet(), lossId = hold.id)
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
            val stillHeld = remaining.filter { it.pair in after }
            val state = store.continuity
            val successor = if (pending.isNotEmpty()) hold.copy(members = stillHeld)
                else if (stillHeld.isNotEmpty()) hold.copy(resolution = null, members = stillHeld)
                else null
            store.continuity = state.copy(holds = state.holds.mapNotNull { if (it.id == hold.id) successor else it })
        }
        return true
    }

    fun recordTrusted(phone: List<MuditaEvent>, firstRun: Boolean) {
        val state = store.continuity
        val pairs = store.all()
        val phoneIds = phone.map { it.id }.toSet()
        val baselines = state.standard.toMutableMap()
        for ((id, calendar) in observed.calendars) {
            if (id in assessment.unavailable || holds.any { h -> h.direction == LossDirection.STANDARD && id in h.calendars }) continue
            val keys = pairs.filter { it.calendarId == id }.map { it.standardKey }.toSet()
            if (keys.isEmpty() && id !in baselines) continue
            val live = observed.remote.values + observed.mapped.mapNotNull { it.standard }
            baselines[id] = CalendarBaseline(calendar, keys, live.filter { it.calendarId == id && it.key in keys }
                .mapNotNull { e -> e.syncId?.let { e.id to it } }.toMap())
        }
        val phoneBaseline = if (phoneLoss || assessment.resetPhone || phone.isEmpty() && state.phone == null) state.phone
        else MuditaBaseline(phone.size, phoneIds.maxOrNull() ?: 0L, firstRun,
            pairs.map { it.muditaId }.filter { it in phoneIds }.toSet())
        val updated = state.copy(phone = phoneBaseline, standard = baselines,
            phoneOnly = if (phoneLoss || assessment.resetPhone) state.phoneOnly else state.phoneOnly.intersect(phoneIds))
        if (updated != state) store.continuity = updated
    }
}
