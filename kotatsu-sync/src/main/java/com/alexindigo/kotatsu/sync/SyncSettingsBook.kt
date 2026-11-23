package com.alexindigo.kotatsu.sync

import android.icu.text.ListFormatter
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.gezimos.katapult.ui.settings.*
import java.util.Date

/** One book: the kit owns placement, page allocation, semantics and Back. */
@Composable
fun SyncSettingsBook(owner: SyncSettingsOwner, onBack: () -> Unit, onAccess: (Boolean) -> Unit, onSetUp: (() -> Unit)?) {
    val state = owner.snapshot
    val navigation = rememberSettingsNavigation()
    val pages = rememberSaveableStateHolder()
    val back: () -> Unit = {
        if (navigation.current == MOVE) owner.cancelMove()
        if (navigation.canGoBack) { pages.removeState(navigation.current); navigation.pop() }
        else onBack()
    }
    val choice = choices(owner, state)
    val changes = stringResource(R.string.calendar_changes)
    val held = state.status.held
    val destination = navigation.current
    val hold = held.singleOrNull { it.id == destination.substringAfter(':', "") }
    SettingsBackOwner(back) {
        pages.SaveableStateProvider(destination) {
            when {
                destination in choice -> {
                    val value = choice.getValue(destination)
                    if (value.choices.isEmpty()) SettingsPage(value.title, back) { SheetText(value.help) }
                    else ChoiceSurface(value, back)
                }
                destination == CALENDARS -> PagedSettingsPage(stringResource(R.string.calendars), state.calendars,
                    back, header = { SheetText(stringResource(R.string.calendars_help)) }) { calendar ->
                    CheckboxOption(calendar.label, calendar.id in state.settings.calendars) { owner.toggleCalendar(calendar.id) }
                }
                destination == MOVE -> SettingsPage(stringResource(R.string.move_phone_contacts), back) {
                    val move = owner.confirmation
                    if (move != null) {
                        val label = state.books.singleOrNull { it.account == move.destination }?.label ?: addressBookLabel(move.destination)
                        SheetText(pluralStringResource(R.plurals.move_confirmation, move.sourceIds.size, move.sourceIds.size, label))
                        SheetAction(stringResource(R.string.move)) { owner.confirmMove(); back() }
                    }
                    SheetAction(stringResource(R.string.cancel), back)
                }
                destination == CHANGES -> PagedSettingsPage(changes, held, back, header = {}) { item ->
                    Entry(SettingsEntry.Open(item.id, lossLabel(item), "", { navigation.push("$LOSS:${item.id}") }))
                }
                destination.startsWith("$LOSS:") || destination.startsWith("$DELETE:") -> {
                    val delete = destination.startsWith("$DELETE:")
                    LossPage(hold, delete, back, { current -> navigation.push("$DELETE:${current.id}") }) { current, resolution ->
                        owner.resolveLoss(current.id, resolution)
                        back()
                        if (delete && navigation.current == "$LOSS:${current.id}") back()
                    }
                }
                else -> PaperIndexRenderer(category(state, owner, onAccess, onSetUp, choice, onOpen = { target ->
                    when (target) {
                        MOVE -> { owner.prepareMove(); navigation.push(MOVE) }
                        CHANGES -> navigation.push(held.singleOrNull()?.let { "$LOSS:${it.id}" } ?: CHANGES)
                        else -> navigation.push(target)
                    }
                }), onBack = back, onChoice = { navigation.push(it.id) })
            }
        }
    }
}

