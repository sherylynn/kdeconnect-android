package org.kde.kdeconnect.plugins.adbconnection.scrcpy

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue

/**
 * Audio decoder - ported from Easycontrol_For_Car AudioDecode.java
 * Uses dual-queue sync mechanism with async MediaCodec callback.
 *
 * CRITICAL: audioTrack must be created BEFORE decoder to avoid NPE
 * in onOutputBufferAvailable callback triggered by codec.start().
 */
class AudioDecode(
    useOpus: Boolean,
    csd0: ByteArray,
    handler: Handler?
) {
    private var decodec: MediaCodec
    private var audioTrack: AudioTrack
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, inIndex: Int) {
            inputBufferQueue.offer(inIndex)
            checkDecode()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, outIndex: Int, info: MediaCodec.BufferInfo) {
            if (info.size > 0) {
                val track = audioTrack
                val outputBuf = codec.getOutputBuffer(outIndex)
                if (outputBuf != null) {
                    track.write(outputBuf, info.size, AudioTrack.WRITE_NON_BLOCKING)
                }
            }
            codec.releaseOutputBuffer(outIndex, false)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            android.util.Log.w(TAG, "Audio decoder error", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
    }

    init {
        // 1. Create audioTrack FIRST (before decoder callback can fire)
        audioTrack = createAudioTrack()
        // 2. Create loudnessEnhancer (depends on audioTrack)
        loudnessEnhancer = createLoudnessEnhancer()
        // 3. Create decoder LAST (callback may fire immediately on start)
        decodec = createDecoder(useOpus, csd0, handler)
    }

    fun release() {
        try {
            decodec.stop()
        } catch (_: Exception) {}
        try {
            decodec.release()
        } catch (_: Exception) {}
        try {
            audioTrack.stop()
        } catch (_: Exception) {}
        try {
            audioTrack.release()
        } catch (_: Exception) {}
        try {
            loudnessEnhancer?.release()
        } catch (_: Exception) {}
    }

    fun playAudio(play: Boolean) {
        if (play) {
            audioTrack.flush()
            audioTrack.play()
        } else {
            audioTrack.pause()
        }
    }

    private val inputDataQueue = LinkedBlockingQueue<ByteArray>()
    private val inputBufferQueue = LinkedBlockingQueue<Int>()

    fun decodeIn(data: ByteArray) {
        inputDataQueue.offer(data)
        checkDecode()
    }

    @Synchronized
    private fun checkDecode() {
        if (inputDataQueue.isEmpty() || inputBufferQueue.isEmpty()) return
        val inIndex = inputBufferQueue.poll() ?: return
        val data = inputDataQueue.poll() ?: return
        val buf = decodec.getInputBuffer(inIndex) ?: return
        buf.put(data)
        decodec.queueInputBuffer(inIndex, 0, data.size, 0, 0)
        checkDecode()
    }

    private fun createDecoder(useOpus: Boolean, csd0: ByteArray, handler: Handler?): MediaCodec {
        val codecMime = if (useOpus) MediaFormat.MIMETYPE_AUDIO_OPUS else MediaFormat.MIMETYPE_AUDIO_AAC
        val codec = MediaCodec.createDecoderByType(codecMime)
        val sampleRate = 48000
        val channelCount = 2
        val bitRate = 96000
        val format = MediaFormat.createAudioFormat(codecMime, sampleRate, channelCount)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
        if (useOpus) {
            // OpusHead format: "OpusHead"(8) + version(1) + channels(1) + preSkip(2 LE) + sampleRate(4 LE) + ...
            // csd-1 = codecDelayNs, csd-2 = seekPrerollNs
            if (csd0.size >= 12) {
                val preSkip = ((csd0[11].toInt() and 0xFF) shl 8) or (csd0[10].toInt() and 0xFF)
                val codecDelayNs = preSkip.toLong() * 1_000_000_000L / sampleRate
                format.setByteBuffer("csd-1", longBuffer(codecDelayNs))
                format.setByteBuffer("csd-2", longBuffer(OPUS_SEEK_PREROLL_NS))
            } else {
                // fallback to zeros if OpusHead is malformed
                val zero8 = ByteBuffer.wrap(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
                format.setByteBuffer("csd-1", zero8)
                format.setByteBuffer("csd-2", zero8)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && handler != null) {
            codec.setCallback(callback, handler)
        } else {
            codec.setCallback(callback)
        }
        codec.configure(format, null, null, 0)
        codec.start()
        return codec
    }

    @Suppress("DEPRECATION")
    private fun createAudioTrack(): AudioTrack {
        val sampleRate = 48000
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(1) * 4
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                .build()
        } else {
            AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM)
        }
    }

    private fun createLoudnessEnhancer(): LoudnessEnhancer? {
        return try {
            LoudnessEnhancer(audioTrack.audioSessionId).apply {
                setTargetGain(2000)
                setEnabled(true)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to create LoudnessEnhancer", e)
            null
        }
    }

    private fun longBuffer(value: Long): ByteBuffer =
        ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply { putLong(value); flip() }

    companion object {
        private const val TAG = "AudioDecode"
        private const val OPUS_SEEK_PREROLL_NS = 80_000_000L // 80 ms
    }
}
