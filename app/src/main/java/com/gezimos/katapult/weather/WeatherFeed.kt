package com.gezimos.katapult.weather

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.gezimos.katapult.weather.api.Weather
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One process-wide owner of optional current weather. No provider call runs on a consumer thread. */
class WeatherFeed internal constructor(
    private val resolveSource: () -> Source?,
    private val worker: Executor,
    private val callbacks: Executor,
    private val now: () -> Long,
) : AutoCloseable {
    sealed interface State {
        /** readAt uses elapsed realtime, so freshness is independent of wall-clock changes. */
        data class Reading(val weather: Weather, val readAt: Long) : State
        data object Unavailable : State
        data object Failed : State
    }

    /** The adapter boundary is also injectable without Android or a remote provider. */
    internal class Source(val identity: Any, val read: () -> Weather?, val observe: (() -> Unit) -> AutoCloseable)

    private val lock = Any()
    private val leases = linkedMapOf<Any, (State) -> Unit>()
    private var generation = 0L
    private var session = 0L
    private var scheduled = false
    private var closed = false
    private var lastCompletedAt: Long? = null
    @Volatile var state: State = State.Unavailable
        private set
    // Owned exclusively by the single worker; stale observer callbacks are checked by identity.
    @Volatile private var source: Source? = null
    private var subscription: AutoCloseable? = null
    private var attachedSession = -1L

    fun acquire(changed: (State) -> Unit): AutoCloseable {
        val owner = Any()
        synchronized(lock) {
            check(!closed)
            val newSession = leases.isEmpty()
            if (newSession) session++
            leases[owner] = changed
            requestLocked(force = newSession)
        }
        callbacks.execute {
            // Read at delivery: the worker may publish between acquire and this enqueue.
            // A captured initial Unavailable must never overwrite that newer Reading.
            val initial = synchronized(lock) { state.takeIf { !closed && leases[owner] === changed } }
            initial?.let(changed)
        }
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (released.compareAndSet(false, true)) synchronized(lock) {
                leases.remove(owner)
                if (leases.isEmpty()) {
                    session++
                    generation++ // Invalidate a blocked read before its observer can detach.
                    state = State.Unavailable
                    lastCompletedAt = null
                    scheduleLocked()
                }
            }
        }
    }

    /** Activation/clock checks share freshness; wake and provider notifications force a new read. */
    fun refresh(force: Boolean = false) = synchronized(lock) { requestLocked(force) }

    private fun requestLocked(force: Boolean) {
        if (closed || leases.isEmpty()) return
        if (!force && scheduled) return // Join slow pending work; clock ticks must not starve it.
        // An empty or failing provider costs the same IPC as a successful read.
        if (!force && lastCompletedAt?.let { now() - it < REFRESH_MS } == true) return
        generation++
        scheduleLocked()
    }

    private fun scheduleLocked() {
        if (scheduled) return
        scheduled = true
        worker.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val (epoch, active, leaseSession) = synchronized(lock) { Triple(generation, !closed && leases.isNotEmpty(), session) }
            if (attachedSession != leaseSession) { detach(); attachedSession = leaseSession }
            if (!active) detach() else {
                val next = try {
                    val selected = resolveSource()
                    if (selected?.identity !== source?.identity) {
                        detach()
                        source = selected
                    }
                    val current = source
                    if (current == null) State.Unavailable else {
                        if (subscription == null) subscription = current.observe {
                            synchronized(lock) {
                                if (source === current && session == leaseSession) requestLocked(force = true)
                            }
                        }
                        current.read()?.let { State.Reading(it, now()) } ?: State.Unavailable
                    }
                } catch (_: Exception) { State.Failed }
                val recipients = synchronized(lock) {
                    if (epoch == generation && !closed && leases.isNotEmpty()) {
                        state = next
                        lastCompletedAt = now()
                        leases.toList()
                    } else emptyList()
                }
                recipients.forEach { (owner, listener) -> deliver(owner, listener, next, epoch) }
            }
            synchronized(lock) {
                if (epoch == generation) { scheduled = false; return }
            }
        }
    }

    private fun deliver(owner: Any, listener: (State) -> Unit, value: State, epoch: Long) {
        callbacks.execute {
            val current = synchronized(lock) { !closed && generation == epoch && leases[owner] === listener }
            if (current) listener(value)
        }
    }

    private fun detach() {
        source = null // Old callbacks lose authority before unregistering.
        val old = subscription
        subscription = null
        try { old?.close() } catch (_: Exception) { /* Optional provider teardown cannot kill the feed. */ }
    }

    override fun close() = synchronized(lock) {
        closed = true
        leases.clear()
        session++
        generation++
        state = State.Unavailable
        lastCompletedAt = null
        scheduleLocked()
    }

    companion object {
        private const val REFRESH_MS = 10 * 60 * 1000L
        @Volatile private var instance: WeatherFeed? = null

        fun get(context: Context): WeatherFeed = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(context: Context): WeatherFeed {
            val main = Handler(Looper.getMainLooper())
            return WeatherFeed(
                resolveSource = { WeatherSources.active(context)?.let { source ->
                    Source(source, { source.read(context) }, { changed -> source.observe(context, changed) })
                } },
                worker = Executors.newSingleThreadExecutor { task -> Thread(task, "KotatsuWeatherFeed").apply { isDaemon = true } },
                callbacks = Executor { main.post(it) },
                now = SystemClock::elapsedRealtime,
            )
        }
    }
}

val WeatherFeed.State.weather: Weather? get() = (this as? WeatherFeed.State.Reading)?.weather
