package com.gezimos.katapult.ui.settings

import android.content.Intent
import android.content.pm.PackageManager

fun interface AppStorePackageLookup {
    fun isInstalled(packageName: String): Boolean
}

interface AppStorePackages : AppStorePackageLookup {
    fun launchIntent(packageName: String): Intent?
}

/** Resolve independently of the launcher's hidden-app/grid filters. */
class AndroidAppStorePackages(private val packages: PackageManager) : AppStorePackages {
    override fun isInstalled(packageName: String): Boolean = try {
        packages.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) { false }

    override fun launchIntent(packageName: String): Intent? = packages.getLaunchIntentForPackage(packageName)
}

fun preferredFdroidPackage(packages: AppStorePackageLookup): String? =
    listOf("org.fdroid.fdroid", "org.fdroid.basic").firstOrNull(packages::isInstalled)
