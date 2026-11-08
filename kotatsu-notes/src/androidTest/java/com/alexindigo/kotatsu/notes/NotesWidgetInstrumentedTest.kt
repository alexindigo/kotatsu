package com.alexindigo.kotatsu.notes

import android.app.job.JobScheduler
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ListView
import android.widget.RemoteViews
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class NotesWidgetInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun onMain(work: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) work() else instrumentation.runOnMainSync(work)
    }
    private fun fixture(method: String, arg: String? = null) = context.contentResolver.call(MuditaNotes.URI, method, arg, null)!!
    private fun await(message: String, ready: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20_000
        var observed = ready()
        while (!observed && SystemClock.elapsedRealtime() < end) {
            Thread.sleep(40)
            observed = ready()
        }
        assertTrue(message, observed)
    }

    /** Receipt is not row binding: RemoteCollectionItems notifies its adapter before ListView lays out. */
    private class ObservedHostView(context: Context) : AppWidgetHostView(context) {
        var received = 0L
            private set
        var laidOut = 0L
            private set
        init {
            viewTreeObserver.addOnGlobalLayoutListener {
                val list = findViewById<ListView>(R.id.tasks)
                if (list != null && list.isShown && !list.isLayoutSuppressed && !list.isLayoutRequested) {
                    laidOut = received
                }
            }
        }
        override fun updateAppWidget(remoteViews: RemoteViews?) {
            received++
            super.updateAppWidget(remoteViews)
        }
    }

    private inner class Host : AutoCloseable {
        val manager = AppWidgetManager.getInstance(context)
        val host = object : AppWidgetHost(context, 0x4b4e10) {
            override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
                ObservedHostView(context)
        }
        val activity = instrumentation.startActivitySync(Intent(context, NotesHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NotesHostActivity
        val id: Int
        lateinit var view: ObservedHostView
        private var clickedAt = 0L
        init {
            fixture("reset")
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            id = host.allocateAppWidgetId()
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, NotesWidgetProvider::class.java)))
            onMain {
                view = host.createView(activity, id, manager.getAppWidgetInfo(id)) as ObservedHostView
                activity.container.addView(view, ViewGroup.LayoutParams(-1, -1))
                host.startListening()
            }
            NotesRuntime.enqueue { check(NotesStore(context).select(id, 4)); NotesRuntime.refresh(context) }.get()
            await("Rows must reach actual widget host") { checkText("Milk") != null }
        }
        fun checkText(label: String): CheckBox? {
            var found: CheckBox? = null
            onMain {
                fun visit(v: View) {
                    if (v is CheckBox && v.contentDescription == label) found = v
                    if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                }
                visit(view)
            }
            return found
        }
        fun click(label: String) = onMain {
            clickedAt = view.received
            checkText(label)!!.performClick()
        }
        fun tickReachedHost(writes: Int, checked: Boolean): Boolean {
            val stored = fixture("stats")
            var rowIsBound = false
            onMain {
                val list = view.findViewById<ListView>(R.id.tasks)
                val check = checkText("Milk")
                rowIsBound = view.received > clickedAt && view.laidOut == view.received &&
                    !list.isLayoutSuppressed && !list.isLayoutRequested &&
                    check?.isShown == true && check.isChecked == checked
            }
            return stored.getInt("writes") == writes &&
                TaskListJson.decode(stored.getString("content")!!)!![0].checked == checked &&
                rowIsBound &&
                NotesStore(context).rendered(id)?.snapshot?.items?.firstOrNull()?.checked == checked
        }
        fun heading(): String? {
            var title: String? = null
            onMain { view.findViewById<TextView>(R.id.heading)?.let {
                if (it.visibility == View.VISIBLE) title = it.text.toString()
            } }
            return title
        }
        fun message(): String? {
            var text: String? = null
            onMain { view.findViewById<TextView>(R.id.message)?.let {
                if (it.visibility == View.VISIBLE) text = it.text.toString()
            } }
            return text
        }
        override fun close() {
            instrumentation.runOnMainSync { host.stopListening(); host.deleteAppWidgetId(id); activity.finish() }
            NotesRuntime.enqueue {
                NotesStore(context).delete(id, true)
                context.getSystemService(JobScheduler::class.java).cancel(NotesRuntime.WATCH_JOB)
            }.get()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun checkboxBroadcastWritesWholeContentAndReadbackUpdatesTheHost() {
        Host().use { f ->
            f.click("Milk")
            await("Tick must persist and return to the host") { f.tickReachedHost(1, true) }
            f.click("Milk")
            await("Untick must persist and render unchecked") { f.tickReachedHost(2, false) }
        }
    }

    @Test fun optimisticCheckboxIsNotAnAcknowledgedHostUpdate() {
        Host().use { f ->
            lateinit var list: ListView
            onMain { list = f.view.findViewById(R.id.tasks); list.suppressLayout(true) }
            try {
                f.click("Milk")
                await("Provider and render store must reach the controlled intermediate state") {
                    fixture("stats").getInt("writes") == 1 &&
                        NotesStore(context).rendered(f.id)?.snapshot?.items?.firstOrNull()?.checked == true
                }
                assertTrue("Click itself is optimistic", f.checkText("Milk")!!.isChecked)
                assertFalse("Persistence and optimistic drawing cannot acknowledge a row that has not rebound",
                    f.tickReachedHost(1, true))
            } finally { onMain { list.suppressLayout(false) } }
            await("The actual rebound row must acknowledge the saved check") { f.tickReachedHost(1, true) }
            f.click("Milk")
            await("The rebound callback must save and display the untick") { f.tickReachedHost(2, false) }
        }
    }

    @Test fun staleRenderedSnapshotDoesNotWriteOrRetargetItsIndex() {
        Host().use { f ->
            val rendered = NotesStore(context).rendered(f.id)!!
            fixture("silent-edit")
            context.sendBroadcast(Intent(context, NotesWidgetProvider::class.java).setAction(NotesWidgetViews.TICK)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, f.id).putExtra(NotesWidgetViews.TOKEN, rendered.token)
                .putExtra(NotesWidgetViews.INDEX, 0))
            await("Stale command must reconcile the new document") { f.checkText("Different first task") != null }
            assertEquals(0, fixture("stats").getInt("writes"))
            assertFalse(f.checkText("Different first task")!!.isChecked)
        }
    }

    @Test fun hostTitleOptionHidesAndRestoresTheActualHeading() {
        Host().use { f ->
            assertEquals("Kotatsu long list", f.heading())
            f.manager.updateAppWidgetOptions(f.id, Bundle().apply { putBoolean(NotesWidgetViews.HOST_TITLE, true) })
            await("Host owns the title") { f.heading() == null && f.checkText("Milk") != null }
            f.manager.updateAppWidgetOptions(f.id, Bundle().apply { putBoolean(NotesWidgetViews.HOST_TITLE, false) })
            await("Provider owns the title again") { f.heading() == "Kotatsu long list" }
        }
    }

    @Test fun contentJobUpdatesAffectedWidgetsAndRearmsForTheNextEditAndDeletion() {
        Host().use { f ->
            fixture("edit", "First external edit")
            await("Content-URI job must update the host") { f.heading() == "First external edit" }
            fixture("edit", "Second external edit")
            await("Rearmed job must deliver the next edit") { f.heading() == "Second external edit" }
            fixture("delete")
            await("Missing list must show plain guidance") { f.message() == context.getString(R.string.choose_list) }
            assertNull(f.heading())
        }
    }

    @Test fun beforeUnlockShowsOneLineWithoutReadingCredentialProtectedState() {
        Host().use { f ->
            val previous = NotesRuntime.unlocked
            try {
                NotesRuntime.unlocked = { false }
                NotesRuntime.enqueue { NotesRuntime.refresh(context) }.get()
                await("Direct-boot guidance must reach host") { f.message() == context.getString(R.string.unlock_once) }
                assertNull(f.heading())
            } finally { NotesRuntime.unlocked = previous }
        }
    }

    @Test fun pickerQueriesTitlesOnlyAndEveryLocaleHasItsOwnCopy() {
        fixture("reset")
        assertEquals(listOf(TaskListChoice(4, "Kotatsu long list")), MuditaNotes(context.contentResolver).choices())
        assertTrue(fixture("stats").getBoolean("titlesOnly"))
        val keys = listOf(R.string.widget_label, R.string.widget_description, R.string.choose_list, R.string.unlock_once, R.string.untitled_list,
            R.string.choose_widget, R.string.no_lists, R.string.add_widget_help, R.string.save_failed)
        for (language in listOf("en", "cs", "de", "es", "fr", "it", "nl", "pl", "pt")) {
            val localized = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale(language))
            })
            keys.forEach { key ->
                assertTrue(localized.getString(key).isNotBlank())
                if (language != "en") assertNotEquals("$language $key", context.getString(key), localized.getString(key))
            }
        }
    }

    @Test fun rowsUseMeasuredNotesGeometryAndWrapWithoutChangingCheckedTypography() {
        Host().use { f ->
            instrumentation.waitForIdleSync()
            onMain {
                val list = f.view.findViewById<ListView>(R.id.tasks)
                val first = list.getChildAt(0) as ViewGroup
                val wrapped = list.getChildAt(1) as ViewGroup
                val check = first.findViewById<CheckBox>(R.id.task_check)
                val text = first.findViewById<TextView>(R.id.task_text)
                val longText = wrapped.findViewById<TextView>(R.id.task_text)
                val density = context.resources.displayMetrics.density
                val scaled = context.resources.displayMetrics.scaledDensity
                assertEquals(48 * density, check.width.toFloat(), 1f)
                assertEquals(28 * density, (check.buttonDrawable as android.graphics.drawable.InsetDrawable).drawable!!.intrinsicWidth.toFloat(), 1f)
                val rowOrigin = IntArray(2).also(first::getLocationOnScreen)
                val textOrigin = IntArray(2).also(text::getLocationOnScreen)
                assertEquals(54 * density, (textOrigin[0] - rowOrigin[0]).toFloat(), 2f)
                assertEquals(50 * density, (wrapped.top - first.top).toFloat(), 2f)
                assertEquals((23 * scaled).roundToInt().toFloat(), text.textSize, .1f)
                assertEquals(28 * scaled, text.lineHeight.toFloat(), 1f)
                assertEquals(500, text.typeface.weight)
                val expectedPaint = android.graphics.Paint(text.paint).apply {
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.create("lato-mudita", android.graphics.Typeface.NORMAL), 500, false)
                }
                for (sample in listOf("Milk", "WWmm ii 0123456789", "Ask the pharmacist")) {
                    assertEquals("Named system family glyph metrics", expectedPaint.measureText(sample), text.paint.measureText(sample), .01f)
                }
                assertTrue("Full task text wraps", longText.lineCount > 1)
                assertTrue("Wrapped row grows", wrapped.height > first.height)
                assertEquals(check.top, wrapped.findViewById<CheckBox>(R.id.task_check).top)
                assertEquals("Same checked and unchecked text ink", text.currentTextColor, longText.currentTextColor)
            }
            val bitmap = capture(f.activity)
            val dir = java.io.File(context.getExternalFilesDir(null), "notes-renders").apply { mkdirs() }
            java.io.File(dir, "widget-notes.png").outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
    }

    @Test fun collectionStartsAtItsFirstDrawingAndEndsAtItsLastDrawingWithoutChangingRowPitch() {
        Host().use { f ->
            fixture("rows", "24")
            f.manager.updateAppWidgetOptions(f.id, Bundle().apply { putBoolean(NotesWidgetViews.HOST_TITLE, true) })
            NotesRuntime.enqueue { NotesRuntime.refresh(context, intArrayOf(f.id)) }.get()
            await("Edge fixture must reach the host") {
                var ready = false
                onMain { val list = f.view.findViewById<ListView>(R.id.tasks)
                    ready = list.count == 24 && list.childCount > 1 && !list.isLayoutRequested && f.heading() == null }
                ready
            }
            lateinit var list: ListView
            onMain { list = f.view.findViewById(R.id.tasks); list.setSelection(0) }
            fun rect(view: View): android.graphics.Rect {
                val p = IntArray(2).also(view::getLocationInWindow)
                return android.graphics.Rect(p[0], p[1], p[0] + view.width, p[1] + view.height)
            }
            fun pixels(frame: android.graphics.Bitmap, area: android.graphics.Rect): android.graphics.Rect {
                val points = mutableListOf<Pair<Int,Int>>()
                for (y in area.top until area.bottom) for (x in area.left until area.right) {
                    if ((frame.getPixel(x,y) and 0xffffff) < 0x808080) points += x to y
                }
                assertTrue("Row drawing absent", points.isNotEmpty())
                return android.graphics.Rect(points.minOf { it.first }, points.minOf { it.second }, points.maxOf { it.first } + 1, points.maxOf { it.second } + 1)
            }
            await("First row must settle") { var ready = false; onMain { ready = !list.isLayoutRequested && list.firstVisiblePosition == 0 }; ready }
            var frame = capture(f.activity)
            onMain {
                val viewport = rect(list)
                val first = rect(list.getChildAt(0)).apply { intersect(viewport) }
                assertEquals("First drawing at viewport top", viewport.top.toFloat(), pixels(frame, first).top.toFloat(), 1f)
                val resources = context.resources
                val pitch = 2 * resources.getDimensionPixelSize(R.dimen.task_row_padding) +
                    2 * resources.getDimensionPixelSize(R.dimen.task_button_padding) + resources.getDimensionPixelSize(R.dimen.task_button_size)
                assertEquals("Original Notes pitch", pitch, list.getChildAt(1).top - list.getChildAt(0).top)
            }
            val directory = java.io.File(context.getExternalFilesDir(null), "notes-renders").apply { mkdirs() }
            java.io.File(directory, "widget-notes-edge-top.png").outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            frame.recycle()
            onMain { list.setSelection(list.count - 1) }
            await("Last row must meet the viewport end") { var ready = false; onMain { ready = !list.isLayoutRequested && list.lastVisiblePosition == list.count - 1 && !list.canScrollList(1) }; ready }
            frame = capture(f.activity)
            onMain {
                val viewport = rect(list)
                val last = list.getChildAt(list.childCount - 1)
                val area = rect(last).apply { intersect(viewport) }
                assertEquals("Last drawing at viewport bottom", viewport.bottom.toFloat(), pixels(frame, area).bottom.toFloat(), 1f)
                val text = last.findViewById<TextView>(R.id.task_text)
                val ink = android.graphics.Rect()
                text.paint.getTextBounds(text.text.toString(),0,text.length(),ink)
                val naturalBottom = rect(text).top + text.totalPaddingTop + text.layout.getLineBaseline(text.lineCount - 1) + ink.bottom
                assertTrue("Last label descender must remain visible", naturalBottom <= viewport.bottom + 1)
            }
            java.io.File(directory, "widget-notes-edge-bottom.png").outputStream().use { frame.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            frame.recycle()
        }
    }

    /** Native widget state can precede its submitted buffer; capture only the committed window. */
    private fun capture(activity: NotesHostActivity): android.graphics.Bitmap {
        instrumentation.waitForIdleSync()
        val committed = java.util.concurrent.CountDownLatch(1)
        val size = IntArray(2)
        onMain {
            val decor = activity.window.decorView
            size[0] = decor.width; size[1] = decor.height
            decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            decor.invalidate()
        }
        assertTrue("Host window must submit a frame", committed.await(8, java.util.concurrent.TimeUnit.SECONDS))
        val bitmap = android.graphics.Bitmap.createBitmap(size[0], size[1], android.graphics.Bitmap.Config.ARGB_8888)
        val copied = java.util.concurrent.CountDownLatch(1)
        var status = -1
        android.view.PixelCopy.request(activity.window, bitmap, { status = it; copied.countDown() }, android.os.Handler(Looper.getMainLooper()))
        assertTrue("Committed host window must copy", copied.await(8, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(android.view.PixelCopy.SUCCESS, status)
        return bitmap
    }

    @Test fun configureActivitySavesItsChosenListAndPublishesItToTheHost() {
        Host().use { f ->
            NotesRuntime.enqueue { NotesStore(context).delete(f.id, true); NotesRuntime.refresh(context) }.get()
            val activity = instrumentation.startActivitySync(Intent(context, ConfigureActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, f.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ConfigureActivity
            var choice: TextView? = null
            try {
                await("Picker choice must render") {
                    onMain {
                        fun visit(v: View) {
                            if (v is TextView && v.text.toString() == "Kotatsu long list") choice = v
                            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
                        }
                        visit(activity.window.decorView)
                    }
                    choice != null
                }
                onMain { choice!!.performClick() }
                await("Chosen list must persist and render in host") {
                    NotesStore(context).selected(f.id) == 4L && f.checkText("Milk") != null
                }
                assertTrue(fixture("stats").getBoolean("titlesOnly"))
            } finally { onMain { activity.finish() } }
        }
    }
}
