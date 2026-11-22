package com.alexindigo.kotatsu.fakesyncadapter;

import android.accounts.AbstractAccountAuthenticator;
import android.accounts.Account;
import android.accounts.AccountAuthenticatorResponse;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

/** Framework-only: this test APK also runs standalone, outside instrumentation's classpath. */
public class AccountService extends Service {
    @Override public IBinder onBind(Intent intent) {
        return new AbstractAccountAuthenticator(this) {
            @Override public Bundle editProperties(AccountAuthenticatorResponse response, String type) { return Bundle.EMPTY; }
            @Override public Bundle addAccount(AccountAuthenticatorResponse response, String type, String token, String[] features, Bundle options) { return Bundle.EMPTY; }
            @Override public Bundle confirmCredentials(AccountAuthenticatorResponse response, Account account, Bundle options) { return Bundle.EMPTY; }
            @Override public Bundle getAuthToken(AccountAuthenticatorResponse response, Account account, String token, Bundle options) { return Bundle.EMPTY; }
            @Override public String getAuthTokenLabel(String token) { return "Test"; }
            @Override public Bundle updateCredentials(AccountAuthenticatorResponse response, Account account, String token, Bundle options) { return Bundle.EMPTY; }
            @Override public Bundle hasFeatures(AccountAuthenticatorResponse response, Account account, String[] features) {
                Bundle result = new Bundle(); result.putBoolean("booleanResult", false); return result;
            }
        }.getIBinder();
    }
}
