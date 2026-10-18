package com.gezimos.katapult.util

/**
 * Kotatsu's chosen default icons for apps whose installed icon does not suit the e-ink grid
 * (photographic, full-colour or missing monochrome artwork). Values use the saved-choice syntax
 * ([IconSelections]); a user's manual choice still wins, and these are never saved as overrides.
 */
object DefaultIcons {
    private val byPackage: Map<String, String> = mapOf(
        "com.android.calendar" to "res:calendar",
        "com.android.deskclock" to "res:mudita_clock",
        "com.b44t.messenger" to "res:delta_chat",
        "com.android.documentsui" to "res:mudita_folder",
        "com.android.fmradio" to "res:radio",
        "com.android.gallery3d" to "res:gallery",
        "com.android.quicksearchbox" to "res:search",
        "com.android.settings" to "res:settings",
        "com.android.stk" to "res:sim",
        "org.chromium.webview_shell" to "res:chromium",
        "com.android.webview" to "catalogue:simple-icons:googlechrome",
    )

    val references: Map<String, String> get() = byPackage

    fun reference(packageName: String): String? = byPackage[packageName]
}
