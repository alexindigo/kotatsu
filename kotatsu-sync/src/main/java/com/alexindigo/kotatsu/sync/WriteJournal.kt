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
    val confirmed: Boolean = false,
    val lossId: String? = null,
    val uploadTarget: CalendarIdentity? = null,
)

internal enum class AttemptPhase { PREPARED, ATTEMPTED, CONFIRMED }
internal val WriteIntent.phase get() = when {
    confirmed -> AttemptPhase.CONFIRMED
    attempted -> AttemptPhase.ATTEMPTED
    else -> AttemptPhase.PREPARED
}

internal val WriteIntent.calendarId get() = mapping?.calendarId ?: standardBefore?.calendarId ?: standardAfter?.calendarId

internal val WriteIntent.reservations: Set<String> get() = buildSet {
    mapping?.let { add(phoneIdentity(it.muditaId)); add(standardIdentity(it.standardKey)) }
    phoneBefore?.let { add(phoneIdentity(it.id)) }
    standardBefore?.let { add(standardIdentity(it.key)) }
    insertedId?.let { add(if (kind == WriteKind.IMPORT) phoneIdentity(it) else standardIdentity(EventKey(it))) }
}
internal fun phoneIdentity(id: Long) = "phone:$id"
internal fun standardIdentity(key: EventKey) = "standard:${key.eventId}:${key.instanceStart}"

internal class SyncScope(val chosen: Set<Long>, private val now: Long, private val end: Long) {
    fun identity(event: StandardEvent?, key: EventKey): Boolean = event != null && event.calendarId in chosen &&
        event.key == key && event.recurring == (key.instanceStart != null)
    fun includes(event: StandardEvent?, key: EventKey): Boolean = identity(event, key) &&
        (key.instanceStart == null || (checkNotNull(event).end > now && event.start < end))
    fun owns(event: StandardEvent?, row: Mapping) = identity(event, row.standardKey) && event?.calendarId == row.calendarId
    fun includes(event: StandardEvent?, row: Mapping) = event?.calendarId == row.calendarId && includes(event, row.standardKey)
}

enum class WriteOutcome { DONE, RETRY, UNRESOLVED }
/** Discard and acknowledgement never grant permission to issue a provider write. */
internal enum class WritePermission { BLOCK, DISCARD, CANCEL_RETIREMENT, RECONCILE, RETIRE, ACKNOWLEDGE }
data class PassResult(val retry: Boolean = false, val unresolved: Int = 0, val changes: Int = 0,
    val stopReasons: Set<SyncStopReason> = emptySet(), val held: List<HeldCalendarLoss> = emptyList())

/** External evidence/decision, never an inference from a fingerprint or a missing row. */
sealed class WriteResolution {
    data class Applied(val providerId: Long? = null) : WriteResolution()
    object NotApplied : WriteResolution()
}

