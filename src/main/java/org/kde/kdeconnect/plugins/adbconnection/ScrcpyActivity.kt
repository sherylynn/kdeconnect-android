package org.kde.kdeconnect.plugins.adbconnection

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.ScrcpySession

class ScrcpyActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var disconnectButton: Button
    private lateinit var backButton: Button
    private lateinit var homeButton: Button

    private var decoder: MediaCodec? = null
    private var scrcpySession: ScrcpySession? = null
    private var surface: Surface? = null
    private var isRunning = false
    private var screenWidth = 0
    private var screenHeight = 0
    private var decoderConfigured = false

    private var host: String = ""
    private var port: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scrcpy)

        surfaceView = findViewById(R.id.surface_view)
        statusText = findViewById(R.id.status_text)
        disconnectButton = findViewById(R.id.disconnect_button)
        backButton = findViewById(R.id.back_button)
        homeButton = findViewById(R.id.home_button)

        surfaceView.holder.addCallback(this)

        disconnectButton.setOnClickListener { stopScrcpy(); finish() }
        backButton.setOnClickListener { scrcpySession?.sendBack() }
        homeButton.setOnClickListener { scrcpySession?.sendHome() }

        host = intent.getStringExtra(EXTRA_HOST) ?: ""
        port = intent.getIntExtra(EXTRA_PORT, 0)

        Log.i(TAG, "onCreate: host=$host, port=$port")
        if (host.isEmpty() || port == 0) {
            statusText.text = "Error: Invalid connection parameters"
            return
        }
        statusText.text = "Connecting to $host:$port..."
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        startScrcpy()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) { stopScrcpy() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isRunning) return super.onTouchEvent(event)
        val session = scrcpySession ?: return super.onTouchEvent(event)
        val sw = session.screenWidth.takeIf { it > 0 } ?: return false
        val sh = session.screenHeight.takeIf { it > 0 } ?: return false
        val viewW = surfaceView.width.toFloat()
        val viewH = surfaceView.height.toFloat()
        if (viewW == 0f || viewH == 0f) return false

        val x = (event.x * sw / viewW).toInt().coerceIn(0, sw - 1)
        val y = (event.y * sh / viewH).toInt().coerceIn(0, sh - 1)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> session.sendTouchEvent(0, event.getPointerId(0).toLong(), x, y, sw, sh)
            MotionEvent.ACTION_UP -> session.sendTouchEvent(1, event.getPointerId(0).toLong(), x, y, sw, sh)
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val px = (event.getX(i) * sw / viewW).toInt().coerceIn(0, sw - 1)
                    val py = (event.getY(i) * sh / viewH).toInt().coerceIn(0, sh - 1)
                    session.sendTouchEvent(2, event.getPointerId(i).toLong(), px, py, sw, sh)
                }
            }
        }
        return true
    }

    private fun startScrcpy() {
        Thread {
            try {
                if (host.isEmpty() || port == 0) {
                    runOnUiThread { statusText.text = "Error: Invalid connection parameters" }
                    return@Thread
                }

                Log.i(TAG, "Creating ScrcpySession connecting to $host:$port...")
                val session = ScrcpySession(host, port, this@ScrcpyActivity)
                val started = session.start()

                if (!started) {
                    runOnUiThread { statusText.text = "Failed to start scrcpy" }
                    return@Thread
                }

                Log.i(TAG, "Scrcpy started: device=${session.deviceName}, codec=0x${String.format("%x", session.videoCodecId)}")
                scrcpySession = session
                screenWidth = session.screenWidth.takeIf { it > 0 } ?: 1920
                screenHeight = session.screenHeight.takeIf { it > 0 } ?: 1080

                runOnUiThread { statusText.text = "Connected to ${session.deviceName}" }

                decodeVideo(session)
            } catch (e: Exception) {
                Log.e(TAG, "scrcpy failed", e)
                runOnUiThread { statusText.text = "Error: ${e.message}" }
            }
        }.start()
    }

    private fun decodeVideo(session: ScrcpySession) {
        isRunning = true
        while (isRunning) {
            val packet = session.readVideoPacket() ?: break
            if (packet.isSession) {
                if (packet.width > 0 && packet.height > 0) {
                    screenWidth = packet.width
                    screenHeight = packet.height
                    reconfigureDecoder()
                }
                continue
            }
            if (packet.data.isEmpty()) continue
            if (!decoderConfigured) {
                if (screenWidth <= 0 || screenHeight <= 0) continue
                setupDecoder()
            }
            feedDecoder(packet.data, packet.ptsUs)
        }
    }

    private fun setupDecoder() {
        try {
            Log.i(TAG, "Setting up decoder: ${screenWidth}x${screenHeight}")
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, screenWidth, screenHeight)
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            decoder!!.configure(format, surface, null, 0)
            decoder!!.start()
            decoderConfigured = true
        } catch (e: Exception) {
            Log.e(TAG, "Decoder setup failed", e)
        }
    }

    private fun reconfigureDecoder() {
        try {
            decoder?.stop()
            decoder?.release()
            decoderConfigured = false
            setupDecoder()
        } catch (e: Exception) {
            Log.e(TAG, "Decoder reconfigure failed", e)
        }
    }

    private fun feedDecoder(data: ByteArray, pts: Long) {
        try {
            val decoder = decoder ?: return
            val inputIndex = decoder.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputIndex) ?: return
                inputBuffer.clear()
                inputBuffer.put(data)
                decoder.queueInputBuffer(inputIndex, 0, data.size, pts, 0)
            }
            val bufferInfo = MediaCodec.BufferInfo()
            val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
            if (outputIndex >= 0) {
                decoder.releaseOutputBuffer(outputIndex, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Feed decoder error", e)
        }
    }

    private fun stopScrcpy() {
        isRunning = false
        decoderConfigured = false
        try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
        decoder = null
        scrcpySession?.stop()
        scrcpySession = null
    }

    override fun onDestroy() { super.onDestroy(); stopScrcpy() }

    companion object {
        private const val TAG = "ScrcpyActivity"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_DEVICE_ID = "device_id"
    }
}
