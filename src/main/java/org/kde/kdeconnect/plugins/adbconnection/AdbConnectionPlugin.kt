/*
 * SPDX-FileCopyrightText: 2024 KDE Connect Contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.adbconnection

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import org.kde.kdeconnect.NetworkPacket
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R

@LoadablePlugin
class AdbConnectionPlugin : Plugin() {

    private var adbClient: SimpleAdbClient? = null

    override val displayName: String
        get() = context.resources.getString(R.string.pref_plugin_adbconnection)

    override val description: String
        get() = context.resources.getString(R.string.pref_plugin_adbconnection_desc)

    override val isEnabledByDefault: Boolean = true

    override fun onCreate(): Boolean {
        Log.i(LOG_TAG, "AdbConnectionPlugin created, device host: ${if (isDeviceInitialized) device.getHostAddress() else "null"}")
        
        // Auto connect if enabled
        if (isDeviceInitialized && preferences?.getBoolean("auto_connect", false) == true) {
            Log.i(LOG_TAG, "Auto connect enabled, connecting to ADB...")
            connectToDevice()
        }
        
        return true
    }

    override fun onDestroy() {
        disconnect()
    }

    override fun hasSettings(): Boolean = true

    override fun supportsDeviceSpecificSettings(): Boolean = false

    override fun getSettingsFragment(activity: Activity): org.kde.kdeconnect.ui.PluginSettingsFragment {
        return AdbConnectionSettingsFragment.newInstance(pluginKey, R.xml.adbconnection_preferences)
    }

    override fun getUiButtons(): List<PluginUiButton> {
        val buttons = mutableListOf<PluginUiButton>()

        if (isDeviceInitialized) {
            val isConnected = adbClient?.isConnected == true

            if (!isConnected) {
                buttons.add(
                    PluginUiButton(
                        context.getString(R.string.adb_connect),
                        R.drawable.ic_notification
                    ) { parentActivity ->
                        connectToDevice()
                    }
                )
            } else {
                buttons.add(
                    PluginUiButton(
                        context.getString(R.string.adb_scrcpy),
                        R.drawable.ic_notification
                    ) { parentActivity ->
                        startScrcpy()
                    }
                )
                buttons.add(
                    PluginUiButton(
                        context.getString(R.string.adb_disconnect),
                        R.drawable.ic_notification
                    ) { parentActivity ->
                        disconnect()
                    }
                )
            }
        }

        return buttons
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingScrcpyLaunch = false
    private var autoRelaunchScrcpyOnReconnect = false
    private var reconnecting = false

    fun connectToDevice() {
        if (!isDeviceInitialized) return

        val host = getDeviceHost()
        val port = getAdbPort()

        mainHandler.post {
            Toast.makeText(context, "Connecting to ADB at $host:$port ...", Toast.LENGTH_SHORT).show()
        }

        Thread {
            try {
                Log.i(LOG_TAG, "Connecting to ADB at $host:$port")

                val client = SimpleAdbClient(host, port, preferences!!)
                val connected = client.connect()

                if (connected) {
                    adbClient = client
                    sharedAdbClient = client

                    // Set up disconnect callback for auto-reconnect
                    client.onDisconnect = {
                        mainHandler.post {
                            adbClient = null
                            sharedAdbClient = null
                            if (isDeviceInitialized) device.reloadPluginsFromSettings()
                            if (preferences?.getBoolean("auto_connect", false) == true) {
                                scheduleReconnect()
                            }
                        }
                    }

                    Log.i(LOG_TAG, "ADB connected successfully")
                    mainHandler.post {
                        Toast.makeText(context, "ADB connected!", Toast.LENGTH_SHORT).show()
                        if (isDeviceInitialized) device.reloadPluginsFromSettings()

                        // If scrcpy was requested while connecting, launch it now
                        if (pendingScrcpyLaunch || autoRelaunchScrcpyOnReconnect) {
                            pendingScrcpyLaunch = false
                            startScrcpy()
                        }
                    }
                } else {
                    Log.e(LOG_TAG, "ADB connection failed")
                    client.close()
                    pendingScrcpyLaunch = false
                    mainHandler.post {
                        Toast.makeText(context, "ADB connection failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "ADB connection error", e)
                pendingScrcpyLaunch = false
                mainHandler.post {
                    Toast.makeText(context, "ADB error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun scheduleReconnect() {
        if (reconnecting) return
        reconnecting = true
        val delayMs = preferences?.getString("adb_reconnect_delay", "5000")?.toLongOrNull() ?: 5000L
        val delayText = when (delayMs) {
            500L -> "0.5s"
            1000L -> "1s"
            2000L -> "2s"
            else -> "5s"
        }
        mainHandler.post {
            Toast.makeText(context, "ADB disconnected, reconnecting in $delayText...", Toast.LENGTH_SHORT).show()
        }
        Thread {
            Thread.sleep(delayMs)
            reconnecting = false
            if (adbClient == null && preferences?.getBoolean("auto_connect", false) == true) {
                connectToDevice()
            }
        }.start()
    }

    fun disconnect() {
        pendingScrcpyLaunch = false
        autoRelaunchScrcpyOnReconnect = false
        adbClient?.close()
        adbClient = null
        sharedAdbClient = null
        mainHandler.post {
            Toast.makeText(context, "ADB disconnected", Toast.LENGTH_SHORT).show()
            if (isDeviceInitialized) device.reloadPluginsFromSettings()
        }
    }

    fun executeCommand(command: String): String? {
        return adbClient?.shell(command)
    }

    fun pushFile(localPath: String, remotePath: String): Boolean {
        return adbClient?.push(localPath, remotePath) == true
    }

    fun pullFile(remotePath: String, localPath: String): Boolean {
        return adbClient?.pull(remotePath, localPath) == true
    }

    fun getAdbClient(): SimpleAdbClient? = adbClient

    private fun startScrcpy() {
        if (!isDeviceInitialized) return
        if (adbClient?.isConnected != true) {
            if (preferences?.getBoolean("auto_connect", false) == true) {
                pendingScrcpyLaunch = true
                autoRelaunchScrcpyOnReconnect = true
                connectToDevice()
            } else {
                mainHandler.post {
                    Toast.makeText(context, "ADB not connected", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        autoRelaunchScrcpyOnReconnect = true

        val host = getDeviceHost()
        val port = getAdbPort()
        val launchMode = preferences?.getString("scrcpy_launch_mode", "fullscreen") ?: "fullscreen"

        if (launchMode == "floating") {
            val serviceIntent = Intent(context, ScrcpyFloatingService::class.java).apply {
                action = ScrcpyFloatingService.ACTION_START
                putExtra(ScrcpyFloatingService.EXTRA_HOST, host)
                putExtra(ScrcpyFloatingService.EXTRA_PORT, port)
                putExtra(ScrcpyFloatingService.EXTRA_PREFS_NAME, sharedPreferencesName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } else {
            val intent = Intent(context, ScrcpyActivity::class.java).apply {
                putExtra(ScrcpyActivity.EXTRA_HOST, host)
                putExtra(ScrcpyActivity.EXTRA_PORT, port)
                putExtra(ScrcpyActivity.EXTRA_PREFS_NAME, sharedPreferencesName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private fun pushScrcpyServer(): String? {
        val remotePath = "/data/local/tmp/scrcpy-server.jar"
        val assetManager = context.assets

        try {
            val inputStream = assetManager.open("scrcpy-server.jar")
            val tempFile = java.io.File(context.cacheDir, "scrcpy-server.jar")
            tempFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
            inputStream.close()

            val success = pushFile(tempFile.absolutePath, remotePath)
            tempFile.delete()

            return if (success) remotePath else null
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to push scrcpy server", e)
            return null
        }
    }

    private fun launchScrcpyServer(serverPath: String) {
        val command = "CLASSPATH=$serverPath app_process / com.genymobile.scrcpy.Server 2.0"
        val result = executeCommand(command)
        Log.i(LOG_TAG, "scrcpy server launched: $result")
    }

    private fun getDeviceHost(): String {
        val fromDevice = if (isDeviceInitialized) device.getHostAddress() else null
        if (!fromDevice.isNullOrBlank()) return fromDevice

        val prefs = preferences
        val defaultHost = "192.168.1.100"
        return prefs?.getString("adb_host", null) ?: defaultHost
    }

    private fun getAdbPort(): Int {
        val prefs = preferences
        val defaultPort = 5555
        return prefs?.getString("adb_port", null)?.toIntOrNull() ?: defaultPort
    }

    override val supportedPacketTypes: Array<String> = arrayOf(PACKET_TYPE_ADB_REQUEST)

    override val outgoingPacketTypes: Array<String> = arrayOf(PACKET_TYPE_ADB_REQUEST)

    companion object {
        private const val LOG_TAG = "AdbConnectionPlugin"
        const val PACKET_TYPE_ADB_REQUEST = "kdeconnect.adbconnection.request"

        @Volatile
        var sharedAdbClient: SimpleAdbClient? = null
            private set
    }
}
