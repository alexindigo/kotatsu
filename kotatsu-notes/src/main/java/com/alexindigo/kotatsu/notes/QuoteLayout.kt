package com.alexindigo.kotatsu.notes

data class QuoteLayout(val padding: Int, val quoteSize: Float, val authorSize: Float, val gap: Int,
    val quoteHeight: Int, val authorHeight: Int, val quoteWidth: Int = 0, val authorWidth: Int = 0)

data class QuoteTextMeasure(val width: Int, val height: Int)

// Subordinate to the stock date, but more deliberate than the old 18sp quote.
// One type scale at every allocation: extra blank paper never enlarges the text.
internal const val NORMAL_QUOTE_SP = 21f

fun normalQuoteSizeSp(heightDp: Float): Float {
    require(heightDp > 0)
    return NORMAL_QUOTE_SP
}

/** Sole sizing owner. Measured wrapping is an input, as font metrics are for Weather's row planner. */
fun planQuoteLayout(width: Int, height: Int, density: Float, scaledDensity: Float, quote: Quote,
    measure: (String, Float, Int, Boolean) -> QuoteTextMeasure): QuoteLayout {
    require(width > 0 && height > 0 && density > 0 && scaledDensity > 0)
    // The offered rectangle is already the host's content lane.
    val padding = 0
    val availableWidth = width
    val availableHeight = height
    val maximum = normalQuoteSizeSp(height / density) * scaledDensity
    fun layout(size: Float): QuoteLayout {
        val authorSize = size * (2f / 3f)
        val gap = if (quote.author.isEmpty()) 0 else (size * .3f).toInt()
        val text = measure(quote.text, size, availableWidth, false)
        val author = if (quote.author.isEmpty()) QuoteTextMeasure(0, 0) else measure(quote.author, authorSize, availableWidth, true)
        return QuoteLayout(padding, size, authorSize, gap, text.height, author.height, text.width, author.width)
    }
    fun fits(plan: QuoteLayout) = plan.quoteWidth <= availableWidth && plan.authorWidth <= availableWidth &&
        plan.quoteHeight + plan.authorHeight + plan.gap <= availableHeight
    val normal = layout(maximum)
    if (fits(normal)) return normal
    var low = .001f
    var high = maximum
    repeat(24) {
        val size = (low + high) / 2
        val plan = layout(size)
        if (fits(plan)) low = size else high = size
    }
    return layout(low)
}
