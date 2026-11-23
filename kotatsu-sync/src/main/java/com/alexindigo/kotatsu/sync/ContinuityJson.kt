package com.alexindigo.kotatsu.sync

import org.json.JSONArray
import org.json.JSONObject

internal object ContinuityJson {
    fun encode(value: CalendarContinuity) = JSONObject().apply {
        value.phone?.let { p -> put("phone", JSONObject().put("count", p.count).put("max", p.maxId)
            .put("firstRun", p.firstPassDone).put("keys", JSONArray(p.keys.toList()))) }
        put("standard", JSONArray(value.standard.values.map { s -> JSONObject().apply {
            put("id", s.calendar.id); put("type", s.calendar.accountType); put("syncing", s.calendar.syncing)
            put("name", s.calendar.accountName); put("label", s.calendar.label); put("collection", s.calendar.collectionId)
            put("keys", JSONArray(s.keys.map { JSONObject().put("event", it.eventId).put("instance", it.instanceStart) }))
            put("syncIds", JSONObject(s.syncIds.mapKeys { it.key.toString() }))
        } }))
        put("holds", JSONArray(value.holds.map { h -> JSONObject().apply {
            put("id", h.id); put("direction", h.direction.name); put("resolution", h.resolution?.name)
            put("calendars", JSONObject(h.calendars.mapKeys { it.key.toString() }))
            put("members", JSONArray(h.members.map { m -> JSONObject().put("pair", IntentJson.mapping(m.pair))
                .put("phone", IntentJson.phone(m.phone)).put("standard", IntentJson.standard(m.standard)) }))
        } }))
        put("phoneOnly", JSONArray(value.phoneOnly.toList()))
    }.toString()

    fun decode(value: String): CalendarContinuity = with(JSONObject(value)) {
        val phone = optJSONObject("phone")?.let { MuditaBaseline(it.getInt("count"), it.getLong("max"),
            it.getBoolean("firstRun"), it.getJSONArray("keys").longs()) }
        val standard = getJSONArray("standard").objects().associate { s ->
            val id = s.getLong("id")
            val ids = s.getJSONObject("syncIds")
            id to CalendarBaseline(SyncCalendar(id, s.getString("type"), s.getBoolean("syncing"),
                s.getString("name"), s.getString("label"), s.optString("collection").takeIf { s.has("collection") }),
                s.getJSONArray("keys").objects().map { EventKey(it.getLong("event"),
                    if (it.isNull("instance")) null else it.getLong("instance")) }.toSet(),
                ids.keys().asSequence().associate { it.toLong() to ids.getString(it) })
        }
        val holds = getJSONArray("holds").objects().map { h ->
            val labels = h.getJSONObject("calendars")
            CalendarHold(h.getString("id"), LossDirection.valueOf(h.getString("direction")),
                h.getJSONArray("members").objects().map { m -> LossMember(IntentJson.mapping(m.getJSONObject("pair")),
                    IntentJson.phone(m.optJSONObject("phone")), IntentJson.standard(m.optJSONObject("standard"))) },
                labels.keys().asSequence().associate { it.toLong() to labels.getString(it) },
                if (h.has("resolution")) LossResolution.valueOf(h.getString("resolution")) else null)
        }
        CalendarContinuity(phone, standard, holds, getJSONArray("phoneOnly").longs())
    }

    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun JSONArray.longs() = (0 until length()).map(::getLong).toSet()
}
