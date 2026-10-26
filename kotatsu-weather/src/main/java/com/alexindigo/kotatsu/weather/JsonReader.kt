package com.alexindigo.kotatsu.weather

/** The configuration codec's small JVM-only reader, shared with forecast ingestion. */
internal class JsonReader(private val text: String) {
    private var index = 0
    private fun space() { while (index < text.length && text[index] in " \t\r\n") index++ }
    private fun take(c: Char) { space(); require(index < text.length && text[index++] == c) }
    fun read(): Any? {
        val result = value()
        space()
        require(index == text.length) { "Trailing JSON content" }
        return result
    }
    private fun value(): Any? {
        space()
        require(index < text.length) { "Missing JSON value" }
        return when (text[index]) {
            '"' -> string()
            '{' -> {
                take('{')
                val map = linkedMapOf<String, Any?>()
                space()
                if (text[index] != '}') while (true) {
                    space(); val key = string(); require(key !in map); take(':'); map[key] = value(); space()
                    if (text[index] != ',') break
                    index++
                }
                take('}'); map
            }
            '[' -> {
                take('[')
                val list = mutableListOf<Any?>()
                space()
                if (text[index] != ']') while (true) {
                    list.add(value()); space()
                    if (text[index] != ',') break
                    index++
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
                require(NUMBER.matches(number)) { "Invalid JSON number" }
                number.toDouble().also { require(it.isFinite()) }
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
    private companion object { val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?") }
}
