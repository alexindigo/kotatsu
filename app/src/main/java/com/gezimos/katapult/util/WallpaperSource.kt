package com.gezimos.katapult.util

/** Missing source keys are old installs/backups, interpreted on every production read. */
enum class WallpaperSource {
    NONE, KOTATSU, PHOTO;

    companion object {
        fun read(stored: String?, photoPath: String?): WallpaperSource {
            val source = entries.firstOrNull { it.name == stored }
                ?: if (photoPath != null) PHOTO else NONE
            return if (source == PHOTO && photoPath == null) NONE else source
        }
    }
}
