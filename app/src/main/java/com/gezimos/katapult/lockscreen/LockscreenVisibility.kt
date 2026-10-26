package com.gezimos.katapult.lockscreen

/** Permission to show lock-screen content comes from an observation, never from event history. */
internal class LockscreenVisibility {
    var epoch = 0L
        private set
    var observedBareAt: Long? = null
        private set
    var covered = true
        private set
    private var locked: Boolean? = null
    private val pulsedWidgets = mutableSetOf<Int>()
    val visible: Boolean get() = observedBareAt == epoch && !covered

    fun beginEpoch() {
        epoch++
        invalidate()
        pulsedWidgets.clear()
    }

    fun keyguard(locked: Boolean) {
        if (locked && this.locked == false) beginEpoch()
        this.locked = locked
        if (!locked) invalidate()
    }

    fun invalidate() {
        observedBareAt = null
        covered = true
    }

    /** Every active evaluation, including an idle tick, performs its own inspection. */
    fun recheck(active: Boolean, inspectCovered: () -> Boolean?): Boolean {
        if (!active) { invalidate(); return false }
        covered = try { inspectCovered() != false } catch (_: Exception) { true }
        if (!covered) observedBareAt = epoch
        return visible
    }

    /** Newly shown widgets each receive one standard options pulse in this epoch. */
    fun widgetsToPulse(shownIds: List<Int>): List<Int> =
        if (!visible) emptyList() else shownIds.filter { pulsedWidgets.add(it) }
}
