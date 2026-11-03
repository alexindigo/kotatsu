package com.gezimos.katapult.lockscreen

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.MediaStore

/** Stable IDs, never ordinals: another action can be added without migrating edge selections. */
enum class EdgeAction(val id: String) {
    CAMERA("camera"), NOTHING("nothing");

    companion object {
        fun fromId(id: String?): EdgeAction? = entries.firstOrNull { it.id == id }
    }
}

class EdgeActionStore(private val prefs: SharedPreferences) {
    var enabled: Boolean
        get() = synchronized(prefs) { prefs.all[ENABLED] as? Boolean ?: false }
        set(value) { synchronized(prefs) { prefs.edit().putBoolean(ENABLED, value).apply() } }

    fun action(edge: ScreenEdge): EdgeAction = synchronized(prefs) {
        EdgeAction.fromId(prefs.all[key(edge)] as? String) ?: defaultAction(edge)
    }

    fun setAction(edge: ScreenEdge, action: EdgeAction) {
        synchronized(prefs) { prefs.edit().putString(key(edge), action.id).apply() }
    }

    companion object {
        const val ENABLED = "lockscreen_edge_swipes"
        const val LEFT = "lockscreen_left_edge_action"
        const val RIGHT = "lockscreen_right_edge_action"
        fun key(edge: ScreenEdge) = if (edge == ScreenEdge.LEFT) LEFT else RIGHT
        fun defaultAction(edge: ScreenEdge) = if (edge == ScreenEdge.LEFT) EdgeAction.NOTHING else EdgeAction.CAMERA
        fun validImport(key: String, value: Any?): Boolean = when (key) {
            ENABLED -> value is Boolean
            LEFT, RIGHT -> value is String && EdgeAction.fromId(value) != null
            else -> true
        }
    }
}

object SecureCamera {
    fun intent() = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)

    fun available(context: Context): Boolean = intent().resolveActivity(context.packageManager) != null

    fun open(context: Context) {
        try { context.startActivity(intent()) }
        catch (error: RuntimeException) { android.util.Log.w("EdgeActions", "Secure camera unavailable", error) }
    }
}
