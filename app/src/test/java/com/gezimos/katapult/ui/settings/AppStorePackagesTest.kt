package com.gezimos.katapult.ui.settings

import org.junit.Assert.*
import org.junit.Test

class AppStorePackagesTest {
    @Test fun fullFdroidIsPreferredWhenBothAreInstalled() {
        val queried = mutableListOf<String>()
        assertEquals("org.fdroid.fdroid", preferredFdroidPackage { queried.add(it); true })
        assertEquals(listOf("org.fdroid.fdroid"), queried)
    }

    @Test fun basicIsUsedWhenFullFdroidIsMissing() {
        val queried = mutableListOf<String>()
        assertEquals("org.fdroid.basic", preferredFdroidPackage { queried.add(it); it == "org.fdroid.basic" })
        assertEquals(listOf("org.fdroid.fdroid", "org.fdroid.basic"), queried)
    }

    @Test fun neitherInstalledIsMissing() {
        assertNull(preferredFdroidPackage { false })
    }
}
