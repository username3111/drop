package com.example.MetaMask.utils

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
        Thread({
            try {
                val srv = RealityConfig.pickServer()
                if (srv == null) {
                    android.util.Log.d("XDBG", "[RealityWebSocket] if branch") //xdbg
                    if (!closed) listener.onFailure(this, java.io.IOException("no REALITY servers"), null)
                    android.util.Log.d("XDBG", "[RealityWebSocket] return") //xdbg
                    return@Thread
                }
                val wsHost = originalRequest.url.host
                val targetHost = RemoteConfig.serverDomain?.split(":")?.first()
                    ?: com.yourcompany.core.jni.NativeBridge.nativeDecryptDomain("IgAFHgUdDhcPAEsfCgY=")

                val pingOk = try {
                    val sock = java.net.Socket()
                    sock.connect(java.net.InetSocketAddress(srv.host, srv.port), 2000)
                    sock.close()
                    true
                } catch (_: Exception) { false }
                if (!pingOk) {
                    if (!closed) {
                        RealityConfig.nextServer()
                        listener.onFailure(this, IOException("REALITY ping failed ${srv.host}:${srv.port}"), null)
                    }
                    return@Thread
                }
                android.util.Log.d("XDBG", "[RealityWebSocket] connect host=${srv.host}:${srv.port}") //xdbg

                val future = java.util.concurrent.FutureTask<Long> {
                    RealityTransport.connect(
                        server      = srv.host,
                        port        = srv.port,
                        serverPub   = srv.publicKey,
                        vlessUuid   = RealityConfig.VLESS_UUID,
                        targetHost  = targetHost,
                        targetPort  = 80,
                        wsPath      = wsPath,
                        wsQuery     = wsQuery,
                        wsHost      = wsHost,
                        serverName  = srv.serverName,
                        shortId     = srv.shortId
                    )
                }
                Thread(future, "reality-connect").apply { isDaemon = true; start() }

                val h = try {
                    future.get(10, java.util.concurrent.TimeUnit.SECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    android.util.Log.e("XDBG", "[RealityWebSocket] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                    future.cancel(true)
                    if (!closed) {
                        android.util.Log.d("XDBG", "[RealityWebSocket] if branch") //xdbg
                        RealityConfig.nextServer()
                        listener.onFailure(this, IOException("REALITY timeout"), null)
                    }
                    android.util.Log.d("XDBG", "[RealityWebSocket] return") //xdbg
                    return@Thread
                } catch (e: Exception) {
                    android.util.Log.e("XDBG", "[RealityWebSocket] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                    if (!closed) {
                        android.util.Log.d("XDBG", "[RealityWebSocket] if branch") //xdbg
                        RealityConfig.nextServer()
                        listener.onFailure(this, IOException(e.message ?: "REALITY exception"), null)
                    }
                    android.util.Log.d("XDBG", "[RealityWebSocket] return") //xdbg
                    return@Thread
                }

                if (h < 0L || closed) {
                    android.util.Log.d("XDBG", "[RealityWebSocket] if branch") //xdbg
                    if (!closed) {
                        android.util.Log.d("XDBG", "[RealityWebSocket] if branch") //xdbg
                        RealityConfig.nextServer()
                        listener.onFailure(this, IOException("REALITY connect failed"), null)
                    }
                    android.util.Log.d("XDBG", "[RealityWebSocket] return") //xdbg
                    return@Thread
                }

                handle = h

                val fakeResponse = Response.Builder()
                    .request(originalRequest)
                    .protocol(Protocol.HTTP_1_1)
                    .code(101)
                    .message("Switching Protocols")
                    .build()
                listener.onOpen(this, fakeResponse)

                var msgCount = 0L
                while (!closed) {
                    android.util.Log.d("XDBG", "[RealityWebSocket] loop") //xdbg
                    val data = RealityTransport.receive(handle) ?: break
                    if (data.isEmpty()) continue
                    msgCount++
                    if (msgCount <= 5L) {
                        android.util.Log.d("XDBG", "[RealityWebSocket] if branch") //xdbg
                    }
                    listener.onMessage(this, String(data, Charsets.UTF_8))
                }

                if (!closed) {
                    android.util.Log.d("XDBG", "[RealityWebSocket] if branch") //xdbg
                    listener.onClosed(this, 1000, "EOF")
                }
            } catch (e: Exception) {
                android.util.Log.e("XDBG", "[RealityWebSocket] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
                if (!closed) {
                    android.util.Log.d("XDBG", "[RealityWebSocket] if branch") //xdbg
                    RealityConfig.nextServer()
                    listener.onFailure(this, e, null)
                }
            }
        }, "reality-ws").apply { isDaemon = true; start() }
    }

    override fun request(): Request = originalRequest

    override fun queueSize(): Long = 0L

    override fun send(text: String): Boolean {
        android.util.Log.d("XDBG", "[RealityWebSocket] send enter text=${text.take(200)}") //xdbg
        val h = handle
        if (closed || h < 0L) {
            android.util.Log.d("XDBG", "[RealityWebSocket] if branch") //xdbg
            android.util.Log.d("XDBG", "[RealityWebSocket] return") //xdbg
            return false
        }
        val ok = try {
            RealityTransport.send(h, text.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[RealityWebSocket] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            false
        }
        android.util.Log.d("XDBG", "[RealityWebSocket] return") //xdbg
        return ok
    }

    override fun send(bytes: ByteString): Boolean = send(bytes.utf8())

    override fun close(code: Int, reason: String?): Boolean {
        android.util.Log.d("XDBG", "[RealityWebSocket] close enter code=${code} reason=${reason}") //xdbg
        if (closed) return true
        closed = true
        val h = handle
        if (h >= 0L) {
            android.util.Log.d("XDBG", "[RealityWebSocket] if branch") //xdbg
            handle = -1L
            RealityTransport.close(h)
        }
        android.util.Log.d("XDBG", "[RealityWebSocket] return") //xdbg
        return true
    }

    override fun cancel() {
        android.util.Log.d("XDBG", "[RealityWebSocket] cancel enter") //xdbg
        closed = true
        val h = handle
        if (h >= 0L) {
            android.util.Log.d("XDBG", "[RealityWebSocket] if branch") //xdbg
            handle = -1L
            RealityTransport.close(h)
        }
    }
}
