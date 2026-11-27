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
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.ui.LocalPalette
import com.gezimos.katapult.ui.PaperPalette
import com.gezimos.katapult.withFixtureCleanup
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.UUID

/** Public settings actions observed at the real providers and durable preferences. */
class SyncSettingsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val captureScreenshots = InstrumentationRegistry.getArguments().getString("captureSyncScreenshots") == "true"
    private val ui = SyncSettingsUi(instrumentation, captureScreenshots)
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val type = "com.alexindigo.kotatsu.sync.test"
    private val suffix = UUID.randomUUID().toString().take(8)
    private fun fixtureName(name: String) = if (captureScreenshots) name else "$name-$suffix"
    private val personal = ContactAccount(fixtureName("Personal"), type)
    private val family = ContactAccount(fixtureName("Family"), type)
    private val personalCalendarName = fixtureName("PersonalR")
    private val familyCalendarName = fixtureName("FamilyR")
    private val localCalendarName = fixtureName("PC Sync")
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
    private fun effectiveCalendar() = config().destination(StandardCalendar(resolver).calendars())?.id
    private fun status() = BridgeStore(context).use { it.syncStatus }
    private fun open() = instrumentation.startActivitySync(Intent(context, SyncSettingsActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SyncSettingsActivity
    private val hostOwners = mutableMapOf<Activity, SyncSettingsOwner>()
    private fun openHost(bookNames: Set<String>? = null, setUp: (() -> Unit)? = {}): ComponentActivity {
        val activity = instrumentation.startActivitySync(Intent(context, SyncSettingsHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        main {
            val owner = SyncSettingsOwner(ownerTestContext(context, bookNames))
            hostOwners[activity] = owner
            val palette = activity.applySyncWindow()
            activity.setContent { activity.SyncWindowContent(palette) {
                SyncSettingsBook(owner, activity::finish, {}, setUp)
            } }
            owner.resume()
        }
        return activity
    }
    private fun close(activity: Activity) = main { hostOwners.remove(activity)?.pause(); activity.finish() }
    private fun seek(label: String) {
        val previous = context.getString(com.gezimos.katapult.paper.R.string.paper_previous_page)
        val next = context.getString(com.gezimos.katapult.paper.R.string.paper_next_page)
        var forward = false
        awaitSyncSettings("Find $label across the committed book pages") {
            if (ui.has(label)) true else {
                // Catalog/status publication can allocate the first pager
                // after this traversal has already started.
                if (!forward && !ui.has(previous)) forward = true
                if (forward && !ui.has(next) && ui.has(previous)) forward = false
                val arrow = if (forward) next else previous
                if (ui.has(arrow)) ui.click(arrow)
                false
            }
        }
    }
    private fun openCalendars() {
        seek("Choose which calendars appear in Mudita Calendar")
        ui.click("Choose which calendars appear in Mudita Calendar")
        ui.awaitPage("Calendars")
    }

    @Test fun collectionLossReviewRequiresBothDeleteActionsAndConsumesOnlyTheHeldOccurrences() = fixture(ownerOnly = true) { f ->
        val at = System.currentTimeMillis()
        val phone = MuditaCalendar(resolver)
        val standard = StandardCalendar(resolver)
        val series = ContentUris.parseId(resolver.insert(f.syncUri(Events.CONTENT_URI), ContentValues().apply {
            put(Events.CALENDAR_ID, f.familyCalendar); put(Events._SYNC_ID, "disposable-daily-series")
            put(Events.TITLE, "Fixture daily series"); put(Events.EVENT_TIMEZONE, "UTC")
            put(Events.DTSTART, at + 3600000); put(Events.RRULE, "FREQ=DAILY;COUNT=56"); put(Events.DURATION, "PT1H")
        })!!)
        PreferenceSyncConfig(context).write(SyncSettings(setOf(f.familyCalendar, f.personalCalendar)))
        fun pass() = BridgeStore(context).use { store ->
            BridgeEngine(phone, standard, store, PreferenceSyncConfig(context)).pass(at).also {
                store.syncStatus = SyncStatus(1763856000000L, it.changes)
            }
        }
        fun occurrences() = standard.instances(setOf(f.familyCalendar), at, at + 8 * BridgeEngine.WEEK)
        pass(); val before = occurrences(); assertEquals(56, before.size)
        fun wipe() {
            assertEquals(56, resolver.delete(MuditaCalendar.URI, "id=${phone.all().last().id}", null))
            assertEquals(56, pass().held.single().count)
            assertEquals(before, occurrences())
        }
        wipe()
        val activity = openHost()
        withFixtureCleanup({ close(activity) }) {
            val owner = hostOwners.getValue(activity)
            seek("Calendar changes"); ui.capture(activity, "hold-phone-landing")
            ui.click("Calendar changes"); ui.awaitPage("Calendar changes")
            ui.capture(activity, "hold-phone-review")
            ui.click("Restore them to Mudita Calendar")
            awaitSyncSettings("Restore reaches Mudita") { phone.all().size == 56 && status().held.isEmpty() }
            assertEquals(before, occurrences())
            wipe(); main { owner.refresh() }; seek("Calendar changes")
            ui.click("Calendar changes"); ui.click("Delete them from the server too"); ui.awaitPage("Delete from server")
            ui.await(context.resources.getQuantityString(R.plurals.delete_server_intro, 56, 56, familyCalendarName))
            ui.capture(activity, "delete-from-server")
            assertEquals(before, occurrences())
            ui.click("Cancel"); ui.awaitPage("Calendar changes")
            assertEquals(before, occurrences())
            ui.click("Delete them from the server too"); ui.awaitPage("Delete from server"); ui.click("Delete")
            awaitSyncSettings("The second confirmation cancels the 56 dates") { occurrences().isEmpty() && status().held.isEmpty() }
            assertNotNull(standard.read(EventKey(series)))
            val dates = resolver.query(Events.CONTENT_URI, arrayOf(Events.ORIGINAL_INSTANCE_TIME),
                "${Events.ORIGINAL_ID} = ? AND ${Events.STATUS} = ?", arrayOf(series.toString(), Events.STATUS_CANCELED.toString()), null)!!.use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0)) }
            }
            assertEquals(before.mapNotNull { it.instanceStart }.sorted(), dates.sorted())
        }
    }

    @Test fun selectedCalendarLossOffersAnExactKeepDecisionAndUnavailableDestination() = fixture(ownerOnly = true) { f ->
        val at = System.currentTimeMillis()
        val phone = MuditaCalendar(resolver)
        PreferenceSyncConfig(context).write(SyncSettings(setOf(f.personalCalendar, f.familyCalendar), f.personalCalendar))
        repeat(3) { f.event() }
        fun pass() = BridgeStore(context).use { store ->
            BridgeEngine(phone, StandardCalendar(resolver), store, PreferenceSyncConfig(context)).pass(at).also {
                store.syncStatus = SyncStatus(1763856000000L, it.changes)
            }
        }
        pass(); assertEquals(3, phone.all().size)
        assertEquals(1, resolver.delete(f.syncUri(ContentUris.withAppendedId(Calendars.CONTENT_URI, f.personalCalendar)), null, null))
        assertEquals(3, pass().held.single().count)
        val activity = openHost()
        withFixtureCleanup({ close(activity) }) {
            seek("New events"); ui.await("Unavailable"); ui.capture(activity, "calendar-unavailable")
            seek("Calendar changes"); ui.capture(activity, "hold-standard-landing")
            ui.click("Calendar changes"); ui.awaitPage("Calendar changes"); ui.capture(activity, "hold-standard-review")
            ui.click("Keep them in Mudita Calendar")
            awaitSyncSettings("Keep retires mappings, not events") { status().held.isEmpty() }
            pass(); assertEquals(3, phone.all().size)
            assertTrue(StandardCalendar(resolver).events(setOf(f.familyCalendar)).isEmpty())
        }
    }

    @Test fun launcherArmsJobsAndEveryCalendarAndPickerPersists() = fixture { f ->
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertEquals(SyncSettingsActivity::class.java.name, launch!!.component!!.className)
        val activity = open()
        withFixtureCleanup({ close(activity) }) {
            seek("Calendar access")
            ui.capture(activity, "access-granted")
            assertTrue(config().calendars.isEmpty()); assertNull(config().newEventCalendar)
            assertFalse("New events is absent until a calendar is shown", ui.has("New events"))
            awaitSyncSettings("Opening arms all recurring jobs") {
                listOf(SyncRuntime.WATCH_JOB, SyncRuntime.CONTACTS_JOB, SyncRuntime.PERIODIC_JOB, SyncRuntime.HORIZON_JOB)
                    .all { scheduler.getPendingJob(it) != null }
            }
            openCalendars()
            ui.await(familyCalendarName); ui.await(personalCalendarName)
            assertFalse(ui.selected(personalCalendarName))
            ui.capture(activity, "calendars-unchecked")
            ui.click(personalCalendarName)
            awaitSyncSettings("Checkbox writes selection and Personal default") {
                config().calendars == setOf(f.personalCalendar) && effectiveCalendar() == f.personalCalendar
            }
            ui.click(familyCalendarName)
            awaitSyncSettings("Both shown calendars persist and render checked") {
                config().calendars == setOf(f.personalCalendar, f.familyCalendar) &&
                    ui.selected(personalCalendarName) && ui.selected(familyCalendarName)
            }
            val control = Rect().also { ui.find(personalCalendarName, actionable = true)!!.getBoundsInScreen(it) }
            val box = ui.drawings("paper_index_checkbox").single { it.center.y in control.top.toFloat()..control.bottom.toFloat() }
            assertEquals("Shared selection-row checkbox reaches the trailing edge", control.right.toFloat(), box.right, 1f)
            assertEquals("Check shares the row center", control.exactCenterY(), box.center.y, 1f)
            val leader = ui.drawings("paper_index_leader").single { it.center.y in control.top.toFloat()..control.bottom.toFloat() }
            assertTrue("The leader ends before the checkbox", leader.right < box.left)
            assertFalse("Local calendars are never offered", ui.has(localCalendarName))
            ui.capture(activity, "calendars-shown")
            ui.back(); ui.awaitPage("Kotatsu Sync")
            seek("New events"); ui.click("New events"); ui.awaitPage("New events")
            ui.await(personalCalendarName); ui.await(familyCalendarName); ui.capture(activity, "new-events")
            ui.back(); ui.awaitPage("Kotatsu Sync"); assertEquals(f.personalCalendar, effectiveCalendar())
            ui.click("New events"); ui.awaitPage("New events"); ui.click(familyCalendarName)
            awaitSyncSettings("New events picker persists") { config().newEventCalendar == f.familyCalendar }
            seek("Look ahead"); ui.await("8 weeks")
            ui.capture(activity, "look-ahead-landing")
            ui.click("Look ahead"); ui.awaitPage("Look ahead"); ui.await("12 weeks")
            ui.capture(activity, "look-ahead-sheet")
            ui.click("12 weeks")
            awaitSyncSettings("Horizon persists") { config().horizonWeeks == 12 }
            seek("Contacts go to"); ui.await(personal.name)
            assertEquals(personal, PreferenceContactsConfig(context).read().target)
            ui.click("Contacts go to"); ui.awaitPage("Contacts go to")
            ui.await(family.name); ui.await(personal.name); ui.capture(activity, "contacts-go-to")
            ui.click(family.name)
            awaitSyncSettings("Contacts picker persists") { PreferenceContactsConfig(context).read().target == family }
            seek("Phone contacts"); ui.await("Move phone contacts to ${family.name}")
        }
        val reopened = open()
        withFixtureCleanup({ close(reopened) }) {
            openCalendars(); assertTrue(ui.selected(personalCalendarName)); ui.back()
            seek("Look ahead"); ui.await("12 weeks")
            seek("Contacts go to"); ui.await(family.name)
            assertEquals(f.familyCalendar, config().newEventCalendar)
        }
    }

    @Test fun confirmedMoveUsesTheDisplayedBookAndOnlyTheDisplayedContacts() = fixture { f ->
        val activity = open()
        withFixtureCleanup({ close(activity) }) {
            seek("Phone contacts")
            quiesceJobs()
            val baseline = phoneSourceIds(context)
            val source = f.phoneContact("Phone fixture")
            val question = context.resources.getQuantityString(R.plurals.move_confirmation, baseline.size + 1, baseline.size + 1, personal.name)
            fun openConfirmation() {
                ui.click("Phone contacts"); ui.awaitPage("Move phone contacts")
                ui.await(question) // The heading can render before the lane supplies the snapshot.
            }
            openConfirmation()
            ui.capture(activity, "move-confirmation")
            ui.click("Cancel")
            assertEquals(baseline + source, f.raw(null).toSet()); assertTrue(f.raw(personal).isEmpty())
            openConfirmation()
            val later = f.phoneContact("Later phone fixture")
            ui.click("Move")
            // The click queues the move on the lane. The provider commits before Last sync is written.
            drain()
            assertEquals(1, f.raw(personal).size)
            assertTrue(f.raw(family).isEmpty())
            assertEquals(setOf(later), f.raw(null).toSet())
            assertEquals(1, status().changes)
            seek("Last sync"); ui.await("1 change")
            ui.capture(activity, "move-complete")
            SyncRuntime.enqueue { SyncRuntime.pass(context) }.get(15, TimeUnit.SECONDS)
            assertTrue(f.raw(null).isEmpty()); assertEquals(2, f.raw(personal).size)
        }
    }

    @Test fun threePhoneContactsWaitForConfirmationAndAreNotMovedOnCancel() = fixture(ownerOnly = true) { f ->
        val baseline = phoneSourceIds(context)
        val created = (1..3).map { f.phoneContact("Waiting fixture $it") }.toSet()
        val scoped = object : ContactsPort by AndroidContacts(context) {
            override fun sources() = AndroidContacts(context).sources().filter { it.id in created }
        }
        BridgeStore(context).use { store ->
            val result = ContactsTransferEngine(scoped, store).pass(ContactsSettings(personal))
            assertEquals(3, result.waiting)
            store.syncStatus = SyncStatus(1763856000000L, contactsWaiting = checkNotNull(result.waiting))
        }
        val activity = openHost()
        withFixtureCleanup({ close(activity) }) {
            seek("Phone contacts"); ui.await("3 contacts are waiting to move")
            ui.capture(activity, "contacts-bulk-wait")
            ui.click("Phone contacts"); ui.awaitPage("Move phone contacts")
            ui.await(context.resources.getQuantityString(R.plurals.move_confirmation, baseline.size + 3, baseline.size + 3, personal.name))
            ui.click("Cancel")
            assertEquals(baseline + created, phoneSourceIds(context))
            assertTrue(f.raw(personal).isEmpty())
        }
    }

    @Test fun lookAheadShowsAllFourChoicesAndSavesSelectionsOnTheOwningPage() = fixture(ownerOnly = true) {
        val activity = openHost()
        withFixtureCleanup({ close(activity) }) {
            seek("Look ahead")
            ui.await("8 weeks")
            val choices = listOf(2, 4, 8, 12)
            var selected = 8
            for (count in choices) {
                ui.click("Look ahead"); ui.awaitPage("Look ahead")
                assertEquals(1, ui.drawings("paper_sheet_surface").size)
                assertFalse("The page under the sheet is excluded from accessibility", ui.has("Kotatsu Sync"))
                ui.await("How far ahead events appear in Mudita Calendar")
                val tops = choices.map { weeks ->
                    val label = "$weeks weeks"
                    ui.await(label)
                    assertEquals(weeks == selected, ui.selected(label))
                    val node = checkNotNull(ui.checkable(label))
                    Rect().also(node::getBoundsInScreen).top
                }
                assertTrue("Choices appear in ascending order", tops.zipWithNext().all { (a, b) -> a < b })
                assertEquals(4, ui.drawings("paper_index_checkbox").size)
                ui.click("$count weeks")
                ui.awaitPage("Kotatsu Sync")
                assertTrue("Selection keeps the Calendars group visible without paging", ui.has("Look ahead"))
                awaitSyncSettings("Look ahead persists and renders $count weeks") {
                    config().horizonWeeks == count && ui.has("$count weeks")
                }
                selected = count
            }
            ui.click("Look ahead"); ui.awaitPage("Look ahead")
            ui.click(context.getString(com.gezimos.katapult.paper.R.string.paper_dismiss))
            ui.awaitPage("Kotatsu Sync"); ui.await("12 weeks")
            assertEquals(12, config().horizonWeeks)
        }
    }

    @Test fun legacyHorizonsRoundUpOnReadWithoutRewritingThePreference() = fixture(ownerOnly = true) {
        val prefs = context.getSharedPreferences("calendar-sync", Context.MODE_PRIVATE)
        for ((saved, expected) in listOf(1 to 2, 3 to 4, 5 to 8, 9 to 12, 26 to 12)) {
            assertTrue(prefs.edit().putInt("horizon_weeks", saved).commit())
            assertEquals(expected, config().horizonWeeks)
            assertEquals(saved, prefs.getInt("horizon_weeks", -1))
        }
        assertTrue(prefs.edit().putInt("horizon_weeks", 5).commit())
        val activity = openHost()
        withFixtureCleanup({ close(activity) }) {
            awaitSyncSettings("The settings catalog loads before opening the sheet") { hostOwners[activity]?.snapshot?.loaded == true }
            seek("Look ahead"); ui.await("8 weeks")
            ui.click("Look ahead"); ui.awaitPage("Look ahead")
            assertTrue(ui.selected("8 weeks"))
            ui.back(); ui.awaitPage("Kotatsu Sync"); ui.await("8 weeks")
            assertEquals(5, prefs.getInt("horizon_weeks", -1))
        }
    }

    @Test fun countedLabelsRenderSingularAndPluralFromTheirRealProviders() = fixture(ownerOnly = true) { f ->
        val activity = openHost()
        withFixtureCleanup({ close(activity) }) {
            quiesceJobs()
            val completedAt = System.currentTimeMillis()
            val time = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(completedAt))
            for ((count, label) in listOf(0 to "0 changes", 1 to "1 change", 2 to "2 changes")) {
                BridgeStore(context).use { it.syncStatus = SyncStatus(completedAt, count) }
                seek("Last sync"); ui.await(time); ui.await(label)
            }
            val baseline = phoneSourceIds(context)
            fun moveCopy(count: Int) = when (count) {
                1 -> "1 contact moves to ${personal.name}. It stays on this phone and syncs to your server."
                else -> "$count contacts move to ${personal.name}. They stay on this phone and sync to your server."
            }
            for (count in 1..2) assertEquals(moveCopy(count),
                context.resources.getQuantityString(R.plurals.move_confirmation, count, count, personal.name))
            for (count in 1..2) {
                f.phoneContact("Count fixture $count")
                seek("Phone contacts"); ui.click("Phone contacts"); ui.awaitPage("Move phone contacts")
                ui.await(moveCopy(baseline.size + count))
                ui.click("Cancel")
                assertEquals("Confirmation does not move contacts", baseline.size + count, f.raw(null).size)
            }
        }
    }

    @Test fun syncNowUpdatesLastSyncAfterTheConsumerChangesAndShowsStops() = fixture { f ->
        val activity = open()
        withFixtureCleanup({ close(activity) }) {
            openCalendars(); ui.click(personalCalendarName); ui.back()
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
                ui.has(context.resources.getQuantityString(R.plurals.sync_status, status.changes, status.changes))
            }
            ui.capture(activity, "sync-complete")
            quiesceJobs()
            BridgeStore(context).use { it.syncStatus = SyncStatus(System.currentTimeMillis(), 0, setOf(SyncStopReason.ADDRESS_BOOK_REQUIRED)) }
            ui.await("Stopped: choose an address book")
            ui.capture(activity, "stopped")
        }
    }

    @Test fun missingAccessRowsForwardTheExactRequestsAndKeepLiveGrantedState() = fixture(ownerOnly = true) {
        val activity = openHost()
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
            ui.await("Calendar access"); ui.await("Contacts access"); ui.await("Not granted")
            awaitSyncSettings("Permission snapshot loaded") { owner.snapshot.loaded }
            ui.click("Calendar access"); ui.click("Contacts access")
            assertEquals(1, calendarRequests); assertEquals(1, contactsRequests)
            ui.capture(activity, "access-missing")
            main {
                val granted = SyncSettingsOwner(ownerTestContext(context))
                granted.refresh()
                activity.setContent { CompositionLocalProvider(LocalPalette provides PaperPalette.Light(activity)) {
                    SyncSettingsBook(granted, activity::finish, {}, {})
                } }
            }
            ui.await("Granted")
            ui.await("Calendar access"); ui.await("Contacts access")
            assertFalse(ui.has("Not granted"))
        }
    }

    @Test fun missingAccessPreservesTheLastRealPassOrHidesTheRow() = fixture(ownerOnly = true) {
        val activity = openHost()
        lateinit var owner: SyncSettingsOwner
        withFixtureCleanup({ close(activity) }) {
            for (missing in listOf(PERMISSIONS.take(2), PERMISSIONS.drop(2), PERMISSIONS)) {
                val denied = object : ContextWrapper(ownerTestContext(context)) {
                    override fun getApplicationContext(): Context = this
                    override fun checkSelfPermission(permission: String) = if (permission in missing) PackageManager.PERMISSION_DENIED
                        else super.checkSelfPermission(permission)
                }
                BridgeStore(context).use { it.syncStatus = SyncStatus() }
                main {
                    owner = SyncSettingsOwner(denied)
                    activity.setContent { CompositionLocalProvider(LocalPalette provides PaperPalette.Light(activity)) {
                        SyncSettingsBook(owner, activity::finish, {}, {})
                    } }
                    owner.refresh()
                }
                awaitSyncSettings("Denied owner loaded") { owner.snapshot.loaded }
                seek("Sync now")
                awaitSyncSettings("No completed pass means no Last sync row: $missing") {
                    owner.snapshot.status.lastPassAt == null && !ui.has("Last sync")
                }
                if (missing == PERMISSIONS) ui.capture(activity, "status-access-missing-no-result")
                for (real in listOf(SyncStatus(System.currentTimeMillis(), 2),
                    SyncStatus(System.currentTimeMillis(), 0, setOf(SyncStopReason.REVIEW_REQUIRED)))) {
                    BridgeStore(context).use { it.syncStatus = real }
                    SyncRuntime.enqueue { SyncRuntime.pass(denied) }.get(15, TimeUnit.SECONDS)
                    assertEquals("Permission-only attempts cannot replace the last real pass", real, status())
                    SyncRuntime.schedulingFailed(context, IllegalStateException("fixture scheduling failure"))
                    main { owner.refresh() }
                    seek("Last sync")
                    if (real.stopReasons.isEmpty()) {
                        ui.await("2 changes")
                        if (missing == PERMISSIONS) ui.capture(activity, "status-access-missing-with-result")
                    } else ui.await(context.getString(R.string.stopped_review))
                    ui.await(context.getString(R.string.stopped_scheduling))
                }
            }
        }
    }

    @Test fun everyStatusIsFullyReadableAtNormalAndEnlargedText() = fixture(withAccounts = false, ownerOnly = true) {
        val activity = openHost(bookNames = emptySet())
        withFixtureCleanup({ close(activity) }) {
            for (scale in listOf(1f, 1.5f)) {
                val owner = SyncSettingsOwner(ownerTestContext(context, emptySet()))
                main {
                    activity.setContent {
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        CompositionLocalProvider(LocalPalette provides PaperPalette.Light(activity),
                            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, scale)) {
                            SyncSettingsBook(owner, activity::finish, {}, {})
                        }
                    }
                }
                val states = SyncStopReason.entries.filter { !it.accessRequired }.map { reason ->
                    reason.code to if (reason == SyncStopReason.SCHEDULING_FAILED) SyncStatus(availabilityStop = SyncStopReason.SCHEDULING_FAILED)
                        else SyncStatus(1763856000000, 0, setOf(reason))
                } + listOf("complete" to SyncStatus(1763856000000, 2), "combined" to SyncStatus(1763856000000, 0,
                    setOf(SyncStopReason.MUDITA_CALENDAR_CHANGED, SyncStopReason.ADDRESS_BOOK_REQUIRED)))
                for ((name, status) in states) {
                    BridgeStore(context).use { it.syncStatus = status }
                    main { owner.refresh() }
                    val reasons = status.availabilityStop?.let { setOf(it) } ?: status.stopReasons
                    val text = if (reasons.isEmpty()) context.resources.getQuantityString(R.plurals.sync_status, status.changes, status.changes)
                        else reasons.sortedBy { it.ordinal }.mapNotNull { it.textResource() }.map { context.getString(it) }.joinToString("\n")
                    awaitSyncSettings("Fixture publishes the exact status $name before paging") { owner.snapshot.status == status }
                    seek(text)
                    val layout = ui.textLayout(text)
                    assertReadable(layout, "$name at $scale")
                    if (status.lastPassAt != null) {
                        val label = ui.textLayout("Last sync")
                        assertEquals("Label never wraps", 1, label.lineCount)
                        assertReadable(label, "Last sync at $scale")
                    } else assertFalse(ui.has("Last sync"))
                    if (scale == 1f || name == "mudita_calendar_changed") ui.capture(activity,
                        "status-$name-${if (scale == 1f) "100" else "150"}")
                }
            }
        }
    }

    private fun assertReadable(layout: androidx.compose.ui.text.TextLayoutResult, description: String) {
        assertFalse("Full height: $description", layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) {
            assertFalse("No ellipsis: $description", layout.isLineEllipsized(line))
            // Compare line bounds within one raster pixel: Compose rounds an
            // intrinsic layout to integers and can flag subpixel width overflow.
            assertTrue("Full line fits its measured track: $description",
                layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f)
        }
    }

    @Test fun noDavShowsOnlySetupInPlaceOfCalendarAndContactsRows() = fixture(withAccounts = false, ownerOnly = true) {
        val packages = context.packageManager
        val expected = listOfNotNull(packages.getLaunchIntentForPackage("at.bitfire.davdroid"),
            Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/at.bitfire.davdroid/")))
            .firstOrNull { it.resolveActivity(packages) != null }
        val activity = openHost(bookNames = emptySet(), setUp = expected?.let { intent -> { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
        withFixtureCleanup({ close(activity) }) {
            ui.await("DAVx5"); ui.await(if (expected == null) "Unavailable" else "No calendars or address books available")
            for (label in listOf("Shown", "New events", "Look ahead", "Contacts go to", "Phone contacts")) assertFalse(ui.has(label))
            assertFalse(ui.has("Last sync")); ui.await("Sync now")
            ui.capture(activity, "no-davx5")
            val handoff = java.util.concurrent.atomic.AtomicReference<Intent>()
            val monitor = object : android.app.Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): android.app.Instrumentation.ActivityResult? {
                    handoff.set(intent)
                    return android.app.Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
            instrumentation.addMonitor(monitor)
            withFixtureCleanup({ instrumentation.removeMonitor(monitor) }) {
                if (expected == null) assertNull(ui.find("DAVx5", actionable = true))
                else {
                    ui.click("DAVx5")
                    val actual = checkNotNull(handoff.get())
                    assertTrue("Setup launches the resolved target", expected.filterEquals(actual))
                    if (expected.action == Intent.ACTION_VIEW)
                        assertEquals("https://f-droid.org/packages/at.bitfire.davdroid/", actual.dataString)
                    else assertEquals("at.bitfire.davdroid", actual.component?.packageName)
                }
            }
        }
    }

    @Test fun setupWithoutAnyHandlersIsUnavailableBeforeInteraction() = fixture(withAccounts = false, ownerOnly = true) {
        val packages = context.packageManager
        val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/at.bitfire.davdroid/"))
        val handlers = (packages.queryIntentActivities(fallback, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName } +
            listOfNotNull(packages.getLaunchIntentForPackage("at.bitfire.davdroid")?.component?.packageName)).distinct()
        val before = handlers.associateWith(packages::getApplicationEnabledSetting)
        fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
        withFixtureCleanup(*before.map { (name, state) -> {
            val action = if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) "default-state" else "enable"
            shell("pm $action --user 0 $name")
            assertEquals("Package state restored for $name", state, packages.getApplicationEnabledSetting(name))
        } }.toTypedArray()) {
            for (name in handlers) {
                shell("pm disable-user --user 0 $name")
                assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, packages.getApplicationEnabledSetting(name))
            }
            assertNull("The fixture removes all HTTPS handlers", fallback.resolveActivity(packages))
            assertNull("The fixture removes the DAVx5 launcher", packages.getLaunchIntentForPackage("at.bitfire.davdroid"))
            val activity = openHost(bookNames = emptySet(), setUp = null)
            withFixtureCleanup({ close(activity) }) {
                ui.await("DAVx5"); ui.await("Unavailable")
                assertFalse(ui.has("Set up"))
                assertNull("Unavailable setup must expose no click action", ui.find("DAVx5", actionable = true))
                ui.awaitPage("Kotatsu Sync")
                ui.capture(activity, "setup-unavailable")
            }
        }
    }

    @Test fun enlargedTextKeepsChoicesAndReturnsToTheOwningPageWithoutChangingThem() = fixture(ownerOnly = true) { f ->
        PreferenceSyncConfig(context).write(SyncSettings(setOf(f.familyCalendar, f.personalCalendar), f.personalCalendar))
        val activity = openHost()
        lateinit var owner: SyncSettingsOwner
        withFixtureCleanup({ close(activity) }) {
            main {
                owner = SyncSettingsOwner(ownerTestContext(context))
                owner.refresh()
                activity.setContent {
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    CompositionLocalProvider(LocalPalette provides PaperPalette.Light(activity),
                        androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 1.5f)) {
                        SyncSettingsBook(owner, activity::finish, {}, {})
                    }
                }
            }
            openCalendars()
            ui.await(personalCalendarName)
            val target = Rect().also { ui.find(personalCalendarName, actionable = true)!!.getBoundsInScreen(it) }
            val box = ui.drawings("paper_index_checkbox").single { it.center.y in target.top.toFloat()..target.bottom.toFloat() }
            val layout = ui.textLayout(personalCalendarName)
            assertEquals("Calendar label stays on one line at 150%", 1, layout.lineCount)
            assertEquals("Shared selection-row checkbox reaches the trailing edge", target.right.toFloat(), box.right, 1f)
            ui.capture(activity, "calendars-150")
            ui.back(); ui.awaitPage("Kotatsu Sync")
            seek("Look ahead"); ui.click("Look ahead"); ui.awaitPage("Look ahead")
            ui.await("12 weeks")
            assertEquals(1, ui.drawings("paper_sheet_surface").size)
            assertEquals(1, ui.textLayout("12 weeks").lineCount)
            ui.capture(activity, "look-ahead-150"); ui.back()
            ui.awaitPage("Kotatsu Sync"); ui.await("Look ahead")
            assertEquals(8, config().horizonWeeks)
            ui.capture(activity, "enlarged-text")
        }
    }

    @Test fun absentPersonalBookUsesFirstAvailableAndNewEventsStayWithinShownCalendars() = fixture(ownerOnly = true) { f ->
        resolver.delete(Settings.CONTENT_URI, "account_name = ? AND account_type = ?", arrayOf(personal.name, personal.type))
        PreferenceContactsConfig(context).write(ContactsSettings())
        val activity = openHost()
        withFixtureCleanup({ close(activity) }) {
            seek("Contacts go to")
            val available = AndroidContacts(context).addressBooks().sortedWith(compareBy<ContactAccount> { it.name.lowercase() }
                .thenBy { it.type }.thenBy { it.dataSet.orEmpty() })
            val expected = available.firstOrNull { it.name.contains("Personal", ignoreCase = true) } ?: available.first()
            awaitSyncSettings("First available address book is the fallback default") { hostOwners[activity]?.snapshot?.contactTarget?.account == expected }
            openCalendars(); ui.click(familyCalendarName)
            awaitSyncSettings("First shown calendar is the fallback default") { effectiveCalendar() == f.familyCalendar }
            ui.click(personalCalendarName)
            awaitSyncSettings("Personal checkbox renders") { ui.selected(personalCalendarName) }
            awaitSyncSettings("Personal becomes the automatic choice while Family is still shown") {
                config().calendars == setOf(f.personalCalendar, f.familyCalendar) && effectiveCalendar() == f.personalCalendar
            }
            ui.click(familyCalendarName)
            awaitSyncSettings("Unshown calendar cannot remain the new-event destination") {
                config().calendars == setOf(f.personalCalendar) && effectiveCalendar() == f.personalCalendar
            }
            ui.click(personalCalendarName)
            awaitSyncSettings("No shown calendars means no destination") { config().calendars.isEmpty() && config().newEventCalendar == null }
            ui.back(); seek("Look ahead")
            assertFalse("New events disappears with the last shown calendar", ui.has("New events"))
        }
    }

    @Test fun queuedCalendarWritesValidateAgainstTheLatestCommittedShownSet() = fixture(ownerOnly = true) { f ->
        PreferenceSyncConfig(context).write(SyncSettings(setOf(f.familyCalendar, f.personalCalendar), f.personalCalendar))
        val owner = settingsOwner()
        queuedWrites {
            owner.toggleCalendar(f.personalCalendar)
            owner.newEvents(f.personalCalendar)
            owner.weeks(12)
            assertFalse("Pending toggle is visible immediately", f.personalCalendar in owner.snapshot.settings.calendars)
            assertTrue("Persistence has not advanced ahead of the blocked lane", f.personalCalendar in config().calendars)
        }
        assertEquals(SyncSettings(setOf(f.familyCalendar), f.personalCalendar, 12), config())
        assertNull("The explicit hidden destination must not fall back", effectiveCalendar())
        assertEquals(config(), owner.snapshot.settings)
        quiesceJobs()
        queuedWrites {
            owner.toggleCalendar(f.personalCalendar)
            owner.newEvents(f.personalCalendar)
            owner.weeks(4)
            assertTrue("The second tap is computed against the latest intent state", f.personalCalendar in owner.snapshot.settings.calendars)
        }
        assertEquals(setOf(f.familyCalendar, f.personalCalendar), config().calendars)
        assertEquals(f.personalCalendar, config().targetIdentity?.id)
        assertEquals(4, config().horizonWeeks)
        quiesceJobs()
        queuedWrites {
            repeat(3) { owner.toggleCalendar(f.familyCalendar) }
            owner.toggleCalendar(f.personalCalendar)
            owner.newEvents(f.familyCalendar) // Already unselected in the pending projection.
            assertTrue(owner.snapshot.settings.calendars.isEmpty())
            assertEquals(setOf(f.familyCalendar, f.personalCalendar), config().calendars)
        }
        assertTrue(config().calendars.isEmpty())
        assertEquals(f.personalCalendar, config().newEventCalendar)
        assertEquals(config(), owner.snapshot.settings)
    }

    @Test fun queuedCalendarFallbackUsesFreshNamesWithoutDiscardingUnavailableSavedChoices() = fixture(ownerOnly = true) { f ->
        val other = f.calendar("AardvarkR-$suffix", type)
        PreferenceSyncConfig(context).write(SyncSettings(setOf(other, f.familyCalendar, f.personalCalendar)))
        val owner = settingsOwner()
        queuedWrites {
            SyncRuntime.enqueue {
                resolver.update(f.syncUri(ContentUris.withAppendedId(Calendars.CONTENT_URI, f.familyCalendar)),
                    ContentValues().apply { put(Calendars.CALENDAR_DISPLAY_NAME, "Personal family-$suffix") }, null, null)
            }
            owner.toggleCalendar(f.personalCalendar)
            owner.weeks(12)
            assertEquals(familyCalendarName, owner.snapshot.calendars.single { it.id == f.familyCalendar }.label)
        }
        assertEquals(SyncSettings(setOf(other, f.familyCalendar), horizonWeeks = 12), config())
        assertEquals(f.familyCalendar, effectiveCalendar())
        quiesceJobs()
        queuedWrites {
            owner.newEvents(f.familyCalendar)
            SyncRuntime.enqueue {
                resolver.update(f.syncUri(ContentUris.withAppendedId(Calendars.CONTENT_URI, f.familyCalendar)),
                    ContentValues().apply { put(Calendars.SYNC_EVENTS, 0) }, null, null)
                resolver.update(f.syncUri(ContentUris.withAppendedId(Calendars.CONTENT_URI, f.personalCalendar)),
                    ContentValues().apply { put(Calendars.SYNC_EVENTS, 0) }, null, null)
            }
            owner.toggleCalendar(f.personalCalendar)
            owner.weeks(2)
        }
        assertEquals("Availability does not undo a saved choice", f.familyCalendar, config().newEventCalendar)
        assertEquals("A stale checkbox cannot add an unavailable calendar", setOf(other, f.familyCalendar), config().calendars)
        assertFalse(owner.snapshot.calendars.any { it.id == f.familyCalendar })
    }

    @Test fun queuedContactsWritesValidateFreshBooksAndConfirmationReadsTheCommittedTarget() = fixture(ownerOnly = true) { f ->
        val owner = settingsOwner()
        queuedWrites {
            SyncRuntime.enqueue {
                resolver.delete(Settings.CONTENT_URI, "account_name = ? AND account_type = ?", arrayOf(family.name, family.type))
            }
            owner.contacts(family)
            assertTrue("The stale UI still offers the removed book", owner.snapshot.books.any { it.account == family })
        }
        assertEquals("Removed book selection must not replace the saved target", personal, PreferenceContactsConfig(context).read().target)
        quiesceJobs()
        queuedWrites {
            SyncRuntime.enqueue {
                resolver.insert(Settings.CONTENT_URI, ContentValues().apply {
                    put(Settings.ACCOUNT_NAME, family.name); put(Settings.ACCOUNT_TYPE, family.type)
                    put(Settings.SHOULD_SYNC, 1); put(Settings.UNGROUPED_VISIBLE, 1)
                })!!
            }
            owner.contacts(family)
            assertFalse("Validation must see the book added ahead of this write", owner.snapshot.books.any { it.account == family })
        }
        assertEquals(family, PreferenceContactsConfig(context).read().target)
        quiesceJobs()
        // Keep the nonempty-baseline regression active even on a clean CI device.
        f.phoneContact("Pre-existing confirmation fixture")
        val baseline = phoneSourceIds(context)
        val contact = f.phoneContact("Queued target fixture")
        queuedWrites {
            owner.contacts(personal)
            owner.prepareMove()
            assertEquals(family, owner.snapshot.contacts.target)
        }
        assertEquals(personal, owner.confirmation!!.destination)
        assertEquals(baseline + contact, owner.confirmation!!.sourceIds)
        main { owner.cancelMove() }
        assertEquals("Preparing and cancelling must preserve every source", baseline + contact, phoneSourceIds(context))
    }

    @Test fun ownerCatalogRetainsBaselineBooksAndCalendarsAlongsideUniqueFixtures() = fixture(ownerOnly = true) { f ->
        PreferenceContactsConfig(context).write(ContactsSettings())
        val owner = settingsOwner()
        assertEquals(f.baselineBooks + personal + family, owner.snapshot.books.map { it.account }.toSet())
        assertEquals(f.baselineCalendars + f.personalCalendar + f.familyCalendar, owner.snapshot.calendars.map { it.id }.toSet())
        if (!captureScreenshots) {
            assertEquals(setOf(personal, family), owner.snapshot.books.map { it.account }.filter { it.name.endsWith(suffix) }.toSet())
        }
        val expected = owner.snapshot.books.firstOrNull { it.label.contains("Personal", ignoreCase = true) } ?: owner.snapshot.books.first()
        assertEquals(expected, owner.snapshot.contactTarget)
        assertEquals(ContactsSettings(), PreferenceContactsConfig(context).read())
    }

    private fun settingsOwner(): SyncSettingsOwner {
        lateinit var owner: SyncSettingsOwner
        main { owner = SyncSettingsOwner(ownerTestContext(context)); owner.refresh() }
        awaitSyncSettings("Owner loaded before blocking the lane") { owner.snapshot.loaded }
        quiesceJobs()
        return owner
    }

    /** Queue real intents behind a latch so UI snapshots cannot race ahead of the assertion. */
    private fun queuedWrites(writes: () -> Unit) {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val blocked = SyncRuntime.enqueue { entered.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
        withFixtureCleanup({ release.countDown(); blocked.get(15, TimeUnit.SECONDS) }) {
            assertTrue("Lane blocker entered", entered.await(15, TimeUnit.SECONDS))
            main(writes)
        }
        drain()
        instrumentation.waitForIdleSync()
    }

    private fun fixture(withAccounts: Boolean = true, ownerOnly: Boolean = false, body: (Fixture) -> Unit) {
        if (captureScreenshots) requireScreenshotDevice()
        val f = Fixture()
        withFixtureCleanup({ f.cleanup() }) {
            f.setup(withAccounts, ownerOnly)
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

    /** The capture fixture uses fixed identities, so require an empty, disposable AVD. */
    private fun requireScreenshotDevice() {
        check(android.os.Build.HARDWARE == "ranchu") { "Sync screenshot fixture is emulator-only" }
        quiesceJobs()
        PERMISSIONS.forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
        requireIsolatedPhoneBook(context)
        check(AndroidContacts(context).addressBooks().isEmpty()) { "Screenshot fixture requires an empty address-book catalog" }
        check(StandardCalendar(resolver).calendars().none { it.eligible }) { "Screenshot fixture requires an empty sync calendar catalog" }
        check(AccountManager.get(context).getAccountsByType(type).isEmpty()) { "Screenshot fixture requires no test accounts" }
        check(MuditaCalendar(resolver).all().isEmpty()) { "Screenshot fixture requires an empty Mudita store" }
    }
    private inner class Fixture {
        val accounts = mutableListOf<Account>()
        val calendars = mutableListOf<Long>()
        val contacts = mutableListOf<Long>()
        var personalCalendar = -1L
        var familyCalendar = -1L
        val previousCalendar = PreferenceSyncConfig(context).read()
        val previousContacts = PreferenceContactsConfig(context).read()
        var baselineBooks = emptySet<ContactAccount>()
        var baselineCalendars = emptySet<Long>()
        var ownsMuditaProvider = false
        fun setup(withAccounts: Boolean, ownerOnly: Boolean) {
            quiesceJobs()
            PERMISSIONS.forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
            // Opening the real activity arms native all-phone jobs. Only owner-only
            // scenarios replace dispatch, so reject unrelated sources before any write.
            if (!ownerOnly) requireIsolatedPhoneBook(context)
            baselineBooks = AndroidContacts(context).addressBooks()
            baselineCalendars = StandardCalendar(resolver).calendars().filter { it.eligible }.map { it.id }.toSet()
            PreferenceSyncConfig(context).write(SyncSettings(emptySet(), null))
            PreferenceContactsConfig(context).write(ContactsSettings(if (withAccounts) personal else null))
            clearStore()
            check(MuditaCalendar(resolver).all().isEmpty()) { "Test Mudita provider must be isolated; existing rows are not fixture-owned" }
            ownsMuditaProvider = true
            if (withAccounts) {
                instrumentation.registerContactBooks(listOf(personal, family), accounts)
                familyCalendar = calendar(familyCalendarName, type)
                personalCalendar = calendar(personalCalendarName, type)
                calendar(localCalendarName, "LOCAL")
            }
        }
        fun calendar(name: String, accountType: String): Long {
            val uri = syncUri(Calendars.CONTENT_URI, accountType)
            val id = ContentUris.parseId(resolver.insert(uri, ContentValues().apply {
                put(Calendars.ACCOUNT_NAME, personal.name); put(Calendars.ACCOUNT_TYPE, accountType)
                put(Calendars.NAME, name); put(Calendars.CALENDAR_DISPLAY_NAME, name); put(Calendars.OWNER_ACCOUNT, personal.name)
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
        fun raw(book: ContactAccount?): List<Long> = if (book == null) phoneSourceIds(context).sorted() else resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID),
            "${RawContacts.DELETED} = 0 AND account_name = ? AND account_type = ?",
            arrayOf(book.name, book.type), RawContacts._ID)!!.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        fun cleanup() {
            quiesceJobs()
            // These accounts were uniquely created by this fixture; record their move-created rows before removing them.
            contacts += accounts.flatMap { raw(ContactAccount(it.name, it.type)) }
            for (id in contacts.distinct()) resolver.delete(ContentUris.withAppendedId(RawContacts.CONTENT_URI, id), null, null)
            for (id in calendars) resolver.delete(syncUri(ContentUris.withAppendedId(Calendars.CONTENT_URI, id)), null, null)
            for (account in accounts) {
                resolver.delete(Settings.CONTENT_URI, "account_name = ? AND account_type = ?", arrayOf(account.name, account.type))
                check(AccountManager.get(context).removeAccountExplicitly(account))
            }
            if (ownsMuditaProvider) {
                val created = MuditaCalendar(resolver).all().map { it.id }
                created.forEach { check(MuditaCalendar(resolver).delete(it)) }
            }
            clearStore()
            PreferenceSyncConfig(context).write(previousCalendar); PreferenceContactsConfig(context).write(previousContacts)
        }
        fun clearStore() { BridgeStore(context).use { store ->
            listOf("pairs", "state", "write_reservation", "write_intent").forEach { store.writableDatabase.delete(it, null, null) }
        } }
        fun syncUri(uri: Uri, accountType: String = type): Uri = uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, personal.name).appendQueryParameter(Calendars.ACCOUNT_TYPE, accountType).build()
    }
    companion object {
        private val PERMISSIONS = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    }
}