/** Owns item reservations, durable provider evidence and atomic baseline acknowledgement. */
internal class WriteJournal(
    private val phone: MuditaCalendarPort, private val standard: StandardCalendarPort,
    private val store: MappingStore, private val mapper: EventMapper,
    private val scope: SyncScope,
    private val permission: (WriteIntent) -> WritePermission,
    private val witnessesStillCurrent: (WriteIntent) -> Boolean = { true },
    private val changed: () -> Unit = {},
) {
    var retry = false
        private set
    var unresolved = 0
        private set
    private val visited = mutableSetOf<String>()
    private val deferred = mutableSetOf<String>()
    private val phoneInserts = mutableSetOf<Long>()
    private val phoneDeletes = mutableSetOf<Long>()
    fun expectedPhoneIds(assessed: Set<Long>) = assessed - phoneDeletes + phoneInserts
    fun reserved(phoneId: Long) = phoneIdentity(phoneId) in reservedIdentities()
    fun reserved(key: EventKey) = standardIdentity(key) in reservedIdentities()
    fun reserved(event: MuditaEvent) = reserved(event.id) || store.pending().any {
        it.kind == WriteKind.IMPORT && it.id in event.insertReceipts
    }
    private fun reservedIdentities() = store.pending().flatMap { it.reservations }.toSet() + deferred
    fun recover(decide: (WriteIntent) -> WritePermission = permission) {
        for (intent in store.pending()) attempt(intent, decide(intent))
    }

    /** Called only for an unchanged, approved hold member, after live checks. */
    fun supersedePrepared(row: Mapping): Boolean {
        val keys = setOf(phoneIdentity(row.muditaId), standardIdentity(row.standardKey))
        val owners = store.pending().filter { it.reservations.any(keys::contains) }
        if (owners.any { it.phase != AttemptPhase.PREPARED } || keys.any(deferred::contains)) return false
        for (owner in owners) if (attempt(owner, WritePermission.DISCARD) != WriteOutcome.DONE) return false
        return true
    }

    fun write(original: WriteIntent): WriteOutcome {
        check(original.phase == AttemptPhase.PREPARED)
        val allowed = permission(original)
        if (allowed != WritePermission.RECONCILE && allowed != WritePermission.RETIRE) return WriteOutcome.UNRESOLVED
        fun occupied(): WriteOutcome { unresolved++; return WriteOutcome.UNRESOLVED }
        if (original.reservations.any { it in reservedIdentities() }) return occupied()
        // Persist the receipt before the provider call. It is not ordinary event content.
        val intent = if (original.kind == WriteKind.IMPORT)
            original.copy(phoneAfter = checkNotNull(original.phoneAfter).copy(insertReceipts = setOf(original.id))) else original
        // Diff-time checks are advisory. Only the store can acquire all identities atomically.
        if (!store.tryPrepare(intent)) return occupied()
        return attempt(intent, allowed)
    }
    private fun attempt(intent: WriteIntent, allowed: WritePermission): WriteOutcome {
        if (allowed == WritePermission.BLOCK) return WriteOutcome.UNRESOLVED
        if (!visited.add(intent.id)) return WriteOutcome.UNRESOLVED
        val result = try {
            when (allowed) {
                WritePermission.DISCARD -> {
                    check(intent.phase == AttemptPhase.PREPARED)
                    finish(intent)
                }
                WritePermission.ACKNOWLEDGE -> acknowledge(intent)
                WritePermission.CANCEL_RETIREMENT -> cancelRetirement(intent)
                WritePermission.RETIRE, WritePermission.RECONCILE -> reconcile(intent, allowed)
                WritePermission.BLOCK -> error("Blocked before reconciliation")
            }
        } catch (_: Exception) { WriteOutcome.RETRY }
        if (result == WriteOutcome.RETRY) retry = true
        if (result == WriteOutcome.UNRESOLVED) unresolved++
        // Do not rediff an affected item again in this pass, even if its intent was acknowledged.
        if (result != WriteOutcome.DONE) deferred.addAll(intent.reservations)
        return result
    }
    fun resolve(id: String, resolution: WriteResolution) {
        val intent = store.pending().single { it.id == id }
        check(intent.attempted && !intent.confirmed) { "Write is not awaiting external evidence" }
        val resolved = when (resolution) {
            WriteResolution.NotApplied -> intent.copy(attempted = false, insertedId = null)
            is WriteResolution.Applied -> {
                val inserting = intent.kind == WriteKind.IMPORT || intent.kind == WriteKind.UPLOAD
                check(!inserting || resolution.providerId != null) { "An applied insertion requires its provider-returned id" }
                intent.copy(confirmed = true, insertedId = resolution.providerId ?: intent.insertedId)
            }
        }
        store.prepare(resolved)
    }
    private fun phoneNow(id: Long) = phone.all().singleOrNull { it.id == id }
    private fun finish(intent: WriteIntent, mapping: Mapping? = null, remove: Long? = null): WriteOutcome {
        store.acknowledge(intent.id, mapping, remove)
        return WriteOutcome.DONE
    }
    private fun pair(phone: MuditaEvent, remote: StandardEvent) = Mapping(phone.id, remote.key,
        remote.calendarId, mapper.muditaFingerprint(phone, remote.location.isNotEmpty()),
        mapper.standardFingerprint(remote), remote.location.isNotEmpty())
    private fun acknowledge(intent: WriteIntent) = when (intent.kind) {
        WriteKind.PHONE_DELETE, WriteKind.STANDARD_DELETE -> acknowledgeDelete(intent)
        WriteKind.PHONE_UPDATE, WriteKind.STANDARD_UPDATE -> {
            check(intent.phase == AttemptPhase.CONFIRMED)
            finish(intent, pair(checkNotNull(intent.phoneAfter ?: intent.phoneBefore),
                checkNotNull(intent.standardAfter ?: intent.standardBefore)))
        }
        else -> error("Insert evidence is reconciled separately")
    }
    private fun acknowledgeDelete(intent: WriteIntent): WriteOutcome {
        val row = checkNotNull(intent.mapping)
        if (!intent.confirmed) {
            if (!intent.attempted) return WriteOutcome.UNRESOLVED
            val stillMissing = when (intent.kind) {
                WriteKind.STANDARD_DELETE -> standard.read(row.standardKey) == null && phoneNow(row.muditaId) == null
                WriteKind.PHONE_DELETE -> phoneNow(row.muditaId) == null
                else -> false
            }
            if (!stillMissing) return WriteOutcome.RETRY
        }
        return finish(intent, remove = row.muditaId)
    }
    private fun cancelRetirement(intent: WriteIntent): WriteOutcome {
        check(intent.kind == WriteKind.PHONE_DELETE && intent.phase == AttemptPhase.ATTEMPTED && intent.lossId == null)
        val row = checkNotNull(intent.mapping)
        val before = checkNotNull(intent.phoneBefore)
        // The exact copy still exists and is again needed in its original
        // calendar. This is evidence for release, not permission to delete it.
        return if (phoneNow(row.muditaId) == before && scope.includes(standard.read(row.standardKey), row) &&
            witnessesStillCurrent(intent)) finish(intent) else WriteOutcome.UNRESOLVED
    }
    private fun reconcile(original: WriteIntent, allowed: WritePermission): WriteOutcome {
        var intent = original
        when (intent.kind) {
            WriteKind.IMPORT -> {
                var desired = checkNotNull(intent.phoneAfter)
                val source = checkNotNull(intent.standardBefore)
                if (intent.insertedId == null) {
                    val mapped = store.all().map { it.muditaId }.toSet()
                    val candidates = phone.all().filter { it.insertReceipts == setOf(intent.id) &&
                        it.id !in intent.phoneIdsBefore && it.id !in mapped }
                    if (candidates.size > 1) return WriteOutcome.UNRESOLVED
                    candidates.singleOrNull()?.let {
                        intent = intent.copy(insertedId = it.id, confirmed = true); store.prepare(intent)
                    }
                }
                if (!intent.attempted) {
                    if (intent.insertedId != null) return acknowledgeImport(intent, desired, source)
                    if (!scope.includes(source, source.key) || standard.read(source.key) != source) return finish(intent)
                    if (!witnessesStillCurrent(intent)) return WriteOutcome.RETRY
                    desired = desired.copy(insertReceipts = setOf(intent.id))
                    intent = intent.copy(attempted = true, phoneAfter = desired)
                    store.prepare(intent)
                    val id = phone.insert(desired)
                    if (id == null) return WriteOutcome.RETRY
                    phoneDeletes.remove(id); phoneInserts.add(id)
                    changed()
                    intent = intent.copy(insertedId = id, confirmed = true); store.prepare(intent)
                }
                if (intent.insertedId == null) return WriteOutcome.UNRESOLVED
                return acknowledgeImport(intent, desired, source)
            }
            WriteKind.UPLOAD -> {
                val source = checkNotNull(intent.phoneBefore)
                val desired = checkNotNull(intent.standardAfter)
                var id = intent.insertedId ?: standard.findInsert(intent.id)
                if (id == null) {
                    if (intent.attempted) return WriteOutcome.UNRESOLVED
                    if (desired.calendarId !in scope.chosen || phoneNow(source.id) != source) return finish(intent)
                    if (!witnessesStillCurrent(intent)) return WriteOutcome.RETRY
                    if (intent.uploadTarget != null && standard.calendars().none {
                            it.eligible && it.identity == intent.uploadTarget
                        }) return WriteOutcome.RETRY
                    intent = intent.copy(attempted = true); store.prepare(intent)
                    id = standard.insert(desired.calendarId, desired, intent.id) ?: return WriteOutcome.RETRY
                    changed()
                }
                intent = intent.copy(insertedId = id, confirmed = true); store.prepare(intent)
                val actual = standard.read(EventKey(id))
                val expected = desired.copy(id = id)
                val matches = actual != null && mapper.standardFingerprint(actual) == mapper.standardFingerprint(expected)
                finish(intent, pair(source, expected))
                return if (matches) WriteOutcome.DONE else WriteOutcome.RETRY
            }
            WriteKind.PHONE_UPDATE -> {
                val before = checkNotNull(intent.phoneBefore)
                val desired = checkNotNull(intent.phoneAfter)
                val source = checkNotNull(intent.standardBefore)
                val current = phoneNow(before.id)
                if (intent.confirmed) return finish(intent, pair(desired, source))
                if (current != desired) {
                    if (intent.attempted && current != before) return WriteOutcome.UNRESOLVED
                    if (!scope.includes(source, source.key) || current != before || standard.read(source.key) != source)
                        return if (intent.attempted) WriteOutcome.UNRESOLVED else finish(intent)
                    if (!witnessesStillCurrent(intent)) return WriteOutcome.RETRY
                    intent = intent.copy(attempted = true); store.prepare(intent)
                    if (!phone.update(before.id, desired)) return WriteOutcome.RETRY
                    changed()
                    intent = intent.copy(confirmed = true); store.prepare(intent)
                }
                phoneNow(before.id) // Readback is observation, never a replacement for provider evidence.
                return finish(intent, pair(desired, source))
            }
            WriteKind.PHONE_DELETE -> {
                val row = checkNotNull(intent.mapping)
                val current = phoneNow(row.muditaId)
                if (intent.confirmed) return acknowledgeDelete(intent)
                if (intent.attempted && current == null) return WriteOutcome.RETRY
                // An obsolete, unattempted retirement must not erase a user-deletion baseline.
                if (intent.lossId == null && scope.includes(standard.read(row.standardKey), row))
                    return if (intent.attempted) cancelRetirement(intent) else finish(intent)
                if (intent.lossId != null && standard.read(row.standardKey) != intent.standardBefore) return WriteOutcome.UNRESOLVED
                if (allowed != WritePermission.RETIRE) return WriteOutcome.UNRESOLVED
                if (!witnessesStillCurrent(intent)) return WriteOutcome.RETRY
                if (current == null) return finish(intent, remove = row.muditaId)
                if (current != intent.phoneBefore) return if (intent.attempted || intent.lossId != null) WriteOutcome.UNRESOLVED else finish(intent)
                intent = intent.copy(attempted = true); store.prepare(intent)
                if (!phone.delete(row.muditaId)) return WriteOutcome.RETRY
                phoneInserts.remove(row.muditaId); phoneDeletes.add(row.muditaId)
                changed()
                intent = intent.copy(confirmed = true); store.prepare(intent)
                return finish(intent, remove = row.muditaId)
            }
            WriteKind.STANDARD_UPDATE -> {
                val before = checkNotNull(intent.standardBefore)
                val desired = checkNotNull(intent.standardAfter)
                val source = checkNotNull(intent.phoneBefore)
                val current = standard.read(before.key)
                if (intent.confirmed) return finish(intent, pair(source, desired))
                if (current != desired) {
                    if (intent.attempted && current != before) return WriteOutcome.UNRESOLVED
                    if (!scope.identity(before, before.key) || current != before || phoneNow(source.id) != source)
                        return if (intent.attempted) WriteOutcome.UNRESOLVED else finish(intent)
                    if (!witnessesStillCurrent(intent)) return WriteOutcome.RETRY
                    intent = intent.copy(attempted = true); store.prepare(intent)
                    if (!standard.update(before.key, desired)) return WriteOutcome.RETRY
                    changed()
                    intent = intent.copy(confirmed = true); store.prepare(intent)
                }
                standard.read(before.key)
                return finish(intent, pair(source, desired))
            }
            WriteKind.STANDARD_DELETE -> {
                val row = checkNotNull(intent.mapping)
                if (intent.confirmed) return acknowledgeDelete(intent)
                val before = intent.standardBefore
                val current = standard.read(row.standardKey)
                if (before == null) {
                    // Exact approval can retire an already absent pair, but it
                    // cannot issue any provider call that changes data.
                    check(intent.lossId != null && allowed == WritePermission.RETIRE && intent.phase == AttemptPhase.PREPARED)
                    return if (current == null && phoneNow(row.muditaId) == null && witnessesStillCurrent(intent)) finish(intent, remove = row.muditaId)
                    else WriteOutcome.RETRY
                }
                // Absence arrived after assessment. Reassess the whole cohort before drawing a conclusion.
                if (current == null) return WriteOutcome.RETRY
                if (intent.attempted && current != before) return WriteOutcome.UNRESOLVED
                if (!scope.owns(before, row) || current != before || phoneNow(row.muditaId) != null)
                    return if (intent.attempted || intent.lossId != null) WriteOutcome.UNRESOLVED else finish(intent)
                if (!witnessesStillCurrent(intent)) return WriteOutcome.RETRY
                intent = intent.copy(attempted = true); store.prepare(intent)
                if (!standard.delete(before.key)) return WriteOutcome.RETRY
                changed()
                intent = intent.copy(confirmed = true); store.prepare(intent)
                return finish(intent, remove = row.muditaId)
            }
        }
    }
    private fun acknowledgeImport(intent: WriteIntent, desired: MuditaEvent, source: StandardEvent): WriteOutcome {
        val expected = desired.copy(id = checkNotNull(intent.insertedId))
        if (store.all().any { it.muditaId == expected.id && it.standardKey != source.key }) return WriteOutcome.UNRESOLVED
        val actual = phoneNow(expected.id)
        if (actual != null && intent.id in actual.insertReceipts) {
            // The provider ID is already durable. Retry this exact cleanup, not
            // the insert, if either the write result or the readback is lost.
            if (actual.insertReceipts != setOf(intent.id)) return WriteOutcome.UNRESOLVED
            if (!phone.update(actual.id, actual.copy(insertReceipts = emptySet()))) return WriteOutcome.RETRY
            val cleaned = phoneNow(expected.id) ?: return WriteOutcome.RETRY
            if (intent.id in cleaned.insertReceipts) return WriteOutcome.RETRY
        }
        val matches = actual != null && mapper.muditaFingerprint(actual, source.location.isNotEmpty()) ==
            mapper.muditaFingerprint(expected, source.location.isNotEmpty())
        finish(intent, pair(expected, source), intent.mapping?.muditaId)
        return if (matches) WriteOutcome.DONE else WriteOutcome.RETRY
    }
}
