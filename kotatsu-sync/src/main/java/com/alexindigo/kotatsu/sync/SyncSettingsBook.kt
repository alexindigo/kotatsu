package com.alexindigo.kotatsu.sync

import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gezimos.katapult.ui.LatoFamily
import com.gezimos.katapult.ui.LocalInk
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
        state.calendarTarget?.label ?: stringResource(R.string.unavailable),
        state.calendars.filter { it.id in state.settings.calendars }.map { SettingsChoice(it.id.toString(), it.label) },
        { owner.newEvents(it.toLong()) }, state.calendarTarget?.id?.toString().orEmpty(), appBuiltChoices = true)
    val horizon = SettingsEntry.Value("look-ahead", stringResource(R.string.look_ahead), "",
        pluralStringResource(R.plurals.weeks, state.settings.horizonWeeks, state.settings.horizonWeeks),
        (1..26).map { SettingsChoice(it.toString(), it.toString()) },
        { owner.weeks(it.toInt()) }, state.settings.horizonWeeks.toString())
    val contacts = SettingsEntry.Value("contacts", stringResource(R.string.contacts_go_to), "",
        state.contactTarget?.label ?: stringResource(R.string.unavailable),
        state.books.map { book -> SettingsChoice(book.account.choiceId(), book.label) },
        { id -> state.books.firstOrNull { it.account.choiceId() == id }?.let { owner.contacts(it.account) } },
        state.contactTarget?.account?.choiceId().orEmpty(), appBuiltChoices = true)
    val context = LocalContext.current
    val displayed = state.status.forDisplay(state.calendarAccess, state.contactsAccess)
    val reasons = if (displayed?.availabilityStop != null) setOf(displayed.availabilityStop)
        else displayed?.stopReasons.orEmpty()
    val stopped = reasons.sortedBy { it.ordinal }.mapNotNull { it.textResource() }.distinct().map { stringResource(it) }
    val status = if (stopped.isNotEmpty()) stopped.joinToString("\n")
        else displayed?.lastPassAt?.let { pluralStringResource(R.plurals.sync_status, displayed.changes,
            DateFormat.getTimeFormat(context).format(Date(it)), displayed.changes) }.orEmpty()
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
                                    PaperCheckboxEntry(calendar.label, stringResource(R.string.shown_calendar, calendar.label), checked,
                                        { owner.toggleCalendar(calendar.id) }, { owner.toggleCalendar(calendar.id) },
                                        nameChecked = checked, controlColumnWidth = shownColumn.width)
                                }
                            }
                            if (state.calendarAccess) {
                                if (newEvents.choices.isNotEmpty()) Entry(newEvents, onChoice = { navigation.push(it.id) })
                                Entry(horizon, onChoice = { navigation.push(it.id) })
                            }
                            if (state.contactsAccess) {
                                Entry(contacts, onChoice = { navigation.push(it.id) })
                                state.contactTarget?.let { target -> Entry(SettingsEntry.Open("move", stringResource(R.string.phone_contacts), "",
                                    owner::prepareMove, actionLabel = stringResource(R.string.move_to, target.label))) }
                            }
                        }
                        Column {
                            if (displayed != null) SyncStatusEntry(status)
                            SheetAction(stringResource(R.string.sync_now), owner::syncNow)
                        }
                    }
                }
            }
        }, content = {
            owner.confirmation?.let { move ->
                PaperSheet(owner::cancelMove) {
                    PaperSheetHeading(stringResource(R.string.move_phone_contacts), owner::cancelMove)
                    SheetText(pluralStringResource(R.plurals.move_confirmation, move.sourceIds.size, move.sourceIds.size,
                        state.books.singleOrNull { it.account == move.destination }?.label ?: addressBookLabel(move.destination)))
                    SheetAction(stringResource(R.string.move), owner::confirmMove)
                    SheetAction(stringResource(R.string.cancel), owner::cancelMove)
                }
            }
        })
    }
}

/** Only Last sync can reflow its value. Ordinary paper-ui entries stay unchanged. */
@Composable
private fun SyncStatusEntry(value: String) {
    val label = stringResource(R.string.last_sync)
    val style = TextStyle(fontFamily = LatoFamily, fontSize = 18.sp, lineHeight = 21.75.sp,
        fontWeight = FontWeight.Bold, color = LocalInk.current)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 9.dp)) {
        val halfWidth = with(density) { maxWidth.roundToPx() } / 2
        val measured = measurer.measure(value, style, maxLines = 1,
            constraints = Constraints(maxWidth = halfWidth))
        val labelWidth = measurer.measure(label, style, maxLines = 1).size.width
        val tracksFit = labelWidth + measured.size.width + with(density) { 24.dp.roundToPx() } <= halfWidth * 2
        if (tracksFit && !measured.hasVisualOverflow && !value.contains('\n')) IndexLine(label, value)
        else Column(Modifier.fillMaxWidth()) {
            BasicText(label, style = style, maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.fillMaxWidth())
            BasicText(value, style = style, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun ContactAccount.choiceId() = org.json.JSONObject().apply {
    put("name", name); put("type", type); put("dataSet", dataSet ?: org.json.JSONObject.NULL)
}.toString()
