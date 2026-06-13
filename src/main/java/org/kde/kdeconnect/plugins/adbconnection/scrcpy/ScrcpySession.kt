package org.kde.kdeconnect.plugins.adbconnection.scrcpy

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import org.kde.kdeconnect.plugins.adbconnection.SimpleAdbClient
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

class ScrcpySession(
    private val client: SimpleAdbClient,
    private val context: Context,
    private val prefsName: String = "",
) {
    companion object {
        private const val TAG = "ScrcpySession"
        private const val SERVER_VERSION = "4.0"
        private const val SERVER_ASSET = "bin/scrcpy-server-v4.0"
        private const val SERVER_REMOTE_PATH = "/data/local/tmp/scrcpy-server.jar"
        private const val SERVER_BOOT_DELAY_MS = 500L
        private const val DEVICE_NAME_FIELD_LENGTH = 64

        private const val PACKET_FLAG_SESSION = 1L shl 63
        private const val PACKET_FLAG_CONFIG = 1L shl 62
        private const val PACKET_FLAG_KEY_FRAME = 1L shl 61
        private const val PACKET_PTS_MASK = (1L shl 61) - 1

        private const val TYPE_INJECT_KEYCODE = 0
        private const val TYPE_INJECT_TEXT = 1
        private const val TYPE_INJECT_TOUCH_EVENT = 2
        private const val TYPE_INJECT_SCROLL_EVENT = 3
        private const val TYPE_BACK_OR_SCREEN_ON = 4
        private const val TYPE_EXPAND_NOTIFICATION_PANEL = 5
        private const val TYPE_EXPAND_SETTINGS_PANEL = 6
        private const val TYPE_COLLAPSE_PANELS = 7
        private const val TYPE_GET_CLIPBOARD = 8
        private const val TYPE_SET_CLIPBOARD = 9
        private const val TYPE_SET_DISPLAY_POWER = 10
        private const val TYPE_ROTATE_DEVICE = 11
        private const val TYPE_START_APP = 16

        private fun socketNameFor(scid: Int): String = "scrcpy_%08x".format(scid)
    }

    var deviceName: String = ""
        private set
    var videoCodecId: Int = 0
        private set
    var screenWidth: Int = 0
        private set
    var screenHeight: Int = 0
        private set
    var audioCodecId: Int = 0
        private set
    var isRunning: Boolean = false
        private set

    private var videoStream: SimpleAdbClient.AdbStream? = null
    private var videoInput: DataInputStream? = null
    private var audioStream: SimpleAdbClient.AdbStream? = null
    private var audioInput: DataInputStream? = null
    private var controlStream: SimpleAdbClient.AdbStream? = null
    private var controlOutput: DataOutputStream? = null
    private var serverStream: SimpleAdbClient.AdbStream? = null

    @Volatile
    private var closed = false

    data class VideoPacket(
        val ptsUs: Long,
        val isConfig: Boolean,
        val isKeyFrame: Boolean,
        val isSession: Boolean,
        val width: Int = 0,
        val height: Int = 0,
        val data: ByteArray = ByteArray(0),
    )

    data class AudioPacket(
        val ptsUs: Long,
        val data: ByteArray,
    )

    private fun extractAndPushServer(): Boolean {
        try {
            Log.i(TAG, "Extracting scrcpy server...")
            val source = context.assets.open(SERVER_ASSET)
            val serverJar = File(context.cacheDir, "scrcpy-server-v4.0.jar")
            source.use { input ->
                serverJar.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "Server: ${serverJar.absolutePath}, size: ${serverJar.length()}")

            Log.i(TAG, "Pushing server to device...")
            val success = client.push(serverJar.absolutePath, SERVER_REMOTE_PATH)
            serverJar.delete()

            if (success) {
                Log.i(TAG, "Server pushed successfully")
            } else {
                Log.e(TAG, "Failed to push server")
            }
            return success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract and push server", e)
            return false
        }
    }

    private fun readDeviceName(input: DataInputStream): String {
        val buffer = ByteArray(DEVICE_NAME_FIELD_LENGTH)
        input.readFully(buffer)
        val firstZero = buffer.indexOf(0)
        val length = if (firstZero >= 0) firstZero else buffer.size
        return buffer.copyOf(length).toString(Charsets.UTF_8)
    }

    fun start(): Boolean {
        try {
            if (!client.isConnected) {
                throw IllegalStateException("ADB client is not connected")
            }

            if (!extractAndPushServer()) {
                throw IOException("Failed to push scrcpy-server.jar to device")
            }

            // Build server command using ClientOptions → ServerParams, exactly like ScrcpyForAndroid
            val scid = (Math.random() * 0x7FFFFFFF).toInt().toUInt()

            // Read settings from SharedPreferences
            val settings = if (prefsName.isNotBlank()) {
                context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences("kdeconnect_prefs", Context.MODE_PRIVATE)
            }
            val videoCodec = Shared.Codec.fromString(
                settings.getString("scrcpy_video_codec", "h264") ?: "h264"
            )
            val audioForward = settings.getBoolean("scrcpy_audio_forward", true)
            val audioCodec = Shared.Codec.fromString(
                settings.getString("scrcpy_audio_codec", "aac") ?: "aac"
            )
            val maxSize = (settings.getString("scrcpy_max_size", "0") ?: "0").toUShortOrNull() ?: 0u
            val maxFps = settings.getString("scrcpy_max_fps", "") ?: ""
            val videoBitRate = (settings.getString("scrcpy_video_bit_rate", "0") ?: "0").toIntOrNull() ?: 0
            val control = settings.getBoolean("scrcpy_control", true)
            val clipboardSync = settings.getBoolean("scrcpy_clipboard_sync", true)
            val keyInjectMode = ClientOptions.KeyInjectMode.fromString(
                settings.getString("scrcpy_key_inject_mode", "mixed") ?: "mixed"
            )
            val stayAwake = settings.getBoolean("scrcpy_stay_awake", false)
            val turnScreenOff = settings.getBoolean("scrcpy_turn_screen_off", false)
            val appStream = settings.getString("scrcpy_app_stream", "") ?: ""
            val newDisplay = settings.getString("scrcpy_new_display", "") ?: ""

            Log.i(TAG, "Settings: videoCodec=$videoCodec, audioForward=$audioForward, audioCodec=$audioCodec, maxSize=$maxSize, maxFps=$maxFps, bitRate=$videoBitRate, control=$control, appStream=$appStream, newDisplay=$newDisplay")

            val options = ClientOptions(
                video = true,
                audio = audioForward,
                control = control,
                videoCodec = videoCodec,
                audioCodec = audioCodec,
                maxSize = maxSize,
                maxFps = maxFps,
                videoBitRate = videoBitRate,
                clipboardAutosync = clipboardSync,
                keyInjectMode = keyInjectMode,
                stayAwake = stayAwake,
                turnScreenOff = turnScreenOff,
                newDisplay = newDisplay,
                startApp = appStream,
                logLevel = Shared.LogLevel.INFO,
            ).validate()
            val serverParams = options.toServerParams(scid)
            val serverCommand = serverParams.build(
                "CLASSPATH=$SERVER_REMOTE_PATH",
                "app_process",
                "/",
                "com.genymobile.scrcpy.Server",
                SERVER_VERSION,
            )
            Log.i(TAG, "Server command: $serverCommand")

            serverStream = client.openStream("shell:$serverCommand")
            Log.i(TAG, "Server shell stream opened")

            // Server log thread
            Thread({
                try {
                    val reader = java.io.BufferedReader(
                        java.io.InputStreamReader(serverStream!!.inputStream, Charsets.UTF_8)
                    )
                    while (!closed) {
                        val line = reader.readLine() ?: break
                        Log.i(TAG, "[server] $line")
                    }
                } catch (e: Exception) {
                    if (!closed) Log.w(TAG, "Server log thread ended", e)
                }
            }, "scrcpy-server-log").start()

            Log.i(TAG, "Waiting ${SERVER_BOOT_DELAY_MS}ms for server boot...")
            Thread.sleep(SERVER_BOOT_DELAY_MS)

            val socketName = socketNameFor(scid.toInt())

            // Phase 1: Open video socket
            Log.i(TAG, "Opening video socket: $socketName")
            videoStream = client.openAbstractSocket(socketName)
                ?: throw IOException("Failed to open video socket")
            // The first client to connect gets a dummy byte from the server
            val dummyByte = videoStream!!.inputStream.read()
            if (dummyByte < 0) throw IOException("Did not receive dummy byte from server")
            videoInput = DataInputStream(BufferedInputStream(videoStream!!.inputStream))
            Log.i(TAG, "Video socket opened")


            // Phase 2: Open audio socket (if enabled)
            if (audioForward) {
                Log.i(TAG, "Opening audio socket...")
                audioStream = client.openAbstractSocket(socketName)
                    ?: throw IOException("Failed to open audio socket")
                audioInput = DataInputStream(BufferedInputStream(audioStream!!.inputStream))
                Log.i(TAG, "Audio socket opened")
            }

            // Phase 3: Open control socket
            Log.i(TAG, "Opening control socket...")
            controlStream = client.openAbstractSocket(socketName)
                ?: throw IOException("Failed to open control socket")
            controlOutput = DataOutputStream(controlStream!!.outputStream)
            Log.i(TAG, "Control socket opened")

            // Phase 4: Read device name (from video stream)
            deviceName = readDeviceName(videoInput!!)
            Log.i(TAG, "Device name: $deviceName")

            // Phase 5: Read video codec ID (from video stream)
            videoCodecId = videoInput!!.readInt()
            Log.i(TAG, "Video codec ID: 0x${String.format("%x", videoCodecId)}")

            // Phase 6: Read audio codec ID (from audio stream, if enabled)
            if (audioForward) {
                audioCodecId = audioInput!!.readInt()
                Log.i(TAG, "Audio codec ID: 0x${String.format("%x", audioCodecId)}")
            }

            screenWidth = 0
            screenHeight = 0

            isRunning = true
            Log.i(TAG, "Scrcpy session started: device=$deviceName")

            // Turn screen off if requested
            if (turnScreenOff) {
                if (!control) {
                    Log.w(TAG, "turnScreenOff ignored because control is disabled")
                } else {
                    Log.i(TAG, "Turning screen off as requested")
                    setDisplayPower(false)
                }
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start scrcpy session", e)
            cleanup()
            return false
        }
    }

    fun readVideoPacket(): VideoPacket? {
        val input = videoInput ?: return null
        if (closed) return null

        try {
            val header = ByteArray(12)
            input.readFully(header)

            val sessionFlagByte = (PACKET_FLAG_SESSION ushr 56).toInt()
            val isSession = (header[0].toInt() and sessionFlagByte) != 0

            if (isSession) {
                // Session packet format: [flags:4][width:4][height:4] (big-endian)
                // flags highest bit=1, lowest bit=clientResized
                val clientResized = (header[3].toInt() and 1) != 0
                val sw = ((header[4].toInt() and 0xFF) shl 24) or
                        ((header[5].toInt() and 0xFF) shl 16) or
                        ((header[6].toInt() and 0xFF) shl 8) or
                        (header[7].toInt() and 0xFF)
                val sh = ((header[8].toInt() and 0xFF) shl 24) or
                        ((header[9].toInt() and 0xFF) shl 16) or
                        ((header[10].toInt() and 0xFF) shl 8) or
                        (header[11].toInt() and 0xFF)
                Log.i(TAG, "Session packet: ${sw}x${sh} clientResized=$clientResized")
                screenWidth = sw
                screenHeight = sh
                return VideoPacket(
                    ptsUs = 0, isConfig = false, isKeyFrame = false,
                    isSession = true, width = sw, height = sh,
                )
            }

            val bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            val ptsAndFlags = bb.long
            val packetSize = bb.int

            if (packetSize <= 0 || packetSize > 10_000_000) return null

            val ptsUs = ptsAndFlags and PACKET_PTS_MASK
            val isConfig = (ptsAndFlags and PACKET_FLAG_CONFIG) != 0L
            val isKeyFrame = (ptsAndFlags and PACKET_FLAG_KEY_FRAME) != 0L

            val data = ByteArray(packetSize)
            input.readFully(data)

            return VideoPacket(
                ptsUs = ptsUs, isConfig = isConfig, isKeyFrame = isKeyFrame,
                isSession = false, data = data,
            )
        } catch (e: Exception) {
            if (!closed) Log.e(TAG, "Failed to read video packet", e)
            return null
        }
    }

    fun readAudioPacket(): AudioPacket? {
        val input = audioInput ?: return null
        if (closed) return null

        try {
            val header = ByteArray(12)
            input.readFully(header)

            val bb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
            val pts = bb.long
            val packetSize = bb.int

            if (packetSize <= 0 || packetSize > 1_000_000) return null

            val data = ByteArray(packetSize)
            input.readFully(data)

            return AudioPacket(pts, data)
        } catch (e: Exception) {
            if (!closed) Log.e(TAG, "Failed to read audio packet", e)
            return null
        }
    }

    fun sendKeyEvent(action: Int, keycode: Int, metaState: Int = 0) {
        val output = controlOutput ?: return
        try {
            val buf = java.io.ByteArrayOutputStream(14)
            val dos = java.io.DataOutputStream(buf)
            dos.writeByte(TYPE_INJECT_KEYCODE)
            dos.writeByte(action)
            dos.writeInt(keycode)
            dos.writeInt(0)
            dos.writeInt(metaState)
            dos.flush()
            Thread {
                try {
                    synchronized(output) {
                        output.write(buf.toByteArray())
                        output.flush()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "sendKeyEvent write failed", e)
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "sendKeyEvent failed", e)
        }
    }

    fun sendTouchEvent(
        action: Int, pointerId: Long, x: Int, y: Int,
        screenWidth: Int, screenHeight: Int, pressure: Float = 1f,
        actionButton: Int = 0, buttons: Int = 0,
    ) {
        val output = controlOutput ?: return
        try {
            val buf = synchronized(output) {
                val buf = java.io.ByteArrayOutputStream(32)
                val dos = java.io.DataOutputStream(buf)
                dos.writeByte(TYPE_INJECT_TOUCH_EVENT)
                dos.writeByte(action)
                dos.writeLong(pointerId)
                dos.writeInt(x)
                dos.writeInt(y)
                dos.writeShort(screenWidth)
                dos.writeShort(screenHeight)
                dos.writeShort(encodeUnsignedFixedPoint16(pressure))
                dos.writeInt(actionButton)
                dos.writeInt(buttons)
                dos.flush()
                buf.toByteArray()
            }
            Thread {
                try {
                    synchronized(output) {
                        output.write(buf)
                        output.flush()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "sendTouchEvent write failed", e)
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "sendTouchEvent failed", e)
        }
    }

    fun sendBack() { sendKeyClick(4) }
    fun sendHome() { sendKeyClick(3) }

    internal fun sendKeyClick(keycode: Int) {
        val output = controlOutput ?: return
        try {
            val buf = java.io.ByteArrayOutputStream(28)
            val dos = java.io.DataOutputStream(buf)
            dos.writeByte(TYPE_INJECT_KEYCODE)
            dos.writeByte(0) // ACTION_DOWN
            dos.writeInt(keycode)
            dos.writeInt(0)
            dos.writeInt(0)
            dos.writeByte(TYPE_INJECT_KEYCODE)
            dos.writeByte(1) // ACTION_UP
            dos.writeInt(keycode)
            dos.writeInt(0)
            dos.writeInt(0)
            dos.flush()
            Thread {
                try {
                    synchronized(output) {
                        output.write(buf.toByteArray())
                        output.flush()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "sendKeyClick write failed", e)
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "sendKeyClick($keycode) failed", e)
        }
    }

    fun setDisplayPower(on: Boolean) {
        val output = controlOutput ?: return
        try {
            synchronized(output) {
                output.writeByte(TYPE_SET_DISPLAY_POWER)
                output.writeBoolean(on)
                output.flush()
            }
            Log.i(TAG, "setDisplayPower($on) sent")
        } catch (e: Exception) {
            Log.e(TAG, "setDisplayPower failed", e)
        }
    }

    fun lockDevice() {
        // Use KEYCODE_SLEEP (223) to lock screen, not KEYCODE_POWER which triggers long-press menu
        sendKeyClick(223)
    }

    fun sendText(text: String) {
        val output = controlOutput ?: return
        try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            synchronized(output) {
                output.writeByte(TYPE_INJECT_TEXT)
                output.writeInt(bytes.size)
                output.write(bytes)
                output.flush()
            }
            Log.i(TAG, "sendText sent")
        } catch (e: Exception) {
            Log.e(TAG, "sendText failed", e)
        }
    }

    fun expandNotificationPanel() {
        val output = controlOutput ?: return
        try {
            synchronized(output) {
                output.writeByte(TYPE_EXPAND_NOTIFICATION_PANEL)
                output.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "expandNotificationPanel failed", e)
        }
    }

    fun expandSettingsPanel() {
        val output = controlOutput ?: return
        try {
            synchronized(output) {
                output.writeByte(TYPE_EXPAND_SETTINGS_PANEL)
                output.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "expandSettingsPanel failed", e)
        }
    }

    fun collapsePanels() {
        val output = controlOutput ?: return
        try {
            synchronized(output) {
                output.writeByte(TYPE_COLLAPSE_PANELS)
                output.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "collapsePanels failed", e)
        }
    }

    fun rotateDevice() {
        val output = controlOutput ?: return
        try {
            synchronized(output) {
                output.writeByte(TYPE_ROTATE_DEVICE)
                output.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "rotateDevice failed", e)
        }
    }

    fun setClipboard(text: String, paste: Boolean) {
        val output = controlOutput ?: return
        try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            synchronized(output) {
                output.writeByte(TYPE_SET_CLIPBOARD)
                output.writeLong(0L) // sequence invalid
                output.writeByte(if (paste) 1 else 0)
                output.writeInt(bytes.size)
                output.write(bytes)
                output.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "setClipboard failed", e)
        }
    }

    fun stop() { closed = true; isRunning = false; cleanup() }

    private fun cleanup() {
        try {
            videoStream?.close()
            audioStream?.close()
            controlStream?.close()
            serverStream?.close()
        } catch (e: Exception) { Log.w(TAG, "Cleanup error", e) }
        videoStream = null; audioStream = null; audioInput = null
        controlStream = null; controlOutput = null
        videoInput = null; serverStream = null
    }

    private fun encodeUnsignedFixedPoint16(value: Float): Int {
        return value.coerceIn(0f, 1f).let {
            if (it >= 1f) 0xffff else (it * 65536f).roundToInt().coerceIn(0, 0xfffe)
        }
    }
}
