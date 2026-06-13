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
import java.util.concurrent.LinkedBlockingQueue

/**
 * Audio decoder - ported from Easycontrol_For_Car AudioDecode.java
 * Uses dual-queue sync mechanism with async MediaCodec callback.
 */
class AudioDecode(
    useOpus: Boolean,
    csd0: ByteArray,
    handler: Handler?
) {
    var decodec: MediaCodec
        private set
    var audioTrack: AudioTrack
        private set
    var loudnessEnhancer: LoudnessEnhancer
        private set

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, inIndex: Int) {
            inputBufferQueue.offer(inIndex)
            checkDecode()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, outIndex: Int, info: MediaCodec.BufferInfo) {
            val outputBuf = decodec.getOutputBuffer(outIndex)
            if (outputBuf != null) {
                audioTrack.write(outputBuf, info.size, AudioTrack.WRITE_NON_BLOCKING)
            }
            codec.releaseOutputBuffer(outIndex, false)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {}

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
    }

    init {
        decodec = createDecoder(useOpus, csd0, handler)
        audioTrack = createAudioTrack()
        loudnessEnhancer = createLoudnessEnhancer()
    }

    fun release() {
        try {
            audioTrack.stop()
            audioTrack.release()
            loudnessEnhancer.release()
            decodec.stop()
            decodec.release()
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
            val csd12Buffer = ByteBuffer.wrap(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            format.setByteBuffer("csd-1", csd12Buffer)
            format.setByteBuffer("csd-2", csd12Buffer)
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
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT) * 4
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
                .build()
        } else {
            AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM)
        }
    }

    private fun createLoudnessEnhancer(): LoudnessEnhancer {
        return LoudnessEnhancer(audioTrack.audioSessionId).apply {
            setTargetGain(2000)
            setEnabled(true)
        }
    }
}
