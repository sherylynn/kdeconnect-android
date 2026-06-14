package org.kde.kdeconnect.plugins.adbconnection.scrcpy

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

/**
 * Video decoder - ported from Easycontrol_For_Car VideoDecode.java
 * Uses dual-queue (inputDataQueue + inputBufferQueue) sync mechanism
 * with async MediaCodec callback for efficient decoding.
 */
class VideoDecode(
    videoSize: Pair<Int, Int>,
    surface: Surface,
    csd0: Pair<ByteArray, Long>,
    csd1: Pair<ByteArray, Long>?,
    handler: Handler?,
    videoCodecId: Int,
) {
    private var decodec: MediaCodec

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, inIndex: Int) {
            inputBufferQueue.offer(inIndex)
            checkDecode()
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, outIndex: Int, info: MediaCodec.BufferInfo) {
            codec.releaseOutputBuffer(outIndex, info.presentationTimeUs)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {}

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
    }

    init {
        decodec = createDecoder(videoSize, surface, csd0, csd1, handler, videoCodecId)
    }

    fun setSurface(surface: Surface?) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && surface != null) {
                decodec.setOutputSurface(surface)
            }
        } catch (e: Exception) {
            android.util.Log.w("VideoDecode", "setOutputSurface failed", e)
        }
    }

    fun release() {
        try {
            decodec.stop()
            decodec.release()
        } catch (_: Exception) {}
    }

    private val inputDataQueue = LinkedBlockingQueue<Pair<ByteArray, Long>>()
    private val inputBufferQueue = LinkedBlockingQueue<Int>()

    fun decodeIn(data: ByteArray, pts: Long) {
        inputDataQueue.offer(Pair(data, pts))
        checkDecode()
    }

    @Synchronized
    private fun checkDecode() {
        if (inputDataQueue.isEmpty() || inputBufferQueue.isEmpty()) return
        val inIndex = inputBufferQueue.poll() ?: return
        val data = inputDataQueue.poll() ?: return
        val buf = decodec.getInputBuffer(inIndex) ?: return
        buf.put(data.first)
        decodec.queueInputBuffer(inIndex, 0, data.first.size, data.second, 0)
        checkDecode()
    }

    private fun createDecoder(
        videoSize: Pair<Int, Int>,
        surface: Surface,
        csd0: Pair<ByteArray, Long>,
        csd1: Pair<ByteArray, Long>?,
        handler: Handler?,
        videoCodecId: Int,
    ): MediaCodec {
        // Determine codec from videoCodecId, NOT from csd1 presence
        // scrcpy may merge SPS+PPS into a single config frame
        val codecMime = when (videoCodecId) {
            0x68323635 -> MediaFormat.MIMETYPE_VIDEO_HEVC  // h265
            0x61763031 -> MediaFormat.MIMETYPE_VIDEO_AV1    // av1
            else -> MediaFormat.MIMETYPE_VIDEO_AVC          // h264 or default
        }
        val isH265 = codecMime == MediaFormat.MIMETYPE_VIDEO_HEVC

        val codec = MediaCodec.createDecoderByType(codecMime)
        val format = MediaFormat.createVideoFormat(codecMime, videoSize.first, videoSize.second)

        if (isH265 || csd1 == null) {
            // H265 or merged H264 config: use csd0 only
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0.first))
        } else {
            // H264 with separate SPS/PPS
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0.first))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1.first))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && handler != null) {
            codec.setCallback(callback, handler)
        } else {
            codec.setCallback(callback)
        }
        codec.configure(format, surface, null, 0)
        codec.start()
        return codec
    }
}
