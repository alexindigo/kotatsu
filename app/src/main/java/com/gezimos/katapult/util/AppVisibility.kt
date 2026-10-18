package com.gezimos.katapult.util

import com.gezimos.katapult.model.AppModel

/**
 * The one visibility rule for the app grid. Some apps are hidden by default (stock apps, see
 * [StockApps], and the packages below); they stay hidden until the user unchecks them. Every other
 * app is shown until the user checks it in Hidden Apps. Only the user's departures from these
 * defaults are stored, so the defaults also reach existing installs and apps installed later.
 */
object AppVisibility {
    /** Tools a child's phone does not need in the grid: app stores, keyboard settings, system updates. */
    val hiddenByDefaultPackages = setOf(
        "org.fdroid.fdroid",
        "org.fdroid.basic",
        "org.futo.inputmethod.latin",
        "com.mudita.system.updater",
    )

    fun hiddenByDefault(app: AppModel): Boolean =
        app.stock || (app.shortcutId.isEmpty() && app.packageName in hiddenByDefaultPackages)

    /** [hidden]: apps the user hid; [shown]: hidden-by-default apps the user unhid. */
    fun isHidden(app: AppModel, hidden: Set<String>, shown: Set<String>): Boolean =
        app.key in hidden || (hiddenByDefault(app) && app.key !in shown)
}
