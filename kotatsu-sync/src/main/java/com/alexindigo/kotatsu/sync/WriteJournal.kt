package com.alexindigo.kotatsu.sync

import java.util.UUID

enum class WriteKind { IMPORT, PHONE_UPDATE, PHONE_DELETE, UPLOAD, STANDARD_UPDATE, STANDARD_DELETE }

/** A durable before/after image, not an in-memory retry closure. */
data class WriteIntent(
    val id: String = UUID.randomUUID().toString(), val kind: WriteKind,
    val mapping: Mapping? = null,
    val phoneBefore: MuditaEvent? = null, val phoneAfter: MuditaEvent? = null,
    val standardBefore: StandardEvent? = null, val standardAfter: StandardEvent? = null,
    val phoneIdsBefore: Set<Long> = emptySet(), val attempted: Boolean = false,
    val insertedId: Long? = null,
)

/** Owns every provider-write/mapping-ack boundary. Replayed before a new diff. */
internal class WriteJournal(
    private val phone: MuditaCalendarPort, private val standard: StandardCalendarPort,
    private val store: MappingStore, private val mapper: EventMapper,
    private val chosenCalendars: Set<Long>,
) {
    fun recover(): Boolean = store.pending()?.let(::reconcile) ?: true
    fun write(intent: WriteIntent): Boolean {
        check(store.pending() == null) { "An earlier calendar write is still pending" }
        store.prepare(intent)
        return reconcile(intent)
    }
    private fun phoneNow(id: Long) = phone.all().singleOrNull { it.id == id }
    private fun finish(intent: WriteIntent, mapping: Mapping? = null, remove: Long? = null): Boolean {
        store.acknowledge(intent.id, mapping, remove)
        return true
    }
    private fun pair(phone: MuditaEvent, remote: StandardEvent) = Mapping(phone.id, remote.key,
        remote.calendarId, mapper.muditaFingerprint(phone, remote.location.isNotEmpty()),
        mapper.standardFingerprint(remote), remote.location.isNotEmpty())
    private fun remoteEquals(a: StandardEvent?, b: StandardEvent?) = a == b
    private fun reconcile(original: WriteIntent): Boolean {
        var intent = original
        when (intent.kind) {
            WriteKind.IMPORT -> {
                val desired = checkNotNull(intent.phoneAfter)
                val source = checkNotNull(intent.standardBefore)
                if (!intent.attempted) {
                    if (source.calendarId !in chosenCalendars || standard.read(source.key) != source) return finish(intent)
                    intent = intent.copy(attempted = true)
                    store.prepare(intent) // Durable before entering the provider, not after it returns.
                    val id = phone.insert(desired)
                    if (id != null) { intent = intent.copy(insertedId = id); store.prepare(intent) }
                }
                if (intent.insertedId == null) {
                    val mapped = store.all().map { it.muditaId }.toSet()
                    val candidates = phone.all().filter { it.id !in intent.phoneIdsBefore && it.id !in mapped &&
                        mapper.muditaFingerprint(it, source.location.isNotEmpty()) ==
                        mapper.muditaFingerprint(desired, source.location.isNotEmpty()) }
                    // Mudita offers no transaction token. An uncertain insertion must not be repeated.
                    if (candidates.size != 1) return false
                    intent = intent.copy(insertedId = candidates.single().id); store.prepare(intent)
                }
                val expected = desired.copy(id = checkNotNull(intent.insertedId))
                val actual = phoneNow(expected.id)
                val matches = actual != null && mapper.muditaFingerprint(actual, source.location.isNotEmpty()) ==
                    mapper.muditaFingerprint(expected, source.location.isNotEmpty())
                // Persist recovered identity even on read-back drift; do not swallow that later edit
                // into the baseline or retry the insertion. The next diff reconciles the drift.
                finish(intent, pair(expected, source), intent.mapping?.muditaId)
                return matches
            }
            WriteKind.UPLOAD -> {
                val source = checkNotNull(intent.phoneBefore)
                val desired = checkNotNull(intent.standardAfter)
                var id = intent.insertedId ?: standard.findInsert(intent.id)
                if (id == null) {
                    if (intent.attempted) return false // Marker may have been deleted along with an unsynced row.
                    if (desired.calendarId !in chosenCalendars || phoneNow(source.id) != source) return finish(intent)
                    intent = intent.copy(attempted = true); store.prepare(intent)
                    id = standard.insert(desired.calendarId, desired, intent.id) ?: return false
                    intent = intent.copy(insertedId = id); store.prepare(intent)
                }
                val actual = standard.read(EventKey(id))
                val expected = desired.copy(id = id)
                val matches = actual != null && mapper.standardFingerprint(actual) == mapper.standardFingerprint(expected)
                finish(intent, pair(source, expected))
                return matches
            }
            WriteKind.PHONE_UPDATE -> {
                val before = checkNotNull(intent.phoneBefore)
                val desired = checkNotNull(intent.phoneAfter)
                val source = checkNotNull(intent.standardBefore)
                val current = phoneNow(before.id)
                if (current != desired) {
                    if (source.calendarId !in chosenCalendars || current != before || standard.read(source.key) != source) return finish(intent)
                    if (!phone.update(before.id, desired)) return false
                    if (phoneNow(before.id) != desired) return false
                }
                return finish(intent, pair(desired, source))
            }
            WriteKind.PHONE_DELETE -> {
                val row = checkNotNull(intent.mapping)
                val current = phoneNow(row.muditaId)
                if (current != null) {
                    if (current != intent.phoneBefore) return finish(intent)
                    if (!phone.delete(row.muditaId) || phoneNow(row.muditaId) != null) return false
                }
                return finish(intent, remove = row.muditaId)
            }
            WriteKind.STANDARD_UPDATE -> {
                val before = checkNotNull(intent.standardBefore)
                val desired = checkNotNull(intent.standardAfter)
                val source = checkNotNull(intent.phoneBefore)
                val current = standard.read(before.key)
                if (!remoteEquals(current, desired)) {
                    if (before.calendarId !in chosenCalendars || !remoteEquals(current, before) || phoneNow(source.id) != source) return finish(intent)
                    if (!standard.update(before.key, desired)) return false
                    if (!remoteEquals(standard.read(before.key), desired)) return false
                }
                return finish(intent, pair(source, desired))
            }
            WriteKind.STANDARD_DELETE -> {
                val before = checkNotNull(intent.standardBefore)
                val row = checkNotNull(intent.mapping)
                val current = standard.read(before.key)
                if (current != null) {
                    if (before.calendarId !in chosenCalendars || current != before || phoneNow(row.muditaId) != null) return finish(intent)
                    if (!standard.delete(before.key) || standard.read(before.key) != null) return false
                }
                return finish(intent, remove = row.muditaId)
            }
        }
    }
}
