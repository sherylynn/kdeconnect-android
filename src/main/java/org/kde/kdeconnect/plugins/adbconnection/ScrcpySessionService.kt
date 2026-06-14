package org.kde.kdeconnect.plugins.adbconnection

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import androidx.core.app.NotificationCompat
import org.kde.kdeconnect.helpers.NotificationHelper
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.AudioDecode
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.ScrcpySession
import org.kde.kdeconnect.plugins.adbconnection.scrcpy.VideoDecode
import org.kde.kdeconnect_tp.R

/**
 * Background service that holds the scrcpy session, video/audio decoders.
 * Activity and floating service are just UI layers that bind to this service
 * and provide a Surface for video rendering.
 */
class ScrcpySessionService : Service() {

    companion object {
        private const val TAG = "ScrcpySessionService"
        private const val NOTIFICATION_ID = 2

        const val ACTION_START = "org.kde.kdeconnect.plugins.adbconnection.ACTION_START_SCRCPY_SESSION"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_PREFS_NAME = "prefs_name"
    }

    inner class LocalBinder : Binder() {
        fun getService(): ScrcpySessionService = this@ScrcpySessionService
    }

    private val binder = LocalBinder()

    private var session: ScrcpySession? = null
    private var videoDecode: VideoDecode? = null
    private var audioDecode: AudioDecode? = null

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private var videoDecodeThread: Thread? = null
    private var audioDecodeThread: Thread? = null
    private var keepAliveThread: Thread? = null

    @Volatile
    private var status = 0 // 0=idle, 1=running, -1=released

    @Volatile
    private var lastKeepAliveTime = 0L

    @Volatile
    var screenWidth = 0
        private set
    @Volatile
    var screenHeight = 0
        private set

    private var csd0: Pair<ByteArray, Long>? = null
    private var csd1: Pair<ByteArray, Long>? = null

    private var dummySurfaceTexture: SurfaceTexture? = null
    private var dummySurface: Surface? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private var pendingSurface: Surface? = null

    // Callbacks for UI components
    var onScreenSizeChanged: ((width: Int, height: Int) -> Unit)? = null
    var onStatusChanged: ((status: Int) -> Unit)? = null
    var onError: ((error: String) -> Unit)? = null

