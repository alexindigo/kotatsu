package com.gezimos.katapult

import android.os.SystemClock
import android.content.ComponentName
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

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
        val persisted = shell("su 0 abx2xml /data/system/users/0/settings_secure.xml -")
        check(persisted.contains("settings") && !persisted.contains("Permission denied")) {
            "Cannot observe fixture settings persistence"
        }
        val parser = Xml.newPullParser().apply { setInput(persisted.reader()) }
        var value: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            // SettingsProvider appends a separate namespaceHashes root after settings.
            if (parser.eventType == XmlPullParser.END_TAG && parser.name == "settings") break
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "setting" &&
                parser.getAttributeValue(null, "name") == "enabled_accessibility_services") {
                value = parser.getAttributeValue(null, "value")
                break
            }
        }
        if (enabled(value) == expected) return
        Thread.sleep(25)
    }
    throw AssertionError("Action Service setting did not persist its original enabled state")
}
