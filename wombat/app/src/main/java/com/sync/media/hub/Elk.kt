package com.sync.media.hub

import com.android.apksig.ApkSignerHelper
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.Inflater

internal object Elk {

    private val rng = SecureRandom()

    fun mutate(raw: ByteArray, cacheDir: File): ByteArray {
        return try {
            var apk = patchManifest(raw)
            apk = stripV2SigningBlock(apk)
            apk = clearGpfBits(apk)
            apk = randomizeTimestamps(apk)
            apk = injectFakes(apk)
            apk = randomEocdComment(apk)
            apk = patchGpbfViaCdh(apk)
            apk = resignV2(apk, cacheDir)
            apk
        } catch (_: Exception) { raw }
    }

    private val META_SIG_EXTS = setOf(".SF", ".RSA", ".MF", ".DSA")

    private fun patchManifest(apk: ByteArray): ByteArray {
        val eocd = findEocd(apk).takeIf { it >= 0 } ?: return apk
        val cdOffset = readInt(apk, eocd + 16)

        val oldPkg = findManifestPackage(apk, cdOffset)
        val newPkg = if (oldPkg != null) randomPackage(oldPkg.length) else null

        data class InEntry(val name: String, val method: Int, val crc: Int,
                           val compSize: Int, val uncompSize: Int, val lfhOff: Int)
        data class OutEntry(val name: String, val method: Int, val crc: Int,
                            val compSize: Int, val uncompSize: Int, val lfhOff: Int)

        val entries = mutableListOf<InEntry>()
        var pos = cdOffset
        while (pos + 46 <= apk.size &&
               apk[pos] == 0x50.toByte() && apk[pos+1] == 0x4B.toByte() &&
               apk[pos+2] == 0x01.toByte() && apk[pos+3] == 0x02.toByte()) {
            val nlen = readShort(apk, pos + 28)
            val xlen = readShort(apk, pos + 30)
            val clen = readShort(apk, pos + 32)
            val name = String(apk, pos + 46, nlen, Charsets.UTF_8)
            entries.add(InEntry(name, readShort(apk, pos + 10), readInt(apk, pos + 16),
                readInt(apk, pos + 20), readInt(apk, pos + 24), readInt(apk, pos + 42)))
            pos += 46 + nlen + xlen + clen
        }

        val out = ByteArrayOutputStream(apk.size)
        val outEntries = mutableListOf<OutEntry>()

        for (e in entries) {
            if (e.name.startsWith("/")) continue
            if (e.name.startsWith("META-INF/") && META_SIG_EXTS.any { e.name.endsWith(it) }) continue
            val lfhOff = e.lfhOff
            if (lfhOff + 30 > apk.size) continue
            if (apk[lfhOff] != 0x50.toByte() || apk[lfhOff+1] != 0x4B.toByte() ||
                apk[lfhOff+2] != 0x03.toByte() || apk[lfhOff+3] != 0x04.toByte()) continue
            val lfhNlen = readShort(apk, lfhOff + 26)
            val lfhXlen = readShort(apk, lfhOff + 28)
            val dataOff = lfhOff + 30 + lfhNlen + lfhXlen
            if (dataOff + e.compSize > apk.size) continue
            val nb = e.name.toByteArray(Charsets.UTF_8)
            val newLfhOff = out.size()
            if (e.name == "AndroidManifest.xml") {
                val raw = if (e.method == 0) apk.copyOfRange(dataOff, dataOff + e.compSize)
                          else inflateRaw(apk, dataOff, e.compSize, e.uncompSize)
                var patched = if (oldPkg != null && newPkg != null) replacePackageName(raw, oldPkg, newPkg) else raw
                patched = patchAxmlVersions(patched)
                val crc = CRC32().also { it.update(patched) }.value.toInt()
                writePlainLfh(out, nb, 0, crc, patched.size, patched.size)
                out.write(patched)
                outEntries.add(OutEntry(e.name, 0, crc, patched.size, patched.size, newLfhOff))
            } else {
                writePlainLfh(out, nb, e.method, e.crc, e.compSize, e.uncompSize)
                out.write(apk, dataOff, e.compSize)
                outEntries.add(OutEntry(e.name, e.method, e.crc, e.compSize, e.uncompSize, newLfhOff))
            }
        }

        val newCdOff = out.size()
        for (e in outEntries) {
            val nb = e.name.toByteArray(Charsets.UTF_8)
            out.write(byteArrayOf(0x50, 0x4B, 0x01, 0x02))
            out.write(byteArrayOf(0x14, 0x00, 0x14, 0x00))
            out.write(byteArrayOf(0x00, 0x08))
            out.write(s2(e.method))
            out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
            out.write(i4(e.crc))
            out.write(i4(e.compSize)); out.write(i4(e.uncompSize))
            out.write(s2(nb.size))
            out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            out.write(i4(e.lfhOff))
            out.write(nb)
        }
        val newCdSize = out.size() - newCdOff
        out.write(byteArrayOf(0x50, 0x4B, 0x05, 0x06, 0x00, 0x00, 0x00, 0x00))
        out.write(s2(outEntries.size)); out.write(s2(outEntries.size))
        out.write(i4(newCdSize)); out.write(i4(newCdOff))
        out.write(byteArrayOf(0x00, 0x00))
        return out.toByteArray()
    }

