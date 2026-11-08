package com.alexindigo.kotatsu.notes

import android.content.Context
import android.graphics.Typeface
import android.graphics.Rect
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

object QuoteWidgetViews {
    fun blank(context: Context) = RemoteViews(context.packageName, R.layout.widget_quote).apply {
        setTextViewText(R.id.quote_text, "")
        removeAllViews(R.id.quote_author_slot)
        setViewVisibility(R.id.quote_author_slot, View.GONE)
    }

    fun quote(context: Context, quote: Quote, width: Int, height: Int): RemoteViews {
        val metrics = context.resources.displayMetrics
        val normal = Typeface.create("lato-mudita", Typeface.NORMAL)
        val italic = Typeface.create("lato-mudita", Typeface.ITALIC)
        val plan = planQuoteLayout(width, height, metrics.density, metrics.scaledDensity, quote) { text, size, lane, author ->
            if (author) QuoteAuthorViews.plan(text, size, lane, italic).measured else measureText(text, size, lane, normal)
        }
        return render(context, quote, plan, if (quote.author.isEmpty()) null else QuoteAuthorViews.plan(quote.author, plan.authorSize, width, italic))
    }

    internal fun measureText(text: String, size: Float, lane: Int, typeface: Typeface): QuoteTextMeasure {
        val paint = TextPaint().apply { this.typeface = typeface; textSize = size }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, lane)
            .setIncludePad(true).setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()
        // Preserve native ascent/leading; the content height ends at its ink.
        val bounds = Rect()
        var right = 0
        val bottom = (0 until layout.lineCount).maxOf { line ->
            paint.getTextBounds(text, layout.getLineStart(line), layout.getLineEnd(line), bounds)
            right = maxOf(right, kotlin.math.ceil(layout.getLineLeft(line) + bounds.right).toInt())
            layout.getLineBaseline(line) + bounds.bottom
        }.coerceAtLeast(1)
        return QuoteTextMeasure(right, bottom)
    }

    internal fun render(context: Context, quote: Quote, plan: QuoteLayout, author: AuthorInkLayout?): RemoteViews =
        blank(context).apply {
            setViewPadding(R.id.quote_paper, plan.padding, plan.padding, plan.padding, plan.padding)
            setTextViewText(R.id.quote_text, quote.text)
            setTextViewTextSize(R.id.quote_text, TypedValue.COMPLEX_UNIT_PX, plan.quoteSize)
            setInt(R.id.quote_text, "setHeight", plan.quoteHeight)
            if (author != null) {
                addView(R.id.quote_author_slot, QuoteAuthorViews.render(context, author, plan.gap))
                setViewVisibility(R.id.quote_author_slot, View.VISIBLE)
            }
        }
}
