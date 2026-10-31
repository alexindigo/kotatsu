package com.gezimos.katapult.lockscreen

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import android.widget.RemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.WidgetHostTestFixture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetPictureInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun creatingAViewInAVisibleEpochCannotPublishAHiddenHint() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.singleProvider("com.alexindigo.kotatsu.weather.debug")
            val widget = LockscreenWidget(fixture.bind(info), LockscreenWidgetSize.M)
            var result: Result<Unit>? = null
            try {
                instrumentation.runOnMainSync { result = runCatching {
                    val state = LockscreenVisibility().apply { beginEpoch(); recheck(true) { false } }
                    LockscreenWidgetHost.pulseVisible(context, state, listOf(widget))
                    fixture.host.createWidgetView(context, widget, info)
                    assertTrue("Creation must retain this epoch's visible hint",
                        fixture.manager.getAppWidgetOptions(widget.id).getBoolean(LockscreenWidgetHost.OPTION_HOST_VISIBLE))
                } }
                result!!.getOrThrow()
            } finally { instrumentation.runOnMainSync { fixture.host.hideWidgets(context); fixture.host.releaseViews() } }
        }
    }

    @Test fun android12ReappliesTheLastVisibleRemoteViewsSynchronouslyWithoutRenewingItsAge() {
        WidgetHostTestFixture(context).use { fixture ->
            val weather = "com.alexindigo.kotatsu.weather.debug"
            val info = fixture.providers.singleProvider(weather)
            val id = fixture.bind(info)
            val resources = context.packageManager.getResourcesForApplication(weather)
            val layout = resources.getIdentifier("widget_timeline", "layout", weather)
            val image = resources.getIdentifier("timeline", "id", weather)
            var now = 0L
            val before = fixture.host.pictureClock
            fixture.host.pictureClock = { now }
            try {
                instrumentation.runOnMainSync {
                    val view = fixture.host.createView(context, id, info) as UnpaddedHostView
                    fun picture(color: Int) = RemoteViews(weather, layout).apply {
                        setImageViewBitmap(image, Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(color) })
                    }
                    fun color() = (view.findViewById<ImageView>(image).drawable as BitmapDrawable).bitmap.getPixel(0, 0)
                    view.hostVisibility(true)
                    view.updateAppWidget(picture(Color.BLACK))
                    view.hostVisibility(false)
                    now = 3600_000
                    view.updateAppWidget(picture(Color.GRAY))
                    view.hostVisibility(true)
                    assertEquals("Replay applied before returning to caller", Color.BLACK, color())
                    view.hostVisibility(false)
                    now = 5 * 3600_000
                    view.updateAppWidget(picture(Color.GRAY))
                    view.hostVisibility(true)
                    assertEquals("Hidden receipt/replay did not rejuvenate black", Color.GRAY, color())
                    view.updateAppWidget(picture(Color.BLUE))
                    assertEquals("Fresh provider picture replaces retained one", Color.BLUE, color())
                }
            } finally {
                fixture.host.pictureClock = before
                instrumentation.runOnMainSync { fixture.host.releaseViews() }
            }
        }
    }

    @Test fun standardClockProviderMayIgnoreTheOptionalVisibilityHint() {
        WidgetHostTestFixture(context).use { fixture ->
            val info = fixture.providers.firstProvider("com.android.deskclock")
            val widget = LockscreenWidget(fixture.bind(info), LockscreenWidgetSize.M)
            try {
                instrumentation.runOnMainSync {
                    fixture.host.listen(true)
                    val view = fixture.host.createView(context, widget.id, info)
                    val state = LockscreenVisibility().apply { beginEpoch(); recheck(true) { false } }
                    LockscreenWidgetHost.pulseVisible(context, state, listOf(widget))
                    assertTrue(fixture.manager.getAppWidgetOptions(widget.id).getBoolean(LockscreenWidgetHost.OPTION_HOST_VISIBLE))
                    assertTrue(view.childCount > 0)
                    fixture.host.hideWidgets(context)
                    assertFalse(fixture.manager.getAppWidgetOptions(widget.id).getBoolean(LockscreenWidgetHost.OPTION_HOST_VISIBLE, true))
                    LockscreenWidgetHost.pulseVisible(context, state, listOf(widget))
                    assertTrue(fixture.manager.getAppWidgetOptions(widget.id).getBoolean(LockscreenWidgetHost.OPTION_HOST_VISIBLE))
                    assertTrue("Ordinary provider still renders", view.childCount > 0)
                    assertEquals(listOf(0, 0, 0, 0), listOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom))
                }
            } finally { instrumentation.runOnMainSync { fixture.host.hideWidgets(context); fixture.host.listen(false); fixture.host.releaseViews() } }
        }
    }
}
