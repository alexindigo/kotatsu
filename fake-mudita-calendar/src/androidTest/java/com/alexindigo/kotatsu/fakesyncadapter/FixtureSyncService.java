package com.alexindigo.kotatsu.fakesyncadapter;

import android.accounts.Account;
import android.app.Service;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Intent;
import android.content.SyncResult;
import android.os.Bundle;
import android.os.IBinder;

/** Each external service advertises one authority; no network or target-app interaction. */
abstract class FixtureSyncService extends Service {
    @Override public IBinder onBind(Intent intent) {
        return new AbstractThreadedSyncAdapter(this, true) {
            @Override public void onPerformSync(Account account, Bundle extras, String authority, ContentProviderClient provider, SyncResult result) { }
        }.getSyncAdapterBinder();
    }
}
