package com.gezimos.katapult

import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.TaskListView
import com.gezimos.katapult.notes.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class TaskListRefreshInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val initial = TaskListSnapshot(42, "Shopping",
        TaskListJson.encode(listOf(TaskItem("Milk", false), TaskItem("Bread", false))), 100)

    private class ReadGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    private class Notes(@Volatile var current: TaskListSnapshot?) : NotesPort {
        val writes = AtomicInteger()
        @Volatile var writtenFrom: TaskListSnapshot? = null
        @Volatile var nextRead: ReadGate? = null
        var observer: (() -> Unit)? = null
        override fun choices() = emptyList<TaskListChoice>()
        override fun read(id: Long): TaskListSnapshot? {
            check(Looper.myLooper() != Looper.getMainLooper())
            assertEquals(42L, id)
            val row = current
            nextRead?.let { gate ->
                nextRead = null
                gate.entered.countDown()
                check(gate.release.await(8, TimeUnit.SECONDS)) { "Test did not release the provider read" }
            }
            return row
        }
        override fun compareAndSet(old: TaskListSnapshot, content: String, updatedAt: Long): Int {
            check(Looper.myLooper() != Looper.getMainLooper())
            writes.incrementAndGet(); writtenFrom = old
            if (old != current) return 0
            current = old.copy(content = content, updatedAt = updatedAt)
            return 1
        }
        override fun observe(changed: () -> Unit): AutoCloseable {
            observer = changed
            return AutoCloseable { observer = null }
        }
    }

    private inner class Page : AutoCloseable {
        val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
            setClassName(context.packageName, MainActivity::class.java.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }) as MainActivity
        val notes = Notes(initial)
        val ui = PlatformUiDriver(instrumentation)
        val deliveries = AtomicInteger()
        lateinit var session: TaskListSession
        lateinit var view: TaskListView
        var deferNextFrame = false

        init {
            instrumentation.runOnMainSync {
                view = TaskListView(activity, {}, { session.toggle(it) }, { _, _ -> false }, { _, _ -> false })
                session = TaskListSession(notes, 42) { snapshot ->
                    // The model delivery can precede a submitted frame. Hold exactly that frame,
                    // without preventing the session from accepting the observer's new snapshot.
                    if (deferNextFrame) deferNextFrame = false else view.render(snapshot)
                    deliveries.incrementAndGet()
                }
                activity.setContentView(view); view.requestFocus(); session.start()
            }
            ui.await("Initial frame missing") { ui.has(initial.title) && ui.checked("Milk") == false }
        }

        fun refresh(snapshot: TaskListSnapshot?, deferFrame: Boolean = false, gate: ReadGate? = null) {
            instrumentation.runOnMainSync {
                deferNextFrame = deferFrame
                notes.current = snapshot; notes.nextRead = gate; notes.observer!!()
            }
        }

        override fun close() {
            notes.nextRead?.release?.countDown()
            instrumentation.runOnMainSync { session.close(); view.dispose(); activity.finish() }
        }
    }

    @Test fun refreshedSnapshotCannotReinterpretATickFromThePreviousFrame() {
        val replacements = listOf(
            listOf(TaskItem("Eggs", false), TaskItem("Milk", false), TaskItem("Bread", false)),
            listOf(TaskItem("Bread", false)),
            listOf(TaskItem("Bread", false), TaskItem("Milk", false)),
            initial.items!!)
        Page().use { page ->
            replacements.forEachIndexed { index, items ->
                if (index > 0) {
                    page.refresh(initial)
                    page.ui.await("Initial frame not restored") { page.ui.has(initial.title) && page.ui.checked("Milk") == false }
                }
                val fresh = initial.copy(title = "Refresh $index", content = TaskListJson.encode(items), updatedAt = 101L + index)
                val before = page.deliveries.get()
                page.refresh(fresh, deferFrame = true)
                page.ui.await("Observer did not reach the session") { page.deliveries.get() == before + 1 }
                assertTrue("The user still sees the old frame", page.ui.has(initial.title))
                page.ui.click("Milk")
                page.ui.await("A refused tick must deliver the current snapshot again") { page.deliveries.get() == before + 2 }
                assertEquals("No task may change: $index", fresh, page.notes.current)
                assertEquals("No CAS attempt for an obsolete rendered snapshot: $index", 0, page.notes.writes.get())
                page.ui.await("Current snapshot must replace the old frame") {
                    page.ui.has(fresh.title) && page.ui.checked(items.first().text) == false
                }
                assertEquals("Removed rows must disappear", items.any { it.text == "Milk" }, page.ui.has("Milk"))
            }
        }
    }

    @Test fun aTickQueuedBehindARefreshMustRecheckItsRenderedSnapshotInsideTheLane() {
        val gate = ReadGate()
        Page().use { page ->
            try {
                val fresh = initial.copy(title = "Reordered", content = TaskListJson.encode(initial.items!!.reversed()), updatedAt = 101)
                val before = page.deliveries.get()
                page.refresh(fresh, gate = gate)
                assertTrue("Refresh did not enter its I/O lane", gate.entered.await(8, TimeUnit.SECONDS))
                page.ui.click("Milk")
                gate.release.countDown()
                page.ui.await("Queued tick did not reconcile") { page.deliveries.get() == before + 2 }
                assertEquals("Reject before any provider CAS attempt", 0, page.notes.writes.get())
                assertEquals(fresh, page.notes.current)
                page.ui.await("Consumer did not render the current order") { page.ui.has(fresh.title) && page.ui.checked("Bread") == false }
            } finally { gate.release.countDown() }
        }
    }

    @Test fun aCurrentFrameTickWritesOnlyTheSeenRowAndRendersReadBack() {
        Page().use { page ->
            page.ui.click("Milk")
            page.ui.await("Persisted tick did not reach the consumer") {
                page.deliveries.get() == 2 && page.ui.checked("Milk") == true
            }
            assertEquals(1, page.notes.writes.get())
            assertEquals(initial, page.notes.writtenFrom)
            assertEquals(listOf(TaskItem("Milk", true), TaskItem("Bread", false)), page.notes.current!!.items)
            assertEquals(false, page.ui.checked("Bread"))
        }
    }

    @Test fun contentAndTimestampIndependentlyIdentifyTheRenderedVersion() {
        listOf(
            initial.copy(content = TaskListJson.encode(listOf(TaskItem("Eggs", false)) + initial.items!!)),
            initial.copy(updatedAt = initial.updatedAt + 1)
        ).forEach { fresh ->
            Page().use { page ->
                page.refresh(fresh, deferFrame = true)
                page.ui.await("Changed version did not reach session") { page.deliveries.get() == 2 }
                page.ui.click("Milk")
                page.ui.await("Stale version did not reconcile") { page.deliveries.get() == 3 }
                assertEquals("Neither a content-only nor timestamp-only refresh may reinterpret a tick", fresh, page.notes.current)
                assertEquals(0, page.notes.writes.get())
                instrumentation.waitForIdleSync()
                page.ui.click("Milk")
                page.ui.await("The replacement frame must supply its own version") { page.ui.checked("Milk") == true }
                assertEquals(1, page.notes.writes.get())
                assertEquals(fresh, page.notes.writtenFrom)
                assertEquals(fresh.items!!.map { if (it.text == "Milk") it.copy(checked = true) else it }, page.notes.current!!.items)
            }
        }
    }

    @Test fun anUnavailableListRefusesAnOldTickAndReplacesTheOldRowsWithGuidance() {
        Page().use { page ->
            page.refresh(null, deferFrame = true)
            page.ui.await("Deletion did not reach session") { page.deliveries.get() == 2 }
            page.ui.click("Milk")
            page.ui.await("Unavailable state must be delivered again") { page.deliveries.get() == 3 }
            assertEquals(0, page.notes.writes.get())
            assertNull(page.notes.current)
            page.ui.await("Obsolete rows did not give way to missing-list guidance") {
                !page.ui.has("Milk") && page.ui.has(context.getString(R.string.edge_task_missing, context.getString(R.string.edge_swipes)))
            }
        }
    }

    @Test fun replacingRowsDuringAPressCannotRetargetItsRelease() {
        Page().use { page ->
            val bounds = Rect()
            page.ui.findAncestor("Milk") { it.isCheckable }!!.getBoundsInScreen(bounds)
            val down = SystemClock.uptimeMillis()
            fun pointer(action: Int) {
                instrumentation.runOnMainSync {
                    val location = IntArray(2).also(page.view::getLocationOnScreen)
                    MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                        bounds.exactCenterX() - location[0], bounds.exactCenterY() - location[1], 0).also {
                        it.source = InputDevice.SOURCE_TOUCHSCREEN
                        page.view.dispatchTouchEvent(it); it.recycle()
                    }
                }
            }
            pointer(MotionEvent.ACTION_DOWN)
            try {
                val fresh = initial.copy(title = "New rows", content = TaskListJson.encode(initial.items!!.reversed()), updatedAt = 101)
                page.refresh(fresh)
                page.ui.await("New rows did not render during the press") { page.ui.has(fresh.title) }
                val beforeRelease = page.deliveries.get()
                pointer(MotionEvent.ACTION_UP)
                // Queue a read behind any release callback. Observing its delivery (or an earlier
                // write's delivery) avoids mistaking a not-yet-started I/O write for cancellation.
                instrumentation.runOnMainSync { page.session.refresh() }
                page.ui.await("Release did not drain through the session lane") { page.deliveries.get() > beforeRelease }
                assertEquals("A press belongs to the rows that accepted DOWN", 0, page.notes.writes.get())
                assertEquals(fresh, page.notes.current)
                page.ui.click("Bread")
                page.ui.await("A new press on the current rows must still work") { page.ui.checked("Bread") == true }
                assertEquals(1, page.notes.writes.get())
            } finally { pointer(MotionEvent.ACTION_CANCEL) }
        }
    }
}
