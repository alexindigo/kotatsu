package com.gezimos.katapult.ui.input

/** Presentation lifetime, independent of a retained draft and the framework's input connection.
 * All events run on the window's UI thread. Each window supplies its own scheduler and hide action.
 */
class EditorInputOwner(private val post: (() -> Unit) -> Unit, private val hide: () -> Unit) : AutoCloseable {
    class Registration internal constructor(
        internal val requestFocus: () -> Unit,
        internal val clearFocus: () -> Unit,
        internal val eligible: () -> Boolean,
    )

    private val registrations = mutableSetOf<Registration>()
    private var focused: Registration? = null
    private var suspended: Registration? = null
    private var generation = 0L
    private var pendingHide = false
    private var available = true
    private var closed = false
    val hasFocusedEditor: Boolean get() = focused != null

    fun register(requestFocus: () -> Unit, clearFocus: () -> Unit, eligible: () -> Boolean = { true }): Registration {
        check(!closed) { "Input window is closed" }
        return Registration(requestFocus, clearFocus, eligible).also { registrations += it }
    }

    fun focused(editor: Registration) {
        if (closed || !available || editor !in registrations) return
        generation++
        pendingHide = false
        suspended = null
        focused = editor
    }

    /** Blur is already executing a framework focus transfer: do not clear focus reentrantly. */
    fun blurred(editor: Registration) {
        if (focused !== editor) return
        focused = null
        scheduleHide(++generation)
    }

    fun unregister(editor: Registration) {
        registrations -= editor
        if (suspended === editor) suspended = null
        release(editor)
    }

    private fun release(editor: Registration) {
        if (focused !== editor) return
        focused = null
        val departingGeneration = ++generation
        // Invalidate first: a clear callback may synchronously focus a successor.
        editor.clearFocus()
        if (generation == departingGeneration && focused == null) scheduleHide(departingGeneration)
    }

    private fun scheduleHide(expected: Long) {
        pendingHide = true
        post {
            if (!closed && generation == expected && focused == null) {
                pendingHide = false
                hide()
            }
        }
    }

    /** Measurement may clamp a page. Retire outside measurement, only for the captured claim. */
    fun retireAfterTransition() {
        val editor = focused ?: return
        val expected = generation
        post { if (!closed && generation == expected && focused === editor) release(editor) }
    }

    /** A page's placement pass may exclude a composed editor without changing page number. */
    fun retireIfUnplaced(editor: Registration, isPlaced: () -> Boolean) {
        val expected = generation
        post {
            if (!closed && generation == expected && !isPlaced()) {
                if (suspended === editor) suspended = null
                release(editor)
            }
        }
    }

    fun stop() {
        if (closed || !available) return
        available = false
        suspended = focused
        focused?.let(::release)
    }

    fun resume() {
        if (closed || available) return
        available = true
        val editor = suspended
        suspended = null
        if (editor != null && editor in registrations && editor.eligible()) editor.requestFocus()
    }

    override fun close() {
        if (closed) return
        val editor = focused
        val needsHide = editor != null || pendingHide
        closed = true
        focused = null
        suspended = null
        pendingHide = false
        generation++
        registrations.clear()
        editor?.clearFocus()
        // Dismissal still owns its attached window now. Never post work past its lifetime.
        if (needsHide) hide()
    }
}
