package com.alexindigo.kotatsu.notes

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

enum class WidgetKind(val provider: Class<*>) {
    TASK(NotesWidgetProvider::class.java), QUOTE(QuoteWidgetProvider::class.java);

    fun enabled(context: Context): Boolean = when (context.packageManager.getComponentEnabledSetting(component(context))) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> context.resources.getBoolean(R.bool.widget_supported)
        else -> false
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(component(context),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
        // Disabling a provider deletes Android's instances; reconcile jobs and alarms afterwards.
        NotesRuntime.refresh(context)
        QuoteRuntime.refresh(context)
    }

    private fun component(context: Context) = ComponentName(context, provider)
}
