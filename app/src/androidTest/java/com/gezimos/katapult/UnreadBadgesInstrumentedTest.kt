package com.gezimos.katapult

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.LockscreenWidgetService
import com.gezimos.katapult.lockscreen.ScreensaverActivity
import com.gezimos.katapult.service.BadgeSources
import com.gezimos.katapult.service.NotificationListener
import com.gezimos.katapult.util.PrefsManager
import com.gezimos.katapult.util.SensitiveAccess
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real OS posts -> listener -> shared source -> rendered consumers. No synthetic badge feed. */
@RunWith(AndroidJUnit4::class)
class UnreadBadgesInstrumentedTest {
    @get:org.junit.Rule val nativeState = ConnectedUiStateRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)

    private fun shell(command: String): String = instrumentation.platformAutomation.acknowledgedShell(command)

    private fun <T> main(read: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(read) }
        return result!!.getOrThrow()
    }

    @Test fun realListenerDeliversUnreadTotalsToHomeGridScreensaverAndLockscreenRows() = exerciseUnread(false)

    @Test fun fullscreenTutorialCannotConcealRealUnreadDelivery() = exerciseUnread(true)

    private fun exerciseUnread(forceFullscreenHint: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val listener = ComponentName(context, NotificationListener::class.java)
        val wasAllowed = manager.isNotificationListenerAccessGranted(listener)
        val prefs = PrefsManager(context)
        val raw = context.getSharedPreferences("katapult_prefs", Context.MODE_PRIVATE)
        val keys = listOf("collect_notification_badges", "notification_indicators", "show_katapult_icon",
            "onboarding_complete", "home_extra_row", "phone_package", "phone_activity", "phone_shortcut_id",
            "screensaver_enabled", "screensaver_show_notifications", "screensaver_update_mode",
            "rename_${context.packageName}", "lockscreen_widget_excluded", "app_order")
        val saved = keys.associateWith { raw.all[it] }
        val fullscreenConfirmation = if (forceFullscreenHint) instrumentation.platformAutomation.readShell("settings get secure immersive_mode_confirmations").trim() else null
        val channel = "unread-${UUID.randomUUID()}"
        val tag = channel
        var activity: MainActivity? = null
        var screensaver: ScreensaverActivity? = null
        var lease: AutoCloseable? = null
        fun post(id: Int, number: Int? = null, summary: Boolean = false, ongoing: Boolean = false) {
            val builder = Notification.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_screensaver).setContentTitle("Unread fixture $id")
                .setGroup(channel).setGroupSummary(summary).setOngoing(ongoing)
            number?.let { builder.setNumber(it) }
            manager.notify(tag, id, builder.build())
        }
        fun count(expected: Int) = ui.await("Listener/source did not report $expected unread messages", 15000) {
            NotificationListener.getCount(context.packageName) == expected &&
                (BadgeSources.counts(context)[context.packageName] ?: 0) == expected
        }
        withFixtureCleanup(body = {
            if (forceFullscreenHint) shell("settings delete secure immersive_mode_confirmations")
            main {
                prefs.onboardingComplete = true
                prefs.notificationIndicators = true
                prefs.showKatapultIcon = true
                prefs.homeExtraRow = false
                prefs.screensaverEnabled = true
                prefs.screensaverShowNotifications = true
                prefs.screensaverUpdateMode = PrefsManager.SCREENSAVER_MODE_STATIC
                prefs.setAppRename(context.packageName, "A unread fixture")
                prefs.setLockscreenExcludedApps(prefs.getLockscreenExcludedApps() - context.packageName)
                raw.edit().putBoolean("collect_notification_badges", true).commit()
                SensitiveAccess.initialize(context)
                lease = SensitiveAccess.acquireBadges(Any())
            }
            manager.createNotificationChannel(NotificationChannel(channel, "Unread fixture", NotificationManager.IMPORTANCE_LOW))
            post(1, 3)
            post(2, 2)
            post(3, 100, summary = true)
            post(4, 200, ongoing = true)
            shell("cmd notification allow_listener ${listener.flattenToString()}")
            ui.await("Notification listener not connected", 15000) {
                manager.isNotificationListenerAccessGranted(listener) && SensitiveAccess.notificationConnected
            }
            count(5)
            activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }) as MainActivity
            val current = activity
            main {
                val model = current.viewModel
                model.navigateTo(Screen.HOME)
                model.resolveLockscreenReEnable()
                model.saveShortcut("phone", context.packageName, MainActivity::class.java.name)
                model.shortcutRefresh++
                model.prefs.saveAppOrder(listOf(context.packageName) + model.orderedApps.map { it.key }.filter { it != context.packageName })
                model.loadApps()
            }
            ui.await("Home did not render the unread total") {
                current.viewModel.notificationCounts[context.packageName] == 5 && ui.has("A unread fixture") && ui.has("5")
            }
            main { current.viewModel.navigateTo(Screen.ALL_APPS) }
            ui.await("All Apps did not render the same unread total") { ui.has("A unread fixture") && ui.has("5") }
            post(1, 7)
            ui.await("Updated chat number did not replace its previous count in All Apps") {
                current.viewModel.notificationCounts[context.packageName] == 9 && ui.has("9")
            }
            post(5) // Apps without Notification.number still contribute one.
            ui.await("Unnumbered notification did not contribute one") {
                current.viewModel.notificationCounts[context.packageName] == 10 && ui.has("10")
            }
            manager.cancel(tag, 2)
            count(8)
            screensaver = instrumentation.startActivitySync(Intent(context, ScreensaverActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }) as ScreensaverActivity
            if (forceFullscreenHint) {
                // Force Android's delayed input-owner change before asking the shared driver
                // to observe app content. A one-shot check at Activity launch cannot own this.
                fixtureAwait("The real Android fullscreen tutorial did not acquire focus") {
                    ui.find { it.packageName == "android" && it.viewIdResourceName == "android:id/immersive_cling_title" } != null
                }
                assertFalse("App observation must not bypass the focused tutorial", ui.has("A unread fixture"))
            }
            ui.await("Screensaver did not render the shared unread total") { ui.has("A unread fixture") && ui.has("8") }
            post(1, 4)
            ui.await("Real listener update did not reach the static screensaver") { ui.has("A unread fixture") && ui.has("5") }
            main { raw.edit().putBoolean("collect_notification_badges", false).commit() }
            ui.await("Opt-out left unread rows on the screensaver") { !ui.has("A unread fixture") && NotificationListener.getAllCounts().isEmpty() }
            main { raw.edit().putBoolean("collect_notification_badges", true).commit() }
            ui.await("Re-enumeration did not restore the current unread total") { ui.has("A unread fixture") && ui.has("5") }
            main { screensaver?.finish() }
            ui.await("Screensaver did not close") { !ScreensaverActivity.isShowing }

            // Host the production lock-screen row view in the test Activity. This checks its real
            // source/filter/order/render chain without claiming a positive Kompakt keyguard overlay.
            main {
                val service = LockscreenWidgetService()
                ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
                    .apply { isAccessible = true }.invoke(service, current)
                LockscreenWidgetService::class.java.getDeclaredField("prefs")
                    .apply { isAccessible = true }.set(service, prefs)
                val counts = LockscreenWidgetService::class.java.getDeclaredMethod("currentCounts")
                    .apply { isAccessible = true }.invoke(service)
                val rows = LockscreenWidgetService::class.java.getDeclaredMethod("orderedRows", Map::class.java)
                    .apply { isAccessible = true }.invoke(service, counts)
                val view = LockscreenWidgetService::class.java.getDeclaredMethod("buildWidget", List::class.java, Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(service, rows, (counts as Map<*, *>).size) as View
                current.setContentView(view)
            }
            ui.await("Production lock-screen row did not render the same total") { ui.has("A unread fixture") && ui.has("5") }
        }, actions = arrayOf<() -> Unit>(
            { ConnectedUiStateFixture.quiesceOwnedProducers() },
            { if (fullscreenConfirmation != null) {
                // The native initial value may be empty rather than absent. Preserve both;
                // Runtime.exec tokenization cannot express an empty argument directly.
                val restore = File.createTempFile("fullscreen-restore-", ".sh", context.getExternalFilesDir(null))
                withFixtureCleanup({ check(restore.delete()) }) {
                    restore.writeText(if (fullscreenConfirmation == "null") "settings delete secure immersive_mode_confirmations\n"
                        else "settings put secure immersive_mode_confirmations '${fullscreenConfirmation.replace("'", "'\\''")}'\n")
                    shell("sh ${restore.absolutePath}")
                }
                assertEquals(fullscreenConfirmation, instrumentation.platformAutomation.readShell("settings get secure immersive_mode_confirmations").trim())
            } },
            { fixtureCleanup(*(1..5).map { id -> { manager.cancel(tag, id) } }.toTypedArray()) },
            { manager.deleteNotificationChannel(channel) },
            { if (!wasAllowed) shell("cmd notification disallow_listener ${listener.flattenToString()}") },
            {
                ui.await("Notification access was not restored", 15000) {
                    manager.isNotificationListenerAccessGranted(listener) == wasAllowed &&
                        (wasAllowed || !SensitiveAccess.notificationConnected)
                }
            },
            { main { lease?.close() } },
            { restoreFixturePreferenceValues(raw, saved) },
            { assertEquals(saved, keys.associateWith { raw.all[it] }) },
            { ui.await("Fixture notifications were not removed") { manager.activeNotifications.none { it.tag == tag } } },
        ))
    }
}
