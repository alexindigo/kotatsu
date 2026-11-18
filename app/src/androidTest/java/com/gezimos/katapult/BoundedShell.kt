package com.gezimos.katapult

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.StringTokenizer
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Returns trimmed stdout only after a confirmed zero exit, or fails within the deadline.
 * UiAutomation's remote launch errors can yield either EOF or a pipe that never closes;
 * neither EOF nor empty output establishes command success. Arguments retain Runtime.exec's
 * whitespace tokenization: quoting and shell operators are literal, not interpreted here. */
internal fun UiAutomation.boundedShell(command: String, timeoutSeconds: Long = 10): String {
    val arguments = StringTokenizer(command).toList().map { it.toString() }
    require(arguments.isNotEmpty()) { "Shell command must not be empty" }
    require(timeoutSeconds > 0) { "Shell deadline must be positive" }
    val marker = "\nkotatsu-shell-${UUID.randomUUID()}:"
    val invocation = arguments.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
    val pipe = AtomicReference<ParcelFileDescriptor?>()
    val script = AtomicReference<File?>()
    val cancelled = AtomicBoolean()
    fun removeScript() = script.get()?.let { file ->
        check(!file.exists() || file.delete() || !file.exists()) { "Cannot remove shell script: $file" }
    }
    val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "kotatsu-test-shell").apply { isDaemon = true }
    }
    val result = worker.submit<String> {
        // Runtime.exec(String) cannot quote sh -c, and the stdin API requires API31.
        // Use the same shell-readable, owned script location as native input fixtures.
        withFixtureCleanup({ pipe.get()?.close() }, { removeScript() }) {
            val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
            checkNotNull(directory) { "Shell script directory is unavailable" }
            val file = File.createTempFile("kotatsu-shell-", ".sh", directory)
            script.set(file)
            if (cancelled.get() || Thread.currentThread().isInterrupted) {
                throw InterruptedException("Shell command cancelled: $command")
            }
            // Isolate exit/exec and prevent the child from waiting for stdin.
            file.writeText("( $invocation ) </dev/null\nprintf '$marker%s\\n' \"\$?\"\n")
            val descriptor = executeShellCommand("/system/bin/sh ${file.absolutePath}")
            pipe.set(descriptor)
            if (cancelled.get() || Thread.currentThread().isInterrupted) {
                throw InterruptedException("Shell command cancelled: $command")
            }
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
    }
    withFixtureCleanup(body = {
        try {
            return confirmedShellStdout(command, result.get(timeoutSeconds, TimeUnit.SECONDS), marker)
        } catch (error: TimeoutException) {
            throw AssertionError("Shell command exceeded ${timeoutSeconds}s: $command", error)
        } catch (error: ExecutionException) {
            throw AssertionError("Shell command failed: $command", error.cause)
        }
    }, actions = arrayOf<() -> Unit>(
        { cancelled.set(true) },
        { result.cancel(true) },
        { fixtureCleanup(
            { pipe.get()?.close() },
            { removeScript() },
            { worker.shutdownNow() },
        ) },
    ))
}

internal fun confirmedShellStdout(command: String, output: String, marker: String): String {
    val record = output.lastIndexOf(marker)
    val status = if (record >= 0) output.substring(record + marker.length).trim().toIntOrNull() else null
    if (status == null || status !in 0..255) throw AssertionError("Shell command ended without a valid exit status: $command")
    val stdout = output.substring(0, record).trim()
    if (status != 0) throw ShellCommandFailure(command, status, stdout)
    return stdout
}

internal class ShellCommandFailure(command: String, val exitCode: Int, output: String) :
    AssertionError("Shell command exited $exitCode: $command\n$output")
