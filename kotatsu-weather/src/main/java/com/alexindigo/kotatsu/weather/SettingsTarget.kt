package com.alexindigo.kotatsu.weather

/** What tapping the app icon opens: settings belong to a widget, so it depends on how many widgets exist. */
sealed interface SettingsTarget {
    /** No Kotatsu Weather widget yet: explain where to add one. */
    object NoWidget : SettingsTarget
    /** Exactly one widget: open its settings directly. */
    data class One(val widgetId: Int) : SettingsTarget
    /** Several widgets: ask which one, in this order. */
    data class Choose(val widgetIds: List<Int>) : SettingsTarget
}

fun settingsTarget(widgetIds: IntArray): SettingsTarget = when (widgetIds.size) {
    0 -> SettingsTarget.NoWidget
    1 -> SettingsTarget.One(widgetIds[0])
    else -> SettingsTarget.Choose(widgetIds.sorted())
}
