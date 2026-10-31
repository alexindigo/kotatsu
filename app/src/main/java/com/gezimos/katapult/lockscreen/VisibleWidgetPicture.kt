package com.gezimos.katapult.lockscreen

/** Receipt age, not forecast age. The clock must include sleep (elapsedRealtime on Android). */
internal fun reapplyVisiblePicture(receivedAt: Long?, now: Long): Boolean =
    receivedAt != null && now >= receivedAt && now - receivedAt < SETTLE_AFTER

private const val SETTLE_AFTER = 4 * 60 * 60 * 1000L

/** Provider-agnostic ownership of the last picture actually received during a visible interval. */
internal class VisibleWidgetPicture<T>(private val clock: () -> Long) {
    private var visible = false
    private var picture: T? = null
    private var receivedAt: Long? = null

    fun receive(value: T?) {
        if (value == null) { clear(); return }
        if (visible) { picture = value; receivedAt = clock() }
    }

    fun visibility(shown: Boolean): T? {
        if (visible == shown) return null
        visible = shown
        return picture.takeIf { shown && reapplyVisiblePicture(receivedAt, clock()) }
    }

    fun clear() { picture = null; receivedAt = null }
}