    override fun onBind(intent: Intent): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START && status != 1) {
            val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
            val port = intent.getIntExtra(EXTRA_PORT, 5555)
            val prefsName = intent.getStringExtra(EXTRA_PREFS_NAME) ?: ""

            startForeground()
            startSession(host, port, prefsName)
        }
        return START_STICKY
    }

    private fun startForeground() {
        val notification = NotificationCompat.Builder(this, NotificationHelper.Channels.SCRCPY_SESSION)
            .setContentTitle("Scrcpy Session")
            .setContentText("Running in background")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startSession(host: String, port: Int, prefsName: String) {
        Thread {
            try {
                val client = AdbConnectionPlugin.sharedAdbClient
                    ?: throw IllegalStateException("ADB client not available")

                if (!client.isConnected) {
                    throw IllegalStateException("ADB client is disconnected")
                }

                session = ScrcpySession(client, this, prefsName)
                if (!session!!.start()) {
                    throw IllegalStateException("Failed to start scrcpy session")
                }

                screenWidth = session!!.screenWidth
                screenHeight = session!!.screenHeight

                handlerThread = HandlerThread("scrcpy_mediacodec").apply { start() }
                handler = Handler(handlerThread!!.looper)

                lastKeepAliveTime = System.currentTimeMillis()
                keepAliveThread = Thread({ keepAliveLoop() }, "scrcpy-keepalive").apply { start() }

                status = 1
                mainHandler.post { onStatusChanged?.invoke(1) }

                if (session!!.audioCodecId != 0 && session!!.audioCodecId != 1) {
                    audioDecodeThread = Thread({ audioLoop() }, "scrcpy-audio").apply { start() }
                }

                videoDecodeThread = Thread({ videoLoop() }, "scrcpy-video").apply { start() }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start session", e)
                mainHandler.post { onError?.invoke(e.message ?: "Unknown error") }
                release()
            }
        }.start()
    }

    private fun videoLoop() {
        try {
            while (!Thread.interrupted()) {
                val packet = session?.readVideoPacket() ?: break
                lastKeepAliveTime = System.currentTimeMillis()

                if (packet.isSession) {
                    if (packet.width > 0 && packet.height > 0) {
                        screenWidth = packet.width
                        screenHeight = packet.height
                        mainHandler.post { onScreenSizeChanged?.invoke(screenWidth, screenHeight) }
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
                    continue
                }

                // Create decoder when we have config and dimensions
                if (videoDecode == null && csd0 != null && screenWidth > 0 && screenHeight > 0) {
                    createVideoDecode()
                }

                videoDecode?.decodeIn(packet.data, packet.ptsUs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Video loop error", e)
        }
        if (status != -1) {
            mainHandler.post { onError?.invoke("Video stream ended") }
            release()
        }
    }

    private fun createVideoDecode() {
        val sur = pendingSurface ?: createDummySurface()
        val h = handler
        val s = session ?: return
        videoDecode = VideoDecode(
            Pair(screenWidth, screenHeight),
            sur,
            csd0!!,
            csd1,
            h,
            s.videoCodecId,
        )
        Log.i(TAG, "Video decoder created: ${screenWidth}x${screenHeight}")
    }

    private fun createDummySurface(): Surface {
        if (dummySurface == null) {
            dummySurfaceTexture = SurfaceTexture(0)
            dummySurface = Surface(dummySurfaceTexture!!)
        }
        return dummySurface!!
    }

    private fun audioLoop() {
        try {
            val useOpus = session?.audioCodecId == 0x6f707573

            while (!Thread.interrupted()) {
                val packet = session?.readAudioPacket() ?: break
                lastKeepAliveTime = System.currentTimeMillis()

                if (packet.data.isEmpty()) continue

                if (packet.isConfig) {
                    if (audioDecode == null) {
                        try {
                            audioDecode = AudioDecode(useOpus, packet.data, handler)
                            audioDecode?.playAudio(true)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to create audio decoder", e)
                        }
                    }
                    continue
                }

                audioDecode?.decodeIn(packet.data)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio loop error", e)
        }
    }

    private fun keepAliveLoop() {
        while (status != -1) {
            if (System.currentTimeMillis() - lastKeepAliveTime > 10000) {
                Log.e(TAG, "Keep-alive timeout")
                mainHandler.post { onError?.invoke("Keep-alive timeout") }
                release()
                break
            }
            try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
        }
    }

    fun setSurface(surface: Surface?) {
        pendingSurface = surface
        if (surface != null) {
            videoDecode?.setSurface(surface)
        }
    }

    fun sendTouchEvent(action: Int, pointerId: Long, x: Int, y: Int, sw: Int, sh: Int, pressure: Float, actionButton: Int, buttons: Int) {
        session?.sendTouchEvent(action, pointerId, x, y, sw, sh, pressure, actionButton, buttons)
    }

    fun sendKeyEvent(action: Int, keyCode: Int, metaState: Int = 0) {
        session?.sendKeyEvent(action, keyCode, metaState)
    }

    fun sendKeyClick(keyCode: Int) {
        session?.sendKeyEvent(0, keyCode)
        session?.sendKeyEvent(1, keyCode)
    }

    fun sendHome() {
        session?.sendHome()
    }

    fun sendAppSwitch() {
        session?.sendKeyEvent(0, KeyEvent.KEYCODE_APP_SWITCH)
        session?.sendKeyEvent(1, KeyEvent.KEYCODE_APP_SWITCH)
    }

    fun sendBack() {
        session?.sendBack()
    }

    fun setDisplayPower(on: Boolean) {
        session?.setDisplayPower(on)
    }

    fun lockDevice() {
        session?.lockDevice()
    }

    fun expandNotificationPanel() {
        session?.expandNotificationPanel()
    }

    fun getScreenSize(): Pair<Int, Int> = Pair(screenWidth, screenHeight)

    fun getStatus(): Int = status

    fun release() {
        if (status == -1) return
        status = -1

        val currentThread = Thread.currentThread()

        keepAliveThread?.let { if (it != currentThread) it.interrupt() }
        audioDecodeThread?.let { if (it != currentThread) it.interrupt() }
        videoDecodeThread?.let { if (it != currentThread) it.interrupt() }

        keepAliveThread?.let { if (it != currentThread) it.join(500) }
        audioDecodeThread?.let { if (it != currentThread) it.join(500) }
        videoDecodeThread?.let { if (it != currentThread) it.join(500) }

        videoDecode?.release()
        videoDecode = null

        audioDecode?.release()
        audioDecode = null

        session?.stop()
        session = null

        handlerThread?.quitSafely()
        handlerThread = null
        handler = null

        dummySurface?.release()
        dummySurfaceTexture?.release()
        dummySurface = null
        dummySurfaceTexture = null

        pendingSurface = null

        stopForeground(true)
        stopSelf()

        Log.i(TAG, "Session released")
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }
}
