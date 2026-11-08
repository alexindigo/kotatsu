package com.alexindigo.kotatsu.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class QuoteCompositionInstrumentedTest {
    @Test fun contentUsesTheWholeAllocationAndNormalTypeScaleWithAnItalicAuthor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val metrics = context.resources.displayMetrics
        instrumentation.runOnMainSync {
            for (height in listOf(64, 112, 160)) for (author in listOf("Dieter Rams", "Staging collection", "")) {
                val width = (328 * metrics.density).toInt()
                val pixels = (height * metrics.density).toInt()
                val frame = FrameLayout(context)
                val view = QuoteWidgetViews.quote(context, Quote("Less, but better.", author), width, pixels).apply(context, frame)
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY))
                view.layout(0, 0, width, pixels)
                val paper = view.findViewById<View>(R.id.quote_paper)
                val text = view.findViewById<TextView>(R.id.quote_text)
                val lines = authorLineViews(view)
                assertEquals(listOf(0, 0, 0, 0), listOf(paper.paddingLeft, paper.paddingTop, paper.paddingRight, paper.paddingBottom))
                assertEquals(21f * metrics.scaledDensity, text.textSize, .001f)
                lines.forEach { attribution ->
                    assertEquals(14f * metrics.scaledDensity, attribution.textSize, .001f)
                    assertTrue("Attribution must be italic", attribution.typeface.style and Typeface.ITALIC != 0)
                }
                assertEquals(0, text.left)
                if (author.isNotEmpty()) assertEquals(width, lines.single().boundsIn(view as android.view.ViewGroup).right)
                val bitmap = Bitmap.createBitmap(width, pixels, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                view.draw(Canvas(bitmap))
                val bottom = (pixels - 1 downTo 0).first { y -> (0 until width).any { x -> Color.red(bitmap.getPixel(x, y)) < 128 } } + 1
                assertEquals("No provider-owned bottom band: $height/$author", pixels.toFloat(), bottom.toFloat(), 1f)
                bitmap.recycle()
            }
        }
    }
}
