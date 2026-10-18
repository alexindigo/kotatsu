package com.gezimos.katapult.model

data class AppModel(
    val packageName: String,
    val label: String,
    val activityName: String,
    val shortcutId: String = "",
    /** A stock (non-Mudita preinstalled) app on the Kompakt; see StockApps. */
    val stock: Boolean = false,
) {
    val key: String = if (shortcutId.isEmpty()) packageName else "$packageName|$shortcutId"
}
