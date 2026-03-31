package com.airreceiver.tv.media

import android.media.*
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AAC-ELD decoder + AudioTrack player.
 *
 * MediaCodec decodes compressed AAC-ELD frames received from AirPlay into PCM,
 * which is written to an AudioTrack for output.
 */
class AudioPlayer(
    private val sampleRate: Int = 44100,
    private val channels: Int = 2
) {
    private val tag = "AudioPlayer"
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private val started = AtomicBoolean(false)

    private fun init() {
        try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels
            )
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(format, null, null, 0)
            c.start()
            codec = c

            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            track = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                bufferSize * 4,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            track?.play()
            started.set(true)
            Log.i(tag, "AudioPlayer started ($sampleRate Hz, $channels ch)")
        } catch (e: Exception) {
            Log.e(tag, "Failed to init audio: ${e.message}")
        }
    }

    fun decodeAndPlay(data: ByteArray) {
        if (!started.get()) init()
        val c = codec ?: return
        val t = track ?: return

        try {
            val inputIdx = c.dequeueInputBuffer(10_000)
            if (inputIdx >= 0) {
                val buf = c.getInputBuffer(inputIdx) ?: return
                buf.clear()
                buf.put(data)
                c.queueInputBuffer(inputIdx, 0, data.size, System.nanoTime() / 1000, 0)
            }

            val info = MediaCodec.BufferInfo()
            var outputIdx = c.dequeueOutputBuffer(info, 0)
            while (outputIdx >= 0) {
                val outBuf = c.getOutputBuffer(outputIdx) ?: break
                val pcm = ByteArray(info.size)
                outBuf.get(pcm)
                t.write(pcm, 0, pcm.size)
                c.releaseOutputBuffer(outputIdx, false)
                outputIdx = c.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            Log.e(tag, "Audio decode error: ${e.message}")
        }
    }

    fun stop() {
        if (started.compareAndSet(true, false)) {
            runCatching { codec?.stop(); codec?.release() }
            runCatching { track?.stop(); track?.release() }
            codec = null
            track = null
        }
    }
}
