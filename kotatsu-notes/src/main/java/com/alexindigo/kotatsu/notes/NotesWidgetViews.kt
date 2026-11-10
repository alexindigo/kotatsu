package com.alexindigo.kotatsu.notes

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.TextView
import kotlin.math.floor

object NotesWidgetViews {
    const val HOST_TITLE = "kotatsu.host.title"
    const val TICK = "com.alexindigo.kotatsu.notes.TICK"
    const val TOKEN = "rendered"
    const val INDEX = "index"

    /** A collection edge may discard only space shared by every drawing in its last row. */
    private fun bottomTrim(context: Context, rendered: RenderedList, artworkBorder: Int): Int {
        val last = rendered.snapshot.items!!.lastOrNull() ?: return 0
        if (last.kind != TaskRowKind.ITEM) return 0
        val label = TextView(context, null, 0, R.style.NotesWidgetTask).apply { text = last.text }
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        label.measure(unspecified, unspecified)
        label.layout(0, 0, label.measuredWidth, label.measuredHeight)
        val ink = android.graphics.Rect()
        label.paint.getTextBounds(last.text, 0, last.text.length, ink)
        val rowHeight = maxOf(context.resources.getDimensionPixelSize(R.dimen.task_button_size), label.measuredHeight)
        val inkBottom = label.totalPaddingTop + label.layout.getLineBaseline(label.lineCount - 1) + ink.bottom
        return minOf(artworkBorder, (rowHeight - inkBottom).coerceAtLeast(0))
    }

    fun message(context: Context, text: Int) = RemoteViews(context.packageName, R.layout.widget_notes).apply {
        setViewVisibility(R.id.heading, View.GONE)
        setViewVisibility(R.id.tasks, View.GONE)
        setTextViewText(R.id.message, context.getString(text))
        setViewVisibility(R.id.message, View.VISIBLE)
    }

    @android.annotation.TargetApi(31)
    fun list(context: Context, widgetId: Int, rendered: RenderedList, hostTitle: Boolean): RemoteViews {
        // The vector's transparent 2-of-24-unit border and the cell drawable's own
        // padding define its visible left edge. Headings and collection clipping
        // share that geometry, rather than a copied screenshot offset.
        val opticalBorder = floor(context.resources.getDimension(R.dimen.task_button_size) * 2 / 24).toInt()
        val cellPadding = android.graphics.Rect().also { context.getDrawable(R.drawable.task_checkbox_cell)!!.getPadding(it) }
        val headingStart = context.resources.getDimensionPixelSize(R.dimen.task_row_start) + cellPadding.left + opticalBorder
        val template = PendingIntent.getBroadcast(context, widgetId,
            Intent(context, NotesWidgetProvider::class.java).setAction(TICK)
                .setData(Uri.parse("kotatsu-notes://widget/$widgetId"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val items = RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(false).setViewTypeCount(3)
        rendered.snapshot.items!!.forEachIndexed { index, item ->
            if (item.kind != TaskRowKind.ITEM) {
                items.addItem(index.toLong(), RemoteViews(context.packageName,
                    if (item.kind == TaskRowKind.HEADING) R.layout.widget_task_heading else R.layout.widget_task_section_gap).apply {
                    if (item.kind == TaskRowKind.HEADING) {
                        setTextViewText(R.id.task_heading, item.text)
                        setViewPadding(R.id.task_heading, headingStart,
                            context.resources.getDimensionPixelSize(R.dimen.task_button_padding),
                            (24 * context.resources.displayMetrics.density + .5f).toInt(),
                            context.resources.getDimensionPixelSize(R.dimen.task_button_padding))
                    }
                })
                return@forEachIndexed
            }
            val command = Intent().putExtra(TOKEN, rendered.token).putExtra(INDEX, index)
            items.addItem(index.toLong(), RemoteViews(context.packageName, R.layout.widget_task).apply {
                setTextViewText(R.id.task_text, item.text)
                setContentDescription(R.id.task_check, item.text)
                setCompoundButtonChecked(R.id.task_check, item.checked)
                setOnCheckedChangeResponse(R.id.task_check, RemoteViews.RemoteResponse.fromFillInIntent(command))
                setOnClickFillInIntent(R.id.task_row, command)
                val last = index == rendered.snapshot.items!!.lastIndex
                // Apply both states: collection rows can be recycled across an insertion/deletion.
                setViewPadding(R.id.task_content, 0, 0, 0,
                    if (last) 0 else context.resources.getDimensionPixelSize(R.dimen.task_row_padding))
                setViewPadding(R.id.task_text, 0, 0, (8 * context.resources.displayMetrics.density + .5f).toInt(),
                    if (last) 0 else context.resources.getDimensionPixelSize(R.dimen.task_button_padding))
                setViewVisibility(R.id.task_gap, if (last) View.GONE else View.VISIBLE)
                setViewLayoutHeight(R.id.task_check, if (last) context.resources.getDimension(R.dimen.task_button_size)
                    else android.view.ViewGroup.LayoutParams.WRAP_CONTENT.toFloat(), TypedValue.COMPLEX_UNIT_PX)
            })
        }
        return RemoteViews(context.packageName, R.layout.widget_notes).apply {
            setTextViewText(R.id.heading, rendered.snapshot.title.ifBlank { context.getString(R.string.untitled_list) })
            setViewVisibility(R.id.heading, if (hostTitle) View.GONE else View.VISIBLE)
            setViewVisibility(R.id.message, View.GONE)
            setViewVisibility(R.id.tasks, View.VISIBLE)
            // The last row contains both checkbox art and text. Its shared blank extent,
            // not the checkbox border alone, owns the collection's scroll boundary.
            setViewPadding(R.id.tasks, 0, -opticalBorder, 0, -bottomTrim(context, rendered, opticalBorder))
            setPendingIntentTemplate(R.id.tasks, template)
            setRemoteAdapter(R.id.tasks, items.build())
        }
    }
}
