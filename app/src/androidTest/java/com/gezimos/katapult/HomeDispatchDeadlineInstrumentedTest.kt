package com.gezimos.katapult

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.recent.RecentApps
import com.gezimos.katapult.recent.RecentAppsSource
import com.gezimos.katapult.lockscreen.EdgePageView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Real kernel HOME events, not shell input keyevent (which bypasses accessibility filtering). */
class HomeDispatchDeadlineInstrumentedTest {
    @get:Rule val usageAccess = UsageAccessRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun doubleHomeNeverStartsHomeUnderTheOverlayAndEveryCloseRevealsTheApp() = exercise(false)
    @Test fun slowHistoryNeverLeaksHomeAndEveryCloseRevealsTheApp() = exercise(true)
    @Test fun slowWindowCreationNeverLeaksHomeAndEveryCloseRevealsTheApp() = exercise(false, true)

    private fun exercise(slowHistory: Boolean, slowWindow: Boolean = false) = RecentOverlayFixture().use { fixture ->
        if (slowWindow) instrumentation.runOnMainSync {
            val page = fixture.service.javaClass.getDeclaredField("recentPage").apply { isAccessible = true }.get(fixture.service)!!
            val window = page.javaClass.getDeclaredField("page").apply { isAccessible = true }.get(page)!!
            val field = window.javaClass.getDeclaredField("content").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val content = field.get(window) as (() -> Unit, (Float, Float) -> Boolean, (Float, Float) -> Boolean) -> EdgePageView
            field.set(window, { back: () -> Unit, capture: (Float, Float) -> Boolean, close: (Float, Float) -> Boolean ->
                SystemClock.sleep(750)
                content(back, capture, close)
            })
        }
        if (slowHistory) instrumentation.runOnMainSync {
            val page = fixture.service.javaClass.getDeclaredField("recentPage").apply { isAccessible = true }.get(fixture.service)!!
            val model = page.javaClass.getDeclaredField("model").apply { isAccessible = true }.get(page) as RecentApps
            val field = RecentApps::class.java.getDeclaredField("source").apply { isAccessible = true }
            val source = field.get(model) as RecentAppsSource
            field.set(model, object : RecentAppsSource by source {
                override fun history(): List<com.gezimos.katapult.recent.RecentApp> {
                    SystemClock.sleep(750)
                    assertNotSame("History must not occupy the accessibility dispatch thread", android.os.Looper.getMainLooper(), android.os.Looper.myLooper())
                    return source.history()
                }
            })
        }
        val native = File(context.getExternalFilesDir(null), "home-native-pair.sh")
        val keyboard = fixture.shell("su 0 getevent -pl").split("add device ").single { it.contains("KEY_HOMEPAGE") }
        val path = checkNotNull(Regex("/dev/input/event[0-9]+").find(keyboard)).value
        native.writeText("exec 2>&1\nset -e\n" +
            "sendevent $path 1 172 1\nsendevent $path 0 0 0\nsleep 0.03\n" +
            "sendevent $path 1 172 0\nsendevent $path 0 0 0\nsleep 0.12\n" +
            "sendevent $path 1 172 1\nsendevent $path 0 0 0\nsleep 0.03\n" +
            "sendevent $path 1 172 0\nsendevent $path 0 0 0\n")
        fun pair() {
            assertEquals("Native injection failed", "", fixture.shell("su 0 sh ${native.absolutePath}"))
        }
        try {
            for (dismiss in listOf("header", "back", "double")) {
                fixture.settings()
                val origin = fixture.resumedTask()
                val marker = "BEGIN ${SystemClock.uptimeMillis()} slowHistory=$slowHistory slowWindow=$slowWindow"
                Log.i("KotatsuHomeProbe", marker)
                pair()
                fixture.ui.await("Native HOME pair did not open overlay", 15000) { fixture.overlay.windowVisible() }
                // Include Android's 500ms accessibility-dispatch deadline, not just overlay attachment.
                SystemClock.sleep(750)
                val log = fixture.shell("logcat -d -v threadtime -s KotatsuHomeProbe:I ActivityTaskManager:I KotatsuHome:D")
                File(context.getExternalFilesDir(null), "home-dispatch-$dismiss.log").writeText(log)
                assertTrue("Missing trace marker", log.contains(marker))
                val trace = log.substringAfter(marker)
                assertFalse("A filtered pair leaked a HOME start:\n$trace", trace.lineSequence().any {
                    it.contains("START u0") && it.contains("android.intent.category.HOME")
                })
                assertEquals("Opening must not change the underlying resumed app", origin, fixture.resumedTask())
                when (dismiss) {
                    "header" -> fixture.overlay.click(context.getString(R.string.recent_dismiss))
                    "back" -> fixture.back()
                    else -> pair()
                }
                fixture.ui.await("Close must reveal the same app", 15000) { !fixture.overlay.windowVisible() && fixture.ui.has("Brightness level") }
                assertEquals(origin, fixture.resumedTask())
            }
        } finally { native.delete() }
    }
}
