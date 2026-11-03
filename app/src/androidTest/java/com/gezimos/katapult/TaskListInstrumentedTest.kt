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
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.TaskListView
import com.gezimos.katapult.notes.*
import com.gezimos.katapult.ui.settings.TaskListPicker
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
        val directory = java.io.File(context.getExternalFilesDir(null), "task-list-renders").apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bitmap
    }

    @Test fun allNineLocalesHaveTaskCopyAndBothNotesPermissionsAreDeclared() {
        val keys = listOf(R.string.edge_task_list, R.string.edge_task_untitled, R.string.edge_task_choose, R.string.edge_task_help,
            R.string.edge_task_missing, R.string.edge_task_no_lists)
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
                val owner = object : android.content.ContextWrapper(activity),
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
