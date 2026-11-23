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

data class SyncSettingsSnapshot(
    val calendarAccess: Boolean = false,
    val contactsAccess: Boolean = false,
    val calendars: List<SyncCalendar> = emptyList(),
    val books: List<AddressBookChoice> = emptyList(),
    val settings: SyncSettings = SyncSettings(emptySet(), null),
    val contacts: ContactsSettings = ContactsSettings(),
    val status: SyncStatus = SyncStatus(),
    val loaded: Boolean = false,
) {
    val calendarTarget get() = settings.destination(calendars)
    val contactTarget get() = contacts.destination(books)
}

/** The settings owner shares the bridge lane; providers never run from composition. */
class SyncSettingsOwner(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var resumed = false
    private var revision = 0L
    private val pending = linkedMapOf<Long, CalendarSettingsIntent>()
    private var moveGeneration = 0
    private var refreshQueued = false
    private var committed = SyncSettingsSnapshot(
        calendarAccess = access(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
        contactsAccess = access(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS),
        settings = PreferenceSyncConfig(app).read(), contacts = PreferenceContactsConfig(app).read(),
    )
    var snapshot by mutableStateOf(committed)
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
        moveGeneration++
    }
    private fun access(vararg permissions: String) = permissions.all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    private fun calendars(): List<SyncCalendar> = if (!access(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) emptyList()
        else StandardCalendar(app.contentResolver).calendars().filter { it.eligible }.sortedWith(calendarOrder)

    private fun read(): SyncSettingsSnapshot {
        val calendarAccess = access(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val contactsAccess = access(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        val calendars = calendars()
        val books = if (!contactsAccess) emptyList() else AndroidContacts(app).addressBookChoices()
        return BridgeStore(app).use { SyncSettingsSnapshot(calendarAccess, contactsAccess, calendars, books,
            PreferenceSyncConfig(app).read(), PreferenceContactsConfig(app).read(), it.syncStatus, true) }
    }

    fun refresh() {
        if (refreshQueued) return
        refreshQueued = true
        update(complete = { refreshQueued = false }) {}
    }
    fun toggleCalendar(id: Long) = calendar(CalendarSettingsIntent.Toggle(id))
    fun newEvents(id: Long) = calendar(CalendarSettingsIntent.Choose(id))
    fun weeks(weeks: Int) = calendar(CalendarSettingsIntent.Weeks(weeks))

    private fun calendar(intent: CalendarSettingsIntent) {
        val request = ++revision
        pending[request] = intent
        publish()
        update(request = request) {
            val config = PreferenceSyncConfig(app)
            val old = config.read()
            val next = intent.reduce(old, calendars()) // Revalidate current provider and selection on the lane.
            if (next != old) {
                config.write(next)
                SyncRuntime.ensureArmed(app)
            }
        }
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
                    val target = checkNotNull(PreferenceContactsConfig(app).read()
                        .destination(AndroidContacts(app).addressBookChoices())) { "contacts address book not available" }.account
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
        BridgeStore(app).use {
            it.status = "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            it.syncStatus = it.syncStatus.copy(availabilityStop = SyncStopReason.RETRY_REQUIRED)
        }
    }
    private fun update(request: Long = ++revision, complete: () -> Unit = {}, write: () -> Unit) {
        val fallback = committed
        SyncRuntime.enqueue {
            val next = try { write(); read() } catch (error: Exception) {
                failed(error)
                runCatching { read() }.getOrElse {
                    BridgeStore(app).use { fallback.copy(settings = PreferenceSyncConfig(app).read(),
                        contacts = PreferenceContactsConfig(app).read(), status = it.syncStatus, loaded = true) }
                }
            }
            // The lane posts acknowledgements in order. Older reads never
            // erase taps that were sent while those reads were in flight.
            main.post {
                complete()
                committed = next
                pending.keys.removeAll { it <= request }
                publish()
            }
        }
    }
    private fun publish() {
        snapshot = committed.copy(settings = pending.values.fold(committed.settings) { settings, intent ->
            intent.reduce(settings, committed.calendars)
        })
    }
}
