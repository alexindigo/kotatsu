package com.gezimos.katapult.util

/** Home's directions are independent of the All Apps paging direction. */
enum class HomeAppSwipe(val id: String, private val up: Boolean, private val left: Boolean) {
    NONE("none", false, false), UP("up", true, false), LEFT("left", false, true),
    UP_AND_LEFT("up_left", true, true);

    fun opens(dx: Float, dy: Float): Boolean = up && dy < -DISTANCE || left && dx < -DISTANCE

    companion object {
        val DEFAULT = UP_AND_LEFT
        private const val DISTANCE = 100f
        fun fromId(id: String?) = entries.singleOrNull { it.id == id }

        fun resolve(id: String?, legacy: Boolean, enabled: Boolean? = null, vertical: Boolean? = null): HomeAppSwipe =
            fromId(id) ?: if (!legacy) DEFAULT else when {
                enabled == false -> NONE
                vertical == true -> UP
                else -> LEFT // The old absent-key defaults were enabled, horizontal.
            }
    }
}
