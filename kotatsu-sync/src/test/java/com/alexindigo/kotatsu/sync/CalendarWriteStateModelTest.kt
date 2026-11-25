package com.alexindigo.kotatsu.sync

import java.time.ZoneId
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** A consumer-side oracle: derive authority from fixtures, not from the production guard. */
class CalendarWriteStateModelTest {
    @Test fun guardAndJournalTransitionsUnderFailuresAndWipes() {
        for (seed in 0 until 2_048) Scenario(seed).run()
    }

    private enum class Step {
        PASS, READ_FAILURE, FAIL_WRITE, DIE_AFTER_WRITE, DIE_AFTER_PREPARE, REOPEN,
        DROP_PHONE, DROP_SERVER, EDIT_PHONE, EDIT_SERVER, ADD_SERVER,
        WIPE_PHONE, WIPE_SERVER, CHANGE_ACCOUNT, LATE_PHONE_LOSS, APPROVE, RESOLVE_WRITE,
    }
    private enum class Fault { NONE, READ, BEFORE_WRITE, AFTER_WRITE, AFTER_PREPARE }
    private class Death : Error()
    private class ProviderFailure : RuntimeException()

    private class Scenario(private val seed: Int) {
        private val random = Random(seed)
        private val now = 1_800_000_000_000L
        private val mapper = EventMapper(ZoneId.of("UTC"))
        private val phone = BridgeEngineTest.Phone()
        private val server = BridgeEngineTest.Server()
        private val state = BridgeEngineTest.MemoryMappings()
        private var fault = Fault.NONE
        private var nextServerId = 1000L
        private var observedPhone: Set<Long>? = null
        private var baselinePhone: MuditaBaseline? = null
        private var assessedPairs = emptyList<Mapping>()
        private var assessedStandard = emptyMap<EventKey, StandardEvent>()
        private var assessedCalendars = emptyList<SyncCalendar>()
        private var previousCalendars = emptyMap<Long, CalendarBaseline>()
        private var approved = emptyMap<String, CalendarHold>()
        private var lateChange = false
        private val insertedPhones = mutableSetOf<Long>()
        private val deletedPhones = mutableSetOf<Long>()
        private val applied = mutableMapOf<String, Long?>()
        private val trace = mutableListOf<Step>()

        private val store = object : MappingStore by state {
            override fun tryPrepare(intent: WriteIntent): Boolean = state.tryPrepare(intent).also {
                if (it && fault == Fault.AFTER_PREPARE) { fault = Fault.NONE; throw Death() }
            }
        }
        private val bridgedPhone = object : MuditaCalendarPort by phone {
            override fun all(): List<MuditaEvent> {
                if (fault == Fault.READ) { fault = Fault.NONE; throw ProviderFailure() }
                val result = phone.all()
                if (observedPhone == null) {
                    observedPhone = result.map { it.id }.toSet()
                    // Between observation and any journal mutation, invalidate only
                    // the final inventory. The rest of the cohort stays observable.
                    if (lateChange) phone.rows.keys.maxOrNull()?.let { phone.rows.remove(it) }
                }
                return result
            }
            override fun insert(event: MuditaEvent) = apply(WriteKind.IMPORT, null, null, event.insertReceipts.single()) {
                phone.insert(event).also { insertedPhones.add(it) }
            }
            override fun delete(id: Long) = apply(WriteKind.PHONE_DELETE, id, null) {
                phone.delete(id).also { if (it) deletedPhones.add(id) }
            }
            override fun update(id: Long, event: MuditaEvent): Boolean {
                val current = phone.rows[id]
                if (current != null && current.insertReceipts.isNotEmpty() && event == current.copy(insertReceipts = emptySet())) {
                    assertTrue(state.pending().any { it.id in current.insertReceipts && it.confirmed && it.insertedId == id })
                    return phone.update(id, event)
                }
                return apply(WriteKind.PHONE_UPDATE, id, null) { phone.update(id, event) }
            }
        }
        private val bridgedServer = object : StandardCalendarPort by server {
            override fun insert(calendarId: Long, event: StandardEvent, intentId: String?) =
                apply(WriteKind.UPLOAD, null, null, checkNotNull(intentId)) { server.insert(calendarId, event, intentId) }
            override fun update(key: EventKey, event: StandardEvent) =
                apply(WriteKind.STANDARD_UPDATE, null, key) { server.update(key, event) }
            override fun delete(key: EventKey) = apply(WriteKind.STANDARD_DELETE, null, key) { server.delete(key) }
        }
        private fun engine() = BridgeEngine(bridgedPhone, bridgedServer, store, object : SyncConfig {
            override fun read() = SyncSettings(setOf(1L, 2L), 1L)
        }, mapper)

