package com.gezimos.katapult

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.ScreensaverActivity
import com.gezimos.katapult.lockscreen.ScreensaverView
import com.gezimos.katapult.service.BadgeFeed
import com.gezimos.katapult.util.OwnerLeases
import com.gezimos.katapult.util.PrefsManager
import com.gezimos.katapult.util.SourceGeneration
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList

internal object ScreensaverAdapterCases {
    class Feed : BadgeFeed {
        val listeners = CopyOnWriteArrayList<() -> Unit>()
        private val sources = SourceGeneration<Map<String, Int>>()
        val leases = OwnerLeases { changed() }
        init {
            sources.reconcile(true)
            sources.publish(sources.epoch, true, mapOf("com.android.calendar" to 2))
        }
        override fun acquire(owner: Any) = leases.acquire(owner)
        override fun subscribe(changed: () -> Unit): AutoCloseable {
            listeners.add(changed)
            return AutoCloseable { listeners.remove(changed) }
        }
        override fun counts() = sources.value.orEmpty()
        override fun times() = emptyMap<String, Long>()
        fun changed() { listeners.forEach { it() } }
        fun disable(): Long {
            val old = sources.epoch
            sources.reconcile(false)
            changed()
            return old
        }
        fun late(epoch: Long): Boolean = sources.publish(epoch, true, mapOf("com.android.calendar" to 9))
    }

    fun staticAndTimedConsumersClearImmediatelyAndReleaseWhenInactive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val raw = context.getSharedPreferences("katapult_prefs", 0)
        val keys = listOf("screensaver_enabled", "screensaver_show_notifications", "screensaver_update_mode", "screensaver_update_minutes")
        val original = keys.associateWith { raw.all[it] }
        val ui = PlatformUiDriver(instrumentation)
        try {
            for (mode in listOf(PrefsManager.SCREENSAVER_MODE_STATIC, PrefsManager.SCREENSAVER_MODE_INTERVAL)) {
                instrumentation.runOnMainSync {
                    raw.edit().putBoolean("screensaver_enabled", true).putBoolean("screensaver_show_notifications", true)
                        .putInt("screensaver_update_mode", mode).putInt("screensaver_update_minutes", 60).commit()
                }
                val activity = instrumentation.startActivitySync(Intent(Intent.ACTION_VIEW).apply {
                    setClassName(context.packageName, ScreensaverActivity::class.java.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }) as ScreensaverActivity
                val feed = Feed()
                instrumentation.runOnMainSync { activity.setContent { ScreensaverView(feed) } }
                ui.await("Synthetic screensaver row did not render in mode $mode") { ui.has("Calendar") && ui.has("2") }
                assertTrue(feed.leases.demanded)
                val old = feed.disable()
                ui.await("Policy invalidation left a static/timed row on screen in mode $mode") { !ui.has("Calendar") && !ui.has("2") }
                assertFalse(feed.late(old))
                assertFalse(ui.has("Calendar"))
                instrumentation.runOnMainSync { activity.finish() }
                ui.await("Screensaver inactivity retained source ownership") { !feed.leases.demanded && feed.listeners.isEmpty() }
                assertFalse(ScreensaverActivity.isShowing)
            }
        } finally {
            instrumentation.runOnMainSync {
                val editor = raw.edit()
                original.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        else -> editor.remove(key)
                    }
                }
                editor.commit()
            }
        }
    }
}
