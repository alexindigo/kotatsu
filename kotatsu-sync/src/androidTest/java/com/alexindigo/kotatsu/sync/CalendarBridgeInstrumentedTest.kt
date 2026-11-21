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
        assertEquals("Location: Original place\nBody", phone.description)
        assertTrue(f.phone.update(phone.id, phone.copy(title = "Phone edit", description = "Location: Ignored\nEdited", preReminder = true)))
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

    private fun scenario(body: (Fixture) -> Unit) {
        val f = Fixture()
        withFixtureCleanup(
            { f.context.getSystemService(JobScheduler::class.java).cancelAll(); SyncRuntime.enqueue {}.get(15, TimeUnit.SECONDS) },
            { f.schema(false); f.phone.all().forEach { check(f.phone.delete(it.id)) } },
            { if (f.calendarId >= 0) f.resolver.delete(f.syncUri(Calendars.CONTENT_URI), "${Calendars._ID} = ?", arrayOf(f.calendarId.toString())) },
            { if (f.accountAdded) check(AccountManager.get(f.context).removeAccountExplicitly(f.account)) },
            { f.store.writableDatabase.delete("pairs", null, null); f.store.writableDatabase.delete("state", null, null); f.store.close() },
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
        fun pass() { BridgeEngine(phone, standard, store, config).pass(now) }
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
