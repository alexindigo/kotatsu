package com.gezimos.katapult

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.setPaperContent as setContent
import com.gezimos.katapult.ui.PaperPalette
import com.gezimos.katapult.ui.settings.Entry
import com.gezimos.katapult.ui.settings.PaperSheet
import com.gezimos.katapult.ui.settings.PaperSheetHeading
import com.gezimos.katapult.ui.settings.SettingsEntry
import com.gezimos.katapult.util.PrefsManager
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Observes the rendered separator, not just a decorative semantics marker. */
internal fun assertSheetHeading(ui: PlatformUiDriver, title: String, first: String, dark: Boolean) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    ui.await("Visible sheet heading missing: $title") {
        ui.find { it.text?.toString() == title && it.isHeading }?.isVisibleToUser == true
    }
    val heading = Rect().also { ui.find { it.text?.toString() == title && it.isHeading }!!.getBoundsInScreen(it) }
    val row = Rect().also { ui.find(first)!!.getBoundsInScreen(it) }
    val density = context.resources.displayMetrics.density
    assertFalse(heading.isEmpty)
    assertTrue("Heading must precede content", heading.bottom <= row.top)
    assertEquals("Sheet heading uses the 16 dp content inset", (16 * density).roundToInt(), heading.left)
    assertEquals("Heading and rows share the inset", row.left, heading.left)
    val palette = if (dark) PaperPalette.Dark(context) else PaperPalette.Light(context)
    // Semantics and even a decor-frame callback can precede framebuffer publication.
    // The pixel contract itself owns readiness; a missing or wrong rule still times out.
    ui.await("A contiguous full-width 2 dp ink rule must render between heading and rows") {
        val bitmap = instrumentation.platformAutomation.takeScreenshot()
        try {
            val left = (16 * density).roundToInt()
            val right = bitmap.width - left
            val lines = (heading.bottom until row.top).filter { y ->
                (left until right).all { x -> bitmap.getPixel(x, y) == palette.inkArgb }
            }
            lines.size == (2 * density).roundToInt() && lines.last() - lines.first() + 1 == lines.size
        } finally { bitmap.recycle() }
    }
}

class PaperSheetHeadingInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    @Test fun sharedHeadingAndRuleUsePageGrammarInBothPalettes() {
        val raw = context.getSharedPreferences("katapult_prefs", 0)
        val before = raw.all
        var activity: MainActivity? = null
        try {
            for (dark in listOf(false, true)) {
                main { raw.edit().putBoolean("onboarding_complete", true)
                    .putInt("theme_mode", if (dark) PrefsManager.THEME_DARK else PrefsManager.THEME_LIGHT)
                    .putBoolean("notice_action_service_dismissed", true).commit() }
                activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                val current = activity!!
                main {
                    current.setContent {
                        PaperSheet({}) {
                            PaperSheetHeading(context.getString(R.string.theme_mode))
                            Entry(SettingsEntry.Open("system", context.getString(R.string.theme_system), "", {}))
                        }
                    }
                }
                val ui = PlatformUiDriver(instrumentation)
                ui.await("Heading fixture missing") { ui.has(context.getString(R.string.theme_system)) }
                val committed = CountDownLatch(1)
                main {
                    current.window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                    current.window.decorView.invalidate()
                }
                assertTrue("Choice capture requires committed pixels", committed.await(8, TimeUnit.SECONDS))
                assertSheetHeading(ui, context.getString(R.string.theme_mode), context.getString(R.string.theme_system), dark)
                val bitmap = instrumentation.platformAutomation.takeScreenshot()
                File(context.getExternalFilesDir(null), "sheet-heading-fixture-${if (dark) "dark" else "light"}.png").outputStream().use {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap.recycle()
                main { current.finish() }
                activity = null
            }
        } finally {
            main {
                activity?.finish()
                val editor = raw.edit().clear()
                before.forEach { (key, value) -> when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
                assertTrue(editor.commit())
            }
        }
    }
}
