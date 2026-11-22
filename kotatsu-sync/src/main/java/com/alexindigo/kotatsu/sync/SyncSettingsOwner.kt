package com.alexindigo.kotatsu.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract.Calendars
import android.provider.ContactsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class CalendarChoice(val id: Long, val name: String)
data class SyncSettingsSnapshot(
    val calendarAccess: Boolean = false,
    val contactsAccess: Boolean = false,
    val calendars: List<CalendarChoice> = emptyList(),
    val books: List<ContactAccount> = emptyList(),
    val settings: SyncSettings = SyncSettings(emptySet(), null),
    val contacts: ContactsSettings = ContactsSettings(),
    val status: SyncStatus = SyncStatus(),
    val loaded: Boolean = false,
)

/** The settings owner shares the bridge lane; providers never run from composition. */
class SyncSettingsOwner(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var resumed = false
    private var generation = 0
    private var moveGeneration = 0
    private var refreshQueued = false
    var snapshot by mutableStateOf(SyncSettingsSnapshot(
        calendarAccess = access(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        contactsAccess = access(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
        settings = PreferenceSyncConfig(app).read(), contacts = PreferenceContactsConfig(app).read(),
    ))
        private set
    var confirmation by mutableStateOf<ContactsMove?>(null)
        private set
    private val observer = object : ContentObserver(main) { override fun onChange(selfChange: Boolean) { refresh() } }
    private val tick = object : Runnable {
        override fun run() {
            if (!resumed) return
            refresh()
            main.postDelayed(this, 1000)
        }
    }

    fun resume() {
        if (resumed) return
        resumed = true
        if (access(Manifest.permission.READ_CALENDAR)) app.contentResolver.registerContentObserver(Calendars.CONTENT_URI, true, observer)
        if (access(Manifest.permission.READ_CONTACTS)) app.contentResolver.registerContentObserver(ContactsContract.AUTHORITY_URI, true, observer)
        main.post(tick)
    }
    fun pause() {
        resumed = false
        main.removeCallbacks(tick)
        app.contentResolver.unregisterContentObserver(observer)
        generation++
        moveGeneration++
    }
    private fun access(vararg permissions: String) = permissions.all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun calendars(): List<CalendarChoice> = if (!access(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) emptyList()
        else checkNotNull(app.contentResolver.query(Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_TYPE, Calendars.SYNC_EVENTS),
            null, null, "${Calendars.CALENDAR_DISPLAY_NAME} COLLATE NOCASE, ${Calendars._ID}")) { "calendar provider unavailable" }.use { c ->
            buildList { while (c.moveToNext()) {
                if (SyncCalendar(c.getLong(0), c.getString(2).orEmpty(), c.getInt(3) != 0).eligible)
                    add(CalendarChoice(c.getLong(0), c.getString(1).orEmpty()))
            } }
        }

    /** Shown membership is configuration; provider availability must not erase saved identities. */
    private fun calendarSettings(settings: SyncSettings, available: List<CalendarChoice>): SyncSettings = settings.copy(
        newEventCalendar = settings.newEventCalendar?.takeIf { it in settings.calendars }
            ?: defaultCalendar(available.filter { it.id in settings.calendars }))

    private fun read(): SyncSettingsSnapshot {
        val calendarAccess = access(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val contactsAccess = access(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        val calendars = calendars()
        val books = if (!contactsAccess) emptyList() else AndroidContacts(app).addressBooks()
            .sortedWith(compareBy<ContactAccount> { it.name.lowercase() }.thenBy { it.type }.thenBy { it.dataSet.orEmpty() })
        val calendarConfig = PreferenceSyncConfig(app)
        val settings = calendarConfig.read()
        val current = calendarSettings(settings, calendars)
        var changed = false
        if (current != settings) { calendarConfig.write(current); changed = true }
        val contactsConfig = PreferenceContactsConfig(app)
        var contacts = contactsConfig.read()
        if (contacts.target == null && books.isNotEmpty()) {
            contacts = ContactsSettings(books.firstOrNull { it.name.contains("Personal", ignoreCase = true) } ?: books.first())
            contactsConfig.write(contacts); changed = true
        }
        if (changed) SyncRuntime.ensureArmed(app)
        return BridgeStore(app).use { SyncSettingsSnapshot(calendarAccess, contactsAccess, calendars, books, current, contacts, it.syncStatus, true) }
    }

    fun refresh() {
        if (refreshQueued) return
        refreshQueued = true
        update(complete = { refreshQueued = false }) {}
    }
    fun shown(id: Long, value: Boolean) = calendar { old, available ->
        if (value && available.none { it.id == id }) old
        else old.copy(calendars = if (value) old.calendars + id else old.calendars - id)
    }
    fun newEvents(id: Long) = calendar { old, _ ->
        if (id in old.calendars) old.copy(newEventCalendar = id) else old
    }
    fun weeks(weeks: Int) = calendar { old, _ -> old.copy(horizonWeeks = weeks) }
    /** Validate dependencies against fresh inputs on the lane, never the published UI snapshot. */
    private fun calendar(change: (SyncSettings, List<CalendarChoice>) -> SyncSettings) = update {
        val config = PreferenceSyncConfig(app)
        val available = calendars()
        config.write(calendarSettings(change(config.read(), available), available))
        SyncRuntime.ensureArmed(app)
    }
    fun contacts(book: ContactAccount) = update {
        if (book in AndroidContacts(app).addressBooks()) {
            PreferenceContactsConfig(app).write(ContactsSettings(book))
            SyncRuntime.ensureArmed(app)
        }
    }
    fun syncNow() = update { SyncRuntime.ensureArmed(app) }
    fun prepareMove() {
        val request = ++moveGeneration
        SyncRuntime.enqueue {
            try {
                val sources = AndroidContacts(app).sources().filter { it.origin == ContactOrigin.PHONE }.map { it.id }.toSet()
                val move = BridgeStore(app).use { store -> store.pendingContactsMove?.let {
                    it.copy(sourceIds = it.sourceIds.intersect(sources))
                } ?: run {
                    val target = checkNotNull(PreferenceContactsConfig(app).read().target) { "contacts address book not selected" }
                    ContactsMove(target, sources)
                } }
                main.post { if (moveGeneration == request) confirmation = move }
            } catch (error: Exception) { failed(error); refreshOnMain() }
        }
    }
    fun cancelMove() { moveGeneration++; confirmation = null }
    fun confirmMove() {
        val move = confirmation ?: return
        confirmation = null
        val result = SyncRuntime.moveConfirmedContacts(app, move)
        // This continuation runs after the transfer, never blocks the bridge lane.
        SyncRuntime.enqueue {
            try { result.get() } catch (error: Exception) { failed(error.cause ?: error) }
            refreshOnMain()
        }
    }
    private fun refreshOnMain() { main.post { refresh() } }
    private fun failed(error: Throwable) {
        BridgeStore(app).use { it.syncStatus = SyncStatus(System.currentTimeMillis(), 0,
            "${error.javaClass.simpleName}: ${error.message.orEmpty()}") }
    }
    private fun update(complete: () -> Unit = {}, write: () -> Unit) {
        val request = ++generation
        SyncRuntime.enqueue {
            val next = try { write(); read() } catch (error: Exception) {
                failed(error)
                BridgeStore(app).use { snapshot.copy(status = it.syncStatus, loaded = true) }
            }
            main.post { complete(); if (generation == request) snapshot = next }
        }
    }
    private fun defaultCalendar(shown: List<CalendarChoice>) =
        (shown.firstOrNull { it.name.contains("Personal", ignoreCase = true) } ?: shown.firstOrNull())?.id
}
