package com.gezimos.katapult

import android.content.Intent
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class AndroidSettingsTaskFixtureInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)

    private fun display() {
        context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.await("Display fixture did not render") { ui.has("Brightness level") }
    }

    @Test fun taskParserSelectsSettingsAndRetainsOtherMembersForOwnershipChecks() {
        val stacks = AndroidSettingsTaskFixture.settingsStacks("""
            RootTask #1: type=home
              * Hist #0: ActivityRecord{abc u0 com.android.launcher3/.Home t1}
            RootTask #42: type=standard
              * Hist #1: ActivityRecord{def u0 com.android.settings/.applications.InstalledAppDetails t42}
              * Hist #0: ActivityRecord{ghi u0 com.android.settings/.Settings${'$'}DisplaySettingsActivity t42}
            RootTask #43: type=standard
              * Hist #1: ActivityRecord{jkl u0 com.android.settings/.Settings t43}
              * Hist #0: ActivityRecord{mno u0 another.owner/.Activity t43}
        """.trimIndent())
        assertEquals(listOf(42, 43), stacks.map { it.id })
        assertEquals(setOf("com.android.settings"), stacks[0].packages)
        assertEquals(setOf("com.android.settings", "another.owner"), stacks[1].packages)
    }

    @Test fun staleAcquisitionAndExceptionalCloseRemoveOnlyOwnedSettingsTasks() {
        ActionServiceSettingsFixture.off().use {
            NativeDisplayFixture().use {
                try {
                    display()
                    assertTrue(runCatching { AndroidSettingsTaskFixture.assertEmpty() }.isFailure)
                    val expected = AssertionError("Settings body failure")
                    val error = runCatching {
                        ConnectedUiStateFixture().use {
                            AndroidSettingsTaskFixture.assertEmpty()
                            display()
                            ConnectedUiStateFixture().use {
                                assertTrue("Nested scope removed its parent's Settings flow", ui.has("Brightness level"))
                            }
                            assertTrue(ui.has("Brightness level"))
                            throw expected
                        }
                    }.exceptionOrNull()
                    assertSame(expected, error)
                    AndroidSettingsTaskFixture.assertEmpty()
                } finally { AndroidSettingsTaskFixture().close() }
            }
        }
    }

    @Test fun closedDisplayScopeCannotInterceptTheNextAppInfoBack() {
        ConnectedUiStateFixture().use { display() }
        AndroidSettingsTaskFixture.assertEmpty()
        ConnectedUiStateFixture().use {
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val home = context.getString(R.string.all_apps)
            ui.await("Launcher origin did not render") { ui.has(home) }
            fixtureMain {
                activity.viewModel.launchIntent(activity, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:${instrumentation.context.packageName}")))
            }
            ui.await("App info did not render") { ui.packageVisible("com.android.settings") && ui.has("App info") }
            ui.back()
            ui.await("App info Back returned to stale Settings instead of the launcher") {
                ui.packageVisible(context.packageName) && ui.has(home)
            }
        }
        AndroidSettingsTaskFixture.assertEmpty()
    }
}
