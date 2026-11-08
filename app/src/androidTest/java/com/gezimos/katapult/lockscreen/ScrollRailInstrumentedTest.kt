package com.gezimos.katapult.lockscreen

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.MainActivity
import com.gezimos.katapult.PlatformUiDriver
import com.gezimos.katapult.R
import org.junit.Assert.*
import org.junit.Test

class ScrollRailInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun drag(track: Rect, down: Boolean) {
        val start = if (down) track.top + 2f else track.bottom - 2f
        val end = if (down) track.bottom - 2f else track.top + 2f
        val time = SystemClock.uptimeMillis()
        for (step in 0..30) {
            MotionEvent.obtain(time, SystemClock.uptimeMillis(), when (step) {
                0 -> MotionEvent.ACTION_DOWN; 30 -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE
            }, track.exactCenterX(), start + (end - start) * step / 30f, 0).also {
                it.source = InputDevice.SOURCE_TOUCHSCREEN
                instrumentation.sendPointerSync(it); it.recycle()
            }
            SystemClock.sleep(20)
        }
    }

    @Test fun dragOwnsItsTargetUntilDelayedConsumerFeedbackArrives() = exercise(false)
    @Test fun measuredRangeChangesDoNotCancelAnActiveDrag() = exercise(true)

    private fun exercise(feedback: Boolean) {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val metrics = mutableStateOf(ListRail(2000, 10000, 0))
        val commands = mutableListOf<Int>()
        var commandedMaximum = 10000
        try {
            main {
                activity.setContentView(ComposeView(activity).apply { setContent {
                    val rail = metrics.value
                    NotesScrollRail(rail.viewport, rail.maximum, rail.position, { value ->
                        commands.add(value)
                        commandedMaximum = rail.maximum
                        if (feedback) {
                            // Wrapped native rows change the visible extent during scrolling.
                            val extent = if (commands.size % 2 == 0) 2200 else 1800
                            metrics.value = ListRail(extent, 12000 - extent, value.coerceAtMost(12000 - extent))
                        }
                    })
                } })
            }
            val label = context.getString(R.string.edge_task_scroll)
            ui.await("Rail not rendered") { ui.find(label) != null }
            val track = Rect().also { ui.find(label)!!.getBoundsInScreen(it) }
            drag(track, true)
            main {
                assertTrue("Drag lost displacement: commands=$commands, metrics=${metrics.value}",
                    commands.lastOrNull()?.let { it == commandedMaximum } == true)
                // Publish authoritative feedback only between gestures in the delayed case.
                metrics.value = metrics.value.copy(position = metrics.value.maximum)
                commands.clear()
            }
            instrumentation.waitForIdleSync()
            drag(track, false)
            main { assertEquals("Reverse drag must reach the real top: $commands", 0, commands.last()) }
        } finally { main { activity.finish() } }
    }
}
