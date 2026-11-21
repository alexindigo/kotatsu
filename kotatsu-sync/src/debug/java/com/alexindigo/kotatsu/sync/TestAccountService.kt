package com.alexindigo.kotatsu.sync

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.app.Service
import android.content.Intent
import android.os.Bundle

/** Debug-only authenticator lets connected tests own and remove a real sync account. */
class TestAccountService : Service() {
    override fun onBind(intent: Intent) = object : AbstractAccountAuthenticator(this) {
        override fun editProperties(response: AccountAuthenticatorResponse, accountType: String) = Bundle.EMPTY
        override fun addAccount(response: AccountAuthenticatorResponse, accountType: String, authTokenType: String?, requiredFeatures: Array<out String>?, options: Bundle?) = Bundle.EMPTY
        override fun confirmCredentials(response: AccountAuthenticatorResponse, account: Account, options: Bundle?) = Bundle.EMPTY
        override fun getAuthToken(response: AccountAuthenticatorResponse, account: Account, authTokenType: String, options: Bundle?) = Bundle.EMPTY
        override fun getAuthTokenLabel(authTokenType: String) = "Test"
        override fun updateCredentials(response: AccountAuthenticatorResponse, account: Account, authTokenType: String?, options: Bundle?) = Bundle.EMPTY
        override fun hasFeatures(response: AccountAuthenticatorResponse, account: Account, features: Array<out String>) = Bundle().apply { putBoolean("booleanResult", false) }
    }.iBinder
}
