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
    private val ui = SyncSettingsUi(instrumentation)
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val type = "com.alexindigo.kotatsu.sync.test"
    private val suffix = UUID.randomUUID().toString().take(8)
    private val personal = ContactAccount("Personal-$suffix", type)
    private val family = ContactAccount("Family-$suffix", type)
    private val personalCalendarName = "PersonalR-$suffix"
    private val familyCalendarName = "FamilyR-$suffix"
    private val localCalendarName = "PC Sync-$suffix"
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
    private val hostOwners = mutableMapOf<Activity, SyncSettingsOwner>()
    private fun openHost(bookNames: Set<String>? = null, setUp: (() -> Unit)? = {}): ComponentActivity {
        val activity = instrumentation.startActivitySync(Intent(context, SyncSettingsHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        main {
            val owner = SyncSettingsOwner(ownerTestContext(context, bookNames))
            hostOwners[activity] = owner
            activity.setContent { CompositionLocalProvider(LocalPalette provides PaperPalette.Light(activity)) {
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
            ui.await(familyCalendarName); ui.await(personalCalendarName); ui.await("Shown")
            assertTrue(config().calendars.isEmpty()); assertNull(config().newEventCalendar)
            assertFalse("New events is absent until a calendar is shown", ui.has("New events"))
            awaitSyncSettings("Opening arms all recurring jobs") {
                listOf(SyncRuntime.WATCH_JOB, SyncRuntime.CONTACTS_JOB, SyncRuntime.PERIODIC_JOB, SyncRuntime.HORIZON_JOB)
                    .all { scheduler.getPendingJob(it) != null }
            }
            assertFalse(ui.selected("Shown $personalCalendarName"))
            ui.capture(activity, "calendars-unchecked")
            ui.click("Shown $personalCalendarName")
            awaitSyncSettings("Checkbox writes selection and Personal default") {
                config().calendars == setOf(f.personalCalendar) && config().newEventCalendar == f.personalCalendar
            }
            ui.click("Shown $familyCalendarName")
            awaitSyncSettings("Both shown calendars persist and render checked") {
                config().calendars == setOf(f.personalCalendar, f.familyCalendar) &&
                    ui.selected("Shown $personalCalendarName") && ui.selected("Shown $familyCalendarName")
            }
            val column = Rect().also { ui.find("Shown")!!.getBoundsInScreen(it) }
            if (context.resources.configuration.fontScale == 1f)
                assertEquals(64f * context.resources.displayMetrics.density, column.width().toFloat(), 1f)
            assertTrue(column.width() >= 64f * context.resources.displayMetrics.density - 1f)
            val control = Rect().also { ui.find("Shown $personalCalendarName")!!.getBoundsInScreen(it) }
            val box = ui.drawings("paper_index_checkbox").single { it.center.y in control.top.toFloat()..control.bottom.toFloat() }
            val leader = ui.drawings("paper_index_leader").single { it.center.y in control.top.toFloat()..control.bottom.toFloat() }
            assertEquals("Shown header centres on its checkbox", column.exactCenterX(), box.center.x, 1f)
            assertEquals("Shared drawing gap", 7.5f * context.resources.displayMetrics.density, box.left - leader.right, 1f)
            assertFalse("Local calendars are never offered", ui.has(localCalendarName))
            ui.capture(activity, "calendars-shown")
            seek("New events"); ui.click("New events"); ui.awaitPage("New events")
            ui.await(personalCalendarName); ui.await(familyCalendarName); ui.capture(activity, "new-events")
            ui.back(); ui.awaitPage("Kotatsu Sync"); assertEquals(f.personalCalendar, config().newEventCalendar)
            ui.click("New events"); ui.awaitPage("New events"); ui.click(familyCalendarName)
            awaitSyncSettings("New events picker persists") { config().newEventCalendar == f.familyCalendar }
            seek("Look ahead"); ui.click("Look ahead"); ui.awaitPage("Look ahead")
            ui.await("1"); ui.capture(activity, "look-ahead")
            seek("26"); ui.click("26")
            awaitSyncSettings("Horizon persists") { config().horizonWeeks == 26 }
            seek("Contacts go to"); ui.await(personal.name)
            assertEquals(personal, PreferenceContactsConfig(context).read().target)
            ui.click("Contacts go to"); ui.awaitPage("Contacts go to")
            ui.await(family.name); ui.await(personal.name); ui.capture(activity, "contacts-go-to")
            ui.click(family.name)
            awaitSyncSettings("Contacts picker persists") { PreferenceContactsConfig(context).read().target == family }
            seek("Phone contacts"); ui.await("Move to ${family.name}")
        }
        val reopened = open()
        withFixtureCleanup({ close(reopened) }) {
            ui.await("Shown $personalCalendarName"); assertTrue(ui.selected("Shown $personalCalendarName"))
            seek("Look ahead"); ui.await("26 weeks")
            seek("Contacts go to"); ui.await(family.name)
            assertEquals(f.familyCalendar, config().newEventCalendar)
        }
    }

    @Test fun confirmedMoveUsesTheDisplayedBookAndOnlyTheDisplayedContacts() = fixture { f ->
        val activity = open()
        withFixtureCleanup({ close(activity) }) {
            ui.await(personalCalendarName); seek("Phone contacts")
            quiesceJobs()
            val baseline = phoneSourceIds(context)
            val source = f.phoneContact("Phone fixture")
            ui.click("Phone contacts"); ui.awaitPage("Move phone contacts")
            ui.await(context.resources.getQuantityString(R.plurals.move_confirmation, baseline.size + 1, baseline.size + 1, personal.name))
            ui.capture(activity, "move-confirmation")
            ui.click("Cancel")
            assertEquals(baseline + source, f.raw(null).toSet()); assertTrue(f.raw(personal).isEmpty())
            ui.click("Phone contacts"); ui.awaitPage("Move phone contacts")
            val later = f.phoneContact("Later phone fixture")
            ui.click("Move")
            awaitSyncSettings("Confirmed contact reaches the chosen book") { f.raw(personal).size == 1 }
            assertEquals(setOf(later), f.raw(null).toSet())
            assertEquals(1, status().changes)
            seek("Last sync"); ui.await(context.resources.getQuantityString(R.plurals.sync_status, 1,
                android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(status().lastPassAt!!)), 1))
            ui.capture(activity, "move-complete")
            SyncRuntime.enqueue { SyncRuntime.pass(context) }.get(15, TimeUnit.SECONDS)
            assertTrue(f.raw(null).isEmpty()); assertEquals(2, f.raw(personal).size)
        }
    }

    @Test fun countedLabelsRenderSingularAndPluralFromTheirRealSettingsAndProviders() = fixture(ownerOnly = true) { f ->
        val activity = openHost()
        withFixtureCleanup({ close(activity) }) {
            ui.await(personalCalendarName)
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
            val baseline = phoneSourceIds(context)
            for (count in 1..2) {
                f.phoneContact("Count fixture $count")
                seek("Phone contacts"); ui.click("Phone contacts"); ui.awaitPage("Move phone contacts")
                ui.await(context.resources.getQuantityString(R.plurals.move_confirmation, baseline.size + count, baseline.size + count, personal.name))
                ui.click("Cancel")
                assertEquals("Confirmation does not move contacts", baseline.size + count, f.raw(null).size)
            }
        }
    }

    @Test fun syncNowUpdatesLastSyncAfterTheConsumerChangesAndShowsStops() = fixture { f ->
        val activity = open()
        withFixtureCleanup({ close(activity) }) {
            ui.await(personalCalendarName); ui.click("Shown $personalCalendarName")
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
            BridgeStore(context).use { it.syncStatus = SyncStatus(System.currentTimeMillis(), 0, setOf(SyncStopReason.ADDRESS_BOOK_REQUIRED)) }
            ui.await("Stopped: choose an address book")
            ui.capture(activity, "stopped")
        }
    }

    @Test fun missingAccessRowsForwardTheExactRequestsAndDisappearWhenGranted() = fixture(ownerOnly = true) {
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
            ui.await("Calendar access"); ui.await("Contacts access"); ui.await("Allow")
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
            ui.await(personalCalendarName)
            assertFalse(ui.has("Calendar access")); assertFalse(ui.has("Contacts access"))
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
                assertFalse("No completed pass means no Last sync row: $missing", ui.has("Last sync"))
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
                        ui.await(context.resources.getQuantityString(R.plurals.sync_status, 2,
                            android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(real.lastPassAt!!)), 2))
                        if (missing == PERMISSIONS) ui.capture(activity, "status-access-missing-with-result")
                    } else ui.await(context.getString(R.string.stopped_review))
                    assertFalse("Availability is not a Last sync state while access is missing",
                        ui.has(context.getString(R.string.stopped_scheduling)))
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
                    val text = if (reasons.isEmpty()) context.resources.getQuantityString(R.plurals.sync_status, status.changes,
                        android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date(status.lastPassAt!!)), status.changes)
                        else reasons.sortedBy { it.ordinal }.mapNotNull { it.textResource() }.map { context.getString(it) }.joinToString("\n")
                    seek("Last sync"); ui.await(text)
                    val layout = ui.textLayout(text)
                    assertReadable(layout, "$name at $scale")
                    val label = ui.textLayout("Last sync")
                    assertEquals("Label never wraps", 1, label.lineCount)
                    assertReadable(label, "Last sync at $scale")
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
            ui.await("DAVx5"); ui.await(if (expected == null) "Unavailable" else "Set up")
            for (label in listOf("Shown", "New events", "Look ahead", "Contacts go to", "Phone contacts")) assertFalse(ui.has(label))
            ui.await("Last sync"); ui.await("Sync now")
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
            ui.await(personalCalendarName)
            val heading = Rect().also { ui.find("Shown")!!.getBoundsInScreen(it) }
            val target = Rect().also { ui.find("Shown $personalCalendarName")!!.getBoundsInScreen(it) }
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

    @Test fun absentPersonalBookUsesFirstAvailableAndNewEventsStayWithinShownCalendars() = fixture(ownerOnly = true) { f ->
        resolver.delete(Settings.CONTENT_URI, "account_name = ? AND account_type = ?", arrayOf(personal.name, personal.type))
        PreferenceContactsConfig(context).write(ContactsSettings())
        val activity = openHost()
        withFixtureCleanup({ close(activity) }) {
            ui.await(personalCalendarName)
            val available = AndroidContacts(context).addressBooks().sortedWith(compareBy<ContactAccount> { it.name.lowercase() }
                .thenBy { it.type }.thenBy { it.dataSet.orEmpty() })
            val expected = available.firstOrNull { it.name.contains("Personal", ignoreCase = true) } ?: available.first()
            awaitSyncSettings("First available address book is the fallback default") { PreferenceContactsConfig(context).read().target == expected }
            seek("Shown $familyCalendarName"); ui.click("Shown $familyCalendarName")
            awaitSyncSettings("First shown calendar is the fallback default") { config().newEventCalendar == f.familyCalendar }
            seek("Shown $personalCalendarName"); ui.click("Shown $personalCalendarName")
            awaitSyncSettings("Personal checkbox renders") { ui.selected("Shown $personalCalendarName") }
            seek("Shown $familyCalendarName"); ui.click("Shown $familyCalendarName")
            awaitSyncSettings("Unshown calendar cannot remain the new-event destination") {
                config().calendars == setOf(f.personalCalendar) && config().newEventCalendar == f.personalCalendar
            }
            seek("Shown $personalCalendarName"); ui.click("Shown $personalCalendarName")
            awaitSyncSettings("No shown calendars means no destination") { config().calendars.isEmpty() && config().newEventCalendar == null }
            awaitSyncSettings("New events disappears with the last shown calendar") { !ui.has("New events") }
        }
    }

    @Test fun queuedCalendarWritesValidateAgainstTheLatestCommittedShownSet() = fixture(ownerOnly = true) { f ->
        PreferenceSyncConfig(context).write(SyncSettings(setOf(f.familyCalendar, f.personalCalendar), f.personalCalendar))
        val owner = settingsOwner()
        queuedWrites {
            owner.shown(f.personalCalendar, false)
            owner.newEvents(f.personalCalendar)
            owner.weeks(12)
            assertTrue("The pending hide is not yet in the UI snapshot", f.personalCalendar in owner.snapshot.settings.calendars)
        }
        assertEquals(SyncSettings(setOf(f.familyCalendar), f.familyCalendar, 12), config())
        assertEquals(config(), owner.snapshot.settings)
        quiesceJobs()
        queuedWrites {
            owner.shown(f.personalCalendar, true)
            owner.newEvents(f.personalCalendar)
            owner.weeks(20)
            assertFalse("Validation must not reject from the old snapshot", f.personalCalendar in owner.snapshot.settings.calendars)
        }
        assertEquals(SyncSettings(setOf(f.familyCalendar, f.personalCalendar), f.personalCalendar, 20), config())
        quiesceJobs()
        queuedWrites {
            owner.shown(f.familyCalendar, false)
            owner.shown(f.personalCalendar, false)
            owner.newEvents(f.familyCalendar)
        }
        assertEquals(SyncSettings(emptySet(), null, 20), config())
        assertEquals(config(), owner.snapshot.settings)
    }

    @Test fun queuedCalendarFallbackUsesFreshNamesWithoutDiscardingUnavailableSavedChoices() = fixture(ownerOnly = true) { f ->
        val other = f.calendar("AardvarkR-$suffix", type)
        PreferenceSyncConfig(context).write(SyncSettings(setOf(other, f.familyCalendar, f.personalCalendar), f.personalCalendar))
        val owner = settingsOwner()
        queuedWrites {
            SyncRuntime.enqueue {
                resolver.update(f.syncUri(ContentUris.withAppendedId(Calendars.CONTENT_URI, f.familyCalendar)),
                    ContentValues().apply { put(Calendars.CALENDAR_DISPLAY_NAME, "Personal family-$suffix") }, null, null)
            }
            owner.shown(f.personalCalendar, false)
            owner.weeks(12)
            assertEquals(familyCalendarName, owner.snapshot.calendars.single { it.id == f.familyCalendar }.name)
        }
        assertEquals(SyncSettings(setOf(other, f.familyCalendar), f.familyCalendar, 12), config())
        quiesceJobs()
        queuedWrites {
            SyncRuntime.enqueue {
                resolver.update(f.syncUri(ContentUris.withAppendedId(Calendars.CONTENT_URI, f.familyCalendar)),
                    ContentValues().apply { put(Calendars.SYNC_EVENTS, 0) }, null, null)
                resolver.update(f.syncUri(ContentUris.withAppendedId(Calendars.CONTENT_URI, f.personalCalendar)),
                    ContentValues().apply { put(Calendars.SYNC_EVENTS, 0) }, null, null)
            }
            owner.shown(f.personalCalendar, true)
            owner.weeks(7)
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
            assertTrue("The stale UI still offers the removed book", family in owner.snapshot.books)
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
            assertFalse("Validation must see the book added ahead of this write", family in owner.snapshot.books)
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
        assertEquals(f.baselineBooks + personal + family, owner.snapshot.books.toSet())
        assertEquals(f.baselineCalendars + f.personalCalendar + f.familyCalendar, owner.snapshot.calendars.map { it.id }.toSet())
        assertEquals(setOf(personal, family), owner.snapshot.books.filter { it.name.endsWith(suffix) }.toSet())
        val expected = owner.snapshot.books.firstOrNull { it.name.contains("Personal", ignoreCase = true) } ?: owner.snapshot.books.first()
        assertEquals(expected, owner.snapshot.contacts.target)
        assertEquals(expected, PreferenceContactsConfig(context).read().target)
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
                for (book in listOf(personal, family)) {
                    val account = Account(book.name, book.type)
                    check(AccountManager.get(context).addAccountExplicitly(account, null, null)); accounts += account
                    resolver.insert(Settings.CONTENT_URI, ContentValues().apply {
                        put(Settings.ACCOUNT_NAME, book.name); put(Settings.ACCOUNT_TYPE, book.type)
                        put(Settings.SHOULD_SYNC, 1); put(Settings.UNGROUPED_VISIBLE, 1)
                    })!!
                }
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
