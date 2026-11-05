package com.gezimos.katapult

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.TaskListView
import com.gezimos.katapult.notes.*
import com.gezimos.katapult.ui.settings.TaskListPicker
import com.gezimos.katapult.ui.settings.ChapterHeader
import com.gezimos.katapult.ui.settings.SettingsHelp
import com.gezimos.katapult.ui.settings.PaperIndexRenderer
import com.gezimos.katapult.ui.settings.SettingsCategory
import com.gezimos.katapult.ui.settings.SettingsGroup
import com.gezimos.katapult.ui.settings.SettingsEntry
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class TaskListInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun capture(activity: MainActivity, name: String): Bitmap {
        instrumentation.waitForIdleSync()
        val committed = java.util.concurrent.CountDownLatch(1)
        val size = IntArray(2)
        instrumentation.runOnMainSync {
            val decor = activity.window.decorView
            size[0] = decor.width; size[1] = decor.height
            decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            decor.invalidate()
        }
        assertTrue("Window did not submit a frame", committed.await(8, java.util.concurrent.TimeUnit.SECONDS))
        val bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888)
        val copied = java.util.concurrent.CountDownLatch(1)
        var status = -1
        android.view.PixelCopy.request(activity.window, bitmap, { status = it; copied.countDown() }, android.os.Handler(Looper.getMainLooper()))
        assertTrue("Window buffer was not copied", copied.await(8, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(android.view.PixelCopy.SUCCESS, status)
        val run = InstrumentationRegistry.getArguments().getString("taskListRenderRun", "latest")!!
        require(run.matches(Regex("[A-Za-z0-9_-]+")))
        val directory = java.io.File(context.getExternalFilesDir(null), "task-list-renders/$run").apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use {
            assertTrue("Capture must encode PNG", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            it.flush(); it.fd.sync()
        }
        return bitmap
    }

    @Test fun allNineLocalesHaveTaskCopyAndBothNotesPermissionsAreDeclared() {
        val keys = listOf(R.string.edge_task_list, R.string.edge_task_untitled, R.string.edge_task_choose, R.string.edge_task_help,
            R.string.edge_task_missing, R.string.edge_task_no_lists, R.string.edge_task_close,
            R.string.edge_task_scroll_up, R.string.edge_task_scroll_down, R.string.edge_task_scroll)
        val english = keys.map(context::getString)
        listOf("en", "cs", "de", "es", "fr", "it", "nl", "pl", "pt").forEach { language ->
            val configuration = android.content.res.Configuration(context.resources.configuration).apply { setLocale(java.util.Locale(language)) }
            val localized = context.createConfigurationContext(configuration)
            keys.forEachIndexed { index, key ->
                assertTrue(localized.getString(key).isNotBlank())
                if (language != "en") assertNotEquals("$language $key", english[index], localized.getString(key))
            }
            val destination = localized.getString(R.string.edge_swipes)
            val missing = localized.getString(R.string.edge_task_missing, destination)
            assertTrue("$language missing-list path must name the actual page", missing.contains(destination))
            assertTrue("$language must not duplicate the page name", localized.getString(R.string.edge_task_missing, "renamed destination").contains("renamed destination"))
        }
        val requested = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toList()
        assertTrue("com.mudita.data.provider.permission.READ_NOTES" in requested)
        assertTrue("com.mudita.data.provider.permission.WRITE_NOTES" in requested)
    }

    private class Provider : ContentProvider() {
        var row: TaskListSnapshot? = TaskListSnapshot(42, "Shopping", """[{"text":"Milk","checked":false}]""", 100)
        var type = "CHECKLIST"
        var nullTitle = false
        var nullContent = false
        var extraChoice: TaskListChoice? = null
        var lastProjection = emptyList<String>()
        var lastArgs = emptyList<String>()
        var lastUri: Uri? = null
        var lastSelection: String? = null
        var lastValues: ContentValues? = null
        override fun onCreate() = true
        override fun getType(uri: Uri) = "vnd.android.cursor.dir/notes"
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            lastProjection = projection!!.toList(); lastArgs = selectionArgs!!.toList()
            lastUri = uri; lastSelection = selection
            val cursor = MatrixCursor(projection)
            val data = row
            if (data != null && type == "CHECKLIST" && (lastArgs.size == 1 || lastArgs.first() == data.id.toString())) {
                cursor.addRow(projection.map<String, Any?> { when (it) {
                    "id" -> data.id; "title" -> if (nullTitle) null else data.title
                    "content" -> if (nullContent) null else data.content; "updated_at" -> data.updatedAt
                    else -> error("Unexpected column")
                } }.toTypedArray())
            }
            if (lastProjection == listOf("id", "title")) extraChoice?.let { cursor.addRow(arrayOf(it.id, it.title)) }
            return cursor
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
            lastUri = uri; lastSelection = selection; lastArgs = selectionArgs!!.toList(); lastValues = values
            val old = row ?: return 0
            if (lastArgs != listOf(old.id.toString(), type, old.content, old.updatedAt.toString())) return 0
            row = old.copy(content = values!!.getAsString("content"), updatedAt = values.getAsLong("updated_at"))
            return 1
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri = error("No insert contract")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("No delete contract")
    }

    @Test fun providerUsesOnlyChecklistTitlesSelectedIdAndCollectionCas() {
        val provider = Provider()
        val notes = MuditaNotes(ContentResolver.wrap(provider))
        assertEquals(listOf(TaskListChoice(42, "Shopping")), notes.choices())
        assertEquals(listOf("id", "title"), provider.lastProjection)
        assertEquals("note_type = ?", provider.lastSelection)
        assertEquals(listOf("CHECKLIST"), provider.lastArgs)
        val old = notes.read(42)!!
        assertEquals("id = ? AND note_type = ?", provider.lastSelection)
        assertEquals(listOf("42", "CHECKLIST"), provider.lastArgs)
        assertEquals(1, notes.compareAndSet(old, "[]", 101))
        assertEquals(MuditaNotes.URI, provider.lastUri)
        assertEquals("id = ? AND note_type = ? AND content = ? AND updated_at = ?", provider.lastSelection)
        assertEquals(listOf("42", "CHECKLIST", old.content, "100"), provider.lastArgs)
        assertEquals(setOf("content", "updated_at"), provider.lastValues!!.keySet())
        assertEquals("[]", notes.read(42)!!.content)
        assertEquals(0, notes.compareAndSet(old, old.content, 102))
        assertNull(notes.read(43))
        provider.type = "TEXT"
        assertNull(notes.read(42))
        assertTrue(notes.choices()!!.isEmpty())
        provider.type = "CHECKLIST"; provider.row = old.copy(content = "malformed")
        assertNull(notes.read(42))
    }

    @Test fun nullTitleKeepsThePickerAndSelectedDocumentAvailableAndReadable() {
        val provider = Provider().apply { nullTitle = true; extraChoice = TaskListChoice(99, "Packing") }
        val notes = MuditaNotes(ContentResolver.wrap(provider))
        val choices = notes.choices()!!
        assertEquals(listOf(TaskListChoice(42, ""), TaskListChoice(99, "Packing")), choices)
        val selected = notes.read(42)!!
        assertEquals("", selected.title)
        assertEquals(listOf(TaskItem("Milk", false)), selected.items)
        provider.nullContent = true
        assertThrows(NullPointerException::class.java) { notes.read(42) }

        val activity = activity()
        var picked: Long? = null
        var page: TaskListView? = null
        try {
            instrumentation.runOnMainSync {
                activity.setContent { TaskListPicker(choices, null, { picked = it }, {}) }
            }
            val ui = PlatformUiDriver(instrumentation)
            val label = context.getString(R.string.edge_task_untitled)
            ui.click(label)
            ui.await("Untitled list did not remain selectable") { picked == 42L }
            instrumentation.runOnMainSync {
                page = TaskListView(activity, {}, {}, { _, _ -> false }, { _, _ -> false }).also {
                    activity.setContentView(it); it.render(selected); it.requestFocus()
                }
            }
            ui.await("Untitled list did not render its tasks") { ui.has(label) && ui.checked("Milk") == false }
        } finally { instrumentation.runOnMainSync { page?.dispose(); activity.finish() } }
    }

    @Test fun previewFramesInspectOnlyAtFirstAttachmentAndRecheckAfterCancellation() {
        val activity = activity()
        val added = mutableListOf<android.view.View>()
        lateinit var page: com.gezimos.katapult.lockscreen.TaskListPage
        var created = false
        var queries = 0
        var allowed = false
        val edge = com.gezimos.katapult.lockscreen.ScreenEdge.LEFT
        try {
            instrumentation.runOnMainSync {
                val manager = activity.getSystemService(android.view.WindowManager::class.java)
                // A test-owned application panel supplies a real window without enabling accessibility.
                val windowPort = object : android.view.WindowManager by manager {
                    override fun getMaximumWindowMetrics() = manager.maximumWindowMetrics
                    override fun addView(view: android.view.View, params: android.view.ViewGroup.LayoutParams) {
                        (params as android.view.WindowManager.LayoutParams).apply {
                            type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
                            token = activity.window.decorView.windowToken
                        }
                        manager.addView(view, params); added += view
                    }
                }
                // A service window has lifecycle/saved-state owners, but no Activity Back dispatcher.
                val owner = object : android.content.ContextWrapper(context),
                    androidx.lifecycle.LifecycleOwner by activity,
                    androidx.lifecycle.ViewModelStoreOwner by activity,
                    androidx.savedstate.SavedStateRegistryOwner by activity {
                    override val lifecycle get() = activity.lifecycle
                    override fun getSystemService(name: String): Any? =
                        if (name == android.content.Context.WINDOW_SERVICE) windowPort else super.getSystemService(name)
                }
                page = com.gezimos.katapult.lockscreen.TaskListPage(owner, android.os.Handler(Looper.getMainLooper()),
                    PageNotes(), { 42L }, { queries++; allowed }, {})
                created = true
                page.preview(edge, 0)
                assertEquals(0, queries)
                page.preview(edge, 1)
                assertEquals(1, queries); assertTrue(added.isEmpty())
                allowed = true
                page.preview(edge, 1)
                assertEquals(2, queries); assertEquals(1, added.size)
            }
            val ui = PlatformUiDriver(instrumentation)
            ui.await("Preview window did not attach") {
                var attached = false
                instrumentation.runOnMainSync { attached = added.single().isAttachedToWindow }
                attached
            }
            instrumentation.runOnMainSync {
                repeat(80) { page.preview(edge, it % 6 + 1) }
                assertEquals("MOVE preview frames must not inspect", 2, queries)
                page.cancelPreview()
                assertFalse(added.single().isAttachedToWindow)
                allowed = false
                page.preview(edge, 1)
                assertEquals(3, queries); assertEquals(1, added.size)
                allowed = true
                page.preview(edge, 1)
                assertEquals(4, queries); assertEquals(2, added.size)
            }
            ui.await("Recreated preview window did not attach") {
                var attached = false
                instrumentation.runOnMainSync { attached = added.last().isAttachedToWindow }
                attached
            }
            instrumentation.runOnMainSync {
                page.close()
                assertTrue(added.none { it.isAttachedToWindow })
            }
        } finally {
            instrumentation.runOnMainSync { if (created) page.close(); activity.finish() }
        }
    }

    private class PageNotes : NotesPort {
        @Volatile var current: TaskListSnapshot? = TaskListSnapshot(42, "Shopping",
            """[{"text":"Milk","checked":false},{"text":"Bread","checked":true}]""", 100)
        @Volatile var writes = 0
        var reject = false
        var observer: (() -> Unit)? = null
        var unregistered = false
        override fun choices() = listOf(TaskListChoice(42, "Shopping"))
        override fun read(id: Long): TaskListSnapshot? {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Provider read on main" }
            assertEquals(42L, id)
            return current
        }
        override fun compareAndSet(old: TaskListSnapshot, content: String, updatedAt: Long): Int {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Provider write on main" }
            writes++
            if (reject || old != current) return 0
            current = old.copy(content = content, updatedAt = updatedAt)
            return 1
        }
        override fun observe(changed: () -> Unit): AutoCloseable {
            observer = changed
            return AutoCloseable { observer = null; unregistered = true }
        }
    }

    private fun activity() = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
        setClassName(context.packageName, MainActivity::class.java.name); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }) as MainActivity

    private fun inkBounds(bitmap: Bitmap, region: Rect): Rect {
        val points = mutableListOf<Pair<Int, Int>>()
        for (y in region.top until region.bottom) for (x in region.left until region.right) {
            if ((bitmap.getPixel(x, y) and 0xffffff) < 0x808080) points += x to y
        }
        assertTrue("Expected visible ink inside $region", points.isNotEmpty())
        return Rect(points.minOf { it.first }, points.minOf { it.second },
            points.maxOf { it.first } + 1, points.maxOf { it.second } + 1)
    }

    private fun dragScreen(x: Float, from: Float, to: Float, durationMs: Long = 320) {
        val down = android.os.SystemClock.uptimeMillis()
        for (step in 0..20) {
            val action = when (step) { 0 -> android.view.MotionEvent.ACTION_DOWN; 20 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
            android.view.MotionEvent.obtain(down, android.os.SystemClock.uptimeMillis(), action, x,
                from + (to - from) * step / 20f, 0).also {
                it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.sendPointerSync(it); it.recycle()
            }
            android.os.SystemClock.sleep(durationMs / 20)
        }
    }

    private data class RenderedText(val bounds: Rect, val baseline: Float, val fontScale: Float,
                                    val style: TextStyle, val lines: Int, val textRight: Float)

    /** Read the consumer's measured text baseline, not a font-metric approximation or test hook. */
    private fun renderedText(root: android.view.View, label: String): RenderedText? {
        var result: RenderedText? = null
        instrumentation.runOnMainSync {
            fun text(node: SemanticsNode): SemanticsNode? {
                if (!node.layoutInfo.isPlaced) return null
                if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true) return node
                return node.children.firstNotNullOfOrNull(::text)
            }
            fun visit(view: android.view.View) {
                if (view is ViewRootForTest) {
                    text(view.semanticsOwner.unmergedRootSemanticsNode)?.let { node ->
                        val baseline = node.getAlignmentLinePosition(FirstBaseline)
                        val position = node.positionOnScreen
                        val layouts = mutableListOf<TextLayoutResult>()
                        val hasLayout = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true
                        if (hasLayout && layouts.size == 1 && baseline != AlignmentLine.Unspecified && node.size.width > 0 && node.size.height > 0) {
                            result = RenderedText(Rect(position.x.roundToInt(), position.y.roundToInt(),
                                (position.x + node.size.width).roundToInt(), (position.y + node.size.height).roundToInt()),
                                position.y + baseline, layouts.single().layoutInput.density.fontScale,
                                layouts.single().layoutInput.style, layouts.single().lineCount,
                                position.x + layouts.single().getLineRight(0))
                        }
                    }
                }
                if (result == null && view is android.view.ViewGroup) {
                    for (i in 0 until view.childCount) { visit(view.getChildAt(i)); if (result != null) break }
                }
            }
            visit(root)
        }
        return result
    }

    private fun awaitSettledText(ui: PlatformUiDriver, root: android.view.View, label: String, message: String) {
        var previous: Int? = null
        var stable = 0
        ui.await(message) {
            val position = renderedText(root, label)?.bounds?.top
            stable = if (position != null && position == previous) stable + 1 else 0
            previous = position
            stable >= 3
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun taskHeaderAndRowsMatchTheNotesSpecification() {
        val activity = activity()
        val ui = PlatformUiDriver(instrumentation)
        val heading = context.getString(R.string.edge_task_list)
        val back = context.getString(R.string.paper_back)
        val close = context.getString(R.string.edge_task_close)
        fun bounds(label: String) = Rect().also { ui.find(label)!!.getBoundsInScreen(it) }
        fun rowBounds(label: String) = Rect().also { ui.findAncestor(label) { node -> node.isCheckable }!!.getBoundsInScreen(it) }
        lateinit var page: TaskListView
        var frame: Bitmap? = null
        try {
            instrumentation.runOnMainSync {
                page = TaskListView(activity, {}, {}, { _, _ -> false }, { _, _ -> false })
                activity.setContentView(page)
                page.render(TaskListSnapshot(42, heading, TaskListJson.encode(listOf(
                    TaskItem("Try edge swipes", false), TaskItem("Check weather", true), TaskItem("Open camera", false))), 100))
                page.requestFocus()
            }
            ui.await("Task page absent") { ui.has("Try edge swipes") }
            frame = capture(activity, "ui-task-list-three-items")
            val taskHasBack = ui.has(back)
            val taskClose = ui.find(close)?.let { Rect().also(it::getBoundsInScreen) }
            val taskTitle = bounds(heading)
            val taskRow = rowBounds("Try edge swipes")
            val taskLabel = bounds("Try edge swipes")
            val taskText = renderedText(page, "Try edge swipes")!!
            val checkedText = renderedText(page, "Check weather")!!
            val secondRow = rowBounds("Check weather")
            var closed = false
            instrumentation.runOnMainSync {
                page.dispose()
                page = TaskListView(activity, { closed = true }, {}, { _, _ -> false }, { _, _ -> false })
                activity.setContentView(page); page.render(null); page.requestFocus()
            }
            ui.await("Unavailable state absent") { ui.has(context.getString(R.string.edge_task_missing, context.getString(R.string.edge_swipes))) }
            capture(activity, "ui-task-list-unavailable").recycle()
            val unavailableHasBack = ui.has(back)
            val unavailableClose = ui.find(close)?.let { Rect().also(it::getBoundsInScreen) }
            if (ui.has(close)) ui.click(close)
            instrumentation.runOnMainSync {
                page.dispose()
                activity.setContent {
                    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        TaskListPicker(listOf(TaskListChoice(42, "Try edge swipes"), TaskListChoice(99, "Packing")), 42, {}, {})
                    }
                }
            }
            ui.await("Picker absent") { ui.has("Packing") }
            capture(activity, "ui-task-list-picker").recycle()
            assertTrue("Picker keeps Settings Back", ui.has(back))
            assertFalse("Picker must not acquire task Close", ui.has(close))
            val pickerBack = bounds(back)
            val pickerTitle = bounds(heading)
            val pickerRow = Rect().also { ui.findAncestor("Try edge swipes") { node -> node.isCheckable }!!.getBoundsInScreen(it) }
            assertTrue("Picker keeps its ordinary row inset", pickerRow.left > taskRow.left)
            instrumentation.runOnMainSync {
                activity.setContent {
                    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        ChapterHeader(heading, SettingsHelp("Fixture help", heading, emptyList()), {}, {})
                    }
                }
            }
            ui.await("Settings help action absent") { ui.has("Fixture help") }
            assertEquals("Picker Back keeps normal Settings geometry", pickerBack, bounds(back))
            assertEquals("Picker title keeps the Settings text inset", pickerTitle.left, bounds(heading).left)
            assertEquals("Picker title keeps the Settings line height", pickerTitle.height(), bounds(heading).height())
            assertFalse("Task heading has no Back control", taskHasBack)
            assertFalse("Unavailable heading has no Back control", unavailableHasBack)
            assertNotNull("Task heading requires Close", taskClose)
            assertEquals("Close uses the Settings help action slot", bounds("Fixture help"), taskClose)
            assertEquals("Unavailable uses the same Close slot", taskClose, unavailableClose)
            assertTrue("Close invokes the native close owner", closed)
            assertEquals("Chapter heading vertical geometry", pickerTitle.top, taskTitle.top)
            assertEquals("Chapter heading line height", pickerTitle.height(), taskTitle.height())
            val density = context.resources.displayMetrics.density
            assertEquals("Heading keeps its 16 dp inset", 16 * density, taskTitle.left.toFloat(), 1f)
            assertEquals("Notes row starts at the screen edge", 0, taskRow.left)
            assertEquals("Notes text follows 6 dp inset and the padded checkbox", 54 * density, taskLabel.left.toFloat(), 2f)
            assertEquals("Notes natural row pitch is 50 dp with independently rounded children", 50 * density,
                (secondRow.top - taskRow.top).toFloat(), 2f)
            assertTrue("Full-row target stays at least 48 dp", taskRow.height() >= 48 * density - 1)
            assertEquals(23.sp, taskText.style.fontSize)
            assertEquals(28.sp, taskText.style.lineHeight)
            assertEquals(FontWeight.Medium, taskText.style.fontWeight)
            assertTrue("Close target width", taskClose!!.width() >= 48 * density - 1)
            assertTrue("Close target height", taskClose.height() >= 48 * density - 1)
            val closeInk = inkBounds(frame!!, taskClose)
            val gaps = listOf(closeInk.left - taskClose.left, taskClose.right - closeInk.right,
                closeInk.top - taskClose.top, taskClose.bottom - closeInk.bottom)
            assertTrue("Close drawing has uniform gaps: $gaps", gaps.max() - gaps.min() <= 1)
            val box = inkBounds(frame!!, Rect(taskRow.left, taskRow.top, taskLabel.left, taskRow.bottom))
            assertEquals("Notes checkbox ink is about 23.33 dp", 28 * 20f / 24 * density, box.width().toFloat(), 2f)
            assertEquals("First-line text and checkbox align", taskLabel.exactCenterY(), box.exactCenterY(), 2f)
            val checkedBox = inkBounds(frame!!, Rect(secondRow.left, secondRow.top, taskLabel.left, secondRow.bottom))
            fun blackPixels(region: Rect) = (region.top until region.bottom).sumOf { y ->
                (region.left until region.right).count { x -> (frame!!.getPixel(x, y) and 0xffffff) < 0x808080 }
            }
            assertTrue("Checked box has a black fill, not an empty outline", blackPixels(checkedBox) > blackPixels(box) * 1.5)
            assertTrue("Checked box contains a white check", blackPixels(checkedBox) < checkedBox.width() * checkedBox.height())
            assertEquals(taskText.style.fontWeight, checkedText.style.fontWeight)
            assertFalse("Notes leaves checked text unstruck", checkedText.style.textDecoration == androidx.compose.ui.text.style.TextDecoration.LineThrough)
            val empty = Rect(taskText.textRight.roundToInt() + 8, taskRow.top, taskRow.right - (24 * density).toInt(), taskRow.bottom)
            assertTrue("White-space probe must cover the old leader/control area", empty.width() > 0)
            assertTrue("Short task leaves no leader or trailing checkbox", (empty.top until empty.bottom).all { y ->
                (empty.left until empty.right).all { x -> (frame!!.getPixel(x, y) and 0xffffff) >= 0x808080 }
            })
            android.util.Log.i("TaskListGeometry", "close=$taskClose ink=$closeInk gaps=$gaps row=$taskRow second=$secondRow label=$taskLabel box=$box pickerBack=$pickerBack")
        } finally { frame?.recycle(); instrumentation.runOnMainSync { page.dispose(); activity.finish() } }
    }

    @Test fun settingsLeadersStayOnBaselineAndTaskRowsUseNotesTypographyAtBothScales() {
        val activity = activity()
        val ui = PlatformUiDriver(instrumentation)
        val density = context.resources.displayMetrics.density
        val gap = (7.5f * density + .5f).toInt()
        val observations = mutableListOf<Triple<String, Float, Float>>()
        var page: TaskListView? = null
        fun observe(root: android.view.View, label: String, right: Int, region: Rect, name: String, scale: Float) {
            ui.await("Measured text did not reach the consumer: $name") { renderedText(root, label) != null }
            val frame = capture(activity, name)
            try {
                val text = renderedText(root, label)!!
                assertEquals("Consumer must use the requested font scale", scale, text.fontScale, .001f)
                val leader = inkBounds(frame, Rect(text.bounds.right + gap, region.top, right - gap, region.bottom))
                observations += Triple(name, text.baseline, leader.exactCenterY())
                android.util.Log.i("TaskListBaseline", "$name nameBaseline=${text.baseline} leader=$leader text=${text.bounds} scale=${text.fontScale}")
            } finally { frame.recycle() }
        }
        try {
            listOf(1f, 1.6f).forEach { scale ->
                val suffix = if (scale == 1f) "default" else "large"
                instrumentation.runOnMainSync {
                    page?.dispose(); page = null
                    activity.setContent {
                        CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                            Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                                PaperIndexRenderer(SettingsCategory("baseline", "Appearance", listOf(
                                    SettingsGroup("screen", "Screen", listOf(
                                        SettingsEntry.Metadata("theme", "Theme mode", "Light"),
                                        SettingsEntry.Metadata("status", "Status bar", "On"))))), {})
                            }
                        }
                    }
                }
                ui.await("Settings name absent") { renderedText(activity.window.decorView, "Theme mode") != null }
                val settingsName = renderedText(activity.window.decorView, "Theme mode")!!
                val settingsValue = renderedText(activity.window.decorView, "Light")!!
                observe(activity.window.decorView, "Theme mode", settingsValue.bounds.left,
                    settingsName.bounds, "ui-task-list-settings-baseline-$suffix", scale)
                instrumentation.runOnMainSync {
                    val configuration = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = scale }
                    page = TaskListView(activity.createConfigurationContext(configuration), {}, {}, { _, _ -> false }, { _, _ -> false }).also {
                        activity.setContentView(it)
                        it.render(TaskListSnapshot(42, "Shopping", TaskListJson.encode(listOf(
                            TaskItem("Milk", false), TaskItem("Bread", true))), 100))
                        it.requestFocus()
                    }
                }
                ui.await("Task name absent") { renderedText(page!!, "Milk") != null }
                val row = Rect().also { ui.findAncestor("Milk") { node -> node.isCheckable }!!.getBoundsInScreen(it) }
                val next = Rect().also { ui.findAncestor("Bread") { node -> node.isCheckable }!!.getBoundsInScreen(it) }
                val taskText = renderedText(page!!, "Milk")!!
                assertEquals(scale, taskText.fontScale, .001f)
                assertEquals(23.sp, taskText.style.fontSize)
                assertEquals(28.sp, taskText.style.lineHeight)
                assertEquals(FontWeight.Medium, taskText.style.fontWeight)
                assertFalse("Checked appearance does not strike out task text",
                    renderedText(page!!, "Bread")!!.style.textDecoration == androidx.compose.ui.text.style.TextDecoration.LineThrough)
                assertTrue("Task target stays at least 48 dp at $scale", row.height() >= 48 * density - 1)
                assertEquals("Natural rows stay adjacent at $scale", row.bottom, next.top)
                assertEquals("Notes first-line top padding", row.top + 11 * density, taskText.bounds.top.toFloat(), 2f)
                capture(activity, "ui-task-list-notes-font-$suffix").recycle()
            }
            observations.forEach { (name, baseline, stroke) ->
                assertEquals("Leader stroke must follow the rendered name baseline: $name", baseline, stroke, 1f)
            }
        } finally { instrumentation.runOnMainSync { page?.dispose(); activity.finish() } }
    }

    @Test fun longTaskTextWrapsWhileTitleAndPickerStaySingleLine() {
        val activity = activity()
        val ui = PlatformUiDriver(instrumentation)
        val longTitle = "A very long shopping list title that must end at the page edge instead of wrapping onto another line"
        val longItem = "Buy milk and bread and enough fruit for every breakfast this week without wrapping the task label"
        lateinit var page: TaskListView
        fun lineHeight(label: String): Int = Rect().also { ui.find(label)!!.getBoundsInScreen(it) }.height()
        try {
            instrumentation.runOnMainSync {
                page = TaskListView(activity, {}, {}, { _, _ -> false }, { _, _ -> false })
                activity.setContentView(page)
                page.render(TaskListSnapshot(42, longTitle, TaskListJson.encode(listOf(TaskItem(longItem, false), TaskItem("Already checked", true))), 100))
                page.requestFocus()
            }
            ui.await("Long task absent") { ui.has(longItem) }
            capture(activity, "ui-task-list-long-labels").recycle()
            val titleHeight = lineHeight(longTitle)
            val itemHeight = lineHeight(longItem)
            val itemLines = renderedText(page, longItem)!!.lines
            instrumentation.runOnMainSync {
                page.dispose()
                activity.setContent {
                    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        TaskListPicker(listOf(TaskListChoice(42, longTitle)), 42, {}, {})
                    }
                }
            }
            ui.await("Long picker label absent") { ui.has(longTitle) }
            capture(activity, "ui-task-list-picker-long-label").recycle()
            val scale = context.resources.displayMetrics.scaledDensity
            assertTrue("Task title must be one line: $titleHeight", titleHeight <= 32 * scale + 1)
            assertTrue("Notes task text wraps instead of truncating", itemLines > 1 && itemHeight > 28 * scale)
            assertTrue("Picker label must be one line", lineHeight(longTitle) <= 22 * scale + 1)
        } finally { instrumentation.runOnMainSync { page.dispose(); activity.finish() } }
    }

    private fun longShoppingItems() = listOf("Milk", "Eggs", "Bread", "Apples",
        "Ask the pharmacist about the allergy tablets for the weekend trip", "Rice", "Cheese", "Bananas", "Coffee",
        "Pick up the parcel from the post office before it closes", "Pasta", "Tomatoes", "Soap", "Tea", "Butter", "Yogurt")
        .map { TaskItem(it, false) }

    @Test fun longListsFillTheViewportScrollAndKeepTheirOriginalToggleIndices() {
        val activity = activity()
        val ui = PlatformUiDriver(instrumentation)
        val items = longShoppingItems()
        val next = context.getString(R.string.edge_task_scroll_down)
        val previous = context.getString(R.string.edge_task_scroll_up)
        var toggled = -1
        lateinit var page: TaskListView
        fun drag(x: Float, from: Float, to: Float) = dragScreen(x, from, to)
        try {
            instrumentation.runOnMainSync {
                page = TaskListView(activity, {}, { toggled = it },
                    { dx, dy -> com.gezimos.katapult.lockscreen.EdgeSwipe.capturesPageClose(
                        com.gezimos.katapult.lockscreen.ScreenEdge.LEFT, dx, dy, 8 * context.resources.displayMetrics.density) },
                    { dx, dy -> com.gezimos.katapult.lockscreen.EdgeSwipe.closesPage(
                        com.gezimos.katapult.lockscreen.ScreenEdge.LEFT, dx, dy, 480, 8 * context.resources.displayMetrics.density) })
                activity.setContentView(page)
                page.render(TaskListSnapshot(42, "Kotatsu long list", TaskListJson.encode(items), 100))
                page.requestFocus()
            }
            ui.await("Task list and rail absent") { ui.has("Milk") && ui.has(next) }
            val viewport = Rect().also { ui.find { node -> node.isScrollable }!!.getBoundsInScreen(it) }
            val seen = mutableSetOf<String>()
            fun observeVisible(): List<Rect> = items.mapNotNull { item ->
                val node = ui.findAncestor(item.text) { it.isCheckable } ?: return@mapNotNull null
                val row = Rect().also(node::getBoundsInScreen)
                if (!node.isVisibleToUser || row.isEmpty) return@mapNotNull null
                seen += item.text
                row
            }
            val initialRows = observeVisible()
            assertTrue("The Notes viewport is not capped at six settings", initialRows.count { viewport.contains(it) } >= 7)
            assertTrue("Rows use the bottom of the viewport", initialRows.maxOf { it.bottom } >= viewport.bottom - 2)
            assertFalse("Task list has no Settings next-page action", ui.has(context.getString(R.string.paper_next_page)))
            assertFalse("Top arrow remains visibly unavailable", ui.find(previous)!!.isEnabled)
            capture(activity, "ui-task-list-notes-long-top").recycle()
            val firstRow = Rect().also { ui.findAncestor("Milk") { node -> node.isCheckable }!!.getBoundsInScreen(it) }
            instrumentation.runOnMainSync {
                val down = android.os.SystemClock.uptimeMillis()
                val location = IntArray(2).also(page::getLocationOnScreen)
                for (step in 0..10) {
                    val action = when (step) { 0 -> android.view.MotionEvent.ACTION_DOWN; 10 -> android.view.MotionEvent.ACTION_UP; else -> android.view.MotionEvent.ACTION_MOVE }
                    android.view.MotionEvent.obtain(down, down + step * 20L, action,
                        300f - step * 10 - location[0], firstRow.exactCenterY() - location[1], 0).also {
                        it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                        page.dispatchTouchEvent(it); it.recycle()
                    }
                }
            }
            instrumentation.waitForIdleSync()
            assertTrue("Horizontal movement does not scroll or page the list", ui.has("Milk"))
            assertEquals("Horizontal drag is not also a toggle", -1, toggled)
            val riceTop = renderedText(page, "Rice")!!.bounds.top
            ui.click(next)
            ui.await("Arrow step must move the rendered consumer") { renderedText(page, "Rice")!!.bounds.top < riceTop }
            awaitSettledText(ui, page, "Rice", "Arrow step must settle")
            val distance = riceTop - renderedText(page, "Rice")!!.bounds.top
            assertEquals("Notes arrows move half the viewport", viewport.height() / 2f, distance.toFloat(), 1f)
            observeVisible()
            capture(activity, "ui-task-list-notes-long-next").recycle()
            ui.click("Bananas")
            assertEquals("Scrolled row retains its Notes index", 7, toggled)
            var steps = 1
            while (ui.find(next)!!.isEnabled) {
                val before = renderedText(page, "Rice")!!.bounds.top
                ui.click(next)
                ui.await("Each down step must move the consumer") { renderedText(page, "Rice")!!.bounds.top < before }
                awaitSettledText(ui, page, "Rice", "Down step must settle")
                observeVisible()
                assertTrue("Scrolling must reach the end", ++steps < 10)
            }
            assertEquals(items.map { it.text }.toSet(), seen)
            assertTrue(ui.find("Yogurt")!!.isVisibleToUser)
            capture(activity, "ui-task-list-notes-long-bottom").recycle()
            while (ui.find(previous)!!.isEnabled) {
                val before = renderedText(page, "Rice")!!.bounds.top
                ui.click(previous)
                ui.await("Each up step must move the consumer") { renderedText(page, "Rice")!!.bounds.top > before }
                awaitSettledText(ui, page, "Rice", "Up step must settle")
                assertTrue("Returning to the top must terminate", ++steps < 20)
            }
            assertTrue(ui.find("Milk")!!.isVisibleToUser)
            val track = Rect().also { ui.find(context.getString(R.string.edge_task_scroll))!!.getBoundsInScreen(it) }
            drag(track.exactCenterX(), track.top + 40f, track.top + 140f)
            ui.await("Thumb must move the consumer") { renderedText(page, "Rice")!!.bounds.top < riceTop }
            awaitSettledText(ui, page, "Rice", "Thumb drag must settle")
            assertTrue("Dragging the thumb moves the actual content", renderedText(page, "Rice")!!.bounds.top < riceTop)
            while (ui.find(previous)!!.isEnabled) {
                val before = renderedText(page, "Rice")!!.bounds.top
                ui.click(previous)
                ui.await("Returning after thumb drag must move the consumer") { renderedText(page, "Rice")!!.bounds.top > before }
                awaitSettledText(ui, page, "Rice", "Returning after thumb drag must settle")
                assertTrue("Returning after thumb drag must terminate", ++steps < 20)
            }
            drag(220f, viewport.bottom - 80f, viewport.top + 80f)
            ui.await("Continuous drag must move the consumer") { renderedText(page, "Rice")!!.bounds.top < riceTop }
            awaitSettledText(ui, page, "Rice", "Continuous drag and fling must settle")
            assertTrue("Finger drag moves the list continuously", renderedText(page, "Rice")!!.bounds.top < riceTop)
            assertEquals("Dragging must not toggle another task", 7, toggled)
            capture(activity, "ui-task-list-notes-long-drag").recycle()
            instrumentation.runOnMainSync { page.render(TaskListSnapshot(42, "Shorter list", TaskListJson.encode(items.take(2)), 200)) }
            ui.await("Rail must disappear when the shortened document fits") { !ui.has(next) && ui.has("Milk") }
        } finally { instrumentation.runOnMainSync { page.dispose(); activity.finish() } }
    }

    @Test fun onlyOpeningTheOwnedWindowAddsAFullScreenInversion() {
        val activity = activity()
        val ui = PlatformUiDriver(instrumentation)
        val notes = PageNotes().apply {
            current = TaskListSnapshot(42, "Kotatsu long list", TaskListJson.encode(longShoppingItems()), 100)
        }
        val inversions = java.util.concurrent.atomic.AtomicInteger()
        val observations = linkedMapOf<String, Int>()
        lateinit var root: TaskListView
        lateinit var page: com.gezimos.katapult.lockscreen.TaskListPage
        var created = false
        val edge = com.gezimos.katapult.lockscreen.ScreenEdge.LEFT
        val down = context.getString(R.string.edge_task_scroll_down)
        val up = context.getString(R.string.edge_task_scroll_up)
        fun record(name: String, label: String = "Rice") {
            awaitSettledText(ui, root, label, "Consumer position did not settle: $name")
            observations[name] = inversions.get()
        }
        try {
            instrumentation.runOnMainSync {
                val manager = activity.getSystemService(android.view.WindowManager::class.java)
                val windowPort = object : android.view.WindowManager by manager {
                    override fun getMaximumWindowMetrics() = manager.maximumWindowMetrics
                    override fun addView(view: android.view.View, params: android.view.ViewGroup.LayoutParams) {
                        root = view as TaskListView
                        root.setOnHierarchyChangeListener(object : android.view.ViewGroup.OnHierarchyChangeListener {
                            override fun onChildViewAdded(parent: android.view.View, child: android.view.View) {
                                val color = (child.background as? android.graphics.drawable.ColorDrawable)?.color
                                if (color == android.graphics.Color.BLACK && child.layoutParams.width == -1 && child.layoutParams.height == -1) {
                                    inversions.incrementAndGet()
                                }
                            }
                            override fun onChildViewRemoved(parent: android.view.View, child: android.view.View) = Unit
                        })
                        (params as android.view.WindowManager.LayoutParams).apply {
                            type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
                            token = activity.window.decorView.windowToken
                        }
                        manager.addView(view, params)
                    }
                }
                val owner = object : android.content.ContextWrapper(context),
                    androidx.lifecycle.LifecycleOwner by activity,
                    androidx.lifecycle.ViewModelStoreOwner by activity,
                    androidx.savedstate.SavedStateRegistryOwner by activity {
                    override val lifecycle get() = activity.lifecycle
                    override fun getSystemService(name: String): Any? =
                        if (name == android.content.Context.WINDOW_SERVICE) windowPort else super.getSystemService(name)
                }
                page = com.gezimos.katapult.lockscreen.TaskListPage(owner, android.os.Handler(Looper.getMainLooper()),
                    notes, { 42L }, { true }, {})
                created = true
                page.settle(edge)
            }
            ui.await("Owned task window did not render") { ui.has(down) && ui.has("Milk") }
            record("open")
            instrumentation.runOnMainSync { page.settle(edge) }
            record("repeat-settle")
            val initial = renderedText(root, "Rice")!!.bounds.top
            ui.click(down)
            ui.await("Arrow must move the consumer") { renderedText(root, "Rice")!!.bounds.top < initial }
            record("arrow-down")
            ui.click(up)
            ui.await("Up arrow must return the consumer") { renderedText(root, "Rice")!!.bounds.top == initial }
            record("arrow-up")
            val track = Rect().also { ui.find(context.getString(R.string.edge_task_scroll))!!.getBoundsInScreen(it) }
            dragScreen(track.exactCenterX(), track.top + 40f, track.top + 140f)
            ui.await("Thumb must move the consumer") { renderedText(root, "Rice")!!.bounds.top < initial }
            record("thumb-drag")
            val progress = android.os.Bundle().apply {
                putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 0f)
            }
            assertTrue(ui.find(context.getString(R.string.edge_task_scroll))!!.performAction(
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, progress))
            ui.await("Accessible progress must return to top") { renderedText(root, "Rice")!!.bounds.top == initial }
            record("accessible-progress")
            val viewport = Rect().also { ui.find { it.isScrollable }!!.getBoundsInScreen(it) }
            dragScreen(220f, viewport.bottom - 80f, viewport.top + 80f, 160)
            ui.await("Finger drag/fling must move the consumer") { renderedText(root, "Rice")!!.bounds.top < initial }
            record("finger-drag/fling")
            var steps = 0
            while (ui.find(down)!!.isEnabled) { ui.click(down); record("to-end-${steps++}"); assertTrue(steps < 10) }
            dragScreen(220f, viewport.bottom - 80f, viewport.top + 80f, 160)
            record("finger-at-end")
            instrumentation.runOnMainSync {
                notes.current = notes.current!!.copy(title = "Refreshed list", updatedAt = 200)
                notes.observer!!()
            }
            ui.await("Observer refresh must reach the title consumer") { ui.has("Refreshed list") }
            record("observer-same-position")
            instrumentation.runOnMainSync {
                notes.current = TaskListSnapshot(42, "Shorter list", TaskListJson.encode(longShoppingItems().take(2)), 300)
                notes.observer!!()
            }
            ui.await("Observer shrink must clamp the scroll and remove the rail") { ui.has("Milk") && !ui.has(down) }
            record("observer-clamp", "Milk")
            ui.click("Milk")
            ui.await("Check read-back must reach the consumer") { notes.writes == 1 && ui.checked("Milk") == true }
            record("checkbox-readback", "Milk")
            instrumentation.runOnMainSync { page.hide() }
            assertFalse(root.isAttachedToWindow)
            observations["close"] = inversions.get()
            instrumentation.runOnMainSync { page.settle(edge) }
            ui.await("Reopened window must read the persisted task") { ui.checked("Milk") == true }
            record("reopen", "Milk")
            android.util.Log.i("TaskListRefreshRule", observations.toString())
            observations.filterKeys { it != "reopen" }.forEach { (name, count) ->
                assertEquals("Only the initial open may add a full-screen inversion: $name", 1, count)
            }
            assertEquals("Reopen adds exactly one new opening inversion", 2, observations.getValue("reopen"))
        } finally { instrumentation.runOnMainSync { if (created) page.close(); activity.finish() } }
    }

    @Test fun pageOwnsCloseDragsBeforeTheLineAndDecidesOnceFromUp() {
        val activity = activity()
        lateinit var page: TaskListView
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val clicks = java.util.concurrent.atomic.AtomicInteger()
        var edge = com.gezimos.katapult.lockscreen.ScreenEdge.LEFT
        var checked = false
        fun snapshot() = TaskListSnapshot(42, "Shopping", TaskListJson.encode(listOf(TaskItem("Milk", checked))), 100)
        try {
            instrumentation.runOnMainSync {
                page = TaskListView(activity, { closes.incrementAndGet() }, {
                    clicks.incrementAndGet(); checked = !checked; page.render(snapshot())
                },
                    { dx, dy -> com.gezimos.katapult.lockscreen.EdgeSwipe.capturesPageClose(edge, dx, dy, 8f) }) { dx, dy ->
                    com.gezimos.katapult.lockscreen.EdgeSwipe.closesPage(edge, dx, dy, 480, 8f)
                }
                activity.setContentView(page)
                page.render(snapshot())
                page.requestFocus()
            }
            val ui = PlatformUiDriver(instrumentation)
            ui.await("Task row did not render") { ui.checked("Milk") == false }
            val row = Rect().also { ui.findAncestor("Milk") { node -> node.isCheckable }!!.getBoundsInScreen(it) }
            // Feed the real view hierarchy exact samples: the last MOVE is below the action line.
            fun gesture(moveDx: Float?, upDx: Float, moveDy: Float = 0f, upDy: Float = 0f,
                        terminal: Int = android.view.MotionEvent.ACTION_UP) {
                instrumentation.runOnMainSync {
                    val location = IntArray(2).also(page::getLocationOnScreen)
                    val x = 240f - location[0]
                    val y = row.exactCenterY() - location[1]
                    val down = android.os.SystemClock.uptimeMillis()
                    fun event(action: Int, dx: Float, dy: Float, elapsed: Long) {
                        android.view.MotionEvent.obtain(down, down + elapsed, action, x + dx, y + dy, 0).also {
                            it.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                            val handled = page.dispatchTouchEvent(it)
                            // ViewGroup can return false on the MOVE that cancels a child during interception.
                            if (action == android.view.MotionEvent.ACTION_DOWN) assertTrue("Page must accept DOWN", handled)
                            it.recycle()
                        }
                    }
                    event(android.view.MotionEvent.ACTION_DOWN, 0f, 0f, 0)
                    moveDx?.let { event(android.view.MotionEvent.ACTION_MOVE, it, moveDy, 100) }
                    event(terminal, upDx, upDy, 200)
                }
                instrumentation.waitForIdleSync()
            }
            com.gezimos.katapult.lockscreen.ScreenEdge.entries.forEach { from ->
                instrumentation.runOnMainSync { edge = from }
                val sign = if (from == com.gezimos.katapult.lockscreen.ScreenEdge.LEFT) -1f else 1f
                val previous = closes.get()
                gesture(sign * 80, sign * 159)
                assertEquals("159 px must stay open: $from", previous, closes.get())
                assertEquals("Short sideways drag must cancel the row click", 0, clicks.get())
                gesture(sign * 80, sign * 161)
                assertEquals("UP-only threshold crossing must close once: $from", previous + 1, closes.get())
                gesture(sign * 80, sign * 160)
                assertEquals("Exact inclusive line must close once: $from", previous + 2, closes.get())
                gesture(null, sign * 161)
                assertEquals("UP must own the decision even without a MOVE: $from", previous + 3, closes.get())
                gesture(sign * 80, sign * 161, upDy = 170f)
                assertEquals("Final vertical dominance must not close", previous + 3, closes.get())
                gesture(sign * 80, -sign * 161)
                assertEquals("Final reversed direction must not close", previous + 3, closes.get())
                gesture(sign * 80, sign * 161, terminal = android.view.MotionEvent.ACTION_CANCEL)
                assertEquals("Cancellation must not close", previous + 3, closes.get())
                assertEquals("No sideways sequence may also click its row", 0, clicks.get())
            }
            gesture(0f, 0f, moveDy = 20f, upDy = 30f)
            assertEquals("Vertical drag must not close", 6, closes.get())
            // A non-scrollable child may treat a vertical move within its row as a click.
            // Its ordinary gesture handling remains the child's responsibility.
            val previousClicks = clicks.get()
            var previousChecked = false
            instrumentation.runOnMainSync { previousChecked = checked }
            ui.await("Child state did not settle after vertical dispatch") { ui.checked("Milk") == previousChecked }
            ui.click("Milk")
            ui.await("Tap must still toggle the checkbox") {
                clicks.get() == previousClicks + 1 && ui.checked("Milk") == !previousChecked
            }
            assertEquals("Tap must not close", 6, closes.get())
        } finally {
            instrumentation.runOnMainSync { page.dispose(); activity.finish() }
        }
    }

    @Test fun pageConsumerReadsBackAcceptedAndRejectedChecksRefreshesAndClosesOnBack() {
        val activity = activity()
        val notes = PageNotes()
        lateinit var session: TaskListSession
        lateinit var page: TaskListView
        var closed = false
        try {
            instrumentation.runOnMainSync {
                page = TaskListView(activity, { closed = true }, { session.toggle(it) }, { _, _ -> false }, { _, _ -> false })
                session = TaskListSession(notes, 42) { page.render(it) }
                activity.setContentView(page)
                page.requestFocus()
                session.start()
            }
            val ui = PlatformUiDriver(instrumentation)
            ui.await("Checklist did not reach consumer") { ui.checked("Milk") == false && ui.checked("Bread") == true }
            val rowBounds = Rect().also { ui.findAncestor("Milk") { node -> node.isCheckable }!!.getBoundsInScreen(it) }
            val pageBounds = Rect()
            instrumentation.runOnMainSync { page.getGlobalVisibleRect(pageBounds) }
            assertTrue(rowBounds.height() >= 48 * context.resources.displayMetrics.density)
            val before = capture(activity, "task-page-before-check")
            ui.click("Milk")
            ui.await("Accepted check did not persist and render") {
                notes.writes == 1 && notes.current!!.items!![0].checked && ui.checked("Milk") == true
            }
            val after = capture(activity, "task-page-after-check")
            var changedPixels = 0
            var outsideRow = 0
            for (y in pageBounds.top until pageBounds.bottom) for (x in pageBounds.left until pageBounds.right) {
                if (before.getPixel(x, y) != after.getPixel(x, y)) {
                    changedPixels++
                    if (!rowBounds.contains(x, y)) outsideRow++
                }
            }
            assertTrue("Checking must change visible pixels", changedPixels > 0)
            assertEquals("Checking must not redraw other rows, title or paper", 0, outsideRow)
            before.recycle(); after.recycle()
            instrumentation.runOnMainSync { notes.reject = true }
            ui.click("Milk")
            ui.await("Stale CAS did not reconcile") { notes.writes == 2 && ui.checked("Milk") == true }
            instrumentation.runOnMainSync {
                notes.current = TaskListSnapshot(42, "Updated title", """[{"text":"New task","checked":false}]""", 500)
                notes.observer!!()
            }
            ui.await("Observer did not refresh title, text and shape") {
                ui.has("Updated title") && ui.checked("New task") == false && !ui.has("Milk")
            }
            capture(activity, "task-page-refreshed").recycle()
            instrumentation.runOnMainSync { notes.current = null; notes.observer!!() }
            ui.await("Missing list did not show Settings guidance") {
                ui.has(context.getString(R.string.edge_task_missing, context.getString(R.string.edge_swipes)))
            }
            capture(activity, "task-page-unavailable").recycle()
            instrumentation.runOnMainSync { assertTrue("Settled page must hold input focus", page.hasFocus()) }
            ui.back()
            ui.await("Root Back was not handled") { closed }
        } finally {
            instrumentation.runOnMainSync { session.close(); page.dispose(); activity.finish() }
        }
        assertTrue(notes.unregistered)
    }

    @Test fun pickerSelectsByIdAndBackLeavesTheSelectionUntouched() {
        val activity = activity()
        var selected = 42L
        var pops = 0
        try {
            instrumentation.runOnMainSync {
                activity.setContent {
                    TaskListPicker(listOf(TaskListChoice(42, "Shopping"), TaskListChoice(99, "Packing")), selected,
                        { selected = it }, { pops++ })
                }
            }
            val ui = PlatformUiDriver(instrumentation)
            ui.click("Packing")
            assertEquals(99L, selected); assertEquals(1, pops)
            capture(activity, "task-list-picker").recycle()
            ui.click(context.getString(R.string.paper_back))
            assertEquals(99L, selected); assertEquals(2, pops)
            instrumentation.runOnMainSync { activity.setContent { TaskListPicker(emptyList(), selected, {}, {}) } }
            ui.await("Empty picker guidance absent") { ui.has(context.getString(R.string.edge_task_no_lists)) }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
