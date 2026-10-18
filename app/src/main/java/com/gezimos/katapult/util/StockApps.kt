package com.gezimos.katapult.util

/**
 * Stock apps: the preinstalled apps Mudita's own launcher leaves out of its app list. Its rule
 * (KompaktLauncher's app query): a preinstalled (FLAG_SYSTEM) app is listed only when its package
 * starts with "com.mudita"; apps the user installed are always listed. Applied on the Kompakt only,
 * since elsewhere it would match the phone's own Phone, Messages and Camera.
 *
 * Stock apps are hidden by default (see AppVisibility) and listed under System Apps.
 */
object StockApps {
    private const val MUDITA_PREFIX = "com.mudita"

    fun isStock(onKompakt: Boolean, preinstalled: Boolean, packageName: String): Boolean =
        onKompakt && preinstalled && !packageName.startsWith(MUDITA_PREFIX)
}
