package org.kde.kdeconnect.plugins.adbconnection

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.ImageView
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.AudioDecode
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.ScrcpySession
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.VideoDecode

class ScrcpyFloatingService : Service() {

    companion object {
        private const val TAG = "ScrcpyFloatingSvc"
        private const val CHANNEL_ID = "scrcpy_floating"
        private const val NOTIFICATION_ID = 3001
        const val ACTION_START = "org.kde.kdeconnect.floating.START"
        const val ACTION_STOP = "org.kde.kdeconnect.floating.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PREFS_NAME = "prefs_name"
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var textureView: TextureView? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var scrcpySession: ScrcpySession? = null

    // Decoder - like Easycontrol
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var videoDecode: VideoDecode? = null

    // Status management - like Easycontrol Client
    private var status = 0 // 0: initial, 1: running, -1: stopped
    private var videoDecodeThread: Thread? = null
    private var audioDecodeThread: Thread? = null
    private var keepAliveThread: Thread? = null
    private var lastKeepAliveTime = 0L
    private val timeoutDelay = 5000L

    // Audio decoder
    private var audioDecode: AudioDecode? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var surfaceReady = false
    private val pointerDownTimes = LongArray(10)

    private lateinit var layoutParams: WindowManager.LayoutParams
    private var host = ""
    private var port = 0
    private var prefsName = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                host = intent.getStringExtra(EXTRA_HOST) ?: ""
                port = intent.getIntExtra(EXTRA_PORT, 0)
                prefsName = intent.getStringExtra(EXTRA_PREFS_NAME) ?: ""
                if (host.isEmpty() || port == 0) { stopSelf(); return START_NOT_STICKY }
                startForeground(NOTIFICATION_ID, buildNotification())
                showOverlay()
            }
            ACTION_STOP -> {
                release()
                hideOverlay()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "悬浮窗投屏", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("悬浮窗投屏")
            .setContentText("正在运行")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("InflateParams")
    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.floating_overlay, null)
        textureView = overlayView!!.findViewById(R.id.floating_texture)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val shortEdge = minOf(screenW, screenH)
        val initW = shortEdge * 4 / 5
        val initH = (initW * 16f / 9f).toInt()

