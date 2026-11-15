package com.gezimos.katapult.util

import com.gezimos.katapult.ui.SwipeDirection

/** Home's directions are independent of the All Apps paging direction. */
enum class HomeAppSwipe(val id: String, private val up: Boolean, private val left: Boolean) {
    NONE("none", false, false), UP("up", true, false), LEFT("left", false, true),
    UP_AND_LEFT("up_left", true, true);

    fun opens(direction: SwipeDirection?): Boolean = when (direction) {
        SwipeDirection.UP -> up
        SwipeDirection.LEFT -> left
        else -> false
    }

    companion object {
        val DEFAULT = UP_AND_LEFT
        fun fromId(id: String?) = entries.singleOrNull { it.id == id }

        fun fromBackup(id: String?, enabled: Boolean? = null, vertical: Boolean? = null): HomeAppSwipe =
            resolve(id, legacy = true, enabled = enabled, vertical = vertical)

        fun resolve(id: String?, legacy: Boolean, enabled: Boolean? = null, vertical: Boolean? = null): HomeAppSwipe =
            fromId(id) ?: if (!legacy) DEFAULT else when {
                enabled == false -> NONE
                vertical == true -> UP
                else -> LEFT // The old absent-key defaults were enabled, horizontal.
            }
    }
}
