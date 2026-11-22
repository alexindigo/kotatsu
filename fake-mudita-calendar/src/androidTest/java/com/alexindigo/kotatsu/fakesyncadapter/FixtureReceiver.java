package com.alexindigo.kotatsu.fakesyncadapter;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.CalendarContract.Calendars;
import android.provider.CalendarContract.Events;
import android.provider.ContactsContract;

/** Only shell sets up the fixture; target-uid interaction would mask package visibility. */
public class FixtureReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String name = intent.getStringExtra("name");
        if (name == null || !name.startsWith("kotatsu-external-test-")) throw new IllegalArgumentException("Fixture name");
        Account account = new Account(name, "com.alexindigo.kotatsu.fakesyncadapter.account");
        android.content.ContentResolver resolver = context.getContentResolver();
        try {
            switch (intent.getStringExtra("operation")) {
                case "seed": {
                    if (!AccountManager.get(context).addAccountExplicitly(account, null, null)) throw new IllegalStateException("Account creation");
                    ContentValues settings = new ContentValues();
                    settings.put("account_name", name); settings.put("account_type", account.type);
                    settings.put(ContactsContract.Settings.SHOULD_SYNC, 1); settings.put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1);
                    resolver.insert(ContactsContract.Settings.CONTENT_URI, settings);
                    ContentValues calendar = new ContentValues();
                    calendar.put(Calendars.ACCOUNT_NAME, name); calendar.put(Calendars.ACCOUNT_TYPE, account.type);
                    calendar.put(Calendars.NAME, name); calendar.put(Calendars.OWNER_ACCOUNT, name);
                    calendar.put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER); calendar.put(Calendars.SYNC_EVENTS, 1);
                    calendar.put(Calendars.VISIBLE, 1); calendar.put(Calendars.CALENDAR_TIME_ZONE, "UTC");
                    long id = ContentUris.parseId(resolver.insert(syncUri(Calendars.CONTENT_URI, account), calendar));
                    long start = System.currentTimeMillis() + 3600000;
                    ContentValues event = new ContentValues();
                    event.put(Events.CALENDAR_ID, id); event.put(Events._SYNC_ID, "fixture-event");
                    event.put(Events.TITLE, "External calendar event"); event.put(Events.DTSTART, start); event.put(Events.DTEND, start + 3600000);
                    event.put(Events.EVENT_TIMEZONE, "UTC");
                    resolver.insert(syncUri(Events.CONTENT_URI, account), event);
                    break;
                }
                case "cleanup": {
                    String[] args = {name, account.type};
                    resolver.delete(syncUri(Calendars.CONTENT_URI, account), "account_name = ? AND account_type = ?", args);
                    resolver.delete(ContactsContract.Settings.CONTENT_URI, "account_name = ? AND account_type = ?", args);
                    if (!AccountManager.get(context).removeAccountExplicitly(account)) throw new IllegalStateException("Account removal");
                    break;
                }
                default: throw new IllegalArgumentException("Fixture operation");
            }
            setResultData("fixture complete");
        } catch (Exception error) {
            setResultCode(1); setResultData(error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }
    private static Uri syncUri(Uri uri, Account account) {
        return uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name).appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type).build();
    }
}
