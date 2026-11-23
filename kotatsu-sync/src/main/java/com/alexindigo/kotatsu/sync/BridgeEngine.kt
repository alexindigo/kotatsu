package com.alexindigo.kotatsu.sync

/** One serialized pass. Mapping fingerprints are acknowledged only after consumer read-back. */
class BridgeEngine(
    private val mudita: MuditaCalendarPort, private val standard: StandardCalendarPort,
    private val mappings: MappingStore, private val config: SyncConfig,
    private val mapper: EventMapper = EventMapper(),
) {
    var changes: Int = 0
        private set
    fun pass(now: Long): PassResult = synchronized(passLock) { passSerialized(now) }
    private fun passSerialized(now: Long): PassResult {
        changes = 0
        fun existingHolds() = mappings.continuity.holds.filter { it.resolution == null }.map { it.reason }
        if (!mudita.schemaOk()) {
            mappings.status = "stopped: Mudita Calendar missing or incompatible schema"
            return PassResult(stopReasons = setOf(SyncStopReason.MUDITA_CALENDAR_CHANGED), held = existingHolds())
        }
        val settings = config.read()
        val calendars = standard.calendars().associateBy { it.id }
        val eligible = calendars.values.filter { it.eligible }.map { it.id }.toSet()
        val chosen = settings.calendars.intersect(eligible)
        val destination = settings.newEventCalendar?.takeIf { it in chosen }
        if (settings.horizonWeeks <= 0) {
            mappings.status = "stopped: invalid calendar horizon"
            return PassResult(stopReasons = setOf(SyncStopReason.CALENDAR_SETTINGS_REQUIRED), held = existingHolds())
        }
        val end = Math.addExact(now, Math.multiplyExact(settings.horizonWeeks.toLong(), WEEK))
        val scope = SyncScope(chosen, now, end)
        val observation = observe(calendars, settings.calendars, scope, now, end)
        val assessment = assessCalendarChanges(observation, mappings.continuity, scope)
        val guard = CalendarWriteGuard(mappings, observation, assessment)
        val journal = WriteJournal(mudita, standard, mappings, mapper, scope, guard::authorizes, guard::authorizesRetirement) { changes++ }
        fun apply(intent: WriteIntent) = journal.write(intent)
        journal.recover()
        if (guard.resolveApproved(journal, scope, mapper, mudita, standard)) {
            return PassResult(journal.retry, journal.unresolved, changes,
                if (journal.retry) setOf(SyncStopReason.RETRY_REQUIRED) else emptySet(), guard.reasons())
        }
        // Recoveries can acknowledge/import/delete rows. Diff only the original
        // observation, but never treat an acknowledged identity as newly seen.
        val phone = observation.phone
        val remote = observation.remote
        val rows = mappings.all().toMutableList()
        val handled = rows.map { it.muditaId }.toMutableSet()
        val claimed = rows.map { it.standardKey }.toMutableSet()
        var firstRunComplete = true
        var uploadRetry = false
        fun uploadNew(event: MuditaEvent): Boolean {
            if (destination == null) return false
            val outcome = upload(event, destination, journal)
            if (outcome == WriteOutcome.RETRY) uploadRetry = true
            return outcome == WriteOutcome.DONE
        }
        if (!mappings.firstRunDone) {
            for (event in phone.values.filter { it.id !in handled && !journal.reserved(it) && guard.canUpload(it.id, destination) }) {
                val match = remote.values.firstOrNull { it.key !in claimed && !journal.reserved(it.key) &&
                    guard.canImport(it.calendarId) && sameIdentity(event, mapper.toMudita(it)) }
                if (match != null) {
                    // A first-run match adopts the server version, including its description.
                    val row = Mapping(event.id, match.key, match.calendarId,
                        mapper.muditaFingerprint(event, match.location.isNotEmpty()), "", match.location.isNotEmpty())
                    mappings.save(row); rows.add(row)
                    claimed.add(match.key); handled.add(event.id)
                } else if (uploadNew(event)) {
                    handled.add(event.id)
                } else firstRunComplete = false
            }
            if (firstRunComplete) mappings.firstRunDone = true
        }

        // Diff the initial snapshot, not rows written by this pass. Standard wins conflicts.
        data class PhoneWork(val row: Mapping, val remote: StandardEvent, val expired: Boolean)
        val phoneWork = mutableListOf<PhoneWork>()
        fun retire(row: Mapping, before: MuditaEvent?) = apply(WriteIntent(
            kind = WriteKind.PHONE_DELETE, mapping = row, phoneBefore = before))
        fun phoneFingerprint(event: MuditaEvent, row: Mapping) = mapper.muditaFingerprint(event, row.generatedLocation)
        for (row in rows) {
            handled.add(row.muditaId); claimed.add(row.standardKey)
            if (journal.reserved(row.muditaId) || journal.reserved(row.standardKey)) continue
            val oldPhone = phone[row.muditaId]
            val visible = remote[row.standardKey]
            val live = visible ?: standard.read(row.standardKey)
            val validIdentity = scope.identity(live, row.standardKey) && live?.calendarId == row.calendarId
            val oldStandard = live?.takeIf { validIdentity }
            if (oldStandard == null) {
                // Retire the copy, never the standard row, when membership or recurrence identity changes.
                if (phoneNow(row.muditaId) != oldPhone) continue
                retire(row, oldPhone)
            } else if (visible == null && row.standardKey.instanceStart == null) {
                // A standalone appeared/moved after the snapshot. Diff it on the next pass.
                continue
            } else if (visible == null) {
                // Window membership is not event existence. Reconcile pending phone work before eviction.
                if (mapper.standardFingerprint(oldStandard) != row.standardFingerprint) {
                    if (phoneNow(row.muditaId) == oldPhone) retire(row, oldPhone)
                } else phoneWork.add(PhoneWork(row, oldStandard, expired = true))
            } else if (mapper.standardFingerprint(oldStandard) != row.standardFingerprint) {
                if (standard.read(row.standardKey) != oldStandard || phoneNow(row.muditaId) != oldPhone) continue
                val replacement = mapper.toMudita(oldStandard, oldPhone)
                if (oldPhone == null) {
                    apply(WriteIntent(kind = WriteKind.IMPORT, mapping = row, phoneAfter = replacement,
                            standardBefore = oldStandard, phoneIdsBefore = mudita.all().map { it.id }.toSet()))
                } else apply(WriteIntent(kind = WriteKind.PHONE_UPDATE, mapping = row,
                        phoneBefore = oldPhone, phoneAfter = replacement, standardBefore = oldStandard))
            } else phoneWork.add(PhoneWork(row, oldStandard, expired = false))
        }
        for (event in remote.values.filter { it.key !in claimed && !journal.reserved(it.key) }) {
            if (standard.read(event.key) != event) continue
            val replacement = mapper.toMudita(event)
            apply(WriteIntent(kind = WriteKind.IMPORT, phoneAfter = replacement,
                    standardBefore = event, phoneIdsBefore = mudita.all().map { it.id }.toSet()))
        }
        // Only after all standard-side changes have been applied can phone changes write back.
        for ((row, oldStandard, expired) in phoneWork) {
            if (journal.reserved(row.muditaId) || journal.reserved(row.standardKey)) continue
            val oldPhone = phone[row.muditaId]
            if (phoneNow(row.muditaId) != oldPhone || standard.read(row.standardKey) != oldStandard) continue
            if (oldPhone == null) {
                apply(WriteIntent(kind = WriteKind.STANDARD_DELETE, mapping = row, standardBefore = oldStandard))
            } else if (phoneFingerprint(oldPhone, row) != row.muditaFingerprint) {
                val replacement = mapper.toStandard(oldPhone, oldStandard, row.generatedLocation)
                apply(WriteIntent(kind = WriteKind.STANDARD_UPDATE, mapping = row,
                        phoneBefore = oldPhone, standardBefore = oldStandard, standardAfter = replacement))
            }
            if (journal.reserved(row.muditaId) || journal.reserved(row.standardKey)) continue
            if (expired && mappings.all().any { it.muditaId == row.muditaId }) {
                val after = standard.read(row.standardKey)
                if (!scope.includes(after, row.standardKey)) retire(row, oldPhone)
            }
        }
        if (mappings.firstRunDone) {
            for (event in phone.values.filter { it.id !in handled && !journal.reserved(it) && guard.canUpload(it.id, destination) }) {
                if (!uploadNew(event)) firstRunComplete = false
            }
        }
        val pending = mappings.pending().size
        guard.recordTrusted(mudita.all(), mappings.firstRunDone)
        val retry = journal.retry || uploadRetry
        mappings.status = if (guard.reasons().isNotEmpty()) "calendar pass held"
            else if (firstRunComplete && !retry && pending == 0) "calendar pass complete"
            else "calendar pass incomplete: $pending pending writes; retry=$retry"
        return PassResult(retry, journal.unresolved, changes, buildSet {
            if (!firstRunComplete && destination == null) add(SyncStopReason.CALENDAR_SETTINGS_REQUIRED)
            if (journal.unresolved > 0 || pending > 0 && !retry) add(SyncStopReason.REVIEW_REQUIRED)
            if (retry) add(SyncStopReason.RETRY_REQUIRED)
        }, guard.reasons())
    }

    private fun observe(calendars: Map<Long, SyncCalendar>, selected: Set<Long>, scope: SyncScope,
        now: Long, end: Long): CalendarObservation {
        val phoneRows = mudita.all()
        val phone = phoneRows.associateBy { it.id }
        val serverRows = standard.events(scope.chosen).filterNot { it.recurring } + standard.instances(scope.chosen, now, end)
        val remote = serverRows.associateBy { it.key }
        val discordant = serverRows.groupBy { it.key }.values.filter { it.size > 1 }
            .flatten().mapTo(mutableSetOf()) { it.calendarId }
        val pairs = mappings.all()
        val mapped = pairs.map { row ->
            val actual = remote[row.standardKey] ?: standard.read(row.standardKey)
            if (row.calendarId in scope.chosen && row.standardKey !in remote && scope.includes(actual, row.standardKey))
                discordant.add(row.calendarId)
            LossMember(row, phone[row.muditaId], actual)
        }
        val goneSeries = mapped.filter { it.pair.standardKey.instanceStart != null && it.standard == null }
            .map { it.pair.standardKey.eventId }.distinct().filter(standard::seriesRemoved).toSet()
        val phoneMissing = mapped.any { it.phone == null && it.pair.calendarId in selected }
        val phoneDiscordant = phone.size != phoneRows.size || phoneMissing && mudita.all().map { it.id }.toSet() != phone.keys
        return CalendarObservation(calendars, selected, phone, remote, mapped, goneSeries, phoneDiscordant, discordant)
    }

    private fun upload(event: MuditaEvent, calendarId: Long, journal: WriteJournal): WriteOutcome {
        if (phoneNow(event.id) != event) return WriteOutcome.RETRY
        val template = StandardEvent(calendarId = calendarId, title = "", description = "", start = 0, end = 0)
        val replacement = mapper.toStandard(event, template)
        return journal.write(WriteIntent(kind = WriteKind.UPLOAD, phoneBefore = event, standardAfter = replacement))
    }

    fun resolvePending(now: Long, id: String, resolution: WriteResolution) = synchronized(passLock) {
        check(mudita.schemaOk()) { "Mudita Calendar missing or incompatible schema" }
        val settings = config.read()
        val chosen = settings.calendars.intersect(standard.calendars().filter { it.eligible }.map { it.id }.toSet())
        val end = Math.addExact(now, Math.multiplyExact(settings.horizonWeeks.toLong(), WEEK))
        WriteJournal(mudita, standard, mappings, mapper, SyncScope(chosen, now, end), { false }).resolve(id, resolution)
    }

    fun resolveLoss(now: Long, id: String, resolution: LossResolution): PassResult = synchronized(passLock) {
        val state = mappings.continuity
        val hold = state.holds.singleOrNull { it.id == id } ?: return@synchronized PassResult(held = state.holds.map { it.reason })
        check(hold.resolution == null || hold.resolution == resolution)
        check(if (hold.direction == LossDirection.PHONE)
            resolution == LossResolution.RESTORE || resolution == LossResolution.DELETE_FROM_SERVER
        else resolution == LossResolution.KEEP || resolution == LossResolution.REMOVE)
        mappings.continuity = state.copy(holds = state.holds.map { if (it.id == id) it.copy(resolution = resolution) else it })
        passSerialized(now)
    }

    private fun phoneNow(id: Long): MuditaEvent? = mudita.all().firstOrNull { it.id == id }
    private fun sameIdentity(a: MuditaEvent, b: MuditaEvent): Boolean =
        a.title == b.title && a.start == b.start && a.end == b.end && a.allDay == b.allDay

    companion object {
        const val WEEK = 7L * 24 * 60 * 60 * 1000
        // Every engine instance uses the app's same provider pair and durable journal.
        // Keep recovery exclusive with active writes, even for callers outside the runtime lane.
        private val passLock = Any()
    }
}
