package com.gezimos.katapult.lockscreen

import android.appwidget.AppWidgetHostView
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ListView
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EmptyWidgetSlotInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup)
        (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    private fun activity() = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    private fun note(method: String, arg: String? = null) = context.contentResolver.call(
        android.net.Uri.parse("content://com.mudita.notes/notes"), method, arg, null)!!

    private fun capture(activity: MainActivity, name: String, inspect: (android.graphics.Bitmap) -> Unit = {}) {
        instrumentation.waitForIdleSync()
        val committed = CountDownLatch(1)
        val size = IntArray(2)
        main {
            val decor = activity.window.decorView
            size[0] = decor.width; size[1] = decor.height
            decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            decor.invalidate()
        }
        assertTrue("Frame must commit before capture", committed.await(8, TimeUnit.SECONDS))
        val bitmap = android.graphics.Bitmap.createBitmap(size[0], size[1], android.graphics.Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var status = -1
        android.view.PixelCopy.request(activity.window, bitmap, { status = it; copied.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue(copied.await(8, TimeUnit.SECONDS))
        assertEquals(android.view.PixelCopy.SUCCESS, status)
        val dir = java.io.File(context.getExternalFilesDir(null), "notes061-renders").apply { mkdirs() }
        val output = java.io.File(dir, name)
        output.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        try { inspect(bitmap) } finally { bitmap.recycle() }
    }

    @Test fun emptyHostedWidgetIsSkippedOnWakeTimerAndBothSwipesAndCanBecomeNonemptyAgain() {
        WidgetHostTestFixture(context).use { f ->
            val provider = f.providers.singleProvider(instrumentation.context.packageName)
            val ids = List(2) { f.bind(provider) }
            val widgets = ids.map { LockscreenWidget(it, LockscreenWidgetSize.M) }
            val activity = activity()
            val visibility = LockscreenVisibility().apply { beginEpoch(); recheck(true) { false } }
            lateinit var slot: LockscreenWidgetSlot
            var timer: (() -> Unit)? = null
            var mode = WidgetRotation.WAKE
            fun render() = slot.show(widgets, mode, true, visibility)
            fun rows(id: Int, count: Int) = context.sendBroadcast(Intent(instrumentation.context.packageName + ".SIDE_ROWS")
                .setComponent(provider.provider).putExtra("id", id).putExtra("count", count))
            fun shown(id: Int?) {
                ui.await("Expected only widget $id") { var ready = false; main {
                    ready = slot.current?.id == id && slot.childCount == if (id == null) 0 else 1
                }; ready }
                main {
                    if (id != null) assertEquals(id, (slot.getChildAt(0) as AppWidgetHostView).appWidgetId)
                    ids.forEach { assertEquals(it == id, f.manager.getAppWidgetOptions(it).getBoolean(LockscreenWidgetHost.OPTION_HOST_VISIBLE)) }
                }
            }
            try {
                main {
                    slot = LockscreenWidgetSlot(activity, Handler(Looper.getMainLooper()), { render() }, { _, action ->
                        timer = action; AutoCloseable { if (timer === action) timer = null }
                    })
                    activity.setContentView(slot); render()
                }
                rows(ids[0], 1); rows(ids[1], -1)
                shown(ids[0])
                repeat(4) {
                    main { slot.hide(); HostedWidgetWake(context).begin(); slot.rotation.wake(); render() }; shown(ids[0])
                    main { slot.rotation.swipe(1) }; shown(ids[0])
                    main { slot.rotation.swipe(-1) }; shown(ids[0])
                }
                main { mode = WidgetRotation.SECONDS_30; render(); assertNull(timer) }
                rows(ids[1], 1)
                ui.await("Hidden widget must become eligible again") { var armed = false; main { armed = timer != null }; armed }
                main { timer!!.invoke() }; shown(ids[1])
                rows(ids[1], -1); shown(ids[0])
                rows(ids[0], -1); shown(null)
                main { slot.rotation.wake(); render(); slot.rotation.swipe(1); assertNull(timer) }; shown(null)
                rows(ids[1], 1); shown(ids[1])
            } finally { main { slot.stop(); activity.finish(); f.host.releaseViews() } }
        }
    }

    @Test fun shoppingTextNoteRendersInTheRealSideScreen() {
        WidgetHostTestFixture(context).use { f ->
            note("reset"); note("shopping")
            val provider = f.manager.installedProviders.single { it.provider.packageName == "com.alexindigo.kotatsu.notes.debug" &&
                it.provider.className.endsWith(".NotesWidgetProvider") }
            val id = f.bind(provider)
            context.startActivity(Intent().setComponent(ComponentName("com.alexindigo.kotatsu.notes.debug",
                "com.alexindigo.kotatsu.notes.ConfigureActivity")).putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ui.click("Note"); ui.click("Kotatsu shopping test")
            ui.back()
            val activity = activity()
            var side: SideScreenView? = null
            try {
                main {
                    side = SideScreenView(activity, SideScreen(name = "Shopping", slots = listOf(id, null)), {}, { _, _ -> false }, { _, _ -> false }, {}, {})
                    activity.setContentView(side); side!!.settled()
                }
                ui.await("Shopping must reach the side-screen collection") { var ready = false; main {
                    val list = descendants(side!!).filterIsInstance<ListView>().singleOrNull()
                    ready = list?.count == 10 && descendants(list).filterIsInstance<TextView>().any { it.text.toString() == "Natto 2" } && !list.isLayoutRequested
                }; ready }
                main {
                    val labels = descendants(side!!).filterIsInstance<TextView>()
                    assertEquals(2, labels.count { it.text.toString() in listOf("Farmers market", "Japanses store") })
                    assertEquals(6, descendants(side!!).filterIsInstance<android.widget.CheckBox>().size)
                }
                capture(activity, "shopping-side.png")
            } finally { main { side?.dispose(); activity.finish(); f.host.releaseViews() } }
        }
    }

    @Test fun visibleHostedWidgetWithAnEmptyQuoteIsTheOnlySelection() {
        WidgetHostTestFixture(context).use { f ->
            note("reset"); note("quote-edit", "  \n\t\n")
            val provider = f.providers.singleProvider(instrumentation.context.packageName)
            val quote = f.manager.installedProviders.single { it.provider.packageName == "com.alexindigo.kotatsu.notes.debug" &&
                it.provider.className.endsWith(".QuoteWidgetProvider") }
            val visibleId = f.bind(provider)
            val quoteId = f.bind(quote)
            context.sendBroadcast(Intent(instrumentation.context.packageName + ".SIDE_ROWS")
                .setComponent(provider.provider).putExtra("id", visibleId).putExtra("count", 1))
            context.startActivity(Intent().setComponent(ComponentName("com.alexindigo.kotatsu.notes.debug",
                "com.alexindigo.kotatsu.notes.QuoteConfigureActivity")).putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, quoteId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ui.click("Note"); ui.click("Kotatsu quotes"); ui.back()
            val widgets = listOf(visibleId, quoteId).map { LockscreenWidget(it, LockscreenWidgetSize.L) }
            val visibility = LockscreenVisibility().apply { beginEpoch(); recheck(true) { false } }
            val activity = activity()
            lateinit var slot: LockscreenWidgetSlot
            var mode = WidgetRotation.WAKE
            var timer: (() -> Unit)? = null
            fun render() = slot.show(widgets, mode, true, visibility)
            try {
                main {
                    // Same native theme as LockscreenWidgetService's BOX surface.
                    slot = LockscreenWidgetSlot(android.view.ContextThemeWrapper(activity, android.R.style.Theme_DeviceDefault_Light),
                        Handler(Looper.getMainLooper()), { render() }, { _, action ->
                            timer = action; AutoCloseable { if (timer === action) timer = null }
                        })
                    activity.setContentView(slot); render()
                }
                ui.await("The visible fixture must render beside the skipped blank quote") { var ready = false; main {
                    ready = slot.current?.id == visibleId && slot.childCount == 1 &&
                        descendants(slot).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "Hosted item 0" } &&
                        !slot.isLayoutRequested
                }; ready }
                main { mode = WidgetRotation.SECONDS_30; render() }
                ui.await("An empty quote must not keep a rotation timer armed") { var ready = false; main {
                    ready = timer == null && slot.current?.id == visibleId
                }; ready }
                main { mode = WidgetRotation.WAKE; render() }
                repeat(4) {
                    main {
                        slot.hide(); HostedWidgetWake(context).begin(); slot.rotation.wake(); render()
                        slot.rotation.swipe(1); slot.rotation.swipe(-1)
                        assertEquals(visibleId, slot.current?.id)
                        assertEquals(visibleId, (slot.getChildAt(0) as AppWidgetHostView).appWidgetId)
                        assertFalse(f.manager.getAppWidgetOptions(quoteId).getBoolean(LockscreenWidgetHost.OPTION_HOST_VISIBLE))
                    }
                }
                val contentBounds = android.graphics.Rect()
                main {
                    val child = slot.getChildAt(0)
                    val origin = IntArray(2).also(child::getLocationInWindow)
                    contentBounds.set(origin[0], origin[1], origin[0] + child.width, origin[1] + child.height)
                    assertTrue("Entire fixture must be in the window: $contentBounds", contentBounds.bottom <= activity.window.decorView.height)
                }
                capture(activity, "visible-fixture-empty-quote-slot.png") { frame ->
                    val ink = (contentBounds.top until contentBounds.bottom).sumOf { y ->
                        (contentBounds.left until contentBounds.right).count { x -> android.graphics.Color.alpha(frame.getPixel(x, y)) >= 128 && android.graphics.Color.red(frame.getPixel(x, y)) < 128 }
                    }
                    assertTrue("Committed frame must contain fixture text in $contentBounds, ink=$ink", ink > 100)
                }
                main { mode = WidgetRotation.SECONDS_30; render(); assertNull(timer) }
                note("quote-edit", "Appeared while hidden")
                ui.await("Hidden quote publication must restore rotation eligibility") { var ready = false; main { ready = timer != null }; ready }
                main { timer!!.invoke() }
                ui.await("The next rotation must show the newly populated quote") { var ready = false; main {
                    ready = slot.current?.id == quoteId && descendants(slot).filterIsInstance<TextView>()
                        .any { it.isShown && it.text.toString() == "Appeared while hidden" }
                }; ready }
            } finally { main { slot.stop(); activity.finish(); f.host.releaseViews() } }
        }
    }

    @Test fun detachedServiceCollectionBecomesEligibleFromDataChangedWithoutAnotherProviderUpdate() {
        WidgetHostTestFixture(context).use { f ->
            val provider = f.providers.singleProvider(instrumentation.context.packageName)
            val id = f.bind(provider)
            fun rows(count: Int, install: Boolean = false) = context.sendBroadcast(Intent(instrumentation.context.packageName + ".SIDE_SERVICE")
                .setComponent(provider.provider).putExtra("id", id).putExtra("count", count).putExtra("install", install))
            rows(0, true)
            val activity = activity()
            val visibility = LockscreenVisibility().apply { beginEpoch(); recheck(true) { false } }
            lateinit var slot: LockscreenWidgetSlot
            fun render() = slot.show(listOf(LockscreenWidget(id, LockscreenWidgetSize.M)), WidgetRotation.WAKE, true, visibility)
            try {
                main { slot = LockscreenWidgetSlot(activity, Handler(Looper.getMainLooper()), { render() }); activity.setContentView(slot); render() }
                ui.await("The empty service-backed collection must be skipped") { var ready = false; main { ready = slot.current == null && slot.childCount == 0 }; ready }
                rows(1)
                ui.await("Detached adapter connection/data callback must restore the widget") { var ready = false; main {
                    ready = slot.current?.id == id && descendants(slot).filterIsInstance<TextView>()
                        .any { it.isShown && it.text.toString() == "Deferred item 0" }
                }; ready }
            } finally { main { slot.stop(); activity.finish(); f.host.releaseViews() } }
        }
    }

    @Test fun contentClassificationUsesVisibleTextImagesAndControlsRatherThanPaperOrHiddenLabels() = main {
        val paper = android.widget.LinearLayout(context).apply { setBackgroundColor(android.graphics.Color.WHITE) }
        assertFalse(widgetHasContent(paper))
        val text = TextView(context).apply { setText(" \t\n") }
        paper.addView(text); assertFalse(widgetHasContent(paper))
        text.text = "Content"; assertTrue(widgetHasContent(paper))
        text.visibility = View.GONE; assertFalse(widgetHasContent(paper))
        val image = android.widget.ImageView(context)
        paper.addView(image); assertFalse(widgetHasContent(paper))
        image.setImageDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK)); assertTrue(widgetHasContent(paper))
        paper.visibility = View.INVISIBLE; assertFalse(widgetHasContent(paper))
        val list = ListView(context)
        assertFalse(widgetHasContent(list))
        list.adapter = android.widget.ArrayAdapter(context, android.R.layout.simple_list_item_1, listOf("Not laid out yet"))
        assertEquals(0, list.childCount)
        assertTrue("A populated detached collection must be attachable for its first row layout", widgetHasContent(list))
    }
}
