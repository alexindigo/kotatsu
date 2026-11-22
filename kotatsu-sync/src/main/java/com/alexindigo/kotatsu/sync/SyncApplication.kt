package com.alexindigo.kotatsu.sync

import android.app.Application

/** Runs for every component entry, not only a launcher activity or a reboot. */
class SyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncRuntime.start(this)
    }
}
