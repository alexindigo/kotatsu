package com.alexindigo.kotatsu.notes

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View

/** A widget-bound titles-only picker. Selection applies and returns; Back leaves it unchanged. */
class ConfigureActivity : Activity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var generation = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setResult(RESULT_CANCELED)
        window.setBackgroundDrawable(ColorDrawable(Color.WHITE))
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
    }

    override fun onResume() {
        super.onResume()
        if (widgetId in QuoteRuntime.ids(this)) {
            startActivity(Intent(this, QuoteConfigureActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
            finish(); return
        }
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            when (val target = settingsTarget(NotesRuntime.ids(this) + QuoteRuntime.ids(this))) {
                SettingsTarget.NoWidget -> showPage(R.string.app_name, listOf(getString(R.string.add_widget_help) to null))
                is SettingsTarget.One -> { openWidget(target.widgetId) }
                is SettingsTarget.Choose -> chooseWidget(target.widgetIds)
            }
        } else loadChoices()
    }

    private fun chooseWidget(ids: List<Int>) {
        val request = ++generation
        NotesRuntime.enqueue {
            val store = NotesStore(this)
            val titles = runCatching { NotesRuntime.notes(this).choices()?.associate { it.id to it.title } }.getOrNull()
            val quoteTitles = runCatching { QuoteRuntime.notes(this).textChoices()?.associate { it.id to it.title } }.getOrNull()
            val quotes = QuoteRuntime.ids(this).toSet()
            val names = ids.map { id ->
                if (id in quotes) getString(R.string.quote_widget_name,
                    quoteTitles?.get(QuoteStore(this).config(id)?.noteId)?.ifBlank { getString(R.string.untitled_note) }
                        ?: getString(R.string.quote_label))
                else titles?.get(store.selected(id))?.ifBlank { getString(R.string.untitled_list) } ?: getString(R.string.choose_list)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != request) return@runOnUiThread
                showPage(R.string.choose_widget, ids.mapIndexed { index, id -> names[index] to {
                    openWidget(id)
                } })
            }
        }
    }

    private fun openWidget(id: Int) {
        widgetId = id
        if (id in QuoteRuntime.ids(this)) {
            startActivity(Intent(this, QuoteConfigureActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            finish()
        } else loadChoices()
    }

    private fun loadChoices() {
        if (widgetId !in NotesRuntime.ids(this)) { finish(); return }
        val request = ++generation
        NotesRuntime.enqueue {
            val choices = runCatching { NotesRuntime.notes(this).choices() }.getOrNull()
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != request) return@runOnUiThread
                if (choices.isNullOrEmpty()) {
                    showPage(R.string.choose_list, listOf(getString(R.string.no_lists) to null))
                } else showPage(R.string.choose_list, choices.map { choice ->
                    choice.title.ifBlank { getString(R.string.untitled_list) } to { select(choice.id) }
                })
            }
        }
    }

    private fun select(noteId: Long) {
        val id = widgetId
        val request = ++generation
        NotesRuntime.enqueue {
            if (id !in NotesRuntime.ids(this)) { runOnUiThread { finish() }; return@enqueue }
            val saved = NotesStore(this).select(id, noteId)
            if (saved) NotesRuntime.refresh(this, intArrayOf(id))
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != request) return@runOnUiThread
                if (saved) {
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                    finish()
                } else showPage(R.string.choose_list, listOf(getString(R.string.save_failed) to null))
            }
        }
    }

    private fun showPage(title: Int, entries: List<Pair<String, (() -> Unit)?>>) {
        notesPage(getString(title), entries.map { NotesPageEntry(it.first, it.second) })
    }
    override fun onPause() { generation++; super.onPause() }
}
