package org.kde.kdeconnect.plugins.adbconnection

import android.app.Activity
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.ScrcpyInputTextureView
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.ScrcpySession
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.TouchEventHandler
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ScrcpyFloatingActivity : Activity(), ScrcpyInputTextureView.InputCallbacks, ScrcpyInputTextureView.SurfaceCallback {

    companion object {
        private const val TAG = "ScrcpyFloating"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PREFS_NAME = "prefs_name"
    }

    private lateinit var surfaceView: ScrcpyInputTextureView
    private lateinit var dragBar: View
    private lateinit var navBar: LinearLayout
    private lateinit var controlBar: GridLayout

    private var scrcpySession: ScrcpySession? = null
    private var decoder: MediaCodec? = null
    private var surface: Surface? = null
    private var isRunning = false
    private var screenWidth = 0
    private var screenHeight = 0
    private var decoderConfigured = false
    private var codecMime = MediaFormat.MIMETYPE_VIDEO_AVC
    private val inputBufferQueue = LinkedBlockingQueue<Int>()
    private var decoderHandlerThread: android.os.HandlerThread? = null
    private var decoderHandler: Handler? = null
    private var touchEventHandler: TouchEventHandler? = null
    private var host = ""
    private var port = 0
    private var prefsName = ""

    private val decoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            inputBufferQueue.offer(index)
        }
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try { codec.releaseOutputBuffer(index, true) } catch (_: Exception) {}
        }
        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Decoder error", e)
        }
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            val w = if (format.containsKey("crop-right") && format.containsKey("crop-left"))
                format.getInteger("crop-right") - format.getInteger("crop-left") + 1
            else format.getInteger(MediaFormat.KEY_WIDTH)
            val h = if (format.containsKey("crop-bottom") && format.containsKey("crop-top"))
                format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
            else format.getInteger(MediaFormat.KEY_HEIGHT)
            if (w > 0 && h > 0) { screenWidth = w; screenHeight = h }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = intent.getStringExtra(EXTRA_HOST) ?: ""
        port = intent.getIntExtra(EXTRA_PORT, 0)
        prefsName = intent.getStringExtra(EXTRA_PREFS_NAME) ?: ""
        if (host.isEmpty() || port == 0) { finish(); return }

        setContentView(R.layout.activity_scrcpy_floating)

        surfaceView = findViewById(R.id.floatingSurfaceView)
        dragBar = findViewById(R.id.dragBar)
        navBar = findViewById(R.id.floatingNavBar)
        controlBar = findViewById(R.id.controlBar)

        surfaceView.inputCallbacks = this
        surfaceView.surfaceCallback = this
        surfaceView.setCommitTextEnabled(true)

        setupFloatingWindow()
        setupDragBar()
        setupControlBar()
    }

    private fun setupFloatingWindow() {
        window.setGravity(Gravity.TOP or Gravity.START)
        window.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setBackgroundDrawableResource(android.R.color.transparent)

        val lp = window.attributes
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 50
        lp.y = 200
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            lp.type = WindowManager.LayoutParams.TYPE_PHONE
        }
        window.attributes = lp
    }

    private fun setupDragBar() {
        var lastX = 0
        var lastY = 0
        var initialX = 0
        var initialY = 0
        var isDragging = false

        dragBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val loc = IntArray(2)
                    window.decorView.getLocationOnScreen(loc)
                    initialX = loc[0]
                    initialY = loc[1]
                    lastX = event.rawX.toInt()
                    lastY = event.rawY.toInt()
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - lastX
                    val dy = event.rawY.toInt() - lastY
                    if (!isDragging && (dx * dx + dy * dy > 625)) isDragging = true
                    if (isDragging) {
                        val lp = window.attributes
                        lp.x = initialX + dx
                        lp.y = initialY + dy
                        window.attributes = lp
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) toggleControlBar()
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleControlBar() {
        val visible = controlBar.visibility == View.GONE
        controlBar.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            navBar.visibility = View.GONE
        }
    }

    private fun setupControlBar() {
        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            scrcpySession?.sendBack()
        }
        findViewById<ImageView>(R.id.btnHome).setOnClickListener {
            scrcpySession?.sendHome()
        }
        findViewById<ImageView>(R.id.btnSwitch).setOnClickListener {
            scrcpySession?.sendKeyEvent(0, KeyEvent.KEYCODE_APP_SWITCH)
            scrcpySession?.sendKeyEvent(1, KeyEvent.KEYCODE_APP_SWITCH)
        }
        findViewById<ImageView>(R.id.btnPower).setOnClickListener {
            scrcpySession?.lockDevice()
        }
        findViewById<ImageView>(R.id.btnFullscreen).setOnClickListener {
            val intent = Intent(this, ScrcpyActivity::class.java).apply {
                putExtra(ScrcpyActivity.EXTRA_HOST, host)
                putExtra(ScrcpyActivity.EXTRA_PORT, port)
                putExtra(ScrcpyActivity.EXTRA_PREFS_NAME, prefsName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            stopScrcpy()
            finish()
        }
        findViewById<ImageView>(R.id.btnNavBar).setOnClickListener {
            navBar.visibility = if (navBar.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        findViewById<ImageView>(R.id.btnClose).setOnClickListener {
            stopScrcpy()
            finish()
        }
    }

    override fun onSurfaceReady(surface: Surface) {
        this.surface = surface
        startScrcpy()
    }

    override fun onSurfaceDestroyed() { stopScrcpy() }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        if (!isRunning || event.keyCode == KeyEvent.KEYCODE_BACK) return false
        scrcpySession?.sendKeyEvent(event.action, event.keyCode, event.metaState)
        return true
    }
    override fun handleCommitText(text: CharSequence): Boolean = true
    override fun handleDeleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val s = scrcpySession ?: return false
        if (!isRunning) return false
        repeat(beforeLength.coerceAtLeast(0)) { s.sendKeyEvent(0, KeyEvent.KEYCODE_DEL); s.sendKeyEvent(1, KeyEvent.KEYCODE_DEL) }
        repeat(afterLength.coerceAtLeast(0)) { s.sendKeyEvent(0, KeyEvent.KEYCODE_FORWARD_DEL); s.sendKeyEvent(1, KeyEvent.KEYCODE_FORWARD_DEL) }
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val handler = touchEventHandler
        if (handler != null && isRunning) {
            handler.updateDimensions(surfaceView.width, surfaceView.height)
            val location = IntArray(2)
            surfaceView.getLocationOnScreen(location)
            event.offsetLocation(-location[0].toFloat(), -location[1].toFloat())
            handler.handleMotionEvent(event)
            event.offsetLocation(location[0].toFloat(), location[1].toFloat())
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun startScrcpy() {
        Thread {
            try {
                val client = AdbConnectionPlugin.sharedAdbClient ?: run {
                    Log.e(TAG, "ADB client not available, aborting scrcpy session")
                    finish()
                    return@Thread
                }
                val session = ScrcpySession(client, this, prefsName)
                if (!session.start()) { finish(); return@Thread }
                scrcpySession = session
                screenWidth = session.screenWidth.takeIf { it > 0 } ?: 0
                screenHeight = session.screenHeight.takeIf { it > 0 } ?: 0

                var viewW = 0; var viewH = 0
                runOnUiThread { viewW = surfaceView.width; viewH = surfaceView.height }

                if (screenWidth > 0 && screenHeight > 0) {
                    runOnUiThread { surfaceView.setVideoDimensions(screenWidth, screenHeight) }
                }

                touchEventHandler = TouchEventHandler(
                    sessionWidth = screenWidth.takeIf { it > 0 } ?: 1920,
                    sessionHeight = screenHeight.takeIf { it > 0 } ?: 1080,
                    touchAreaWidth = viewW,
                    touchAreaHeight = viewH,
                    onInjectTouch = { action, pointerId, x, y, pressure, actionButton, buttons ->
                        val sw = screenWidth.takeIf { it > 0 } ?: 1920
                        val sh = screenHeight.takeIf { it > 0 } ?: 1080
                        session.sendTouchEvent(action, pointerId, x, y, sw, sh, pressure, actionButton, buttons)
                    },
                    onBackOrScreenOn = { action -> session.sendKeyEvent(action, KeyEvent.KEYCODE_BACK) },
                )

                decodeVideo(session)
            } catch (e: Exception) { Log.e(TAG, "scrcpy failed", e); finish() }
        }.start()
    }

    private fun decodeVideo(session: ScrcpySession) {
        isRunning = true
        var lastPacketTime = System.currentTimeMillis()
        var waitingForKeyFrame = false
        while (isRunning) {
            val packet = session.readVideoPacket() ?: break
            val now = System.currentTimeMillis()
            val gapMs = now - lastPacketTime

            if (packet.isSession) {
                if (packet.width > 0 && packet.height > 0) {
                    val newW = packet.width; val newH = packet.height
                    val sizeChanged = decoderConfigured && (newW != screenWidth || newH != screenHeight)
                    screenWidth = newW; screenHeight = newH
                    if (sizeChanged || !decoderConfigured) {
                        createDecoder(newW, newH)
                        waitingForKeyFrame = true
                    }
                }
                lastPacketTime = now
                continue
            }

            if (gapMs > 200 && decoderConfigured) {
                createDecoder(screenWidth, screenHeight)
                waitingForKeyFrame = true
            }
            lastPacketTime = now

            if (packet.data.isEmpty()) continue
            if (!decoderConfigured) {
                if (screenWidth <= 0 || screenHeight <= 0) continue
                createDecoder(screenWidth, screenHeight)
                waitingForKeyFrame = true
            }
            if (waitingForKeyFrame && !packet.isKeyFrame && !packet.isConfig) continue
            if (packet.isKeyFrame) waitingForKeyFrame = false
            feedPacket(packet.data, packet.ptsUs, packet.isConfig, packet.isKeyFrame)
        }
    }

    private fun createDecoder(width: Int, height: Int) {
        releaseDecoder()
        try {
            decoderHandlerThread = android.os.HandlerThread("floating_decoder").also { it.start() }
            decoderHandler = Handler(decoderHandlerThread!!.looper)
            val format = MediaFormat.createVideoFormat(codecMime, width, height)
            decoder = MediaCodec.createDecoderByType(codecMime)
            decoder!!.setCallback(decoderCallback, decoderHandler)
            decoder!!.configure(format, surface, null, 0)
            decoder!!.start()
            decoderConfigured = true
        } catch (e: Exception) { Log.e(TAG, "Decoder create failed", e); decoderConfigured = false }
    }

    private fun releaseDecoder() {
        inputBufferQueue.clear()
        try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
        decoder = null; decoderConfigured = false
        decoderHandlerThread?.quitSafely()
        decoderHandlerThread = null
        decoderHandler = null
    }

    private fun feedPacket(data: ByteArray, ptsUs: Long, isConfig: Boolean, isKeyFrame: Boolean) {
        val d = decoder ?: return
        try {
            var inputIndex = inputBufferQueue.poll()
            if (inputIndex == null && (isConfig || isKeyFrame)) {
                inputIndex = inputBufferQueue.poll(50, TimeUnit.MILLISECONDS)
            }
            if (inputIndex != null) {
                val buf = d.getInputBuffer(inputIndex) ?: return
                buf.clear(); buf.put(data)
                var flags = 0
                if (isKeyFrame) flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                if (isConfig) flags = flags or MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                d.queueInputBuffer(inputIndex, 0, data.size, ptsUs, flags)
            }
        } catch (e: Exception) { Log.e(TAG, "Feed error", e) }
    }

    private fun stopScrcpy() {
        isRunning = false
        releaseDecoder()
        scrcpySession?.stop(); scrcpySession = null; touchEventHandler = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopScrcpy()
    }
}