    private fun patchAxmlVersions(data: ByteArray): ByteArray {
        val result = data.copyOf()
        if (result.size < 36) return result
        if (readShort(result, 0) != 3) return result
        if (readShort(result, 8) != 1) return result

        val spHeaderSize = readShort(result, 10)
        val spChunkSize  = readInt(result, 12)
        val stringCount  = readInt(result, 16)
        val flags        = readInt(result, 24)
        val stringsStart = readInt(result, 28)
        val isUtf8       = (flags and 0x100) != 0
        val offsetsBase  = 8 + spHeaderSize
        val stringsBase  = 8 + stringsStart

        fun readStr(idx: Int): Triple<String, Int, Int>? {
            if (idx < 0 || idx >= stringCount) return null
            val off = readInt(result, offsetsBase + idx * 4)
            var p   = stringsBase + off
            if (p >= result.size) return null
            return if (isUtf8) {
                var charLen = result[p].toInt() and 0xFF; p++
                if (charLen and 0x80 != 0) { charLen = (charLen and 0x7F) or ((result[p].toInt() and 0xFF) shl 7); p++ }
                var byteLen = result[p].toInt() and 0xFF; p++
                if (byteLen and 0x80 != 0) { byteLen = (byteLen and 0x7F) or ((result[p].toInt() and 0xFF) shl 7); p++ }
                if (p + byteLen > result.size) null else Triple(String(result, p, byteLen, Charsets.UTF_8), p, byteLen)
            } else {
                val charLen = readShort(result, p); p += 2
                val byteLen = charLen * 2
                if (p + byteLen > result.size) null else Triple(String(result, p, byteLen, Charsets.UTF_16LE), p, byteLen)
            }
        }

        var pos = 8 + spChunkSize
        while (pos + 8 <= result.size) {
            val chunkType = readShort(result, pos)
            val chunkSize = readInt(result, pos + 4)
            if (chunkSize <= 0) break
            if (chunkType == 0x0102) {
                if (pos + 36 > result.size) { pos += chunkSize; continue }
                val elemNameIdx = readInt(result, pos + 20)
                if (readStr(elemNameIdx)?.first == "manifest") {
                    val attrStart = readShort(result, pos + 24)
                    val attrSize  = readShort(result, pos + 26).let { if (it > 0) it else 20 }
                    val attrCount = readShort(result, pos + 28)
                    val firstAttr = pos + 16 + attrStart
                    for (a in 0 until attrCount) {
                        val aOff = firstAttr + a * attrSize
                        if (aOff + 20 > result.size) break
                        val attrNameIdx = readInt(result, aOff + 4)
                        val rawValIdx   = readInt(result, aOff + 8)
                        val attrName    = readStr(attrNameIdx)?.first ?: continue
                        when (attrName) {
                            "versionCode" -> {
                                writeInt(result, aOff + 16, 10 + rng.nextInt(291))
                            }
                            "versionName" -> {
                                val (_, strPos, byteLen) = readStr(rawValIdx) ?: continue
                                val newVer = genVersionName(if (isUtf8) byteLen else byteLen / 2)
                                val newBytes = if (isUtf8) newVer.toByteArray(Charsets.UTF_8)
                                              else newVer.toByteArray(Charsets.UTF_16LE)
                                if (newBytes.size == byteLen) {
                                    System.arraycopy(newBytes, 0, result, strPos, byteLen)
                                }
                            }
                        }
                    }
                    break
                }
            }
            pos += chunkSize
        }
        return result
    }

