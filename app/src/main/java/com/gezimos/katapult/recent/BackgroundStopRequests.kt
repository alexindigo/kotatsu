package com.gezimos.katapult.recent

/** Requests only: Android may keep a protected process alive after every attempt. */
internal class BackgroundStopRequests(
    private val schedule: (Long, () -> Unit) -> AutoCloseable,
    private val requestStop: (String) -> Unit,
) : AutoCloseable {
    private class Request { val tasks = mutableListOf<AutoCloseable>() }
    private val pending = mutableMapOf<String, Request>()
    private var closed = false

    @Synchronized fun request(packageName: String) {
        if (closed) return
        cancel(packageName)
        val request = Request()
        pending[packageName] = request
        val delays = listOf(0L, 300L, 900L, 1500L)
        for ((index, delay) in delays.withIndex()) {
            request.tasks += schedule(delay) {
                val current = synchronized(this) {
                    if (pending[packageName] === request) {
                        if (index == delays.lastIndex) pending.remove(packageName)
                        true
                    } else false
                }
                // A binder call must never hold the lock used by the UI/teardown path.
                if (current) requestStop(packageName)
            }
        }
    }

    @Synchronized fun cancel(packageName: String) {
        pending.remove(packageName)?.tasks?.forEach { it.close() }
    }

    @Synchronized fun cancelAll() {
        pending.keys.toList().forEach(::cancel)
    }

    @Synchronized override fun close() {
        closed = true
        cancelAll()
    }
}
