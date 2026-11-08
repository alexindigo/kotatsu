package com.alexindigo.kotatsu.notes

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class QuoteWidgetInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(work: () -> Unit) = instrumentation.runOnMainSync(work)
    private fun fixture(method: String, arg: String? = null) = context.contentResolver.call(MuditaNotes.URI, method, arg, null)!!
    private fun await(label: String, ready: () -> Boolean) = awaitWidgetHost(label, ready)
    private fun inkBottom(text: TextView): Int {
        val bounds = android.graphics.Rect()
        return (0 until text.lineCount).maxOf { line ->
            text.paint.getTextBounds(text.text.toString(), text.layout.getLineStart(line), text.layout.getLineEnd(line), bounds)
            text.layout.getLineBaseline(line) + bounds.bottom
        }
    }
    private inner class Host : AutoCloseable {
        val manager = AppWidgetManager.getInstance(context)
        val host = AppWidgetHost(context, 0x4b4e12)
        val activity = instrumentation.startActivitySync(Intent(context, NotesHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NotesHostActivity
        val id: Int
        lateinit var view: AppWidgetHostView
        init {
            fixture("reset")
            QuoteRuntime.clock = Clock.fixed(Instant.EPOCH, ZoneId.of("UTC"))
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            id = host.allocateAppWidgetId()
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, QuoteWidgetProvider::class.java)))
            main {
                view = host.createView(activity, id, manager.getAppWidgetInfo(id))
                view.setPadding(0, 0, 0, 0)
                activity.container.addView(view, FrameLayout.LayoutParams(-1, -1))
                host.startListening()
            }
            size(112)
            save(QuoteConfig(7))
            await("Text note must render in the actual host") { text() == "First quote" }
        }
        fun text(): String { var value = ""; main { value = view.findViewById<TextView>(R.id.quote_text)?.text?.toString().orEmpty() }; return value }
        fun author(): String { var value = ""; main { value = authorLineViews(view).joinToString("") { it.text.toString() } }; return value }
        fun save(config: QuoteConfig) = NotesRuntime.enqueue { QuoteStore(context).save(config); QuoteRuntime.refresh(context) }.get()
        fun options(token: Long, visible: Boolean = true) = manager.updateAppWidgetOptions(id, Bundle().apply {
            putLong(QuoteRuntime.HOST_WAKE, token); putBoolean("kotatsu.host.visible", visible)
        })
        fun size(height: Int) {
            val density = context.resources.displayMetrics.density
            main { view.layoutParams = FrameLayout.LayoutParams((300 * density).toInt(), (height * density).toInt()) }
            manager.updateAppWidgetOptions(id, Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 300)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
            })
        }
        fun observe(label: String, quote: String, author: String, height: Int, assertions: () -> Unit) {
            await(label) {
                var observed = false
                main {
                    val text = view.findViewById<TextView>(R.id.quote_text)
                    val lines = authorLineViews(view)
                    val slot = view.findViewById<View>(R.id.quote_author_slot)
                    if (text?.text?.toString() == quote && lines.joinToString("") { it.text.toString() } == author &&
                        !view.isLayoutRequested && view.height == (height * context.resources.displayMetrics.density).toInt() &&
                        text.layout != null && text.lineCount > 0 &&
                        text.layout.width == text.width - text.totalPaddingLeft - text.totalPaddingRight &&
                        (slot.visibility == View.VISIBLE) == author.isNotEmpty() &&
                        lines.all { it.layout != null && it.lineCount > 0 && it.width > 0 && !it.isLayoutRequested }) {
                        // Readiness and all geometry assertions are one main-thread observation.
                        assertions()
                        observed = true
                    }
                }
                observed
            }
        }
        override fun close() {
            main { host.stopListening(); host.deleteAppWidgetId(id); activity.finish() }
            NotesRuntime.enqueue { QuoteStore(context).delete(id); QuoteRuntime.refresh(context) }.get()
            QuoteRuntime.clock = null
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun wakePulseIsIdempotentAcrossRedrawsRestartedStoreAndSilentEditPickup() {
        Host().use { f ->
            f.save(QuoteConfig(7, QuoteFrequency.WAKE))
            f.options(100)
            await("First wake must select the next entry") { f.text() == "Second quote" }
            repeat(2) { f.options(100, false); f.options(100) }
            NotesRuntime.enqueue { QuoteRuntime.refresh(context) }.get()
            assertEquals(QuoteWake(100, 1), QuoteStore(context).wake(f.id))
            assertEquals("Second quote", f.text())
            fixture("quote-silent-edit", "Edited first\n\nEdited second\n\nEdited third\nNew author")
            f.options(101)
            await("Next wake must re-read the selected note") { f.text() == "Edited third" && f.author() == "New author" }
            f.options(99)
            NotesRuntime.enqueue { QuoteRuntime.refresh(context) }.get()
            assertEquals(QuoteWake(101, 2), QuoteStore(context).wake(f.id))
        }
    }

    @Test fun contentJobRearmsForQuoteOnlyWidgetsAndMissingNotesClearOldInk() {
        Host().use { f ->
            fixture("quote-edit", "Edited once\nAuthor")
            await("Notes content job must publish the edit") { f.text() == "Edited once" }
            fixture("quote-edit", "Edited twice")
            await("Successor job must publish a second edit") { f.text() == "Edited twice" && f.author().isEmpty() }
            fixture("quote-edit", null)
            await("Deleted note must leave blank paper, including after RemoteViews reapply") { f.text().isEmpty() && f.author().isEmpty() }
        }
    }

    @Test fun unreadableBeforeFirstUnlockIsBlankAndDoesNotReadNotes() {
        Host().use { f ->
            val old = NotesRuntime.unlocked
            val notes = QuoteRuntime.notes
            try {
                NotesRuntime.unlocked = { false }
                QuoteRuntime.notes = { error("Credential-protected Notes must not be opened") }
                NotesRuntime.enqueue { QuoteRuntime.refresh(context) }.get()
                await("Direct boot must clear the old quote") { f.text().isEmpty() && f.author().isEmpty() }
            } finally { NotesRuntime.unlocked = old; QuoteRuntime.notes = notes }
        }
    }

    @Test fun scheduledBoundaryChangesSelectionWhileOrdinaryRedrawsDoNot() {
        Host().use { f ->
            f.save(QuoteConfig(7, QuoteFrequency.HOURLY))
            QuoteRuntime.clock = Clock.fixed(Instant.ofEpochSecond(3599), ZoneId.of("UTC"))
            f.options(10)
            NotesRuntime.enqueue { QuoteRuntime.refresh(context) }.get()
            assertEquals("First quote", f.text())
            QuoteRuntime.clock = Clock.fixed(Instant.ofEpochSecond(3600), ZoneId.of("UTC"))
            context.sendBroadcast(Intent(context, QuoteWidgetProvider::class.java).setAction(QuoteRuntime.DEADLINE))
            await("Boundary alarm receiver must deliver the next period to the host") { f.text() == "Second quote" }
        }
    }

    @Test fun measuredLongQuoteAndAuthorFitEveryBoxWithoutEllipsisOrClippedLines() {
        Host().use { f ->
            val quote = "A long quote with measured wrapping and accented words, café. ".repeat(8).trim()
            fixture("quote-silent-edit", "$quote\nAn author with a longer name")
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) for (height in listOf(64, 112, 160)) {
                main { f.view.layoutDirection = direction }
                f.size(height)
                NotesRuntime.enqueue { QuoteRuntime.refresh(context) }.get()
                await("New $height dp size and full quote must settle") {
                    var ready = false
                    main { val text = f.view.findViewById<TextView>(R.id.quote_text)
                        ready = text?.text?.toString() == quote && !f.view.isLayoutRequested && text.layout != null &&
                            f.view.height == (height * context.resources.displayMetrics.density).toInt() &&
                            text.layout.width == text.width - text.totalPaddingLeft - text.totalPaddingRight }
                    ready
                }
                f.observe("Complete directional layout", quote, "An author with a longer name", height) {
                    val paper = f.view.findViewById<ViewGroup>(R.id.quote_paper)
                    val text = f.view.findViewById<TextView>(R.id.quote_text)
                    val author = f.view.findViewById<ViewGroup>(R.id.quote_author)
                    val lines = authorLineViews(f.view)
                    assertTrue(text.textSize > lines.first().textSize)
                    assertEquals(quote, text.text.toString())
                    assertEquals("An author with a longer name", lines.joinToString("") { it.text.toString() })
                    for (label in listOf(text) + lines) {
                        assertEquals(label.length(), label.layout.getLineEnd(label.lineCount - 1))
                        assertEquals(0, label.layout.getEllipsisCount(label.lineCount - 1))
                        assertTrue("All rendered glyphs must fit", label.height >= inkBottom(label) + label.paddingTop + label.paddingBottom)
                        assertTrue("No label may leave the paper", label.boundsIn(paper).bottom <= paper.height - paper.paddingBottom)
                    }
                    assertTrue(text.top >= paper.paddingTop)
                    assertTrue(author.boundsIn(paper).top >= text.bottom)
                    assertEquals("Full-width shared text lane", text.width, author.width)
                    val end = if (direction == View.LAYOUT_DIRECTION_RTL) Gravity.LEFT else Gravity.RIGHT
                    for (line in lines) {
                        val params = line.layoutParams as android.widget.LinearLayout.LayoutParams
                        assertEquals("The line's ink box resolves END using the view direction", end,
                            Gravity.getAbsoluteGravity(params.gravity, line.layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK)
                        val bounds = line.boundsIn(author)
                        if (direction == View.LAYOUT_DIRECTION_RTL) assertEquals(0, bounds.left)
                        else assertEquals(author.width, bounds.right)
                    }
                }
                capture(f.activity, "widget-quote-${height}-${if (direction == View.LAYOUT_DIRECTION_RTL) "rtl" else "ltr"}.png")
            }
        }
    }

    @Test fun shortTextKeepsItsNormalSizeAndOnlyLongTextShrinksInEveryBox() {
        Host().use { f ->
            val samples = listOf("Less, but better.", "A long quote with measured wrapping and accented words, café. ".repeat(16).trim())
            for (height in listOf(64, 112, 160)) for ((index, quote) in samples.withIndex()) {
                fixture("quote-silent-edit", "$quote\nDieter Rams")
                f.size(height)
                NotesRuntime.enqueue { QuoteRuntime.refresh(context) }.get()
                await("Normal-size $height dp sample$index must reach the host") {
                    var ready = false
                    main { val text = f.view.findViewById<TextView>(R.id.quote_text)
                        ready = text?.text?.toString() == quote && !f.view.isLayoutRequested && text.layout != null &&
                            f.view.height == (height * context.resources.displayMetrics.density).toInt() &&
                            text.layout.width == text.width - text.totalPaddingLeft - text.totalPaddingRight }
                    ready
                }
                f.observe("Complete normal-size layout", quote, "Dieter Rams", height) {
                    val text = f.view.findViewById<TextView>(R.id.quote_text)
                    val author = authorLineViews(f.view).first()
                    val paper = f.view.findViewById<ViewGroup>(R.id.quote_paper)
                    val normal = 21f * context.resources.displayMetrics.scaledDensity
                    if (index == 0) assertEquals("Short quote uses its normal token, never fills the box", normal, text.textSize, .001f)
                    else assertTrue("Long quote only shrinks", text.textSize < normal)
                    assertEquals(text.textSize * (2f / 3f), author.textSize, .001f)
                    for (label in listOf(text, author)) {
                        assertEquals(label.length(), label.layout.getLineEnd(label.lineCount - 1))
                        assertEquals(0, label.layout.getEllipsisCount(label.lineCount - 1))
                        assertTrue(label.height >= inkBottom(label) + label.paddingTop + label.paddingBottom)
                        val bounds = label.boundsIn(paper)
                        assertTrue(bounds.top >= paper.paddingTop && bounds.bottom <= paper.height - paper.paddingBottom)
                    }
                    android.util.Log.i("QuoteNormalSize", "heightDp=$height sample=$index normalPx=$normal quotePx=${text.textSize} linePx=${text.lineHeight} authorPx=${author.textSize} lines=${text.lineCount}")
                }
                capture(f.activity, "widget-quote-normal-$height-${if (index == 0) "short" else "long"}.png")
            }
        }
    }

    @Test fun lastLineBottomIsFixedAcrossQuoteLengthsWithAndWithoutAuthorsInEveryBox() {
        Host().use { f ->
            val samples = listOf("Less, but better.",
                "Time is not found. It is made, one deliberate choice at a time, by leaving room in the day for the things that matter most.")
            for (height in listOf(64, 112, 160)) {
                var firstBottom: Float? = null
                for (withAuthor in listOf(true, false)) for ((index, quote) in samples.withIndex()) {
                    fixture("quote-silent-edit", quote + if (withAuthor) "\nStaging collection" else "")
                    f.size(height)
                    NotesRuntime.enqueue { QuoteRuntime.refresh(context) }.get()
                    await("Bottom-anchored $height dp sample$index author=$withAuthor must settle") {
                        var ready = false
                        main {
                            val text = f.view.findViewById<TextView>(R.id.quote_text)
                            val author = f.view.findViewById<View>(R.id.quote_author_slot)
                            val lines = authorLineViews(f.view)
                            ready = text?.text?.toString() == quote && !f.view.isLayoutRequested && text.layout != null &&
                                f.view.height == (height * context.resources.displayMetrics.density).toInt() &&
                                (author.visibility == View.VISIBLE) == withAuthor &&
                                (!withAuthor || lines.isNotEmpty() && lines.last().layout != null)
                        }
                        ready
                    }
                    f.observe("Complete bottom-anchored layout", quote, if (withAuthor) "Staging collection" else "", height) {
                        val paper = f.view.findViewById<ViewGroup>(R.id.quote_paper)
                        val text = f.view.findViewById<TextView>(R.id.quote_text)
                        val author = f.view.findViewById<View>(R.id.quote_author_slot)
                        val last = if (withAuthor) authorLineViews(f.view).last() else text
                        val paperAt = IntArray(2).also(paper::getLocationInWindow)
                        val lastAt = IntArray(2).also(last::getLocationInWindow)
                        val bottom = (lastAt[1] - paperAt[1] + last.totalPaddingTop + inkBottom(last)).toFloat()
                        assertEquals("Last rendered line reaches the unchanged bottom inset",
                            (paper.height - paper.paddingBottom).toFloat(), bottom, 1f)
                        firstBottom?.let { assertEquals("Quote length and author presence cannot move the last line", it, bottom, 1f) }
                            ?: run { firstBottom = bottom }
                        assertEquals(last.length(), last.layout.getLineEnd(last.lineCount - 1))
                        assertEquals(0, last.layout.getEllipsisCount(last.lineCount - 1))
                        assertTrue(text.top >= paper.paddingTop)
                        if (withAuthor) assertTrue(author.top >= text.bottom)
                        android.util.Log.i("QuoteBottomAnchor", "heightDp=$height sample=$index author=$withAuthor bottomPx=$bottom paddingPx=${paper.paddingBottom}")
                    }
                    capture(f.activity, "widget-quote-bottom-$height-${if (index == 0) "short" else "long"}-${if (withAuthor) "author" else "anonymous"}.png")
                }
            }
        }
    }

    private fun capture(activity: NotesHostActivity, name: String) {
        instrumentation.waitForIdleSync()
        val committed = java.util.concurrent.CountDownLatch(1)
        val size = IntArray(2)
        main {
            val decor = activity.window.decorView
            size[0] = decor.width; size[1] = decor.height
            decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            decor.invalidate()
        }
        assertTrue("Quote host must submit its frame", committed.await(8, java.util.concurrent.TimeUnit.SECONDS))
        val bitmap = android.graphics.Bitmap.createBitmap(size[0], size[1], android.graphics.Bitmap.Config.ARGB_8888)
        val copied = java.util.concurrent.CountDownLatch(1)
        var status = -1
        android.view.PixelCopy.request(activity.window, bitmap, { status = it; copied.countDown() },
            android.os.Handler(android.os.Looper.getMainLooper()))
        assertTrue("Quote host frame must copy", copied.await(8, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(android.view.PixelCopy.SUCCESS, status)
        val directory = java.io.File(context.getExternalFilesDir(null), "notes-renders").apply { mkdirs() }
        java.io.File(directory, name).outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun textPickerSavesARealSelectionAndAllNineLocalesContainQuoteSettings() {
        Host().use { f ->
            assertEquals(listOf(TaskListChoice(7, "Kotatsu quotes")), MuditaNotes(context.contentResolver).textChoices())
            assertTrue(fixture("stats").getBoolean("textTitlesOnly"))
            NotesRuntime.enqueue {
                context.createDeviceProtectedStorageContext().getSharedPreferences("quote-widgets", 0).edit().clear().commit()
                QuoteRuntime.refresh(context)
            }.get()
            val activity = instrumentation.startActivitySync(Intent(context, QuoteConfigureActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, f.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as QuoteConfigureActivity
            val ui = NotesSettingsUi(instrumentation)
            fun click(label: String) = ui.click(label)
            try {
                click(context.getString(R.string.settings_note)); click("Kotatsu quotes")
                await("Picker choice must persist and render") { QuoteStore(context).config()?.noteId == 7L && f.text() == "First quote" }
                click(context.getString(R.string.settings_changes))
                click(context.getString(R.string.quote_on_wake))
                await("Frequency must persist") { QuoteStore(context).config()?.frequency == QuoteFrequency.WAKE }
                click(context.getString(R.string.quote_order))
                click(context.getString(R.string.quote_shuffle))
                await("Order must persist") { QuoteStore(context).config()?.order == QuoteOrder.SHUFFLE }
            } finally { main { activity.finish() } }
            val keys = listOf(R.string.quote_label, R.string.quote_description, R.string.choose_note, R.string.choose_quotes,
                R.string.no_text_notes, R.string.untitled_note, R.string.quote_frequency, R.string.quote_order,
                R.string.quote_daily, R.string.quote_hourly, R.string.quote_on_wake, R.string.quote_rotation,
                R.string.quote_shuffle, R.string.quote_random, R.string.quote_done, R.string.quote_frequency_value,
                R.string.quote_order_value, R.string.quote_widget_name)
            for (language in listOf("en", "cs", "de", "es", "fr", "it", "nl", "pl", "pt")) {
                val localized = context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
                    setLocale(java.util.Locale(language))
                })
                keys.forEach { assertTrue(localized.getString(it).isNotBlank()) }
            }
        }
    }
}
