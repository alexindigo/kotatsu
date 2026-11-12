package com.gezimos.katapult

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bounds launch and pipe reads, including UiAutomation's missing-executable/no-EOF failure. */
internal fun UiAutomation.boundedShell(command: String, timeoutSeconds: Long = 10): String {
    val input = AtomicReference<ParcelFileDescriptor.AutoCloseInputStream?>()
    val cancelled = AtomicBoolean()
    val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "kotatsu-test-shell").apply { isDaemon = true }
    }
    val result = worker.submit<String> {
        val stream = ParcelFileDescriptor.AutoCloseInputStream(executeShellCommand(command))
        input.set(stream)
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            stream.close()
            throw InterruptedException("Shell command cancelled: $command")
        }
        stream.bufferedReader().use { it.readText().trim() }
    }
    try {
        return result.get(timeoutSeconds, TimeUnit.SECONDS)
    } catch (error: TimeoutException) {
        throw AssertionError("Shell command exceeded ${timeoutSeconds}s: $command", error)
    } catch (error: ExecutionException) {
        throw AssertionError("Shell command failed: $command", error.cause)
    } finally {
        cancelled.set(true)
        result.cancel(true)
        try { input.get()?.close() } finally { worker.shutdownNow() }
    }
}
