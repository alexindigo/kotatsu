package com.gezimos.katapult.util

/** Android-free decisions; the ViewModel owns the working AppModel list. */
data class ReorderBaseline(
    val effectiveKeys: List<String>,
    val persistedKeys: List<String>?,
) {
    fun isDirty(workingKeys: List<String>): Boolean = workingKeys != effectiveKeys

    fun resolve(choice: ReorderChoice, workingKeys: List<String>): ReorderResolution? =
        when (choice) {
            ReorderChoice.CANCEL -> null
            ReorderChoice.DISCARD -> ReorderResolution(effectiveKeys, persist = false)
            ReorderChoice.SAVE -> ReorderResolution(workingKeys.toList(), persist = isDirty(workingKeys))
        }
}

enum class ReorderChoice { SAVE, DISCARD, CANCEL }

data class ReorderResolution(val keys: List<String>, val persist: Boolean)