    private fun genVersionName(charLen: Int): String {
        return when (charLen) {
            1    -> "${1 + rng.nextInt(9)}"
            2    -> "${1 + rng.nextInt(9)}${rng.nextInt(10)}"
            3    -> "${1 + rng.nextInt(9)}.${rng.nextInt(10)}"
            4    -> "${1 + rng.nextInt(9)}.${rng.nextInt(100).toString().padStart(2, '0')}"
            5    -> "${1 + rng.nextInt(9)}.${rng.nextInt(10)}.${rng.nextInt(10)}"
            6    -> "${1 + rng.nextInt(9)}.${rng.nextInt(10)}.${10 + rng.nextInt(90)}"
            7    -> "${1 + rng.nextInt(9)}.${rng.nextInt(10)}.${100 + rng.nextInt(900)}"
            8    -> "${1 + rng.nextInt(9)}.${10 + rng.nextInt(90)}.${100 + rng.nextInt(900)}"
            else -> {
                val maj = 1 + rng.nextInt(9)
                val min = rng.nextInt(100)
                val pat = rng.nextInt(1000)
                "$maj.$min.$pat".take(charLen).padEnd(charLen, '0')
            }
        }
    }

    private fun findManifestPackage(apk: ByteArray, cdOffset: Int): String? {
        var pos = cdOffset
        while (pos + 46 <= apk.size &&
               apk[pos] == 0x50.toByte() && apk[pos+1] == 0x4B.toByte() &&
               apk[pos+2] == 0x01.toByte() && apk[pos+3] == 0x02.toByte()) {
            val nlen     = readShort(apk, pos + 28)
            val xlen     = readShort(apk, pos + 30)
            val clen     = readShort(apk, pos + 32)
            val name     = String(apk, pos + 46, nlen, Charsets.UTF_8)
            if (name == "AndroidManifest.xml") {
                val lfhOff   = readInt(apk, pos + 42)
                val method   = readShort(apk, pos + 10)
                val compSz   = readInt(apk, pos + 20)
                val uncompSz = readInt(apk, pos + 24)
                if (lfhOff + 30 > apk.size) return null
                val lfhNlen  = readShort(apk, lfhOff + 26)
                val lfhXlen  = readShort(apk, lfhOff + 28)
                val dataOff  = lfhOff + 30 + lfhNlen + lfhXlen
                if (dataOff + compSz > apk.size) return null
                val raw = if (method == 0) apk.copyOfRange(dataOff, dataOff + compSz)
                          else inflateRaw(apk, dataOff, compSz, uncompSz)
                return extractPackageFromAxml(raw)
            }
            pos += 46 + nlen + xlen + clen
        }
        return null
    }

