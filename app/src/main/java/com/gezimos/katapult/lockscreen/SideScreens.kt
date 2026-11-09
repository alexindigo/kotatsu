package com.gezimos.katapult.lockscreen

enum class SideLayout(val cells: Int) { FULL(1), SPLIT(2) }

/** Stable screen identity and stable cell identities. Full preserves the bottom cell for Split. */
data class SideScreen(val id: String = "side", val name: String = "", val layout: SideLayout = SideLayout.FULL,
                      val slots: List<Int?> = listOf(null, null)) {
    init { require(id.isNotBlank() && slots.size == 2) }
    val placements: List<Int> get() = slots.take(layout.cells).filterNotNull()
}

/** Shared rail geometry uses measured content and viewport, never a provider/package assumption. */
data class ListRail(val viewport: Int, val maximum: Int, val position: Int)
fun listRail(count: Int, first: Int, last: Int, firstTop: Int, firstHeight: Int,
             lastBottom: Int, lastHeight: Int, viewport: Int): ListRail? {
    if (count <= 0 || first !in 0 until count || last !in first until count || viewport <= 0 || firstHeight <= 0 || lastHeight <= 0) return null
    val extent = ((last - first + 1) * 1000f + firstTop * 1000f / firstHeight -
        (lastBottom - viewport).coerceAtLeast(0) * 1000f / lastHeight).toInt().coerceIn(1, count * 1000)
    val maximum = count * 1000 - extent
    if (maximum <= 0) return null
    // Endpoint identity comes from the measured native boundaries, not two independently
    // rounded fractional-row calculations (which can otherwise disagree by one unit).
    val position = when {
        first == 0 && firstTop >= 0 -> 0
        last == count - 1 && lastBottom <= viewport -> maximum
        else -> (first * 1000 - firstTop * 1000 / firstHeight).coerceIn(0, maximum)
    }
    return ListRail(extent, maximum, position)
}

/** Pure codec for a list of screens, even though v1 exposes just the first screen. */
object SideScreenList {
    private fun quote(text: String) = buildString {
        append('"')
        text.forEach { c -> when (c) {
            '"' -> append("\\\""); '\\' -> append("\\\\")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        } }
        append('"')
    }
    fun encode(screens: List<SideScreen>): String = screens.joinToString(",", "[", "]") { screen ->
        """{"id":${quote(screen.id)},"name":${quote(screen.name)},"layout":"${screen.layout.name}","slots":[${screen.slots.joinToString(",") { it?.toString() ?: "null" }}]}"""
    }
    fun decode(text: String): List<SideScreen> = runCatching {
        val used = mutableSetOf<Int>()
        (LockscreenWidgetList.Reader(text).read() as List<*>).map { entry ->
            val row = entry as Map<*, *>
            val slots = (row["slots"] as List<*>).map { value ->
                if (value == null) null else {
                    val n = value as Double
                    require(n >= 1 && n <= Int.MAX_VALUE && n == n.toInt().toDouble() && used.add(n.toInt()))
                    n.toInt()
                }
            }
            SideScreen(row["id"] as String, row["name"] as String, SideLayout.valueOf(row["layout"] as String), slots)
        }.also { require(it.map { screen -> screen.id }.distinct().size == it.size) }
    }.getOrDefault(emptyList())
}
