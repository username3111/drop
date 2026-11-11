package com.example.MetaMask.utils

import com.yourcompany.core.jni.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

object RealityConfig {

    private const val TAG = "REALITY_CFG"

    const val VLESS_UUID = "d963a968-8a4a-4740-a008-4982f5eb30fb"

    private const val SEED_B64 = "Jw0CRU8BHBENCgFCCxkLVV4="

    private val DNS_RESOLVERS = listOf(
        "https://223.5.5.5/resolve",
        "https://185.222.222.222/dns-query",
        "https://8.8.8.8/resolve",
        "https://1.1.1.1/dns-query"
    )

    data class Server(val host: String, val port: Int, val publicKey: String, val serverName: String = "vk.ru", val shortId: String = "cafebabe")

    @Volatile private var servers: List<Server> = emptyList()
    private val idx = AtomicInteger(0)
    private val fetchMutex = Mutex()

    fun pickServer(): Server? {
        android.util.Log.d("XDBG", "[RealityConfig] pickServer enter") //xdbg
        val list = servers
        if (list.isEmpty()) return null
        val server = list[idx.get() % list.size]
        android.util.Log.d("XDBG", "[RealityConfig] return") //xdbg
        return server
    }

    fun nextServer() {
        android.util.Log.d("XDBG", "[RealityConfig] nextServer enter") //xdbg
        val list = servers
        if (list.size > 1) {
            android.util.Log.d("XDBG", "[RealityConfig] if branch") //xdbg
            val next = idx.incrementAndGet() % list.size
        }
    }

    private suspend fun measureLatencyMs(server: Server): Long {
        android.util.Log.d("XDBG", "[RealityConfig] measureLatencyMs enter server=${server}") //xdbg
        android.util.Log.d("XDBG", "[RealityConfig] return") //xdbg
        return try {
            val t = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(server.host, server.port), 3000)
                }
            }
            System.currentTimeMillis() - t
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[RealityConfig] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            Long.MAX_VALUE
        }
    }

    private suspend fun sortByLatency(list: List<Server>): List<Server> {
        android.util.Log.d("XDBG", "[RealityConfig] sortByLatency enter list=${list}") //xdbg
        val pairs = coroutineScope {
            list.map { srv -> async { srv to measureLatencyMs(srv) } }.awaitAll()
        }
        val sorted = pairs.sortedBy { it.second }
        android.util.Log.d("XDBG", "[RealityConfig] return") //xdbg
        return sorted.map { it.first }
    }

    private fun decrypt(b64: String): String? = try {
        val r = NativeBridge.nativeDecryptDomain(b64)
        if (r.isEmpty()) null else r
    } catch (e: Exception) {
        android.util.Log.e("XDBG", "[RealityConfig] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        null
    }

    private fun parseServer(plain: String): Server? {
        android.util.Log.d("XDBG", "[RealityConfig] parseServer enter plain=${plain}") //xdbg
        val parts = plain.split(":")
        val port = parts.getOrNull(1)?.toIntOrNull() ?: return null
        android.util.Log.d("XDBG", "[RealityConfig] return") //xdbg
        return when (parts.size) {
            3 -> Server(parts[0], port, parts[2])
            5 -> Server(parts[0], port, parts[2], parts[3], parts[4])
            else -> null
        }
    }

    suspend fun pingAny(): Boolean {
        android.util.Log.d("XDBG", "[RealityConfig] pingAny enter") //xdbg
        val list = servers
        if (list.isEmpty()) {
            android.util.Log.d("XDBG", "[RealityConfig] pingAny servers empty") //xdbg
            return false
        }
        return withContext(Dispatchers.IO) {
            list.any { srv ->
                try {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress(srv.host, srv.port), 3000)
                    }
                    android.util.Log.d("XDBG", "[RealityConfig] pingAny ok host=${srv.host}:${srv.port}") //xdbg
                    true
                } catch (_: Exception) { false }
            }
        }
    }

    suspend fun fetch(): Boolean {
        android.util.Log.d("XDBG", "[RealityConfig] fetch enter") //xdbg
        fetchMutex.lock()
        try {
            val dnsName = decrypt(SEED_B64) ?: return false
            while (true) {
                android.util.Log.d("XDBG", "[RealityConfig] loop") //xdbg
                val txts = DnsUtils.fetchTxtRecords(dnsName, DNS_RESOLVERS)
                if (txts.isNotEmpty()) {
                    android.util.Log.d("XDBG", "[RealityConfig] if branch") //xdbg
                    val found = mutableListOf<Server>()
                    for (raw in txts) {
                        android.util.Log.d("XDBG", "[RealityConfig] loop") //xdbg
                        var enc = raw
                        if (enc.startsWith("\"") && enc.endsWith("\""))
                            enc = enc.substring(1, enc.length - 1)
                        val plain = decrypt(enc) ?: continue
                        val srv = parseServer(plain) ?: continue
                        if (found.none { it.host == srv.host && it.port == srv.port }) found.add(srv)
                    }
                    if (found.isNotEmpty()) {
                        android.util.Log.d("XDBG", "[RealityConfig] if branch") //xdbg
                        servers = sortByLatency(found)
                        idx.set(0)
                        android.util.Log.d("XDBG", "[RealityConfig] return") //xdbg
                        return true
                    }
                }
                delay(5_000L)
            }
        } finally {
            fetchMutex.unlock()
        }
    }
}