        private fun <T> apply(kind: WriteKind, phoneId: Long?, key: EventKey?, id: String? = null, effect: () -> T): T {
            val intent = state.pending().single { it.kind == kind && when {
                id != null -> it.id == id
                phoneId != null -> it.mapping?.muditaId == phoneId
                else -> it.mapping?.standardKey == key
            } }
            assertTrue("provider call must have durable attempt evidence", intent.attempted && !intent.confirmed)
            assertFalse("provider effect was repeated", intent.id in applied)
            val row = intent.mapping
            val actualPhone = row?.muditaId?.let { phone.rows[it] }
            val actualStandard = row?.standardKey?.let { server.rows[it] }
            val calendar = server.calendarList.single { it.id == intent.calendarId }
            val seen = checkNotNull(observedPhone)
            if (intent.lossId != null) {
                val hold = approved.getValue(intent.lossId)
                val member = hold.members.single { it.pair == row }
                assertEquals(member.phone, actualPhone)
                assertEquals(member.standard, actualStandard)
                assertEquals(member.calendar, calendar.witness())
                assertEquals(when (hold.resolution) {
                    LossResolution.RESTORE -> WriteKind.IMPORT
                    LossResolution.DELETE_FROM_SERVER -> WriteKind.STANDARD_DELETE
                    LossResolution.REMOVE -> WriteKind.PHONE_DELETE
                    else -> error("Keep must not mutate a provider")
                }, kind)
            } else {
                assertFalse("ordinary work touched a held pair", state.continuity.holds.any { h -> h.members.any { it.pair == row } })
                if (kind == WriteKind.STANDARD_DELETE) {
                    val missing = assessedPairs.count { it.muditaId !in seen }
                    assertTrue("ordinary delete during assessed bulk loss", missing < 3 && (seen.isNotEmpty() || assessedPairs.size < 2))
                    assertNull(actualPhone)
                    assertEquals(assessedStandard[key], actualStandard)
                    assertEquals(intent.standardBefore, actualStandard)
                }
                if (kind == WriteKind.PHONE_DELETE) {
                    val owner = checkNotNull(row).calendarId
                    val healthy = assessedCalendars.filter { current ->
                        previousCalendars[current.id]?.calendar?.identity.let { it == null || it == current.identity }
                    }.map { it.id }.toSet()
                    assertTrue("ordinary retirement in an untrusted calendar", owner in healthy)
                    val lost = assessedPairs.filter { assessedStandard[it.standardKey] == null && it.calendarId in healthy }
                    val sameCalendar = maxOf(assessedPairs.count { it.calendarId == owner }, previousCalendars[owner]?.keys?.size ?: 0)
                    assertTrue("ordinary retirement during assessed bulk loss", lost.size < 3)
                    assertFalse("ordinary retirement during whole-calendar loss", sameCalendar >= 2 &&
                        lost.count { it.calendarId == owner } == sameCalendar)
                    assertNull(actualStandard)
                    assertEquals(intent.phoneBefore, actualPhone)
                }
            }
            assertEquals(assessedCalendars.single { it.id == calendar.id }, calendar)
            if (fault == Fault.BEFORE_WRITE) { fault = Fault.NONE; throw ProviderFailure() }
            val result = effect()
            check(result != false && result != null)
            applied[intent.id] = result as? Long
            if (fault == Fault.AFTER_WRITE) { fault = Fault.NONE; throw Death() }
            return result
        }

