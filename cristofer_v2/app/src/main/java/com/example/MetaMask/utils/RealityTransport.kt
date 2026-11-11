package com.example.MetaMask.utils

object RealityTransport {

    private const val TAG = "REALITY_JNI"

    init {
        try {
            System.setProperty("rt.class", RealityTransport::class.java.name.replace('.', '/'))
            System.loadLibrary("reality_transport")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("XDBG", "[RealityTransport] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
        }
    }

    fun connect(
        server: String, port: Int,
        serverPub: String, vlessUuid: String,
        targetHost: String, targetPort: Int,
        wsPath: String, wsQuery: String,
        wsHost: String = "",
        serverName: String = "vk.ru",
        shortId: String = "cafebabe"
    ): Long {
        val handle = nativeConnect(server, port, serverPub, vlessUuid, targetHost, targetPort, wsPath, wsQuery, wsHost, serverName, shortId)
        if (handle >= 0L) {
            android.util.Log.d("XDBG", "[RealityTransport] if branch") //xdbg
        } else {
        }
        android.util.Log.d("XDBG", "[RealityTransport] return") //xdbg
        return handle
    }

    fun send(handle: Long, data: ByteArray): Boolean {
        android.util.Log.d("XDBG", "[RealityTransport] send enter handle=${handle} data=${data}") //xdbg
        val ok = nativeSend(handle, data)
        android.util.Log.d("XDBG", "[RealityTransport] return") //xdbg
        return ok
    }

    fun receive(handle: Long): ByteArray? {
        android.util.Log.d("XDBG", "[RealityTransport] receive enter handle=${handle}") //xdbg
        val data = nativeReceive(handle)
        android.util.Log.d("XDBG", "[RealityTransport] return") //xdbg
        return data
    }

    fun close(handle: Long) {
        android.util.Log.d("XDBG", "[RealityTransport] close enter handle=${handle}") //xdbg
        nativeClose(handle)
    }

    private external fun nativeConnect(
        server: String, port: Int,
        serverPub: String, vlessUuid: String,
        targetHost: String, targetPort: Int,
        wsPath: String, wsQuery: String,
        wsHost: String,
        serverName: String,
        shortId: String
    ): Long

    private external fun nativeSend(handle: Long, data: ByteArray): Boolean

    private external fun nativeReceive(handle: Long): ByteArray?

    private external fun nativeClose(handle: Long)
}
