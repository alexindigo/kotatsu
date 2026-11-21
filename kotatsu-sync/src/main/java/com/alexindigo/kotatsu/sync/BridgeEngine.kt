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
                    val row = Mapping(event.id, match.key, match.calendarId, mapper.muditaFingerprint(event), "")
                    mappings.save(row); rows.add(row)
                    claimed.add(match.key); handled.add(event.id)
                } else if (destination != null && upload(event, destination)) {
                    handled.add(event.id)
                } else firstRunComplete = false
            }
            if (firstRunComplete) mappings.firstRunDone = true
        }

        // Diff the initial snapshot, not rows written by this pass. Standard wins conflicts.
        val phoneWork = mutableListOf<Pair<Mapping, StandardEvent>>()
        for (row in rows) {
            handled.add(row.muditaId); claimed.add(row.standardKey)
            val oldPhone = phone[row.muditaId]
            val oldStandard = remote[row.standardKey]
            if (oldStandard == null) {
                // Includes deleted events, expired occurrences and removed/non-sync calendars.
                if (phoneNow(row.muditaId) != oldPhone) continue
                if (row.calendarId in chosen && row.standardKey.instanceStart == null && standard.read(row.standardKey) != null) continue
                if (oldPhone == null || mudita.delete(row.muditaId)) mappings.remove(row.muditaId)
            } else if (mapper.standardFingerprint(oldStandard) != row.standardFingerprint) {
                if (standard.read(row.standardKey) != oldStandard || phoneNow(row.muditaId) != oldPhone) continue
                val replacement = mapper.toMudita(oldStandard, oldPhone)
                if (oldPhone == null) {
                    val id = mudita.insert(replacement) ?: continue
                    val actual = phoneNow(id) ?: continue
                    if (mapper.muditaFingerprint(actual) == mapper.muditaFingerprint(replacement)) {
                        mappings.remove(row.muditaId); remember(actual, oldStandard)
                    }
                } else if (mudita.update(oldPhone.id, replacement)) {
                    val actual = phoneNow(oldPhone.id) ?: continue
                    if (mapper.muditaFingerprint(actual) == mapper.muditaFingerprint(replacement)) remember(actual, oldStandard)
                }
            } else phoneWork.add(row to oldStandard)
        }
        for (event in remote.values.filter { it.key !in claimed }) {
            if (standard.read(event.key) != event) continue
            val replacement = mapper.toMudita(event)
            val id = mudita.insert(replacement) ?: continue
            val actual = phoneNow(id) ?: continue
            if (mapper.muditaFingerprint(actual) == mapper.muditaFingerprint(replacement)) remember(actual, event)
        }
        // Only after all standard-side changes have been applied can phone changes write back.
        for ((row, oldStandard) in phoneWork) {
            val oldPhone = phone[row.muditaId]
            if (phoneNow(row.muditaId) != oldPhone || standard.read(row.standardKey) != oldStandard) continue
            if (oldPhone == null) {
                if (standard.delete(row.standardKey)) mappings.remove(row.muditaId)
            } else if (mapper.muditaFingerprint(oldPhone) != row.muditaFingerprint) {
                val replacement = mapper.toStandard(oldPhone, oldStandard)
                if (standard.update(row.standardKey, replacement)) {
                    val actual = standard.read(row.standardKey) ?: continue
                    if (mapper.standardFingerprint(actual) == mapper.standardFingerprint(replacement)) remember(oldPhone, actual)
                }
            }
        }
        if (mappings.firstRunDone) {
            for (event in phone.values.filter { it.id !in handled }) {
                if (destination == null || !upload(event, destination)) firstRunComplete = false
            }
        }
        mappings.status = if (firstRunComplete) "calendar pass complete" else "calendar pass incomplete: retry pending uploads"
    }

    private fun upload(event: MuditaEvent, calendarId: Long): Boolean {
        if (phoneNow(event.id) != event) return false
        val template = StandardEvent(calendarId = calendarId, title = "", description = "", start = 0, end = 0)
        val replacement = mapper.toStandard(event, template)
        val id = standard.insert(calendarId, replacement) ?: return false
        val actual = standard.read(EventKey(id)) ?: return false
        if (mapper.standardFingerprint(actual) != mapper.standardFingerprint(replacement)) return false
        remember(event, actual)
        return true
    }

    private fun remember(phone: MuditaEvent, remote: StandardEvent) = mappings.save(Mapping(phone.id,
        remote.key, remote.calendarId, mapper.muditaFingerprint(phone), mapper.standardFingerprint(remote)))
    private fun phoneNow(id: Long): MuditaEvent? = mudita.all().firstOrNull { it.id == id }
    private fun sameIdentity(a: MuditaEvent, b: MuditaEvent): Boolean =
        a.title == b.title && a.start == b.start && a.end == b.end && a.allDay == b.allDay

    companion object { const val WEEK = 7L * 24 * 60 * 60 * 1000 }
}
