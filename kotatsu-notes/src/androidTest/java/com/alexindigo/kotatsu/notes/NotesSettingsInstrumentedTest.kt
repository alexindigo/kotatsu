package com.alexindigo.kotatsu.notes

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class NotesSettingsInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = NotesSettingsUi(instrumentation)
    private fun text(id: Int) = context.getString(id)
    private fun main(work: () -> Unit) = instrumentation.runOnMainSync(work)
    private fun open(type: Class<*>) = instrumentation.startActivitySync(Intent(context, type)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    private fun close(activity: Activity) = main { activity.finish() }
    private fun reset() {
        NotesRuntime.enqueue {
            WidgetKind.entries.forEach { it.setEnabled(context, true) }
            context.createDeviceProtectedStorageContext().getSharedPreferences("notes-selection", 0).edit().clear().commit()
            context.createDeviceProtectedStorageContext().getSharedPreferences("quote-widgets", 0).edit().clear().commit()
        }.get()
        context.contentResolver.call(MuditaNotes.URI, "reset", null, null)
    }

    @Test fun bookWithoutInstancesChoicesBackAndKindPagesPersist() {
        reset()
        assertTrue(NotesRuntime.ids(context).isEmpty())
        assertTrue(QuoteRuntime.ids(context).isEmpty())
        val root = open(NotesEntryActivity::class.java)
        try {
            ui.await(text(R.string.settings_widgets))
            ui.await(text(R.string.settings_changes))
            ui.await(text(R.string.quote_order))
            assertFalse("Six settings fit one reference page", ui.has(context.getString(com.gezimos.katapult.paper.R.string.paper_next_page)))
            for (label in listOf(R.string.settings_note, R.string.settings_changes, R.string.quote_order)) {
                val bounds = android.graphics.Rect().also { ui.find(text(label), actionable = true)!!.getBoundsInScreen(it) }
                assertEquals("Help-less settings keep the shared 48dp row and normal padding",
                    48f * context.resources.displayMetrics.density, bounds.height().toFloat(), 1f)
            }
            ui.capture(root, "root-1")
        } finally { close(root) }
        val task = open(ConfigureActivity::class.java)
        try {
            ui.click(text(R.string.settings_note))
            ui.awaitPage(text(R.string.settings_note)); ui.await("Kotatsu long list")
            ui.capture(task, "task-note-unselected")
            ui.back()
            ui.await(text(R.string.settings_note))
            assertNull("Back must not apply a choice", NotesStore(context).selected())
            ui.click(text(R.string.settings_note)); ui.click("Kotatsu long list")
            awaitWidgetHost("Kind selection persists without an instance") { NotesStore(context).selected() == 4L }
            ui.await(text(R.string.settings_task)); ui.await("Kotatsu long list")
            ui.capture(task, "task")
            ui.click(text(R.string.settings_note)); ui.awaitPage(text(R.string.settings_note)); ui.await("Kotatsu long list")
            assertTrue(ui.selected("Kotatsu long list"))
            ui.capture(task, "task-note-selected")
            ui.back()
        } finally { close(task) }
        val quote = open(QuoteConfigureActivity::class.java)
        try {
            ui.click(text(R.string.settings_changes)); ui.await(text(R.string.quote_daily))
            ui.capture(quote, "changes")
            ui.click(text(R.string.quote_hourly))
            ui.click(text(R.string.quote_order)); ui.await(text(R.string.quote_rotation))
            ui.capture(quote, "order")
            ui.click(text(R.string.quote_shuffle))
            ui.click(text(R.string.settings_note)); ui.awaitPage(text(R.string.settings_note)); ui.await("Kotatsu quotes")
            assertFalse("Quote choices must not include checklists", ui.has("Kotatsu long list"))
            ui.capture(quote, "quote-note-unselected")
            ui.click("Kotatsu quotes")
            awaitWidgetHost("All kind settings persist") { QuoteStore(context).config()?.let {
                it.noteId == 7L && it.frequency == QuoteFrequency.HOURLY && it.order == QuoteOrder.SHUFFLE
            } == true }
            ui.await(text(R.string.quote_label)); ui.await("Kotatsu quotes")
            ui.capture(quote, "quote")
            ui.click(text(R.string.settings_note)); ui.awaitPage(text(R.string.settings_note)); ui.await("Kotatsu quotes")
            assertTrue(ui.selected("Kotatsu quotes"))
            ui.capture(quote, "quote-note-selected")
        } finally { close(quote) }
        val reopened = open(QuoteConfigureActivity::class.java)
        try { ui.await("Kotatsu quotes"); ui.await(text(R.string.quote_hourly)) }
        finally { close(reopened) }
    }

    @Test fun realDeviceBackReturnsFromEveryChoiceToItsOwningPagerPageThenLeaves() {
        reset()
        val activity = open(NotesEntryActivity::class.java) as NotesEntryActivity
        // Keep multi-page Back coverage even though the reference viewport now fits one page.
        main {
            val owner = NotesSettingsOwner(activity)
            owner.refresh()
            activity.setContent {
                androidx.compose.runtime.CompositionLocalProvider(com.gezimos.katapult.ui.LocalPalette provides NotesAppearance.palette(activity)) {
                    Box(Modifier.requiredSize(360.dp, 420.dp)) { NotesSettingsBook(owner, null, activity::finish) }
                }
            }
        }
        val page = com.gezimos.katapult.paper.R.string.paper_page
        fun returnFrom(label: Int, parentPage: Int) {
            ui.click(text(label))
            ui.awaitPage(text(label))
            ui.back()
            ui.awaitPage(text(R.string.app_name))
            ui.await(context.getString(page, parentPage, 2))
            main { assertFalse("Only top-level Back may finish the activity", activity.isFinishing) }
        }
        try {
            ui.awaitPage(text(R.string.app_name))
            returnFrom(R.string.settings_note, 1)
            ui.click(context.getString(com.gezimos.katapult.paper.R.string.paper_next_page))
            returnFrom(R.string.settings_note, 2)
            returnFrom(R.string.settings_changes, 2)
            returnFrom(R.string.quote_order, 2)
            assertNull(NotesStore(context).selected())
            assertNull(QuoteStore(context).config())
            ui.back()
            awaitWidgetHost("Top-level device Back must finish the activity") {
                var finished = false
                main { finished = activity.isFinishing || activity.isDestroyed }
                finished
            }
        } finally { close(activity) }
    }

    @Test fun initialKeyboardModeDoesNotHighlightBackButKeyboardNavigationUsesOnlyInkRing() {
        reset()
        instrumentation.setInTouchMode(false)
        val activity = open(NotesEntryActivity::class.java)
        val back = context.getString(com.gezimos.katapult.paper.R.string.paper_back)
        fun assertWhiteControlInterior(name: String, focused: Boolean) {
            ui.capture(activity, name)
            val bitmap = android.graphics.BitmapFactory.decodeFile(java.io.File(context.getExternalFilesDir(null),
                "settings-after/$name.png").path)
            val rect = android.graphics.Rect().also { ui.find(back)!!.getBoundsInScreen(it) }
            // Away from both the chevron and the border: a state-layer fill is not antialiasing.
            assertEquals("No gray focus fill", android.graphics.Color.WHITE, bitmap.getPixel(rect.left + 8, rect.top + 8))
            assertEquals("Only keyboard navigation draws the kit's black focus ring",
                if (focused) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
                bitmap.getPixel(rect.left, rect.top + 8))
            bitmap.recycle()
        }
        try {
            ui.awaitPage(text(R.string.app_name))
            assertWhiteControlInterior("back-focus-initial", false)
            assertFalse("No initial keyboard focus highlight", ui.find(back)!!.isFocused)
            ui.key(android.view.KeyEvent.KEYCODE_TAB)
            awaitWidgetHost("Keyboard navigation must still focus Back") { ui.find(back)?.isFocused == true }
            assertWhiteControlInterior("back-focus-keyboard", true)
        } finally { close(activity); instrumentation.setInTouchMode(true) }
    }

    @Test fun realDeviceBackPopsConfigureChoicesBeforeReturningTheOriginalWidgetResult() {
        reset()
        for (kind in WidgetKind.entries) {
            val host = open(SettingsConfigureHostActivity::class.java) as SettingsConfigureHostActivity
            val type = if (kind == WidgetKind.TASK) ConfigureActivity::class.java else QuoteConfigureActivity::class.java
            val title = text(if (kind == WidgetKind.TASK) R.string.settings_task else R.string.quote_label)
            val monitor = instrumentation.addMonitor(type.name, null, false)
            val widgetId = 4200 + kind.ordinal
            var child: Activity? = null
            try {
                main { host.startActivityForResult(Intent(host, type)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId), 71) }
                child = monitor.waitForActivityWithTimeout(5000)
                assertNotNull("Android configure activity must open", child)
                ui.awaitPage(title)
                val choices = if (kind == WidgetKind.TASK) listOf(R.string.settings_note)
                    else listOf(R.string.settings_note, R.string.settings_changes, R.string.quote_order)
                for (label in choices) {
                    ui.click(text(label)); ui.awaitPage(text(label))
                    ui.back(); ui.awaitPage(title)
                    main {
                        assertFalse(child!!.isFinishing)
                        assertNull("A child-page Back must not complete configuration", host.returnedResult)
                    }
                }
                ui.back()
                awaitWidgetHost("Top configure-page Back must return to its actual caller") {
                    var returned = false
                    main { returned = host.returnedResult != null && host.hasWindowFocus() }
                    returned
                }
                main {
                    assertEquals(71, host.returnedRequest)
                    assertEquals(Activity.RESULT_OK, host.returnedResult)
                    assertEquals(widgetId, host.returnedData!!.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1))
                }
            } finally {
                instrumentation.removeMonitor(monitor)
                child?.let(::close)
                close(host)
            }
        }
    }

    @Test fun togglesRemoveOnlyTheirProviderAndInstancesButRetainKindSettings() {
        reset()
        NotesRuntime.enqueue { check(NotesStore(context).select(4)); QuoteStore(context).save(QuoteConfig(7)) }.get()
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        val host = AppWidgetHost(context, 0x4b4e51)
        val manager = AppWidgetManager.getInstance(context)
        fun bind(kind: WidgetKind) = host.allocateAppWidgetId().also {
            assertTrue(manager.bindAppWidgetIdIfAllowed(it, ComponentName(context, kind.provider)))
            assertTrue(manager.getAppWidgetInfo(it).widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0)
        }
        val taskId = bind(WidgetKind.TASK)
        val quoteId = bind(WidgetKind.QUOTE)
        val activity = open(NotesEntryActivity::class.java)
        try {
            ui.click(text(R.string.quote_label))
            awaitWidgetHost("Quote must disappear from Android's provider and instance lists") {
                !WidgetKind.QUOTE.enabled(context) && manager.getAppWidgetInfo(quoteId) == null &&
                    manager.installedProviders.none { it.provider == ComponentName(context, QuoteWidgetProvider::class.java) }
            }
            assertNotNull(manager.getAppWidgetInfo(taskId))
            ui.capture(activity, "quote-off")
            ui.click(text(R.string.settings_task))
            awaitWidgetHost("Task provider removed independently") { manager.getAppWidgetInfo(taskId) == null && !WidgetKind.TASK.enabled(context) }
            ui.capture(activity, "both-off")
            assertFalse("Unavailable groups are hidden", ui.has(text(R.string.settings_note)))
            main {
                val reopened = NotesSettingsOwner(context).snapshot
                assertFalse("Initial task state comes from PackageManager before Notes I/O", reopened.taskOn)
                assertFalse("Initial quote state comes from PackageManager before Notes I/O", reopened.quoteOn)
            }
            ui.click(text(R.string.quote_label))
            awaitWidgetHost("Quote provider can be re-added") {
                manager.installedProviders.any { it.provider == ComponentName(context, QuoteWidgetProvider::class.java) }
            }
            bind(WidgetKind.QUOTE)
            assertEquals(7L, QuoteStore(context).config()!!.noteId)
            assertEquals(4L, NotesStore(context).selected())
        } finally {
            close(activity)
            main { host.deleteHost() }
            NotesRuntime.enqueue { WidgetKind.entries.forEach { it.setEnabled(context, true) } }.get()
            awaitWidgetHost("Both providers must be available before releasing the fixture") {
                WidgetKind.entries.all { kind -> manager.installedProviders.any { it.provider == ComponentName(context, kind.provider) } }
            }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun missingNotesIsOnePlainLineAndEveryLocaleHasSettingsCopy() {
        reset()
        val previous = NotesRuntime.notes
        NotesRuntime.notes = { object : NotesPort by MuditaNotes(it.contentResolver) {
            override fun choices(): List<TaskListChoice>? = throw SecurityException("Missing permission")
        } }
        val activity = open(ConfigureActivity::class.java)
        try {
            ui.click(text(R.string.settings_note))
            ui.await(text(R.string.settings_notes_unavailable))
            ui.capture(activity, "notes-unavailable")
            assertFalse(ui.has("Kotatsu long list"))
        } finally { close(activity); NotesRuntime.notes = previous }
        val keys = listOf(R.string.settings_widgets, R.string.settings_task, R.string.settings_note,
            R.string.settings_choose, R.string.settings_changes, R.string.settings_task_help,
            R.string.settings_quote_help, R.string.settings_notes_unavailable, R.string.settings_loading)
        for (language in listOf("en", "cs", "de", "es", "fr", "it", "nl", "pl", "pt")) {
            val localized = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag(language))
            })
            keys.forEach { assertTrue(localized.getString(it).isNotBlank()) }
        }
    }

    @Test fun kindSettingsReachEveryPlacedInstanceAndSurviveInstanceDeletion() {
        reset()
        val device = context.createDeviceProtectedStorageContext()
        device.getSharedPreferences("notes-selection", 0).edit().putLong("widget_99", 4).commit()
        device.getSharedPreferences("quote-widgets", 0).edit()
            .putString("config_99", "{\"note\":7,\"frequency\":\"DAILY\",\"order\":\"ROTATION\",\"seed\":0}").commit()
        assertNull("Unreleased instance selections are not migrated", NotesStore(context).selected())
        assertNull(QuoteStore(context).config())
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        QuoteRuntime.clock = java.time.Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC)
        val activity = open(NotesHostActivity::class.java) as NotesHostActivity
        val host = AppWidgetHost(context, 0x4b4e52)
        val manager = AppWidgetManager.getInstance(context)
        val tasks = mutableListOf<android.appwidget.AppWidgetHostView>()
        val quotes = mutableListOf<android.appwidget.AppWidgetHostView>()
        try {
            main {
                val column = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.VERTICAL }
                activity.container.addView(column)
                for (kind in WidgetKind.entries) repeat(2) {
                    val id = host.allocateAppWidgetId()
                    assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, kind.provider)))
                    val view = host.createView(activity, id, manager.getAppWidgetInfo(id))
                    column.addView(view, android.widget.LinearLayout.LayoutParams(-1,
                        (116 * context.resources.displayMetrics.density).toInt()))
                    if (kind == WidgetKind.TASK) tasks += view else quotes += view
                }
                host.startListening()
                val owner = NotesSettingsOwner(context)
                owner.taskNote(4)
                owner.quoteNote(7)
            }
            awaitWidgetHost("Both task and both quote consumers must render their kind settings") {
                var observed = false
                main {
                    fun containsMilk(view: android.view.View): Boolean =
                        view.contentDescription?.toString() == "Milk" || view is android.view.ViewGroup &&
                            (0 until view.childCount).any { containsMilk(view.getChildAt(it)) }
                    observed = tasks.all { containsMilk(it) } && quotes.all {
                        it.findViewById<android.widget.TextView>(R.id.quote_text)?.text?.toString() == "First quote"
                    }
                }
                observed
            }
        } finally {
            main { host.stopListening(); host.deleteHost(); activity.finish() }
            NotesRuntime.enqueue {}.get()
            QuoteRuntime.clock = null
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        assertEquals(4L, NotesStore(context).selected())
        assertEquals(7L, QuoteStore(context).config()!!.noteId)
    }
}
