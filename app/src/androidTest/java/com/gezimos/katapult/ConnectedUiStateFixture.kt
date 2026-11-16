package com.gezimos.katapult

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.util.IconUtility
import com.gezimos.katapult.util.SensitiveAccess
import com.gezimos.katapult.util.UsageHelper
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Per-test ownership, never a suite-wide reset. Nested helper scopes restore their parent. */
internal class ConnectedUiStateFixture(completeOnboarding: Boolean = true, private val restorePreferences: Boolean = true) : AutoCloseable {
    private val activities = ActivityLifetimeFixture()
    private var accessibility: ActionServiceSettingsFixture? = null
    private var display: NativeDisplayFixture? = null
    private var settingsTasks: AndroidSettingsTaskFixture? = null
    private val raw = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("katapult_prefs", Context.MODE_PRIVATE)
    private var preferences: Map<String, Any?>? = null
    private var activationPreferences: ServiceActivationPreferencesFixture? = null
    private var closed = false
    private val producers = mutableListOf<AutoCloseable>()

    init {
        try {
            if (scopes.isEmpty()) fixtureMain {
                check(!com.gezimos.katapult.lockscreen.ScreensaverActivity.isShowing) { "Unowned screensaver already showing" }
            }
            accessibility = ActionServiceSettingsFixture.off()
            display = NativeDisplayFixture()
            fixtureMain {
                preferences = preferenceSnapshot(raw)
                activationPreferences = ServiceActivationPreferencesFixture(raw)
                if (completeOnboarding) synchronized(raw) {
                    check(raw.edit().putBoolean("onboarding_complete", true).commit())
                }
                // Process-wide consumers must never initialize from a disposable test store.
                SensitiveAccess.initialize(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext)
            }
            // Removing a foreign task can reveal/launch Home: establish app preferences first.
            if (scopes.isEmpty()) settingsTasks = AndroidSettingsTaskFixture()
            scopes.add(this)
        } catch (error: Throwable) {
            fixtureCleanup({ close() }, failure = error)
            throw error
        }
    }

    fun closeActivities() = activities.closeActivities()

    fun closeProducers() {
        val owned = fixtureMain { producers.toList().asReversed().also { producers.clear() } }
        fixtureCleanup(*owned.map { producer -> { fixtureMain { producer.close() } } }.toTypedArray())
    }

    override fun close() {
        if (closed) return
        closed = true
        scopes.remove(this)
        fixtureCleanup(
            { closeProducers() },
            { activities.closeActivities() },
            { settingsTasks?.close() },
            { activities.close() },
            { ActionServiceSettingsFixture.off().use {
                fixtureMain {
                    fixtureCleanup(
                        { activationPreferences?.close(); Unit },
                        { preferences?.takeIf { restorePreferences }?.let { snapshot ->
                            synchronized(raw) { restorePreferenceSnapshot(raw, snapshot) }
                        }; Unit },
                        { UsageHelper.invalidate() },
                        { IconUtility.invalidateOverrides() },
                    )
                }
            } },
            { display?.close() },
            { accessibility?.close() },
        )
    }

    companion object {
        private val scopes = mutableListOf<ConnectedUiStateFixture>()
        fun closeOwnedActivities() {
            fixtureCleanup(*scopes.toList().asReversed().map { scope -> { scope.closeActivities() } }.toTypedArray())
        }
        fun quiesceOwnedProducers() {
            fixtureCleanup(*scopes.toList().asReversed().map { scope ->
                { fixtureCleanup({ scope.closeProducers() }, { scope.closeActivities() }) }
            }.toTypedArray())
        }
        fun ownMain(producer: AutoCloseable) {
            checkNotNull(scopes.lastOrNull()) { "Producer requires an enclosing connected state scope" }.producers.add(producer)
        }
        /** Foreground service tests do not exercise power-entry screensaver activation. */
        fun prepareForegroundService() = fixtureMain {
            val scope = checkNotNull(scopes.lastOrNull())
            check(!com.gezimos.katapult.recent.HomeKeyFilterState.active)
            synchronized(scope.raw) {
                check(scope.raw.edit().putBoolean("screensaver_enabled", false).putBoolean("screensaver_on_power", false)
                    .putBoolean("allow_accessibility_features", true).commit())
            }
        }
    }
}

internal fun ownedViewModelStore() = androidx.lifecycle.ViewModelStore().also { store ->
    ConnectedUiStateFixture.ownMain(AutoCloseable { store.clear() })
}

internal fun <T : AutoCloseable> ownFixtureMain(producer: T): T = producer.also { ConnectedUiStateFixture.ownMain(it) }

class ConnectedUiStateRule(private val completeOnboarding: Boolean = true, private val restorePreferences: Boolean = true) : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() { ConnectedUiStateFixture(completeOnboarding, restorePreferences).use { base.evaluate() } }
    }
}
