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

    @Test fun bulkPhoneLossDoesNotDeleteAnyOfTheMappedServerEventsEvenAcrossPasses() {
        (1L..5).forEach { server.rows[EventKey(it)] = event(it) }
        engine.pass(now)
        val baseline = store.all()
        baseline.take(3).forEach { phone.rows.remove(it.muditaId) }
        repeat(2) { engine.pass(now) }
        assertEquals(5, server.rows.size)
        assertEquals(baseline, store.all())
    }

    @Test fun twoOrdinaryPhoneDeletesPropagateWhenTheStoreDoesNotReadEmpty() {
        (1L..4).forEach { server.rows[EventKey(it)] = event(it) }
        engine.pass(now)
        store.all().take(2).forEach { phone.rows.remove(it.muditaId) }
        assertEquals(2, engine.pass(now).changes)
        assertEquals(2, server.rows.size)
    }

    @Test fun emptyPhoneStoreWithTwoMappingsRetainsBothCopiesOnTheServer() {
        (1L..2).forEach { server.rows[EventKey(it)] = event(it) }
        engine.pass(now)
        phone.rows.clear()
        assertEquals(0, engine.pass(now).changes)
        assertEquals(2, server.rows.size)
    }

    @Test fun calendarDisappearanceWhileSelectedRetainsTheMappedPhoneCopy() {
        val copy = seed()
        server.calendarList = emptyList()
        assertEquals(0, engine.pass(now).changes)
        assertEquals(copy, phone.rows[copy.id])
        assertEquals(1, store.all().size)
    }

    @Test fun identityChangeHoldsEvenOnePair() {
        server.rows[event().key] = event().copy(syncId = "original")
        engine.pass(now)
        val copy = phone.rows.values.single()
        server.rows[event().key] = event().copy(syncId = "replacement", title = "Replacement")
        val held = engine.pass(now).held.single()
        assertEquals(LossDirection.STANDARD, held.direction)
        assertEquals(copy, phone.rows.values.single())
    }

    @Test fun partialPhoneCursorHoldsAndDoesNotReplayAPendingDeletion() {
        (1L..4).forEach { server.rows[EventKey(it)] = event(it) }
        engine.pass(now)
        val row = store.all().first()
        val original = phone.rows.remove(row.muditaId)!!
        phone.onRead = { phone.rows[row.muditaId] = original }
        val pending = WriteIntent(kind = WriteKind.STANDARD_DELETE, mapping = row, standardBefore = event())
        store.prepare(pending)
        assertEquals(LossDirection.PHONE, engine.pass(now).held.single().direction)
        assertEquals(4, server.rows.size)
        assertEquals(listOf(pending), store.pending())
        assertEquals(4, store.all().size)
    }

    @Test fun deletingOneStandardSeriesRetiresAllItsOccurrencesWithoutHolding() {
        repeat(4) { offset ->
            val start = now + offset * 1000 + 1
            val instance = event().copy(recurring = true, start = start, end = start + 500, instanceStart = start)
            server.rows[instance.key] = instance
        }
        engine.pass(now)
        server.rows.clear()
        server.removedSeries = setOf(1)
        assertTrue(engine.pass(now).held.isEmpty())
        assertTrue(phone.rows.isEmpty())
    }

    @Test fun calendarKeepDoesNotAutomaticallyUploadTheRetiredPhoneCopies() {
        (1L..4).forEach { server.rows[EventKey(it)] = event(it) }
        engine.pass(now)
        (1L..3).forEach { server.rows.remove(EventKey(it)) }
        val hold = engine.pass(now).held.single()
        assertEquals(LossDirection.STANDARD, hold.direction)
        assertEquals(3, hold.count)
        engine.resolveLoss(now, hold.id, LossResolution.KEEP)
        repeat(2) { engine.pass(now) }
        assertEquals(1, server.rows.size)
        assertEquals(4, phone.rows.size)
        assertEquals(1, store.all().size)
    }

    @Test fun exactDeleteApprovalDoesNotIncludeLaterPhoneLoss() {
        (1L..6).forEach { server.rows[EventKey(it)] = event(it) }
        engine.pass(now)
        val pairs = store.all().sortedBy { it.standardKey.eventId }
        pairs.take(3).forEach { phone.rows.remove(it.muditaId) }
        val hold = engine.pass(now).held.single()
        phone.rows.remove(pairs[3].muditaId)
        assertEquals(3, engine.resolveLoss(now, hold.id, LossResolution.DELETE_FROM_SERVER).changes)
        assertEquals(setOf(EventKey(4), EventKey(5), EventKey(6)), server.rows.keys)
    }

    @Test fun statusCountsProviderMutationsButNotStableMappings() {
        server.rows[EventKey(1)] = event()
        assertEquals(1, engine.pass(now).changes)
        assertEquals(0, engine.pass(now).changes)
        server.rows[EventKey(1)] = event().copy(title = "Changed")
        assertEquals(1, engine.pass(now).changes)
        phone.rows.clear()
        assertEquals(1, engine.pass(now).changes)
        assertEquals(0, engine.pass(now).changes)
    }
    @Test fun stoppedPassHasPlainReasonAndNoChanges() {
        phone.schema = false
        val result = engine.pass(now)
        assertEquals(0, result.changes); assertEquals(setOf(SyncStopReason.MUDITA_CALENDAR_CHANGED), result.stopReasons)
        assertFalse(result.retry)
    }

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
    @Test fun unchoosingRetiresCopiesButDisabledSyncHolds() {
        seed(); settings = settings.copy(calendars = emptySet())
        engine.pass(now); assertTrue(phone.rows.isEmpty()); assertEquals(1, server.rows.size)
        settings = settings.copy(calendars = setOf(1)); engine.pass(now); assertEquals(1, phone.rows.size)
        server.calendarList = listOf(SyncCalendar(1, "dav", false))
        assertEquals(LossDirection.STANDARD, engine.pass(now).held.single().direction)
        assertEquals(1, phone.rows.size); assertEquals(1, server.rows.size)
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
    @Test fun staleFirstRunUploadRequestsRetryWithoutWritingTheSnapshot() {
        assertStaleUploadRetries(firstRun = true)
    }
    @Test fun staleLaterUploadRequestsRetryWithoutWritingTheSnapshot() {
        assertStaleUploadRetries(firstRun = false)
    }
    private fun assertStaleUploadRetries(firstRun: Boolean) {
        store.firstRunDone = !firstRun
        val before = MuditaEvent(10, "Before", "Body", now, now + 1000)
        val after = before.copy(title = "Fresh edit")
        phone.rows[before.id] = before
        phone.onRead = { phone.rows[before.id] = after }
        val result = engine.pass(now)
        assertTrue(result.retry); assertEquals(0, result.unresolved)
        assertTrue(store.status.contains("retry=true")); assertTrue(store.pending().isEmpty())
        assertEquals(0, server.writes); assertTrue(server.rows.isEmpty())
        assertEquals(!firstRun, store.firstRunDone)
        assertFalse(engine.pass(now).retry)
        assertEquals("Fresh edit", server.rows.values.single().title)
        assertTrue(store.firstRunDone)
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
        assertTrue(store.pending().isEmpty())
    }
    @Test fun interruptedPhoneDeletionNeverUploadsTheRemovedCopy() {
        seed(); server.rows.clear(); store.failSave = true
        try { engine.pass(now); fail("Expected death") } catch (_: ProcessDeath) { }
        engine.pass(now)
        assertTrue(phone.rows.isEmpty()); assertTrue(server.rows.isEmpty()); assertTrue(store.rows.isEmpty())
        assertTrue(store.pending().isEmpty())
    }
    @Test fun interruptedStandardUpdateAcknowledgesWithoutRepeatingOrLosingEdits() {
        val p = seed(); phone.rows[p.id] = p.copy(title = "Phone change"); store.failSave = true
        try { engine.pass(now); fail("Expected death") } catch (_: ProcessDeath) { }
        val writes = server.writes
        engine.pass(now)
        assertEquals(writes, server.writes); assertEquals("Phone change", server.rows[EventKey(1)]!!.title)
        assertTrue(store.pending().isEmpty())
    }
    @Test fun interruptedStandardDeletionDoesNotResurrectEitherCopy() {
        seed(); phone.rows.clear(); store.failSave = true
        try { engine.pass(now); fail("Expected death") } catch (_: ProcessDeath) { }
        engine.pass(now)
        assertTrue(phone.rows.isEmpty()); assertTrue(server.rows.isEmpty()); assertTrue(store.rows.isEmpty())
        assertTrue(store.pending().isEmpty())
    }
    @Test fun failedExpiredEditRetainsCopyAndMappingUntilTheProviderAcceptsIt() {
        val occurrence = event().copy(recurring = true, instanceStart = event().start)
        server.rows[occurrence.key] = occurrence; engine.pass(now)
        val p = phone.rows.values.single(); phone.rows[p.id] = p.copy(title = "Not lost")
        server.allowUpdate = false; engine.pass(now + BridgeEngine.WEEK)
        assertEquals(1, phone.rows.size); assertEquals(1, store.rows.size); assertTrue(store.pending().isNotEmpty())
        server.allowUpdate = true; engine.pass(now + BridgeEngine.WEEK)
        assertEquals("Not lost", server.rows[occurrence.key]!!.title)
        assertTrue(phone.rows.isEmpty()); assertTrue(store.rows.isEmpty()); assertTrue(store.pending().isEmpty())
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
        assertEquals(2, phone.rows.size); assertEquals(3, server.rows.size); assertEquals(listOf(intent), store.pending())
        assertTrue(store.rows.values.none { it.standardKey == remote.key })
        assertTrue(store.status.contains("incomplete"))
    }
    @Test fun uncertainInsertWithNoRecoverableRowIsNeverBlindlyRepeated() {
        engine.pass(now)
        val remote = event(); server.rows[remote.key] = remote
        store.prepare(WriteIntent(kind = WriteKind.IMPORT, phoneAfter = mapper.toMudita(remote), standardBefore = remote, attempted = true))
        engine.pass(now); assertEquals(0, phone.writes); assertTrue(store.pending().isNotEmpty())
        store.intent = null
        val p = MuditaEvent(10, "Phone", "Body", now, now + 1000); phone.rows[10] = p
        store.prepare(WriteIntent(kind = WriteKind.UPLOAD, phoneBefore = p,
            standardAfter = mapper.toStandard(p, event().copy(id = 0, location = "")), attempted = true))
        engine.pass(now); assertEquals(0, server.writes); assertTrue(store.pending().isNotEmpty())
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
        assertEquals(0, phone.writes); assertTrue(store.pending().isNotEmpty())
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
        assertTrue(phone.rows.isEmpty()); assertTrue(store.rows.isEmpty()); assertTrue(store.pending().isEmpty())
    }
    @Test fun unattemptedUploadDoesNotWriteToANoLongerSyncingDestination() {
        engine.pass(now)
        val p = MuditaEvent(10, "Phone", "Body", now, now + 1000); phone.rows[p.id] = p
        val template = event().copy(id = 0, location = "")
        store.prepare(WriteIntent(kind = WriteKind.UPLOAD, phoneBefore = p, standardAfter = mapper.toStandard(p, template)))
        server.calendarList = listOf(SyncCalendar(1, "dav", false))
        engine.pass(now)
        assertEquals(0, server.writes); assertEquals(p, phone.rows[p.id]); assertTrue(store.pending().isEmpty())
    }

    @Test fun aSoleIndependentMatchIsNeverClaimedAsAnUnknownImport() {
        engine.pass(now); val remote = event(); server.rows[remote.key] = remote
        store.prepare(WriteIntent(kind = WriteKind.IMPORT, phoneAfter = mapper.toMudita(remote),
            standardBefore = remote, attempted = true))
        phone.rows[20] = mapper.toMudita(remote).copy(id = 20, preReminder = true)
        engine.pass(now)
        assertTrue(store.rows.values.none { it.standardKey == remote.key })
        server.rows.remove(remote.key); engine.pass(now)
        assertTrue(phone.rows[20]!!.preReminder)
    }
    @Test fun anUnknownImportDoesNotSuspendUnrelatedEditsImportsOrUploads() {
        val existing = seed()
        val pending = event(2); server.rows[pending.key] = pending
        store.prepare(WriteIntent(kind = WriteKind.IMPORT, phoneAfter = mapper.toMudita(pending),
            standardBefore = pending, attempted = true))
        server.rows[event().key] = event().copy(title = "Remote update")
        server.rows[event(3).key] = event(3)
        phone.rows[50] = MuditaEvent(50, "Independent", "New", now, now + 1000)
        engine.pass(now)
        assertEquals("Remote update", phone.rows[existing.id]!!.title)
        assertTrue(store.rows.values.any { it.standardKey == event(3).key })
        assertTrue(server.rows.values.any { it.title == "Independent" })
        assertTrue(store.pending().isNotEmpty())
    }
    @Test fun successfulPhoneUpdateDoesNotOverwriteAnEditBeforeReadback() {
        val original = seed(); server.rows[event().key] = event().copy(title = "B")
        phone.afterUpdate = { id -> phone.rows[id] = phone.rows[id]!!.copy(title = "C", preReminder = true) }
        engine.pass(now); engine.pass(now)
        assertEquals("C", phone.rows[original.id]!!.title)
        assertEquals("C", server.rows[event().key]!!.title)
        assertTrue(phone.rows[original.id]!!.preReminder)
    }
    @Test fun pendingRetirementRechecksSelectionAndKeepsTheExistingReminder() {
        val original = seed(); phone.rows[original.id] = original.copy(preReminder = true)
        val before = phone.rows[original.id]!!
        store.prepare(WriteIntent(kind = WriteKind.PHONE_DELETE, mapping = store.rows.values.single(), phoneBefore = before))
        engine.pass(now)
        assertEquals(before, phone.rows[original.id])
        assertEquals(original.id, store.rows.values.single().muditaId)
    }
    @Test fun pendingRetirementRechecksRecurrenceAndTheCurrentHorizon() {
        val occurrence = event().copy(recurring = true, instanceStart = event().start)
        server.rows[occurrence.key] = occurrence; engine.pass(now)
        val row = store.rows.values.single(); val before = phone.rows[row.muditaId]!!.copy(preReminder = true)
        phone.rows[row.muditaId] = before
        store.prepare(WriteIntent(kind = WriteKind.PHONE_DELETE, mapping = row, phoneBefore = before))
        engine.pass(now)
        assertEquals(before, phone.rows[row.muditaId])
        store.prepare(WriteIntent(kind = WriteKind.PHONE_DELETE, mapping = row, phoneBefore = before))
        engine.pass(now + BridgeEngine.WEEK)
        assertTrue(phone.rows.isEmpty())
    }

    @Test fun receiptRecoversAnUnknownImportEvenAfterPhoneContentChanges() {
        engine.pass(now); server.rows[event().key] = event()
        phone.afterInsert = { id ->
            phone.rows[id] = phone.rows[id]!!.copy(title = "Edited after commit")
            throw ProcessDeath()
        }
        try { engine.pass(now); fail("Expected death") } catch (_: ProcessDeath) { }
        val p = phone.rows.values.single()
        assertTrue(p.insertReceipts.isNotEmpty()); assertNull(store.pending().single().insertedId)
        engine.pass(now); engine.pass(now)
        assertEquals(1, phone.rows.size); assertEquals(1, server.rows.size)
        assertEquals("Edited after commit", server.rows.values.single().title)
    }
    @Test fun multipleUnknownWritesReserveOnlyTheirOwnItemsAndCanBeResolvedExplicitly() {
        engine.pass(now)
        val remote = event(); server.rows[remote.key] = remote
        val p = MuditaEvent(10, "Upload", "Body", now, now + 1000); phone.rows[p.id] = p
        val a = WriteIntent(kind = WriteKind.IMPORT, standardBefore = remote,
            phoneAfter = mapper.toMudita(remote), attempted = true)
        val b = WriteIntent(kind = WriteKind.UPLOAD, phoneBefore = p,
            standardAfter = mapper.toStandard(p, event().copy(id = 0, location = "")), attempted = true)
        store.prepare(a); store.prepare(b)
        assertFalse(engine.pass(now).retry); assertEquals(2, store.pending().size)
        engine.resolvePending(now, a.id, WriteResolution.NotApplied)
        engine.resolvePending(now, b.id, WriteResolution.NotApplied)
        engine.pass(now)
        assertTrue(store.pending().isEmpty()); assertEquals(2, store.rows.size)
        assertEquals(2, phone.rows.size); assertEquals(2, server.rows.size)
    }
    @Test fun anUnconfirmedUpdateWithDriftRemainsReservedInsteadOfOverwritingThePhone() {
        val p = seed(); val source = event().copy(title = "B"); server.rows[source.key] = source
        val intent = WriteIntent(kind = WriteKind.PHONE_UPDATE, mapping = store.rows.values.single(),
            phoneBefore = p, phoneAfter = mapper.toMudita(source, p), standardBefore = source, attempted = true)
        store.prepare(intent); phone.rows[p.id] = p.copy(title = "C")
        server.rows[event(2).key] = event(2)
        engine.pass(now); engine.pass(now)
        assertEquals("C", phone.rows[p.id]!!.title); assertEquals("B", server.rows[source.key]!!.title)
        assertEquals(listOf(intent), store.pending()); assertEquals(2, phone.rows.size)
        engine.resolvePending(now, intent.id, WriteResolution.Applied())
        engine.pass(now)
        assertEquals("C", server.rows[source.key]!!.title); assertTrue(store.pending().isEmpty())
    }
    @Test fun confirmedUpdateSurvivesDeathBeforeAcknowledgementAndPreservesANewerEdit() {
        val p = seed(); server.rows[event().key] = event().copy(title = "B")
        phone.afterUpdate = { id -> phone.rows[id] = phone.rows[id]!!.copy(title = "C") }
        store.failSave = true
        try { engine.pass(now); fail("Expected death") } catch (_: ProcessDeath) { }
        assertTrue(store.pending().single().confirmed)
        engine.pass(now)
        assertEquals("C", phone.rows[p.id]!!.title); assertEquals("C", server.rows[event().key]!!.title)
    }
    @Test fun retirementStillRemovesACopyWhenItsRecurrenceIdentityIsObsolete() {
        val p = seed(); val row = store.rows.values.single()
        server.rows[event().key] = event().copy(recurring = true)
        store.prepare(WriteIntent(kind = WriteKind.PHONE_DELETE, mapping = row, phoneBefore = p, attempted = true))
        engine.pass(now)
        assertTrue(phone.rows.isEmpty()); assertTrue(store.rows.isEmpty())
        assertNotNull(server.rows[event().key])
    }
    @Test fun vanishedPendingRetirementNeverTurnsIntoAUserDeletionOfTheStandardEvent() {
        val p = seed(); val row = store.rows.values.single()
        store.prepare(WriteIntent(kind = WriteKind.PHONE_DELETE, mapping = row, phoneBefore = p, attempted = true))
        phone.rows.clear()
        engine.pass(now)
        assertEquals(event(), server.rows[event().key])
        assertEquals(1, phone.rows.size); assertEquals(0, server.writes)
        assertTrue(store.pending().isEmpty())
    }
    @Test fun vanishedUnattemptedRetirementPreservesTheUserDeletionWhenEligibleAgain() {
        val p = seed(); val row = store.rows.values.single()
        store.prepare(WriteIntent(kind = WriteKind.PHONE_DELETE, mapping = row, phoneBefore = p))
        phone.rows.clear()
        engine.pass(now)
        assertTrue(phone.rows.isEmpty()); assertTrue(server.rows.isEmpty())
        assertTrue(store.rows.isEmpty()); assertTrue(store.pending().isEmpty())
    }
    @Test fun obsoleteUnattemptedRetirementKeepsTheBaselineForANewerServerWinner() {
        val p = seed(); val row = store.rows.values.single()
        store.prepare(WriteIntent(kind = WriteKind.PHONE_DELETE, mapping = row, phoneBefore = p))
        phone.rows.clear(); server.rows[event().key] = event().copy(title = "Server winner")
        WriteJournal(phone, server, store, mapper, SyncScope(setOf(1), now, now + BridgeEngine.WEEK), { true }).recover()
        assertEquals(row, store.rows.values.single()); assertTrue(store.pending().isEmpty())
        engine.pass(now)
        assertEquals("Server winner", phone.rows.values.single().title)
        assertEquals("Server winner", server.rows.values.single().title)
        assertEquals(0, server.writes)
    }
    @Test fun obsoleteUnattemptedRetirementRechecksAnOccurrenceReturningIntoTheWindow() {
        val occurrence = event().copy(recurring = true, instanceStart = event().start)
        server.rows[occurrence.key] = occurrence; engine.pass(now)
        val p = phone.rows.values.single(); val row = store.rows.values.single()
        store.prepare(WriteIntent(kind = WriteKind.PHONE_DELETE, mapping = row, phoneBefore = p))
        phone.rows.clear()
        engine.pass(now)
        assertNull(server.rows[occurrence.key]); assertTrue(phone.rows.isEmpty())
        assertTrue(store.rows.isEmpty()); assertTrue(store.pending().isEmpty())
    }
    @Test fun vanishedUnattemptedRetirementStillRetiresAnIneligibleCopyOnly() {
        val p = seed(); val row = store.rows.values.single()
        store.prepare(WriteIntent(kind = WriteKind.PHONE_DELETE, mapping = row, phoneBefore = p))
        phone.rows.clear(); settings = settings.copy(calendars = emptySet())
        engine.pass(now)
        assertEquals(event(), server.rows[event().key]); assertEquals(0, server.writes)
        assertTrue(phone.rows.isEmpty()); assertTrue(store.rows.isEmpty()); assertTrue(store.pending().isEmpty())
    }
    @Test fun alreadyConvergedContentAdvancesTheBaselineWithoutIssuingAnotherWrite() {
        val p = seed(); val source = event().copy(title = "B"); server.rows[source.key] = source
        phone.rows[p.id] = mapper.toMudita(source, p)
        val writes = phone.writes
        engine.pass(now); val saves = store.saves
        engine.pass(now)
        assertEquals(writes, phone.writes); assertEquals(saves, store.saves)
        assertEquals(mapper.standardFingerprint(source), store.rows.values.single().standardFingerprint)
    }
    @Test fun oneProviderExceptionDoesNotAbortAnotherItemOrDisappearFromRetry() {
        engine.pass(now); server.rows[event().key] = event(); server.rows[event(2).key] = event(2)
        phone.afterInsert = { throw IllegalStateException("Provider failed after committing") }
        val result = engine.pass(now)
        assertTrue(result.retry); assertEquals(0, result.unresolved)
        assertEquals(1, store.pending().size); assertEquals(1, store.rows.size)
        assertTrue(store.rows.values.any { it.standardKey == event(2).key })
        val recovered = engine.pass(now)
        assertFalse(recovered.retry); assertEquals(0, recovered.unresolved)
        assertEquals(2, store.rows.size); assertEquals(2, phone.rows.size)
    }
    @Test fun duplicateOrConflictingReceiptsStayUnownedWithoutBlockingIndependentUploads() {
        engine.pass(now); val remote = event(); server.rows[remote.key] = remote
        val intent = WriteIntent(kind = WriteKind.IMPORT, standardBefore = remote,
            phoneAfter = mapper.toMudita(remote), attempted = true)
        store.prepare(intent)
        phone.rows[10] = mapper.toMudita(remote).copy(id = 10, insertReceipts = setOf(intent.id))
        phone.rows[11] = phone.rows[10]!!.copy(id = 11)
        phone.rows[12] = phone.rows[10]!!.copy(id = 12, insertReceipts = setOf(intent.id, "12345678-1234-1234-1234-123456789abc"))
        phone.rows[20] = MuditaEvent(20, "Independent", "Body", now, now + 1000)
        val result = engine.pass(now)
        assertFalse(result.retry); assertTrue(result.unresolved > 0)
        assertEquals(listOf(intent), store.pending())
        assertEquals(listOf(20L), store.rows.keys.toList())
        assertEquals(2, server.rows.size)
    }
    @Test fun aSingleConflictingReceiptRowIsNeitherAdoptedNorUploaded() {
        engine.pass(now); val remote = event(); server.rows[remote.key] = remote
        val intent = WriteIntent(kind = WriteKind.IMPORT, standardBefore = remote,
            phoneAfter = mapper.toMudita(remote), attempted = true)
        store.prepare(intent)
        val conflicting = mapper.toMudita(remote).copy(id = 12,
            insertReceipts = setOf(intent.id, "12345678-1234-1234-1234-123456789abc"))
        phone.rows[conflicting.id] = conflicting
        repeat(2) {
            val result = engine.pass(now)
            assertFalse(result.retry); assertEquals(1, result.unresolved)
            assertEquals(listOf(intent), store.pending()); assertTrue(store.rows.isEmpty())
            assertEquals(listOf(conflicting), phone.rows.values.toList())
            assertEquals(listOf(remote), server.rows.values.toList())
            assertEquals(0, phone.writes + server.writes)
        }
    }
    @Test fun phoneDoubleRejectsAnUpdateToAMissingRow() {
        val missing = MuditaEvent(10, "Missing", "Body", now, now + 1000)
        assertFalse(phone.update(missing.id, missing))
        assertTrue(phone.rows.isEmpty()); assertEquals(0, phone.writes)
    }
    @Test fun deletionBetweenJournalReadAndPhoneUpdateRetriesWithoutRecreatingTheRow() {
        val p = seed(); val row = store.rows.values.single()
        server.rows[event().key] = event().copy(title = "Server edit")
        phone.beforeUpdate = { id -> phone.rows.remove(id) }
        val result = engine.pass(now)
        assertTrue(result.retry); assertTrue(phone.rows.isEmpty())
        assertEquals(row, store.rows.values.single())
        val pending = store.pending().single()
        assertEquals(WriteKind.PHONE_UPDATE, pending.kind)
        assertTrue(pending.attempted); assertFalse(pending.confirmed)
        assertEquals(p.id, pending.phoneBefore!!.id)
        assertEquals(0, server.writes)
        assertEquals(1, engine.pass(now).unresolved)
        assertTrue(phone.rows.isEmpty()); assertEquals(0, server.writes)
    }
    @Test fun anExternalAppliedInsertResultRequiresAnIdentityAndDoesNotRepeatTheInsert() {
        engine.pass(now); val remote = event(); server.rows[remote.key] = remote
        val intent = WriteIntent(kind = WriteKind.IMPORT, standardBefore = remote,
            phoneAfter = mapper.toMudita(remote), attempted = true)
        store.prepare(intent); phone.rows[10] = mapper.toMudita(remote).copy(id = 10)
        try { engine.resolvePending(now, intent.id, WriteResolution.Applied()); fail("Expected missing identity") }
        catch (_: IllegalStateException) { }
        engine.resolvePending(now, intent.id, WriteResolution.Applied(10))
        engine.pass(now)
        assertTrue(store.pending().isEmpty()); assertEquals(0, phone.writes)
        assertEquals(10L, store.rows.values.single().muditaId)
    }
    @Test fun aReceiptCopiedOntoAnAlreadyMappedRowCannotReassignItsOwnership() {
        val p = seed(); val row = store.rows.values.single()
        val remote = event(2); server.rows[remote.key] = remote
        val intent = WriteIntent(kind = WriteKind.IMPORT, standardBefore = remote,
            phoneAfter = mapper.toMudita(remote), attempted = true)
        store.prepare(intent)
        phone.rows[p.id] = p.copy(insertReceipts = setOf(intent.id))
        engine.pass(now)
        assertEquals(row.standardKey, store.rows[p.id]!!.standardKey)
        assertEquals(listOf(intent), store.pending())
        assertEquals(1, phone.rows.size)
    }

    @Test fun firstRunAfterReinstallStillMatchesRowsWithHistoricalReceipts() {
        val remote = event(); server.rows[remote.key] = remote
        phone.rows[10] = mapper.toMudita(remote).copy(id = 10,
            insertReceipts = setOf("12345678-1234-1234-1234-123456789abc"))
        engine.pass(now)
        assertEquals(1, phone.rows.size); assertEquals(1, server.rows.size)
        assertEquals(10L, store.rows.values.single().muditaId)
    }
    @Test fun aNewIndependentCopyWithHistoricalReceiptIsNotQuarantinedForever() {
        engine.pass(now)
        phone.rows[10] = MuditaEvent(10, "Independent copy", "Body", now, now + 1000,
            insertReceipts = setOf("12345678-1234-1234-1234-123456789abc"))
        engine.pass(now)
        assertEquals(1, server.rows.size); assertEquals(10L, store.rows.values.single().muditaId)
        assertEquals("Body", server.rows.values.single().description)
    }

    @Test fun reservationAcquiredAfterDiffSkipsOnlyThatItemAndSurvivesASchemaStop() {
        store.firstRunDone = true
        val remote = event(); server.rows[remote.key] = remote
        server.rows[event(2).key] = event(2)
        val pending = WriteIntent(kind = WriteKind.IMPORT, standardBefore = remote,
            phoneAfter = mapper.toMudita(remote), attempted = true)
        val interleaved = object : StandardCalendarPort by server {
            var acquired = false
            override fun read(key: EventKey): StandardEvent? {
                if (!acquired && key == remote.key) {
                    acquired = true
                    // Another owner acquires the item after the diff's reservation check.
                    store.prepare(pending)
                }
                return server.read(key)
            }
        }
        val result = BridgeEngine(phone, interleaved, store,
            object : SyncConfig { override fun read() = settings }, mapper).pass(now)
        assertFalse(result.retry); assertEquals(1, result.unresolved)
        assertEquals(listOf(pending), store.pending())
        assertEquals(listOf(event(2).key), store.rows.values.map { it.standardKey })
        phone.schema = false
        val writes = phone.writes + server.writes
        assertTrue(engine.pass(now).stopReasons.isNotEmpty())
        assertEquals(writes, phone.writes + server.writes); assertEquals(listOf(pending), store.pending())
        phone.schema = true
        engine.resolvePending(now, pending.id, WriteResolution.NotApplied)
        assertFalse(engine.pass(now).retry)
        assertTrue(store.pending().isEmpty()); assertEquals(2, phone.rows.size); assertEquals(2, store.rows.size)
    }

    @Test fun atomicAcquisitionLosingToAnotherOwnerDoesNotAbortThePass() {
        store.firstRunDone = true
        val remote = event(); server.rows[remote.key] = remote
        server.rows[event(2).key] = event(2)
        val pending = WriteIntent(kind = WriteKind.IMPORT, standardBefore = remote,
            phoneAfter = mapper.toMudita(remote), attempted = true)
        val interleaved = object : MappingStore by store {
            var acquired = false
            override fun tryPrepare(intent: WriteIntent): Boolean {
                if (!acquired) { acquired = true; store.prepare(pending) }
                return store.tryPrepare(intent)
            }
        }
        val result = BridgeEngine(phone, server, interleaved,
            object : SyncConfig { override fun read() = settings }, mapper).pass(now)
        assertFalse(result.retry); assertEquals(1, result.unresolved)
        assertEquals(listOf(pending), store.pending())
        assertEquals(listOf(event(2).key), store.rows.values.map { it.standardKey })
    }

    @Test fun everyWriteKindSkipsAnExistingReservationWithoutReplacingItsEvidence() {
        val remote = event(); val p = mapper.toMudita(remote).copy(id = 10)
        val row = Mapping(p.id, remote.key, remote.calendarId, "phone", "standard")
        val pending = WriteIntent(kind = WriteKind.PHONE_UPDATE, mapping = row,
            phoneBefore = p, phoneAfter = p, standardBefore = remote, attempted = true)
        store.prepare(pending)
        for (kind in WriteKind.entries) {
            val journal = WriteJournal(phone, server, store, mapper, SyncScope(setOf(1), now, now + BridgeEngine.WEEK), { true })
            assertEquals(kind.name, WriteOutcome.UNRESOLVED, journal.write(WriteIntent(kind = kind, mapping = row,
                phoneBefore = p, phoneAfter = p, standardBefore = remote, standardAfter = remote)))
            assertEquals(listOf(pending), store.pending())
            assertFalse(journal.retry); assertEquals(0, phone.writes + server.writes)
        }
    }

    private class ProcessDeath : Error()
    private class Phone : MuditaCalendarPort {
        val rows = linkedMapOf<Long, MuditaEvent>(); var schema = true; var writes = 0; var onRead: (() -> Unit)? = null
        private var nextId = 1L
        var afterInsert: ((Long) -> Unit)? = null
        var beforeUpdate: ((Long) -> Unit)? = null
        var afterUpdate: ((Long) -> Unit)? = null
        override fun schemaOk() = schema
        override fun all(): List<MuditaEvent> { val snapshot = rows.values.toList(); onRead?.also { onRead = null; it() }; return snapshot }
        override fun insert(event: MuditaEvent): Long {
            writes++; val id = maxOf(nextId, (rows.keys.maxOrNull() ?: 0) + 1); nextId = id + 1
            rows[id] = event.copy(id = id)
            afterInsert?.also { afterInsert = null; it(id) }
            return id
        }
        override fun update(id: Long, event: MuditaEvent): Boolean {
            beforeUpdate?.also { beforeUpdate = null; it(id) }
            if (id !in rows) return false
            writes++; rows[id] = event.copy(id = id)
            afterUpdate?.also { afterUpdate = null; it(id) }
            return true
        }
        override fun delete(id: Long): Boolean { writes++; return rows.remove(id) != null }
    }
    private class Server : StandardCalendarPort {
        val rows = linkedMapOf<EventKey, StandardEvent>(); var writes = 0
        val insertIdentities = mutableMapOf<String, Long>()
        var allowUpdate = true
        var afterInsert: ((EventKey) -> Unit)? = null
        var calendarList = listOf(SyncCalendar(1, "dav", true))
        var removedSeries = emptySet<Long>()
        override fun seriesRemoved(eventId: Long) = eventId in removedSeries
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
        val intents = linkedMapOf<String, WriteIntent>()
        var intent: WriteIntent?
            get() = intents.values.singleOrNull()
            set(value) { intents.clear(); if (value != null) intents[value.id] = value }
        override fun all() = rows.values.toList()
        override fun save(mapping: Mapping) {
            if (failSave) { failSave = false; throw ProcessDeath() }
            saves++; rows[mapping.muditaId] = mapping
        }
        override fun remove(muditaId: Long) { rows.remove(muditaId) }
        override fun pending() = intents.values.toList()
        override fun tryPrepare(intent: WriteIntent): Boolean {
            if (intent.id in intents || intents.values.any { it.reservations.intersect(intent.reservations).isNotEmpty() })
                return false
            intents[intent.id] = intent
            return true
        }
        override fun prepare(intent: WriteIntent) {
            check(intents.values.none { it.id != intent.id && it.reservations.intersect(intent.reservations).isNotEmpty() })
            intents[intent.id] = intent
        }
        override fun acknowledge(intentId: String, mapping: Mapping?, removeMuditaId: Long?) {
            check(intentId in intents)
            if (mapping != null) check(rows.values.none { it.muditaId == mapping.muditaId && it.standardKey != mapping.standardKey })
            if (failSave) { failSave = false; throw ProcessDeath() }
            if (removeMuditaId != null) remove(removeMuditaId)
            if (mapping != null) save(mapping)
            intents.remove(intentId)
        }
        override var firstRunDone = false
        override var status = ""
        override var continuity = CalendarContinuity()
        override fun keepPhoneOnly(ids: Set<Long>) {
            continuity = continuity.copy(phoneOnly = continuity.phoneOnly + ids)
            ids.forEach(::remove)
        }
    }
}
