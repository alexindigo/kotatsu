package com.alexindigo.kotatsu.weather

import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException

/** A generation survives JobParameters parceling; a reusable scheduler ID does not identify a run.
 * Null identifies jobs scheduled before generation extras were introduced, until normal replacement. */
internal data class RefreshExecutionKey(val jobId: Int, val generation: String?) {
    companion object { const val GENERATION = "refreshGeneration" }
}

/** Start, stop and posted completion run on the service's main thread. Only work runs off-main. */
internal class RefreshJobExecutions(
    private val executor: Executor,
    private val postToMain: (() -> Unit) -> Unit,
) {
    private class Execution(val finished: () -> Unit) {
        lateinit var task: FutureTask<Unit>
    }
    private val executions = mutableMapOf<RefreshExecutionKey, Execution>()

    fun start(key: RefreshExecutionKey, work: () -> Unit, finished: () -> Unit): Boolean {
        val execution = Execution(finished)
        execution.task = object : FutureTask<Unit>({ work(); Unit }) {
            // done also runs for cancellation before the callable starts. Its finally would not.
            override fun done() = postToMain {
                if (executions[key] === execution) {
                    executions.remove(key)
                    execution.finished()
                }
            }
        }
        executions[key] = execution
        try {
            executor.execute(execution.task)
        } catch (_: RejectedExecutionException) {
            executions.remove(key)
            execution.task.cancel(false)
            // onStartJob returns false: the framework acknowledges this non-async start.
            return false
        }
        return true
    }

    fun stop(key: RefreshExecutionKey) {
        // The framework acknowledges this execution's stop. A late done must not finish it again.
        executions.remove(key)?.task?.cancel(true)
    }

    fun stopAll() = executions.keys.toList().forEach(::stop)
}
