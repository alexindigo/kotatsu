package com.alexindigo.kotatsu.sync

import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.gezimos.katapult.ui.settings.*
import java.util.Date

/** Kit composition and navigation only; the owner handles storage and Android. */
@Composable
fun SyncSettingsBook(owner: SyncSettingsOwner, onBack: () -> Unit, onAccess: (Boolean) -> Unit, onSetUp: (() -> Unit)?) {
    val state = owner.snapshot
    val navigation = rememberSettingsNavigation()
    val pages = rememberSaveableStateHolder()
    val back = {
        if (owner.confirmation != null) owner.cancelMove()
        else if (navigation.canGoBack) { pages.removeState(navigation.current); navigation.pop() }
        else onBack()
    }
    val newEvents = SettingsEntry.Value("new-events", stringResource(R.string.new_events), "",
        state.calendars.firstOrNull { it.id == state.settings.newEventCalendar }?.name.orEmpty(),
        state.calendars.filter { it.id in state.settings.calendars }.map { SettingsChoice(it.id.toString(), it.name) },
        { owner.newEvents(it.toLong()) }, state.settings.newEventCalendar?.toString().orEmpty(), appBuiltChoices = true)
    val horizon = SettingsEntry.Value("look-ahead", stringResource(R.string.look_ahead), "",
        pluralStringResource(R.plurals.weeks, state.settings.horizonWeeks, state.settings.horizonWeeks),
        (1..26).map { SettingsChoice(it.toString(), it.toString()) },
        { owner.weeks(it.toInt()) }, state.settings.horizonWeeks.toString())
    val contacts = SettingsEntry.Value("contacts", stringResource(R.string.contacts_go_to), "", state.contacts.target?.name.orEmpty(),
        state.books.map { book -> SettingsChoice(book.choiceId(), book.name) },
        { id -> owner.contacts(state.books.first { it.choiceId() == id }) }, state.contacts.target?.choiceId().orEmpty(), appBuiltChoices = true)
    val context = LocalContext.current
    val status = state.status.stopReason?.let { stringResource(R.string.stopped, it) }
        ?: state.status.lastPassAt?.let { pluralStringResource(R.plurals.sync_status, state.status.changes,
            DateFormat.getTimeFormat(context).format(Date(it)), state.status.changes) }.orEmpty()
    val noDav = state.loaded && state.calendarAccess && state.contactsAccess && state.calendars.isEmpty() && state.books.isEmpty()
    val shownColumn = rememberControlColumn(stringResource(R.string.shown))
    SettingsBackOwner(back) {
        PaperSheetHost(owner.confirmation != null, background = {
            pages.SaveableStateProvider(navigation.current) {
                when (navigation.current) {
                    "new-events" -> ChoiceSurface(newEvents, back)
                    "look-ahead" -> ChoiceSurface(horizon, back)
                    "contacts" -> ChoiceSurface(contacts, back)
                    else -> SettingsPage(stringResource(R.string.app_name), back) {
                        if (!state.calendarAccess) Entry(SettingsEntry.Open("calendar-access", stringResource(R.string.calendar_access), "",
                            { onAccess(true) }, actionLabel = stringResource(R.string.allow)))
                        if (!state.contactsAccess) Entry(SettingsEntry.Open("contacts-access", stringResource(R.string.contacts_access), "",
                            { onAccess(false) }, actionLabel = stringResource(R.string.allow)))
                        if (noDav) {
                            if (onSetUp != null) Entry(SettingsEntry.Open("davx5", stringResource(R.string.davx5), "", onSetUp,
                                actionLabel = stringResource(R.string.set_up)))
                            else Entry(SettingsEntry.Metadata("davx5", stringResource(R.string.davx5), stringResource(R.string.unavailable)))
                        }
                        else {
                            if (state.calendars.isNotEmpty()) Column {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                    ColumnLabel(shownColumn)
                                }
                                state.calendars.forEach { calendar ->
                                    val checked = calendar.id in state.settings.calendars
                                    PaperCheckboxEntry(calendar.name, stringResource(R.string.shown_calendar, calendar.name), checked,
                                        { owner.shown(calendar.id, !checked) }, { owner.shown(calendar.id, !checked) },
                                        nameChecked = checked, controlColumnWidth = shownColumn.width)
                                }
                            }
                            if (state.calendarAccess) {
                                if (newEvents.choices.isNotEmpty()) Entry(newEvents, onChoice = { navigation.push(it.id) })
                                Entry(horizon, onChoice = { navigation.push(it.id) })
                            }
                            if (state.contactsAccess) {
                                Entry(contacts, onChoice = { navigation.push(it.id) })
                                if (state.contacts.target != null) Entry(SettingsEntry.Open("move", stringResource(R.string.phone_contacts), "",
                                    owner::prepareMove, actionLabel = stringResource(R.string.move_to, state.contacts.target.name)))
                            }
                        }
                        Column {
                            Entry(SettingsEntry.Metadata("status", stringResource(R.string.last_sync), status))
                            SheetAction(stringResource(R.string.sync_now), owner::syncNow)
                        }
                    }
                }
            }
        }, content = {
            owner.confirmation?.let { move ->
                PaperSheet(owner::cancelMove) {
                    PaperSheetHeading(stringResource(R.string.move_phone_contacts), owner::cancelMove)
                    SheetText(pluralStringResource(R.plurals.move_confirmation, move.sourceIds.size, move.sourceIds.size, move.destination.name))
                    SheetAction(stringResource(R.string.move), owner::confirmMove)
                    SheetAction(stringResource(R.string.cancel), owner::cancelMove)
                }
            }
        })
    }
}

private fun ContactAccount.choiceId() = org.json.JSONObject().apply {
    put("name", name); put("type", type); put("dataSet", dataSet ?: org.json.JSONObject.NULL)
}.toString()
