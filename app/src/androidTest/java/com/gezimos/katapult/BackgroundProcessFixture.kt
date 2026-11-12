package com.gezimos.katapult

import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/** Owns the Activity transition and native process prerequisite for the real kill adapter. */
internal class BackgroundProcessFixture : AutoCloseable {
    companion object {
        const val PACKAGE_NAME = "org.chromium.webview_shell"
        // Android 12's killBackgroundProcesses uses SERVICE_ADJ, not UID state/importance.
        fun killEligible(nativeAdjustment: String) = nativeAdjustment.trim().toIntOrNull()?.let { it >= 500 } == true
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)
    private val home = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
    private var pid = ""

    fun foreground() {
        val launch = checkNotNull(context.packageManager.getLaunchIntentForPackage(PACKAGE_NAME))
        instrumentation.runOnMainSync { home.viewModel.screen = Screen.HOME; context.startActivity(launch) }
        ui.await("Real close fixture not foreground") { ui.packageVisible(PACKAGE_NAME) }
        ui.await("Real close fixture has no process") {
            pid = shell("pidof $PACKAGE_NAME")
            pid.toIntOrNull() != null
        }
    }

    fun background() {
        check(pid.toIntOrNull() != null)
        instrumentation.runOnMainSync {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        ui.await("Home did not regain foreground") { ui.packageVisible(context.packageName) }
        var adjustment = ""
        ui.await("Fixture process did not become natively kill-eligible") {
            adjustment = shell("cat /proc/$pid/oom_score_adj")
            killEligible(adjustment) && shell("pidof $PACKAGE_NAME") == pid
        }
        android.util.Log.i("BackgroundProcessFixture", "ready package=$PACKAGE_NAME pid=$pid oom_score_adj=$adjustment")
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.platformAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    override fun close() { instrumentation.runOnMainSync { home.finish() } }
}
