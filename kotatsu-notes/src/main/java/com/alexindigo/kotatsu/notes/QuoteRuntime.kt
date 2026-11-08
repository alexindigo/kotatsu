package com.alexindigo.kotatsu.notes

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.time.Clock
import java.time.ZonedDateTime

/** Runs on NotesRuntime's lane: every trigger re-reads, selects, sizes, publishes and reconciles the alarm. */
object QuoteRuntime {
    const val DEADLINE = "com.alexindigo.kotatsu.notes.QUOTE_DEADLINE"
    const val HOST_WAKE = "kotatsu.host.wake"
    internal var clock: Clock? = null
    internal var notes: (Context) -> TextNotesPort = { MuditaNotes(it.contentResolver) }
    fun ids(context: Context): IntArray = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, QuoteWidgetProvider::class.java))
    private fun alarm(context: Context) = PendingIntent.getBroadcast(context, 1,
        Intent(context, QuoteWidgetProvider::class.java).setAction(DEADLINE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun refresh(context: Context, widgetIds: IntArray = ids(context)) {
        val manager = AppWidgetManager.getInstance(context)
        val store = QuoteStore(context)
        val now = ZonedDateTime.now(clock ?: Clock.systemDefaultZone())
        val live = ids(context).toSet()
        for (id in widgetIds) {
            if (id !in live) continue
            val options = manager.getAppWidgetOptions(id)
            val wake = store.consume(id, if (options.containsKey(HOST_WAKE)) options.getLong(HOST_WAKE) else null)
            val config = store.config()
            val entries = if (config != null && NotesRuntime.unlocked(context)) runCatching {
                notes(context).readText(config.noteId)?.content?.let(::parseQuotes)
            }.getOrNull().orEmpty() else emptyList()
            val quote = config?.let { selectQuote(entries, it.order, quotePeriod(it.frequency, now, wake.counter), it.seed) }
            val density = context.resources.displayMetrics.density
            val width = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300).coerceAtLeast(1) * density).toInt().coerceAtLeast(1)
            val height = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 112).coerceAtLeast(1) * density).toInt().coerceAtLeast(1)
            manager.updateAppWidget(id, if (quote == null) QuoteWidgetViews.blank(context)
                else QuoteWidgetViews.quote(context, quote, width, height))
        }
        // A single non-wakeup RTC alarm also changes a quote when the screen stays on past a boundary.
        val next = if (live.isEmpty()) null else store.config()?.let { nextQuoteBoundary(it.frequency, now) }
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (next == null) alarms.cancel(alarm(context))
        else alarms.setWindow(AlarmManager.RTC, next.toEpochMilli(), 60_000, alarm(context))
        NotesRuntime.arm(context)
    }

    fun changed(context: Context, changedIds: Set<Long>?) {
        val store = QuoteStore(context)
        if (changedIds == null || store.config()?.noteId in changedIds) refresh(context)
    }
}
