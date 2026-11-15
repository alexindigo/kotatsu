package com.gezimos.katapult.screenshot

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class ScreenshotCaptureOrderTest {
    private class ControlledExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun runNext() { tasks.removeFirst().run() }
    }

    private class Fixture {
        val order = ScreenshotCaptureOrder()
        val worker = ControlledExecutor()
        val main = ControlledExecutor()
        var now = 0L
        var expiresAt = 0L
        var preview: String? = null
        var saves = 0

        fun result(token: ScreenshotCaptureOrder.Token, value: String) {
            main.execute {
                if (order.isLatest(token)) { preview = value; expiresAt = now + 3000 }
            }
        }
        fun save(token: ScreenshotCaptureOrder.Token) {
            worker.execute { saves++; result(token, "saved") }
        }
        fun advance(millis: Long) {
            now += millis
            if (now >= expiresAt) preview = null
        }
        fun clear() { order.clear(); preview = null }
    }

    @Test fun oldSaveAfterNewFailureCannotReplaceFailureOrExtendItsExpiry() {
        val f = Fixture()
        f.save(f.order.begin()) // Hold the first save while the second capture fails.
        val newest = f.order.begin()
        f.result(newest, "unavailable"); f.main.runNext()
        f.advance(1000)
        f.worker.runNext(); f.main.runNext()
        assertEquals(1, f.saves)
        assertEquals("unavailable", f.preview)
        assertEquals(3000L, f.expiresAt)
        f.advance(2000)
        assertNull(f.preview)
    }

    @Test fun expiredNewFailureCannotBeResurrectedByOldSave() {
        val f = Fixture()
        f.save(f.order.begin())
        f.result(f.order.begin(), "unavailable"); f.main.runNext()
        f.advance(3000)
        f.worker.runNext(); f.main.runNext()
        assertNull(f.preview)
    }

    @Test fun queuedOldFailureCannotReplaceNewSuccess() {
        val f = Fixture()
        val old = f.order.begin()
        f.result(old, "storage full") // Posted before, but delivered after the next capture starts.
        val newest = f.order.begin()
        f.main.runNext()
        assertNull(f.preview)
        f.save(newest); f.worker.runNext(); f.main.runNext()
        f.result(old, "unavailable"); f.main.runNext()
        assertEquals("saved", f.preview)
    }

    @Test fun newCaptureDoesNotCancelAnOlderCaptureOrItsSave() {
        val f = Fixture()
        val old = f.order.begin()
        val newest = f.order.begin()
        assertTrue(f.order.isActive(old))
        assertTrue(f.order.isActive(newest))
        f.save(old); f.save(newest)
        f.worker.runNext(); f.main.runNext()
        assertNull(f.preview)
        f.worker.runNext(); f.main.runNext()
        assertEquals(2, f.saves)
        assertEquals("saved", f.preview)
    }

    @Test fun clearInvalidatesPendingCapturesAndPostedResultsWithoutBlockingNextCapture() {
        val f = Fixture()
        val old = f.order.begin()
        f.save(old); f.worker.runNext()
        f.clear()
        assertFalse(f.order.isActive(old))
        f.main.runNext()
        f.result(old, "unavailable"); f.main.runNext()
        assertNull(f.preview)
        f.save(f.order.begin()); f.worker.runNext(); f.main.runNext()
        assertEquals("saved", f.preview)
    }
}
