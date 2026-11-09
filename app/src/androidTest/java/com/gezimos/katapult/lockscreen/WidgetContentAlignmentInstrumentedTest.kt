package com.gezimos.katapult.lockscreen
import com.gezimos.katapult.ui.settings.ScrollRailGeometry

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.appwidget.AppWidgetHostView
import android.widget.ListView
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WidgetContentAlignmentInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun bounds(view: View): Rect {
        val origin = IntArray(2).also(view::getLocationInWindow)
        return Rect(origin[0], origin[1], origin[0] + view.width, origin[1] + view.height)
    }
    private fun capture(activity: MainActivity): Bitmap {
        instrumentation.waitForIdleSync()
        val committed = CountDownLatch(1)
        val size = IntArray(2)
        main {
            val decor = activity.window.decorView
            size[0] = decor.width; size[1] = decor.height
            decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            decor.invalidate()
        }
        assertTrue("Alignment frame must commit", committed.await(8, TimeUnit.SECONDS))
        val bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888)
        val copied = CountDownLatch(1)
        var status = -1
        android.view.PixelCopy.request(activity.window, bitmap, { status = it; copied.countDown() }, android.os.Handler(Looper.getMainLooper()))
        assertTrue(copied.await(8, TimeUnit.SECONDS))
        assertEquals(android.view.PixelCopy.SUCCESS, status)
        return bitmap
    }
    private fun inkTop(frame: Bitmap, area: Rect): Int {
        for (y in area.top until area.bottom) for (x in area.left until area.right) {
            val pixel = frame.getPixel(x, y)
            if (android.graphics.Color.red(pixel) < 245 && android.graphics.Color.green(pixel) < 245 && android.graphics.Color.blue(pixel) < 245) return y
        }
        error("No drawing in $area")
    }
    private fun inkBottom(frame: Bitmap, area: Rect): Int {
        for (y in area.bottom - 1 downTo area.top) for (x in area.left until area.right) {
            val pixel = frame.getPixel(x, y)
            if (android.graphics.Color.red(pixel) < 245 && android.graphics.Color.green(pixel) < 245 && android.graphics.Color.blue(pixel) < 245) return y + 1
        }
        error("No drawing in $area")
    }

    @Test fun hostedViewsFillTheClippedContentAreaInFullAndBothSplitCellsWithAndWithoutRails() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider(instrumentation.context.packageName)
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            var root: SideScreenView? = null
            try {
                val ids = List(2) { fixture.bind(info) }
                for (hasRail in listOf(true, false)) {
                    val count = if (hasRail) 24 else 1
                    ids.forEach { id -> context.sendBroadcast(Intent(instrumentation.context.packageName + ".SIDE_ROWS")
                        .setComponent(info.provider).putExtra("id", id).putExtra("count", count)) }
                    for (layout in SideLayout.entries) {
                        main {
                            root?.dispose()
                            root = SideScreenView(activity, SideScreen(layout = layout, slots = ids), {}, { _, _ -> false }, { _, _ -> false }, {}, {})
                            activity.setContentView(root); root!!.settled()
                        }
                        ui.await("Widget $layout rail=$hasRail did not settle") {
                            var ready = false
                            main {
                                val slots = descendants(root!!).filterIsInstance<HostedWidgetSlot>()
                                ready = slots.size == layout.cells && slots.all { slot ->
                                    val list = descendants(slot).filterIsInstance<ListView>().singleOrNull()
                                    list?.count == count && list.childCount > 0 && !list.isLayoutRequested && !slot.isLayoutRequested &&
                                        (slot.rail != null) == hasRail
                                }
                            }
                            ready
                        }
                        val frame = capture(activity)
                        main {
                            val density = Density(context.resources.displayMetrics.density)
                            descendants(root!!).filterIsInstance<HostedWidgetSlot>().forEachIndexed { cell, slot ->
                                val origin = bounds(slot)
                                val widget = descendants(slot).filterIsInstance<AppWidgetHostView>().single()
                                val content = bounds(widget)
                                assertTrue(slot.clipChildren && slot.clipToPadding)
                                val target = if (hasRail) inkTop(frame, Rect(origin.right, origin.top,
                                    origin.right + with(density) { ScrollRailGeometry.arrowCell.roundToPx() },
                                    origin.top + with(density) { ScrollRailGeometry.arrowCell.roundToPx() })).toFloat()
                                else origin.top + ScrollRailGeometry.topTip(density)
                                val bottom = if (hasRail) inkBottom(frame, Rect(origin.right,
                                    origin.bottom - with(density) { ScrollRailGeometry.arrowCell.roundToPx() },
                                    origin.right + with(density) { ScrollRailGeometry.arrowCell.roundToPx() }, origin.bottom)).toFloat()
                                else origin.top + ScrollRailGeometry.bottomTip(slot.height, density)
                                assertEquals("$layout cell$cell rail=$hasRail content=$content up=$target",
                                    target, content.top.toFloat(), 1f)
                                assertEquals("$layout cell$cell rail=$hasRail content=$content down=$bottom",
                                    bottom, content.bottom.toFloat(), 1f)
                                assertEquals(origin.left, content.left)
                                assertEquals(origin.right, content.right)
                            }
                        }
                        frame.recycle()
                    }
                }
            } finally {
                main { root?.dispose(); activity.finish(); fixture.host.releaseViews() }
            }
        }
    }
}
