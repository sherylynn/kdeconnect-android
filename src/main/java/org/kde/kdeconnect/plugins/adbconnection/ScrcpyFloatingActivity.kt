package org.kde.kdeconnect.plugins.adbconnection

import android.app.Activity
import android.content.Intent
import android.graphics.PixelFormat
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
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.ScrcpyInputSurfaceView
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.ScrcpySession
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.TouchEventHandler
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ScrcpyFloatingActivity : Activity(), SurfaceHolder.Callback, ScrcpyInputSurfaceView.InputCallbacks {

    companion object {
        private const val TAG = "ScrcpyFloating"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PREFS_NAME = "prefs_name"
    }

    private lateinit var surfaceView: ScrcpyInputSurfaceView
    private lateinit var dragBar: View
    private lateinit var navBar: LinearLayout
    private lateinit var controlBar: GridLayout
    private lateinit var editText: EditText

    private var scrcpySession: ScrcpySession? = null
    private var decoder: MediaCodec? = null
    private var surface: Surface? = null
    private var isRunning = false
    private var screenWidth = 0
    private var screenHeight = 0
    private var decoderConfigured = false
    private var codecMime = MediaFormat.MIMETYPE_VIDEO_AVC
    private val inputBufferQueue = LinkedBlockingQueue<Int>()
    private val decoderHandler = Handler(Looper.getMainLooper())
    private var touchEventHandler: TouchEventHandler? = null
    private var host = ""
    private var port = 0
    private var prefsName = ""

    // Floating window
    private lateinit var windowManager: WindowManager
    private lateinit var layoutParams: WindowManager.LayoutParams
    private var controlBarVisible = false

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

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        setContentView(R.layout.activity_scrcpy_floating)

        surfaceView = findViewById(R.id.floatingSurfaceView)
        dragBar = findViewById(R.id.dragBar)
        navBar = findViewById(R.id.floatingNavBar)
        controlBar = findViewById(R.id.controlBar)
        editText = findViewById(R.id.editText)

        surfaceView.holder.addCallback(this)
        surfaceView.inputCallbacks = this
        surfaceView.setCommitTextEnabled(true)

        setupFloatingWindow()
        setupDragBar()
        setupControlBar()
    }

    private fun setupFloatingWindow() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = 50
            y = 200
        }

        try {
            windowManager.addView(window.decorView, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating window", e)
            finish()
        }
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
                    initialX = layoutParams.x
                    initialY = layoutParams.y
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
                        layoutParams.x = initialX + (event.rawX.toInt() - lastX)
                        layoutParams.y = initialY + (event.rawY.toInt() - lastY)
                        try { windowManager.updateViewLayout(window.decorView, layoutParams) } catch (_: Exception) {}
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
        controlBarVisible = !controlBarVisible
        controlBar.visibility = if (controlBarVisible) View.VISIBLE else View.GONE
        navBar.visibility = if (controlBarVisible && navBar.tag == "visible") View.VISIBLE else View.GONE
    }

    private fun setupControlBar() {
        val session = scrcpySession
        findViewById<ImageView>(R.id.btnBack).setOnClickListener {
            session?.sendBack()
        }
        findViewById<ImageView>(R.id.btnHome).setOnClickListener {
            session?.sendHome()
        }
        findViewById<ImageView>(R.id.btnSwitch).setOnClickListener {
            session?.sendKeyEvent(0, KeyEvent.KEYCODE_APP_SWITCH)
            session?.sendKeyEvent(1, KeyEvent.KEYCODE_APP_SWITCH)
        }
        findViewById<ImageView>(R.id.btnRotate).setOnClickListener {
            session?.rotateDevice()
        }
        findViewById<ImageView>(R.id.btnPower).setOnClickListener {
            session?.lockDevice()
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
            navBar.tag = if (navBar.visibility == View.VISIBLE) "visible" else null
        }
        findViewById<ImageView>(R.id.btnClose).setOnClickListener {
            stopScrcpy()
            finish()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        startScrcpy()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        touchEventHandler?.updateDimensions(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) { stopScrcpy() }

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
                val session = ScrcpySession(host, port, this, prefsName)
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

            if (packet.isSession) {
                if (packet.width > 0 && packet.height > 0) {
                    val newW = packet.width; val newH = packet.height
                    val sizeChanged = decoderConfigured && (newW != screenWidth || newH != screenHeight)
                    val wasGapped = now - lastPacketTime > 300
                    screenWidth = newW; screenHeight = newH
                    if (sizeChanged || !decoderConfigured || wasGapped) {
                        createDecoder(newW, newH)
                        waitingForKeyFrame = true
                    }
                }
                lastPacketTime = now
                continue
            }

            if (decoderConfigured && (now - lastPacketTime > 300)) {
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
        try { windowManager.removeView(window.decorView) } catch (_: Exception) {}
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Keep floating when user navigates away
    }
}