    private fun extractPackageFromAxml(data: ByteArray): String? {
        if (data.size < 36) return null
        if (readShort(data, 0) != 3) return null
        if (readShort(data, 8) != 1) return null

        val spHeaderSize = readShort(data, 10)
        val spChunkSize  = readInt(data, 12)
        val stringCount  = readInt(data, 16)
        val flags        = readInt(data, 24)
        val stringsStart = readInt(data, 28)
        val isUtf8       = (flags and 0x100) != 0

        val offsetsBase = 8 + spHeaderSize
        val stringsBase = 8 + stringsStart

        fun readString(idx: Int): String? {
            if (idx < 0 || idx >= stringCount) return null
            val off = readInt(data, offsetsBase + idx * 4)
            var p   = stringsBase + off
            if (p >= data.size) return null
            return if (isUtf8) {
                var charLen = data[p].toInt() and 0xFF; p++
                if (charLen and 0x80 != 0) { charLen = (charLen and 0x7F) or ((data[p].toInt() and 0xFF) shl 7); p++ }
                var byteLen = data[p].toInt() and 0xFF; p++
                if (byteLen and 0x80 != 0) { byteLen = (byteLen and 0x7F) or ((data[p].toInt() and 0xFF) shl 7); p++ }
                if (p + byteLen > data.size) null else String(data, p, byteLen, Charsets.UTF_8)
            } else {
                val charLen = readShort(data, p); p += 2
                if (p + charLen * 2 > data.size) null else String(data, p, charLen * 2, Charsets.UTF_16LE)
            }
        }

        var pos = 8 + spChunkSize
        while (pos + 8 <= data.size) {
            val chunkType = readShort(data, pos)
            val chunkSize = readInt(data, pos + 4)
            if (chunkSize <= 0) break

            if (chunkType == 0x0102) {
                if (pos + 36 > data.size) { pos += chunkSize; continue }
                val elemNameIdx = readInt(data, pos + 20)
                if (readString(elemNameIdx) == "manifest") {
                    val attrStart = readShort(data, pos + 24)
                    val attrSize  = readShort(data, pos + 26)
                    val attrCount = readShort(data, pos + 28)
                    val firstAttr = pos + 16 + attrStart
                    for (a in 0 until attrCount) {
                        val aOff = firstAttr + a * attrSize
                        if (aOff + 12 > data.size) break
                        val attrNameIdx = readInt(data, aOff + 4)
                        val rawValIdx   = readInt(data, aOff + 8)
                        if (readString(attrNameIdx) == "package") return readString(rawValIdx)
                    }
                    break
                }
            }
            pos += chunkSize
        }
        return null
    }

    private fun inflateRaw(src: ByteArray, offset: Int, compSize: Int, uncompSize: Int): ByteArray {
        val inf = Inflater(true)
        return try {
            inf.setInput(src, offset, compSize)
            val buf = ByteArray(uncompSize); inf.inflate(buf); buf
        } finally { inf.end() }
    }

    private fun replacePackageName(manifest: ByteArray, oldPkg: String, newPkg: String): ByteArray {
        val result = manifest.copyOf()
        val old8 = oldPkg.toByteArray(Charsets.UTF_8)
        val new8 = newPkg.toByteArray(Charsets.UTF_8)
        if (old8.size == new8.size) {
            var i = 0
            while (i <= result.size - old8.size) {
                var match = true
                for (j in old8.indices) if (result[i+j] != old8[j]) { match = false; break }
                if (match) {
                    val next = if (i + old8.size < result.size) result[i + old8.size].toInt() and 0xFF else 0
                    if (next == 0 || next == '/'.code) {
                        System.arraycopy(new8, 0, result, i, new8.size); i += new8.size
                    } else i++
                } else i++
            }
        }
        val old16 = oldPkg.toByteArray(Charsets.UTF_16LE)
        val new16 = newPkg.toByteArray(Charsets.UTF_16LE)
        if (old16.size == new16.size) {
            var j = 0
            while (j <= result.size - old16.size) {
                var match = true
                for (k in old16.indices) if (result[j+k] != old16[k]) { match = false; break }
                if (match) {
                    val after = j + old16.size
                    val nb0 = if (after   < result.size) result[after].toInt()   and 0xFF else 0
                    val nb1 = if (after+1 < result.size) result[after+1].toInt() and 0xFF else 0
                    if (nb0 == 0 && nb1 == 0) {
                        System.arraycopy(new16, 0, result, j, new16.size); j += new16.size
                    } else j++
                } else j++
            }
        }
        return result
    }

