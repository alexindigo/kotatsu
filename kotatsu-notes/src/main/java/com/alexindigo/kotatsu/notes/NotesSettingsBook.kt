package com.alexindigo.kotatsu.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.res.stringResource
import com.gezimos.katapult.ui.settings.*

/** Presentation supplies only kit data and forwards intents to the settings owner. */
@Composable
fun NotesSettingsBook(owner: NotesSettingsOwner, kind: WidgetKind?, onBack: () -> Unit) {
    val state = owner.snapshot
    val navigation = rememberSettingsNavigation()
    val pages = rememberSaveableStateHolder()
    val back = {
        if (navigation.canGoBack) {
            pages.removeState(navigation.current)
            navigation.pop()
        } else onBack()
    }
    val taskName = stringResource(R.string.settings_task)
    val quoteName = stringResource(R.string.quote_label)
    val noteLabel = stringResource(R.string.settings_note)
    val none = stringResource(R.string.settings_choose)
    fun noteTitle(choices: List<TaskListChoice>?, selected: Long?, untitled: String) =
        choices?.firstOrNull { it.id == selected }?.title?.ifBlank { untitled } ?: none
    val taskNote = SettingsEntry.Open("task-note", noteLabel, "", { navigation.push("task-note") },
        actionLabel = noteTitle(state.taskChoices, state.taskNote, stringResource(R.string.untitled_list)))
    val quoteNote = SettingsEntry.Open("quote-note", noteLabel, "", { navigation.push("quote-note") },
        actionLabel = noteTitle(state.quoteChoices, state.quote.noteId, stringResource(R.string.untitled_note)))
    val frequency = SettingsEntry.Value("changes", stringResource(R.string.settings_changes), "",
        frequencyName(state.quote.frequency), QuoteFrequency.entries.map { SettingsChoice(it.name, frequencyName(it)) },
        { owner.frequency(QuoteFrequency.valueOf(it)) }, state.quote.frequency.name)
    val order = SettingsEntry.Value("order", stringResource(R.string.quote_order), "",
        orderName(state.quote.order), QuoteOrder.entries.map { SettingsChoice(it.name, orderName(it)) },
        { owner.order(QuoteOrder.valueOf(it)) }, state.quote.order.name)
    val groups = buildList {
        if (kind == null) add(SettingsGroup("widgets", stringResource(R.string.settings_widgets), listOf(
            SettingsEntry.Switch("task", taskName, stringResource(R.string.settings_task_help), state.taskOn,
                { owner.enabled(WidgetKind.TASK, it) }),
            SettingsEntry.Switch("quote", quoteName, stringResource(R.string.settings_quote_help), state.quoteOn,
                { owner.enabled(WidgetKind.QUOTE, it) }),
        )))
        if ((kind == null || kind == WidgetKind.TASK) && state.taskOn) add(SettingsGroup("task", taskName, listOf(taskNote)))
        if ((kind == null || kind == WidgetKind.QUOTE) && state.quoteOn) add(SettingsGroup("quote", quoteName,
            listOf(quoteNote, frequency, order)))
    }
    val title = when (kind) { WidgetKind.TASK -> taskName; WidgetKind.QUOTE -> quoteName; null -> stringResource(R.string.app_name) }
    SettingsBackOwner(back) {
      pages.SaveableStateProvider(navigation.current) {
        when (navigation.current) {
            "task-note", "quote-note" -> {
                val task = navigation.current == "task-note"
                val choices = if (task) state.taskChoices else state.quoteChoices
                val selected = if (task) state.taskNote else state.quote.noteId
                if (choices.isNullOrEmpty()) SettingsPage(noteLabel, back) {
                    SheetText(stringResource(if (!state.loaded) R.string.settings_loading
                        else if (choices == null) R.string.settings_notes_unavailable
                        else if (task) R.string.no_lists else R.string.no_text_notes))
                } else ChoicePage(noteLabel, choices.map {
                    SettingsChoice(it.id.toString(), it.title.ifBlank {
                        stringResource(if (task) R.string.untitled_list else R.string.untitled_note)
                    })
                }, selected?.toString().orEmpty(), { value ->
                    if (task) owner.taskNote(value.toLong()) else owner.quoteNote(value.toLong())
                }, back)
            }
            "changes", "order" -> {
                val entry = if (navigation.current == "changes") frequency else order
                ChoicePage(entry.title, entry.choices, entry.selected, entry.onSelect, back)
            }
            else -> if (state.saveFailed) SettingsPage(title, back) {
                SheetText(stringResource(R.string.save_failed))
            } else PaperIndexRenderer(SettingsCategory("notes", title, groups), back,
                onChoice = { navigation.push(it.id) })
        }
      }
    }
}

@Composable
private fun frequencyName(value: QuoteFrequency) = stringResource(when (value) {
    QuoteFrequency.DAILY -> R.string.quote_daily
    QuoteFrequency.HOURLY -> R.string.quote_hourly
    QuoteFrequency.WAKE -> R.string.quote_on_wake
})

@Composable
private fun orderName(value: QuoteOrder) = stringResource(when (value) {
    QuoteOrder.ROTATION -> R.string.quote_rotation
    QuoteOrder.SHUFFLE -> R.string.quote_shuffle
    QuoteOrder.RANDOM -> R.string.quote_random
})