        layoutParams = WindowManager.LayoutParams(
            initW, initH,
            type,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenW - initW) / 2
            y = (screenH - initH) / 2
        }

        setupTextureView()
        setupDragBar()
        setupResizeHandles()
        setupControlBar()

        windowManager!!.addView(overlayView, layoutParams)
    }

    private fun hideOverlay() {
        try { if (overlayView != null) windowManager?.removeView(overlayView) } catch (_: Exception) {}
        overlayView = null; textureView = null
    }

    private fun setupTextureView() {
        val tv = textureView ?: return
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surfaceTexture = st
                surface = Surface(st)
                surfaceReady = true
                startScrcpy()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                if (screenWidth > 0 && screenHeight > 0) {
                    st.setDefaultBufferSize(screenWidth, screenHeight)
                }
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = false
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        tv.setOnTouchListener { view, event ->
            val session = scrcpySession ?: return@setOnTouchListener false
            if (status != 1) return@setOnTouchListener false
            val w = view.width; val h = view.height
            if (w <= 0 || h <= 0) return@setOnTouchListener false

            val actionMasked = event.actionMasked
            if (actionMasked == MotionEvent.ACTION_DOWN || actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                val i = event.actionIndex
                pointerDownTimes[i] = event.eventTime
            }

            val scrcpyAction = when (actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> 0
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> 1
                MotionEvent.ACTION_MOVE -> 2
                MotionEvent.ACTION_CANCEL -> 3
                else -> return@setOnTouchListener false
            }

            for (i in 0 until event.pointerCount) {
                val pointerId = event.getPointerId(i)
                val offsetTime = (event.eventTime - pointerDownTimes[i.coerceIn(0, 9)]).toInt()
                var x = event.getX(i) / w
                var y = event.getY(i) / h
                if (x < 0f || x > 1f || y < 0f || y > 1f) {
                    x = x.coerceIn(0f, 1f)
                    y = y.coerceIn(0f, 1f)
                }
                val devX = (x * (screenWidth - 1).coerceAtLeast(0)).toInt().coerceIn(0, screenWidth - 1)
                val devY = (y * (screenHeight - 1).coerceAtLeast(0)).toInt().coerceIn(0, screenHeight - 1)
                session.sendTouchEvent(scrcpyAction, pointerId.toLong(), devX, devY, screenWidth, screenHeight, 1f, 0, 0)
            }
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragBar() {
        val bar = overlayView?.findViewById<View>(R.id.floating_drag_bar) ?: return
        var lastX = 0; var lastY = 0
        var initialX = 0; var initialY = 0
        var isDragging = false

        bar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x; initialY = layoutParams.y
                    lastX = event.rawX.toInt(); lastY = event.rawY.toInt()
                    isDragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - lastX
                    val dy = event.rawY.toInt() - lastY
                    if (!isDragging && (dx * dx + dy * dy > 625)) isDragging = true
                    if (isDragging) {
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        try { windowManager?.updateViewLayout(overlayView, layoutParams) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> true
                else -> false
            }
        }
    }

    private fun setupControlBar() {
        overlayView?.findViewById<ImageView>(R.id.floating_btn_back)?.setOnClickListener {
            scrcpySession?.sendBack()
        }
        overlayView?.findViewById<ImageView>(R.id.floating_btn_home)?.setOnClickListener {
            scrcpySession?.sendHome()
        }
        overlayView?.findViewById<ImageView>(R.id.floating_btn_switch)?.setOnClickListener {
            scrcpySession?.sendKeyEvent(0, KeyEvent.KEYCODE_APP_SWITCH)
            scrcpySession?.sendKeyEvent(1, KeyEvent.KEYCODE_APP_SWITCH)
        }
        overlayView?.findViewById<ImageView>(R.id.floating_btn_fullscreen)?.setOnClickListener {
            val intent = Intent(this, ScrcpyActivity::class.java).apply {
                putExtra(ScrcpyActivity.EXTRA_HOST, host)
                putExtra(ScrcpyActivity.EXTRA_PORT, port)
                putExtra(ScrcpyActivity.EXTRA_PREFS_NAME, prefsName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            release(); hideOverlay(); stopSelf()
        }
        overlayView?.findViewById<ImageView>(R.id.floating_btn_close)?.setOnClickListener {
            release(); hideOverlay(); stopSelf()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupResizeHandles() {
        val minSize = 150
        setupCornerResize(R.id.resize_br, minSize)
        setupCornerResize(R.id.resize_tl, minSize)
        setupCornerResize(R.id.resize_tr, minSize)
        setupCornerResize(R.id.resize_bl, minSize)
    }

    private fun setupCornerResize(viewId: Int, minSize: Int) {
        val view = overlayView?.findViewById<View>(viewId) ?: return
        var initialX = 0; var initialY = 0; var initialW = 0; var initialH = 0
        var initialRawX = 0f; var initialRawY = 0f

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x; initialY = layoutParams.y
                    initialW = layoutParams.width; initialH = layoutParams.height
                    initialRawX = event.rawX; initialRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialRawX
                    val deltaY = event.rawY - initialRawY
                    when (viewId) {
                        R.id.resize_br -> {
                            layoutParams.width = (initialW + deltaX).toInt().coerceAtLeast(minSize)
                            layoutParams.height = (initialH + deltaY).toInt().coerceAtLeast(minSize)
                        }
                        R.id.resize_bl -> {
                            val newW = (initialW - deltaX).toInt()
                            if (newW >= minSize) { layoutParams.x = (initialX + deltaX).toInt(); layoutParams.width = newW }
                            layoutParams.height = (initialH + deltaY).toInt().coerceAtLeast(minSize)
                        }
                        R.id.resize_tr -> {
                            layoutParams.width = (initialW + deltaX).toInt().coerceAtLeast(minSize)
                            val newH = (initialH - deltaY).toInt()
                            if (newH >= minSize) { layoutParams.y = (initialY + deltaY).toInt(); layoutParams.height = newH }
                        }
                        R.id.resize_tl -> {
                            val newW = (initialW - deltaX).toInt()
                            if (newW >= minSize) { layoutParams.x = (initialX + deltaX).toInt(); layoutParams.width = newW }
                            val newH = (initialH - deltaY).toInt()
                            if (newH >= minSize) { layoutParams.y = (initialY + deltaY).toInt(); layoutParams.height = newH }
                        }
                    }
                    try { windowManager?.updateViewLayout(overlayView, layoutParams) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
    }

    private fun updateOverlaySize() {
        if (screenWidth <= 0 || screenHeight <= 0) return
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val shortEdge = minOf(screenW, screenH)
        val maxW = shortEdge * 4 / 5
        val videoRatio = screenHeight.toFloat() / screenWidth.toFloat()
        val w: Int; val h: Int
        if (videoRatio > 1f) {
            h = minOf(maxW, (screenH * 4 / 5))
            w = (h / videoRatio).toInt()
        } else {
            w = maxW
            h = (w * videoRatio).toInt()
        }
        if (layoutParams.width != w || layoutParams.height != h) {
            layoutParams.width = w; layoutParams.height = h
            try { windowManager?.updateViewLayout(overlayView, layoutParams) } catch (_: Exception) {}
        }
    }

    // ============ Core: startScrcpy - like Easycontrol Client ============

    private fun startScrcpy() {
        if (status == 1) return
        Thread {
            try {
                val client = AdbConnectionPlugin.sharedAdbClient ?: run {
                    Log.e(TAG, "ADB client not available")
                    stopSelf()
                    return@Thread
                }
                val session = ScrcpySession(client, this, prefsName)
                if (!session.start()) { stopSelf(); return@Thread }
                scrcpySession = session
                screenWidth = session.screenWidth.takeIf { it > 0 } ?: 0
                screenHeight = session.screenHeight.takeIf { it > 0 } ?: 0
                mainHandler.post { updateOverlaySize() }

                // Create handler thread for MediaCodec
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    handlerThread = HandlerThread("floating_mediacodec")
                    handlerThread?.start()
                    handler = Handler(handlerThread!!.looper)
                }

                // Start keep-alive thread
                lastKeepAliveTime = System.currentTimeMillis()
                keepAliveThread = Thread({
                    while (status != -1) {
                        if (System.currentTimeMillis() - lastKeepAliveTime > timeoutDelay) {
                            Log.e(TAG, "Keep-alive timeout")
                            release(); stopSelf()
                            break
                        }
                        try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
                    }
                }, "floating-keepalive").also { it.start() }

                // Set status to running
                status = 1

                // Start audio thread
                if (session.audioCodecId != 0 && session.audioCodecId != 1) {
                    audioDecodeThread = Thread({ executeStreamIn(session) }, "floating-audio").also { it.start() }
                }

                // Start video thread
                videoDecodeThread = Thread({ executeStreamVideo(session) }, "floating-video").also { it.start() }

            } catch (e: Exception) {
                Log.e(TAG, "scrcpy failed", e)
                stopSelf()
            }
        }.start()
    }

    // ============ Audio stream - like Easycontrol executeStreamIn ============

    private fun executeStreamIn(session: ScrcpySession) {
        try {
            val useOpus = session.audioCodecId == 0x6f707573 // "opus"

            while (!Thread.interrupted()) {
                val packet = session.readAudioPacket() ?: break
                lastKeepAliveTime = System.currentTimeMillis()

                if (packet.data.isEmpty()) continue

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

                audioDecode?.decodeIn(packet.data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio stream error", e)
        }
    }

    // ============ Video stream - like Easycontrol executeStreamVideo ============

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
                        mainHandler.post { updateOverlaySize() }
                    }
                    continue
                }

                if (packet.data.isEmpty()) continue

                if (packet.isConfig) {
                    if (csd0 == null) {
                        csd0 = Pair(packet.data.clone(), packet.ptsUs)
                    } else if (csd1 == null) {
                        csd1 = Pair(packet.data.clone(), packet.ptsUs)
                    }
                }

                if (videoDecode == null && csd0 != null && screenWidth > 0 && screenHeight > 0) {
                    val sur = surface ?: continue
                    videoDecode = VideoDecode(
                        Pair(screenWidth, screenHeight),
                        sur,
                        csd0!!,
                        csd1,
                        handler,
                        session.videoCodecId,
                    )
                    Log.i(TAG, "Video decoder created: ${screenWidth}x${screenHeight}, codecId=0x${String.format("%x", session.videoCodecId)}")
                }

                videoDecode?.decodeIn(packet.data, packet.ptsUs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Video stream error", e)
        }
        release()
    }

    // ============ Release - like Easycontrol Client.release() ============

    private fun release() {
        if (status == -1) return
        status = -1

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
    }

    override fun onDestroy() {
        release()
        hideOverlay()
        super.onDestroy()
    }
}
