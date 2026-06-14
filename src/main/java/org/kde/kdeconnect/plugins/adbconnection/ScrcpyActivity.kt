package org.kde.kdeconnect.plugins.adbconnection

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.ScrcpyInputTextureView
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.TouchEventHandler
import org.kde.kdeconnect_tp.R

/**
 * Scrcpy remote control activity - UI layer only.
 * The actual scrcpy session runs in ScrcpySessionService.
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

    private var surface: Surface? = null
    private var screenWidth = 0
    private var screenHeight = 0

    private var host: String = ""
    private var port: Int = 0
    private var prefsName: String = ""
    private var touchEventHandler: TouchEventHandler? = null
    private var isNavBarVisible = true
    private val moreMenuHandler = android.os.Handler(Looper.getMainLooper())
    private var moreMenuRunnable: Runnable? = null

    private var status = 0

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
                surfaceView.setVideoDimensions(w, h)
                requestedOrientation = if (w > h)
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                touchEventHandler?.updateSessionDimensions(w, h)
            }

            sessionService?.onStatusChanged = { s ->
                status = s
            }

            sessionService?.onError = { error ->
                Log.e(TAG, "Session error: $error")
                runOnUiThread { finish() }
            }

            // Set surface if already ready
            surface?.let { sessionService?.setSurface(it) }

            // Update dimensions from service
            val (sw, sh) = sessionService?.getScreenSize() ?: Pair(0, 0)
            if (sw > 0 && sh > 0) {
                screenWidth = sw
                screenHeight = sh
                surfaceView.setVideoDimensions(sw, sh)
                touchEventHandler?.updateSessionDimensions(sw, sh)
            }

            // Update touch handler with current service dimensions
            touchEventHandler?.updateDimensions(surfaceView.width, surfaceView.height)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            sessionService = null
            serviceBound = false
        }
    }

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

        // Initialize touch handler early with default dimensions
        touchEventHandler = TouchEventHandler(
            sessionWidth = 1920,
            sessionHeight = 1080,
            touchAreaWidth = surfaceView.width,
            touchAreaHeight = surfaceView.height,
            onInjectTouch = { action, pointerId, x, y, pressure, actionButton, buttons ->
                val sw = screenWidth.takeIf { it > 0 } ?: 1920
                val sh = screenHeight.takeIf { it > 0 } ?: 1080
                sessionService?.sendTouchEvent(action, pointerId, x.toInt(), y.toInt(), sw, sh, pressure, actionButton, buttons)
            },
            onBackOrScreenOn = { action -> sessionService?.sendKeyEvent(action, KeyEvent.KEYCODE_BACK) },
        )

        // Start and bind to session service
        startAndBindSessionService()
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

    override fun onSurfaceReady(surface: Surface) {
        this.surface = surface
        sessionService?.setSurface(surface)
    }

    override fun onSurfaceDestroyed() {
        sessionService?.setSurface(null)
        surface = null
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean {
        if (status != 1 || event.keyCode == KeyEvent.KEYCODE_BACK) return false
        sessionService?.sendKeyEvent(event.action, event.keyCode, event.metaState)
        return true
    }

    override fun handleCommitText(text: CharSequence): Boolean { return true }

    override fun handleDeleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (status != 1) return false
        repeat(beforeLength.coerceAtLeast(0)) {
            sessionService?.sendKeyEvent(0, KeyEvent.KEYCODE_DEL)
            sessionService?.sendKeyEvent(1, KeyEvent.KEYCODE_DEL)
        }
        repeat(afterLength.coerceAtLeast(0)) {
            sessionService?.sendKeyEvent(0, KeyEvent.KEYCODE_FORWARD_DEL)
            sessionService?.sendKeyEvent(1, KeyEvent.KEYCODE_FORWARD_DEL)
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

    private fun setupBottomNavBar() {
        findViewById<ImageView>(R.id.button_rotate).setOnClickListener {
            sessionService?.rotateDevice()
            resetBarViewTimer()
        }
        findViewById<ImageView>(R.id.button_switch).setOnClickListener {
            sessionService?.sendAppSwitch()
            resetBarViewTimer()
        }
        findViewById<ImageView>(R.id.button_home).setOnClickListener {
            sessionService?.sendHome()
            resetBarViewTimer()
        }
        findViewById<ImageView>(R.id.button_back).setOnClickListener {
            sessionService?.sendBack()
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
            switchToFloating()
            hideBarView()
        }
        findViewById<ImageView>(R.id.button_close).setOnClickListener {
            sessionService?.release()
            finish()
        }
        findViewById<ImageView>(R.id.button_transfer).setOnClickListener {
            sessionService?.expandNotificationPanel()
            resetBarViewTimer()
        }
        findViewById<ImageView>(R.id.button_light_off).setOnClickListener {
            sessionService?.setDisplayPower(false)
            resetBarViewTimer()
        }
        findViewById<ImageView>(R.id.button_power).setOnClickListener {
            sessionService?.sendKeyClick(KeyEvent.KEYCODE_POWER)
            resetBarViewTimer()
        }
        findViewById<ImageView>(R.id.button_lock).setOnClickListener {
            sessionService?.lockDevice()
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

    private fun switchToFloating() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "需要悬浮窗权限", Toast.LENGTH_SHORT).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName"))
            startActivity(intent)
            return
        }
        sessionService?.setSurface(null)
        val serviceIntent = Intent(this, ScrcpyFloatingService::class.java).apply {
            action = ScrcpyFloatingService.ACTION_START
            putExtra(ScrcpyFloatingService.EXTRA_HOST, host)
            putExtra(ScrcpyFloatingService.EXTRA_PORT, port)
            putExtra(ScrcpyFloatingService.EXTRA_PREFS_NAME, prefsName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
        else startService(serviceIntent)
        finish()
    }



    override fun onDestroy() {
        moreMenuRunnable?.let { moreMenuHandler.removeCallbacks(it) }
        if (serviceBound) {
            sessionService?.setSurface(null)
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }
}
