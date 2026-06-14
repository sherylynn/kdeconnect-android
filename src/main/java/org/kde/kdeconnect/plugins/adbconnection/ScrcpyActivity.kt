package org.kde.kdeconnect.plugins.adbconnection

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.Toast
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.ScrcpyInputTextureView
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.ScrcpySession
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.TouchEventHandler
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.VideoDecode
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.AudioDecode

/**
 * Scrcpy remote control activity.
 * Architecture ported from Easycontrol_For_Car Client.java:
 * - status: 0=initial, 1=running, -1=stopped
 * - executeStreamVideo: video decode thread
 * - executeStreamIn: audio/control stream thread
 * - keepAliveThread: heartbeat detection
 * - release: ordered cleanup
 */
class ScrcpyActivity : AppCompatActivity(), ScrcpyInputTextureView.InputCallbacks, ScrcpyInputTextureView.SurfaceCallback {

    companion object {
        const val TAG = "ScrcpyActivity"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PREFS_NAME = "prefs_name"
    }

    private lateinit var surfaceView: ScrcpyInputTextureView
    private lateinit var navBar: LinearLayout
    private lateinit var barView: GridLayout
    private lateinit var buttonMore: ImageView

    private var scrcpySession: ScrcpySession? = null
    private var surface: Surface? = null
    private var screenWidth = 0
    private var screenHeight = 0

    // Decoder components - like Easycontrol Client
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var videoDecode: VideoDecode? = null
    private var audioDecode: AudioDecode? = null

    private var host: String = ""
    private var port: Int = 0
    private var prefsName: String = ""
    private var touchEventHandler: TouchEventHandler? = null
    private var isInPipMode = false
    private var isNavBarVisible = true
    private var moreMenuHandler = Handler(Looper.getMainLooper())
    private var moreMenuRunnable: Runnable? = null

