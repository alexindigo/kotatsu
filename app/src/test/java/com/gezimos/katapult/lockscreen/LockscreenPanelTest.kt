package com.gezimos.katapult.lockscreen

import org.junit.Assert.*
import org.junit.Test

class LockscreenPanelTest {
    private val launchable = linkedMapOf(
        "alpha" to "AlphaActivity", "beta" to "BetaActivity", "gamma" to "GammaActivity",
        "delta" to "DeltaActivity", "epsilon" to "EpsilonActivity", "zeta" to "ZetaActivity",
        "eta" to "EtaActivity", "theta" to "ThetaActivity", "iota" to "IotaActivity",
    )

    @Test fun savedHomeOrderIsPreservedWithLaunchActivities() {
        assertEquals(listOf("gamma" to "GammaActivity", "alpha" to "AlphaActivity"),
            selectPanelApps(listOf("gamma", "alpha"), emptySet(), launchable, 2))
    }

    @Test fun hiddenShortcutAndNonLaunchableKeysAreSkippedAlsoDuringFill() {
        val apps = launchable + ("beta|shortcut" to "ShortcutActivity")
        val selected = selectPanelApps(listOf("alpha", "beta|shortcut", "missing", "gamma"),
            setOf("alpha", "delta"), apps, 4)
        assertEquals(listOf("gamma", "beta", "epsilon", "zeta"), selected.map { it.first })
    }

    @Test fun fillsFromLabelOrderedLaunchableMapWithoutDuplicates() {
        assertEquals(listOf("gamma", "alpha", "beta", "delta"),
            selectPanelApps(listOf("gamma", "gamma"), emptySet(), launchable, 4).map { it.first })
    }

    @Test fun fourAndEightLimitTheResultAndKeepTheSamePrefix() {
        val order = listOf("theta", "eta", "zeta", "epsilon", "delta", "gamma", "beta", "alpha", "iota")
        for (count in listOf(4, 8)) {
            val result = selectPanelApps(order, emptySet(), launchable, count)
            assertEquals(count, result.size)
            assertEquals(order.take(count), result.map { it.first })
        }
    }

    @Test fun emptyAndExhaustedSourcesReturnOnlyAvailableApps() {
        assertTrue(selectPanelApps(emptyList(), emptySet(), emptyMap(), 8).isEmpty())
        assertTrue(selectPanelApps(emptyList(), emptySet(), launchable, 0).isEmpty())
        assertEquals(listOf("alpha" to "AlphaActivity"),
            selectPanelApps(emptyList(), launchable.keys - "alpha", launchable, 8))
    }
}
