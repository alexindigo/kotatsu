package com.gezimos.katapult.notes

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One page generation, one serialized I/O lane. Closing invalidates all queued UI delivery. */
class TaskListSession(private val notes: NotesPort, private val id: Long?,
                      private val changed: (TaskListSnapshot?) -> Unit) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lane = Mutex()
    private var observer: AutoCloseable? = null
    private var closed = false
    private var writing = false
    private var snapshot: TaskListSnapshot? = null

    fun start() {
        try { observer = notes.observe(::refresh) } catch (_: RuntimeException) { /* A read still determines availability. */ }
        refresh()
    }

    fun refresh() {
        scope.launch {
            lane.withLock {
                val fresh = withContext(Dispatchers.IO) {
                    try { id?.let(notes::read) } catch (_: RuntimeException) { null }
                }
                if (!closed) { snapshot = fresh; changed(fresh) }
            }
        }
    }

    fun toggle(task: TaskToggle) {
        if (writing || closed) return
        writing = true
        scope.launch {
            try {
                lane.withLock {
                    // A queued observer read can advance the session before this command gets the
                    // lane. Never reinterpret an old row index using that newer snapshot.
                    if (task.rendered != snapshot) {
                        if (!closed) changed(snapshot)
                        return@withLock
                    }
                    val result = withContext(Dispatchers.IO) {
                        toggleTask(notes, task.rendered, task.index, System.currentTimeMillis())
                    }
                    if (!closed) { snapshot = result.persisted; changed(result.persisted) }
                }
            } finally { writing = false }
        }
    }

    override fun close() {
        closed = true
        try { observer?.close() }
        catch (error: RuntimeException) { android.util.Log.w("TaskListSession", "Could not stop Notes observation", error) }
        finally { observer = null; scope.cancel(); snapshot = null }
    }
}