    // Status management - exactly like Easycontrol Client
    // 0: initial, 1: running, -1: stopped
    private var status = 0
    private var videoDecodeThread: Thread? = null
    private var audioDecodeThread: Thread? = null
    private var keepAliveThread: Thread? = null
    private var lastKeepAliveTime = 0L
    private val timeoutDelay = 5000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scrcpy)
        surfaceView = findViewById(R.id.surface_view)
        surfaceView.inputCallbacks = this
        surfaceView.surfaceCallback = this
        surfaceView.setCommitTextEnabled(true)

        host = intent.getStringExtra(EXTRA_HOST) ?: ""
        port = intent.getIntExtra(EXTRA_PORT, 0)
        prefsName = intent.getStringExtra(EXTRA_PREFS_NAME) ?: ""
        Log.i(TAG, "onCreate: host=$host, port=$port, prefs=$prefsName")
        if (host.isEmpty() || port == 0) { finish(); return }

        navBar = findViewById(R.id.nav_bar)
        barView = findViewById(R.id.bar_view)
        buttonMore = findViewById(R.id.button_more)

        setupBottomNavBar()
        setupBarView()
        setupMoreButton()

        navBar.visibility = View.VISIBLE
        isNavBarVisible = true

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onSurfaceReady(surface: Surface) {
        this.surface = surface
        startScrcpy()
    }

    override fun onSurfaceDestroyed() {
        release(null)
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        val session = scrcpySession ?: return false
        if (status != 1 || event.keyCode == KeyEvent.KEYCODE_BACK) return false
        session.sendKeyEvent(event.action, event.keyCode, event.metaState)
        return true
    }

    override fun handleCommitText(text: CharSequence): Boolean { return true }

    override fun handleDeleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val session = scrcpySession ?: return false
        if (status != 1) return false
        repeat(beforeLength.coerceAtLeast(0)) {
            session.sendKeyEvent(0, KeyEvent.KEYCODE_DEL)
            session.sendKeyEvent(1, KeyEvent.KEYCODE_DEL)
        }
        repeat(afterLength.coerceAtLeast(0)) {
            session.sendKeyEvent(0, KeyEvent.KEYCODE_FORWARD_DEL)
            session.sendKeyEvent(1, KeyEvent.KEYCODE_FORWARD_DEL)
        }
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (isTouchOnView(navBar, event) || isTouchOnView(buttonMore, event) || isTouchOnView(barView, event)) {
            return super.dispatchTouchEvent(event)
        }
        if (barView.visibility == View.VISIBLE) {
            hideBarView()
        }
        val handler = touchEventHandler
        if (handler != null && status == 1) {
            handler.updateDimensions(surfaceView.width, surfaceView.height)
            val location = IntArray(2)
            surfaceView.getLocationOnScreen(location)
            val localX = location[0].toFloat()
            val localY = location[1].toFloat()
            event.offsetLocation(-localX, -localY)
            handler.handleMotionEvent(event)
            event.offsetLocation(localX, localY)
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun isTouchOnView(view: View, event: MotionEvent): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        val x = event.rawX
        val y = event.rawY
        return x >= location[0] && x <= location[0] + view.width &&
               y >= location[1] && y <= location[1] + view.height
    }

    // ============ UI Setup ============

    private fun setupBottomNavBar() {
        findViewById<ImageView>(R.id.button_rotate).setOnClickListener {
            scrcpySession?.rotateDevice()
            resetBarViewTimer()
        }
        findViewById<ImageView>(R.id.button_switch).setOnClickListener {
            scrcpySession?.sendKeyEvent(1, KeyEvent.KEYCODE_APP_SWITCH)
            scrcpySession?.sendKeyEvent(0, KeyEvent.KEYCODE_APP_SWITCH)
            resetBarViewTimer()
        }
        findViewById<ImageView>(R.id.button_home).setOnClickListener {
            scrcpySession?.sendHome()
            resetBarViewTimer()
        }
        findViewById<ImageView>(R.id.button_back).setOnClickListener {
            scrcpySession?.sendBack()
            resetBarViewTimer()
        }
    }

    private fun setupBarView() {
        findViewById<ImageView>(R.id.button_nav_bar).setOnClickListener {
            isNavBarVisible = !isNavBarVisible
            navBar.visibility = if (isNavBarVisible) View.VISIBLE else View.GONE
            resetBarViewTimer()
        }
        findViewById<ImageView>(R.id.button_mini).setOnClickListener {
            moveTaskToBack(true)
            resetBarViewTimer()
        }
        findViewById<ImageView>(R.id.button_full_exit).setOnClickListener {
            if (!isInPipMode) enterPipMode()
            hideBarView()
        }
        findViewById<ImageView>(R.id.button_close).setOnClickListener {
            release(null)
            finish()
        }
        findViewById<ImageView>(R.id.button_transfer).setOnClickListener {
            scrcpySession?.expandNotificationPanel()
            resetBarViewTimer()
        }
        findViewById<ImageView>(R.id.button_light_off).setOnClickListener {
            scrcpySession?.setDisplayPower(false)
            resetBarViewTimer()
        }
        findViewById<ImageView>(R.id.button_power).setOnClickListener {
            scrcpySession?.sendKeyClick(KeyEvent.KEYCODE_POWER)
            resetBarViewTimer()
        }
        findViewById<ImageView>(R.id.button_lock).setOnClickListener {
            scrcpySession?.lockDevice()
            resetBarViewTimer()
        }
        findViewById<ImageView>(R.id.button_floating).setOnClickListener {
            switchToFloating()
            resetBarViewTimer()
        }
    }

    private fun setupMoreButton() {
        buttonMore.setOnClickListener {
            if (barView.visibility == View.VISIBLE) hideBarView() else showBarView()
        }
    }

    private fun showBarView() {
        barView.visibility = View.VISIBLE
        buttonMore.setImageResource(R.drawable.x_icon)
        startBarViewTimer()
    }

    private fun hideBarView() {
        barView.visibility = View.GONE
        buttonMore.setImageResource(R.drawable.ellipsis_vertical)
        moreMenuRunnable?.let { moreMenuHandler.removeCallbacks(it) }
    }

    private fun resetBarViewTimer() { startBarViewTimer() }

    private fun startBarViewTimer() {
        moreMenuRunnable?.let { moreMenuHandler.removeCallbacks(it) }
        moreMenuRunnable = Runnable { hideBarView() }
        moreMenuHandler.postDelayed(moreMenuRunnable!!, 3000)
    }

    // ============ Core: startScrcpy - like Easycontrol Client constructor ============

    private fun startScrcpy() {
        Thread {
            try {
                Log.i(TAG, "Starting scrcpy session...")

                val client = AdbConnectionPlugin.sharedAdbClient ?: run {
                    Log.e(TAG, "ADB client not available")
                    runOnUiThread {
                        Toast.makeText(this, "ADB client not connected", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@Thread
                }
                if (!client.isConnected) {
                    Log.e(TAG, "ADB client is disconnected")
                    runOnUiThread {
                        Toast.makeText(this, "ADB client is disconnected", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@Thread
                }

                val session = ScrcpySession(client, this@ScrcpyActivity, prefsName)
                if (!session.start()) {
                    Log.e(TAG, "Failed to start scrcpy session")
                    finish()
                    return@Thread
                }
                Log.i(TAG, "Scrcpy started: device=${session.deviceName}")
                scrcpySession = session
                screenWidth = session.screenWidth.takeIf { it > 0 } ?: 0
                screenHeight = session.screenHeight.takeIf { it > 0 } ?: 0

                var viewWidth = 0; var viewHeight = 0
                runOnUiThread {
                    viewWidth = surfaceView.width
                    viewHeight = surfaceView.height
                    if (screenWidth > 0 && screenHeight > 0) {
                        surfaceView.setVideoDimensions(screenWidth, screenHeight)
                        requestedOrientation = if (screenWidth > screenHeight)
                            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        else
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                }

                touchEventHandler = TouchEventHandler(
                    sessionWidth = screenWidth.takeIf { it > 0 } ?: 1920,
                    sessionHeight = screenHeight.takeIf { it > 0 } ?: 1080,
                    touchAreaWidth = viewWidth,
                    touchAreaHeight = viewHeight,
                    onInjectTouch = { action, pointerId, x, y, pressure, actionButton, buttons ->
                        val sw = screenWidth.takeIf { it > 0 } ?: 1920
                        val sh = screenHeight.takeIf { it > 0 } ?: 1080
                        session.sendTouchEvent(action, pointerId, x, y, sw, sh, pressure, actionButton, buttons)
                    },
                    onBackOrScreenOn = { action -> session.sendKeyEvent(action, KeyEvent.KEYCODE_BACK) },
                )

                // Create handler thread for MediaCodec - like Easycontrol
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    handlerThread = HandlerThread("scrcpy_mediacodec")
                    handlerThread?.start()
                    handler = Handler(handlerThread!!.looper)
                }

                // Start keep-alive thread - like Easycontrol keepAliveThread
                lastKeepAliveTime = System.currentTimeMillis()
                keepAliveThread = Thread({
                    while (status != -1) {
                        if (System.currentTimeMillis() - lastKeepAliveTime > timeoutDelay) {
                            Log.e(TAG, "Keep-alive timeout")
                            runOnUiThread { release(null); finish() }
                            break
                        }
                        try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
                    }
                }, "scrcpy-keepalive").also { it.start() }

                // Set status to running - like Easycontrol: status = 1
                status = 1

                // Start audio thread - like Easycontrol executeStreamInThread
                // audioCodecId == 0 means disabled, == 1 means server error
                if (session.audioCodecId != 0 && session.audioCodecId != 1) {
                    audioDecodeThread = Thread({ executeStreamIn(session) }, "scrcpy-audio").also { it.start() }
                }

                // Start video thread - like Easycontrol executeStreamVideoThread
                videoDecodeThread = Thread({ executeStreamVideo(session) }, "scrcpy-video").also { it.start() }

            } catch (e: Exception) {
                Log.e(TAG, "scrcpy failed", e)
                finish()
            }
        }.start()
    }

    // ============ Video stream thread - like Easycontrol executeStreamVideo ============

    private fun executeStreamVideo(session: ScrcpySession) {
        try {
            var csd0: Pair<ByteArray, Long>? = null
            var csd1: Pair<ByteArray, Long>? = null

            while (!Thread.interrupted()) {
                val packet = session.readVideoPacket() ?: break
                lastKeepAliveTime = System.currentTimeMillis()

                if (packet.isSession) {
                    if (packet.width > 0 && packet.height > 0) {
                        screenWidth = packet.width
                        screenHeight = packet.height
                        runOnUiThread {
                            surfaceView.setVideoDimensions(screenWidth, screenHeight)
                            requestedOrientation = if (screenWidth > screenHeight)
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            else
                                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            touchEventHandler?.updateSessionDimensions(screenWidth, screenHeight)
                        }
                    }
                    continue
                }

                if (packet.data.isEmpty()) continue

                // Collect config frames - like Easycontrol reads csd0/csd1 from stream
                if (packet.isConfig) {
                    if (csd0 == null) {
                        csd0 = Pair(packet.data.clone(), packet.ptsUs)
                    } else if (csd1 == null) {
                        csd1 = Pair(packet.data.clone(), packet.ptsUs)
                    }
                }

                // Create decoder when we have config data and dimensions - like Easycontrol
                if (videoDecode == null && csd0 != null && screenWidth > 0 && screenHeight > 0) {
                    val sur = surface ?: continue
                    val h = handler
                    videoDecode = VideoDecode(
                        Pair(screenWidth, screenHeight),
                        sur,
                        csd0!!,
                        csd1,
                        h,
                        session.videoCodecId,
                    )
                    Log.i(TAG, "Video decoder created: ${screenWidth}x${screenHeight}, codecId=0x${String.format("%x", session.videoCodecId)}")
                }

                // Feed data to decoder - like Easycontrol: videoDecode.decodeIn(data, pts)
                videoDecode?.decodeIn(packet.data, packet.ptsUs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Video stream error", e)
        }
        release(null)
    }

    // ============ Audio/control stream thread - like Easycontrol executeStreamIn ============

    private fun executeStreamIn(session: ScrcpySession) {
        try {
            val useOpus = session.audioCodecId == 0x6f707573 // "opus"

            while (!Thread.interrupted()) {
                val packet = session.readAudioPacket() ?: break
                lastKeepAliveTime = System.currentTimeMillis()

                if (packet.data.isEmpty()) continue

                // Config packet initializes decoder (scrcpy 4.0 audio stream)
                if (packet.isConfig) {
                    if (audioDecode == null) {
                        try {
                            audioDecode = AudioDecode(useOpus, packet.data, handler)
                            audioDecode?.playAudio(true)
                            Log.i(TAG, "Audio decoder created, useOpus=$useOpus, csdSize=${packet.data.size}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to create audio decoder", e)
                        }
                    }
                    continue
                }

                // Regular audio frame
                audioDecode?.decodeIn(packet.data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio stream error", e)
        }
    }

    // ============ Release - like Easycontrol Client.release() ============

    private fun release(error: String?) {
        if (status == -1) return
        status = -1

        if (error != null) {
            Log.e(TAG, "Release due to: $error")
        }

        // Lock device on disconnect if enabled
        if (prefsName.isNotEmpty()) {
            val prefs = getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("scrcpy_lock_on_disconnect", false)) {
                scrcpySession?.lockDevice()
                try { Thread.sleep(100) } catch (_: InterruptedException) {}
            }
        }

        // Ordered cleanup - like Easycontrol release() switch-case
        for (i in 0..6) {
            try {
                when (i) {
                    0 -> {
                        keepAliveThread?.interrupt()
                        audioDecodeThread?.interrupt()
                        videoDecodeThread?.interrupt()
                    }
                    1 -> {
                        keepAliveThread?.join(500)
                        audioDecodeThread?.join(500)
                        videoDecodeThread?.join(500)
                    }
                    2 -> {
                        videoDecode?.release()
                        videoDecode = null
                    }
                    3 -> {
                        audioDecode?.release()
                        audioDecode = null
                    }
                    4 -> {
                        scrcpySession?.stop()
                        scrcpySession = null
                    }
                    5 -> {
                        touchEventHandler = null
                    }
                    6 -> {
                        handlerThread?.quitSafely()
                        handlerThread = null
                        handler = null
                        surface = null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during cleanup step $i", e)
            }
        }

        Log.i(TAG, "Scrcpy released")
    }

    // ============ Floating window ============

    private fun switchToFloating() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName"))
            startActivity(intent)
            return
        }
        val serviceIntent = Intent(this, ScrcpyFloatingService::class.java).apply {
            action = ScrcpyFloatingService.ACTION_START
            putExtra(ScrcpyFloatingService.EXTRA_HOST, host)
            putExtra(ScrcpyFloatingService.EXTRA_PORT, port)
            putExtra(ScrcpyFloatingService.EXTRA_PREFS_NAME, prefsName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
        release(null)
        finish()
    }

    // ============ PiP ============

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (status == 1 && screenWidth > 0 && screenHeight > 0) {
            enterPipMode()
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val aspectRatio = Rational(screenWidth, screenHeight)
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build()
            try {
                enterPictureInPictureMode(params)
                isInPipMode = true
                navBar.visibility = View.GONE
                barView.visibility = View.GONE
                buttonMore.visibility = View.GONE
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enter PiP mode", e)
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        isInPipMode = isInPictureInPictureMode
        if (isInPictureInPictureMode) {
            navBar.visibility = View.GONE
            barView.visibility = View.GONE
            buttonMore.visibility = View.GONE
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        } else {
            navBar.visibility = if (isNavBarVisible) View.VISIBLE else View.GONE
            buttonMore.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        moreMenuRunnable?.let { moreMenuHandler.removeCallbacks(it) }
        release(null)
        super.onDestroy()
    }
}
