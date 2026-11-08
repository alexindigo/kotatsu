package com.alexindigo.kotatsu.notes

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import java.util.UUID

/** Per-widget settings. Choices apply immediately; their page returns to this widget's index. */
class QuoteConfigureActivity : Activity() {
    private var id = AppWidgetManager.INVALID_APPWIDGET_ID
    private var generation = 0
    private var childPage = false
    private var config: QuoteConfig? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setResult(RESULT_CANCELED)
        id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (android.os.Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
    }
    override fun onResume() { super.onResume(); index() }
    override fun onPause() { generation++; super.onPause() }
    // API31/32 hardware Back; API33+ gestures use the registered platform callback above.
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun onBackPressed() = back()
    private fun back() { if (childPage) index() else finish() }

    private fun index() {
        if (id !in QuoteRuntime.ids(this)) { finish(); return }
        childPage = false
        val request = ++generation
        NotesRuntime.enqueue {
            val selected = QuoteStore(this).config(id)
            val note = selected?.let { runCatching { QuoteRuntime.notes(this).readText(it.noteId) }.getOrNull() }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != request) return@runOnUiThread
                config = selected
                val rows = mutableListOf(NotesPageEntry(note?.title?.ifBlank { getString(R.string.untitled_note) }
                    ?: getString(R.string.choose_note), ::chooseNote))
                if (note == null || parseQuotes(note.content).isEmpty()) rows += NotesPageEntry(getString(R.string.choose_quotes))
                if (selected != null) {
                    rows += NotesPageEntry(getString(R.string.quote_frequency_value, frequencyName(selected.frequency)), ::chooseFrequency)
                    rows += NotesPageEntry(getString(R.string.quote_order_value, orderName(selected.order)), ::chooseOrder)
                    rows += NotesPageEntry(getString(R.string.quote_done), {
                        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish()
                    })
                }
                notesPage(getString(R.string.quote_label), rows, ::back)
            }
        }
    }

    private fun chooseNote() {
        childPage = true
        val request = ++generation
        NotesRuntime.enqueue {
            val choices = runCatching { QuoteRuntime.notes(this).textChoices() }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != request) return@runOnUiThread
                notesPage(getString(R.string.choose_note), if (choices.isNullOrEmpty()) listOf(NotesPageEntry(getString(R.string.no_text_notes)))
                    else choices.map { choice -> NotesPageEntry(choice.title.ifBlank { getString(R.string.untitled_note) }, {
                        save(config?.copy(noteId = choice.id) ?: QuoteConfig(choice.id, seed = UUID.randomUUID().mostSignificantBits))
                    }, config?.noteId == choice.id) }, ::back)
            }
        }
    }
    private fun chooseFrequency() {
        childPage = true
        notesPage(getString(R.string.quote_frequency), QuoteFrequency.entries.map { frequency ->
            NotesPageEntry(frequencyName(frequency), { config?.let { save(it.copy(frequency = frequency)) } }, config?.frequency == frequency)
        }, ::back)
    }
    private fun chooseOrder() {
        childPage = true
        notesPage(getString(R.string.quote_order), QuoteOrder.entries.map { order ->
            NotesPageEntry(orderName(order), { config?.let { save(it.copy(order = order)) } }, config?.order == order)
        }, ::back)
    }
    private fun save(next: QuoteConfig) {
        val request = ++generation
        NotesRuntime.enqueue {
            if (id !in QuoteRuntime.ids(this)) { runOnUiThread { finish() }; return@enqueue }
            QuoteStore(this).save(id, next)
            QuoteRuntime.refresh(this, intArrayOf(id))
            runOnUiThread { if (!isFinishing && !isDestroyed && generation == request) index() }
        }
    }
    private fun frequencyName(value: QuoteFrequency) = getString(when (value) {
        QuoteFrequency.DAILY -> R.string.quote_daily
        QuoteFrequency.HOURLY -> R.string.quote_hourly
        QuoteFrequency.WAKE -> R.string.quote_on_wake
    })
    private fun orderName(value: QuoteOrder) = getString(when (value) {
        QuoteOrder.ROTATION -> R.string.quote_rotation
        QuoteOrder.SHUFFLE -> R.string.quote_shuffle
        QuoteOrder.RANDOM -> R.string.quote_random
    })
}
