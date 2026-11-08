package com.alexindigo.kotatsu.notes

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.widget.RemoteViews

class NotesWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        // Keep framework lifecycle dispatch and all I/O in the same serialized lane.
        val pending = goAsync()
        NotesRuntime.enqueue {
            try {
                when (intent.action) {
                    NotesWidgetViews.TICK -> {
                        if (Build.VERSION.SDK_INT < 31) return@enqueue
                        val token = intent.getStringExtra(NotesWidgetViews.TOKEN) ?: return@enqueue
                        NotesRuntime.tick(context, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1),
                            WidgetTick(token, intent.getIntExtra(NotesWidgetViews.INDEX, -1),
                                if (intent.hasExtra(RemoteViews.EXTRA_CHECKED)) intent.getBooleanExtra(RemoteViews.EXTRA_CHECKED, false) else null))
                    }
                    Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_USER_UNLOCKED, Intent.ACTION_MY_PACKAGE_REPLACED -> NotesRuntime.refresh(context)
                    else -> super.onReceive(context, intent)
                }
            } finally { pending?.finish() }
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = NotesRuntime.refresh(context, ids)
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        NotesRuntime.refresh(context, intArrayOf(id))
    override fun onEnabled(context: Context) = NotesRuntime.arm(context)
    override fun onDisabled(context: Context) = NotesRuntime.arm(context)
    override fun onDeleted(context: Context, ids: IntArray) {
        val store = NotesStore(context)
        ids.forEach { store.delete(it, NotesRuntime.unlocked(context)) }
        NotesRuntime.arm(context)
    }
    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val store = NotesStore(context)
        oldWidgetIds.zip(newWidgetIds).forEach { (old, new) -> store.restore(old, new) }
        NotesRuntime.refresh(context, newWidgetIds)
    }
}
