package com.alexindigo.kotatsu.sync

/** CalendarProvider duration dialect, including legacy values received from sync adapters. */
internal fun calendarDurationMillis(value: String): Long {
    // calendarcommon2.Duration permits time units without T and supports weeks.
    if (value.isEmpty()) return 0
    val sign = if (value.startsWith('-')) -1L else 1L
    val body = value.removePrefix("-").removePrefix("+")
    require(body.startsWith('P')) { "Invalid calendar duration: $value" }
    var number = 0L
    val units = mutableMapOf<Char, Long>()
    for (c in body.drop(1)) when {
        c in '0'..'9' -> number = Math.addExact(Math.multiplyExact(number, 10), (c - '0').toLong())
        c == 'T' -> Unit
        c in "WDHMS" -> { units[c] = number; number = 0 }
        else -> error("Invalid calendar duration: $value")
    }
    val seconds = units.entries.fold(0L) { total, (unit, amount) ->
        val multiplier = when (unit) { 'W' -> 604800L; 'D' -> 86400L; 'H' -> 3600L; 'M' -> 60L; else -> 1L }
        Math.addExact(total, Math.multiplyExact(amount, multiplier))
    }
    return Math.multiplyExact(Math.multiplyExact(seconds, 1000), sign)
}
