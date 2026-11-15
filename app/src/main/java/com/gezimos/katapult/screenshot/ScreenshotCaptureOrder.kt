package com.gezimos.katapult.screenshot

/** Main-thread ownership of capture lifetime and result order, independent of save completion. */
internal class ScreenshotCaptureOrder {
    class Token internal constructor(internal val generation: Long)

    private var generation = 0L
    private var latest: Token? = null

    fun begin(): Token = Token(generation).also { latest = it }
    fun isActive(token: Token): Boolean = token.generation == generation
    fun isLatest(token: Token): Boolean = isActive(token) && token === latest
    fun clear() { generation++; latest = null }
}
