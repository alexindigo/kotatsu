package com.gezimos.katapult.lockscreen

/** Snapshots must be independent values: widening cannot mutate the captured original. */
internal interface ReplayServiceInfoPort<T> {
    fun staticInfo(): T
    fun currentInfo(): T
    fun widened(info: T): T
    fun apply(info: T)
}

/** One connection-local owner; broad delivery is leased only until the removal acknowledgement. */
internal class ReplayDelivery<T : Any>(private val port: ReplayServiceInfoPort<T>,
                                     private val failed: (RuntimeException) -> Unit) {
    private var original: T? = null
    private var generation = 0L
    private var usable = true
    var busy = false
        private set

    init {
        // A reconnect must not inherit the previous connection's temporary delivery settings.
        try { port.apply(port.staticInfo()) }
        catch (error: RuntimeException) { usable = false; failed(error) }
    }

    fun begin(): Long {
        check(usable && !busy) { "Replay event delivery is unavailable or busy" }
        val captured = port.currentInfo()
        val token = ++generation
        original = captured
        busy = true
        try { port.apply(port.widened(captured)) }
        catch (error: RuntimeException) { end(token); throw error }
        return token
    }

    /** Restore before dispatch; callback ownership stays live until completion/cancellation. */
    fun dispatched(token: Long): Boolean = current(token) && restore()

    fun current(token: Long): Boolean = busy && token == generation

    fun end(token: Long) {
        if (!current(token)) return
        restore()
        busy = false
        generation++
    }

    fun cancel() { if (busy) end(generation) }

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
