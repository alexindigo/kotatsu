package com.gezimos.katapult.ui.settings

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable

/** Destination identities, not independent modal flags. Only the top page is rendered. */
class SettingsNavigation internal constructor(destinations: List<String>) {
    private val destinations = mutableStateListOf<String>().apply { addAll(destinations) }
    val current: String get() = destinations.last()
    val canGoBack: Boolean get() = destinations.size > 1
    fun push(destination: String) {
        // Repeated input or a repeated model event must not create duplicate page owners.
        if (destination !in destinations) destinations.add(destination)
    }
    fun pop() { if (canGoBack) destinations.removeAt(destinations.lastIndex) }
    fun contains(destination: String): Boolean = destination in destinations

    companion object {
        val Saver = listSaver<SettingsNavigation, String>(
            save = { it.destinations.toList() }, restore = { SettingsNavigation(it) })
    }
}

@Composable
fun rememberSettingsNavigation(root: String = "root"): SettingsNavigation =
    rememberSaveable(saver = SettingsNavigation.Saver) { SettingsNavigation(listOf(root)) }
