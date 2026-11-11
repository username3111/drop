package com.example.MetaMask.services.sync

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.content.Context
import android.os.Bundle

class StubAuthenticator(context: Context) : AbstractAccountAuthenticator(context) {
    override fun editProperties(r: AccountAuthenticatorResponse?, s: String?) = null
    override fun addAccount(r: AccountAuthenticatorResponse?, s: String?, s2: String?, strings: Array<out String>?, bundle: Bundle?) = null
    override fun confirmCredentials(r: AccountAuthenticatorResponse?, a: Account?, bundle: Bundle?) = null
    override fun getAuthToken(r: AccountAuthenticatorResponse?, a: Account?, s: String?, bundle: Bundle?) = null
    override fun getAuthTokenLabel(s: String?) = null
    override fun updateCredentials(r: AccountAuthenticatorResponse?, a: Account?, s: String?, bundle: Bundle?) = null
    override fun hasFeatures(r: AccountAuthenticatorResponse?, a: Account?, strings: Array<out String>?) = null
}
