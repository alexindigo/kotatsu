package com.alexindigo.kotatsu.weather

/** Small JSON codec kept independent of Android, so configuration contracts run on the JVM. */
object ConfigJson {
    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach {
            when (it) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> if (it < ' ') append("\\u%04x".format(it.code)) else append(it)
            }
        }
        append('"')
    }

    fun encode(config: WidgetConfig): String = """{"fahrenheit":${config.fahrenheit},"locations":[""" +
        config.locations.joinToString(",") {
            """{"label":${quote(it.label)},"place":${quote(it.place)},"latitude":${it.latitude},"longitude":${it.longitude},"start":${it.start},"end":${it.end}}"""
        } + "]}"

    fun decode(json: String): WidgetConfig {
        val root = Reader(json).read() as Map<*, *>
        return WidgetConfig((root["locations"] as List<*>).map {
            val row = it as Map<*, *>
            Location(row["label"] as String, row["place"] as String,
                (row["latitude"] as Number).toDouble(), (row["longitude"] as Number).toDouble(),
                (row["start"] as Number).toInt(), (row["end"] as Number).toInt())
        }, root["fahrenheit"] as Boolean)
    }

    private class Reader(private val text: String) {
        private var index = 0
        private fun space() { while (index < text.length && text[index].isWhitespace()) index++ }
        private fun take(c: Char) { space(); require(index < text.length && text[index++] == c) }
        fun read(): Any {
            val result = value()
            space()
            require(index == text.length)
            return result
        }
        private fun value(): Any {
            space()
            require(index < text.length)
            return when (text[index]) {
                '"' -> string()
                '{' -> {
                    take('{')
                    val map = linkedMapOf<String, Any>()
                    space()
                    if (text[index] != '}') while (true) {
                        space(); val key = string(); take(':'); map[key] = value(); space()
                        if (text[index] != ',') break
                        index++
                    }
                    take('}'); map
                }
                '[' -> {
                    take('[')
                    val list = mutableListOf<Any>()
                    space()
                    if (text[index] != ']') while (true) {
                        list += value(); space()
                        if (text[index] != ',') break
                        index++
                    }
                    take(']'); list
                }
                't' -> { require(text.startsWith("true", index)); index += 4; true }
                'f' -> { require(text.startsWith("false", index)); index += 5; false }
                else -> {
                    val start = index
                    while (index < text.length && text[index] in "-+0123456789.eE") index++
                    text.substring(start, index).toDouble()
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
