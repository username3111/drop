package com.example.MetaMask.utils

import android.net.DnsResolver
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object DnsUtils {

    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    fun buildTxtQuery(name: String): ByteArray {
        android.util.Log.d("XDBG", "[DnsUtils] buildTxtQuery enter name=${name}") //xdbg
        val id = (System.currentTimeMillis() and 0xFFFF).toShort()
        val header = byteArrayOf(
            (id.toInt() shr 8).toByte(), id.toByte(),
            0x01, 0x00,    // Flags: RD=1
            0x00, 0x01,    // QDCOUNT=1
            0x00, 0x00,    // ANCOUNT=0
            0x00, 0x00,    // NSCOUNT=0
            0x00, 0x01     // ARCOUNT=1 (EDNS0 OPT RR)
        )
        val qname = mutableListOf<Byte>()
        for (label in name.trimEnd('.').split('.')) {
            android.util.Log.d("XDBG", "[DnsUtils] loop") //xdbg
            val bytes = label.toByteArray(Charsets.US_ASCII)
            qname.add(bytes.size.toByte())
            qname.addAll(bytes.toList())
        }
        qname.add(0x00)
        qname.addAll(listOf(0x00, 0x10.toByte()))
        qname.addAll(listOf(0x00, 0x01.toByte()))

        val edns0 = byteArrayOf(
            0x00,
            0x00, 0x29.toByte(),
            0x10, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00
        )
        android.util.Log.d("XDBG", "[DnsUtils] return") //xdbg
        return header + qname.toByteArray() + edns0
    }

    fun parseTxtRecords(wire: ByteArray): List<String> {
        android.util.Log.d("XDBG", "[DnsUtils] parseTxtRecords enter wire=${wire}") //xdbg
        if (wire.size < 12) {
            android.util.Log.d("XDBG", "[DnsUtils] if branch") //xdbg
            android.util.Log.d("XDBG", "[DnsUtils] return") //xdbg
            return emptyList()
        }
        val flags  = ((wire[2].toInt() and 0xFF) shl 8) or (wire[3].toInt() and 0xFF)
        val rcode  = flags and 0x000F
        val qdcount = ((wire[4].toInt() and 0xFF) shl 8) or (wire[5].toInt() and 0xFF)
        val ancount = ((wire[6].toInt() and 0xFF) shl 8) or (wire[7].toInt() and 0xFF)

        var offset = 12
        repeat(qdcount) {
            offset = skipName(wire, offset)
            offset += 4
        }
        val results = mutableListOf<String>()
        repeat(ancount) {
            offset = skipName(wire, offset)
            if (offset + 10 > wire.size) return results
            val rtype = ((wire[offset].toInt() and 0xFF) shl 8) or (wire[offset + 1].toInt() and 0xFF)
            offset += 2
            offset += 2
            offset += 4
            val rdlen = ((wire[offset].toInt() and 0xFF) shl 8) or (wire[offset + 1].toInt() and 0xFF)
            offset += 2
            if (rtype == 16) {
                var pos = offset
                val sb = StringBuilder()
                while (pos < offset + rdlen) {
                    android.util.Log.d("XDBG", "[DnsUtils] loop") //xdbg
                    val slen = wire[pos].toInt() and 0xFF
                    pos++
                    sb.append(String(wire, pos, slen, Charsets.UTF_8))
                    pos += slen
                }
                results.add(sb.toString())
            }
            offset += rdlen
        }
        android.util.Log.d("XDBG", "[DnsUtils] return") //xdbg
        return results
    }

    private fun skipName(data: ByteArray, startOffset: Int): Int {
        var o = startOffset
        while (o < data.size) {
            val len = data[o].toInt() and 0xFF
            if (len == 0) return o + 1
            if ((len and 0xC0) == 0xC0) return o + 2
            o += 1 + len
        }
        return o
    }

    suspend fun querySystemDnsTxt(name: String): List<String> {
        android.util.Log.d("XDBG", "[DnsUtils] querySystemDnsTxt enter name=${name}") //xdbg
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            android.util.Log.d("XDBG", "[DnsUtils] if branch") //xdbg
            return emptyList()
        }
        val t0 = System.currentTimeMillis()
        return suspendCancellableCoroutine { cont ->
            try {
                val resolver = DnsResolver.getInstance()
                val executor = Executor { it.run() }

                val callback = object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) {
                        android.util.Log.d("XDBG", "[DnsUtils] onAnswer enter answer=${answer} rcode=${rcode}") //xdbg
                        val ms = System.currentTimeMillis() - t0
                        val tc = if (answer.size >= 4)
                            (((answer[2].toInt() and 0xFF) shl 8) or (answer[3].toInt() and 0xFF)) and 0x0200 != 0
                        else false
                        if (rcode != 0) {
                            android.util.Log.d("XDBG", "[DnsUtils] if branch") //xdbg
                            cont.resume(emptyList())
                            return
                        }
                        val txts = parseTxtRecords(answer)
                        cont.resume(txts)
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        android.util.Log.d("XDBG", "[DnsUtils] onError enter error=${error}") //xdbg
                        cont.resume(emptyList())
                    }
                }

                if (Build.VERSION.SDK_INT >= 30) {
                    android.util.Log.d("XDBG", "[DnsUtils] if branch") //xdbg
                    val query = buildTxtQuery(name)
                    resolver.rawQuery(null, query, DnsResolver.FLAG_EMPTY, executor, null, callback)
                } else {
                    resolver.rawQuery(null, name, DnsResolver.CLASS_IN, 16,
                        DnsResolver.FLAG_EMPTY, executor, null, callback)
                }
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[DnsUtils] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                cont.resume(emptyList())
            }
        }
    }

    private fun queryOneDoh(resolverUrl: String, name: String): List<String> {
        return try {
            val url = "$resolverUrl?name=$name&type=TXT&random=${System.currentTimeMillis()}"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .build()
            val resp = dohClient.newCall(req).execute()
            val body = resp.body?.string()
            if (!resp.isSuccessful || body.isNullOrEmpty()) return emptyList()
            val json = JSONObject(body)
            if (json.optInt("Status") != 0 || !json.has("Answer")) return emptyList()
            val answers = json.getJSONArray("Answer")
            val result = mutableListOf<String>()
            for (i in 0 until answers.length()) {
                val data = answers.getJSONObject(i).optString("data", "")
                if (data.isNotEmpty()) result.add(data)
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun queryDohParallel(name: String, resolvers: List<String>): List<String> {
        if (resolvers.isEmpty()) return emptyList()
        val done = AtomicBoolean(false)
        return withTimeoutOrNull(5_500L) {
            coroutineScope {
                resolvers.map { url ->
                    async(Dispatchers.IO) {
                        try {
                            val r = queryOneDoh(url, name)
                            if (r.isNotEmpty() && done.compareAndSet(false, true)) r
                            else emptyList()
                        } catch (e: Exception) {
                            emptyList<String>()
                        }
                    }
                }.awaitAll().firstOrNull { it.isNotEmpty() } ?: emptyList()
            }
        } ?: emptyList()
    }

    // Primary: UDP/53 (3 sec timeout). Fallback: all DoH in parallel, first to answer wins.
    suspend fun fetchTxtRecords(name: String, resolvers: List<String>): List<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val udpResult = withTimeoutOrNull(3_000L) {
                querySystemDnsTxt(name)
            } ?: emptyList()
            if (udpResult.isNotEmpty()) return udpResult
        }
        return queryDohParallel(name, resolvers)
    }
}
