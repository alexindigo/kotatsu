package com.gezimos.katapult.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings reads titles only; opening the lock-screen page never enumerates notes. */
class TaskListSettings(private val notes: NotesPort) {
    var choices by mutableStateOf<List<TaskListChoice>?>(null)
        private set

    suspend fun refresh() {
        choices = withContext(Dispatchers.IO) {
            try { notes.choices() } catch (_: RuntimeException) { null }
        }
    }
}
