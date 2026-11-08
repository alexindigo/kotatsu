package com.alexindigo.kotatsu.notes

data class RenderedList(val token: String, val snapshot: TaskListSnapshot)
data class WidgetTick(val token: String, val index: Int, val checked: Boolean? = null)

/** Never retarget a row from an older rendering or a different widget selection. */
fun acceptsTick(selected: Long?, rendered: RenderedList?, tick: WidgetTick): Boolean {
    if (rendered == null || rendered.token != tick.token || selected != rendered.snapshot.id) return false
    val item = rendered.snapshot.items?.getOrNull(tick.index) ?: return false
    if (item.kind != TaskRowKind.ITEM) return false
    return tick.checked == null || tick.checked != item.checked
}

/** A provider edit observed before the command starts means no write at all. CAS covers later edits. */
fun tickRenderedList(notes: NotesPort, selected: Long?, rendered: RenderedList?, tick: WidgetTick, now: Long): TaskToggleResult {
    val current = try { selected?.let(notes::read) } catch (_: RuntimeException) { null }
    if (!acceptsTick(selected, rendered, tick) || current != rendered?.snapshot) {
        return TaskToggleResult(TaskWrite.STALE, current)
    }
    return toggleTask(notes, rendered!!.snapshot, tick.index, now)
}

/** Null or ambiguous content notifications affect every selection; exact descendants can be narrowed. */
fun affectedWidgets(selected: Map<Int, Long?>, changedIds: Set<Long>?): Set<Int> =
    selected.filterValues { changedIds == null || it in changedIds }.keys
