package org.kde.kdeconnect.plugins.adbconnection

import android.content.pm.ActivityInfo
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.ScrcpyInputSurfaceView
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.ScrcpySession
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.TouchEventHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScrcpyActivity : AppCompatActivity(), SurfaceHolder.Callback, ScrcpyInputSurfaceView.InputCallbacks {

    private lateinit var surfaceView: ScrcpyInputSurfaceView
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
    private var codecMime = MediaFormat.MIMETYPE_VIDEO_AVC

    private var host: String = ""
    private var port: Int = 0
    private var touchEventHandler: TouchEventHandler? = null
    private val gotOutputFormat = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scrcpy)
        surfaceView = findViewById(R.id.surface_view)
        statusText = findViewById(R.id.status_text)
        disconnectButton = findViewById(R.id.disconnect_button)
        backButton = findViewById(R.id.back_button)
        homeButton = findViewById(R.id.home_button)
        surfaceView.holder.addCallback(this)
        surfaceView.inputCallbacks = this
        surfaceView.setCommitTextEnabled(true)
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

    override fun surfaceCreated(holder: SurfaceHolder) { surface = holder.surface; startScrcpy() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { touchEventHandler?.updateDimensions(width, height) }
    override fun surfaceDestroyed(holder: SurfaceHolder) { stopScrcpy() }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val session = scrcpySession ?: return false
        if (!isRunning || event.keyCode == KeyEvent.KEYCODE_BACK) return false
        session.sendKeyEvent(event.action, event.keyCode, event.metaState)
        return true
    }
    override fun handleCommitText(text: CharSequence): Boolean { return true }
    override fun handleDeleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val session = scrcpySession ?: return false
        if (!isRunning) return false
        repeat(beforeLength.coerceAtLeast(0)) { session.sendKeyEvent(0, KeyEvent.KEYCODE_DEL); session.sendKeyEvent(1, KeyEvent.KEYCODE_DEL) }
        repeat(afterLength.coerceAtLeast(0)) { session.sendKeyEvent(0, KeyEvent.KEYCODE_FORWARD_DEL); session.sendKeyEvent(1, KeyEvent.KEYCODE_FORWARD_DEL) }
        return true
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        val handler = touchEventHandler
        if (handler != null && isRunning) {
            handler.updateDimensions(surfaceView.width, surfaceView.height)
            handler.handleMotionEvent(event)
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun createDecoder(width: Int, height: Int) {
        releaseDecoder()
        try {
            Log.i(TAG, "Creating decoder: ${codecMime} ${width}x${height}")
            val format = MediaFormat.createVideoFormat(codecMime, width, height)
            decoder = MediaCodec.createDecoderByType(codecMime)
            decoder!!.configure(format, surface, null, 0)
            decoder!!.start()
            decoderConfigured = true
        } catch (e: Exception) { Log.e(TAG, "Decoder create failed", e); decoderConfigured = false }
    }

    private fun releaseDecoder() {
        try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
        decoder = null; decoderConfigured = false
    }

    private fun feedPacket(data: ByteArray, ptsUs: Long, isConfig: Boolean, isKeyFrame: Boolean) {
        val d = decoder ?: return
        try {
            var inputIndex = d.dequeueInputBuffer(10_000)
            if (inputIndex < 0 && (isConfig || isKeyFrame)) { drainOutput(); inputIndex = d.dequeueInputBuffer(50_000) }
            if (inputIndex >= 0) {
                val buf = d.getInputBuffer(inputIndex) ?: return
                buf.clear(); buf.put(data)
                var flags = 0
                if (isKeyFrame) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                if (isConfig) flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                d.queueInputBuffer(inputIndex, 0, data.size, ptsUs, flags)
            }
            drainOutput()
        } catch (e: Exception) { Log.e(TAG, "Feed error", e) }
    }

    private fun drainOutput() {
        val d = decoder ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = d.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outIndex >= 0 -> d.releaseOutputBuffer(outIndex, true)
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!gotOutputFormat.getAndSet(true)) {
                        val fmt = d.outputFormat
                        val w = if (fmt.containsKey("crop-right") && fmt.containsKey("crop-left")) fmt.getInteger("crop-right") - fmt.getInteger("crop-left") + 1 else fmt.getInteger(MediaFormat.KEY_WIDTH)
                        val h = if (fmt.containsKey("crop-bottom") && fmt.containsKey("crop-top")) fmt.getInteger("crop-bottom") - fmt.getInteger("crop-top") + 1 else fmt.getInteger(MediaFormat.KEY_HEIGHT)
                        Log.i(TAG, "Output format: ${w}x${h}")
                        if (w > 0 && h > 0) { screenWidth = w; screenHeight = h }
                    }
                    continue
                }
                else -> return
            }
        }
    }

    private fun startScrcpy() {
        Thread {
            try {
                if (host.isEmpty() || port == 0) { runOnUiThread { statusText.text = "Error: Invalid connection parameters" }; return@Thread }
                Log.i(TAG, "Connecting to $host:$port...")
                val session = ScrcpySession(host, port, this@ScrcpyActivity)
                if (!session.start()) { runOnUiThread { statusText.text = "Failed to start scrcpy" }; return@Thread }
                Log.i(TAG, "Scrcpy started: device=${session.deviceName}")
                scrcpySession = session
                screenWidth = session.screenWidth.takeIf { it > 0 } ?: 0
                screenHeight = session.screenHeight.takeIf { it > 0 } ?: 0

                // Turn screen off if requested (like ScrcpyForAndroid)
                val turnScreenOff = getSharedPreferences("kdeconnect_prefs", MODE_PRIVATE).getBoolean("scrcpy_turn_screen_off", false)
                if (turnScreenOff) {
                    Log.i(TAG, "Turning off remote screen...")
                    session.setDisplayPower(false)
                }

                var viewWidth = 0; var viewHeight = 0
                val latch = CountDownLatch(1)
                runOnUiThread { viewWidth = surfaceView.width; viewHeight = surfaceView.height; latch.countDown() }
                latch.await(1, TimeUnit.SECONDS)
                Log.i(TAG, "View: ${viewWidth}x${viewHeight}, Session: ${screenWidth}x${screenHeight}")

                if (screenWidth > 0 && screenHeight > 0) {
                    runOnUiThread { requestedOrientation = if (screenWidth > screenHeight) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                }

                touchEventHandler = TouchEventHandler(
                    sessionWidth = screenWidth.takeIf { it > 0 } ?: 1920,
                    sessionHeight = screenHeight.takeIf { it > 0 } ?: 1080,
                    touchAreaWidth = viewWidth,
                    touchAreaHeight = viewHeight,
                    onInjectTouch = { action, pointerId, x, y, pressure, actionButton, buttons ->
                        val sw = screenWidth.takeIf { it > 0 } ?: 1920; val sh = screenHeight.takeIf { it > 0 } ?: 1080
                        session.sendTouchEvent(action, pointerId, x, y, sw, sh, pressure, actionButton, buttons)
                    },
                    onBackOrScreenOn = { action -> session.sendKeyEvent(action, KeyEvent.KEYCODE_BACK) },
                )
                runOnUiThread { statusText.text = "Connected to ${session.deviceName}"; surfaceView.requestFocus() }
                decodeVideo(session)
            } catch (e: Exception) { Log.e(TAG, "scrcpy failed", e); runOnUiThread { statusText.text = "Error: ${e.message}" } }
        }.start()
    }

    private fun decodeVideo(session: ScrcpySession) {
        isRunning = true
        while (isRunning) {
            val packet = session.readVideoPacket() ?: break
            if (packet.isSession) {
                if (packet.width > 0 && packet.height > 0) {
                    screenWidth = packet.width; screenHeight = packet.height
                    if (decoderConfigured && (packet.width != screenWidth || packet.height != screenHeight)) { gotOutputFormat.set(false); createDecoder(packet.width, packet.height) }
                    else if (!decoderConfigured) { createDecoder(packet.width, packet.height) }
                    runOnUiThread {
                        requestedOrientation = if (screenWidth > screenHeight) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        touchEventHandler?.updateSessionDimensions(screenWidth, screenHeight)
                    }
                }
                continue
            }
            if (packet.data.isEmpty()) continue
            if (!decoderConfigured) { if (screenWidth <= 0 || screenHeight <= 0) continue; createDecoder(screenWidth, screenHeight) }
            feedPacket(packet.data, packet.ptsUs, packet.isConfig, packet.isKeyFrame)
        }
    }

    private fun stopScrcpy() {
        isRunning = false; gotOutputFormat.set(false); releaseDecoder()
        scrcpySession?.stop(); scrcpySession = null; touchEventHandler = null
    }

    override fun onDestroy() { super.onDestroy(); stopScrcpy() }

    companion object {
        private const val TAG = "ScrcpyActivity"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_DEVICE_ID = "device_id"
    }
}
