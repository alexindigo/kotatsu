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

    fun resolve(override: String?, automaticId: String?): IconSelection {
        // An unknown retained override must not silently become a brand guess.
        if (override != null) return when {
            override.startsWith(CATALOGUE_PREFIX) ->
                IconSelection.Catalogue(override.removePrefix(CATALOGUE_PREFIX))
            override.startsWith("file:") -> IconSelection.Imported(override.removePrefix("file:"))
            override.startsWith("res:") -> IconSelection.Bundled(override.removePrefix("res:"))
            else -> IconSelection.Original
        }
        return automaticId?.let(IconSelection::Catalogue) ?: IconSelection.Original
    }
}
