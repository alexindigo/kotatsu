package com.alexindigo.kotatsu.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class QuoteInkInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun textViews(root: View): List<TextView> = when (root) {
        is TextView -> listOf(root)
        is ViewGroup -> (0 until root.childCount).flatMap { textViews(root.getChildAt(it)) }
        else -> emptyList()
    }

    private fun layout(root: View, width: Int, height: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
    }

    /** Compare actual native drawing with the SAME shaped Layout on an unclipped canvas. */
    private fun assertCompleteInk(label: TextView) {
        label.onPreDraw()
        assertEquals("Every original character reaches native layout", label.length(), label.layout.getLineEnd(label.lineCount - 1))
        assertEquals("Ellipsis must not replace glyphs", 0, label.layout.getEllipsisCount(label.lineCount - 1))
        val guard = 12
        fun bitmap() = Bitmap.createBitmap(label.width + 2 * guard, label.height + 2 * guard,
            Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val actual = bitmap()
        Canvas(actual).apply { translate((guard - label.scrollX).toFloat(), (guard - label.scrollY).toFloat()); label.draw(this) }
        val reference = bitmap()
        Canvas(reference).apply {
            translate((guard + label.compoundPaddingLeft - label.scrollX).toFloat(),
                (guard + label.extendedPaddingTop - label.scrollY).toFloat())
            label.layout.draw(this)
        }
        var missing = 0
        for (y in 0 until actual.height) for (x in 0 until actual.width) {
            if (reference.getPixel(x, y) != Color.WHITE) {
                assertTrue("Ink escapes physical view bounds in '${label.text}' at $x,$y",
                    x in guard until guard + label.width && y in guard until guard + label.height)
                if (actual.getPixel(x, y) == Color.WHITE) missing++
            }
        }
        assertEquals("Clipped ink in '${label.text}' at ${label.textSize}px width=${label.width}", 0, missing)
        actual.recycle(); reference.recycle()
    }

    private fun assertAuthor(root: View, width: Int, direction: Int, author: String) {
        val lines = textViews(root.findViewById(R.id.quote_author))
        assertEquals(author, lines.joinToString("") { it.text.toString() })
        assertTrue(lines.isNotEmpty())
        for (line in lines) {
            assertCompleteInk(line)
            assertEquals("Ink-sized author lines must not depend on horizontal scrolling", 0, line.scrollX)
            val pixels = Bitmap.createBitmap(line.width, line.height, Bitmap.Config.ARGB_8888)
            pixels.eraseColor(Color.WHITE)
            Canvas(pixels).apply { translate(-line.scrollX.toFloat(), -line.scrollY.toFloat()); line.draw(this) }
            val clear = (0 until line.height).none { pixels.getPixel(line.width - 1, it) != Color.WHITE }
            if (!clear) fail("Author touches its right clip bound: $author; size=${line.textSize} width=${line.width} dir=${line.layoutDirection}/${line.layout.getParagraphDirection(0)} scroll=${line.scrollX} flags=${line.paint.flags} skew=${line.paint.textSkewX} tracking=${line.letterSpacing} plan=${QuoteAuthorViews.plan(line.text.toString(), line.textSize, width, line.typeface)}")
            val xs = (0 until line.width).filter { x -> (0 until line.height).any { y -> pixels.getPixel(x, y) != Color.WHITE } }
            assertTrue(xs.isNotEmpty())
            val bounds = line.boundsIn(root as ViewGroup)
            val left = bounds.left + xs.first()
            val right = bounds.left + xs.last() + 1
            if (direction == View.LAYOUT_DIRECTION_LTR) assertEquals("Visible author ink meets content END", width.toFloat(), right.toFloat(), 1f)
            else assertEquals("RTL visible author ink meets content END", 0f, left.toFloat(), 1f)
            pixels.recycle()
        }
        assertCompleteInk(root.findViewById(R.id.quote_text))
    }

    private val quotes = listOf("Less, but better.", "Time is not found. It is made, one deliberate choice at a time, by leaving room in the day for the things that matter most.")
    private val authors = listOf("Dieter Rams", "Staging collection", "f", "An author with a longer name ending in f ".repeat(3), "\u05d0\u05dc\u05d9 \u05db\u05d4\u05df")

    @Test fun authorInkIsCompleteGuardedAndEndAlignedInEverySizeAndDirection() {
        val metrics = context.resources.displayMetrics
        instrumentation.runOnMainSync {
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL))
                for (height in listOf(64, 112, 160))
                    for (author in authors) for (quote in quotes) {
                        val width = (329 * metrics.density).toInt()
                        val frame = FrameLayout(context).apply { layoutDirection = direction }
                        val root = QuoteWidgetViews.quote(context, Quote(quote, author), width,
                            (height * metrics.density).toInt()).apply(context, frame)
                        root.layoutDirection = direction
                        layout(root, width, (height * metrics.density).toInt())
                        assertAuthor(root, width, direction, author)
                    }
        }
    }

    @Test fun thePhonesLatoFontHasCompleteInkAtEverySizeAndDirection() {
        val metrics = context.resources.displayMetrics
        val regular = context.resources.getFont(R.font.lato_medium)
        val italic = Typeface.create(regular, Typeface.ITALIC)
        instrumentation.runOnMainSync {
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) for (height in listOf(64, 112, 160))
                for (author in authors) for (text in quotes) {
                    val width = (329 * metrics.density).toInt()
                    val pixels = (height * metrics.density).toInt()
                    val quote = Quote(text, author)
                    val plan = planQuoteLayout(width, pixels, metrics.density, metrics.scaledDensity, quote) { value, size, lane, isAuthor ->
                        if (isAuthor) QuoteAuthorViews.plan(value, size, lane, italic).measured else QuoteWidgetViews.measureText(value, size, lane, regular)
                    }
                    val root = QuoteWidgetViews.render(context, quote, plan, QuoteAuthorViews.plan(author, plan.authorSize, width, italic))
                        .apply(context, FrameLayout(context).apply { layoutDirection = direction })
                    root.layoutDirection = direction
                    // Platform font fixture: the emulator lacks the phone's named system family.
                    // Text, dimensions, spans and geometry remain the production renderer's output.
                    root.findViewById<TextView>(R.id.quote_text).typeface = regular
                    authorLineViews(root).forEach { it.typeface = italic }
                    layout(root, width, pixels)
                    assertAuthor(root, width, direction, author)
                }
        }
    }
}
