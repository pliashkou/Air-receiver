package com.airreceiver.tv.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * H.264 hardware decoder using MediaCodec async callback API.
 *
 * Uses callbacks instead of polling dequeueOutputBuffer, which avoids
 * timing issues with Amlogic's hardware decoder output scheduling.
 */
class VideoDecoder {
    private val tag = "VideoDecoder"
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private val started = AtomicBoolean(false)

    private var pendingSps: ByteArray? = null
    private var pendingPps: ByteArray? = null
    private var streamWidth = 1920
    private var streamHeight = 1080
    private var frameIndex = 0L
    private var renderedCount = 0L

    /** Called when the first decoded frame is rendered. */
    var onFirstFrameRendered: (() -> Unit)? = null
    /** Called when the decoder reports the actual video dimensions. */
    var onVideoSizeChanged: ((width: Int, height: Int) -> Unit)? = null
    private var firstFrameRendered = false

    // Queue of available input buffer indices (filled by callback)
    private val inputBufferQueue = ArrayBlockingQueue<Int>(16)
    private var callbackThread: HandlerThread? = null

    fun setSurface(s: Surface) {
        surface = s
        // Warm up the Amlogic decoder — first init stalls, second works
        warmupDecoder(s)
    }

    private fun warmupDecoder(surf: Surface) {
        try {
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 320, 240)
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(fmt, surf, null, 0)
            c.start()
            c.stop()
            c.release()
            Log.i(tag, "Decoder warmup complete")
        } catch (e: Exception) {
            Log.w(tag, "Decoder warmup failed: ${e.message}")
        }
    }

    fun setCodecParams(sps: ByteArray, pps: ByteArray) {
        pendingSps = sps
        pendingPps = pps
        if (started.get()) reinitCodec()
    }

    fun setCodecParamsFromStream(payload: ByteArray, width: Int, height: Int) {
        if (payload.size < 8) return
        try {
            if (width > 0 && height > 0) {
                streamWidth = width
                streamHeight = height
            }
            val numSps = payload[5].toInt() and 0x1F
            var pos = 6
            var spsBytes: ByteArray? = null
            for (i in 0 until numSps) {
                if (pos + 2 > payload.size) break
                val spsLen = ((payload[pos].toInt() and 0xFF) shl 8) or (payload[pos + 1].toInt() and 0xFF)
                pos += 2
                if (pos + spsLen <= payload.size) {
                    spsBytes = payload.copyOfRange(pos, pos + spsLen)
                    pos += spsLen
                }
            }
            val numPps = if (pos < payload.size) payload[pos++].toInt() and 0xFF else 0
            var ppsBytes: ByteArray? = null
            for (i in 0 until numPps) {
                if (pos + 2 > payload.size) break
                val ppsLen = ((payload[pos].toInt() and 0xFF) shl 8) or (payload[pos + 1].toInt() and 0xFF)
                pos += 2
                if (pos + ppsLen <= payload.size) {
                    ppsBytes = payload.copyOfRange(pos, pos + ppsLen)
                    pos += ppsLen
                }
            }
            if (spsBytes != null && ppsBytes != null) {
                val spsChanged = !spsBytes.contentEquals(pendingSps)
                val ppsChanged = !ppsBytes.contentEquals(pendingPps)
                pendingSps = spsBytes
                pendingPps = ppsBytes
                if (started.get() && (spsChanged || ppsChanged)) {
                    Log.i(tag, "SPS/PPS changed — reinitializing codec")
                    reinitCodec()
                }
                Log.i(tag, "Codec params: ${streamWidth}x$streamHeight SPS=${spsBytes.size} PPS=${ppsBytes.size} changed=${spsChanged||ppsChanged}")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to parse codec info: ${e.message}")
        }
    }

    private fun initCodec() {
        val surf = surface ?: run {
            Log.e(tag, "Surface not set, cannot init codec")
            return
        }
        try {
            Log.i(tag, "initCodec: ${streamWidth}x$streamHeight SPS=${pendingSps?.size} PPS=${pendingPps?.size}")
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, streamWidth, streamHeight)
            val startCode = byteArrayOf(0, 0, 0, 1)
            pendingSps?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(startCode + it)) }
            pendingPps?.let { format.setByteBuffer("csd-1", ByteBuffer.wrap(startCode + it)) }
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)

            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

            // Set up async callback for output frames
            val ht = HandlerThread("VideoDecoderCB").also { it.start() }
            callbackThread = ht
            inputBufferQueue.clear()

            c.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                    inputBufferQueue.offer(index)
                }

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try {
                        codec.releaseOutputBuffer(index, true)
                        renderedCount++
                        if (!firstFrameRendered) {
                            firstFrameRendered = true
                            Log.i(tag, "First frame rendered to surface!")
                            onFirstFrameRendered?.invoke()
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "releaseOutputBuffer error: ${e.message}")
                    }
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    val w = format.getInteger(MediaFormat.KEY_WIDTH)
                    val h = format.getInteger(MediaFormat.KEY_HEIGHT)
                    Log.i(tag, "Output format changed: ${w}x$h")
                    onVideoSizeChanged?.invoke(w, h)
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(tag, "Codec error: ${e.message}")
                }
            }, Handler(ht.looper))

            c.configure(format, surf, null, 0)
            c.start()
            codec = c
            started.set(true)
            frameIndex = 0
            renderedCount = 0
            firstFrameRendered = false
            Log.i(tag, "MediaCodec H.264 decoder started (${streamWidth}x$streamHeight) [async mode]")
        } catch (e: Exception) {
            Log.e(tag, "Failed to init codec: ${e.message}")
        }
    }

    private fun reinitCodec() {
        stop()
        initCodec()
    }

    fun decodeFrame(data: ByteArray, presentationTimeUs: Long = 0) {
        if (!started.get()) initCodec()
        val c = codec ?: return

        try {
            val pts = System.nanoTime() / 1000
            frameIndex++

            // Wait for an available input buffer (from async callback)
            val timeout = if (frameIndex <= 3) 500L else 10L // milliseconds
            val inputIdx = inputBufferQueue.poll(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (inputIdx != null) {
                val buf = c.getInputBuffer(inputIdx) ?: return
                buf.clear()
                buf.put(data)
                c.queueInputBuffer(inputIdx, 0, data.size, pts, 0)
            } else if (frameIndex % 30 == 0L) {
                Log.w(tag, "No input buffer (frame=$frameIndex)")
            }

            if (frameIndex <= 5 || frameIndex % 100 == 0L) {
                val nalType = if (data.size > 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte())
                    data[4].toInt() and 0x1F else -1
                Log.d(tag, "Frame #$frameIndex nal=$nalType size=${data.size} rendered=$renderedCount queued=${inputIdx != null}")
            }
        } catch (e: Exception) {
            Log.e(tag, "Decode error: ${e.message}")
        }
    }

    fun flush() {
        codec?.flush()
    }

    fun stop() {
        if (started.compareAndSet(true, false)) {
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) {
                Log.e(tag, "Stop error: ${e.message}")
            }
            codec = null
            callbackThread?.quitSafely()
            callbackThread = null
            inputBufferQueue.clear()
        }
    }
}
