package com.gezimos.katapult

import android.app.Instrumentation
import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo

/** Input, shell and observation share one connection; default flags would reconnect and suppress the service. */
internal val Instrumentation.platformAutomation: UiAutomation
    get() = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).also { automation ->
        val info = automation.serviceInfo
        if (info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS == 0) {
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            automation.serviceInfo = info
        }
    }
