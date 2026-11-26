package com.alexindigo.kotatsu.sync

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncIconInstrumentedTest {
    @Test fun syncGlyphIs54PercentInsideUnchangedFrame() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Frozen Kotatsu Weather frame. Sync must match without moving any frame pixels.
        val frame = PathParser.createPathFromPathData("M19.25,2.5C9.999,2.5 2.5,9.999 2.5,19.25V61.75C2.5,71.001 9.999,78.5 19.25,78.5H61.75C71.001,78.5 78.5,71.001 78.5,61.75V19.25C78.5,9.999 71.001,2.5 61.75,2.5H19.25ZM0,19.25C0,8.619 8.619,0 19.25,0H61.75C72.382,0 81,8.619 81,19.25V61.75C81,72.382 72.382,81 61.75,81H19.25C8.619,81 0,72.382 0,61.75V19.25Z")!!.apply { fillType = Path.FillType.EVEN_ODD }
        for (size in listOf(107, 192)) {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            context.packageManager.getApplicationIcon(context.applicationInfo).apply {
                setBounds(0, 0, size, size)
                draw(Canvas(bitmap))
            }
            val expectedFrame = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            Canvas(expectedFrame).apply {
                scale(size / 81f, size / 81f)
                drawPath(frame, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
            }
            val inset = (size * 16f / 107f).toInt()
            var top = size
            var bottom = -1
            for (y in 0 until size) for (x in 0 until size) {
                val pixel = bitmap.getPixel(x, y)
                if (x < inset || y < inset || x >= size - inset || y >= size - inset) {
                    assertEquals("Frame pixel $size/$x/$y", expectedFrame.getPixel(x, y), pixel)
                } else if (Color.alpha(pixel) >= 128) {
                    top = minOf(top, y)
                    bottom = maxOf(bottom, y)
                }
            }
            assertEquals("Sync glyph height/$size", 0.54f, (bottom - top + 1).toFloat() / size, 0.02f)
        }
    }
}
