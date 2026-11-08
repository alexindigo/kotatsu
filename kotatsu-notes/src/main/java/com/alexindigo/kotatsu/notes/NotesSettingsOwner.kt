package com.alexindigo.kotatsu.notes

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

data class NotesSettingsSnapshot(
    val taskOn: Boolean = true,
    val quoteOn: Boolean = true,
    val taskNote: Long? = null,
    val quote: QuoteConfig = QuoteConfig(-1),
    val taskChoices: List<TaskListChoice>? = null,
    val quoteChoices: List<TaskListChoice>? = null,
    val loaded: Boolean = false,
    val saveFailed: Boolean = false,
)

/** One settings owner, on the same serialized lane as widget publication. No content is retained. */
class NotesSettingsOwner(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    var snapshot by mutableStateOf(readSettings())
        private set

    // Persisted configuration is available before asynchronous provider-title discovery.
    private fun readSettings() = NotesSettingsSnapshot(
        taskOn = WidgetKind.TASK.enabled(app), quoteOn = WidgetKind.QUOTE.enabled(app),
        taskNote = NotesStore(app).selected(), quote = QuoteStore(app).config() ?: QuoteConfig(-1),
    )

    fun refresh() = update {}
    fun enabled(kind: WidgetKind, enabled: Boolean) = update { kind.setEnabled(app, enabled) }
    fun taskNote(note: Long) = update {
        check(NotesStore(app).select(note))
        NotesRuntime.refresh(app)
    }
    fun quoteNote(note: Long) = quote { it.copy(noteId = note) }
    fun frequency(value: QuoteFrequency) = quote { it.copy(frequency = value) }
    fun order(value: QuoteOrder) = quote { it.copy(order = value) }

    private fun quote(change: (QuoteConfig) -> QuoteConfig) = update {
        val store = QuoteStore(app)
        val previous = store.config() ?: QuoteConfig(-1, seed = UUID.randomUUID().mostSignificantBits)
        store.save(change(previous))
        QuoteRuntime.refresh(app)
    }

    private fun update(write: () -> Unit) {
        val request = ++generation
        NotesRuntime.enqueue {
            val failed = runCatching(write).isFailure
            val next = readSettings().copy(
                taskChoices = runCatching { NotesRuntime.notes(app).choices() }.getOrNull(),
                quoteChoices = runCatching { QuoteRuntime.notes(app).textChoices() }.getOrNull(),
                loaded = true, saveFailed = failed,
            )
            main.post { if (generation == request) snapshot = next }
        }
    }
}
