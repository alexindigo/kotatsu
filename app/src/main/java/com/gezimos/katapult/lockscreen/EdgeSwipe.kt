package com.gezimos.katapult.lockscreen

import kotlin.math.abs
import kotlin.math.hypot

enum class ScreenEdge { LEFT, RIGHT }
enum class EdgeSwipeDecision { ACTION, DROP, REPLAY }
data class ReplayPoint(val x: Float, val y: Float)

/** Raw final displacement owns the decision; clipped replay coordinates never feed it. */
object EdgeSwipe {
    fun decide(edge: ScreenEdge, dx: Float, dy: Float, width: Int, slop: Float): EdgeSwipeDecision {
        if (!dx.isFinite() || !dy.isFinite() || width <= 0) return EdgeSwipeDecision.DROP
        if (hypot(dx, dy) <= slop || abs(dx) <= abs(dy)) return EdgeSwipeDecision.REPLAY
        val inward = if (edge == ScreenEdge.LEFT) dx > 0 else dx < 0
        return if (inward && abs(dx) >= width / 3f) EdgeSwipeDecision.ACTION else EdgeSwipeDecision.DROP
    }

    fun clamp(x: Float, y: Float, width: Int, height: Int): ReplayPoint {
        require(width > 0 && height > 0 && x.isFinite() && y.isFinite())
        return ReplayPoint(x.coerceIn(0f, width - 1f), y.coerceIn(0f, height - 1f))
    }

    fun duration(milliseconds: Long): Long = milliseconds.coerceIn(80L, 1500L)

    /** MOVE is only a hint. UP still makes the final decision using raw displacement. */
    fun previewStep(edge: ScreenEdge, dx: Float, dy: Float, width: Int, slop: Float): Int {
        if (!dx.isFinite() || !dy.isFinite() || width <= 0 || hypot(dx, dy) <= slop || abs(dx) <= abs(dy)) return 0
        val inward = if (edge == ScreenEdge.LEFT) dx else -dx
        return ((inward / (width / 3f)).coerceIn(0f, 1f) * 6).toInt()
    }

    fun closesPage(edge: ScreenEdge, dx: Float, dy: Float, width: Int, slop: Float): Boolean =
        decide(if (edge == ScreenEdge.LEFT) ScreenEdge.RIGHT else ScreenEdge.LEFT, dx, dy, width, slop) == EdgeSwipeDecision.ACTION

    /** Input ownership starts at slop, not at the final action distance. */
    fun capturesPageClose(edge: ScreenEdge, dx: Float, dy: Float, slop: Float): Boolean =
        dx.isFinite() && dy.isFinite() && hypot(dx, dy) > slop && abs(dx) > abs(dy) &&
            if (edge == ScreenEdge.LEFT) dx < 0 else dx > 0
}

/** A nonempty platform snapshot and a removal event must agree with local detach. */
internal object ReplayBarrier {
    fun ready(ids: Set<Int>, titles: Set<String>, removed: Boolean, detached: Boolean,
              windows: List<Pair<Int, String?>>): Boolean =
        ids.isNotEmpty() && removed && detached && windows.isNotEmpty() &&
            windows.none { it.first in ids || it.second in titles }
}
