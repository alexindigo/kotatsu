package com.gezimos.katapult.lockscreen

/** Snapshots must be independent values: widening cannot mutate the captured original. */
internal interface ReplayServiceInfoPort<T> {
    fun staticInfo(): T
    fun currentInfo(): T
    fun widened(info: T): T
    fun apply(info: T)
}

/** One connection-local owner; overlapping observers release only their own broad-delivery lease. */
internal class ReplayDelivery<T : Any>(private val port: ReplayServiceInfoPort<T>,
                                     private val failed: (RuntimeException) -> Unit) {
    private var original: T? = null
    private var generation = 0L
    private var usable = true
    private val leases = mutableMapOf<Long, Boolean>()
    val busy get() = leases.isNotEmpty()

    init {
        // A reconnect must not inherit the previous connection's temporary delivery settings.
        try { port.apply(port.staticInfo()) }
        catch (error: RuntimeException) { usable = false; failed(error) }
    }

    fun begin(): Long {
        check(usable) { "Replay event delivery is unavailable" }
        val widen = leases.values.none { it }
        val captured = if (widen) port.currentInfo() else null
        val token = ++generation
        leases[token] = true
        if (captured == null) return token
        original = captured
        try { port.apply(port.widened(captured)) }
        catch (error: RuntimeException) { end(token); throw error }
        return token
    }

    /** Release observation before dispatch; callback ownership stays live until completion/cancellation. */
    fun dispatched(token: Long): Boolean {
        if (!current(token)) return false
        leases[token] = false
        return leases.values.any { it } || restore()
    }

    fun current(token: Long): Boolean = token in leases

    fun end(token: Long) {
        if (!current(token)) return
        leases.remove(token)
        if (leases.values.none { it }) restore()
    }

    fun cancel() { leases.clear(); restore() }

    private fun restore(): Boolean {
        val captured = original ?: return usable
        original = null
        try { port.apply(captured) }
        catch (error: RuntimeException) {
            // Do not capture widened state as a new baseline or run another replay.
            // The next connection resets from static configuration instead.
            usable = false
            failed(error)
        }
        return usable
    }
}