@Composable
private fun choices(owner: SyncSettingsOwner, state: SyncSettingsSnapshot): Map<String, SettingsEntry.Value> {
    val unavailable = stringResource(R.string.unavailable)
    val newEvents = SettingsEntry.Value(NEW, stringResource(R.string.new_events),
        stringResource(if (state.calendarTarget == null) R.string.chosen_calendar_unavailable else R.string.new_events_help),
        state.calendarTarget?.label ?: unavailable,
        state.calendars.filter { it.id in state.settings.calendars }.map { SettingsChoice(it.id.toString(), it.label) },
        { owner.newEvents(it.toLong()) }, state.calendarTarget?.id?.toString().orEmpty(), appBuiltChoices = true)
    val horizon = SettingsEntry.Value(HORIZON, stringResource(R.string.look_ahead), stringResource(R.string.look_ahead_help),
        weeks(state.settings.horizonWeeks), (1..26).map { SettingsChoice(it.toString(), weeks(it)) },
        { owner.weeks(it.toInt()) }, state.settings.horizonWeeks.toString())
    val contacts = SettingsEntry.Value(BOOK, stringResource(R.string.contacts_go_to),
        stringResource(when {
            state.contactTarget != null -> R.string.contacts_go_to_help
            state.contacts.mode == TargetMode.EXPLICIT -> R.string.chosen_calendar_unavailable
            else -> R.string.no_address_books
        }), state.contactTarget?.label ?: unavailable,
        state.books.map { SettingsChoice(it.account.choiceId(), it.label) },
        { id -> state.books.firstOrNull { it.account.choiceId() == id }?.let { owner.contacts(it.account) } },
        state.contactTarget?.account?.choiceId().orEmpty(), appBuiltChoices = true)
    return listOf(newEvents, horizon, contacts).associateBy { it.id }
}

@Composable
private fun category(state: SyncSettingsSnapshot, owner: SyncSettingsOwner, onAccess: (Boolean) -> Unit,
    onSetUp: (() -> Unit)?, choice: Map<String, SettingsEntry.Value>, onOpen: (String) -> Unit): SettingsCategory {
    val noDav = state.loaded && state.calendarAccess && state.contactsAccess && state.calendars.isEmpty() && state.books.isEmpty()
    val access = buildList<SettingsEntry> {
        add(accessRow("calendar-access", R.string.calendar_access, R.string.calendar_access_help, state.calendarAccess) { onAccess(true) })
        add(accessRow("contacts-access", R.string.contacts_access, R.string.contacts_access_help, state.contactsAccess) { onAccess(false) })
        if (noDav) {
            val title = stringResource(R.string.davx5)
            if (onSetUp != null) add(SettingsEntry.Open("davx5", title, stringResource(R.string.no_davx5_catalogs), onSetUp, external = true))
            else add(SettingsEntry.Metadata("davx5", title, stringResource(R.string.unavailable), stringResource(R.string.davx5_unavailable)))
        }
    }
    val calendars = if (noDav || !state.calendarAccess) emptyList() else buildList<SettingsEntry> {
        if (state.calendars.isNotEmpty()) add(SettingsEntry.Open(CALENDARS, stringResource(R.string.calendars),
            stringResource(R.string.calendars_help), { onOpen(CALENDARS) }))
        if (state.settings.calendars.isNotEmpty()) add(choice.getValue(NEW).availableEntry())
        add(choice.getValue(HORIZON))
    }
    val contacts = if (noDav || !state.contactsAccess) emptyList() else buildList<SettingsEntry> {
        add(choice.getValue(BOOK).availableEntry())
        state.contactTarget?.let { target ->
            val waiting = state.status.contactsWaiting
            val help = if (waiting > 0) pluralStringResource(R.plurals.contacts_waiting, waiting, waiting)
                else stringResource(R.string.move_to, target.label)
            add(SettingsEntry.Open(MOVE, stringResource(R.string.phone_contacts), help, { onOpen(MOVE) }))
        }
    }
    val context = LocalContext.current
    val status = state.status
    val sync = buildList<SettingsEntry> {
        status.lastPassAt?.let { at ->
            val reasons = status.held.map { lossLabel(it) } + status.stopReasons.sortedBy { it.ordinal }
                .mapNotNull { it.textResource() }.map { stringResource(it) }
            val help = if (reasons.isEmpty()) pluralStringResource(R.plurals.sync_status, status.changes, status.changes)
                else reasons.distinct().joinToString("\n")
            add(SettingsEntry.Metadata("last-sync", stringResource(R.string.last_sync), DateFormat.getTimeFormat(context).format(Date(at)), help))
        }
        if (status.held.isNotEmpty()) add(SettingsEntry.Open(CHANGES, stringResource(R.string.calendar_changes), "", { onOpen(CHANGES) }))
        add(SettingsEntry.Action("sync-now", stringResource(R.string.sync_now), owner::syncNow,
            if (status.availabilityStop != null) stringResource(R.string.stopped_scheduling) else ""))
    }
    return SettingsCategory("sync-settings", stringResource(R.string.app_name), listOf(
        SettingsGroup("access", stringResource(R.string.access), access),
        SettingsGroup("calendars", stringResource(R.string.calendars), calendars),
        SettingsGroup("contacts", stringResource(R.string.contacts_group), contacts),
        SettingsGroup("sync", stringResource(R.string.sync_group), sync),
    ))
}

