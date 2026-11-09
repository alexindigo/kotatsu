package com.gezimos.katapult.lockscreen
import com.gezimos.katapult.ui.settings.ScrollRailGeometry

import androidx.compose.ui.unit.Density
import org.junit.Assert.*
import org.junit.Test

class ScrollRailGeometryTest {
    @Test fun contentEdgesFollowBothTriangleTipsAtEveryDensityAndCellHeight() {
        for (scale in listOf(1f, 1.33125f, 2f, 2.5f)) {
            val density = Density(scale)
            val (top, bottom) = ScrollRailGeometry.contentInsets(density)
            for (height in listOf(360, 721, 1440)) {
                assertEquals(ScrollRailGeometry.topTip(density), top.toFloat(), .5f)
                assertEquals(ScrollRailGeometry.bottomTip(height, density), (height - bottom).toFloat(), .5f)
                assertTrue(top > 0 && bottom > 0 && top + bottom < height)
            }
        }
    }
}
