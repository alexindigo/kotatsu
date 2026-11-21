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
        description = (if (event.location.isNotEmpty()) "Location: ${event.location}\n" else "") + event.description,
        start = toLocal(event.start, event.allDay), end = toLocal(event.end, event.allDay),
        allDay = event.allDay, preReminder = current?.preReminder ?: false,
    )

    fun toStandard(event: MuditaEvent, current: StandardEvent): StandardEvent = current.copy(
        title = event.title, description = body(event.description), start = toUtc(event.start, event.allDay),
        end = toUtc(event.end, event.allDay), allDay = event.allDay,
        timeZone = when { event.allDay -> "UTC"; current.allDay -> zone.id; else -> current.timeZone },
    )

    fun body(description: String): String = if (description.startsWith("Location: "))
        description.substringAfter('\n', "") else description

    fun muditaFingerprint(event: MuditaEvent): String = hash(event.title, body(event.description),
        event.start.toString(), event.end.toString(), event.allDay.toString())

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
}