@Composable
private fun accessRow(id: String, title: Int, help: Int, granted: Boolean, onOpen: () -> Unit) =
    SettingsEntry.Open(id, stringResource(title), stringResource(help), onOpen,
        actionLabel = stringResource(if (granted) R.string.granted else R.string.not_granted), minimumHeightDp = 63f)

private fun SettingsEntry.Value.availableEntry(): SettingsEntry = if (choices.isNotEmpty()) this
    else SettingsEntry.Metadata(id, title, value, help)

@Composable private fun weeks(count: Int) = pluralStringResource(R.plurals.weeks, count, count)

@Composable private fun HeldCalendarLoss.calendarNames() = ListFormatter.getInstance(LocalConfiguration.current.locales[0]).format(calendars)

@Composable
private fun lossLabel(hold: HeldCalendarLoss) = if (hold.direction == LossDirection.PHONE)
    pluralStringResource(R.plurals.held_phone_status, hold.count, hold.count)
else pluralStringResource(R.plurals.held_standard_status, hold.count, hold.count, hold.calendarNames())

@Composable
private fun LossPage(hold: HeldCalendarLoss?, delete: Boolean, back: () -> Unit, confirmDelete: (HeldCalendarLoss) -> Unit,
    resolve: (HeldCalendarLoss, LossResolution) -> Unit) {
    val title = stringResource(if (delete) R.string.delete_server_title else R.string.calendar_changes)
    SettingsPage(title, back) {
        if (hold == null) SheetText(stringResource(R.string.unavailable))
        else when {
            delete -> {
                SheetText(pluralStringResource(R.plurals.delete_server_intro, hold.count, hold.count, hold.calendarNames()))
                SheetAction(stringResource(R.string.delete)) { resolve(hold, LossResolution.DELETE_FROM_SERVER) }
                SheetAction(stringResource(R.string.cancel), back)
            }
            hold.direction == LossDirection.PHONE -> {
                SheetText(pluralStringResource(R.plurals.held_phone_intro, hold.count, hold.count))
                SheetAction(stringResource(R.string.restore_phone), singleLine = true) { resolve(hold, LossResolution.RESTORE) }
                SheetAction(stringResource(R.string.delete_from_server), singleLine = true) { confirmDelete(hold) }
            }
            else -> {
                SheetText(pluralStringResource(R.plurals.held_standard_intro, hold.count, hold.count, hold.calendarNames()))
                SheetAction(stringResource(R.string.keep_phone), singleLine = true) { resolve(hold, LossResolution.KEEP) }
                SheetAction(stringResource(R.string.remove_phone), singleLine = true) { resolve(hold, LossResolution.REMOVE) }
            }
        }
    }
}

private fun ContactAccount.choiceId() = org.json.JSONObject().apply {
    put("name", name); put("type", type); put("dataSet", dataSet ?: org.json.JSONObject.NULL)
}.toString()

private const val CALENDARS = "calendars"
private const val NEW = "new-events"
private const val HORIZON = "look-ahead"
private const val BOOK = "contacts"
private const val MOVE = "move"
private const val CHANGES = "calendar-changes"
private const val LOSS = "loss"
private const val DELETE = "delete"
