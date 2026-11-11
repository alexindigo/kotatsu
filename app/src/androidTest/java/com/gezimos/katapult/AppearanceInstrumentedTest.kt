package com.gezimos.katapult

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.*
import com.gezimos.katapult.recent.*
import com.gezimos.katapult.ui.*
import com.gezimos.katapult.util.PrefsManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import java.io.File

class AppearanceInstrumentedTest {
    @get:Rule val usageAccess = UsageAccessRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val raw = context.getSharedPreferences("katapult_prefs", 0)
    private val ui = PlatformUiDriver(instrumentation)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun preserve(test: () -> Unit) {
        val before = raw.all
        try { test() } finally {
            main {
                raw.edit().clear().apply { before.forEach { (key, value) -> when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is String -> putString(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                } } }.commit()
            }
        }
    }

    @Test fun defaultSystemKeepsExplicitAndLegacyChoicesAndResolvesEveryContext() = preserve {
        main { raw.edit().remove("theme_mode").remove("dark_mode").commit() }
        val prefs = PrefsManager(context)
        assertEquals(PrefsManager.THEME_SYSTEM, prefs.themeMode)
        main { raw.edit().putBoolean("dark_mode", true).commit() }
        assertEquals(PrefsManager.THEME_DARK, prefs.themeMode)
        main { raw.edit().putBoolean("dark_mode", false).commit() }
        assertEquals("A stored legacy Light choice is not an unchosen default", PrefsManager.THEME_LIGHT, prefs.themeMode)
        main { raw.edit().putBoolean("dark_mode", true).commit() }
        for (mode in listOf(PrefsManager.THEME_LIGHT, PrefsManager.THEME_DARK, PrefsManager.THEME_SYSTEM)) {
            main { prefs.themeMode = mode }
            assertEquals(mode, prefs.themeMode)
            for (night in listOf(false, true)) {
                val configuration = Configuration(context.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
                val configured = context.createConfigurationContext(configuration)
                val expectedDark = mode == PrefsManager.THEME_DARK || mode == PrefsManager.THEME_SYSTEM && night
                assertEquals(expectedDark, Appearance.palette(configured, Appearance.Surface.APP).isDark)
                assertEquals(expectedDark, Appearance.palette(configured, Appearance.Surface.SCREENSAVER).isDark)
                assertFalse(Appearance.palette(configured, Appearance.Surface.LOCK_SCREEN).isDark)
            }
        }
    }

    private fun capture(name: String, dark: Boolean) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(120)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val palette = if (dark) PaperPalette.Dark(context) else PaperPalette.Light(context)
        // Real consumer pixels, not just a resolved palette or a successful startup.
        val prefix = InstrumentationRegistry.getArguments().getString("appearanceCapturePrefix", "appearance")
        File(context.getExternalFilesDir(null), "$prefix-$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        assertEquals("Page paper in the empty outer inset", palette.paperArgb, bitmap.getPixel(4, bitmap.height / 2))
        bitmap.recycle()
    }

    @Test fun recentServiceOverlayAndSharedHeaderRailFollowLiveAppTheme() = preserve {
        main { raw.edit().putBoolean("onboarding_complete", true).putBoolean("collect_usage_stats", true)
            .putInt("theme_mode", PrefsManager.THEME_LIGHT).commit() }
        RecentOverlayFixture().use { fixture ->
            val adapter = AndroidRecentApps(context)
            val now = System.currentTimeMillis()
            val source = object : RecentAppsSource by adapter {
                override fun history() = (1..30).map { RecentApp("fixture.$it", "Recent fixture $it", now - it * 60_000L) }
                override fun closed() = emptyMap<String, Long>()
            }
            main {
                val page = fixture.service.javaClass.getDeclaredField("recentPage").apply { isAccessible = true }.get(fixture.service)!!
                val model = page.javaClass.getDeclaredField("model").apply { isAccessible = true }.get(page) as RecentApps
                RecentApps::class.java.getDeclaredField("source").apply { isAccessible = true }.set(model, source)
            }
            fixture.settings()
            val task = fixture.resumedTask()
            fixture.open()
            fixture.overlay.await("Recent overlay title missing") { fixture.overlay.has(context.getString(R.string.recent_apps)) }
            assertFalse(fixture.overlay.has("Close all"))
            val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            val metrics = fixture.home.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics
            val bars = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars())
            val prefix = InstrumentationRegistry.getArguments().getString("appearanceCapturePrefix", "appearance")
            for (dark in listOf(false, true)) {
                main { fixture.home.viewModel.prefs.themeMode = if (dark) PrefsManager.THEME_DARK else PrefsManager.THEME_LIGHT }
                val palette = if (dark) PaperPalette.Dark(context) else PaperPalette.Light(context)
                var frame: Bitmap? = null
                var previous: Bitmap? = null
                fixture.overlay.await("Overlay did not present its live APP palette") {
                    val image = automation.takeScreenshot()
                    val ready = image.getPixel(4, image.height / 2) == palette.paperArgb && previous?.sameAs(image) == true
                    previous?.recycle()
                    if (ready) { frame = image; previous = null } else previous = image
                    ready
                }
                val image = checkNotNull(frame)
                try {
                    val rail = fixture.overlay.find { it.rangeInfo != null } ?: error("Scroll rail missing")
                    val railBounds = android.graphics.Rect().also(rail::getBoundsInScreen)
                    val railInk = (railBounds.top until railBounds.bottom).count {
                        image.getPixel(railBounds.centerX(), it) == palette.inkArgb
                    }
                    assertTrue("Presented rail thumb must occupy its own centerline", railInk > railBounds.height() / 5)
                    val title = fixture.overlay.find(context.getString(R.string.recent_apps))!!
                    val titleBounds = android.graphics.Rect().also(title::getBoundsInScreen)
                    for (bounds in listOf(railBounds, titleBounds)) {
                        var ink = 0
                        for (y in bounds.top until bounds.bottom) for (x in bounds.left until bounds.right) {
                            if (image.getPixel(x, y) == palette.inkArgb) ink++
                        }
                        assertTrue("Header and rail must inherit the APP ink: $bounds", ink > 10)
                    }
                    File(context.getExternalFilesDir(null), "$prefix-${if (dark) "dark" else "light"}-recent-overlay-over-settings.png")
                        .outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    assertEquals(task, fixture.resumedTask())
                    assertTrue("Overlay must not own system bars", bars.top > 0 && bars.bottom > 0)
                } finally { image.recycle() }
            }
            fixture.overlay.click(context.getString(R.string.recent_dismiss))
            ui.await("Overlay close did not reveal Settings") { !fixture.overlay.windowVisible() && ui.has("Brightness level") }
        }
    }

