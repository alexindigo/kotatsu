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

    fun encode(config: WidgetConfig): String = """{"version":1,"fahrenheit":${config.fahrenheit},"locations":[""" +
        config.locations.joinToString(",") {
            """{"id":${quote(it.id)},"label":${quote(it.label)},"place":${quote(it.place)},"latitude":${it.latitude},"longitude":${it.longitude},"start":${it.start},"end":${it.end}}"""
        } + "]}"

    fun decode(json: String): WidgetConfig {
        val root = JsonReader(json).read() as Map<*, *>
        val version = root["version"]
        require(version == null || version == 1.0) { "Unsupported configuration version" }
        require(root.keys == if (version == null) setOf("fahrenheit", "locations") else setOf("version", "fahrenheit", "locations"))
        val locations = (root["locations"] as List<*>).mapIndexed { index, value ->
            val row = value as Map<*, *>
            val fields = setOf("label", "place", "latitude", "longitude", "start", "end")
            require(row.keys == if (version == null) fields else fields + "id")
            // v0.2 had no row IDs. Stable IDs on read let concurrent editors address the same
            // legacy row; the first successful operation persists them with the version marker.
            val id = if (version == null) java.util.UUID.nameUUIDFromBytes(
                "$index|${row["label"]}|${row["place"]}|${row["latitude"]}|${row["longitude"]}"
                    .toByteArray(Charsets.UTF_8)).toString() else row["id"] as String
            fun hour(key: String): Int {
                val number = (row[key] as Number).toDouble()
                require(number == number.toInt().toDouble())
                return number.toInt()
            }
            Location(row["label"] as String, row["place"] as String,
                (row["latitude"] as Number).toDouble(), (row["longitude"] as Number).toDouble(),
                hour("start"), hour("end"), id)
        }
        require(locations.map { it.id }.distinct().size == locations.size) { "Duplicate place IDs" }
        return WidgetConfig(locations, root["fahrenheit"] as Boolean)
    }
}
