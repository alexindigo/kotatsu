package com.alexindigo.kotatsu.weather

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The device's wall zone and clock preference, independent of place coordinates and locale defaults. */
fun weatherTime(instant: Instant, zone: ZoneId, locale: Locale, use24Hour: Boolean): String =
    DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale).format(instant.atZone(zone))
