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
        assertTrue(store.firstRunDone); assertEquals("Location: Home\nBody", phone.rows[10]!!.description)
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
        val original = seed(); phone.rows[original.id] = original.copy(preReminder = true, description = "Location: Elsewhere\nNew body")
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

    private class Phone : MuditaCalendarPort {
        val rows = linkedMapOf<Long, MuditaEvent>(); var schema = true; var writes = 0; var onRead: (() -> Unit)? = null
        override fun schemaOk() = schema
        override fun all(): List<MuditaEvent> { val snapshot = rows.values.toList(); onRead?.also { onRead = null; it() }; return snapshot }
        override fun insert(event: MuditaEvent): Long { writes++; val id = (rows.keys.maxOrNull() ?: 0) + 1; rows[id] = event.copy(id = id); return id }
        override fun update(id: Long, event: MuditaEvent): Boolean { writes++; rows[id] = event.copy(id = id); return true }
        override fun delete(id: Long): Boolean { writes++; return rows.remove(id) != null }
    }
    private class Server : StandardCalendarPort {
        val rows = linkedMapOf<EventKey, StandardEvent>(); var writes = 0
        var calendarList = listOf(SyncCalendar(1, "dav", true))
        override fun calendars() = calendarList
        override fun events(calendarIds: Set<Long>) = rows.values.filter { it.calendarId in calendarIds && !it.recurring }
        override fun instances(calendarIds: Set<Long>, start: Long, end: Long) = rows.values.filter {
            it.recurring && it.calendarId in calendarIds && it.start >= start && it.start < end
        }
        override fun read(key: EventKey) = rows[key]
        override fun insert(calendarId: Long, event: StandardEvent): Long { writes++; val id = (rows.keys.maxOfOrNull { it.eventId } ?: 0) + 1
            rows[EventKey(id)] = event.copy(id = id, calendarId = calendarId); return id }
        override fun update(key: EventKey, event: StandardEvent): Boolean { writes++; rows[key] = event; return true }
        override fun delete(key: EventKey): Boolean { writes++; return rows.remove(key) != null }
    }
    private class MemoryMappings : MappingStore {
        val rows = linkedMapOf<Long, Mapping>(); var saves = 0
        override fun all() = rows.values.toList()
        override fun save(mapping: Mapping) { saves++; rows[mapping.muditaId] = mapping }
        override fun remove(muditaId: Long) { rows.remove(muditaId) }
        override var firstRunDone = false
        override var status = ""
    }
}
