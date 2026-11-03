package com.gezimos.katapult.lockscreen

/** A root reference is retained, never an eligibility result. Every use requires a fresh query. */
internal class LockscreenRootInspection<R>(
    private val copy: (R) -> R,
    private val refresh: (R) -> Boolean,
    private val inspect: (R) -> Observation,
    private val release: (R) -> Unit,
) : AutoCloseable {
    data class Observation(val systemUi: Boolean, val panel: Boolean, val pin: Boolean) {
        val bare get() = systemUi && panel && !pin
    }
    data class Window<R>(val root: R?, val ownOverlay: Boolean, val active: Boolean,
                         val focused: Boolean, val application: Boolean = false)
    private var retained: R? = null

    fun covered(windows: List<Window<R>>, pinFocused: Boolean): Boolean = try {
        if (pinFocused) reject() else {
            val index = LockscreenVisibility.inspectionWindow(windows.map {
                LockscreenVisibility.InspectionWindow(it.ownOverlay, it.active, it.focused)
            })
            val current = index?.let(windows::get)
            val observed = current?.root?.let(inspect)
            when {
                observed?.bare == true -> {
                    val next = copy(requireNotNull(current?.root))
                    close(); retained = next
                    false
                }
                observed?.pin == true || observed?.systemUi == false -> reject()
                // An opaque own page can remove SystemUI from getWindows(). Only that
                // ownership case may query the previously identified SystemUI root.
                windows.none { it.ownOverlay && it.focused } -> reject()
                windows.any { !it.ownOverlay && (it.active || it.focused || it.application) } -> reject()
                else -> {
                    val root = retained
                    if (root != null && refresh(root) && inspect(root).bare) false else reject()
                }
            }
        }
    } catch (_: RuntimeException) { reject() }

    private fun reject(): Boolean { close(); return true }

    override fun close() { val old = retained; retained = null; old?.let(release) }
}
