package com.alexindigo.kotatsu.sync

/** Plain lifecycle orchestration; Android owns execution and completion callbacks. */
internal object PassLifecycle {
    fun run(pass: () -> Unit, successor: () -> Unit, finish: (Boolean) -> Unit) {
        var retry = false
        try { pass() } catch (_: Exception) { retry = true }
        finally {
            try { successor() } catch (_: Exception) { retry = true }
            finally { finish(retry) }
        }
    }
    fun boot(armRecurring: () -> Unit, scheduleBootPass: () -> Unit) {
        try { armRecurring() } finally { scheduleBootPass() }
    }
}
