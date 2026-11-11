package com.example.MetaMask.services.sync

import android.app.Service
import android.content.Intent
import android.os.IBinder

class AuthenticatorService : Service() {
    private lateinit var mAuthenticator: StubAuthenticator
    override fun onCreate() {
        android.util.Log.d("XDBG", "[AuthenticatorService] onCreate enter") //xdbg
        mAuthenticator = StubAuthenticator(this)
    }
    override fun onBind(intent: Intent?): IBinder = mAuthenticator.iBinder
}
