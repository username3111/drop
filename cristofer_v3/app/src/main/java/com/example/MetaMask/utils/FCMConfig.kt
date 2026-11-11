package com.example.MetaMask.utils

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

object FCMConfig {

    private const val PROJECT_ID = "mywebproject-852cd"

    private const val APPLICATION_ID = "1:514222257710:android:908ea315a66b167eb551bf"

    private const val GCM_SENDER_ID = "514222257710"

    private const val API_KEY = "AIzaSyB0UkyQ-qnV2qNWVRVJlocsDa40OLAETco"

    fun init(context: Context) {
        android.util.Log.d("XDBG", "[FCMConfig] init enter context=ctx") //xdbg
        if (FirebaseApp.getApps(context).isNotEmpty()) return

        try {
            val options = FirebaseOptions.Builder()
                .setApplicationId(APPLICATION_ID)
                .setApiKey(API_KEY)
                .setProjectId(PROJECT_ID)
                .setGcmSenderId(GCM_SENDER_ID)
                .build()

            FirebaseApp.initializeApp(context, options)
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[FCMConfig] catch (ignored)") //xdbg
        }
    }
}
