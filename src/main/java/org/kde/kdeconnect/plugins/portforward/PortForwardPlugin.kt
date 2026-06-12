package org.kde.kdeconnect.plugins.portforward

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.util.Log
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.PluginFactory.LoadablePlugin
import org.kde.kdeconnect_tp.R

@LoadablePlugin
class PortForwardPlugin : Plugin() {

    override val displayName: String
        get() = context.getString(R.string.pref_plugin_portforward)

    override val description: String
        get() = context.getString(R.string.pref_plugin_portforward_desc)

    override val isEnabledByDefault: Boolean = true

    override fun hasSettings(): Boolean = false

    override fun onCreate(): Boolean {
        if (isDeviceInitialized) {
            autoStartTasks()
        }
        return true
    }

    override fun getUiButtons(): List<PluginUiButton> = listOf(
        PluginUiButton(
            context.getString(R.string.port_forward_open),
            R.drawable.ic_notification
        ) { activity ->
            openPortForwardManager(activity)
        }
    )

    private fun openPortForwardManager(activity: Activity) {
        val intent = Intent(context, PortForwardActivity::class.java).apply {
            putExtra(EXTRA_DEVICE_IP, if (isDeviceInitialized) device.getHostAddress() else "")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun autoStartTasks() {
        val deviceIp = device.getHostAddress()
        if (deviceIp.isNullOrBlank()) {
            Log.w(TAG, "Device IP not available, skipping auto-start")
            return
        }

        Thread {
            try {
                val db = PortForwardDatabase.getInstance(context)
                val tasks = db.getAutoStartTasks()
                if (tasks.isEmpty()) return@Thread

                Log.i(TAG, "Auto-starting ${tasks.size} port forward tasks to $deviceIp")
                for (task in tasks) {
                    val intent = Intent(context, PortForwardService::class.java).apply {
                        action = PortForwardService.ACTION_START
                        putExtra(PortForwardService.EXTRA_TASK_ID, task.id)
                        putExtra(PortForwardService.EXTRA_DEVICE_IP, deviceIp)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                    else context.startService(intent)
                    db.updateTask(task.copy(status = PortForwardTask.STATUS_RUNNING))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-start failed", e)
            }
        }.start()
    }

    override fun onDestroy() {
        // Stop all running tasks when plugin is destroyed (device disconnects)
        val intent = Intent(context, PortForwardService::class.java).apply {
            action = PortForwardService.ACTION_STOP_ALL
        }
        context.startService(intent)
    }

    // Declare ping capability so the plugin always loads for any device
    override val supportedPacketTypes: Array<String> = arrayOf("kdeconnect.ping")
    override val outgoingPacketTypes: Array<String> = emptyArray()

    companion object {
        private const val TAG = "PortForwardPlugin"
        const val EXTRA_DEVICE_IP = "device_ip"
    }
}
