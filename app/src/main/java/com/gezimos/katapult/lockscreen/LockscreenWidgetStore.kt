package com.gezimos.katapult.lockscreen

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.SharedPreferences

/** The only owner of the persisted widget selection. No editor can write a captured list. */
class LockscreenWidgetStore(context: Context, private val prefs: SharedPreferences) {
    private val context = context.applicationContext
    private val host get() = LockscreenWidgetHost.get(context)
    private val manager = AppWidgetManager.getInstance(context)

    enum class Result { APPLIED, MISSING, UNBOUND, NO_ROOM, INVALID, WRITE_FAILED }

    // All instances for this backing store (including PrefsManager import) share this monitor.
    fun read(): List<LockscreenWidget> = synchronized(prefs) { current() }

    fun canAdd(size: LockscreenWidgetSize): Result {
        val available = LockscreenWidgetHost.availableDp(context)
        return synchronized(prefs) {
            if (lockscreenBoxLayout(current().map { it.size.heightDp } + size.heightDp, available) != null) Result.APPLIED else Result.NO_ROOM
        }
    }

    fun addAfterConfigure(widget: LockscreenWidget): Result {
        val available = LockscreenWidgetHost.availableDp(context)
        return synchronized(prefs) {
            val snapshot = snapshot()
            val list = snapshot.selected
            if (!bound(widget.id)) return@synchronized unchanged(snapshot, Result.UNBOUND)
            if (list.any { it.id == widget.id }) return@synchronized unchanged(snapshot, Result.APPLIED)
            val next = list + widget
            if (!fits(next, available)) return@synchronized unchanged(snapshot, Result.NO_ROOM)
            LockscreenWidgetHost.updateSize(context, widget)
            write(next)
        }
    }

    fun resize(id: Int, size: LockscreenWidgetSize): Result {
        // External layout metadata is sampled before the transaction; the list is read only inside it.
        val available = LockscreenWidgetHost.availableDp(context)
        return synchronized(prefs) {
            val snapshot = snapshot()
            val list = snapshot.selected
            val widget = list.firstOrNull { it.id == id }?.copy(size = size) ?: return@synchronized unchanged(snapshot, Result.MISSING)
            val next = list.map { if (it.id == id) widget else it }
            if (!fits(next, available)) return@synchronized unchanged(snapshot, Result.NO_ROOM)
            LockscreenWidgetHost.updateSize(context, widget)
            write(next)
        }
    }

    fun move(id: Int, delta: Int): Result = synchronized(prefs) {
        val snapshot = snapshot()
        val list = snapshot.selected.toMutableList()
        val from = list.indexOfFirst { it.id == id }
        val to = from + delta
        if (from !in list.indices || to !in list.indices) return@synchronized unchanged(snapshot, Result.MISSING)
        java.util.Collections.swap(list, from, to)
        write(list)
    }

    fun remove(id: Int): Result = synchronized(prefs) {
        val snapshot = snapshot()
        val list = snapshot.selected
        if (list.none { it.id == id }) return@synchronized unchanged(snapshot, Result.MISSING)
        host.deleteAppWidgetId(id)
        write(list.filterNot { it.id == id })
    }

    /** Cancel only an unpublished allocation, never a widget already published by another holder. */
    fun discardPending(id: Int) = synchronized(prefs) {
        if (current().none { it.id == id } && id in host.appWidgetIds) host.deleteAppWidgetId(id)
    }

    /** Explicit restore/import replacement; ordinary Settings operations cannot supply a list. */
    fun replaceImportedSelection(serialized: String): Result = synchronized(prefs) {
        val imported = LockscreenWidgetList.decodeForImport(serialized) ?: return@synchronized Result.INVALID
        write(reconciled(imported))
    }

    /** Export and the read-only compatibility facade both go through the reconciling owner. */
    fun exportSelection(): String? = synchronized(prefs) {
        val list = current()
        if (prefs.contains(KEY)) LockscreenWidgetList.encode(list) else null
    }

    /** App config imports intentionally keep IDs local. Commit the full validated preference editor
     * together with the current reconciled selection, not a snapshot captured by the importer. */
    internal fun commitImportedPreferences(editor: SharedPreferences.Editor): Boolean = synchronized(prefs) {
        val present = prefs.contains(KEY)
        val list = reconciled(decode())
        if (present) editor.putString(KEY, LockscreenWidgetList.encode(list)) else editor.remove(KEY)
        editor.commit()
    }

    private data class Selection(val stored: List<LockscreenWidget>, val selected: List<LockscreenWidget>)
    private fun snapshot(): Selection {
        val stored = decode()
        return Selection(stored, reconciled(stored))
    }

    private fun unchanged(snapshot: Selection, result: Result): Result =
        if (snapshot.selected != snapshot.stored && write(snapshot.selected) != Result.APPLIED) Result.WRITE_FAILED else result

    private fun current(): List<LockscreenWidget> {
        val snapshot = snapshot()
        if (snapshot.selected == snapshot.stored) return snapshot.selected
        check(unchanged(snapshot, Result.APPLIED) == Result.APPLIED) { "Could not reconcile widget selection" }
        // A synchronous preference listener may have applied another owner operation at commit.
        return decode()
    }

    private fun decode(): List<LockscreenWidget> = LockscreenWidgetList.decode(try {
        prefs.getString(KEY, "[]") ?: "[]"
    } catch (_: ClassCastException) { "[]" })

    private fun reconciled(stored: List<LockscreenWidget>): List<LockscreenWidget> {
        if (stored.isEmpty()) return stored
        val owned = host.appWidgetIds.toSet()
        val selected = stored.filter { it.id in owned && manager.getAppWidgetInfo(it.id) != null }
        val retained = selected.map { it.id }.toSet()
        stored.filter { it.id in owned && it.id !in retained }.forEach { host.deleteAppWidgetId(it.id) }
        return selected
    }

    private fun bound(id: Int) = id in host.appWidgetIds && manager.getAppWidgetInfo(id) != null
    private fun fits(list: List<LockscreenWidget>, available: Int) = lockscreenBoxLayout(list.map { it.size.heightDp }, available) != null
    private fun write(list: List<LockscreenWidget>): Result {
        check(list.all { it.id > 0 } && list.map { it.id }.distinct().size == list.size)
        return if (prefs.edit().putString(KEY, LockscreenWidgetList.encode(list)).commit()) Result.APPLIED else Result.WRITE_FAILED
    }

    companion object { const val KEY = "lockscreen_widgets" }
}
