package com.gezimos.katapult.util

import org.junit.Assert.*
import org.junit.Test

class ContrastMapTest {
    private val black = 0xff000000.toInt()
    private val white = 0xffffffff.toInt()
    private fun grey(value: Int) = black or (value * 0x010101)

    @Test fun uniformAndThreshold() {
        for (value in listOf(0, 117, 118, 128, 255)) {
            val result = ContrastMap.build(IntArray(12) { grey(value) }, 3, 4, 40, 60)
            assertTrue("grey $value", result.all { it == if (value >= 118) black else white })
        }
    }

    @Test fun usesSrgbGreyNotLinearLuminance() {
        for ((color, expected) in listOf(0xffff0000.toInt() to white, 0xff00ff00.toInt() to black,
            0xff0000ff.toInt() to white)) {
            assertTrue(ContrastMap.build(intArrayOf(color), 1, 1, 30, 30).all { it == expected })
        }
    }

    @Test fun centredCropPortraitLandscapeAndUpscaling() {
        for ((w, h) in listOf(240 to 800, 1000 to 200, 24 to 40)) {
            val pixels = IntArray(w * h) { if (it % w < w / 2) black else white }
            val result = ContrastMap.build(pixels, w, h, 480, 800)
            for (y in 0 until 800) {
                assertEquals(white, result[y * 480 + 230])
                assertEquals(black, result[y * 480 + 250])
                assertEquals("straight edge row $y", result.sliceArray(0 until 480).toList(),
                    result.sliceArray(y * 480 until (y + 1) * 480).toList())
            }
        }
    }

    @Test fun gaussianSevenMatchesPillowEdgeProfile() {
        // Pillow L image, x<40 = 30, otherwise 255, GaussianBlur(7), threshold 118.
        val result = ContrastMap.build(IntArray(80 * 30) { if (it % 80 < 40) grey(30) else white }, 80, 30, 80, 30)
        assertEquals(white, result[37])
        assertEquals(black, result[38])
        assertEquals(black, result[39])
    }
}
