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
    private val store = prefs.lockscreenWidgetStore
    var enabled by mutableStateOf(prefs.lockscreenWidgetsEnabled)
        private set
    var rotation by mutableStateOf(prefs.widgetRotation)
        private set
    var swipeToSwitch by mutableStateOf(prefs.widgetSwipeToSwitch)
        private set
    var rows by mutableStateOf(emptyList<WidgetSettingsRow>())
        private set
    var screens by mutableStateOf(listOf(SideScreen()))
        private set
    private data class Pending(val widget: LockscreenWidget, val editing: Boolean, val cell: Int? = null)
    private var pending: Pending? = null
    private val subscription = prefs.subscribeLockscreenWidgetChanges(::refresh)

    init { refresh() }

    fun refresh() {
        enabled = prefs.lockscreenWidgetsEnabled
        rotation = prefs.widgetRotation
        swipeToSwitch = prefs.widgetSwipeToSwitch
        screens = store.screens()
        rows = widgets().map { widget ->
            val info = manager.getAppWidgetInfo(widget.id)
            WidgetSettingsRow(widget, info?.loadLabel(context.packageManager) ?: context.getString(R.string.lockscreen_widgets_unavailable),
                info?.configure != null && info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0)
        }
    }

    private fun widgets() = store.read()
    fun rotation(value: WidgetRotation) { prefs.widgetRotation = value; refresh() }
    fun swipeToSwitch(value: Boolean) { prefs.widgetSwipeToSwitch = value; refresh() }
    fun screenName(screen: SideScreen = screens.first()): String = sideScreenName(context, screen)
    fun slotLabel(cell: Int): String = screens.first().slots[cell]?.let {
        manager.getAppWidgetInfo(it)?.loadLabel(context.packageManager)
    } ?: context.getString(R.string.side_choose)
    fun nameScreen(name: String) { applied(store.nameScreen(name)) }
    fun layoutScreen(layout: SideLayout) { applied(store.layoutScreen(layout)) }
    fun removeSlot(cell: Int) { applied(store.removePlacement(cell)) }
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

    private fun applied(result: LockscreenWidgetStore.Result): Boolean {
        when (result) {
            LockscreenWidgetStore.Result.APPLIED -> Unit
            LockscreenWidgetStore.Result.MISSING -> Unit
            LockscreenWidgetStore.Result.NO_ROOM -> Toast.makeText(context, R.string.lockscreen_widgets_no_room, Toast.LENGTH_SHORT).show()
            LockscreenWidgetStore.Result.UNBOUND, LockscreenWidgetStore.Result.INVALID, LockscreenWidgetStore.Result.WRITE_FAILED -> failed()
        }
        refresh()
        return result == LockscreenWidgetStore.Result.APPLIED
    }

    fun resize(id: Int, size: LockscreenWidgetSize) {
        try { applied(store.resize(id, size)) } catch (_: Exception) { failed() }
    }

    fun move(id: Int, delta: Int) {
        try { applied(store.move(id, delta)) } catch (_: Exception) { failed() }
    }

    fun remove(id: Int) {
        try { applied(store.remove(id)) }
        catch (_: Exception) { failed() }
    }

    fun add(info: AppWidgetProviderInfo, bind: (Intent) -> Unit, configure: (Int) -> Unit, cell: Int? = null) {
        if (pending != null) return
        val size = defaultLockscreenWidgetSize(info.minHeight / context.resources.displayMetrics.density)
        try {
            if (cell == null && !applied(store.canAdd(size))) return
            if (cell != null && cell !in 0 until screens.first().layout.cells) return
            val id = host.allocateAppWidgetId()
            check(id > 0)
            pending = Pending(LockscreenWidget(id, size), false, cell)
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
            if (request.cell != null) {
                val bounds = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
                val density = context.resources.displayMetrics.density
                host.sideSize(context, request.widget.id, (bounds.width() / density).toInt(),
                    ((bounds.height() / density - 112) / screens.first().layout.cells).toInt())
            } else LockscreenWidgetHost.updateSize(context, request.widget)
            if (info.configure != null) configure(request.widget.id) else configured(true)
        } catch (_: Exception) { cancel(); failed() }
    }

    fun edit(id: Int, configure: (Int) -> Unit, cell: Int? = null) {
        if (pending != null) return
        refresh()
        val widget = if (cell == null) rows.firstOrNull { it.widget.id == id && it.canEdit }?.widget
            else screens.first().slots.getOrNull(cell)?.takeIf { it == id && manager.getAppWidgetInfo(id)?.configure != null }
                ?.let { LockscreenWidget(it, LockscreenWidgetSize.L) }
        pending = Pending(widget ?: return, true, cell)
        try { configure(id) } catch (_: Exception) { cancel(); failed() }
    }

    fun configured(ok: Boolean) {
        val request = pending ?: return
        if (!ok) { cancel(); return }
        try {
            check(manager.getAppWidgetInfo(request.widget.id) != null)
            if (!request.editing) {
                val result = request.cell?.let { store.placeAfterConfigure(it, request.widget.id) } ?: store.addAfterConfigure(request.widget)
                if (!applied(result)) { cancel(); return }
            }
            pending = null
            refresh()
        } catch (_: Exception) { cancel(); failed() }
    }

    fun launchFailed() { cancel(); failed() }
    private fun failed() { Toast.makeText(context, R.string.lockscreen_widgets_failed, Toast.LENGTH_SHORT).show() }
    private fun cancel() {
        val request = pending ?: return
        if (!request.editing) store.discardPending(request.widget.id)
        pending = null
    }

    fun saveState(state: Bundle) {
        pending?.let {
            state.putInt("widget_pending_id", it.widget.id)
            state.putString("widget_pending_size", it.widget.size.name)
            state.putBoolean("widget_pending_edit", it.editing)
            it.cell?.let { cell -> state.putInt("widget_pending_cell", cell) }
        }
    }

    fun restoreState(state: Bundle?) {
        if (pending != null || state == null || !state.containsKey("widget_pending_id")) return
        val size = LockscreenWidgetSize.entries.firstOrNull { it.name == state.getString("widget_pending_size") } ?: return
        pending = Pending(LockscreenWidget(state.getInt("widget_pending_id"), size), state.getBoolean("widget_pending_edit"),
            state.takeIf { it.containsKey("widget_pending_cell") }?.getInt("widget_pending_cell"))
    }

    override fun close() { subscription.close(); cancel() }
}
