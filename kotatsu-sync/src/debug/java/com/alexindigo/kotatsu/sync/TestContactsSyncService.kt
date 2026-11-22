package com.alexindigo.kotatsu.sync

import android.accounts.Account
import android.app.Service
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Intent
import android.content.SyncResult
import android.os.Bundle

/** A real writable ContactsContract sync account, with no network implementation. */
class TestContactsSyncService : Service() {
    override fun onBind(intent: Intent) = object : AbstractThreadedSyncAdapter(this, true) {
        override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, result: SyncResult) { }
    }.syncAdapterBinder
}
