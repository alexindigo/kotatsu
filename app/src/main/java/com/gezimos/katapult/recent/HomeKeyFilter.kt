package com.gezimos.katapult.recent

/** Owns HOME and its screenshot chord. Event timestamps and deadlines share the uptime clock. */
class HomeKeyFilter(
    private val clock: () -> Long,
    private val schedule: (delayMs: Long, task: () -> Unit) -> AutoCloseable,
    private val eligible: () -> Boolean,
    private val single: () -> Unit,
    private val double: () -> Unit,
    private val recentApps: () -> Boolean = { true },
    private val screenshotEnabled: () -> Boolean = { false },
    private val screenshot: () -> Unit = {},
    private val menuEnabled: () -> Boolean = { false },
    private val menu: () -> Unit = {},
) : AutoCloseable {
    companion object {
        const val DOUBLE_PRESS_MS = 500L
        const val HOME_MENU_HOLD_MS = 1000L
    }
    private var firstDown: Long? = null
    // Owning DOWN also owns its matching UP, even after action eligibility is lost.
    private var held = false
    private var expired = false
    private var timer: AutoCloseable? = null
    private var generation = 0L
    private var chord = false
    private var volumeHeld = false
    private var holdTimer: AutoCloseable? = null
    private var holdGeneration = 0L
    private var menuShown = false

    private fun allowed() = eligible() && (recentApps() || screenshotEnabled() || menuEnabled())

    private fun cancelHold() { holdGeneration++; holdTimer?.close(); holdTimer = null }
    private fun beginHold(eventTime: Long) {
        cancelHold()
        menuShown = false
        if (!menuEnabled()) return
        val token = holdGeneration
        holdTimer = schedule((eventTime + HOME_MENU_HOLD_MS - clock()).coerceAtLeast(0)) {
            if (token == holdGeneration && held && !chord && allowed() && menuEnabled()) {
                clearPending()
                menuShown = true
                menu()
            }
        }
    }

    fun down(eventTime: Long, repeat: Boolean = false): Boolean {
        if (!allowed()) { clearPending(); cancelHold(); return held }
        if (repeat) return held
        if (held) return true
        if (!recentApps()) { clearPending(); held = true; beginHold(eventTime); return true }
        val first = firstDown
        if (first != null) {
            if (eventTime - first in 0 until DOUBLE_PRESS_MS) {
                clearPending()
                held = true
                beginHold(eventTime)
                double()
                return true
            }
            // Delivery can be late even when the scheduler has not run yet.
            clearPending()
            single()
        }
        held = true
        beginHold(eventTime)
        firstDown = eventTime
        val token = ++generation
        timer = schedule((eventTime + DOUBLE_PRESS_MS - clock()).coerceAtLeast(0)) {
            if (token == generation && firstDown != null) {
                if (!allowed()) { clearPending(); cancelHold() }
                else if (!recentApps()) clearPending()
                else {
                    expired = true
                    if (!held) { clearPending(); single() }
                }
            }
        }
        return true
    }

    fun up(): Boolean {
        val consumed = held
        held = false
        cancelHold()
        val actionHandled = chord || menuShown
        chord = false
        menuShown = false
        if (!allowed() || actionHandled) clearPending()
        else if (consumed && (!recentApps() || expired)) { clearPending(); single() }
        return consumed
    }

    fun volumeDown(repeat: Boolean = false): Boolean {
        if (!allowed()) { clearPending(); cancelHold(); return volumeHeld }
        // Volume cancels menu intent even when Android, not the screenshot chord, owns it.
        if (held) cancelHold()
        if (volumeHeld) return true
        if (!screenshotEnabled() || !held || chord || menuShown || repeat) return false
        clearPending()
        cancelHold()
        chord = true
        volumeHeld = true
        screenshot()
        return true
    }

    fun volumeUp(): Boolean {
        if (!allowed()) { clearPending(); cancelHold() }
        return volumeHeld.also { volumeHeld = false }
    }

    private fun clearPending() {
        generation++
        timer?.close(); timer = null
        firstDown = null
        expired = false
    }

    override fun close() { clearPending(); cancelHold(); held = false; chord = false; volumeHeld = false; menuShown = false }
}

/** Process-local routing state. A disappearing Activity cannot retain or replace a newer owner's gate. */
object HomeKeyFilterState {
    @Volatile private var service: Any? = null
    val active: Boolean get() = service != null
    fun isActive(owner: Any): Boolean = service === owner
    private var launcherGate: (() -> Boolean)? = null
    fun connected(owner: Any) { service = owner }
    fun disconnected(owner: Any) { if (service === owner) service = null }
    fun launcherAllowsFilter(): Boolean = launcherGate?.invoke() ?: true
    fun observeLauncher(gate: () -> Boolean): AutoCloseable {
        launcherGate = gate
        return AutoCloseable { if (launcherGate === gate) launcherGate = null }
    }
}
