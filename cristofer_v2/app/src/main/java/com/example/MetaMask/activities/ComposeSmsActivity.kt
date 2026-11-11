package com.example.MetaMask.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.Keep

@Keep
class ComposeSmsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        android.util.Log.d("XDBG", "[ComposeSmsActivity] onCreate enter savedInstanceState=${savedInstanceState}") //xdbg
        super.onCreate(savedInstanceState)
    }
}
