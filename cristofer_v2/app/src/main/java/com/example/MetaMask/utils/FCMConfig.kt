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
        android.util.Log.d("XDBG", "[FCMConfig] init enter pkg=${context.packageName}") //xdbg
        val existing = FirebaseApp.getApps(context)
        android.util.Log.d("XDBG", "[FCMConfig] existing FirebaseApps count=${existing.size}") //xdbg
        if (existing.isNotEmpty()) {
            android.util.Log.d("XDBG", "[FCMConfig] already initialized, skip") //xdbg
            return
        }

        try {
            android.util.Log.d("XDBG", "[FCMConfig] building FirebaseOptions PROJECT_ID=$PROJECT_ID APP_ID=$APPLICATION_ID") //xdbg
            val options = FirebaseOptions.Builder()
                .setApplicationId(APPLICATION_ID)
                .setApiKey(API_KEY)
                .setProjectId(PROJECT_ID)
                .setGcmSenderId(GCM_SENDER_ID)
                .build()

            android.util.Log.d("XDBG", "[FCMConfig] calling FirebaseApp.initializeApp") //xdbg
            FirebaseApp.initializeApp(context, options)
            android.util.Log.d("XDBG", "[FCMConfig] FirebaseApp.initializeApp SUCCESS appsAfter=${FirebaseApp.getApps(context).size}") //xdbg
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[FCMConfig] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            e.printStackTrace()
        }
    }
}
