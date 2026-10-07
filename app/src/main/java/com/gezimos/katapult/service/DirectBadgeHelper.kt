package com.gezimos.katapult.service

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.provider.CallLog
import android.provider.Telephony
import com.gezimos.katapult.util.SensitiveAccess
import com.gezimos.katapult.util.SourceGeneration
import com.gezimos.katapult.util.SourceRead
import java.util.concurrent.CopyOnWriteArrayList

/** Narrow source/observer inputs; Android production and isolated fixtures use the same worker. */
internal interface DirectBadgePort {
    fun callEligible(): Boolean
    fun smsEligible(): Boolean
    fun callRevision(): Long
    fun smsRevision(): Long
    fun writeGranted(): Boolean
    fun acknowledgmentEligible(): Boolean
    fun callCount(signal: CancellationSignal): Int?
    fun smsCount(signal: CancellationSignal): Int?
    fun observe(calls: Boolean, observer: ContentObserver): AutoCloseable
    fun acknowledgeCalls()
    fun subscribe(changed: () -> Unit): AutoCloseable
}

class DirectBadgeHelper internal constructor(private val port: DirectBadgePort) : AutoCloseable {
    companion object {
        const val MUDITA_DIAL = "com.mudita.dial"
        const val MUDITA_MESSAGES = "com.mudita.messages"
        val DIRECT_PACKAGES = setOf(MUDITA_DIAL, MUDITA_MESSAGES)
        private const val MISSED_SELECTION =
            "((${CallLog.Calls.TYPE} IN (?, ?) AND ${CallLog.Calls.NEW} = 1) OR " +
                "(${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.IS_READ} = 0))"
        private val MISSED_ARGS = arrayOf(CallLog.Calls.MISSED_TYPE.toString(),
            CallLog.Calls.REJECTED_TYPE.toString(), CallLog.Calls.MISSED_TYPE.toString())
        @Volatile private var instance: DirectBadgeHelper? = null
        fun getInstance(context: Context): DirectBadgeHelper {
            SensitiveAccess.initialize(context)
            return instance ?: synchronized(this) {
                instance ?: DirectBadgeHelper(AndroidPort(context.applicationContext)).also { instance = it }
            }
        }
    }

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var closed = false
    private val calls = Worker("KotatsuCallCounts", true, port::callEligible, port::callRevision, port::callCount)
    private val sms = Worker("KotatsuSmsCounts", false, port::smsEligible, port::smsRevision, port::smsCount)
    private val policySubscription = port.subscribe(::reconcile)

