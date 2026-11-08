package com.alexindigo.kotatsu.notes

import kotlin.math.ceil
import org.junit.Assert.*
import org.junit.Test

class QuoteLayoutTest {
    private fun measure(text: String, size: Float, width: Int, author: Boolean = false): QuoteTextMeasure =
        QuoteTextMeasure(width, ceil((ceil(text.length * size * .55 / width).coerceAtLeast(1.0)) * size * 1.3).toInt())

    @Test fun inkWidthCanRequireShrinkingEvenWhenTheWholeHeightFits() {
        val plan = planQuoteLayout(100, 160, 1f, 1f, Quote("Quote", "Italic author")) { _, size, width, author ->
            QuoteTextMeasure(if (author && size > 10f) width + 3 else width, 10)
        }
        assertTrue(plan.authorSize <= 10f)
        assertTrue(plan.authorWidth <= 100)
        assertTrue(plan.quoteHeight + plan.authorHeight + plan.gap <= 160)
    }
    @Test fun onePlannerFitsShortLongAuthoredAndAnonymousQuotesInEveryBox() {
        for (height in listOf(64, 112, 160)) for (scale in listOf(1f, 1.5f, 2f))
            for (quote in listOf(Quote("Brief"), Quote("Long quote ".repeat(60), "Author"), Quote("Quote", "Long author ".repeat(12)))) {
                val plan = planQuoteLayout(300, height, 1f, scale, quote, ::measure)
                assertTrue(plan.quoteHeight + plan.authorHeight + plan.gap + 2 * plan.padding <= height)
                assertTrue(plan.quoteSize > plan.authorSize)
                assertTrue(plan.quoteSize > 0)
                assertTrue(plan.quoteSize <= normalQuoteSizeSp(height.toFloat()) * scale)
                if (quote.author.isEmpty()) { assertEquals(0, plan.authorHeight); assertEquals(0, plan.gap) }
            }
    }
    @Test fun shortQuotesUseTheNormalTokenAndLongQuotesOnlyShrink() {
        val short = Quote("A short quote", "An author")
        for ((height, normal) in listOf(64 to 21f, 112 to 21f, 160 to 21f)) {
            assertEquals(normal, normalQuoteSizeSp(height.toFloat()), 0f)
            val normalPlan = planQuoteLayout(300, height, 1f, 1f, short, ::measure)
            val longPlan = planQuoteLayout(300, height, 1f, 1f, Quote("Words ".repeat(100), "An author"), ::measure)
            assertEquals(normal, normalPlan.quoteSize, 0f)
            assertTrue(longPlan.quoteSize < normalPlan.quoteSize)
            assertEquals(normalPlan.quoteSize * (2f / 3f), normalPlan.authorSize, 0f)
            assertEquals(0, normalPlan.padding)
            assertTrue(longPlan.quoteHeight + longPlan.authorHeight + longPlan.gap + 2 * longPlan.padding <= height)
        }
        assertEquals("Fitting text length cannot enlarge a quote within its box", planQuoteLayout(300, 112, 1f, 1f, short, ::measure).quoteSize,
            planQuoteLayout(300, 112, 1f, 1f, Quote("Brief"), ::measure).quoteSize, 0f)
    }

    @Test fun authorAndQuoteKeepTheFullSharedWrappingLaneInEveryBox() {
        val quote = Quote("The quote keeps its original wrapping lane.", "An author whose name can wrap ".repeat(8).trim())
        for (height in listOf(64, 112, 160)) {
            val lanes = mutableMapOf<String, MutableSet<Int>>()
            val plan = planQuoteLayout(300, height, 1f, 1f, quote) { text, size, width, author ->
                lanes.getOrPut(text) { mutableSetOf() }.add(width)
                measure(text, size, width, author)
            }
            assertEquals(setOf(300 - 2 * plan.padding), lanes[quote.text])
            assertEquals(lanes[quote.text], lanes[quote.author])
            assertTrue(plan.quoteHeight + plan.authorHeight + plan.gap + 2 * plan.padding <= height)
        }
    }
}
