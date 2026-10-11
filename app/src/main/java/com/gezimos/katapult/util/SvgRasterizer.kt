package com.gezimos.katapult.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import androidx.core.graphics.createBitmap
import com.caverock.androidsvg.SVG
import java.io.ByteArrayOutputStream
import java.io.InputStream

object SvgRasterizer {
    init {
        SVG.setInternalEntitiesEnabled(false)
    }

    /** Same geometry-preserving path for curated SVGs and user-imported SVGs. */
    fun rasterize(input: InputStream, sizePx: Int): Bitmap? {
        if (sizePx <= 0 || sizePx > 2048) return null
        return try {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 2_000_000) {
                val read = input.read(buffer, 0, minOf(buffer.size, 2_000_001 - output.size()))
                if (read < 0) break
                if (read > 0) output.write(buffer, 0, read)
            }
            val bytes = output.toByteArray()
            if (bytes.size > 2_000_000) return null
            val svg = SVG.getFromInputStream(bytes.inputStream())
            var viewBox = svg.documentViewBox
            if (viewBox == null) {
                val width = svg.documentWidth
                val height = svg.documentHeight
                if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
                svg.setDocumentViewBox(0f, 0f, width, height)
                viewBox = svg.documentViewBox
            }
            if (viewBox == null || !viewBox.width().isFinite() || !viewBox.height().isFinite() ||
                viewBox.width() <= 0f || viewBox.height() <= 0f) return null
            // Fixed SVG width/height are source units. Render into the requested
            // viewport, retaining the original viewBox and aspect-ratio contract.
            svg.setDocumentWidth("100%")
            svg.setDocumentHeight("100%")
            val bitmap = createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val inset = sizePx * 0.2f
            svg.renderToCanvas(Canvas(bitmap), RectF(inset, inset, sizePx - inset, sizePx - inset))
            val pixels = IntArray(sizePx * sizePx)
            bitmap.getPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
            if (pixels.none { Color.alpha(it) > 0 }) {
                bitmap.recycle()
                return null
            }
            bitmap
        } catch (error: Exception) {
            Log.w("KotatsuIcons", "Could not render SVG", error)
            null
        }
    }
}