    fun addListener(listener: () -> Unit) { listeners.addIfAbsent(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
    private fun notifyChanged() { listeners.forEach { it() } }
    fun reconcile() { calls.reconcile(); sms.reconcile() }
    fun getCounts(): Map<String, Int> {
        reconcile()
        return buildMap {
            if (!closed && port.callEligible()) calls.state.value?.let { put(MUDITA_DIAL, it) }
            if (!closed && port.smsEligible()) sms.state.value?.let { put(MUDITA_MESSAGES, it) }
        }
    }
    fun hasMissedCalls(): Boolean = (getCounts()[MUDITA_DIAL] ?: 0) > 0
    fun hasWriteCallLogPermission(): Boolean = port.writeGranted()

    fun clearMissedCalls(stillCurrent: () -> Boolean, finished: () -> Unit = {}) {
        reconcile()
        if (closed || !port.acknowledgmentEligible() || !stillCurrent()) { finished(); return }
        calls.action(finished) {
            if (closed || !port.acknowledgmentEligible() || !stillCurrent()) return@action
            port.acknowledgeCalls()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        policySubscription.close()
        reconcile()
        listeners.clear()
    }

    private class AndroidPort(private val context: Context) : DirectBadgePort {
        override fun callEligible() = SensitiveAccess.callBadges()
        override fun smsEligible() = SensitiveAccess.smsBadges()
        override fun callRevision() = SensitiveAccess.callRevision
        override fun smsRevision() = SensitiveAccess.smsRevision
        override fun writeGranted() = SensitiveAccess.canWriteCallLog()
        override fun acknowledgmentEligible() = SensitiveAccess.callAcknowledgment()
        override fun subscribe(changed: () -> Unit) = SensitiveAccess.subscribe(changed)
        override fun observe(calls: Boolean, observer: ContentObserver): AutoCloseable {
            val uri = if (calls) CallLog.Calls.CONTENT_URI else Telephony.MmsSms.CONTENT_URI
            context.contentResolver.registerContentObserver(uri, true, observer)
            return AutoCloseable { context.contentResolver.unregisterContentObserver(observer) }
        }
        private fun count(uri: Uri, projection: String, selection: String, args: Array<String>, signal: CancellationSignal): Int? =
            try { context.contentResolver.query(uri, arrayOf(projection), selection, args, null, signal)?.use { it.count } }
            catch (_: Exception) { null }
        override fun callCount(signal: CancellationSignal): Int? {
            if (!callEligible()) return null
            return count(CallLog.Calls.CONTENT_URI, CallLog.Calls._ID, MISSED_SELECTION, MISSED_ARGS, signal)
        }
        override fun smsCount(signal: CancellationSignal): Int? {
            if (!smsEligible()) return null
            val one = count(Telephony.Sms.Inbox.CONTENT_URI, Telephony.Sms._ID, "${Telephony.Sms.READ} = ?", arrayOf("0"), signal)
            if (!smsEligible() || signal.isCanceled) return null
            val two = count(Telephony.Mms.Inbox.CONTENT_URI, Telephony.Mms._ID, "${Telephony.Mms.READ} = ?", arrayOf("0"), signal)
            return if (one == null || two == null) null else one + two
        }
        override fun acknowledgeCalls() {
            if (!acknowledgmentEligible()) return
            val values = ContentValues(2).apply { put(CallLog.Calls.NEW, 0); put(CallLog.Calls.IS_READ, 1) }
            context.contentResolver.update(CallLog.Calls.CONTENT_URI, values, MISSED_SELECTION, MISSED_ARGS)
        }
    }

    /** Each source has its own observer, cancellation, worker and generation. */
    private inner class Worker(private val name: String, private val callSource: Boolean,
                               private val eligible: () -> Boolean, private val revision: () -> Long,
                               private val read: (CancellationSignal) -> Int?) {
        val state = SourceGeneration<Int>()
        private val lock = Any()
        private var thread: HandlerThread? = null
        private var handler: Handler? = null
        private var observer: ContentObserver? = null
        private var observation: AutoCloseable? = null
        private var cancellation: CancellationSignal? = null
        private var work: Runnable? = null
        private var seenRevision = revision()
        @Volatile private var activationEpoch = 0L

        fun reconcile() {
            var notify = false
            synchronized(lock) {
                val want = !closed && eligible()
                if (seenRevision != revision()) {
                    seenRevision = revision()
                    activationEpoch++
                    state.reconcile(false)
                    detach()
                    notify = true
                }
                if (state.reconcile(want)) {
                    activationEpoch++
                    notify = true
                    detach() // Epoch was invalidated before observers/work/data teardown.
                    if (want) {
                        val nextThread = HandlerThread(name).apply { start() }
                        val nextHandler = Handler(nextThread.looper)
                        thread = nextThread; handler = nextHandler
                        val nextObserver = object : ContentObserver(nextHandler) {
                            override fun onChange(selfChange: Boolean) {
                                // A callback delivered by a detached observer cannot
                                // schedule work in a newer source activation.
                                schedule(300L, this)
                            }
                        }
                        observer = nextObserver
                        try {
                            if (eligible() && !closed) observation = port.observe(callSource, nextObserver)
                            else state.reconcile(false)
                        } catch (_: Exception) {
                            state.reconcile(false)
                        }
                        if (state.enabled) schedule(0L) else detach()
                    }
                }
            }
            if (notify) notifyChanged()
        }

        private fun detach() {
            work?.let { handler?.removeCallbacks(it) }
            work = null
            cancellation?.cancel(); cancellation = null
            observer = null
            observation?.let { try { it.close() } catch (_: Exception) {} }
            observation = null
            thread?.quitSafely()
            handler = null; thread = null
        }

        fun invalidate() {
            synchronized(lock) { state.invalidate(); detach(); state.reconcile(false) }
            notifyChanged()
        }

        private fun schedule(delay: Long, originatingObserver: ContentObserver? = null) {
            synchronized(lock) {
                if (originatingObserver != null && observer !== originatingObserver) return
                val target = handler ?: return
                work?.let { target.removeCallbacks(it) }
                cancellation?.cancel()
                val epoch = state.invalidate()
                val inputRevision = revision()
                val signal = CancellationSignal()
                cancellation = signal
                val next = Runnable {
                    if (closed || !eligible() || !state.enabled || state.epoch != epoch || signal.isCanceled) {
                        reconcile(); return@Runnable
                    }
                    val publish = SourceRead(state).run(epoch, { !closed && eligible() && !signal.isCanceled && inputRevision == revision() }) {
                        try { read(signal) } catch (_: Exception) { null }
                    }
                    if (publish) notifyChanged() else reconcile()
                }
                work = next
                target.postDelayed(next, delay)
            }
        }

        fun action(finished: () -> Unit, operation: () -> Unit) {
            val target: Handler?
            val epoch: Long
            synchronized(lock) { target = handler; epoch = activationEpoch }
            if (target == null) { finished(); return }
            target.post {
                try {
                    if (!eligible() || activationEpoch != epoch) return@post
                    try { operation() } catch (_: Exception) { invalidate() }
                    if (eligible()) schedule(0L) else reconcile()
                } finally { finished() }
            }
        }
    }
}
