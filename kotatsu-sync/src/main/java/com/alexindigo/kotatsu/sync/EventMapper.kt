package com.alexindigo.kotatsu.sync

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** CalendarContract all-day dates are UTC dates, Mudita dates use the system zone. */
class EventMapper(private val zone: ZoneId = ZoneId.systemDefault()) {
    fun toMudita(event: StandardEvent, current: MuditaEvent? = null): MuditaEvent = MuditaEvent(
        id = current?.id ?: 0, title = event.title,
        description = (if (event.location.isNotEmpty()) "Location: ${oneLine(event.location)}$LOCATION_MARKER\n" else "") + event.description,
        start = toLocal(event.start, event.allDay), end = toLocal(event.end, event.allDay),
        allDay = event.allDay, preReminder = current?.preReminder ?: false,
    )

    fun toStandard(event: MuditaEvent, current: StandardEvent, generatedLocation: Boolean = false): StandardEvent = current.copy(
        title = event.title, description = body(event.description, generatedLocation), start = toUtc(event.start, event.allDay),
        end = toUtc(event.end, event.allDay), allDay = event.allDay,
        timeZone = when { event.allDay -> "UTC"; current.allDay -> zone.id; else -> current.timeZone },
    )

    fun body(description: String, generatedLocation: Boolean): String = if (generatedLocation &&
        description.substringBefore('\n').endsWith(LOCATION_MARKER))
        description.substringAfter('\n', "") else description

    fun muditaFingerprint(event: MuditaEvent, generatedLocation: Boolean = false): String = hash(event.title, body(event.description, generatedLocation),
        event.start.toString(), event.end.toString(), event.allDay.toString())

    private fun oneLine(value: String): String = value.replace(Regex("\\r\\n|[\\r\\n\\u000B\\u000C\\u0085\\u2028\\u2029]"), " ")

    fun standardFingerprint(event: StandardEvent): String = hash(event.title, event.description, event.location,
        event.start.toString(), event.end.toString(), event.allDay.toString())

    private fun hash(vararg fields: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (field in fields) {
            val bytes = field.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array()); digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun toLocal(time: Long, allDay: Boolean): Long = if (!allDay) time else
        Instant.ofEpochMilli(time).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    private fun toUtc(time: Long, allDay: Boolean): Long = if (!allDay) time else
        Instant.ofEpochMilli(time).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    companion object {
        // Invisible format characters keep the agreed visible first line while identifying bridge text.
        const val LOCATION_MARKER = "\u2063\u2060\u2063"
    }
}
