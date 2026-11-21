package com.alexindigo.kotatsu.sync

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class BridgeEngineTest {
    private val now = 1800000000000L
    private val mapper = EventMapper(ZoneId.of("UTC"))
    private val phone = Phone()
    private val server = Server()
    private val store = MemoryMappings()
    private var settings = SyncSettings(setOf(1), 1)
    private val engine = BridgeEngine(phone, server, store, object : SyncConfig { override fun read() = settings }, mapper)
    private fun event(id: Long = 1) = StandardEvent(id, 1, "Title $id", "Body", "Home", now + 1000, now + 2000)
    private fun seed(): MuditaEvent { server.rows[EventKey(1)] = event(); engine.pass(now); return phone.rows.values.single() }

    @Test fun standardNewEditDelete() {
        val original = seed()
        server.rows[EventKey(1)] = event().copy(title = "Changed")
        engine.pass(now); assertEquals("Changed", phone.rows[original.id]!!.title)
        server.rows.clear(); engine.pass(now); assertTrue(phone.rows.isEmpty()); assertTrue(store.rows.isEmpty())
    }
    @Test fun phoneNewEditDelete() {
        engine.pass(now)
        phone.rows[10] = MuditaEvent(10, "Phone", "Body", now, now + 1000)
        engine.pass(now); val key = server.rows.keys.single()
        assertEquals("Phone", server.rows[key]!!.title)
        phone.rows[10] = phone.rows[10]!!.copy(title = "Edited")
        engine.pass(now); assertEquals("Edited", server.rows[key]!!.title)
        phone.rows.clear(); engine.pass(now); assertTrue(server.rows.isEmpty())
    }
    @Test fun doubleChangeStandardWins() {
        val original = seed()
        phone.rows[original.id] = original.copy(title = "Phone edit")
        server.rows[EventKey(1)] = event().copy(title = "Server edit")
        engine.pass(now)
        assertEquals("Server edit", phone.rows[original.id]!!.title)
        assertEquals("Server edit", server.rows[EventKey(1)]!!.title)
    }
    @Test fun simultaneousPhoneDeleteAndServerEditRecreatesPhoneCopy() {
        seed(); phone.rows.clear(); server.rows[EventKey(1)] = event().copy(title = "Server edit")
        engine.pass(now); assertEquals("Server edit", phone.rows.values.single().title)
    }
    @Test fun schemaGuardWritesNothing() {
        seed(); phone.rows.clear(); phone.schema = false
        val writes = phone.writes + server.writes
        engine.pass(now)
        assertEquals(writes, phone.writes + server.writes); assertTrue(server.rows.isNotEmpty())
        assertTrue(store.status.startsWith("stopped:"))
    }
    @Test fun firstRunUploadAndMatchDoNotDuplicate() {
        val remote = event(); server.rows[remote.key] = remote
        phone.rows[10] = mapper.toMudita(remote).copy(id = 10, description = "Earlier body")
        phone.rows[11] = MuditaEvent(11, "Local", "Upload", now, now + 1000)
        engine.pass(now)
        assertEquals(2, server.rows.size); assertEquals(2, phone.rows.size); assertEquals(2, store.rows.size)
        assertTrue(store.firstRunDone); assertEquals("Location: Home${EventMapper.LOCATION_MARKER}\n${EventMapper.BODY_MARKER}Body", phone.rows[10]!!.description)
        val writes = phone.writes + server.writes
        engine.pass(now); assertEquals(writes, phone.writes + server.writes)
    }
    @Test fun idempotentSecondPassHasNoWrites() {
        seed(); val writes = phone.writes + server.writes; val saves = store.saves
        engine.pass(now); assertEquals(writes, phone.writes + server.writes); assertEquals(saves, store.saves)
    }
    @Test fun occurrenceEditAndDeleteUseOnlyTheirDate() {
        val one = event().copy(recurring = true, instanceStart = now + 1000)
        val two = one.copy(start = now + BridgeEngine.WEEK, end = now + BridgeEngine.WEEK + 1000, instanceStart = now + BridgeEngine.WEEK)
        server.rows[one.key] = one; server.rows[two.key] = two; engine.pass(now)
        val row = store.rows.values.first { it.standardKey == one.key }
        phone.rows[row.muditaId] = phone.rows[row.muditaId]!!.copy(title = "Only one")
        engine.pass(now); assertEquals("Only one", server.rows[one.key]!!.title); assertEquals(two, server.rows[two.key])
        phone.rows.remove(row.muditaId); engine.pass(now)
        assertNull(server.rows[one.key]); assertEquals(two, server.rows[two.key])
    }
    @Test fun horizonRollAddsAndRemovesOccurrences() {
        for (week in 0..9) {
            val instance = event().copy(recurring = true, start = now + week * BridgeEngine.WEEK + 1000,
                end = now + week * BridgeEngine.WEEK + 2000, instanceStart = now + week * BridgeEngine.WEEK + 1000)
            server.rows[instance.key] = instance
        }
        engine.pass(now); assertEquals(8, phone.rows.size)
        engine.pass(now + BridgeEngine.WEEK); assertEquals(8, phone.rows.size)
        assertTrue(phone.rows.values.all { it.start >= now + BridgeEngine.WEEK })
        assertEquals(10, server.rows.size)
    }
    @Test fun seriesEndingInsideHorizonDoesNotInventInstances() {
        repeat(3) { week -> val item = event().copy(recurring = true, instanceStart = now + week * BridgeEngine.WEEK + 1000,
            start = now + week * BridgeEngine.WEEK + 1000); server.rows[item.key] = item }
        engine.pass(now); assertEquals(3, phone.rows.size)
    }
    @Test fun unchosenOrNoLongerSyncingCalendarRemovesOnlyPhoneCopies() {
        seed(); settings = settings.copy(calendars = emptySet())
        engine.pass(now); assertTrue(phone.rows.isEmpty()); assertEquals(1, server.rows.size)
        settings = settings.copy(calendars = setOf(1)); engine.pass(now); assertEquals(1, phone.rows.size)
        server.calendarList = listOf(SyncCalendar(1, "dav", false)); engine.pass(now)
        assertTrue(phone.rows.isEmpty()); assertEquals(1, server.rows.size)
    }
    @Test fun localCalendarsNeverBridgedOrWritten() {
        server.calendarList = listOf(SyncCalendar(1, "LOCAL", true)); server.rows[event().key] = event()
        engine.pass(now); assertTrue(phone.rows.isEmpty()); assertEquals(0, server.writes)
    }
    @Test fun phoneDescriptionEditsKeepLocationAndPreReminderChoice() {
        val original = seed(); phone.rows[original.id] = original.copy(preReminder = true,
            description = original.description.replace("Home", "Elsewhere").replace("Body", "New body"))
        engine.pass(now); assertEquals("Home", server.rows[EventKey(1)]!!.location)
        assertEquals("New body", server.rows[EventKey(1)]!!.description)
        server.rows[EventKey(1)] = server.rows[EventKey(1)]!!.copy(title = "Changed again")
        engine.pass(now); assertTrue(phone.rows[original.id]!!.preReminder)
    }
    @Test fun targetThatChangesAfterSnapshotIsSkipped() {
        val original = seed(); server.rows[EventKey(1)] = event().copy(title = "Server")
        phone.onRead = { phone.rows[original.id] = original.copy(title = "Concurrent") }
        engine.pass(now); assertEquals("Concurrent", phone.rows[original.id]!!.title)
        assertEquals("Server", server.rows[EventKey(1)]!!.title)
        engine.pass(now); assertEquals("Server", phone.rows[original.id]!!.title)
    }
    @Test fun timedToAllDayUsesUtcAndAllDayToTimedUsesDeviceZone() {
        val local = EventMapper(ZoneId.of("America/Los_Angeles"))
        val timed = event().copy(timeZone = "Europe/Paris")
        val allDay = local.toStandard(local.toMudita(timed).copy(allDay = true), timed)
        assertEquals("UTC", allDay.timeZone)
        val back = local.toStandard(local.toMudita(allDay).copy(allDay = false), allDay)
        assertEquals("America/Los_Angeles", back.timeZone)
    }

    @Test fun standaloneBecomingSeriesRetiresOnlyObsoletePhoneCopy() {
        val original = seed()
        server.rows[EventKey(1)] = event().copy(recurring = true)
        val occurrence = event().copy(recurring = true, instanceStart = event().start)
        server.rows[occurrence.key] = occurrence
        engine.pass(now)
        assertFalse(phone.rows.containsKey(original.id))
        assertEquals(listOf(occurrence.key), store.rows.values.map { it.standardKey })
        assertTrue(server.rows.containsKey(EventKey(1)))
    }
    @Test fun movingEventToUnselectedCalendarRemovesOnlyItsPhoneCopy() {
        seed(); server.rows[EventKey(1)] = event().copy(calendarId = 2)
        engine.pass(now)
        assertTrue(phone.rows.isEmpty()); assertTrue(store.rows.isEmpty())
        assertEquals(2L, server.rows[EventKey(1)]!!.calendarId)
    }
    @Test fun expiredOccurrencePhoneEditIsUploadedBeforeEviction() {
        val occurrence = event().copy(recurring = true, instanceStart = event().start)
        server.rows[occurrence.key] = occurrence; engine.pass(now)
        val row = store.rows.values.single()
        phone.rows[row.muditaId] = phone.rows[row.muditaId]!!.copy(title = "Last minute edit")
        engine.pass(now + BridgeEngine.WEEK)
        assertEquals("Last minute edit", server.rows[occurrence.key]!!.title)
        assertTrue(phone.rows.isEmpty()); assertTrue(store.rows.isEmpty())
    }
    @Test fun expiredOccurrencePhoneDeletionCancelsThatDate() {
        val occurrence = event().copy(recurring = true, instanceStart = event().start)
        server.rows[occurrence.key] = occurrence; engine.pass(now); phone.rows.clear()
        engine.pass(now + BridgeEngine.WEEK)
        assertNull(server.rows[occurrence.key]); assertTrue(store.rows.isEmpty())
    }
    @Test fun expiredOccurrenceConflictStillKeepsServerVersion() {
        val occurrence = event().copy(recurring = true, instanceStart = event().start)
        server.rows[occurrence.key] = occurrence; engine.pass(now)
        val row = store.rows.values.single()
        phone.rows[row.muditaId] = phone.rows[row.muditaId]!!.copy(title = "Phone")
        server.rows[occurrence.key] = occurrence.copy(title = "Server")
        engine.pass(now + BridgeEngine.WEEK)
        assertEquals("Server", server.rows[occurrence.key]!!.title); assertTrue(phone.rows.isEmpty())
    }
    @Test fun interruptedImportIsRecoveredBeforeUnmappedRowsAreUploaded() {
        engine.pass(now); server.rows[EventKey(1)] = event()
        store.failSave = true
        try { engine.pass(now); fail("Expected simulated process death") } catch (_: ProcessDeath) { }
        engine.pass(now)
        assertEquals(1, phone.rows.size); assertEquals(1, server.rows.size); assertEquals(1, store.rows.size)
    }
    @Test fun interruptedRecreationIsRecoveredBeforeAnotherImport() {
        seed(); phone.rows.clear(); server.rows[EventKey(1)] = event().copy(title = "Server edit")
        store.failSave = true
        try { engine.pass(now); fail("Expected simulated process death") } catch (_: ProcessDeath) { }
        engine.pass(now)
        assertEquals(1, phone.rows.size); assertEquals(1, server.rows.size); assertEquals(1, store.rows.size)
    }
    @Test fun interruptedUploadIsRecoveredByItsInsertIdentity() {
        engine.pass(now); phone.rows[10] = MuditaEvent(10, "Phone", "Body", now, now + 1000)
        store.failSave = true
        try { engine.pass(now); fail("Expected simulated process death") } catch (_: ProcessDeath) { }
        engine.pass(now)
        assertEquals(1, phone.rows.size); assertEquals(1, server.rows.size); assertEquals(1, store.rows.size)
    }

    @Test fun interruptedPhoneUpdateAcknowledgesTheWriteWithoutRepeatingIt() {
        seed(); server.rows[EventKey(1)] = event().copy(title = "New remote")
        store.failSave = true
        try { engine.pass(now); fail("Expected death") } catch (_: ProcessDeath) { }
        val writes = phone.writes
        engine.pass(now)
        assertEquals(writes, phone.writes); assertEquals("New remote", phone.rows.values.single().title)
        assertNull(store.pending())
    }
    @Test fun interruptedPhoneDeletionNeverUploadsTheRemovedCopy() {
        seed(); server.rows.clear(); store.failSave = true
        try { engine.pass(now); fail("Expected death") } catch (_: ProcessDeath) { }
        engine.pass(now)
        assertTrue(phone.rows.isEmpty()); assertTrue(server.rows.isEmpty()); assertTrue(store.rows.isEmpty())
        assertNull(store.pending())
    }
    @Test fun interruptedStandardUpdateAcknowledgesWithoutRepeatingOrLosingEdits() {
        val p = seed(); phone.rows[p.id] = p.copy(title = "Phone change"); store.failSave = true
        try { engine.pass(now); fail("Expected death") } catch (_: ProcessDeath) { }
        val writes = server.writes
        engine.pass(now)
        assertEquals(writes, server.writes); assertEquals("Phone change", server.rows[EventKey(1)]!!.title)
        assertNull(store.pending())
    }
    @Test fun interruptedStandardDeletionDoesNotResurrectEitherCopy() {
        seed(); phone.rows.clear(); store.failSave = true
        try { engine.pass(now); fail("Expected death") } catch (_: ProcessDeath) { }
        engine.pass(now)
        assertTrue(phone.rows.isEmpty()); assertTrue(server.rows.isEmpty()); assertTrue(store.rows.isEmpty())
        assertNull(store.pending())
    }
    @Test fun failedExpiredEditRetainsCopyAndMappingUntilTheProviderAcceptsIt() {
        val occurrence = event().copy(recurring = true, instanceStart = event().start)
        server.rows[occurrence.key] = occurrence; engine.pass(now)
        val p = phone.rows.values.single(); phone.rows[p.id] = p.copy(title = "Not lost")
        server.allowUpdate = false; engine.pass(now + BridgeEngine.WEEK)
        assertEquals(1, phone.rows.size); assertEquals(1, store.rows.size); assertNotNull(store.pending())
        server.allowUpdate = true; engine.pass(now + BridgeEngine.WEEK)
        assertEquals("Not lost", server.rows[occurrence.key]!!.title)
        assertTrue(phone.rows.isEmpty()); assertTrue(store.rows.isEmpty()); assertNull(store.pending())
    }
    @Test fun expiredOccurrenceMovedBackIntoTheWindowKeepsItsPhoneIdentity() {
        val occurrence = event().copy(recurring = true, instanceStart = event().start)
        server.rows[occurrence.key] = occurrence; engine.pass(now)
        val p = phone.rows.values.single()
        phone.rows[p.id] = p.copy(start = p.start + 2 * BridgeEngine.WEEK, end = p.end + 2 * BridgeEngine.WEEK)
        engine.pass(now + BridgeEngine.WEEK)
        assertEquals(p.id, phone.rows.values.single().id)
        assertEquals(p.id, store.rows.values.single().muditaId)
        assertEquals(p.start + 2 * BridgeEngine.WEEK, server.rows[occurrence.key]!!.start)
    }
    @Test fun ambiguousInterruptedImportDoesNotDuplicateOrGuessOwnership() {
        engine.pass(now); val remote = event(); server.rows[remote.key] = remote
        val intent = WriteIntent(kind = WriteKind.IMPORT, phoneAfter = mapper.toMudita(remote), standardBefore = remote, attempted = true)
        store.prepare(intent)
        phone.rows[10] = mapper.toMudita(remote).copy(id = 10); phone.rows[11] = mapper.toMudita(remote).copy(id = 11)
        engine.pass(now)
        assertEquals(2, phone.rows.size); assertEquals(1, server.rows.size); assertEquals(intent, store.pending())
        assertTrue(store.status.contains("incomplete"))
    }
    @Test fun uncertainInsertWithNoRecoverableRowIsNeverBlindlyRepeated() {
        engine.pass(now)
        val remote = event(); server.rows[remote.key] = remote
        store.prepare(WriteIntent(kind = WriteKind.IMPORT, phoneAfter = mapper.toMudita(remote), standardBefore = remote, attempted = true))
        engine.pass(now); assertEquals(0, phone.writes); assertNotNull(store.pending())
        store.intent = null
        val p = MuditaEvent(10, "Phone", "Body", now, now + 1000); phone.rows[10] = p
        store.prepare(WriteIntent(kind = WriteKind.UPLOAD, phoneBefore = p,
            standardAfter = mapper.toStandard(p, event().copy(id = 0, location = "")), attempted = true))
        engine.pass(now); assertEquals(0, server.writes); assertNotNull(store.pending())
    }
    @Test fun ordinaryLocationTextOnNewPhoneEventsIsUploadedIntact() {
        engine.pass(now)
        phone.rows[10] = MuditaEvent(10, "Phone", "Location: Gate A\nBring ID", now, now + 1000)
        engine.pass(now)
        assertEquals("Location: Gate A\nBring ID", server.rows.values.single().description)
        assertEquals("", server.rows.values.single().location)
    }
    @Test fun schemaGuardAlsoPreventsPendingWriteRecovery() {
        val remote = event(); server.rows[remote.key] = remote
        store.prepare(WriteIntent(kind = WriteKind.IMPORT, phoneAfter = mapper.toMudita(remote), standardBefore = remote))
        phone.schema = false; engine.pass(now)
        assertEquals(0, phone.writes); assertNotNull(store.pending())
    }
    @Test fun importReadbackDriftKeepsIdentityAndReconcilesThePhoneEditNextPass() {
        engine.pass(now); server.rows[event().key] = event()
        phone.afterInsert = { id -> phone.rows[id] = phone.rows[id]!!.copy(title = "Edit after insert") }
        engine.pass(now)
        assertTrue(store.status.contains("incomplete")); assertEquals(1, store.rows.size)
        engine.pass(now)
        assertEquals(1, phone.rows.size); assertEquals(1, server.rows.size)
        assertEquals("Edit after insert", server.rows.values.single().title)
    }
    @Test fun uploadReadbackDriftKeepsIdentityAndAppliesTheServerWinnerNextPass() {
        engine.pass(now); phone.rows[10] = MuditaEvent(10, "Phone", "Body", now, now + 1000)
        server.afterInsert = { key -> server.rows[key] = server.rows[key]!!.copy(title = "Server change after insert") }
        engine.pass(now)
        assertTrue(store.status.contains("incomplete")); assertEquals(1, store.rows.size)
        engine.pass(now)
        assertEquals(1, phone.rows.size); assertEquals(1, server.rows.size)
        assertEquals("Server change after insert", phone.rows.values.single().title)
    }
    @Test fun pendingWriteCannotModifyACalendarThatHasSinceBeenUnselected() {
        val p = seed(); val before = server.rows[EventKey(1)]!!
        val edited = p.copy(title = "Pending edit"); phone.rows[p.id] = edited
        store.prepare(WriteIntent(kind = WriteKind.STANDARD_UPDATE, mapping = store.rows.values.single(),
            phoneBefore = edited, standardBefore = before, standardAfter = mapper.toStandard(edited, before, true)))
        settings = settings.copy(calendars = emptySet())
        engine.pass(now)
        assertEquals(before, server.rows[EventKey(1)]); assertEquals(0, server.writes)
        assertTrue(phone.rows.isEmpty()); assertTrue(store.rows.isEmpty()); assertNull(store.pending())
    }
    @Test fun unattemptedUploadDoesNotWriteToANoLongerSyncingDestination() {
        engine.pass(now)
        val p = MuditaEvent(10, "Phone", "Body", now, now + 1000); phone.rows[p.id] = p
        val template = event().copy(id = 0, location = "")
        store.prepare(WriteIntent(kind = WriteKind.UPLOAD, phoneBefore = p, standardAfter = mapper.toStandard(p, template)))
        server.calendarList = listOf(SyncCalendar(1, "dav", false))
        engine.pass(now)
        assertEquals(0, server.writes); assertEquals(p, phone.rows[p.id]); assertNull(store.pending())
    }

    private class ProcessDeath : Error()
    private class Phone : MuditaCalendarPort {
        val rows = linkedMapOf<Long, MuditaEvent>(); var schema = true; var writes = 0; var onRead: (() -> Unit)? = null
        private var nextId = 1L
        var afterInsert: ((Long) -> Unit)? = null
        override fun schemaOk() = schema
        override fun all(): List<MuditaEvent> { val snapshot = rows.values.toList(); onRead?.also { onRead = null; it() }; return snapshot }
        override fun insert(event: MuditaEvent): Long {
            writes++; val id = maxOf(nextId, (rows.keys.maxOrNull() ?: 0) + 1); nextId = id + 1
            rows[id] = event.copy(id = id)
            afterInsert?.also { afterInsert = null; it(id) }
            return id
        }
        override fun update(id: Long, event: MuditaEvent): Boolean { writes++; rows[id] = event.copy(id = id); return true }
        override fun delete(id: Long): Boolean { writes++; return rows.remove(id) != null }
    }
    private class Server : StandardCalendarPort {
        val rows = linkedMapOf<EventKey, StandardEvent>(); var writes = 0
        val insertIdentities = mutableMapOf<String, Long>()
        var allowUpdate = true
        var afterInsert: ((EventKey) -> Unit)? = null
        var calendarList = listOf(SyncCalendar(1, "dav", true))
        override fun calendars() = calendarList
        override fun events(calendarIds: Set<Long>) = rows.values.filter { it.calendarId in calendarIds && !it.recurring }
        override fun instances(calendarIds: Set<Long>, start: Long, end: Long) = rows.values.filter {
            it.recurring && it.calendarId in calendarIds && it.start >= start && it.start < end
        }
        override fun read(key: EventKey) = rows[key]
        override fun insert(calendarId: Long, event: StandardEvent, intentId: String?): Long {
            writes++; val id = (rows.keys.maxOfOrNull { it.eventId } ?: 0) + 1
            rows[EventKey(id)] = event.copy(id = id, calendarId = calendarId)
            if (intentId != null) insertIdentities[intentId] = id
            afterInsert?.also { afterInsert = null; it(EventKey(id)) }
            return id
        }
        override fun findInsert(intentId: String) = insertIdentities[intentId]
        override fun update(key: EventKey, event: StandardEvent): Boolean {
            if (!allowUpdate) return false
            writes++; rows[key] = event; return true
        }
        override fun delete(key: EventKey): Boolean { writes++; return rows.remove(key) != null }
    }
    private class MemoryMappings : MappingStore {
        val rows = linkedMapOf<Long, Mapping>(); var saves = 0; var failSave = false
        var intent: WriteIntent? = null
        override fun all() = rows.values.toList()
        override fun save(mapping: Mapping) {
            if (failSave) { failSave = false; throw ProcessDeath() }
            saves++; rows[mapping.muditaId] = mapping
        }
        override fun remove(muditaId: Long) { rows.remove(muditaId) }
        override fun pending() = intent
        override fun prepare(intent: WriteIntent) { check(this.intent == null || this.intent!!.id == intent.id); this.intent = intent }
        override fun acknowledge(intentId: String, mapping: Mapping?, removeMuditaId: Long?) {
            check(intent?.id == intentId)
            if (failSave) { failSave = false; throw ProcessDeath() }
            if (removeMuditaId != null) remove(removeMuditaId)
            if (mapping != null) save(mapping)
            intent = null
        }
        override var firstRunDone = false
        override var status = ""
    }
}
