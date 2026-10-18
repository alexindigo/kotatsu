package com.gezimos.katapult.util

import android.content.Context

/** Which apps' notification counts may appear on the lock-screen panel and the screensaver. */
object LockscreenNotifications {
    fun filter(
        counts: Map<String, Int>,
        excluded: Set<String>,
        hiddenPackages: Set<String>,
        notifyWhileHidden: Set<String>,
    ): Map<String, Int> = counts.filter { (pkg, count) ->
        count > 0 && pkg !in excluded && (pkg !in hiddenPackages || pkg in notifyWhileHidden)
    }

    /** Launcher apps (not shortcuts) hidden from the app list, by the user or by default. */
    fun hiddenPackages(context: Context, prefs: PrefsManager): Set<String> {
        val hidden = prefs.getHiddenApps()
        val shown = prefs.getShownDefaultHiddenApps()
        return AppLoader.loadApps(context).filter { AppVisibility.isHidden(it, hidden, shown) }
            .map { it.packageName }.toSet()
    }

    fun forDisplay(context: Context, prefs: PrefsManager, counts: Map<String, Int>): Map<String, Int> =
        filter(counts, prefs.getLockscreenExcludedApps(), hiddenPackages(context, prefs), prefs.getNotifyWhileHiddenApps())
}
