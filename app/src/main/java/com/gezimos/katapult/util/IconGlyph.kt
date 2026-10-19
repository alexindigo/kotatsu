package com.gezimos.katapult.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap

/** Sizing for glyph-only artwork; original apps' framed icons are not glyphs. */
object IconGlyph {
    const val MAX_SHARE = 0.54f

    /** Mudita artwork, drawn as Mudita drew it (including bundled stock-app replacements). */
    fun isMuditaArtwork(resourceName: String): Boolean = resourceName.startsWith("mudita_")

    /** Cap visible ink, not drawable/viewBox bounds. Smaller artwork stays pixel-identical. */
    fun cap(bitmap: Bitmap): Bitmap {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (Color.alpha(pixels[y * bitmap.width + x]) >= 128) {
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x)
                bottom = maxOf(bottom, y)
            }
        }
        val extent = maxOf(right - left + 1, bottom - top + 1)
        val limit = minOf(bitmap.width, bitmap.height) * MAX_SHARE
        if (extent <= limit) return bitmap

        val scale = limit / extent
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(bitmap.width / 2f - (left + right + 1) / 2f * scale,
                bitmap.height / 2f - (top + bottom + 1) / 2f * scale)
        }
        return createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }
}
