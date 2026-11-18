package com.gezimos.katapult

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeoutException

class BoundedShellInstrumentedTest {
    private val automation get() = InstrumentationRegistry.getInstrumentation().platformAutomation
    private fun scripts() = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        .listFiles()!!.filter { it.name.startsWith("kotatsu-shell-") }.map { it.name }.toSet()

    @Test fun successPreservesEmptyAndMultilineStdoutAndLiteralArguments() {
        val before = scripts()
        assertEquals("", automation.boundedShell("true"))
        assertEquals("first\nsecond", automation.boundedShell("printf first\\nsecond\\n"))
        assertEquals("'\";|&`$*", automation.boundedShell("printf %s '\";|&`$*"))
        assertEquals("", automation.boundedShell("cat")) // Child stdin must be EOF, not our script.
        assertEquals(before, scripts())
    }

    @Test fun nonzeroExitCannotMasqueradeAsSuccessfulOutput() {
        val before = scripts()
        val failure = runCatching { automation.boundedShell("false") }.exceptionOrNull()
        assertTrue("Expected a confirmed command failure, got $failure", failure is ShellCommandFailure)
        assertEquals(1, (failure as ShellCommandFailure).exitCode)
        assertEquals("alive", automation.boundedShell("printf alive"))
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
        assertEquals("alive", automation.boundedShell("printf alive"))
        assertEquals(before, scripts())
    }
}
