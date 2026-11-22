package com.alexindigo.kotatsu.sync

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.app.job.JobScheduler
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import android.provider.ContactsContract.Settings
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.ui.LocalPalette
import com.gezimos.katapult.ui.PaperPalette
import com.gezimos.katapult.withFixtureCleanup
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Public settings actions observed at the real providers and durable preferences. */
class SyncSettingsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val ui = SyncSettingsUi(instrumentation)
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val type = "com.alexindigo.kotatsu.sync.test"
    private val personal = ContactAccount("Personal", type)
    private val family = ContactAccount("Family", type)
    private fun main(work: () -> Unit) = instrumentation.runOnMainSync(work)
    private fun drain() = SyncRuntime.enqueue {}.get(15, TimeUnit.SECONDS)
    /** Cancellation is not native completion; own every producer before constructing fixtures. */
    private fun quiesceJobs() {
        val services = setOf(CalendarChangedJob::class.java.name, ContactsChangedJob::class.java.name,
            PeriodicPassJob::class.java.name, HorizonJob::class.java.name, BootPassJob::class.java.name)
        @Suppress("DEPRECATION")
        fun active() = context.getSystemService(android.app.ActivityManager::class.java)
            .getRunningServices(Int.MAX_VALUE).any { it.service.className in services }
        scheduler.cancelAll()
        awaitSyncSettings("Native bridge services and their lane must finish before fixture writes") {
            instrumentation.waitForIdleSync()
            scheduler.cancelAll()
            if (active()) false else {
                drain()
                instrumentation.waitForIdleSync()
                !active() && scheduler.allPendingJobs.isEmpty()
            }
        }
    }
    private fun config() = PreferenceSyncConfig(context).read()
    private fun status() = BridgeStore(context).use { it.syncStatus }
    private fun open() = instrumentation.startActivitySync(Intent(context, SyncSettingsActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SyncSettingsActivity
    private fun close(activity: Activity) = main { activity.finish() }
    private fun seek(label: String) {
        val previous = context.getString(com.gezimos.katapult.paper.R.string.paper_previous_page)
        val next = context.getString(com.gezimos.katapult.paper.R.string.paper_next_page)
        instrumentation.waitForIdleSync()
        if (ui.has(label)) return
        while (ui.has(previous)) { ui.click(previous); if (ui.has(label)) return }
        while (ui.has(next)) { ui.click(next); if (ui.has(label)) return }
        ui.await(label)
    }

    @Test fun launcherArmsJobsAndEveryCalendarAndPickerPersists() = fixture { f ->
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertEquals(SyncSettingsActivity::class.java.name, launch!!.component!!.className)
        val activity = open()
        withFixtureCleanup({ close(activity) }) {
            ui.await("FamilyR"); ui.await("PersonalR"); ui.await("Shown")
            assertTrue(config().calendars.isEmpty()); assertNull(config().newEventCalendar)
            assertFalse("New events is absent until a calendar is shown", ui.has("New events"))
            awaitSyncSettings("Opening arms all recurring jobs") {
                listOf(SyncRuntime.WATCH_JOB, SyncRuntime.CONTACTS_JOB, SyncRuntime.PERIODIC_JOB, SyncRuntime.HORIZON_JOB)
                    .all { scheduler.getPendingJob(it) != null }
            }
            assertFalse(ui.selected("Shown PersonalR"))
            ui.capture(activity, "calendars-unchecked")
            ui.click("Shown PersonalR")
            awaitSyncSettings("Checkbox writes selection and Personal default") {
                config().calendars == setOf(f.personalCalendar) && config().newEventCalendar == f.personalCalendar
            }
            ui.click("Shown FamilyR")
            awaitSyncSettings("Both shown calendars persist and render checked") {
                config().calendars == setOf(f.personalCalendar, f.familyCalendar) &&
                    ui.selected("Shown PersonalR") && ui.selected("Shown FamilyR")
            }
            val column = Rect().also { ui.find("Shown")!!.getBoundsInScreen(it) }
            assertEquals(64f * context.resources.displayMetrics.density, column.width().toFloat(), 1f)
            val control = Rect().also { ui.find("Shown PersonalR")!!.getBoundsInScreen(it) }
            val box = ui.drawings("paper_index_checkbox").single { it.center.y in control.top.toFloat()..control.bottom.toFloat() }
            val leader = ui.drawings("paper_index_leader").single { it.center.y in control.top.toFloat()..control.bottom.toFloat() }
            assertEquals("Shown header centres on its checkbox", column.exactCenterX(), box.center.x, 1f)
            assertEquals("Shared drawing gap", 7.5f * context.resources.displayMetrics.density, box.left - leader.right, 1f)
            assertFalse("Local calendars are never offered", ui.has("PC Sync"))
            ui.capture(activity, "calendars-shown")
            seek("New events"); ui.click("New events"); ui.awaitPage("New events")
            ui.await("PersonalR"); ui.await("FamilyR"); ui.capture(activity, "new-events")
            ui.back(); ui.awaitPage("Kotatsu Sync"); assertEquals(f.personalCalendar, config().newEventCalendar)
            ui.click("New events"); ui.awaitPage("New events"); ui.click("FamilyR")
            awaitSyncSettings("New events picker persists") { config().newEventCalendar == f.familyCalendar }
            seek("Look ahead"); ui.click("Look ahead"); ui.awaitPage("Look ahead")
            ui.await("1"); ui.capture(activity, "look-ahead")
            seek("26"); ui.click("26")
            awaitSyncSettings("Horizon persists") { config().horizonWeeks == 26 }
            seek("Contacts go to"); ui.await("Personal")
            assertEquals(personal, PreferenceContactsConfig(context).read().target)
            ui.click("Contacts go to"); ui.awaitPage("Contacts go to")
            ui.await("Family"); ui.await("Personal"); ui.capture(activity, "contacts-go-to")
            ui.click("Family")
            awaitSyncSettings("Contacts picker persists") { PreferenceContactsConfig(context).read().target == family }
            seek("Phone contacts"); ui.await("Move to Family")
        }
        val reopened = open()
        withFixtureCleanup({ close(reopened) }) {
            ui.await("Shown PersonalR"); assertTrue(ui.selected("Shown PersonalR"))
            seek("Look ahead"); ui.await("26 weeks")
            seek("Contacts go to"); ui.await("Family")
            assertEquals(f.familyCalendar, config().newEventCalendar)
        }
    }

    @Test fun confirmedMoveUsesTheDisplayedBookAndOnlyTheDisplayedContacts() = fixture { f ->
        val activity = open()
        withFixtureCleanup({ close(activity) }) {
            ui.await("PersonalR"); seek("Phone contacts")
            quiesceJobs()
            val source = f.phoneContact("Phone fixture")
            ui.click("Phone contacts"); ui.awaitPage("Move phone contacts")
            ui.await("1 contact moves to Personal. They stay on this phone and sync to your server.")
            ui.capture(activity, "move-confirmation")
            ui.click("Cancel")
            assertEquals(listOf(source), f.raw(null)); assertTrue(f.raw(personal).isEmpty())
            ui.click("Phone contacts"); ui.awaitPage("Move phone contacts")
            val later = f.phoneContact("Later phone fixture")
            ui.click("Move")
            awaitSyncSettings("Confirmed contact reaches the chosen book") { f.raw(personal).size == 1 }
            assertEquals(listOf(later), f.raw(null))
            assertEquals(1, status().changes)
            seek("Last sync"); ui.await(context.resources.getQuantityString(R.plurals.sync_status, 1,
                android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(status().lastPassAt!!)), 1))
            ui.capture(activity, "move-complete")
            SyncRuntime.enqueue { SyncRuntime.pass(context) }.get(15, TimeUnit.SECONDS)
            assertTrue(f.raw(null).isEmpty()); assertEquals(2, f.raw(personal).size)
        }
    }

    @Test fun countedLabelsRenderSingularAndPluralFromTheirRealSettingsAndProviders() = fixture { f ->
        val activity = open()
        withFixtureCleanup({ close(activity) }) {
            ui.await("PersonalR")
            quiesceJobs()
            for ((count, label) in listOf(1 to "1 week", 2 to "2 weeks")) {
                seek("Look ahead"); ui.click("Look ahead"); ui.awaitPage("Look ahead")
                seek(count.toString()); ui.click(count.toString())
                awaitSyncSettings("Look ahead persists $count and renders $label") {
                    config().horizonWeeks == count && ui.has(label)
                }
                quiesceJobs()
            }
            val completedAt = System.currentTimeMillis()
            val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(completedAt))
            for ((count, label) in listOf(0 to "0 changes", 1 to "1 change", 2 to "2 changes")) {
                BridgeStore(context).use { it.syncStatus = SyncStatus(completedAt, count) }
                seek("Last sync"); ui.await("$time, $label")
            }
            for ((count, sentence) in listOf(1 to "1 contact moves to Personal.", 2 to "2 contacts move to Personal.")) {
                f.phoneContact("Count fixture $count")
                seek("Phone contacts"); ui.click("Phone contacts"); ui.awaitPage("Move phone contacts")
                ui.await("$sentence They stay on this phone and sync to your server.")
                ui.click("Cancel")
                assertEquals("Confirmation does not move contacts", count, f.raw(null).size)
            }
        }
    }

    @Test fun syncNowUpdatesLastSyncAfterTheConsumerChangesAndShowsStops() = fixture { f ->
        val activity = open()
        withFixtureCleanup({ close(activity) }) {
            ui.await("PersonalR"); ui.click("Shown PersonalR")
            awaitSyncSettings("Selection persisted") { config().calendars == setOf(f.personalCalendar) }
            quiesceJobs()
            f.event()
            val before = status().lastPassAt ?: 0
            seek("Sync now"); ui.click("Sync now")
            awaitSyncSettings("Manual pass reaches Mudita and advances status") {
                (status().lastPassAt ?: 0) > before && MuditaCalendar(resolver).all().any { it.title == "Settings fixture event" }
            }
            seek("Last sync")
            awaitSyncSettings("Status is rendered from the completed pass") {
                val status = status()
                ui.has(context.resources.getQuantityString(R.plurals.sync_status, status.changes,
                    android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(status.lastPassAt!!)), status.changes))
            }
            ui.capture(activity, "sync-complete")
            quiesceJobs()
            BridgeStore(context).use { it.syncStatus = SyncStatus(System.currentTimeMillis(), 0, "contacts address book unavailable") }
            ui.await("Stopped: contacts address book unavailable")
            ui.capture(activity, "stopped")
        }
    }

    @Test fun missingAccessRowsForwardTheExactRequestsAndDisappearWhenGranted() = fixture {
        val activity = open()
        var calendarRequests = 0
        var contactsRequests = 0
        val denied = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun checkSelfPermission(permission: String) = if (permission in PERMISSIONS) PackageManager.PERMISSION_DENIED
                else super.checkSelfPermission(permission)
        }
        lateinit var owner: SyncSettingsOwner
        withFixtureCleanup({ close(activity) }) {
            main {
                owner = SyncSettingsOwner(denied)
                activity.setContent { CompositionLocalProvider(LocalPalette provides PaperPalette.Light(activity)) {
                    SyncSettingsBook(owner, activity::finish, { if (it) calendarRequests++ else contactsRequests++ }, {})
                } }
                owner.refresh()
            }
            ui.await("Calendar access"); ui.await("Contacts access"); ui.await("Allow")
            awaitSyncSettings("Permission snapshot loaded") { owner.snapshot.loaded }
            ui.click("Calendar access"); ui.click("Contacts access")
            assertEquals(1, calendarRequests); assertEquals(1, contactsRequests)
            ui.capture(activity, "access-missing")
            main {
                val granted = SyncSettingsOwner(context)
                granted.refresh()
                activity.setContent { CompositionLocalProvider(LocalPalette provides PaperPalette.Light(activity)) {
                    SyncSettingsBook(granted, activity::finish, {}, {})
                } }
            }
            ui.await("PersonalR")
            assertFalse(ui.has("Calendar access")); assertFalse(ui.has("Contacts access"))
        }
    }

    @Test fun noDavShowsOnlySetupInPlaceOfCalendarAndContactsRows() = fixture(withAccounts = false) {
        val activity = open()
        withFixtureCleanup({ close(activity) }) {
            ui.await("DAVx5"); ui.await("Set up")
            for (label in listOf("Shown", "New events", "Look ahead", "Contacts go to", "Phone contacts")) assertFalse(ui.has(label))
            ui.await("Last sync"); ui.await("Sync now")
            ui.capture(activity, "no-davx5")
            val handoff = java.util.concurrent.atomic.AtomicReference<Intent>()
            val monitor = object : android.app.Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
                    if (intent.action != Intent.ACTION_VIEW) return null
                    handoff.set(intent)
                    return android.app.Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
            instrumentation.addMonitor(monitor)
            withFixtureCleanup({ instrumentation.removeMonitor(monitor) }) {
                ui.click("DAVx5")
                assertEquals("https://f-droid.org/packages/at.bitfire.davdroid/", handoff.get()?.dataString)
            }
        }
    }

    @Test fun enlargedTextKeepsChoicesAndReturnsToTheOwningPageWithoutChangingThem() = fixture { f ->
        PreferenceSyncConfig(context).write(SyncSettings(setOf(f.familyCalendar, f.personalCalendar), f.personalCalendar))
        val activity = open()
        lateinit var owner: SyncSettingsOwner
        withFixtureCleanup({ close(activity) }) {
            main {
                owner = SyncSettingsOwner(context)
                owner.refresh()
                activity.setContent {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    CompositionLocalProvider(LocalPalette provides PaperPalette.Light(activity),
                        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.5f)) {
                        SyncSettingsBook(owner, activity::finish, {}, {})
                    }
                }
            }
            ui.await("PersonalR")
            val heading = Rect().also { ui.find("Shown")!!.getBoundsInScreen(it) }
            val target = Rect().also { ui.find("Shown PersonalR")!!.getBoundsInScreen(it) }
            val box = ui.drawings("paper_index_checkbox").single { it.center.y in target.top.toFloat()..target.bottom.toFloat() }
            val density = context.resources.displayMetrics.density
            val layout = ui.textLayout("Shown")
            assertEquals("Full Shown label stays on one line at 150%", 1, layout.lineCount)
            assertFalse("Shown must not clip", layout.didOverflowWidth)
            assertFalse("Shown must not ellipsize", layout.isLineEllipsized(0))
            assertEquals("Shown and its checkbox share the measured column", heading.exactCenterX(), box.center.x, 1f)
            assertTrue(heading.width() >= 64f * density - 1)
            seek("Look ahead"); ui.click("Look ahead"); ui.awaitPage("Look ahead")
            ui.await("1"); ui.back()
            ui.awaitPage("Kotatsu Sync"); ui.await("Look ahead")
            assertEquals(8, config().horizonWeeks)
            ui.capture(activity, "enlarged-text")
        }
    }

    @Test fun absentPersonalBookUsesFirstAvailableAndNewEventsStayWithinShownCalendars() = fixture { f ->
        resolver.delete(Settings.CONTENT_URI, "account_name = ? AND account_type = ?", arrayOf(personal.name, personal.type))
        val activity = open()
        withFixtureCleanup({ close(activity) }) {
            ui.await("PersonalR")
            awaitSyncSettings("First address book is the fallback default") { PreferenceContactsConfig(context).read().target == family }
            ui.click("Shown FamilyR")
            awaitSyncSettings("First shown calendar is the fallback default") { config().newEventCalendar == f.familyCalendar }
            ui.click("Shown PersonalR")
            awaitSyncSettings("Personal checkbox renders") { ui.selected("Shown PersonalR") }
            ui.click("Shown FamilyR")
            awaitSyncSettings("Unshown calendar cannot remain the new-event destination") {
                config().calendars == setOf(f.personalCalendar) && config().newEventCalendar == f.personalCalendar
            }
            ui.click("Shown PersonalR")
            awaitSyncSettings("No shown calendars means no destination") { config().calendars.isEmpty() && config().newEventCalendar == null }
            awaitSyncSettings("New events disappears with the last shown calendar") { !ui.has("New events") }
        }
    }

    private fun fixture(withAccounts: Boolean = true, body: (Fixture) -> Unit) {
        val f = Fixture()
        withFixtureCleanup({ f.cleanup() }) {
            f.setup(withAccounts)
            try { body(f) } catch (error: Throwable) {
                val directory = java.io.File(context.getExternalFilesDir(null), "failure-dumps/sync-settings-${android.os.SystemClock.uptimeMillis()}")
                    .apply { mkdirs() }
                try {
                    ui.dumpFailure(directory)
                    java.io.File(directory, "providers.txt").writeText("${error}\nphone=${f.raw(null)}\npersonal=${f.raw(personal)}\nfamily=${f.raw(family)}\n" +
                        "settings=${config()}\ncontacts=${PreferenceContactsConfig(context).read()}\nstatus=${status()}\njobs=${scheduler.allPendingJobs.map { it.id }}\n")
                } catch (diagnostic: Throwable) { error.addSuppressed(diagnostic) }
                throw error
            }
        }
    }
    private inner class Fixture {
        val accounts = mutableListOf<Account>()
        val calendars = mutableListOf<Long>()
        val contacts = mutableListOf<Long>()
        var personalCalendar = -1L
        var familyCalendar = -1L
        val previousCalendar = PreferenceSyncConfig(context).read()
        val previousContacts = PreferenceContactsConfig(context).read()
        fun setup(withAccounts: Boolean) {
            quiesceJobs()
            PERMISSIONS.forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
            PreferenceSyncConfig(context).write(SyncSettings(emptySet(), null))
            PreferenceContactsConfig(context).write(ContactsSettings())
            clearStore()
            MuditaCalendar(resolver).all().forEach { check(MuditaCalendar(resolver).delete(it.id)) }
            if (withAccounts) {
                for (book in listOf(personal, family)) {
                    val account = Account(book.name, book.type)
                    check(AccountManager.get(context).addAccountExplicitly(account, null, null)); accounts += account
                    resolver.insert(Settings.CONTENT_URI, ContentValues().apply {
                        put(Settings.ACCOUNT_NAME, book.name); put(Settings.ACCOUNT_TYPE, book.type)
                        put(Settings.SHOULD_SYNC, 1); put(Settings.UNGROUPED_VISIBLE, 1)
                    })!!
                }
                familyCalendar = calendar("FamilyR", type)
                personalCalendar = calendar("PersonalR", type)
                calendar("PC Sync", "LOCAL")
            }
        }
        fun calendar(name: String, accountType: String): Long {
            val uri = syncUri(Calendars.CONTENT_URI, accountType)
            val id = ContentUris.parseId(resolver.insert(uri, ContentValues().apply {
                put(Calendars.ACCOUNT_NAME, "Personal"); put(Calendars.ACCOUNT_TYPE, accountType)
                put(Calendars.NAME, name); put(Calendars.CALENDAR_DISPLAY_NAME, name); put(Calendars.OWNER_ACCOUNT, "Personal")
                put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER); put(Calendars.VISIBLE, 1)
                put(Calendars.SYNC_EVENTS, 1); put(Calendars.CALENDAR_TIME_ZONE, "UTC")
            })!!)
            calendars += id
            return id
        }
        fun event() = resolver.insert(syncUri(Events.CONTENT_URI), ContentValues().apply {
            put(Events.CALENDAR_ID, personalCalendar); put(Events.TITLE, "Settings fixture event")
            put(Events.DTSTART, System.currentTimeMillis() + 3600000); put(Events.DTEND, System.currentTimeMillis() + 7200000)
            put(Events.EVENT_TIMEZONE, "UTC")
        })!!
        fun phoneContact(name: String): Long {
            val id = ContentUris.parseId(resolver.insert(RawContacts.CONTENT_URI, ContentValues())!!)
            contacts += id
            resolver.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, id)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            })!!
            return id
        }
        fun raw(book: ContactAccount?): List<Long> = resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID),
            "${RawContacts.DELETED} = 0 AND " + if (book == null) "account_name IS NULL AND account_type IS NULL" else "account_name = ? AND account_type = ?",
            book?.let { arrayOf(it.name, it.type) }, RawContacts._ID)!!.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        fun cleanup() {
            quiesceJobs()
            for (id in contacts + raw(personal) + raw(family)) resolver.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id), null, null)
            for (id in calendars) resolver.delete(syncUri(ContentUris.withAppendedId(Calendars.CONTENT_URI, id)), null, null)
            for (account in accounts) {
                resolver.delete(Settings.CONTENT_URI, "account_name = ? AND account_type = ?", arrayOf(account.name, account.type))
                check(AccountManager.get(context).removeAccountExplicitly(account))
            }
            MuditaCalendar(resolver).all().forEach { check(MuditaCalendar(resolver).delete(it.id)) }
            clearStore()
            PreferenceSyncConfig(context).write(previousCalendar); PreferenceContactsConfig(context).write(previousContacts)
        }
        fun clearStore() { BridgeStore(context).use { store ->
            listOf("pairs", "state", "write_reservation", "write_intent").forEach { store.writableDatabase.delete(it, null, null) }
        } }
        fun syncUri(uri: Uri, accountType: String = type): Uri = uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, "Personal").appendQueryParameter(Calendars.ACCOUNT_TYPE, accountType).build()
    }
    companion object {
        private val PERMISSIONS = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    }
}
