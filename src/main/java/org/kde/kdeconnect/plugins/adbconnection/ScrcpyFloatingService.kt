package org.kde.kdeconnect.plugins.adbconnection

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
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
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.ScrcpySession

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
    private var decoder: MediaCodec? = null
    private var isRunning = false
    private var screenWidth = 0
    private var screenHeight = 0
    private var decoderConfigured = false
    private val codecMime = MediaFormat.MIMETYPE_VIDEO_AVC
    private val inputBufferQueue = java.util.concurrent.LinkedBlockingQueue<Int>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var codecHandler: Handler? = null
    private var codecHandlerThread: HandlerThread? = null
    private var surfaceReady = false
    private val pointerDownTimes = LongArray(10)

    private lateinit var layoutParams: WindowManager.LayoutParams
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
            if (w > 0 && h > 0) {
                screenWidth = w; screenHeight = h
                mainHandler.post { updateOverlaySize() }
            }
        }
    }

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
                stopScrcpy()
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

        // Calculate initial size based on screen (like EasyControl: shortEdge * 4/5)
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
                // Keep buffer at remote device resolution — decoder must not be interrupted
                if (screenWidth > 0 && screenHeight > 0) {
                    st.setDefaultBufferSize(screenWidth, screenHeight)
                }
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                return false
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        // Forward touch events to remote device — like EasyControl's ClientView
        tv.setOnTouchListener { view, event ->
            val session = scrcpySession ?: return@setOnTouchListener false
            if (!isRunning) return@setOnTouchListener false
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
                // Clamp to 0-1 like EasyControl
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
            // Switch back to full screen activity
            val intent = Intent(this, ScrcpyActivity::class.java).apply {
                putExtra(ScrcpyActivity.EXTRA_HOST, host)
                putExtra(ScrcpyActivity.EXTRA_PORT, port)
                putExtra(ScrcpyActivity.EXTRA_PREFS_NAME, prefsName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            stopScrcpy(); hideOverlay(); stopSelf()
        }
        overlayView?.findViewById<ImageView>(R.id.floating_btn_close)?.setOnClickListener {
            stopScrcpy(); hideOverlay(); stopSelf()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupResizeHandles() {
        val minSize = 150

        // Bottom-right: anchor = top-left corner
        setupCornerResize(R.id.resize_br, minSize) { ax, ay, rawX, rawY ->
            layoutParams.width = (rawX - ax).coerceAtLeast(minSize)
            layoutParams.height = (rawY - ay).coerceAtLeast(minSize)
        }

        // Top-left: anchor = bottom-right corner
        setupCornerResize(R.id.resize_tl, minSize) { ax, ay, rawX, rawY ->
            val newW = (ax - rawX).coerceAtLeast(minSize)
            val newH = (ay - rawY).coerceAtLeast(minSize)
            layoutParams.x = ax - newW
            layoutParams.y = ay - newH
            layoutParams.width = newW
            layoutParams.height = newH
        }

        // Top-right: anchor = bottom-left corner
        setupCornerResize(R.id.resize_tr, minSize) { ax, ay, rawX, rawY ->
            val newH = (ay - rawY).coerceAtLeast(minSize)
            layoutParams.width = (rawX - ax).coerceAtLeast(minSize)
            layoutParams.y = ay - newH
            layoutParams.height = newH
        }

        // Bottom-left: anchor = top-right corner
        setupCornerResize(R.id.resize_bl, minSize) { ax, ay, rawX, rawY ->
            val newW = (ax - rawX).coerceAtLeast(minSize)
            layoutParams.x = ax - newW
            layoutParams.width = newW
            layoutParams.height = (rawY - ay).coerceAtLeast(minSize)
        }
    }

    private fun setupCornerResize(viewId: Int, minSize: Int, onResize: (anchorAbsX: Int, anchorAbsY: Int, rawX: Int, rawY: Int) -> Unit) {
        val view = overlayView?.findViewById<View>(viewId) ?: return

        view.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                // Record anchor as absolute screen coordinates of the OPPOSITE corner
                val loc = IntArray(2)
                overlayView?.getLocationOnScreen(loc)
                val ovX = loc[0]
                val ovY = loc[1]
                val anchorX: Int
                val anchorY: Int
                when (viewId) {
                    R.id.resize_br -> { anchorX = ovX; anchorY = ovY }
                    R.id.resize_tl -> { anchorX = ovX + layoutParams.width; anchorY = ovY + layoutParams.height }
                    R.id.resize_tr -> { anchorX = ovX; anchorY = ovY + layoutParams.height }
                    R.id.resize_bl -> { anchorX = ovX + layoutParams.width; anchorY = ovY }
                    else -> { anchorX = ovX; anchorY = ovY }
                }
                v.tag = intArrayOf(anchorX, anchorY)
                true
            } else if (event.action == MotionEvent.ACTION_MOVE) {
                val anchor = v.tag as? IntArray ?: return@setOnTouchListener false
                onResize(anchor[0], anchor[1], event.rawX.toInt(), event.rawY.toInt())
                mainHandler.post {
                    try { windowManager?.updateViewLayout(overlayView, layoutParams) } catch (_: Exception) {}
                }
                true
            } else false
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
        val w: Int
        val h: Int
        // Fit within screen while maintaining video aspect ratio
        if (videoRatio > 1f) {
            // Portrait video: limit by height
            h = minOf(maxW, (screenH * 4 / 5))
            w = (h / videoRatio).toInt()
        } else {
            // Landscape video: limit by width
            w = maxW
            h = (w * videoRatio).toInt()
        }
        if (layoutParams.width != w || layoutParams.height != h) {
            layoutParams.width = w; layoutParams.height = h
            try { windowManager?.updateViewLayout(overlayView, layoutParams) } catch (_: Exception) {}
        }
    }

    private fun startScrcpy() {
        if (isRunning) return
        Thread {
            try {
                val client = AdbConnectionPlugin.sharedAdbClient ?: run {
                    Log.e(TAG, "ADB client not available, aborting scrcpy session")
                    stopSelf()
                    return@Thread
                }
                val session = ScrcpySession(client, this, prefsName)
                if (!session.start()) { stopSelf(); return@Thread }
                scrcpySession = session
                screenWidth = session.screenWidth.takeIf { it > 0 } ?: 0
                screenHeight = session.screenHeight.takeIf { it > 0 } ?: 0
                mainHandler.post { updateOverlaySize() }

                decodeVideo(session)
            } catch (e: Exception) { Log.e(TAG, "scrcpy failed", e); stopSelf() }
        }.start()
    }

    private fun decodeVideo(session: ScrcpySession) {
        isRunning = true
        var lastPacketTime = System.currentTimeMillis()
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
                        mainHandler.post { updateOverlaySize() }
                        createDecoder(newW, newH)
                    } else if (gapMs > 1500 && decoderConfigured) {
                        try { decoder?.flush() } catch (_: Exception) {}
                    }
                }
                lastPacketTime = now; continue
            }

            if (gapMs > 1500 && decoderConfigured) {
                try { decoder?.flush() } catch (_: Exception) {}
            }
            lastPacketTime = now

            if (packet.data.isEmpty()) continue
            if (!decoderConfigured) {
                if (screenWidth <= 0 || screenHeight <= 0) continue
                createDecoder(screenWidth, screenHeight)
            }
            feedPacket(packet.data, packet.ptsUs, packet.isConfig, packet.isKeyFrame)
        }
    }

    private fun createDecoder(width: Int, height: Int) {
        releaseDecoder()
        val s = surface ?: return
        try {
            // Create dedicated HandlerThread for decoder callbacks (like EasyControl)
            codecHandlerThread = HandlerThread("floating_decoder").also { it.start() }
            codecHandler = Handler(codecHandlerThread!!.looper)

            val fmt = MediaFormat.createVideoFormat(codecMime, width, height)
            decoder = MediaCodec.createDecoderByType(codecMime)
            decoder!!.setCallback(decoderCallback, codecHandler)
            decoder!!.configure(fmt, s, null, 0)
            decoder!!.start()
            decoderConfigured = true
        } catch (e: Exception) { Log.e(TAG, "Decoder create failed", e); decoderConfigured = false }
    }

    private fun releaseDecoder() {
        inputBufferQueue.clear()
        try { decoder?.stop(); decoder?.release() } catch (_: Exception) {}
        decoder = null; decoderConfigured = false
        codecHandlerThread?.quitSafely()
        codecHandlerThread = null
        codecHandler = null
    }

    private fun feedPacket(data: ByteArray, ptsUs: Long, isConfig: Boolean, isKeyFrame: Boolean) {
        val d = decoder ?: return
        try {
            var inputIndex = inputBufferQueue.poll()
            if (inputIndex == null && (isConfig || isKeyFrame)) {
                inputIndex = inputBufferQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)
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
        scrcpySession?.stop(); scrcpySession = null
    }

    override fun onDestroy() {
        stopScrcpy()
        hideOverlay()
        codecHandlerThread?.quitSafely()
        super.onDestroy()
    }
}
