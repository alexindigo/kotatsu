package com.alexindigo.kotatsu.notes

import android.content.Context
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.util.TypedValue
import android.widget.RemoteViews

internal data class AuthorInkLine(val text: String, val width: Int, val height: Int, val leadingMargin: Int,
    val paddingLeft: Int, val paddingRight: Int)
internal data class AuthorInkLayout(val text: String, val size: Float, val lines: List<AuthorInkLine>) {
    val measured get() = QuoteTextMeasure(lines.maxOfOrNull { it.width } ?: 0, lines.sumOf { it.height })
}

/** END belongs to the ink-sized line box, not to TextView's advance-width clipping lane. */
internal object QuoteAuthorViews {
    private const val DRAW_GUARD = 1

    /** Actual shaped coverage, including antialiasing; advance and font rectangles are not ink. */
    private fun inkBounds(layout: StaticLayout, size: Float): Rect {
        val guard = kotlin.math.ceil(size).toInt().coerceAtLeast(2)
        val bitmap = Bitmap.createBitmap(layout.width + 2 * guard, layout.height + 2 * guard, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        Canvas(bitmap).apply { translate(guard.toFloat(), guard.toFloat()); layout.draw(this) }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var left = bitmap.width; var top = bitmap.height; var right = 0; var bottom = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (pixels[y * bitmap.width + x] != Color.WHITE) {
            left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
        }
        bitmap.recycle()
        return if (right == 0) Rect() else Rect(left - guard, top - guard, right - guard, bottom - guard)
    }

    fun plan(text: String, size: Float, width: Int, typeface: Typeface): AuthorInkLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface; textSize = size; color = Color.BLACK }
        fun layout(value: String, lane: Int) = StaticLayout.Builder.obtain(value, 0, value.length, paint, lane)
            .setIncludePad(true).setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build()
        var lane = (width - 2 * DRAW_GUARD).coerceAtLeast(1)
        while (true) {
            val wrapped = layout(text, lane)
            val lines = (0 until wrapped.lineCount).map { index ->
                val value = text.substring(wrapped.getLineStart(index), wrapped.getLineEnd(index))
                // Measure this planned line without its terminating paragraph break.
                val visible = value.trimEnd('\r', '\n')
                val single = layout(visible, maxOf(lane, kotlin.math.ceil(Layout.getDesiredWidth(visible, paint)).toInt() + 1))
                val ink = inkBounds(single, size)
                val rtl = single.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT
                val margin = if (rtl) ink.right - single.width + DRAW_GUARD else DRAW_GUARD - ink.left
                val height = if (index == wrapped.lineCount - 1) ink.bottom else single.height
                val inkWidth = ink.width() + 2 * DRAW_GUARD
                // A finite advance lane may be wider than the ink box (side bearings or
                // trailing spaces). Keep that slack at the non-anchored side, not in a
                // million-pixel horizontally scrolling TextView layout.
                val slack = (kotlin.math.ceil(single.getLineWidth(0)).toInt() + margin + 1 - inkWidth).coerceAtLeast(0)
                AuthorInkLine(value, inkWidth, height.coerceAtLeast(1), margin,
                    if (rtl) -slack else 0, if (rtl) 0 else -slack)
            }
            val result = AuthorInkLayout(text, size, lines)
            val overflow = result.measured.width - width
            if (overflow <= 0 || lane == 1) return result
            lane = (lane - overflow).coerceAtLeast(1)
        }
    }

    fun render(context: Context, layout: AuthorInkLayout, gap: Int): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_quote_author).apply {
            setContentDescription(R.id.quote_author, layout.text)
            setViewPadding(R.id.quote_author, 0, gap, 0, 0)
            layout.lines.forEach { line ->
                addView(R.id.quote_author, RemoteViews(context.packageName, R.layout.widget_quote_author_line).apply {
                    val text = SpannableString(line.text).apply {
                        setSpan(LeadingMarginSpan.Standard(line.leadingMargin), 0, length, Spanned.SPAN_PARAGRAPH)
                    }
                    setTextViewText(R.id.quote_author_line, text)
                    setTextViewTextSize(R.id.quote_author_line, TypedValue.COMPLEX_UNIT_PX, layout.size)
                    setViewPadding(R.id.quote_author_line, line.paddingLeft, 0, line.paddingRight, 0)
                    setInt(R.id.quote_author_line, "setWidth", line.width)
                    setInt(R.id.quote_author_line, "setHeight", line.height)
                })
            }
        }
}
