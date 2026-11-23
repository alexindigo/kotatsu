package com.alexindigo.kotatsu.sync

import android.content.Context
import org.json.JSONObject

class PreferenceContactsConfig(context: Context) {
    private val prefs = context.getSharedPreferences("contacts-sync", Context.MODE_PRIVATE)
    fun read(): ContactsSettings {
        val target = read("target")
        return ContactsSettings(target, readTargetMode(prefs.getString("target_mode", null), target != null))
    }
    private fun read(key: String): ContactAccount? = prefs.getString(key, null)?.let {
        val j = JSONObject(it)
        ContactAccount(j.getString("name"), j.getString("type"), if (j.isNull("dataSet")) null else j.getString("dataSet"))
    }
    fun write(settings: ContactsSettings) {
        fun encode(a: ContactAccount?) = a?.let { JSONObject().apply {
            put("name", it.name); put("type", it.type); put("dataSet", it.dataSet)
        }.toString() }
        check(prefs.edit().putString("target_mode", settings.mode.name)
            .putString("target", encode(settings.target.takeIf { settings.mode == TargetMode.EXPLICIT })).commit()) {
            "Could not save contacts configuration"
        }
    }
}
