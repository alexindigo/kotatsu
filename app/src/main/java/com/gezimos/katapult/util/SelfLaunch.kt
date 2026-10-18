package com.gezimos.katapult.util

/**
 * Kotatsu's own app icon, tapped inside Kotatsu (grid, home slot), opens Kotatsu's Settings instead of
 * restarting Kotatsu. Other launchers start Kotatsu through Android's launcher entry and are unaffected.
 * Only the main entry qualifies: the Screensaver entry and published shortcuts launch normally.
 */
object SelfLaunch {
    fun opensSettings(selfPackage: String, mainActivity: String, packageName: String, activityName: String): Boolean =
        packageName == selfPackage && (activityName.isEmpty() || activityName == mainActivity)
}
