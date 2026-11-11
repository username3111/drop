package com.example.MetaMask.utils

object RealityTransport {

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
        android.util.Log.d("XDBG", "[RealityTransport] return") //xdbg
        return nativeConnect(server, port, serverPub, vlessUuid, targetHost, targetPort, wsPath, wsQuery, wsHost, serverName, shortId)
    }

    fun send(handle: Long, data: ByteArray): Boolean = nativeSend(handle, data)

    fun receive(handle: Long): ByteArray? = nativeReceive(handle)

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
