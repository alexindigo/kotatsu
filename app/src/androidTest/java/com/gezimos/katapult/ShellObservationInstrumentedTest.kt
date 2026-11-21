package com.gezimos.katapult

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ShellObservationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.platformAutomation

    @Test fun stalledReadCannotOutliveItsUiAwait() = stalledRead(false)

    @Test fun nestedFixtureAwaitCannotExtendItsUiDeadline() = stalledRead(true)

    @Test fun explicitCommandBudgetCannotExtendItsUiDeadline() = stalledRead(false, true)

    private fun stalledRead(nested: Boolean, bounded: Boolean = false) {
        val directory = instrumentation.targetContext.getExternalFilesDir(null)!!
        val token = UUID.randomUUID().toString()
        val fifo = "/data/local/kotatsu-observation-$token"
        val pid = File(directory, "read-$token.pid")
        val command = File(directory, "read-$token.sh")
        val release = File(directory, "release-read-$token.sh")
        command.writeText("echo \$\$ > ${pid.absolutePath}\nexec cat $fifo\n")
        release.writeText("if [ -f ${pid.absolutePath} ]; then kill \$(cat ${pid.absolutePath}) 2>/dev/null || true; fi\nrm -f $fifo\n")
        val worker = Executors.newSingleThreadExecutor()
        var pending: java.util.concurrent.Future<Throwable?>? = null
        withFixtureCleanup(body = {
            automation.acknowledgedShell("su 0 mkfifo $fifo")
            pending = worker.submit<Throwable?> {
                runCatching {
                    PlatformUiDriver(instrumentation).await("The observation deadline fired", 1000) {
                        if (nested) fixtureAwait("The nested await must not own the deadline") {
                            automation.readShell("su 0 sh ${command.absolutePath}")
                            true
                        } else if (bounded) automation.boundedShell("su 0 sh ${command.absolutePath}", 10)
                        else automation.readShell("su 0 sh ${command.absolutePath}")
                        true
                    }
                }.exceptionOrNull()
            }
            val failure = pending!!.get(3, TimeUnit.SECONDS)
            assertTrue("The real stalled read must have started", pid.exists())
            assertTrue("The await must own the timeout: $failure", failure is AssertionError)
            assertEquals("The observation deadline fired", failure!!.message)
        }, actions = arrayOf<() -> Unit>(
            { automation.acknowledgedShell("su 0 sh ${release.absolutePath}"); Unit },
            { pending?.cancel(true); worker.shutdownNow(); Unit },
            { for (file in listOf(command, release, pid)) check(!file.exists() || file.delete()) },
        ))
    }

    @Test fun mutationsCannotStartInsideObservationPredicates() {
        val marker = File(instrumentation.targetContext.getExternalFilesDir(null), "unstarted-${UUID.randomUUID()}")
        withFixtureCleanup({ if (marker.exists()) check(marker.delete()) }) {
            val failure = runCatching {
                ObservationDeadline.await("Mutation must not launch", 1000, 25) {
                    automation.acknowledgedShell("touch ${marker.absolutePath}")
                    true
                }
            }.exceptionOrNull()
            assertTrue("An unbounded helper must be rejected before launch: $failure", failure is IllegalStateException)
            assertFalse(marker.exists())
            assertNull("Observation scope must be removed on exceptions", ObservationDeadline.current())
        }
    }

    @Test fun readHelperKeepsExitStatusAndAbsentProcessSemantics() {
        assertEquals("", automation.readProcessIds("com.alexindigo.kotatsu.missing_process"))
        val failure = runCatching { automation.readShell("/system/bin/kotatsu_missing_read") }.exceptionOrNull()
        assertTrue(failure is ShellCommandFailure)
        assertEquals(127, (failure as ShellCommandFailure).exitCode)
        assertEquals("first\r\nsecond", automation.readShell("printf first\\r\\nsecond"))
    }
}
