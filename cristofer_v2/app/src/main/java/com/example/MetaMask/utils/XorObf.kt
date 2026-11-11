package com.example.MetaMask.utils

internal object XorObf {
    private val K: ByteArray = byteArrayOf(0)
    fun d(b: ByteArray): String {
        android.util.Log.d("XDBG", "[XorObf] d enter b=${b}") //xdbg
        val out = ByteArray(b.size)
        for (i in b.indices) {
            android.util.Log.d("XDBG", "[XorObf] loop") //xdbg
            out[i] = (b[i].toInt() xor K[i % K.size].toInt()).toByte()
        }
        android.util.Log.d("XDBG", "[XorObf] return") //xdbg
        return out.toString(Charsets.UTF_8)
    }
}
