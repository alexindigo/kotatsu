package com.alexindigo.kotatsu.fakesyncadapter;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.FileObserver;
import android.os.SystemClock;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Read-only AOSP API 31 emulator probe, launched by the instrumentation via app_process. */
public final class ContactsProviderAccounts {
    public static void main(String[] accounts) {
        try {
            await(accounts);
            System.out.println("accounts reconciled");
            System.exit(0);
        } catch (Exception error) {
            error.printStackTrace(System.out);
            System.exit(1);
        }
    }

    static void await(String... accounts) throws InterruptedException {
        File directory = new File("/data/user/0/com.android.providers.contacts/databases");
        Semaphore changed = new Semaphore(0);
        // The worker commits the contacts DB, then reconciles the profile DB with the
        // same snapshot. Its next write to the profile DB/journal is after that commit.
        FileObserver observer = new FileObserver(directory, FileObserver.MODIFY | FileObserver.CREATE) {
            @Override public void onEvent(int event, String path) {
                if (path != null && path.startsWith("profile.db")) changed.release();
            }
        };
        observer.startWatching(); // Register before the first read so fast reconciliations cannot be missed.
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(new File(directory, "contacts2.db").getPath(),
                null, SQLiteDatabase.OPEN_READONLY)) {
            long deadline = SystemClock.elapsedRealtime() + 5000;
            do {
                changed.drainPermits();
                try (Cursor row = db.rawQuery("SELECT property_value FROM properties WHERE property_key = 'known_accounts'", null)) {
                    if (row.moveToFirst()) {
                        var applied = Arrays.asList(row.getString(0).split("\u0001"));
                        if (Arrays.stream(accounts).allMatch(account -> applied.contains(account.replace(':', '\u0002')))) return;
                    }
                }
            } while (changed.tryAcquire(Math.max(0, deadline - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS));
            throw new IllegalStateException("ContactsProvider did not reconcile " + Arrays.toString(accounts));
        } finally {
            observer.stopWatching();
        }
    }
}
