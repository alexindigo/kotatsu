package com.alexindigo.kotatsu.sync

/** Missing-only registration shared by process start, broadcasts and job successors. */
internal object SyncArming {
    fun ensure(jobs: List<Int>, pending: (Int) -> Boolean, schedule: (Int) -> Unit) {
        for (id in jobs) if (!pending(id)) schedule(id)
    }
}