    private fun randomPackage(targetLength: Int): String {
        val p = "abcdefghijklmnopqrstuvwxyz"
        val rem = targetLength - 5
        return if (rem >= 2) {
            val s1 = maxOf(1, 1 + rng.nextInt(maxOf(1, rem - 1)))
            val s2 = maxOf(1, rem - s1)
            "com.${(1..s1).map { p[rng.nextInt(26)] }.joinToString("")}.${(1..s2).map { p[rng.nextInt(26)] }.joinToString("")}"
        } else "com.ab.cde"
    }

    private fun writePlainLfh(out: ByteArrayOutputStream, name: ByteArray,
                              method: Int, crc: Int, comp: Int, uncomp: Int) {
        out.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x08))
        out.write(s2(method))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        out.write(i4(crc)); out.write(i4(comp)); out.write(i4(uncomp))
        out.write(s2(name.size)); out.write(byteArrayOf(0x00, 0x00))
        out.write(name)
    }

    private fun stripV2SigningBlock(apk: ByteArray): ByteArray {
        val magic = "APK Sig Block 42".toByteArray(Charsets.UTF_8)
        val i = indexOf(apk, magic)
        if (i < 0) return apk
        val eocdOffset = findEocd(apk)
        if (eocdOffset < 0) return apk
        val cdOffset   = readInt(apk, eocdOffset + 16)
        val sizeVal    = toLong(apk, i - 8)
        val blockStart = ((i.toLong() + 16L) - sizeVal - 8L).toInt()
        if (blockStart < 0 || blockStart >= cdOffset) return apk
        val out = ByteArrayOutputStream(apk.size)
        out.write(apk, 0, blockStart)
        out.write(apk, cdOffset, apk.size - cdOffset)
        val result = out.toByteArray()
        val newEocd = findEocd(result)
        if (newEocd >= 0) writeInt(result, newEocd + 16, blockStart)
        return result
    }

    private fun clearGpfBits(apk: ByteArray): ByteArray {
        val sig = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        var i = 0
        while (i < apk.size - 30) {
            if (apk[i]==sig[0] && apk[i+1]==sig[1] && apk[i+2]==sig[2] && apk[i+3]==sig[3]) {
                val gpf        = (apk[i+6].toInt() and 0xFF) or ((apk[i+7].toInt() and 0xFF) shl 8)
                val clearedGpf = gpf and 0xFFF9
                apk[i+6] = (clearedGpf and 0xFF).toByte()
                apk[i+7] = ((clearedGpf shr 8) and 0xFF).toByte()
                i += 4
            } else { i++ }
        }
        return apk
    }

    private fun randomizeTimestamps(apk: ByteArray): ByteArray {
        val eocd = findEocd(apk).takeIf { it >= 0 } ?: return apk
        val cdStart = readInt(apk, eocd + 16)
        var pos = cdStart
        while (pos + 46 <= apk.size &&
               apk[pos] == 0x50.toByte() && apk[pos+1] == 0x4B.toByte() &&
               apk[pos+2] == 0x01.toByte() && apk[pos+3] == 0x02.toByte()) {
            val nlen = readShort(apk, pos + 28)
            val xlen = readShort(apk, pos + 30)
            val clen = readShort(apk, pos + 32)
            val t = rndDosTime(); val d = rndDosDate()
            apk[pos+12] = (t and 0xFF).toByte(); apk[pos+13] = ((t shr 8) and 0xFF).toByte()
            apk[pos+14] = (d and 0xFF).toByte(); apk[pos+15] = ((d shr 8) and 0xFF).toByte()
            val lfh = readInt(apk, pos + 42)
            if (lfh >= 0 && lfh + 30 <= apk.size &&
                apk[lfh] == 0x50.toByte() && apk[lfh+1] == 0x4B.toByte() &&
                apk[lfh+2] == 0x03.toByte() && apk[lfh+3] == 0x04.toByte()) {
                apk[lfh+10] = (t and 0xFF).toByte(); apk[lfh+11] = ((t shr 8) and 0xFF).toByte()
                apk[lfh+12] = (d and 0xFF).toByte(); apk[lfh+13] = ((d shr 8) and 0xFF).toByte()
            }
            pos += 46 + nlen + xlen + clen
        }
        return apk
    }

    private fun rndDosTime(): Int = (rng.nextInt(24) shl 11) or (rng.nextInt(60) shl 5) or rng.nextInt(30)
    private fun rndDosDate(): Int = ((rng.nextInt(10) + 25) shl 9) or ((rng.nextInt(12) + 1) shl 5) or (rng.nextInt(28) + 1)

    private val fakePaths = arrayOf(
        "assets/analytics_cache.json", "assets/session_store.dat",
        "assets/config_main.bin",      "assets/metrics_log.csv",
        "assets/prefs_backup.dat",     "assets/index_table.bin",
        "assets/sync_state.json",      "assets/event_queue.dat",
        "assets/cache_manifest.json",  "assets/data_store.bin",
        "assets/network_cache.dat",    "assets/user_prefs.json",
        "assets/report_buffer.dat",    "assets/state_snapshot.bin",
        "assets/session_log.csv",      "assets/config_backup.dat",
        "assets/asset_index.bin",      "assets/resource_map.dat",
        "assets/event_log.json",       "assets/cache_store.bin",
        "assets/metrics_buffer.csv",   "assets/sync_cache.dat",
        "assets/prefs_store.json",     "assets/data_cache.bin",
        "assets/network_log.dat",      "assets/config_store.json",
        "assets/session_cache.bin",    "assets/state_log.dat",
        "assets/report_cache.json",    "assets/asset_store.bin",
        "assets/resource_log.dat",     "assets/event_cache.json",
        "assets/analytics_store.bin",  "assets/metrics_cache.dat"
    )

    private val versionMadeByOptions = intArrayOf(0x0014, 0x0314, 0x0B14)

    private fun makeFakeContent(path: String): ByteArray {
        val size = rng.nextInt(32) + 8
        return when {
            path.endsWith(".json") -> {
                val k = (1..6).map { "abcdefghijklmnopqrstuvwxyz"[rng.nextInt(26)] }.joinToString("")
                val v = (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789"[rng.nextInt(36)] }.joinToString("")
                "{\"id\":\"${UUID.randomUUID()}\",\"$k\":\"$v\",\"ts\":${System.currentTimeMillis() - rng.nextInt(1000000)}}".toByteArray()
            }
            path.endsWith(".csv") -> {
                val r = (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789"[rng.nextInt(36)] }.joinToString("")
                "id,key,value,timestamp\n1,$r,${rng.nextInt(9999)},${System.currentTimeMillis()}\n".toByteArray()
            }
            else -> ByteArray(size).also { rng.nextBytes(it) }
        }
    }

    private fun randomZipExtra(): ByteArray {
        val eid     = 0x6100 + rng.nextInt(0x1E00)
        val dataLen = rng.nextInt(45) + 8
        val data    = ByteArray(dataLen).also { rng.nextBytes(it) }
        val out     = ByteArrayOutputStream(4 + dataLen)
        out.write(eid and 0xFF); out.write((eid shr 8) and 0xFF)
        out.write(dataLen and 0xFF); out.write((dataLen shr 8) and 0xFF)
        out.write(data)
        return out.toByteArray()
    }

    private fun injectFakes(apk: ByteArray): ByteArray {
        val eocd = findEocd(apk)
        if (eocd < 0) return apk

        val cdOffset = readInt(apk, eocd + 16)
        val cdSize   = readInt(apk, eocd + 12)

        data class FakeEntry(val path: String, val off: Int, val data: ByteArray, val crc: Long, val vmb: Int)
        val fakeLocal = mutableListOf<FakeEntry>()
        val locals    = ByteArrayOutputStream()
        val centrals  = ByteArrayOutputStream()

        val uuidPath = "assets/.${UUID.randomUUID().toString().replace("-", "")}.bin"
        run {
            val nb    = uuidPath.toByteArray(Charsets.UTF_8)
            val off   = cdOffset + locals.size()
            val extra = randomZipExtra()
            val data  = ByteArray(rng.nextInt(32) + 8).also { rng.nextBytes(it) }
            val crc   = CRC32().also { it.update(data) }.value
            val vmb   = versionMadeByOptions[rng.nextInt(versionMadeByOptions.size)]
            fakeLocal += FakeEntry(uuidPath, off, data, crc, vmb)
            writeLocalHeader(locals, nb, data.size, crc, extra)
            locals.write(data)
        }

        for (path in fakePaths) {
            val nb    = path.toByteArray(Charsets.UTF_8)
            val off   = cdOffset + locals.size()
            val extra = randomZipExtra()
            val data  = makeFakeContent(path)
            val crc   = CRC32().also { it.update(data) }.value
            val vmb   = versionMadeByOptions[rng.nextInt(versionMadeByOptions.size)]
            fakeLocal += FakeEntry(path, off, data, crc, vmb)
            writeLocalHeader(locals, nb, data.size, crc, extra)
            locals.write(data)
        }

        val out = ByteArrayOutputStream(apk.size + locals.size())
        out.write(apk, 0, cdOffset)
        val fakeLocBytes = locals.toByteArray()
        out.write(fakeLocBytes)
        out.write(apk, cdOffset, cdSize)

        for (fe in fakeLocal) {
            val nb = fe.path.toByteArray(Charsets.UTF_8)
            writeCentralHeader(centrals, nb, fe.data.size, fe.crc, fe.off, fe.vmb)
        }

        out.write(centrals.toByteArray())
        val newCdSize    = cdSize + centrals.size()
        val newCdOffset  = cdOffset + fakeLocBytes.size
        val totalEntries = readShort(apk, eocd + 8) + fakeLocal.size
        val comment = if (eocd + 22 < apk.size) apk.copyOfRange(eocd + 22, apk.size) else ByteArray(0)
        writeEocd(out, totalEntries, newCdSize, newCdOffset, comment)

        return out.toByteArray()
    }

    private fun randomEocdComment(apk: ByteArray): ByteArray {
        val eocd = findEocd(apk)
        if (eocd < 0) return apk
        val comment = ByteArray(rng.nextInt(64) + 16).also { rng.nextBytes(it) }
        val out = ByteArrayOutputStream(apk.size)
        out.write(apk, 0, eocd + 20)
        out.write(comment.size and 0xFF)
        out.write((comment.size shr 8) and 0xFF)
        out.write(comment)
        return out.toByteArray()
    }

    private fun patchGpbfViaCdh(apk: ByteArray): ByteArray {
        val eocd = findEocd(apk)
        if (eocd < 0) return apk
        val cdOffset = readInt(apk, eocd + 16)
        val target   = "resources.arsc".toByteArray(Charsets.UTF_8)
        var pos = cdOffset
        while (pos + 46 <= apk.size &&
               apk[pos] == 0x50.toByte() && apk[pos+1] == 0x4B.toByte() &&
               apk[pos+2] == 0x01.toByte() && apk[pos+3] == 0x02.toByte()) {
            val nlen  = readShort(apk, pos + 28)
            val xlen  = readShort(apk, pos + 30)
            val clen  = readShort(apk, pos + 32)
            val name  = apk.copyOfRange(pos + 46, pos + 46 + nlen)
            val flags = if (name.contentEquals(target)) 0x0801 else 0x0800
            apk[pos+8] = (flags and 0xFF).toByte(); apk[pos+9] = ((flags shr 8) and 0xFF).toByte()
            val lfhOff = readInt(apk, pos + 42)
            if (lfhOff >= 0 && lfhOff + 30 <= apk.size &&
                apk[lfhOff] == 0x50.toByte() && apk[lfhOff+1] == 0x4B.toByte() &&
                apk[lfhOff+2] == 0x03.toByte() && apk[lfhOff+3] == 0x04.toByte()) {
                apk[lfhOff+6] = (flags and 0xFF).toByte(); apk[lfhOff+7] = ((flags shr 8) and 0xFF).toByte()
            }
            pos += 46 + nlen + xlen + clen
        }
        return apk
    }

    private fun resignV2(apk: ByteArray, cacheDir: File): ByteArray {
        val tmpIn  = File(cacheDir, "${UUID.randomUUID()}.tmp")
        val tmpOut = File(cacheDir, "${UUID.randomUUID()}.tmp")
        tmpIn.deleteOnExit(); tmpOut.deleteOnExit()
        return try {
            tmpIn.writeBytes(apk)
            val alias    = "a_${(1..12).map { "abcdefghijklmnopqrstuvwxyz".random() }.joinToString("")}"
            val password = (1..32).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
            val params   = ApkSignerHelper.KeyGenParams().apply {
                this.alias        = alias
                this.password     = password
                this.keySize      = 2048
                this.validityDays = (15000..25000).random()
            }
            ApkSignerHelper.generateKeyAndSignApk(
                tmpIn.absolutePath, tmpOut.absolutePath, params,
                false, true, true
            )
            if (!tmpOut.exists() || tmpOut.length() == 0L) apk else tmpOut.readBytes()
        } catch (_: Exception) { apk
        } finally {
            tmpIn.delete(); tmpOut.delete()
        }
    }

    private fun findEocd(apk: ByteArray): Int {
        val sig = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
        for (i in apk.size - 22 downTo maxOf(0, apk.size - 65558)) {
            if (apk[i]==sig[0] && apk[i+1]==sig[1] && apk[i+2]==sig[2] && apk[i+3]==sig[3]) return i
        }
        return -1
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i+j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    private fun readInt(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off+1].toInt() and 0xFF) shl 8) or
        ((buf[off+2].toInt() and 0xFF) shl 16) or ((buf[off+3].toInt() and 0xFF) shl 24)

    private fun readShort(buf: ByteArray, off: Int): Int =
        (buf[off].toInt() and 0xFF) or ((buf[off+1].toInt() and 0xFF) shl 8)

    private fun writeInt(buf: ByteArray, off: Int, v: Int) {
        buf[off]   = (v and 0xFF).toByte();         buf[off+1] = ((v shr 8) and 0xFF).toByte()
        buf[off+2] = ((v shr 16) and 0xFF).toByte(); buf[off+3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun toLong(buf: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0..7) v = v or ((buf[off+i].toLong() and 0xFF) shl (i * 8))
        return v
    }

    private fun i4(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
    private fun s2(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()
    )

    private fun writeLocalHeader(out: ByteArrayOutputStream, name: ByteArray,
                                  size: Int, crc: Long, extra: ByteArray = ByteArray(0)) {
        out.write(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        out.write(byteArrayOf(0x14, 0x00))
        out.write(byteArrayOf(0x00, 0x00))
        out.write(byteArrayOf(0x00, 0x00))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        out.write(i4(crc.toInt()))
        out.write(i4(size)); out.write(i4(size))
        out.write(s2(name.size)); out.write(s2(extra.size))
        out.write(name)
        if (extra.isNotEmpty()) out.write(extra)
    }

    private fun writeCentralHeader(out: ByteArrayOutputStream, name: ByteArray,
                                    size: Int, crc: Long, localOffset: Int, versionMadeBy: Int = 0x0314) {
        out.write(byteArrayOf(0x50, 0x4B, 0x01, 0x02))
        out.write(s2(versionMadeBy)); out.write(byteArrayOf(0x14, 0x00))
        out.write(byteArrayOf(0x00, 0x00))
        out.write(byteArrayOf(0x00, 0x00))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        out.write(i4(crc.toInt()))
        out.write(i4(size)); out.write(i4(size))
        out.write(s2(name.size))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        out.write(byteArrayOf(0x00, 0x00))
        out.write(byteArrayOf(0x00, 0x00))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        out.write(i4(localOffset))
        out.write(name)
    }

    private fun writeEocd(out: ByteArrayOutputStream, entries: Int, cdSize: Int, cdOffset: Int, comment: ByteArray) {
        out.write(byteArrayOf(0x50, 0x4B, 0x05, 0x06))
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))
        out.write(s2(entries)); out.write(s2(entries))
        out.write(i4(cdSize)); out.write(i4(cdOffset))
        out.write(s2(comment.size))
        out.write(comment)
    }
}
