package com.gezimos.katapult.recent

/** Owns only HOME sequences. Event timestamps and scheduler deadlines share the uptime clock. */
class HomeKeyFilter(
    private val clock: () -> Long,
    private val schedule: (delayMs: Long, task: () -> Unit) -> AutoCloseable,
    private val eligible: () -> Boolean,
    private val single: () -> Unit,
    private val double: () -> Unit,
) : AutoCloseable {
    companion object { const val DOUBLE_PRESS_MS = 500L }
    private var firstDown: Long? = null
    // Owning DOWN also owns its matching UP, even after action eligibility is lost.
    private var held = false
    private var expired = false
    private var timer: AutoCloseable? = null
    private var generation = 0L

    fun down(eventTime: Long, repeat: Boolean = false): Boolean {
        if (!eligible()) { clearPending(); return held }
        if (repeat) return held
        if (held) return true
        val first = firstDown
        if (first != null) {
            if (eventTime - first in 0 until DOUBLE_PRESS_MS) {
                clearPending()
                held = true
                double()
                return true
            }
            // Delivery can be late even when the scheduler has not run yet.
            clearPending()
            single()
        }
        held = true
        firstDown = eventTime
        val token = ++generation
        timer = schedule((eventTime + DOUBLE_PRESS_MS - clock()).coerceAtLeast(0)) {
            if (token == generation && firstDown != null) {
                if (!eligible()) clearPending()
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
        if (!eligible()) clearPending()
        else if (consumed && expired) { clearPending(); single() }
        return consumed
    }

    private fun clearPending() {
        generation++
        timer?.close(); timer = null
        firstDown = null
        expired = false
    }

    override fun close() { clearPending(); held = false }
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
