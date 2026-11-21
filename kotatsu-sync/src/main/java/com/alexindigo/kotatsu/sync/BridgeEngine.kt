package com.alexindigo.kotatsu.sync

/** One serialized pass. Mapping fingerprints are acknowledged only after consumer read-back. */
class BridgeEngine(
    private val mudita: MuditaCalendarPort, private val standard: StandardCalendarPort,
    private val mappings: MappingStore, private val config: SyncConfig,
    private val mapper: EventMapper = EventMapper(),
) {
    fun pass(now: Long) {
        if (!mudita.schemaOk()) { mappings.status = "stopped: Mudita Calendar missing or incompatible schema"; return }
        val settings = config.read()
        val eligible = standard.calendars().filter { it.eligible }.map { it.id }.toSet()
        val chosen = settings.calendars.intersect(eligible)
        val destination = settings.newEventCalendar?.takeIf { it in chosen }
        if (settings.horizonWeeks <= 0) {
            mappings.status = "stopped: invalid calendar horizon"; return
        }
        val journal = WriteJournal(mudita, standard, mappings, mapper, chosen)
        fun apply(intent: WriteIntent): Boolean = journal.write(intent).also {
            if (!it) mappings.status = "calendar pass incomplete: provider write or readback pending"
        }
        if (!journal.recover()) {
            mappings.status = "calendar pass incomplete: provider write or readback pending"; return
        }
        val phone = mudita.all().associateBy { it.id }
        val end = Math.addExact(now, Math.multiplyExact(settings.horizonWeeks.toLong(), WEEK))
        val remote = (standard.events(chosen).filterNot { it.recurring } + standard.instances(chosen, now, end))
            .associateBy { it.key }
        val rows = mappings.all().toMutableList()
        val handled = rows.map { it.muditaId }.toMutableSet()
        val claimed = rows.map { it.standardKey }.toMutableSet()
        var firstRunComplete = true
        if (!mappings.firstRunDone) {
            for (event in phone.values.filter { it.id !in handled }) {
                val match = remote.values.firstOrNull { it.key !in claimed && sameIdentity(event, mapper.toMudita(it)) }
                if (match != null) {
                    // A first-run match adopts the server version, including its description.
                    val row = Mapping(event.id, match.key, match.calendarId,
                        mapper.muditaFingerprint(event, match.location.isNotEmpty()), "", match.location.isNotEmpty())
                    mappings.save(row); rows.add(row)
                    claimed.add(match.key); handled.add(event.id)
                } else if (destination != null && upload(event, destination, journal)) {
                    handled.add(event.id)
                } else firstRunComplete = false
                if (mappings.pending() != null) { mappings.status = "calendar pass incomplete: provider write recovery pending"; return }
            }
            if (firstRunComplete) mappings.firstRunDone = true
        }

        // Diff the initial snapshot, not rows written by this pass. Standard wins conflicts.
        data class PhoneWork(val row: Mapping, val remote: StandardEvent, val expired: Boolean)
        val phoneWork = mutableListOf<PhoneWork>()
        fun retire(row: Mapping, before: MuditaEvent?): Boolean = apply(WriteIntent(
            kind = WriteKind.PHONE_DELETE, mapping = row, phoneBefore = before))
        fun phoneFingerprint(event: MuditaEvent, row: Mapping) = mapper.muditaFingerprint(event, row.generatedLocation)
        for (row in rows) {
            handled.add(row.muditaId); claimed.add(row.standardKey)
            val oldPhone = phone[row.muditaId]
            val visible = remote[row.standardKey]
            val live = visible ?: standard.read(row.standardKey)
            val validIdentity = live != null && live.calendarId in chosen &&
                (if (row.standardKey.instanceStart == null) !live.recurring else live.recurring)
            val oldStandard = live?.takeIf { validIdentity }
            if (oldStandard == null) {
                // Retire the copy, never the standard row, when membership or recurrence identity changes.
                if (phoneNow(row.muditaId) != oldPhone) continue
                if (!retire(row, oldPhone)) return
            } else if (visible == null && row.standardKey.instanceStart == null) {
                // A standalone appeared/moved after the snapshot. Diff it on the next pass.
                continue
            } else if (visible == null) {
                // Window membership is not event existence. Reconcile pending phone work before eviction.
                if (mapper.standardFingerprint(oldStandard) != row.standardFingerprint) {
                    if (phoneNow(row.muditaId) == oldPhone && !retire(row, oldPhone)) return
                } else phoneWork.add(PhoneWork(row, oldStandard, expired = true))
            } else if (mapper.standardFingerprint(oldStandard) != row.standardFingerprint) {
                if (standard.read(row.standardKey) != oldStandard || phoneNow(row.muditaId) != oldPhone) continue
                val replacement = mapper.toMudita(oldStandard, oldPhone)
                if (oldPhone == null) {
                    if (!apply(WriteIntent(kind = WriteKind.IMPORT, mapping = row, phoneAfter = replacement,
                            standardBefore = oldStandard, phoneIdsBefore = mudita.all().map { it.id }.toSet()))) return
                } else if (!apply(WriteIntent(kind = WriteKind.PHONE_UPDATE, mapping = row,
                        phoneBefore = oldPhone, phoneAfter = replacement, standardBefore = oldStandard))) return
            } else phoneWork.add(PhoneWork(row, oldStandard, expired = false))
        }
        for (event in remote.values.filter { it.key !in claimed }) {
            if (standard.read(event.key) != event) continue
            val replacement = mapper.toMudita(event)
            if (!apply(WriteIntent(kind = WriteKind.IMPORT, phoneAfter = replacement,
                    standardBefore = event, phoneIdsBefore = mudita.all().map { it.id }.toSet()))) return
        }
        // Only after all standard-side changes have been applied can phone changes write back.
        for ((row, oldStandard, expired) in phoneWork) {
            val oldPhone = phone[row.muditaId]
            if (phoneNow(row.muditaId) != oldPhone || standard.read(row.standardKey) != oldStandard) continue
            if (oldPhone == null) {
                if (!apply(WriteIntent(kind = WriteKind.STANDARD_DELETE, mapping = row, standardBefore = oldStandard))) return
            } else if (phoneFingerprint(oldPhone, row) != row.muditaFingerprint) {
                val replacement = mapper.toStandard(oldPhone, oldStandard, row.generatedLocation)
                if (!apply(WriteIntent(kind = WriteKind.STANDARD_UPDATE, mapping = row,
                        phoneBefore = oldPhone, standardBefore = oldStandard, standardAfter = replacement))) return
            }
            if (expired && mappings.all().any { it.muditaId == row.muditaId }) {
                val after = standard.read(row.standardKey)
                val backInWindow = after != null && after.calendarId in chosen && after.recurring &&
                    after.end > now && after.start < end
                if (!backInWindow && !retire(row, oldPhone)) return
            }
        }
        if (mappings.firstRunDone) {
            for (event in phone.values.filter { it.id !in handled }) {
                if (destination == null || !upload(event, destination, journal)) firstRunComplete = false
                if (mappings.pending() != null) { mappings.status = "calendar pass incomplete: provider write recovery pending"; return }
            }
        }
        mappings.status = if (firstRunComplete) "calendar pass complete" else "calendar pass incomplete: retry pending uploads"
    }

    private fun upload(event: MuditaEvent, calendarId: Long, journal: WriteJournal): Boolean {
        if (phoneNow(event.id) != event) return false
        val template = StandardEvent(calendarId = calendarId, title = "", description = "", start = 0, end = 0)
        val replacement = mapper.toStandard(event, template)
        return journal.write(WriteIntent(kind = WriteKind.UPLOAD, phoneBefore = event, standardAfter = replacement))
    }

    private fun phoneNow(id: Long): MuditaEvent? = mudita.all().firstOrNull { it.id == id }
    private fun sameIdentity(a: MuditaEvent, b: MuditaEvent): Boolean =
        a.title == b.title && a.start == b.start && a.end == b.end && a.allDay == b.allDay

    companion object { const val WEEK = 7L * 24 * 60 * 60 * 1000 }
}
