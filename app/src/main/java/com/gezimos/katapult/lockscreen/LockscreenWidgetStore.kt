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

    fun screens(): List<SideScreen> = synchronized(prefs) {
        val stored = decodeScreens()
        val boxIds = current().map { it.id }.toSet()
        val next = stored.map { screen -> screen.copy(slots = screen.slots.map { id ->
            id?.takeIf { it !in boxIds && bound(it) }
        }) }
        if (stored != next) {
            check(writeScreens(next) == Result.APPLIED) { "Could not reconcile side screens" }
            // Commit can synchronously notify another owner operation, just like the box read.
            decodeScreens()
        } else next
    }

    fun nameScreen(name: String): Result = editScreen { it.copy(name = name.trim()) }
    fun layoutScreen(layout: SideLayout): Result = editScreen { it.copy(layout = layout) }

    fun placeAfterConfigure(cell: Int, id: Int): Result = synchronized(prefs) {
        if (cell !in 0..1 || !bound(id) || current().any { it.id == id }) return@synchronized Result.INVALID
        val list = screens()
        if (list.any { screen -> id in screen.slots }) return@synchronized Result.INVALID
        val old = list.first().slots[cell]
        val next = list.first().copy(slots = list.first().slots.mapIndexed { index, value -> if (index == cell) id else value })
        val result = writeScreens(listOf(next) + list.drop(1))
        if (result == Result.APPLIED && old != null) discardPending(old)
        result
    }

    fun removePlacement(cell: Int): Result = synchronized(prefs) {
        if (cell !in 0..1) return@synchronized Result.INVALID
        val old = screens().first().slots[cell]
        val result = editScreen { screen -> screen.copy(slots = screen.slots.mapIndexed { index, id -> if (index == cell) null else id }) }
        if (result == Result.APPLIED && old != null) discardPending(old)
        result
    }

    private fun editScreen(edit: (SideScreen) -> SideScreen): Result = synchronized(prefs) {
        val list = screens()
        writeScreens(listOf(edit(list.first())) + list.drop(1))
    }
    private fun decodeScreens(): List<SideScreen> = SideScreenList.decode(prefs.all[SCREENS] as? String ?: "[]")
        .ifEmpty { listOf(SideScreen()) }
    private fun writeScreens(list: List<SideScreen>): Result =
        if (prefs.edit().putString(SCREENS, SideScreenList.encode(list)).commit()) Result.APPLIED else Result.WRITE_FAILED

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
            if (widget.id in sideIds()) return@synchronized unchanged(snapshot, Result.INVALID)
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
        val result = write(list.filterNot { it.id == id })
        if (result == Result.APPLIED) discardPending(id)
        result
    }

    /** Cancel only an unpublished allocation, never a widget already published by another holder. */
    fun discardPending(id: Int) = synchronized(prefs) {
        if (current().none { it.id == id } && decodeScreens().none { id in it.slots } && id in host.appWidgetIds) host.deleteAppWidgetId(id)
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
        val reserved = sideIds()
        val selected = stored.filter { it.id !in reserved && it.id in owned && manager.getAppWidgetInfo(it.id) != null }
        val retained = selected.map { it.id }.toSet() + reserved
        stored.filter { it.id in owned && it.id !in retained }.forEach { host.deleteAppWidgetId(it.id) }
        return selected
    }

    private fun bound(id: Int) = id in host.appWidgetIds && manager.getAppWidgetInfo(id) != null
    // Dormant cells are still owned. One ID must never have two native callback/view owners.
    private fun sideIds(): Set<Int> = decodeScreens().flatMap { it.slots.filterNotNull() }.toSet()
    private fun fits(list: List<LockscreenWidget>, available: Int) = lockscreenBoxLayout(list.map { it.size.heightDp }, available) != null
    private fun write(list: List<LockscreenWidget>): Result {
        check(list.all { it.id > 0 } && list.map { it.id }.distinct().size == list.size)
        check(list.none { it.id in sideIds() })
        return if (prefs.edit().putString(KEY, LockscreenWidgetList.encode(list)).commit()) Result.APPLIED else Result.WRITE_FAILED
    }

    companion object {
        const val KEY = "lockscreen_widgets"
        const val SCREENS = "lockscreen_side_screens"
    }
}