        fun run() {
            server.calendarList = (1L..2L).map { SyncCalendar(it, "dav", true, "Account $it") }
            repeat(6) { addServer(it % 2 + 1L) }
            pass()
            repeat(32) {
                val step = Step.entries.random(random)
                trace.add(step)
                try {
                    advance(step)
                    assertState()
                } catch (failure: Throwable) {
                    throw AssertionError("seed=$seed step=${trace.size} trace=$trace", failure)
                }
            }
            // Under stable fresh evidence resolve any applicable holds, including
            // ones sharing an attempted reservation. Assess again after each action.
            repeat(8) {
                resolveWrites()
                pass()
                state.continuity.holds.firstOrNull { h -> h.resolution == null && when (h.direction) {
                    LossDirection.PHONE -> h.members.all { it.phone == null && it.calendar == server.calendarList.single { c -> c.id == it.pair.calendarId }.witness() }
                    LossDirection.STANDARD -> h.members.none { m -> state.continuity.holds.any { it.direction == LossDirection.PHONE && it.members.any { it.pair == m.pair && it.phone != null } } }
                } }?.let { h -> pass(h, if (h.direction == LossDirection.PHONE) LossResolution.DELETE_FROM_SERVER else LossResolution.KEEP) }
                assertState()
            }
            for (hold in state.continuity.holds) {
                // A coherent standard-side Keep needs only the exact snapshot and
                // released/settled ownership. It must not require approval again.
                if (hold.direction == LossDirection.STANDARD && hold.resolution == null &&
                    hold.members.all { member -> state.pending().none { it.mapping == member.pair } &&
                        phone.rows[member.pair.muditaId] == member.phone && server.rows[member.pair.standardKey] == member.standard }) {
                    pass(hold, LossResolution.KEEP)
                    assertTrue("seed=$seed applicable reviewed action remained stuck; trace=$trace", state.continuity.holds.none { it.id == hold.id })
                }
            }
        }

        private fun advance(step: Step) {
            val row = state.all().randomOrNull(random)
            when (step) {
                Step.PASS, Step.REOPEN -> pass()
                Step.READ_FAILURE -> pass(failure = Fault.READ)
                Step.FAIL_WRITE -> { addServer(2); pass(failure = Fault.BEFORE_WRITE) }
                Step.DIE_AFTER_WRITE -> { addServer(2); pass(failure = Fault.AFTER_WRITE) }
                Step.DIE_AFTER_PREPARE -> { addServer(2); pass(failure = Fault.AFTER_PREPARE) }
                Step.DROP_PHONE -> row?.let { phone.rows.remove(it.muditaId) }
                Step.DROP_SERVER -> row?.let { server.rows.remove(it.standardKey) }
                Step.EDIT_PHONE -> row?.let { phone.rows[it.muditaId]?.let { p -> phone.rows[p.id] = p.copy(title = "Phone ${trace.size}") } }
                Step.EDIT_SERVER -> row?.let { server.rows[it.standardKey]?.let { s -> server.rows[s.key] = s.copy(title = "Server ${trace.size}") } }
                Step.ADD_SERVER -> addServer(random.nextLong(1, 3))
                Step.WIPE_PHONE -> phone.rows.clear()
                Step.WIPE_SERVER -> server.rows.clear()
                Step.CHANGE_ACCOUNT -> server.calendarList = server.calendarList.map {
                    if (it.id == 1L) it.copy(accountName = "Replacement ${trace.size}") else it
                }
                Step.LATE_PHONE_LOSS -> pass(late = true)
                Step.APPROVE -> state.continuity.holds.filter { it.resolution == null }.randomOrNull(random)?.let { hold ->
                    pass(hold, if (hold.direction == LossDirection.PHONE) listOf(LossResolution.RESTORE, LossResolution.DELETE_FROM_SERVER).random(random)
                        else listOf(LossResolution.KEEP, LossResolution.REMOVE).random(random))
                }
                Step.RESOLVE_WRITE -> resolveWrites()
            }
        }

