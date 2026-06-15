package org.kde.kdeconnect.plugins.adbconnection

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.ImageView
import org.kde.kdeconnect_tp.R

/**
 * Scrcpy floating window service - UI layer only.
 * The actual scrcpy session runs in ScrcpySessionService.
 */
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

    private var status = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private val mainHandler = Handler(Looper.getMainLooper())
    private var surfaceReady = false
    private val pointerDownTimes = LongArray(10)

    private lateinit var layoutParams: WindowManager.LayoutParams
    private var host = ""
    private var port = 0
    private var prefsName = ""

    // Service connection
    private var sessionService: ScrcpySessionService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScrcpySessionService.LocalBinder
            sessionService = binder.getService()
            serviceBound = true

            sessionService?.onScreenSizeChanged = { w, h ->
                screenWidth = w
                screenHeight = h
                mainHandler.post { updateOverlaySize() }
            }

            sessionService?.onStatusChanged = { s ->
                status = s
            }
            status = sessionService?.getStatus() ?: 0

            sessionService?.onError = { error ->
                Log.e(TAG, "Session error: $error")
                mainHandler.post { release(); stopSelf() }
            }

            // Set surface if already ready
            if (surfaceReady && surface != null) {
                sessionService?.setSurface(surface)
            }

            // Update dimensions from service
            val (sw, sh) = sessionService?.getScreenSize() ?: Pair(0, 0)
            if (sw > 0 && sh > 0) {
                screenWidth = sw
                screenHeight = sh
                updateOverlaySize()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sessionService = null
            serviceBound = false
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
                startAndBindSessionService()
            }
            ACTION_STOP -> {
                release()
                hideOverlay()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startAndBindSessionService() {
        val serviceIntent = Intent(this, ScrcpySessionService::class.java).apply {
            action = ScrcpySessionService.ACTION_START
            putExtra(ScrcpySessionService.EXTRA_HOST, host)
            putExtra(ScrcpySessionService.EXTRA_PORT, port)
            putExtra(ScrcpySessionService.EXTRA_PREFS_NAME, prefsName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, BIND_AUTO_CREATE)
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
                sessionService?.setSurface(surface)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                if (screenWidth > 0 && screenHeight > 0) {
                    st.setDefaultBufferSize(screenWidth, screenHeight)
                }
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                sessionService?.setSurface(null)
                surface = null
                surfaceReady = false
                return false
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }

        tv.setOnTouchListener { view, event ->
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
                sessionService?.sendTouchEvent(scrcpyAction, pointerId.toLong(), devX, devY, screenWidth, screenHeight, 1f, 0, 0)
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
            sessionService?.sendBack()
        }
        overlayView?.findViewById<ImageView>(R.id.floating_btn_home)?.setOnClickListener {
            sessionService?.sendHome()
        }
        overlayView?.findViewById<ImageView>(R.id.floating_btn_switch)?.setOnClickListener {
            sessionService?.sendAppSwitch()
        }
        overlayView?.findViewById<ImageView>(R.id.floating_btn_fullscreen)?.setOnClickListener {
            sessionService?.setSurface(null)
            val intent = Intent(this, ScrcpyActivity::class.java).apply {
                putExtra(ScrcpyActivity.EXTRA_HOST, host)
                putExtra(ScrcpyActivity.EXTRA_PORT, port)
                putExtra(ScrcpyActivity.EXTRA_PREFS_NAME, prefsName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            release()
            hideOverlay()
            stopSelf()
        }
        overlayView?.findViewById<ImageView>(R.id.floating_btn_close)?.setOnClickListener {
            sessionService?.release()
            release()
            hideOverlay()
            stopSelf()
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

    private fun release() {
        if (serviceBound) {
            sessionService?.setSurface(null)
            unbindService(serviceConnection)
            serviceBound = false
        }
        sessionService = null
    }

    override fun onDestroy() {
        release()
        hideOverlay()
        super.onDestroy()
    }
}
