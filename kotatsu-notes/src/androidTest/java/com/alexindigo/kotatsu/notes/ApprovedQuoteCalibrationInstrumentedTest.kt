package com.alexindigo.kotatsu.notes

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.graphics.text.LineBreaker
import android.view.View
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Reference recovery from305a117's unmodified planner, using the phone's byte-identical Lato file. */
class ApprovedQuoteCalibrationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val quote = Quote("Time is not found. It is made, one deliberate choice at a time, by leaving room in the day for the things that matter most.", "Staging collection")
    private val density = context.resources.displayMetrics.density
    private val scaledDensity = context.resources.displayMetrics.scaledDensity

    private fun measure(text: String, size: Float, width: Int): Int {
        val paint = TextPaint().apply { typeface = context.resources.getFont(R.font.lato_medium); textSize = size }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width).setIncludePad(true)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build().height
    }

    private fun historicalPlan(width: Int, height: Int): QuoteLayout {
        val padding = minOf(8 * density, width / 32f, height / 16f).toInt()
        val lane = (width - 2 * padding).coerceAtLeast(1)
        val available = height - 2 * padding
        val maximum = when {
            height <= 64 * density -> 24f
            height <= 112 * density -> 30f
            else -> 36f
        } * scaledDensity
        fun layout(size: Float): QuoteLayout {
            val author = size * .65f
            val gap = (size * .3f).toInt()
            return QuoteLayout(padding, size, author, gap, measure(quote.text, size, lane), measure(quote.author, author, lane))
        }
        var low = .001f
        var high = maximum
        repeat(24) {
            val size = (low + high) / 2
            val plan = layout(size)
            if (plan.quoteHeight + plan.authorHeight + plan.gap <= available) low = size else high = size
        }
        return layout(low)
    }

    @Test fun recoverApprovedMediumSize() {
        val plan = historicalPlan((344 * density).toInt(), (112 * density).toInt())
        android.util.Log.i("ApprovedQuoteCalibration", "historical=$plan density=$density scaledDensity=$scaledDensity normalSp=${plan.quoteSize / scaledDensity}")
        assertEquals("Historical reference remains reproducible", 18.028166f, plan.quoteSize / scaledDensity, 0f)
        assertTrue("The new normal is deliberately larger than the old approved long fit",
            normalQuoteSizeSp(112f) * scaledDensity > plan.quoteSize)
        val short = Quote("Less, but better.", "Dieter Rams")
        val shortPlan = planQuoteLayout((328 * density).toInt(), (112 * density).toInt(), density, scaledDensity, short) { text, size, width, _ ->
            QuoteTextMeasure(width, measure(text, size, width))
        }
        assertEquals("A short quote uses the lock-screen normal size", 21f * scaledDensity, shortPlan.quoteSize, 0f)
        val shortInk = android.graphics.Rect()
        TextPaint().apply {
            typeface = context.resources.getFont(R.font.lato_medium); textSize = shortPlan.quoteSize
            getTextBounds(short.text, 0, short.text.length, shortInk)
        }
        assertTrue("Restore the approved scale between the rejected19px and35px glyphs", shortInk.height() > 19 && shortInk.height() < 35)
        android.util.Log.i("ApprovedQuoteCalibration", "historical=$plan short=$shortPlan shortInk=$shortInk")
        instrumentation.runOnMainSync {
            val text = TextView(context).apply {
                typeface = context.resources.getFont(R.font.lato_medium)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, plan.quoteSize)
                this.text = quote.text
                breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            }
            text.measure(View.MeasureSpec.makeMeasureSpec(460 - 2 * plan.padding, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            text.layout(0, 0, text.measuredWidth, text.measuredHeight)
            val lines = (0 until text.lineCount).map { quote.text.substring(text.layout.getLineStart(it), text.layout.getLineEnd(it)).trim() }
            android.util.Log.i("ApprovedQuoteCalibration", "nativeSizePx=${text.textSize} pitch=${text.lineHeight} height=${text.height} lines=$lines")
            assertEquals(listOf("Time is not found. It is made, one", "deliberate choice at a time, by leaving room", "in the day for the things that matter most."), lines)
            assertEquals(29f, text.lineHeight.toFloat(), 1f)
        }
    }
}
