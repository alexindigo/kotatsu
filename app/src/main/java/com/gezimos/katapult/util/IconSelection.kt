package com.gezimos.katapult.util

/** Source selection is independent of drawing, preferences and any UI consumer. */
sealed interface IconSelection {
    data class Catalogue(val id: String) : IconSelection
    data class Imported(val path: String) : IconSelection
    data class Bundled(val name: String) : IconSelection
    data object Original : IconSelection
}

object IconSelections {
    const val CATALOGUE_PREFIX = "catalogue:"
    const val BUNDLED_PREFIX = "res:"
    const val IMPORTED_PREFIX = "file:"

    /**
     * [override] is the user's saved choice; [automatic] is the app's default reference
     * (Kotatsu's own default, else an exact catalogue brand match). Both use the saved-choice syntax.
     */
    fun resolve(override: String?, automatic: String?): IconSelection {
        // An unknown retained override must not silently become a brand guess.
        if (override != null) return parse(override)
        return automatic?.let(::parse) ?: IconSelection.Original
    }

    fun parse(reference: String): IconSelection = when {
        reference.startsWith(CATALOGUE_PREFIX) -> IconSelection.Catalogue(reference.removePrefix(CATALOGUE_PREFIX))
        reference.startsWith(IMPORTED_PREFIX) -> IconSelection.Imported(reference.removePrefix(IMPORTED_PREFIX))
        reference.startsWith(BUNDLED_PREFIX) -> IconSelection.Bundled(reference.removePrefix(BUNDLED_PREFIX))
        else -> IconSelection.Original
    }
}
