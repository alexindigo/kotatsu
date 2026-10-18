package com.gezimos.katapult.lockscreen

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gezimos.katapult.R
import com.gezimos.katapult.util.PrefsManager

data class WidgetProviderChoice(val info: AppWidgetProviderInfo, val label: String)
data class WidgetSettingsRow(val widget: LockscreenWidget, val label: String, val canEdit: Boolean)

/** Owns allocation/binding/configuration. Composables only render rows and forward intents. */
class LockscreenWidgetSettings(private val context: Context, private val prefs: PrefsManager) : AutoCloseable {
    private val manager = AppWidgetManager.getInstance(context)
    private val host get() = LockscreenWidgetHost.get(context)
    var enabled by mutableStateOf(prefs.lockscreenWidgetsEnabled)
        private set
    var rows by mutableStateOf(emptyList<WidgetSettingsRow>())
        private set
    private data class Pending(val widget: LockscreenWidget, val editing: Boolean)
    private var pending: Pending? = null
    private val subscription = prefs.subscribeChanges { refresh() }

    init { refresh() }

    fun refresh() {
        enabled = prefs.lockscreenWidgetsEnabled
        rows = widgets().map { widget ->
            val info = manager.getAppWidgetInfo(widget.id)
            WidgetSettingsRow(widget, info?.loadLabel(context.packageManager) ?: context.getString(R.string.lockscreen_widgets_unavailable),
                info?.configure != null && info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0)
        }
    }

    private fun widgets() = LockscreenWidgetList.decode(prefs.lockscreenWidgets)
    private fun save(widgets: List<LockscreenWidget>) { prefs.lockscreenWidgets = LockscreenWidgetList.encode(widgets); refresh() }
    /** "Messaging (SMS)" — the app is named only when the widget's own name doesn't already say it. */
    private fun label(info: AppWidgetProviderInfo): String {
        val pm = context.packageManager
        val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString() }
            .getOrDefault(info.provider.packageName)
        return providerLabel(info.loadLabel(pm), app)
    }

    fun providers(): List<WidgetProviderChoice> = manager.installedProviders
        .filter { it.provider.packageName != context.packageName }
        .map { WidgetProviderChoice(it, label(it)) }.sortedBy { it.label.lowercase() }

    /** Turning on needs the Action Service; without it [needsService] runs instead (the caller asks for setup). */
    fun requestEnabled(value: Boolean, needsService: () -> Unit) {
        if (value && !LockscreenWidgetService.isEnabled(context)) {
            needsService()
            return
        }
        prefs.lockscreenWidgetsEnabled = value
        refresh()
    }

    private fun fits(widgets: List<LockscreenWidget>): Boolean {
        val fits = lockscreenBoxLayout(widgets.map { it.size.heightDp }, LockscreenWidgetHost.availableDp(context)) != null
        if (!fits) Toast.makeText(context, R.string.lockscreen_widgets_no_room, Toast.LENGTH_SHORT).show()
        return fits
    }

    fun resize(id: Int, size: LockscreenWidgetSize) {
        val list = widgets().map { if (it.id == id) it.copy(size = size) else it }
        if (!fits(list)) return
        try {
            list.firstOrNull { it.id == id }?.let { LockscreenWidgetHost.updateSize(context, it) }
            save(list)
        } catch (_: Exception) { failed() }
    }

    fun move(id: Int, delta: Int) {
        val list = widgets().toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = from + delta
        if (from !in list.indices || to !in list.indices) return
        java.util.Collections.swap(list, from, to)
        save(list)
    }

    fun remove(id: Int) {
        try { host.deleteAppWidgetId(id); save(widgets().filterNot { it.id == id }) }
        catch (_: Exception) { failed() }
    }

    fun add(info: AppWidgetProviderInfo, bind: (Intent) -> Unit, configure: (Int) -> Unit) {
        if (pending != null) return
        val size = defaultLockscreenWidgetSize(info.minHeight / context.resources.displayMetrics.density)
        if (!fits(widgets() + LockscreenWidget(1, size))) return
        try {
            val id = host.allocateAppWidgetId()
            check(id > 0)
            pending = Pending(LockscreenWidget(id, size), false)
            if (manager.bindAppWidgetIdIfAllowed(id, info.provider)) bound(true, configure)
            else bind(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            })
        } catch (_: Exception) { cancel(); failed() }
    }

    fun bound(ok: Boolean, configure: (Int) -> Unit) {
        val request = pending ?: return
        if (!ok) { cancel(); return }
        try {
            val info = manager.getAppWidgetInfo(request.widget.id) ?: error("Provider unavailable")
            LockscreenWidgetHost.updateSize(context, request.widget)
            if (info.configure != null) configure(request.widget.id) else configured(true)
        } catch (_: Exception) { cancel(); failed() }
    }

    fun edit(id: Int, configure: (Int) -> Unit) {
        if (pending != null) return
        val row = rows.firstOrNull { it.widget.id == id && it.canEdit } ?: return
        pending = Pending(row.widget, true)
        try { configure(id) } catch (_: Exception) { cancel(); failed() }
    }

    fun configured(ok: Boolean) {
        val request = pending ?: return
        if (!ok) { cancel(); return }
        try {
            check(manager.getAppWidgetInfo(request.widget.id) != null)
            if (!request.editing) {
                val list = widgets() + request.widget
                if (!fits(list)) { cancel(); return }
                LockscreenWidgetHost.updateSize(context, request.widget)
                save(list)
            }
            pending = null
            refresh()
        } catch (_: Exception) { cancel(); failed() }
    }

    fun launchFailed() { cancel(); failed() }
    private fun failed() { Toast.makeText(context, R.string.lockscreen_widgets_failed, Toast.LENGTH_SHORT).show() }
    private fun cancel() {
        val request = pending ?: return
        if (!request.editing) host.deleteAppWidgetId(request.widget.id)
        pending = null
    }

    fun saveState(state: Bundle) {
        pending?.let {
            state.putInt("widget_pending_id", it.widget.id)
            state.putString("widget_pending_size", it.widget.size.name)
            state.putBoolean("widget_pending_edit", it.editing)
        }
    }

    fun restoreState(state: Bundle?) {
        if (pending != null || state == null || !state.containsKey("widget_pending_id")) return
        val size = LockscreenWidgetSize.entries.firstOrNull { it.name == state.getString("widget_pending_size") } ?: return
        pending = Pending(LockscreenWidget(state.getInt("widget_pending_id"), size), state.getBoolean("widget_pending_edit"))
    }

    override fun close() { subscription.close(); cancel() }
}
