package com.gezimos.katapult.lockscreen

enum class LockscreenWidgetSize(val heightDp: Int) { S(64), M(112), L(160) }
data class LockscreenWidget(val id: Int, val size: LockscreenWidgetSize)

// Measured on the staging Kompakt, 480x800 pixels, 2026-10-14.
const val WIDGETS_MEASURED_DATE_BOTTOM_PX = 268
const val WIDGETS_MEASURED_PADLOCK_TOP_PX = 687

fun lockscreenBoxLayout(sizesDp: List<Int>, availableDp: Int): List<Int>? {
    if (sizesDp.any { it <= 0 }) return null
    val total = sizesDp.sumOf { it.toLong() } + (sizesDp.size - 1).coerceAtLeast(0) * 8L + 16
    return sizesDp.takeIf { total <= availableDp }
}

fun defaultLockscreenWidgetSize(minHeightDp: Float): LockscreenWidgetSize =
    LockscreenWidgetSize.entries.firstOrNull { it.heightDp >= minHeightDp } ?: LockscreenWidgetSize.L

/** A widget's name in the add list; the app is appended only when the widget's name doesn't already contain it. */
fun providerLabel(widget: String, app: String): String =
    if (app.isBlank() || widget.contains(app, ignoreCase = true)) widget else "$widget ($app)"

/** Device-local list codec; malformed entries never become host IDs. No Android dependency. */
object LockscreenWidgetList {
    fun encode(widgets: List<LockscreenWidget>): String = widgets.joinToString(",", "[", "]") {
        """{"id":${it.id},"size":"${it.size.name}"}"""
    }

    fun decode(json: String): List<LockscreenWidget> = runCatching {
        val rows = Reader(json).read() as List<*>
        rows.mapNotNull { entry ->
            val row = entry as? Map<*, *> ?: return@mapNotNull null
            val number = row["id"] as? Double ?: return@mapNotNull null
            if (number < 1 || number > Int.MAX_VALUE || number != number.toInt().toDouble()) return@mapNotNull null
            val size = LockscreenWidgetSize.entries.firstOrNull { it.name == row["size"] } ?: return@mapNotNull null
            LockscreenWidget(number.toInt(), size)
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    private class Reader(private val text: String) {
        private var index = 0
        private fun space() { while (index < text.length && text[index].isWhitespace()) index++ }
        private fun take(c: Char) { space(); require(index < text.length && text[index++] == c) }
        fun read(): Any? { val result = value(); space(); require(index == text.length); return result }
        private fun value(): Any? {
            space(); require(index < text.length)
            return when (text[index]) {
                '"' -> string()
                '{' -> {
                    take('{'); val map = linkedMapOf<String, Any?>(); space()
                    if (text[index] != '}') while (true) {
                        val key = string(); take(':'); map[key] = value(); space()
                        if (text[index] != ',') break
                        index++
                    }
                    take('}'); map
                }
                '[' -> {
                    take('['); val list = mutableListOf<Any?>(); space()
                    if (text[index] != ']') while (true) {
                        list.add(value()); space(); if (text[index] != ',') break; index++
                    }
                    take(']'); list
                }
                't' -> { require(text.startsWith("true", index)); index += 4; true }
                'f' -> { require(text.startsWith("false", index)); index += 5; false }
                'n' -> { require(text.startsWith("null", index)); index += 4; null }
                else -> {
                    val start = index
                    while (index < text.length && text[index] in "-+0123456789.eE") index++
                    val number = text.substring(start, index)
                    require(number.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                    number.toDouble()
                }
            }
        }
        private fun string(): String {
            take('"')
            return buildString {
                while (true) {
                    val c = text[index++]
                    if (c == '"') break
                    if (c != '\\') { require(c >= ' '); append(c); continue }
                    append(when (val escaped = text[index++]) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> text.substring(index, index + 4).toInt(16).toChar().also { index += 4 }
                        else -> error("Invalid JSON escape")
                    })
                }
            }
        }
    }
}
