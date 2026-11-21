package com.gezimos.katapult

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.BufferedReader
import java.util.StringTokenizer
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Normal fixture operations are ordered by their exit acknowledgement, not a per-command
 * wall-clock guess. The connected runner owns the hard run deadline and emulator teardown;
 * abandoning an in-flight mutation here lets it race subsequent fixture restoration. */
internal fun UiAutomation.acknowledgedShell(command: String): String = shellCompletion(command, null)

/** Explicitly bounded operation, for callers that own cancellation of their command. */
internal fun UiAutomation.boundedShell(command: String, timeoutSeconds: Long): String = shellCompletion(command, timeoutSeconds)

/** Returns trimmed stdout only after a confirmed zero exit.
 * UiAutomation's remote launch errors can yield either EOF or a pipe that never closes;
 * neither EOF nor empty output establishes command success. Arguments retain Runtime.exec's
 * whitespace tokenization: quoting and shell operators are literal, not interpreted here. */
private fun UiAutomation.shellCompletion(command: String, timeoutSeconds: Long?): String {
    val started = SystemClock.elapsedRealtime()
    val arguments = StringTokenizer(command).toList().map { it.toString() }
    require(arguments.isNotEmpty()) { "Shell command must not be empty" }
    require(timeoutSeconds == null || timeoutSeconds > 0) { "Shell deadline must be positive" }
    Log.i("KotatsuTestShell", "Starting: $command")
    val marker = "\nkotatsu-shell-${UUID.randomUUID()}:"
    val invocation = arguments.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
    val pipe = AtomicReference<ParcelFileDescriptor?>()
    val script = AtomicReference<File?>()
    val cancelled = AtomicBoolean()
    val phase = AtomicReference("waiting for shell worker")
    val observed = StringBuffer()
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
            phase.set("creating command script")
            val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
            checkNotNull(directory) { "Shell script directory is unavailable" }
            val file = File.createTempFile("kotatsu-shell-", ".sh", directory)
            script.set(file)
            if (cancelled.get() || Thread.currentThread().isInterrupted) {
                throw InterruptedException("Shell command cancelled: $command")
            }
            // Isolate exit/exec and prevent the child from waiting for stdin.
            file.writeText("( $invocation ) </dev/null\nprintf '$marker%s\\n' \"\$?\"\n")
            phase.set("launching UiAutomation command")
            val descriptor = executeShellCommand("/system/bin/sh ${file.absolutePath}")
            pipe.set(descriptor)
            if (cancelled.get() || Thread.currentThread().isInterrupted) {
                throw InterruptedException("Shell command cancelled: $command")
            }
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
                phase.set("waiting for command completion record")
                readShellCompletion(reader, marker, observed)
            }
        }
    }
    withFixtureCleanup(body = {
        try {
            val output = if (timeoutSeconds == null) result.get() else result.get(timeoutSeconds, TimeUnit.SECONDS)
            val stdout = confirmedShellStdout(command, output, marker)
            Log.i("KotatsuTestShell", "Acknowledged after ${SystemClock.elapsedRealtime() - started}ms: $command")
            return stdout
        } catch (error: TimeoutException) {
            Log.i("KotatsuTestShell", "Timed out after ${timeoutSeconds}s: $command")
            throw AssertionError("Shell command exceeded ${timeoutSeconds}s: $command\nPhase: ${phase.get()}\nObserved stdout: $observed", error)
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

/** A child can retain stdout after its parent exits. The explicit exit record, not pipe EOF,
 * is the acknowledgement; still reject missing/malformed records and nonzero status. */
private fun readShellCompletion(reader: BufferedReader, marker: String, output: StringBuffer): String {
    val prefix = marker.removePrefix("\n")
    var lineStart = 0
    while (true) {
        val character = reader.read()
        if (character == -1) return output.toString()
        output.append(character.toChar())
        if (character == '\n'.code) {
            if (output.substring(lineStart, output.length - 1).startsWith(prefix)) return output.toString()
            lineStart = output.length
        }
    }
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