        private fun resolveWrites() {
            for (intent in state.pending().filter { it.attempted && !it.confirmed }) {
                val evidence = if (intent.id in applied) WriteResolution.Applied(applied[intent.id]) else WriteResolution.NotApplied
                engine().resolvePending(now, intent.id, evidence)
            }
        }

        private fun pass(hold: CalendarHold? = null, action: LossResolution? = null, failure: Fault = Fault.NONE, late: Boolean = false) {
            fault = failure
            lateChange = late
            observedPhone = null
            baselinePhone = state.continuity.phone
            assessedPairs = state.all()
            assessedStandard = server.rows.toMap()
            assessedCalendars = server.calendarList.toList()
            previousCalendars = state.continuity.standard
            val beforeHolds = state.continuity.holds
            approved = state.continuity.holds.filter { it.resolution != null }.associateBy { it.id } +
                (hold?.let { mapOf(it.id to it.copy(resolution = action)) } ?: emptyMap())
            insertedPhones.clear(); deletedPhones.clear()
            try {
                if (hold == null) engine().pass(now) else engine().resolveLoss(now, hold.id, checkNotNull(action))
            } catch (_: Death) {
                assertTrue(failure == Fault.AFTER_PREPARE || failure == Fault.AFTER_WRITE)
            } catch (_: ProviderFailure) {
                assertEquals(Fault.READ, failure)
            }
            val baseline = state.continuity.phone
            if (baseline != baselinePhone) {
                assertNotNull(observedPhone)
                val justified = observedPhone.orEmpty() - deletedPhones + insertedPhones
                assertEquals("advanced unassessed phone baseline", justified, baseline?.observedIds)
                assertEquals(phone.rows.keys, baseline?.observedIds)
            }
            // At most one stable fresh pass is needed to drop a Prepared item
            // with no continuing hold, including after death before a provider call.
            if (failure == Fault.NONE && !late && beforeHolds == state.continuity.holds) {
                val heldPairs = state.continuity.holds.flatMap { it.members }.map { it.pair }
                val stranded = state.pending().filter { !it.attempted && !it.confirmed }.filterNot { i ->
                    heldPairs.any { r -> r.muditaId == i.mapping?.muditaId || r.standardKey == i.mapping?.standardKey ||
                        r.standardKey == i.standardBefore?.key || r.muditaId == i.phoneBefore?.id }
                }
                assertTrue("seed=$seed stable reassessment left unattempted reservations: $stranded; trace=$trace", stranded.isEmpty())
            }
        }

        private fun assertState() {
            val pairs = state.all()
            assertEquals(pairs.size, pairs.map { it.muditaId }.toSet().size)
            assertEquals(pairs.size, pairs.map { it.standardKey }.toSet().size)
            assertTrue(pairs.none { it.muditaId in state.continuity.phoneOnly })
            val reservations = state.pending().flatMap { it.reservations }
            assertEquals(reservations.size, reservations.toSet().size)
            val holds = state.continuity.holds
            assertEquals(holds.size, holds.map { it.id }.toSet().size)
            assertTrue(holds.none { it.members.isEmpty() })
        }

        private fun addServer(calendar: Long) {
            val id = nextServerId++
            val e = StandardEvent(id, calendar, "Event $id", "", start = now, end = now + 1_000, syncId = "external-$id")
            server.rows[e.key] = e
        }
    }
}
