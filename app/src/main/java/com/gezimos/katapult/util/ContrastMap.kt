package com.gezimos.katapult.util

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Screen-pixel ink, independent of theme. The input copy is never modified. */
object ContrastMap {
    const val THRESHOLD = 118

    fun build(pixels: IntArray, sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): IntArray {
        require(sourceWidth > 0 && sourceHeight > 0 && width > 0 && height > 0)
        require(pixels.size == sourceWidth * sourceHeight)
        val scale = max(width.toDouble() / sourceWidth, height.toDouble() / sourceHeight)
        val cropX = (sourceWidth * scale - width) / 2
        val cropY = (sourceHeight * scale - height) / 2
        var grey = IntArray(width * height) { index ->
            // Pixel centres, with edge extension, match the centred ContentScale.Crop image.
            val sx = ((index % width + cropX + .5) / scale - .5).coerceIn(0.0, sourceWidth - 1.0)
            val sy = ((index / width + cropY + .5) / scale - .5).coerceIn(0.0, sourceHeight - 1.0)
            val x = sx.toInt()
            val y = sy.toInt()
            val dx = sx - x
            val dy = sy - y
            fun sample(xx: Int, yy: Int) = luminance(pixels[yy * sourceWidth + xx])
            val right = (x + 1).coerceAtMost(sourceWidth - 1)
            val bottom = (y + 1).coerceAtMost(sourceHeight - 1)
            val top = sample(x, y) * (1 - dx) + sample(right, y) * dx
            val low = sample(x, bottom) * (1 - dx) + sample(right, bottom) * dx
            (top * (1 - dy) + low * dy).roundToInt()
        }
        // Pillow GaussianBlur(7): sigma=7, three extended fractional box passes per axis.
        // See Pillow src/libImaging/BoxBlur.c (_gaussian_blur_radius / ImagingBoxBlur).
        val sigma2 = 7f * 7f / 3
        val length = sqrt(12.0 * sigma2 + 1).toFloat()
        val radius = floor((length - 1) / 2)
        val fraction = (2 * radius + 1) * (radius * (radius + 1) - 3 * sigma2) /
            (6 * (sigma2 - (radius + 1) * (radius + 1)))
        val boxRadius = radius + fraction
        repeat(3) { grey = boxBlur(grey, width, height, boxRadius, false) }
        repeat(3) { grey = boxBlur(grey, width, height, boxRadius, true) }
        return IntArray(grey.size) { if (grey[it] >= THRESHOLD) 0xff000000.toInt() else 0xffffffff.toInt() }
    }

    private fun luminance(color: Int): Int =
        (((color ushr 16 and 255) * .299) + ((color ushr 8 and 255) * .587) +
            (color and 255) * .114).roundToInt()

    private fun boxBlur(input: IntArray, width: Int, height: Int, radius: Float, vertical: Boolean): IntArray {
        val whole = radius.toInt()
        val weight = ((1 shl 24) / (radius * 2 + 1)).toLong()
        val farWeight = ((1 shl 24) - (whole * 2 + 1) * weight) / 2
        val length = if (vertical) height else width
        val lines = if (vertical) width else height
        val output = IntArray(input.size)
        repeat(lines) { line ->
            fun index(at: Int) = if (vertical) at * width + line else line * width + at
            fun value(at: Int) = input[index(at.coerceIn(0, length - 1))]
            var sum = 0L
            for (at in -whole..whole) sum += value(at)
            repeat(length) { at ->
                val bulk = sum * weight + (value(at - whole - 1) + value(at + whole + 1)) * farWeight
                output[index(at)] = ((bulk + (1 shl 23)) shr 24).toInt()
                sum += value(at + whole + 1) - value(at - whole)
            }
        }
        return output
    }
}
