package com.gezimos.katapult.util

import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperSourceTest {
    @Test fun missingKeyInExistingInstallsAndOldImportsUsesThePhotoPath() {
        assertEquals(WallpaperSource.NONE, WallpaperSource.read(null, null))
        assertEquals(WallpaperSource.PHOTO, WallpaperSource.read(null, "/owned/wallpaper.png"))
    }

    @Test fun explicitChoicesWinOverLeftoverPaths() {
        for (path in listOf(null, "/owned/wallpaper.png")) {
            assertEquals(WallpaperSource.NONE, WallpaperSource.read("NONE", path))
            assertEquals(WallpaperSource.KOTATSU, WallpaperSource.read("KOTATSU", path))
        }
        assertEquals(WallpaperSource.PHOTO, WallpaperSource.read("PHOTO", "/owned/wallpaper.png"))
    }

    @Test fun importedPhotoWithNoUsableFileResolvesToNone() {
        assertEquals(WallpaperSource.NONE, WallpaperSource.read("PHOTO", null))
        assertEquals(WallpaperSource.NONE, WallpaperSource.read("unknown", null))
        assertEquals(WallpaperSource.PHOTO, WallpaperSource.read("unknown", "/owned/wallpaper.png"))
    }
}
