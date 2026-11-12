package com.gezimos.katapult

import android.app.AppOpsManager
import android.app.UiAutomation
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.util.UsageHelper
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Owns the real usage consumer's prerequisites for one test, including setup-failure cleanup. */
class UsageAccessRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val appOps = context.getSystemService(AppOpsManager::class.java)
            val preferences = context.getSharedPreferences("katapult_prefs", 0)
            val keys = listOf("onboarding_complete", "collect_usage_stats")
            val original = keys.associateWith { preferences.all[it] }
            fun mode() = appOps.unsafeCheckOpRawNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            val previousMode = mode()
            fun setMode(value: Int) {
                val name = when (value) {
                    AppOpsManager.MODE_ALLOWED -> "allow"
                    AppOpsManager.MODE_IGNORED -> "ignore"
                    AppOpsManager.MODE_ERRORED -> "deny"
                    AppOpsManager.MODE_DEFAULT -> "default"
                    AppOpsManager.MODE_FOREGROUND -> "foreground"
                    else -> error("Unsupported usage AppOp mode $value")
                }
                instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                    .boundedShell("appops set ${context.packageName} GET_USAGE_STATS $name")
                val deadline = SystemClock.uptimeMillis() + 5000
                while (mode() != value && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(25)
                check(mode() == value) { "Usage AppOp did not become $name" }
                UsageHelper.invalidate()
            }
            var failure: Throwable? = null
            fun restore(action: () -> Unit) {
                try { action() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                }
            }
            try {
                android.util.Log.i("UsageAccessFixture", "${description.methodName} priorMode=$previousMode")
                setMode(AppOpsManager.MODE_ALLOWED)
                check(preferences.edit().putBoolean("onboarding_complete", true)
                    .putBoolean("collect_usage_stats", true).commit())
                check(UsageHelper.collectionAllowed(context)) { "Usage fixture did not enable the real consumer" }
                base.evaluate()
            } catch (error: Throwable) {
                failure = error
            } finally {
                restore {
                    check(preferences.edit().apply { original.forEach { (key, value) ->
                        if (value == null) remove(key) else putBoolean(key, value as Boolean)
                    } }.commit())
                    check(keys.associateWith { preferences.all[it] } == original) { "Usage preferences leaked" }
                }
                restore { setMode(previousMode) }
                android.util.Log.i("UsageAccessFixture", "${description.methodName} restoredMode=${mode()}")
            }
            failure?.let { throw it }
        }
    }
}
