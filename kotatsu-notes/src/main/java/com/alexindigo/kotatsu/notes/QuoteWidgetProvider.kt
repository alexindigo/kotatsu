package com.alexindigo.kotatsu.notes

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle

class QuoteWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        NotesRuntime.enqueue {
            try {
                when (intent.action) {
                    QuoteRuntime.DEADLINE, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                    Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_USER_UNLOCKED, Intent.ACTION_MY_PACKAGE_REPLACED -> QuoteRuntime.refresh(context)
                    else -> super.onReceive(context, intent)
                }
            } finally { pending?.finish() }
        }
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = QuoteRuntime.refresh(context, ids)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        QuoteRuntime.refresh(context, intArrayOf(id))
    override fun onEnabled(context: Context) = QuoteRuntime.refresh(context)
    override fun onDisabled(context: Context) = QuoteRuntime.refresh(context)
    override fun onDeleted(context: Context, ids: IntArray) {
        val store = QuoteStore(context)
        ids.forEach(store::delete)
        QuoteRuntime.refresh(context)
    }
    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val store = QuoteStore(context)
        oldWidgetIds.zip(newWidgetIds).forEach { (old, new) -> store.restore(old, new) }
        QuoteRuntime.refresh(context, newWidgetIds)
    }
}
