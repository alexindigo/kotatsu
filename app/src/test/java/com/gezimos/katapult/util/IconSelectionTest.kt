package com.gezimos.katapult.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IconSelectionTest {
    @Test fun unmatchedAppsHaveNoInventedSymbol() {
        assertEquals(IconSelection.Original, IconSelections.resolve(null, null))
    }

    @Test fun automaticBrandsAreDefaultsNotUserChoices() {
        assertEquals(IconSelection.Catalogue("simple-icons:signal"),
            IconSelections.resolve(null, "catalogue:simple-icons:signal"))
    }

    @Test fun userChoicesOverrideAnAutomaticBrandAcrossAllSources() {
        for (id in listOf("simple-icons:firefox", "tabler-icons:outline:zodiac-aquarius", "material-icons:outlined:music_note")) {
            assertEquals(IconSelection.Catalogue(id),
                IconSelections.resolve("catalogue:$id", "catalogue:simple-icons:signal"))
        }
        assertEquals(IconSelection.Bundled("clock"), IconSelections.resolve("res:clock", "catalogue:simple-icons:signal"))
        assertEquals(IconSelection.Imported("/saved/my image.png"),
            IconSelections.resolve("file:/saved/my image.png", "catalogue:simple-icons:signal"))
    }

    @Test fun unknownRetainedChoicesDoNotBecomeAnotherBrand() {
        assertEquals(IconSelection.Original, IconSelections.resolve("future:my-icon", "catalogue:simple-icons:signal"))
    }

    @Test fun kotatsuDefaultsApplyOnlyWithoutAManualChoice() {
        assertEquals(IconSelection.Bundled("radio"), IconSelections.resolve(null, DefaultIcons.reference("com.android.fmradio")))
        assertEquals(IconSelection.Catalogue("simple-icons:googlechrome"),
            IconSelections.resolve(null, DefaultIcons.reference("com.android.webview")))
        assertEquals(IconSelection.Catalogue("tabler-icons:outline:radio"),
            IconSelections.resolve("catalogue:tabler-icons:outline:radio", DefaultIcons.reference("com.android.fmradio")))
        assertEquals(IconSelection.Original, IconSelections.resolve(null, DefaultIcons.reference("org.example.unlisted")))
    }

    @Test fun everyKotatsuDefaultIsADrawableReference() {
        assertEquals(setOf("com.android.calendar", "com.android.deskclock", "com.b44t.messenger",
            "com.android.documentsui", "com.android.fmradio", "com.android.gallery3d", "com.android.quicksearchbox",
            "com.android.settings", "com.android.stk", "org.chromium.webview_shell", "com.android.webview"),
            DefaultIcons.references.keys)
        for ((pkg, reference) in DefaultIcons.references) {
            val selection = IconSelections.parse(reference)
            assertNotEquals("$pkg: $reference", IconSelection.Original, selection)
            assertNotEquals("$pkg: $reference", true, selection is IconSelection.Imported)
        }
    }
}
