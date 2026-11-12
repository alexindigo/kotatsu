package com.gezimos.katapult

import android.os.SystemClock
import android.content.ComponentName
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.LockscreenWidgetService
import com.gezimos.katapult.recent.HomeKeyFilterState
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** The sole native writer of the accessibility pair, including setup-failure restoration. */
internal class ActionServiceSettingsFixture private constructor(on: Boolean?, exact: Values?) : AutoCloseable {
    data class Values(val enabled: String, val accessibility: String)
    companion object {
        fun on() = ActionServiceSettingsFixture(true, null)
        fun off() = ActionServiceSettingsFixture(false, null)
        fun exact(enabled: String, accessibility: String) = ActionServiceSettingsFixture(null, Values(enabled, accessibility))
        private const val ENABLED = "enabled_accessibility_services"
        private const val ACCESSIBILITY = "accessibility_enabled"
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val component = ComponentName(context, LockscreenWidgetService::class.java)
    private val original = values()
    private var closed = false
    private fun contains(value: String) = value.split(':').any { ComponentName.unflattenFromString(it) == component }
    val service: LockscreenWidgetService get() = LockscreenWidgetService::class.java.getDeclaredField("instance")
        .apply { isAccessible = true }.get(null) as LockscreenWidgetService
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }
    fun values() = Values(shell("settings get secure $ENABLED"), shell("settings get secure $ACCESSIBILITY"))
    init {
        try {
            val enabled = when {
                exact != null -> exact.enabled
                on == true -> (original.enabled.takeUnless { it == "null" || it.isEmpty() }?.split(':').orEmpty()
                    .filterNot { ComponentName.unflattenFromString(it) == component } + component.flattenToString()).joinToString(":")
                !contains(original.enabled) -> original.enabled
                else -> original.enabled.split(':').filterNot { ComponentName.unflattenFromString(it) == component }.joinToString(":")
            }
            apply(exact ?: Values(enabled, if (on == true) "1" else original.accessibility))
        } catch (error: Throwable) {
            try { close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            throw error
        }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) {
            if (condition()) return
            Thread.sleep(25)
        }
        throw AssertionError(message)
    }
    private fun apply(value: Values) {
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) { try { action() } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        } }
        attempt { restoreSecureSetting(ENABLED, value.enabled, ::shell) }
        attempt { restoreSecureSetting(ACCESSIBILITY, value.accessibility, ::shell) }
        attempt { await("Action Service connection did not reach the owned state") {
            HomeKeyFilterState.active == contains(value.enabled)
        } }
        attempt { await("Accessibility pair did not restore exactly") { values() == value } }
        attempt { await("Accessibility pair did not persist exactly") {
            val stored = persistedSecureSettings(::shell)
            (stored[ENABLED] ?: "null") == value.enabled && (stored[ACCESSIBILITY] ?: "null") == value.accessibility
        } }
        failure?.let { throw it }
    }
    override fun close() {
        if (closed) return
        closed = true
        apply(original)
    }
}

/** Establish OFF before constructing the Activity/model; nested ON leases restore this state. */
class ActionServiceOffRule : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() { ActionServiceSettingsFixture.off().use { base.evaluate() } }
    }
}

/** UiAutomation splits argv itself, so shell quotes would become literal setting data. */
internal fun restoreSecureSetting(key: String, original: String, shell: (String) -> String) {
    shell(when (original) {
        "null" -> "settings delete secure $key"
        "" -> "content call --uri content://settings/secure --method PUT_secure --arg $key --extra value:s:"
        else -> "settings put secure $key $original"
    })
}

/** SettingsProvider readback is cached; a fixture must also leave its owned service disabled on
 * the next emulator boot. Observe the persisted record before the gate stops the runtime. */
internal fun awaitActionServiceSettingsPersisted(original: String, shell: (String) -> String) {
    val service = "com.gezimos.katapult.lockscreen.LockscreenWidgetService"
    fun enabled(value: String?) = value?.split(':')?.any {
        ComponentName.unflattenFromString(it)?.className == service
    } == true
    val expected = enabled(original)
    val deadline = SystemClock.elapsedRealtime() + 15_000
    while (SystemClock.elapsedRealtime() < deadline) {
        // API31 persists ABX, not text XML. Decode to stdout without modifying the file.
        val value = persistedSecureSettings(shell)["enabled_accessibility_services"]
        if (enabled(value) == expected) return
        Thread.sleep(25)
    }
    throw AssertionError("Action Service setting did not persist its original enabled state")
}

private fun persistedSecureSettings(shell: (String) -> String): Map<String, String> {
    val persisted = shell("su 0 abx2xml /data/system/users/0/settings_secure.xml -")
    check(persisted.contains("settings") && !persisted.contains("Permission denied")) { "Cannot observe fixture settings persistence" }
    val parser = Xml.newPullParser().apply { setInput(persisted.reader()) }
    val values = mutableMapOf<String, String>()
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.END_TAG && parser.name == "settings") break
        if (parser.eventType == XmlPullParser.START_TAG && parser.name == "setting") {
            val name = parser.getAttributeValue(null, "name")
            val value = parser.getAttributeValue(null, "value")
            if (name != null && value != null) values[name] = value
        }
    }
    return values
}
