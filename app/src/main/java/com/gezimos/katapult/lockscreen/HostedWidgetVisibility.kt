package com.gezimos.katapult.lockscreen

/** One owner for options hints and receipt classification, including creation and size-only updates. */
internal class HostedWidgetVisibility {
    private data class State(val widget: LockscreenWidget, val visible: Boolean)
    private val states = linkedMapOf<Int, State>()

    // Never-shown widgets have no visibility transition to announce.
    fun hint(id: Int): Boolean? = states[id]?.visible

    fun reconcile(visibility: LockscreenVisibility, selected: List<LockscreenWidget>): List<LockscreenWidget> {
        val shown = selected.takeIf { visibility.visible }.orEmpty()
        return transition(shown, visibility.widgetsToPulse(shown.map { it.id }).toSet())
    }

    fun hide(): List<LockscreenWidget> = transition(emptyList(), emptySet())
    fun clear() = states.clear()

    private fun transition(shown: List<LockscreenWidget>, pulse: Set<Int>): List<LockscreenWidget> {
        val target = shown.associateBy { it.id }
        val changed = mutableListOf<LockscreenWidget>()
        states.values.toList().filter { it.visible && it.widget.id !in target }.forEach { old ->
            states[old.widget.id] = old.copy(visible = false)
            changed += old.widget
        }
        shown.forEach { widget ->
            val next = State(widget, true)
            if (states.put(widget.id, next) != next || widget.id in pulse) changed += widget
        }
        return changed
    }
}
