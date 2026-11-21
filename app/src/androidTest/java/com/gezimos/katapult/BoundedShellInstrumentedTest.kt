package com.gezimos.katapult

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeoutException

class BoundedShellInstrumentedTest {
    private val automation get() = InstrumentationRegistry.getInstrumentation().platformAutomation
    private fun scripts() = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        .listFiles()!!.filter { it.name.startsWith("kotatsu-shell-") }.map { it.name }.toSet()

    @Test fun successPreservesEmptyAndMultilineStdoutAndLiteralArguments() {
        val before = scripts()
        assertEquals("", automation.acknowledgedShell("true"))
        assertEquals("first\nsecond", automation.acknowledgedShell("printf first\\nsecond\\n"))
        assertEquals("first\r\nsecond", automation.acknowledgedShell("printf first\\r\\nsecond\\n"))
        assertEquals("'\";|&`$*", automation.acknowledgedShell("printf %s '\";|&`$*"))
        assertEquals("", automation.acknowledgedShell("cat")) // Child stdin must be EOF, not our script.
        assertEquals(before, scripts())
    }

    @Test fun nonzeroExitCannotMasqueradeAsSuccessfulOutput() {
        val before = scripts()
        val failure = runCatching { automation.acknowledgedShell("false") }.exceptionOrNull()
        assertTrue("Expected a confirmed command failure, got $failure", failure is ShellCommandFailure)
        assertEquals(1, (failure as ShellCommandFailure).exitCode)
        assertEquals("alive", automation.acknowledgedShell("printf alive"))
        assertEquals(before, scripts())
    }

    @Test fun missingExecutableIsAcknowledgedAsFailureWithoutADeadline() {
        val before = scripts()
        val failure = runCatching { automation.acknowledgedShell("/system/bin/kotatsu_missing_test_command") }.exceptionOrNull()
        assertTrue("A missing executable must report its actual failure, got $failure", failure is ShellCommandFailure)
        assertEquals(127, (failure as ShellCommandFailure).exitCode)
        assertEquals(before, scripts())
    }

    @Test fun eofWithoutAValidCompletionRecordIsNeverSuccess() {
        val marker = "\nkotatsu-shell-test:"
        for (output in listOf("", "some output", "${marker}no-status\n", "${marker}0\ntrailing", "${marker}256\n")) {
            val failure = runCatching { confirmedShellStdout("test-command", output, marker) }.exceptionOrNull()
            assertTrue("Incomplete output was accepted: $output", failure is AssertionError)
        }
        assertEquals("", confirmedShellStdout("test-command", "${marker}0\n", marker))
        assertEquals("first${marker}0\nsecond", confirmedShellStdout(
            "test-command", "first${marker}0\nsecond${marker}0\n", marker))
    }

    @Test fun liveOutputPipeIsBoundedAndNextCommandStillCompletes() {
        val before = scripts()
        val start = SystemClock.elapsedRealtime()
        val failure = runCatching { automation.boundedShell("sleep 3", 1) }.exceptionOrNull()
        assertTrue("Expected the deadline, got $failure", failure?.cause is TimeoutException)
        assertTrue("The deadline must not wait for the child", SystemClock.elapsedRealtime() - start < 2500)
        assertEquals("alive", automation.acknowledgedShell("printf alive"))
        assertEquals(before, scripts())
    }

    @Test fun completedCommandDoesNotWaitForAnInheritedOutputPipe() {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        val token = UUID.randomUUID().toString()
        val fifo = "/data/local/tmp/kotatsu-shell-$token"
        val pid = File(directory, "pipe-$token.pid")
        val command = File(directory, "pipe-$token.sh")
        val release = File(directory, "release-$token.sh")
        command.writeText("cat $fifo &\nprintf '%s' \"\$!\" > ${pid.absolutePath}\nprintf 'completed\\n'\n")
        release.writeText("kill \$(cat ${pid.absolutePath})\nrm $fifo\n")
        withFixtureCleanup(body = {
            automation.acknowledgedShell("su 0 mkfifo $fifo")
            // This test owns and releases the blocked child even when the protocol regresses.
            assertEquals("completed", automation.boundedShell("su 0 sh ${command.absolutePath}", 10))
        }, actions = arrayOf<() -> Unit>(
            { automation.acknowledgedShell("su 0 sh ${release.absolutePath}"); Unit },
            { for (file in listOf(command, release, pid)) check(!file.exists() || file.delete()) },
        ))
    }
}
