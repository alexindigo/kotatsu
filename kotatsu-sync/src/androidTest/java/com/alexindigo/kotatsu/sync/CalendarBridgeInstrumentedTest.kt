package com.alexindigo.kotatsu.sync

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.withFixtureCleanup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CalendarBridgeInstrumentedTest {
    @Test fun standardEventAppearsInMuditaAndPhoneEditKeepsUnsupportedFields() = scenario { f ->
        val id = f.insert(location = "Original place")
        val reminders = listOf(10, 60).map { minutes ->
            f.resolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
                put(Reminders.EVENT_ID, id); put(Reminders.MINUTES, minutes); put(Reminders.METHOD, Reminders.METHOD_ALERT)
            })!!
        }
        f.pass(); val phone = f.phone.all().single()
        assertEquals("Location: Original place${EventMapper.LOCATION_MARKER}\n${EventMapper.BODY_MARKER}Body", phone.description)
        assertTrue(f.phone.update(phone.id, phone.copy(title = "Phone edit",
            description = phone.description.replace("Original place", "Ignored").replace("Body", "Edited"), preReminder = true)))
        f.pass()
        val actual = f.standard.read(EventKey(id))!!
        assertEquals("Phone edit", actual.title); assertEquals("Edited", actual.description); assertEquals("Original place", actual.location)
        for ((index, uri) in reminders.withIndex()) f.resolver.query(uri, arrayOf(Reminders.MINUTES), null, null, null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(listOf(10, 60)[index], it.getInt(0))
        }
        f.resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), ContentValues().apply { put(Events.TITLE, "Server edit") }, null, null)
        f.pass(); assertTrue(f.phone.all().single().preReminder)
        assertEquals("Server edit", f.phone.all().single().title)
    }

    @Test fun weeklySeriesExpandsToEightWeeksAndOccurrenceDeleteCancelsOnlyOneDate() = scenario { f ->
        val id = f.insert(recurring = true)
        f.pass(); assertEquals(8, f.phone.all().size)
        val row = f.store.all().sortedBy { it.standardKey.instanceStart }[2]
        assertTrue(f.phone.delete(row.muditaId)); f.pass()
        val remaining = f.standard.instances(setOf(f.calendarId), f.now, f.now + 8 * BridgeEngine.WEEK)
        assertEquals(7, remaining.size)
        assertTrue(remaining.all { it.id == id && it.instanceStart != row.standardKey.instanceStart })
        f.resolver.query(Events.CONTENT_URI, arrayOf(Events.STATUS),
            "${Events.ORIGINAL_ID} = ? AND ${Events.ORIGINAL_INSTANCE_TIME} = ?", arrayOf(id.toString(), row.standardKey.instanceStart.toString()), null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals(Events.STATUS_CANCELED, it.getInt(0))
        }
        f.pass(); assertEquals(7, f.phone.all().size)
    }

    @Test fun occurrenceEditMovesOnlyThatOccurrence() = scenario { f ->
        f.insert(recurring = true); f.pass()
        val before = f.standard.instances(setOf(f.calendarId), f.now, f.now + 8 * BridgeEngine.WEEK)
        val row = f.store.all().sortedBy { it.standardKey.instanceStart }[1]
        val phone = f.phone.all().single { it.id == row.muditaId }
        assertTrue(f.phone.update(phone.id, phone.copy(title = "Exception", start = phone.start + 3600000, end = phone.end + 3600000)))
        f.pass()
        val actual = f.standard.read(row.standardKey)!!
        assertEquals("Exception", actual.title); assertEquals(phone.start + 3600000, actual.start)
        assertEquals(before.filterNot { it.key == row.standardKey },
            f.standard.instances(setOf(f.calendarId), f.now, f.now + 8 * BridgeEngine.WEEK).filterNot { it.key == row.standardKey })
        f.pass(); assertEquals(8, f.phone.all().size)
    }

    @Test fun schemaTwoStopsWithoutProviderWrites() = scenario { f ->
        val id = f.insert(); f.pass(); val before = f.phone.all()
        f.schema(true)
        f.resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), ContentValues().apply { put(Events.TITLE, "New server title") }, null, null)
        f.pass(); assertTrue(f.store.status.startsWith("stopped:"))
        f.schema(false); assertEquals(before, f.phone.all())
        assertEquals("New server title", f.standard.read(EventKey(id))!!.title)
    }

    @Test fun contentChangeJobRunsPassAfterStandardInsert() = scenario { f ->
        f.store.firstRunDone = true
        SyncRuntime.arm(f.context)
        f.insert()
        f.await { f.phone.all().size == 1 }
        assertEquals("Test event", f.phone.all().single().title)
        f.await { f.context.getSystemService(JobScheduler::class.java).getPendingJob(SyncRuntime.WATCH_JOB) != null }
    }

    @Test fun firstRunUploadsPhoneEventWithoutRemindersAndSecondPassIsStable() = scenario { f ->
        val id = f.phone.insert(MuditaEvent(title = "Existing phone event", description = "Body", start = f.now + 1000, end = f.now + 2000, preReminder = true))!!
        f.pass(); val row = f.store.all().single()
        assertEquals(id, row.muditaId)
        assertEquals("Existing phone event", f.standard.read(row.standardKey)!!.title)
        f.resolver.query(Reminders.CONTENT_URI, arrayOf(Reminders._ID), "${Reminders.EVENT_ID} = ?", arrayOf(row.standardKey.eventId.toString()), null)!!.use {
            assertEquals(0, it.count)
        }
        f.pass(); assertEquals(listOf(row), f.store.all()); assertEquals(1, f.phone.all().size)
    }

    @Test fun fakeProviderHasFixedSchemaFullModelsAndIgnoresReminderJson() = scenario { f ->
        val id = f.phone.insert(MuditaEvent(title = "Contract", description = "Body", start = f.now + 3600000, end = f.now + 7200000))!!
        f.resolver.query(MuditaCalendar.URI, arrayOf("id"), null, null, null)!!.use {
            assertEquals(MuditaCalendar.COLUMNS, it.columnNames.toList())
        }
        try { f.resolver.query(ContentUris.withAppendedId(MuditaCalendar.URI, id), null, null, null, null); fail("Row queries must be rejected") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, f.resolver.update(ContentUris.withAppendedId(MuditaCalendar.URI, id), ContentValues().apply { put("title", "Partial") }, null, null))
        val original = f.phone.all().single()
        assertEquals("Contract", original.title)
        val values = ContentValues().apply {
            put("id", id); put("title", "Contract"); put("desc", "Body"); put("startDateTime", original.start)
            put("endDateTime", original.end); put("isAllDayEvent", false); put("isPreReminderSet", true)
            put("reminder", "not JSON")
        }
        assertEquals(1, f.resolver.update(ContentUris.withAppendedId(MuditaCalendar.URI, id), values, null, null))
        f.resolver.query(MuditaCalendar.URI, null, null, null, null)!!.use {
            assertTrue(it.moveToFirst()); val json = org.json.JSONObject(it.getString(6))
            assertTrue(json.getBoolean("d")); assertEquals(2, json.getJSONArray("e").length())
            assertEquals(1, it.getInt(7))
        }
    }

    @Test fun recurringOccurrenceCanChangeAllDayWithoutChangingTheSeries() = scenario { f ->
        val id = f.insert(recurring = true, zone = "Europe/Paris"); f.pass()
        val row = f.store.all().sortedBy { it.standardKey.instanceStart }[1]
        val original = f.phone.all().single { it.id == row.muditaId }
        val date = Instant.ofEpochMilli(original.start).atZone(ZoneId.systemDefault()).toLocalDate()
        val allDay = original.copy(allDay = true, start = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            end = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        assertTrue(f.phone.update(original.id, allDay)); f.pass()
        assertTrue(f.standard.read(row.standardKey)!!.allDay); assertEquals("UTC", f.standard.read(row.standardKey)!!.timeZone)
        assertFalse(f.standard.read(EventKey(id))!!.allDay); assertEquals("Europe/Paris", f.standard.read(EventKey(id))!!.timeZone)
        assertTrue(f.phone.update(original.id, allDay.copy(allDay = false, start = original.start, end = original.end))); f.pass()
        assertFalse(f.standard.read(row.standardKey)!!.allDay)
        assertEquals(ZoneId.systemDefault().id, f.standard.read(row.standardKey)!!.timeZone)
    }

    @Test fun timedAllDayTransitionsNormalizeTheStandardZone() = scenario { f ->
        val id = f.insert(zone = "Europe/Paris"); f.pass()
        var phone = f.phone.all().single()
        val localDate = Instant.ofEpochMilli(phone.start).atZone(ZoneId.systemDefault()).toLocalDate()
        phone = phone.copy(allDay = true, start = localDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            end = localDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        assertTrue(f.phone.update(phone.id, phone)); f.pass()
        var actual = f.standard.read(EventKey(id))!!
        assertTrue(actual.allDay); assertEquals("UTC", actual.timeZone)
        assertEquals(localDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), actual.start)
        assertTrue(f.phone.update(phone.id, phone.copy(allDay = false, start = phone.start + 3600000, end = phone.start + 7200000)))
        f.pass(); actual = f.standard.read(EventKey(id))!!
        assertFalse(actual.allDay); assertEquals(ZoneId.systemDefault().id, actual.timeZone)
        assertEquals(phone.start + 3600000, actual.start)
    }

    @Test fun interruptedPhoneImportRecoversAcrossDatabaseReopen() = scenario { f ->
        f.store.firstRunDone = true; f.insert(location = "Home")
        val interrupted = object : MuditaCalendarPort by f.phone {
            override fun insert(event: MuditaEvent): Long? {
                f.phone.insert(event); throw SimulatedDeath()
            }
        }
        try { BridgeEngine(interrupted, f.standard, f.store, f.config).pass(f.now); fail("Expected death") }
        catch (_: SimulatedDeath) { }
        BridgeStore(f.context).use { BridgeEngine(f.phone, f.standard, it, f.config).pass(f.now) }
        assertEquals(1, f.phone.all().size); assertEquals(1, f.standard.events(setOf(f.calendarId)).size)
        assertEquals(1, f.store.all().size)
    }

    @Test fun interruptedStandardUploadRecoversAcrossDatabaseReopen() = scenario { f ->
        f.store.firstRunDone = true
        f.phone.insert(MuditaEvent(title = "Phone", description = "Body", start = f.now + 1000, end = f.now + 2000))
        val interrupted = object : StandardCalendarPort by f.standard {
            override fun insert(calendarId: Long, event: StandardEvent, intentId: String?): Long? {
                f.standard.insert(calendarId, event, intentId); throw SimulatedDeath()
            }
        }
        try { BridgeEngine(f.phone, interrupted, f.store, f.config).pass(f.now); fail("Expected death") }
        catch (_: SimulatedDeath) { }
        BridgeStore(f.context).use { BridgeEngine(f.phone, f.standard, it, f.config).pass(f.now) }
        assertEquals(1, f.phone.all().size); assertEquals(1, f.standard.events(setOf(f.calendarId)).size)
        assertEquals(1, f.store.all().size)
    }

    @Test fun standaloneBecomingSeriesRetiresTheOldIdentity() = scenario { f ->
        val id = f.insert(); f.pass()
        f.resolver.update(f.syncUri(ContentUris.withAppendedId(Events.CONTENT_URI, id)), ContentValues().apply {
            put(Events.RRULE, "FREQ=WEEKLY;COUNT=20"); put(Events.DURATION, "PT1H"); putNull(Events.DTEND)
        }, null, null)
        val expected = f.standard.instances(setOf(f.calendarId), f.now, f.now + 8 * BridgeEngine.WEEK).size
        f.pass(); assertEquals(expected, f.phone.all().size)
        assertTrue(f.store.all().all { it.standardKey.instanceStart != null })
        assertNotNull(f.standard.read(EventKey(id)))
    }

    @Test fun expiredOccurrenceEditsAndDeletesReachTheRealProvider() = scenario { f ->
        f.insert(recurring = true); f.pass()
        val rows = f.store.all().sortedBy { it.standardKey.instanceStart }
        val edited = f.phone.all().single { it.id == rows[0].muditaId }
        assertTrue(f.phone.update(edited.id, edited.copy(title = "Edited before expiry")))
        assertTrue(f.phone.delete(rows[1].muditaId))
        f.pass(f.now + 2 * BridgeEngine.WEEK)
        assertEquals("Edited before expiry", f.standard.read(rows[0].standardKey)!!.title)
        assertNull(f.standard.read(rows[1].standardKey))
        assertTrue(f.store.all().none { it.standardKey in rows.take(2).map { row -> row.standardKey } })
    }

    @Test fun locationRenderingDoesNotConsumeOrdinaryDescriptionText() = scenario { f ->
        val id = f.insert()
        val ordinary = "Location: Gate A\nBring ID"
        f.resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), ContentValues().apply {
            put(Events.DESCRIPTION, ordinary)
        }, null, null)
        f.pass(); var phone = f.phone.all().single()
        assertTrue(f.phone.update(phone.id, phone.copy(title = "Title edit"))); f.pass()
        assertEquals(ordinary, f.standard.read(EventKey(id))!!.description)
        f.resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), ContentValues().apply {
            put(Events.EVENT_LOCATION, "Building\r\nRoom")
        }, null, null)
        f.pass(); phone = f.phone.all().single()
        assertEquals(3, phone.description.lines().size)
        assertTrue(f.phone.update(phone.id, phone.copy(title = "Another title"))); f.pass()
        assertEquals(ordinary, f.standard.read(EventKey(id))!!.description)
        assertEquals("Building\r\nRoom", f.standard.read(EventKey(id))!!.location)
    }

    @Test fun replacingTheWholeLocationLinePreservesTheServerLocationAndBodyBoundary() = scenario { f ->
        val id = f.insert(location = "Original place"); f.pass()
        val phone = f.phone.all().single()
        val body = phone.description.substringAfter('\n').replace("Body", "Edited body")
        assertTrue(f.phone.update(phone.id, phone.copy(title = "Phone edit", description = "Location: Elsewhere\n$body")))
        f.pass()
        val actual = f.standard.read(EventKey(id))!!
        assertEquals("Edited body", actual.description); assertEquals("Original place", actual.location)
        assertTrue(f.phone.update(phone.id, phone.copy(title = "Another edit", description = body)))
        f.pass()
        assertEquals("Edited body", f.standard.read(EventKey(id))!!.description)
    }

    @Test fun expiredOccurrenceMovedIntoTheWindowKeepsTheExistingPhoneRow() = scenario { f ->
        f.insert(recurring = true); f.pass()
        val row = f.store.all().minBy { it.standardKey.instanceStart!! }
        val phone = f.phone.all().single { it.id == row.muditaId }
        assertTrue(f.phone.update(phone.id, phone.copy(start = phone.start + 2 * BridgeEngine.WEEK,
            end = phone.end + 2 * BridgeEngine.WEEK)))
        f.pass(f.now + BridgeEngine.WEEK)
        assertTrue(f.phone.all().any { it.id == phone.id })
        assertEquals(phone.start + 2 * BridgeEngine.WEEK, f.standard.read(row.standardKey)!!.start)
        assertEquals(f.standard.instances(setOf(f.calendarId), f.now + BridgeEngine.WEEK,
            f.now + 9 * BridgeEngine.WEEK).size, f.phone.all().size)
    }

    @Test fun rdateExceptionKeepsBaseRemindersAndDoesNotChangeExistingOnes() = scenario { f ->
        val id = f.insert()
        val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        val dates = listOf(f.now + 3600000, f.now + BridgeEngine.WEEK + 3600000)
            .joinToString(",") { stamp.format(Instant.ofEpochMilli(it)) }
        f.resolver.update(f.syncUri(ContentUris.withAppendedId(Events.CONTENT_URI, id)), ContentValues().apply {
            put(Events.RDATE, dates); put(Events.DURATION, "PT1H"); putNull(Events.DTEND)
        }, null, null)
        val reminders = listOf(10 to Reminders.METHOD_ALERT, 60 to Reminders.METHOD_EMAIL)
        for ((minutes, method) in reminders) f.resolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
            put(Reminders.EVENT_ID, id); put(Reminders.MINUTES, minutes); put(Reminders.METHOD, method)
        })!!
        f.resolver.insert(CalendarContract.Attendees.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Attendees.EVENT_ID, id); put(CalendarContract.Attendees.ATTENDEE_NAME, "Guest")
            put(CalendarContract.Attendees.ATTENDEE_EMAIL, "guest@example.invalid")
            put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_REQUIRED)
            put(CalendarContract.Attendees.ATTENDEE_RELATIONSHIP, CalendarContract.Attendees.RELATIONSHIP_ATTENDEE)
            put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_ACCEPTED)
        })!!
        f.pass(); val row = f.store.all().minBy { it.standardKey.instanceStart!! }
        val phone = f.phone.all().single { it.id == row.muditaId }
        assertTrue(f.phone.update(phone.id, phone.copy(title = "RDATE edit"))); f.pass()
        val exceptionId = f.resolver.query(Events.CONTENT_URI, arrayOf(Events._ID),
            "${Events.ORIGINAL_ID} = ? AND ${Events.ORIGINAL_INSTANCE_TIME} = ?",
            arrayOf(id.toString(), row.standardKey.instanceStart.toString()), null)!!.use {
            assertTrue(it.moveToFirst()); it.getLong(0)
        }
        fun readReminders(eventId: Long) = f.resolver.query(Reminders.CONTENT_URI,
            arrayOf(Reminders.MINUTES, Reminders.METHOD), "${Reminders.EVENT_ID} = ?",
            arrayOf(eventId.toString()), Reminders.MINUTES)!!.use { c ->
            buildList { while (c.moveToNext()) add(c.getInt(0) to c.getInt(1)) }
        }
        assertEquals(reminders, readReminders(exceptionId)); assertEquals(reminders, readReminders(id))
        f.resolver.query(CalendarContract.Attendees.CONTENT_URI, arrayOf(CalendarContract.Attendees.ATTENDEE_EMAIL),
            "${CalendarContract.Attendees.EVENT_ID} = ?", arrayOf(exceptionId.toString()), null)!!.use {
            assertTrue(it.moveToFirst()); assertEquals("guest@example.invalid", it.getString(0)); assertEquals(1, it.count)
        }
        f.resolver.update(Reminders.CONTENT_URI, ContentValues().apply { put(Reminders.MINUTES, 15) },
            "${Reminders.EVENT_ID} = ? AND ${Reminders.MINUTES} = ?", arrayOf(exceptionId.toString(), "10"))
        assertTrue(f.phone.update(phone.id, phone.copy(title = "Second edit"))); f.pass()
        assertEquals(listOf(15 to Reminders.METHOD_ALERT, 60 to Reminders.METHOD_EMAIL), readReminders(exceptionId))
        assertEquals(reminders, readReminders(id))
    }

    @Test fun calendarProviderDurationDialectDoesNotStopCalendarReads() = scenario { f ->
        for (duration in listOf("P1W", "P3600S", "PT3600S", "P1D")) {
            val id = f.insert(recurring = true)
            f.resolver.update(f.syncUri(ContentUris.withAppendedId(Events.CONTENT_URI, id)), ContentValues().apply {
                put(Events.DURATION, duration)
            }, null, null)
            assertNotNull(f.standard.read(EventKey(id)))
        }
        assertEquals(4, f.standard.events(setOf(f.calendarId)).size)
        f.pass(); assertTrue(f.phone.all().isNotEmpty())
    }

    @Test fun bootSchedulesImmediatelyEvenWhenTheProviderLaneIsBusy() = scenario { f ->
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val blocked = SyncRuntime.enqueue { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
        withFixtureCleanup({ release.countDown() }, { blocked.get(15, TimeUnit.SECONDS) }) {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.insert()
            BootReceiver().onReceive(f.context, Intent(Intent.ACTION_BOOT_COMPLETED))
            val scheduler = f.context.getSystemService(JobScheduler::class.java)
            // The boot job owns its own lifetime, independently of the occupied provider executor.
            assertNotNull(scheduler.getPendingJob(0x4b5304))
            assertNotNull(scheduler.getPendingJob(SyncRuntime.PERIODIC_JOB))
            assertNotNull(scheduler.getPendingJob(SyncRuntime.HORIZON_JOB))
            assertTrue(f.phone.all().isEmpty())
            release.countDown()
            f.await { f.phone.all().size == 1 }
        }
    }

    @Test fun interruptedPhoneUpdateIsRecoveredWithoutDuplicateRows() = scenario { f ->
        val id = f.insert(); f.pass()
        f.resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), ContentValues().apply { put(Events.TITLE, "Server edit") }, null, null)
        interruptBeforeAcknowledgement(f)
        assertEquals("Server edit", f.phone.all().single().title); assertEquals(1, f.store.all().size)
    }
    @Test fun interruptedPhoneDeleteDoesNotResurrectTheStandardEvent() = scenario { f ->
        val id = f.insert(); f.pass(); assertTrue(f.standard.delete(EventKey(id)))
        interruptBeforeAcknowledgement(f)
        assertTrue(f.phone.all().isEmpty()); assertTrue(f.store.all().isEmpty()); assertNull(f.standard.read(EventKey(id)))
    }
    @Test fun interruptedOccurrenceEditRecoversItsExistingException() = scenario { f ->
        f.insert(recurring = true); f.pass()
        val row = f.store.all().minBy { it.standardKey.instanceStart!! }
        val phone = f.phone.all().single { it.id == row.muditaId }
        assertTrue(f.phone.update(phone.id, phone.copy(title = "Recovered exception")))
        interruptBeforeAcknowledgement(f)
        assertEquals("Recovered exception", f.standard.read(row.standardKey)!!.title)
        f.resolver.query(Events.CONTENT_URI, arrayOf(Events._ID),
            "${Events.ORIGINAL_ID} = ? AND ${Events.ORIGINAL_INSTANCE_TIME} = ?",
            arrayOf(row.standardKey.eventId.toString(), row.standardKey.instanceStart.toString()), null)!!.use { assertEquals(1, it.count) }
        assertEquals(8, f.phone.all().size)
    }
    @Test fun interruptedOccurrenceDeleteDoesNotRecreateTheCancelledInstance() = scenario { f ->
        f.insert(recurring = true); f.pass()
        val row = f.store.all().minBy { it.standardKey.instanceStart!! }
        assertTrue(f.phone.delete(row.muditaId)); interruptBeforeAcknowledgement(f)
        assertNull(f.standard.read(row.standardKey)); assertEquals(7, f.phone.all().size)
        assertTrue(f.store.all().none { it.standardKey == row.standardKey })
    }
    @Test fun movedEventInUnselectedCalendarLosesOnlyItsPhoneCopy() = scenario { f ->
        val id = f.insert(); f.pass()
        val other = ContentUris.parseId(f.resolver.insert(f.syncUri(Calendars.CONTENT_URI), ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, f.account.name); put(Calendars.ACCOUNT_TYPE, f.account.type)
            put(Calendars.NAME, "Unselected"); put(Calendars.OWNER_ACCOUNT, f.account.name)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER); put(Calendars.SYNC_EVENTS, 1)
        })!!)
        withFixtureCleanup({ f.resolver.delete(f.syncUri(Calendars.CONTENT_URI), "${Calendars._ID} = ?", arrayOf(other.toString())) }) {
            f.resolver.update(f.syncUri(ContentUris.withAppendedId(Events.CONTENT_URI, id)), ContentValues().apply {
                put(Events.CALENDAR_ID, other)
            }, null, null)
            f.pass(); assertTrue(f.phone.all().isEmpty()); assertTrue(f.store.all().isEmpty())
            assertEquals(other, f.standard.read(EventKey(id))!!.calendarId)
        }
    }
    @Test fun ruleExceptionStillInheritsTheBaseReminders() = scenario { f ->
        val id = f.insert(recurring = true)
        for (minutes in listOf(10, 60)) f.resolver.insert(Reminders.CONTENT_URI, ContentValues().apply {
            put(Reminders.EVENT_ID, id); put(Reminders.MINUTES, minutes); put(Reminders.METHOD, Reminders.METHOD_ALERT)
        })!!
        f.pass(); val row = f.store.all().minBy { it.standardKey.instanceStart!! }
        val phone = f.phone.all().single { it.id == row.muditaId }
        assertTrue(f.phone.update(phone.id, phone.copy(title = "RRULE edit"))); f.pass()
        val exceptionId = f.resolver.query(Events.CONTENT_URI, arrayOf(Events._ID),
            "${Events.ORIGINAL_ID} = ? AND ${Events.ORIGINAL_INSTANCE_TIME} = ?",
            arrayOf(id.toString(), row.standardKey.instanceStart.toString()), null)!!.use { assertTrue(it.moveToFirst()); it.getLong(0) }
        f.resolver.query(Reminders.CONTENT_URI, arrayOf(Reminders.MINUTES), "${Reminders.EVENT_ID} = ?",
            arrayOf(exceptionId.toString()), Reminders.MINUTES)!!.use { c ->
            assertEquals(listOf(10, 60), buildList { while (c.moveToNext()) add(c.getInt(0)) })
        }
    }

    @Test fun failedMappingCommitRetainsBothThePreviousMappingAndTheIntent() = scenario { f ->
        f.insert(); f.insert(); f.pass()
        val before = f.store.all(); assertEquals(2, before.size)
        val row = before.first()
        val phone = f.phone.all().single { it.id == row.muditaId }
        val intent = WriteIntent(kind = WriteKind.PHONE_UPDATE, mapping = row, phoneBefore = phone,
            phoneAfter = phone, standardBefore = f.standard.read(row.standardKey))
        f.store.prepare(intent)
        try {
            // Force the unique standard-identity constraint to fail after removing the old mapping.
            f.store.acknowledge(intent.id, before.last().copy(muditaId = Long.MAX_VALUE), row.muditaId)
            fail("Expected mapping constraint failure")
        } catch (_: android.database.sqlite.SQLiteConstraintException) { }
        BridgeStore(f.context).use {
            assertEquals(before, it.all()); assertEquals(listOf(intent), it.pending())
        }
    }

    @Test fun reopenedPendingWriteRespectsTheCurrentCalendarSelection() = scenario { f ->
        val id = f.insert(); f.pass()
        val row = f.store.all().single(); val before = f.standard.read(EventKey(id))!!
        val phone = f.phone.all().single().copy(title = "Pending edit")
        assertTrue(f.phone.update(phone.id, phone))
        f.store.prepare(WriteIntent(kind = WriteKind.STANDARD_UPDATE, mapping = row, phoneBefore = phone,
            standardBefore = before, standardAfter = EventMapper().toStandard(phone, before, row.generatedLocation)))
        f.config.write(SyncSettings(emptySet(), null))
        BridgeStore(f.context).use { BridgeEngine(f.phone, f.standard, it, f.config).pass(f.now) }
        assertEquals(before, f.standard.read(EventKey(id)))
        assertTrue(f.phone.all().isEmpty()); assertTrue(f.store.all().isEmpty()); assertTrue(f.store.pending().isEmpty())
    }

    private fun interruptBeforeAcknowledgement(f: Fixture) {
        val interrupted = object : MappingStore by f.store {
            override fun acknowledge(intentId: String, mapping: Mapping?, removeMuditaId: Long?) { throw SimulatedDeath() }
        }
        try { BridgeEngine(f.phone, f.standard, interrupted, f.config).pass(f.now); fail("Expected death") }
        catch (_: SimulatedDeath) { }
        assertTrue(f.store.pending().isNotEmpty())
        BridgeStore(f.context).use { BridgeEngine(f.phone, f.standard, it, f.config).pass(f.now) }
        assertTrue(f.store.pending().isEmpty())
    }

    @Test fun runtimeProviderFailureReachesJobCompletionAsRetry() = scenario { f ->
        f.store.firstRunDone = true; f.insert()
        f.resolver.call(MuditaCalendar.URI, "failInsert", null, null)
        val completions = mutableListOf<Boolean>()
        PassLifecycle.run({ SyncRuntime.pass(f.context) }, {}, { completions.add(it) })
        assertEquals(listOf(true), completions)
        assertTrue(f.store.status.contains("incomplete"))
    }

    @Test fun runtimeQueryFailureRetriesButAnIncompatibleSchemaStopsWithoutRetry() = scenario { f ->
        val completions = mutableListOf<Boolean>()
        f.resolver.call(MuditaCalendar.URI, "failQuery", null, null)
        PassLifecycle.run({ SyncRuntime.pass(f.context) }, {}, { completions.add(it) })
        assertEquals(listOf(true), completions)
        assertTrue(f.store.status.contains("incomplete"))
        f.schema(true)
        PassLifecycle.run({ SyncRuntime.pass(f.context) }, {}, { completions.add(it) })
        assertEquals(listOf(true, false), completions)
        assertTrue(f.store.status.startsWith("stopped:"))
    }

    @Test fun textBeforeBodyFramingReachesTheStandardConsumer() = scenario { f ->
        val id = f.insert(location = "Home"); f.pass()
        val phone = f.phone.all().single()
        val description = phone.description.replace(EventMapper.BODY_MARKER, "Added " + EventMapper.BODY_MARKER)
        assertTrue(f.phone.update(phone.id, phone.copy(description = description)))
        f.pass()
        assertEquals("Added Body", f.standard.read(EventKey(id))!!.description)
        assertEquals("Home", f.standard.read(EventKey(id))!!.location)
    }

    @Test fun unknownImportNeverClaimsOrDeletesAnIndependentMatchingPhoneEvent() = scenario { f ->
        f.store.firstRunDone = true; val id = f.insert(location = "Home")
        val remote = f.standard.read(EventKey(id))!!
        val intent = WriteIntent(kind = WriteKind.IMPORT, standardBefore = remote,
            phoneAfter = EventMapper().toMudita(remote), attempted = true)
        f.store.prepare(intent)
        val independent = f.phone.insert(EventMapper().toMudita(remote).copy(preReminder = true))!!
        BridgeStore(f.context).use { BridgeEngine(f.phone, f.standard, it, f.config).pass(f.now) }
        assertTrue(f.store.all().none { it.standardKey == remote.key })
        assertTrue(f.store.all().any { it.muditaId == independent })
        assertTrue(f.standard.delete(remote.key)); f.pass()
        assertTrue(f.phone.all().single { it.id == independent }.preReminder)
        assertEquals(listOf(intent), f.store.pending())
    }

    @Test fun twoUnresolvedItemsDoNotSuspendOtherChangesAndResolveAcrossDatabaseReopen() = scenario { f ->
        val existing = f.insert(); f.pass()
        val existingPhone = f.phone.all().single()
        val blocked = f.insert(); val remote = f.standard.read(EventKey(blocked))!!
        val import = WriteIntent(kind = WriteKind.IMPORT, standardBefore = remote,
            phoneAfter = EventMapper().toMudita(remote), attempted = true)
        val p = MuditaEvent(title = "Unknown upload", description = "Body", start = f.now, end = f.now + 1000)
        val pid = f.phone.insert(p)!!; val source = f.phone.all().single { it.id == pid }
        val upload = WriteIntent(kind = WriteKind.UPLOAD, phoneBefore = source, attempted = true,
            standardAfter = EventMapper().toStandard(source, remote.copy(id = 0)))
        f.store.prepare(import); f.store.prepare(upload)
        f.resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, existing), ContentValues().apply {
            put(Events.TITLE, "Unrelated update")
        }, null, null)
        val another = f.insert()
        val independent = f.phone.insert(p.copy(title = "Independent upload"))!!
        f.pass()
        assertEquals("Unrelated update", f.phone.all().single { it.id == existingPhone.id }.title)
        assertTrue(f.store.all().any { it.standardKey == EventKey(another) })
        assertTrue(f.store.all().any { it.muditaId == independent })
        assertEquals(2, f.store.pending().size)
        BridgeStore(f.context).use {
            val engine = BridgeEngine(f.phone, f.standard, it, f.config)
            engine.resolvePending(f.now, import.id, WriteResolution.NotApplied)
            engine.resolvePending(f.now, upload.id, WriteResolution.NotApplied)
            engine.pass(f.now)
        }
        assertTrue(f.store.pending().isEmpty())
        assertTrue(f.store.all().any { it.standardKey == remote.key })
        assertTrue(f.store.all().any { it.muditaId == pid })
    }

    @Test fun providerStoredReceiptRecoversEditedImportWithoutClaimingAnEqualIndependentRow() = scenario { f ->
        f.store.firstRunDone = true; val id = f.insert(location = "Home")
        val remote = f.standard.read(EventKey(id))!!
        val independent = f.phone.insert(EventMapper().toMudita(remote).copy(preReminder = true))!!
        val interrupted = object : MuditaCalendarPort by f.phone {
            override fun insert(event: MuditaEvent): Long? {
                val inserted = f.phone.insert(event)!!
                val actual = f.phone.all().single { it.id == inserted }
                assertTrue(actual.insertReceipts.isNotEmpty())
                assertTrue(f.phone.update(inserted, actual.copy(title = "Edited after insertion")))
                throw SimulatedDeath()
            }
        }
        try { BridgeEngine(interrupted, f.standard, f.store, f.config).pass(f.now); fail("Expected death") }
        catch (_: SimulatedDeath) { }
        BridgeStore(f.context).use { BridgeEngine(f.phone, f.standard, it, f.config).pass(f.now) }
        f.pass()
        val row = f.store.all().single { it.standardKey == remote.key }
        assertNotEquals(independent, row.muditaId)
        assertEquals("Edited after insertion", f.standard.read(remote.key)!!.title)
        assertTrue(f.phone.all().single { it.id == independent }.preReminder)
        assertFalse(f.standard.read(remote.key)!!.description.any { it == '\u200B' || it == '\u200C' })
    }

    @Test fun confirmedProviderUpdateAndANewerPhoneEditSurviveDatabaseReopen() = scenario { f ->
        val id = f.insert(); f.pass()
        val original = f.phone.all().single()
        f.resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), ContentValues().apply { put(Events.TITLE, "B") }, null, null)
        val interleaved = object : MuditaCalendarPort by f.phone {
            override fun update(id: Long, event: MuditaEvent): Boolean {
                val result = f.phone.update(id, event)
                assertTrue(f.phone.update(id, f.phone.all().single { it.id == id }.copy(title = "C", preReminder = true)))
                return result
            }
        }
        val interrupted = object : MappingStore by f.store {
            override fun acknowledge(intentId: String, mapping: Mapping?, removeMuditaId: Long?) { throw SimulatedDeath() }
        }
        try { BridgeEngine(interleaved, f.standard, interrupted, f.config).pass(f.now); fail("Expected death") }
        catch (_: SimulatedDeath) { }
        assertTrue(f.store.pending().single().confirmed)
        BridgeStore(f.context).use { BridgeEngine(f.phone, f.standard, it, f.config).pass(f.now) }
        assertEquals("C", f.standard.read(EventKey(id))!!.title)
        assertEquals("C", f.phone.all().single().title)
        assertEquals(original.id, f.phone.all().single().id); assertTrue(f.phone.all().single().preReminder)
    }

    @Test fun pendingRetirementRechecksCurrentSelectionAndPreservesIdentityAndReminder() = scenario { f ->
        f.insert(); f.pass()
        val original = f.phone.all().single().copy(preReminder = true)
        assertTrue(f.phone.update(original.id, original))
        val row = f.store.all().single()
        f.store.prepare(WriteIntent(kind = WriteKind.PHONE_DELETE, mapping = row, phoneBefore = original, attempted = true))
        BridgeStore(f.context).use { BridgeEngine(f.phone, f.standard, it, f.config).pass(f.now) }
        assertEquals(original, f.phone.all().single()); assertEquals(row.muditaId, f.store.all().single().muditaId)
        assertTrue(f.store.pending().isEmpty())
    }

    @Test fun itemReservationsAndAcknowledgementsAreAtomicAndIndependent() = scenario { f ->
        f.store.firstRunDone = true
        val first = f.standard.read(EventKey(f.insert()))!!
        val second = f.standard.read(EventKey(f.insert()))!!
        val a = WriteIntent(kind = WriteKind.IMPORT, standardBefore = first, phoneAfter = EventMapper().toMudita(first), attempted = true)
        val b = WriteIntent(kind = WriteKind.IMPORT, standardBefore = second, phoneAfter = EventMapper().toMudita(second), attempted = true)
        f.store.prepare(a); f.store.prepare(b)
        try { f.store.prepare(a.copy(id = "competing")); fail("Expected reservation conflict") }
        catch (_: android.database.sqlite.SQLiteConstraintException) { }
        BridgeStore(f.context).use {
            assertEquals(listOf(a, b), it.pending())
            it.acknowledge(a.id, null, null)
            assertEquals(listOf(b), it.pending())
            it.prepare(a.copy(id = "released"))
            assertEquals(2, it.pending().size)
        }
    }

    @Test fun pendingExpiredRetirementKeepsARemindedOccurrenceMovedBackIntoTheWindow() = scenario { f ->
        f.insert(recurring = true); f.pass()
        val row = f.store.all().minBy { it.standardKey.instanceStart!! }
        val original = f.phone.all().single { it.id == row.muditaId }.copy(preReminder = true)
        assertTrue(f.phone.update(original.id, original))
        f.store.prepare(WriteIntent(kind = WriteKind.PHONE_DELETE, mapping = row, phoneBefore = original, attempted = true))
        val before = f.standard.read(row.standardKey)!!
        val moved = before.copy(start = before.start + 2 * BridgeEngine.WEEK, end = before.end + 2 * BridgeEngine.WEEK)
        assertTrue(f.standard.update(row.standardKey, moved))
        BridgeStore(f.context).use {
            BridgeEngine(f.phone, f.standard, it, f.config).pass(f.now + BridgeEngine.WEEK)
        }
        val actual = f.phone.all().single { it.id == original.id }
        assertEquals(moved.start, actual.start); assertTrue(actual.preReminder)
        assertEquals(original.id, f.store.all().single { it.standardKey == row.standardKey }.muditaId)
    }

    @Test fun resolvingAnInsertionCannotStealAnAlreadyMappedProviderIdentity() = scenario { f ->
        f.insert(); f.pass()
        val original = f.phone.all().single(); val originalMapping = f.store.all().single()
        val remote = f.standard.read(EventKey(f.insert()))!!
        val intent = WriteIntent(kind = WriteKind.IMPORT, standardBefore = remote,
            phoneAfter = EventMapper().toMudita(remote), attempted = true)
        f.store.prepare(intent)
        val engine = BridgeEngine(f.phone, f.standard, f.store, f.config)
        engine.resolvePending(f.now, intent.id, WriteResolution.Applied(original.id))
        engine.pass(f.now)
        assertEquals(original, f.phone.all().single()); assertEquals(originalMapping, f.store.all().single())
        assertEquals(intent.id, f.store.pending().single().id)
        try {
            f.store.acknowledge(intent.id, originalMapping.copy(standardKey = remote.key), null)
            fail("Expected ownership conflict")
        } catch (_: IllegalStateException) { }
        assertEquals(originalMapping, f.store.all().single())
    }

    @Test fun firstRunWithHistoricalProviderReceiptsKeepsTheExistingPhoneRow() = scenario { f ->
        val id = f.insert(location = "Home"); f.pass()
        val original = f.phone.all().single()
        assertTrue(original.insertReceipts.isNotEmpty())
        // Simulate the bridge's first-run state after reinstall, not loss of the phone provider.
        f.store.remove(original.id); f.store.firstRunDone = false
        f.pass()
        assertEquals(original, f.phone.all().single())
        assertEquals(EventKey(id), f.store.all().single().standardKey)
        assertEquals(1, f.standard.events(setOf(f.calendarId)).size)
    }

    @Test fun independentCopyWithHistoricalReceiptUploadsWithoutLeakingTheReceipt() = scenario { f ->
        f.insert(); f.pass()
        val original = f.phone.all().single()
        val copy = f.phone.insert(original.copy(id = 0, title = "Independent copy"))!!
        f.pass()
        val row = f.store.all().single { it.muditaId == copy }
        val standard = f.standard.read(row.standardKey)!!
        assertEquals("Independent copy", standard.title); assertEquals("Body", standard.description)
        assertEquals(2, f.standard.events(setOf(f.calendarId)).size)
        assertTrue(f.store.pending().isEmpty())
    }

    private class SimulatedDeath : Error()
    private fun scenario(body: (Fixture) -> Unit) {
        val f = Fixture()
        withFixtureCleanup(
            { f.context.getSystemService(JobScheduler::class.java).cancelAll(); SyncRuntime.enqueue {}.get(15, TimeUnit.SECONDS) },
            { f.schema(false); f.phone.all().forEach { check(f.phone.delete(it.id)) } },
            { if (f.calendarId >= 0) f.resolver.delete(f.syncUri(Calendars.CONTENT_URI), "${Calendars._ID} = ?", arrayOf(f.calendarId.toString())) },
            { if (f.accountAdded) check(AccountManager.get(f.context).removeAccountExplicitly(f.account)) },
            { f.store.writableDatabase.delete("pairs", null, null) },
            { f.store.writableDatabase.delete("state", null, null) },
            { f.store.writableDatabase.delete("write_reservation", null, null) },
            { f.store.writableDatabase.delete("write_intent", null, null); f.store.close() },
            { f.config.write(f.previous) },
        ) { f.setup(); body(f) }
    }

    private class Fixture {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context: Context = instrumentation.targetContext
        val resolver = context.contentResolver
        val phone = MuditaCalendar(resolver)
        val standard = StandardCalendar(resolver)
        val store = BridgeStore(context)
        val config = PreferenceSyncConfig(context)
        val previous = config.read()
        val account = Account("kotatsu-sync-test-${UUID.randomUUID()}", "com.alexindigo.kotatsu.sync.test")
        var accountAdded = false
        var calendarId = -1L
        val now = System.currentTimeMillis()
        fun setup() {
            for (permission in listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
            accountAdded = AccountManager.get(context).addAccountExplicitly(account, null, null)
            check(accountAdded)
            val uri = resolver.insert(syncUri(Calendars.CONTENT_URI), ContentValues().apply {
                put(Calendars.ACCOUNT_NAME, account.name); put(Calendars.ACCOUNT_TYPE, account.type)
                put(Calendars.NAME, account.name); put(Calendars.CALENDAR_DISPLAY_NAME, "Bridge tests")
                put(Calendars.OWNER_ACCOUNT, account.name); put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
                put(Calendars.VISIBLE, 1); put(Calendars.SYNC_EVENTS, 1); put(Calendars.CALENDAR_TIME_ZONE, "UTC")
            })!!
            calendarId = ContentUris.parseId(uri)
            config.write(SyncSettings(setOf(calendarId), calendarId))
        }
        fun insert(location: String = "", recurring: Boolean = false, zone: String = "UTC"): Long {
            val start = now + 3600000
            // Stand in for DAVx5's server download, including its server-owned sync identity.
            return ContentUris.parseId(resolver.insert(syncUri(Events.CONTENT_URI), ContentValues().apply {
                put(Events._SYNC_ID, UUID.randomUUID().toString())
                put(Events.CALENDAR_ID, calendarId); put(Events.TITLE, "Test event"); put(Events.DESCRIPTION, "Body")
                put(Events.EVENT_LOCATION, location); put(Events.EVENT_TIMEZONE, zone); put(Events.DTSTART, start)
                if (recurring) { put(Events.RRULE, "FREQ=WEEKLY;COUNT=20"); put(Events.DURATION, "PT1H") }
                else put(Events.DTEND, start + 3600000)
            })!!)
        }
        fun pass(at: Long = now) { BridgeEngine(phone, standard, store, config).pass(at) }
        fun syncUri(uri: Uri): Uri = uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name).appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type).build()
        fun schema(enabled: Boolean) {
            val done = CountDownLatch(1)
            context.sendOrderedBroadcast(Intent().setComponent(ComponentName("com.alexindigo.kotatsu.fakemuditacalendar",
                "com.alexindigo.kotatsu.fakemuditacalendar.SchemaReceiver")).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra("schema2", enabled), null, object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) { done.countDown() }
                }, Handler(Looper.getMainLooper()), 0, null, null)
            check(done.await(10, TimeUnit.SECONDS)) { "Schema fixture broadcast did not complete" }
        }
        fun await(predicate: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 45000
            while (!predicate()) { check(SystemClock.uptimeMillis() < deadline) { "Calendar consumer did not converge" }; SystemClock.sleep(100) }
        }
    }
}
