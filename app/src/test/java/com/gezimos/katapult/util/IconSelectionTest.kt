package com.gezimos.katapult.util

import org.junit.Assert.assertEquals
import org.junit.Test

class IconSelectionTest {
    @Test fun unmatchedAppsHaveNoInventedSymbol() {
        assertEquals(IconSelection.Original, IconSelections.resolve(null, null))
    }

    @Test fun automaticBrandsAreDefaultsNotUserChoices() {
        assertEquals(IconSelection.Catalogue("simple-icons:signal"),
            IconSelections.resolve(null, "simple-icons:signal"))
    }

    @Test fun userChoicesOverrideAnAutomaticBrandAcrossAllSources() {
        for (id in listOf("simple-icons:firefox", "tabler-icons:outline:zodiac-aquarius", "material-icons:outlined:music_note")) {
            assertEquals(IconSelection.Catalogue(id),
                IconSelections.resolve("catalogue:$id", "simple-icons:signal"))
        }
        assertEquals(IconSelection.Bundled("clock"), IconSelections.resolve("res:clock", "simple-icons:signal"))
        assertEquals(IconSelection.Imported("/saved/my image.png"),
            IconSelections.resolve("file:/saved/my image.png", "simple-icons:signal"))
    }

    @Test fun unknownRetainedChoicesDoNotBecomeAnotherBrand() {
        assertEquals(IconSelection.Original, IconSelections.resolve("future:my-icon", "simple-icons:signal"))
    }
}
