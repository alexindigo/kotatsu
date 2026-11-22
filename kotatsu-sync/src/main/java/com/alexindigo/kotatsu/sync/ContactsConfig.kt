package com.alexindigo.kotatsu.sync

import android.content.Context
import org.json.JSONObject

class PreferenceContactsConfig(context: Context) {
    private val prefs = context.getSharedPreferences("contacts-sync", Context.MODE_PRIVATE)
    fun read() = ContactsSettings(read("personal"), read("family"))
    private fun read(key: String): ContactAccount? = prefs.getString(key, null)?.let {
        val j = JSONObject(it)
        ContactAccount(j.getString("name"), j.getString("type"), if (j.isNull("dataSet")) null else j.getString("dataSet"))
    }
    fun write(settings: ContactsSettings) {
        fun encode(a: ContactAccount?) = a?.let { JSONObject().apply {
            put("name", it.name); put("type", it.type); put("dataSet", it.dataSet)
        }.toString() }
        check(prefs.edit().putString("personal", encode(settings.personal)).putString("family", encode(settings.family)).commit()) {
            "Could not save contacts configuration"
        }
    }
}