    @Test fun liveAppPagesAndLockScreenFrameUseTheirOwners() = preserve {
        main { raw.edit().putBoolean("onboarding_complete", true).putBoolean("notice_action_service_dismissed", true)
            .putInt("theme_mode", PrefsManager.THEME_LIGHT).commit() }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            val model = activity.viewModel
            main { model.resolveLockscreenReEnable(); model.screen = Screen.SETTINGS }
            for (dark in listOf(false, true)) {
                val mode = if (dark) PrefsManager.THEME_DARK else PrefsManager.THEME_LIGHT
                main { model.prefs.themeMode = mode; model.themeMode = mode }
                ui.await("Window chrome did not observe the owner") {
                    activity.window.statusBarColor == Appearance.palette(activity, Appearance.Surface.APP).paperArgb
                }
                ui.await("Settings root missing") { ui.has(context.getString(R.string.section_appearance)) }
                ui.click(context.getString(R.string.section_appearance))
                ui.await("Appearance chapter missing") { ui.has(context.getString(R.string.icon_shape)) }
                capture("${if (dark) "dark" else "light"}-appearance-chapter", dark)
                ui.click(context.getString(R.string.icon_shape))
                ui.await("Choice page missing") { ui.has(context.getString(R.string.icon_shape_circle)) }
                capture("${if (dark) "dark" else "light"}-choice-page", dark)
                ui.back(); ui.back()
            }
            // Keep one Recent apps composition open while its palette changes.
            val now = 1_800_000_000_000L
            val source = object : RecentAppsSource {
                override fun available() = true
                override fun history() = listOf(RecentApp("fixture.notes", "Notes fixture", now))
                override fun closed() = emptyMap<String, Long>()
                override fun close(packages: List<String>, at: Long) = Unit
                override fun launch(packageName: String) = Unit
                override fun canOpenAccess() = false
                override fun openAccess() = false
            }
            val recent = RecentApps(source, context.packageName) { now }
            main { recent.show(); activity.setPaperContent { RecentAppsScreen(recent) } }
            for (dark in listOf(false, true)) {
                main { model.prefs.themeMode = if (dark) PrefsManager.THEME_DARK else PrefsManager.THEME_LIGHT }
                ui.await("Recent apps missing") { ui.has("Notes fixture") }
                capture("${if (dark) "dark" else "light"}-recent-apps", dark)
            }
            lateinit var side: SideScreenView
            main {
                side = SideScreenView(activity, SideScreen(name = "Side screen fixture"), {}, { _, _ -> false }, { _, _ -> false }, {}, {})
                activity.setContentView(side)
                com.gezimos.katapult.util.SystemChrome.apply(activity.window, Appearance.Surface.LOCK_SCREEN, false, model.prefs)
                side.settled()
            }
            try {
                for (dark in listOf(false, true)) {
                    main { model.prefs.themeMode = if (dark) PrefsManager.THEME_DARK else PrefsManager.THEME_LIGHT }
                    ui.await("Side screen missing") { ui.has("Side screen fixture") }
                    capture("${if (dark) "dark" else "light"}-side-screen", false)
                }
            } finally { main { side.dispose() } }
        } finally { main { activity.finish() } }
    }
}
