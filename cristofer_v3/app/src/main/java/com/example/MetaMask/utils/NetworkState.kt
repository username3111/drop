package com.example.MetaMask.utils

import android.net.Network

object NetworkState {
    @Volatile private var netId: String = "unset"
    @Volatile private var boundMs: Long = 0L
    @Volatile private var clearedMs: Long = 0L

    fun update(network: Network?, isBound: Boolean) {
        android.util.Log.d("XDBG", "[NetworkState] update enter network=${network} isBound=${isBound}") //xdbg
        if (isBound && network != null) {
            android.util.Log.d("XDBG", "[NetworkState] if branch") //xdbg
            netId = network.toString()
            boundMs = System.currentTimeMillis()
        } else {
            netId = "null"
            clearedMs = System.currentTimeMillis()
        }
    }

    fun describe(): String {
        android.util.Log.d("XDBG", "[NetworkState] describe enter") //xdbg
        val id = netId
        val now = System.currentTimeMillis()
        android.util.Log.d("XDBG", "[NetworkState] return") //xdbg
        return when (id) {
            "unset" -> "!!! UNBOUND (never set)"
            "null"  -> "!!! UNBOUND (cleared ${now - clearedMs}ms ago)"
            else    -> "net=$id (bound ${now - boundMs}ms ago)"
        }
    }
}
