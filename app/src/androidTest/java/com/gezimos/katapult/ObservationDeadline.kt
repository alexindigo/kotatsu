package com.gezimos.katapult

import android.os.SystemClock

/** One budget follows synchronous observations through helper calls and nested awaits. */
internal class ObservationDeadline private constructor(private val end: Long) {
    fun remainingMs(): Long = (end - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    fun checkRemaining() { if (remainingMs() == 0L) throw Expired(this) }
    class Expired(val deadline: ObservationDeadline) : RuntimeException()

    companion object {
        private val active = ThreadLocal<ObservationDeadline?>()
        fun current(): ObservationDeadline? = active.get()

        /** Imperative restoration loops keep writes outside the cancellable read scope. */
        fun <T> readUntil(end: Long, query: () -> T): T {
            val previous = active.get()
            val deadline = previous?.takeIf { it.end <= end } ?: ObservationDeadline(end)
            active.set(deadline)
            return withFixtureCleanup({ if (previous == null) active.remove() else active.set(previous) }) {
                deadline.checkRemaining()
                val value = query()
                deadline.checkRemaining()
                value
            }
        }

        fun await(message: String, timeoutMs: Long, intervalMs: Long, ready: () -> Boolean) {
            require(timeoutMs > 0 && intervalMs > 0)
            val previous = active.get()
            val end = SystemClock.elapsedRealtime() + timeoutMs
            val deadline = previous?.takeIf { it.end <= end } ?: ObservationDeadline(end)
            active.set(deadline)
            withFixtureCleanup({ if (previous == null) active.remove() else active.set(previous) }) {
                try {
                    while (true) {
                        deadline.checkRemaining()
                        val observed = ready()
                        deadline.checkRemaining() // A late successful observation is not success.
                        if (observed) return
                        SystemClock.sleep(minOf(intervalMs, deadline.remainingMs()))
                    }
                } catch (expired: Expired) {
                    // A nested await cannot rename or extend its parent's deadline.
                    if (expired.deadline !== deadline || previous === deadline) throw expired
                    throw AssertionError(message, expired)
                }
            }
        }
    }
}
