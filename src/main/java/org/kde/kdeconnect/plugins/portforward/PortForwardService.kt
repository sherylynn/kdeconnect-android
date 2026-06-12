package org.kde.kdeconnect.plugins.portforward

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PortForwardService : Service() {

    companion object {
        private const val TAG = "PortForwardService"
        private const val CHANNEL_ID = "port_forward_channel"
        private const val NOTIFICATION_ID = 2001
        const val ACTION_START = "org.kde.kdeconnect.portforward.START"
        const val ACTION_STOP = "org.kde.kdeconnect.portforward.STOP"
        const val ACTION_STOP_ALL = "org.kde.kdeconnect.portforward.STOP_ALL"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_DEVICE_IP = "device_ip"
    }

    private val runningTasks = ConcurrentHashMap<Long, ForwardThread>()
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private var database: PortForwardDatabase? = null

    override fun onCreate() {
        super.onCreate()
        database = PortForwardDatabase.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1)
                val deviceIp = intent.getStringExtra(EXTRA_DEVICE_IP) ?: ""
                if (taskId != -1L && deviceIp.isNotBlank()) startTask(taskId, deviceIp)
            }
            ACTION_STOP -> {
                val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1)
                if (taskId != -1L) stopTask(taskId)
            }
            ACTION_STOP_ALL -> stopAllTasks()
        }
        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopAllTasks()
        executor.shutdown()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "端口转发", NotificationManager.IMPORTANCE_LOW)
            channel.description = "显示端口转发任务状态"
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("端口转发")
            .setContentText("运行中 ${runningTasks.size} 个任务")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startTask(taskId: Long, deviceIp: String) {
        val db = database ?: return
        val task = db.getTask(taskId) ?: return

        stopTask(taskId)

        val thread = ForwardThread(task, deviceIp, db) { updateNotification() }
        runningTasks[taskId] = thread
        executor.submit(thread)
    }

    private fun stopTask(taskId: Long) {
        runningTasks.remove(taskId)?.stop()
        val db = database ?: return
        db.getTask(taskId)?.let {
            db.updateTask(it.copy(status = PortForwardTask.STATUS_STOPPED))
        }
    }

    private fun stopAllTasks() {
        runningTasks.forEach { (id, thread) ->
            thread.stop()
            database?.getTask(id)?.let { task ->
                database?.updateTask(task.copy(status = PortForwardTask.STATUS_STOPPED))
            }
        }
        runningTasks.clear()
    }

    private class ForwardThread(
        private val task: PortForwardTask,
        private val deviceIp: String,
        private val db: PortForwardDatabase,
        private val onStateChanged: () -> Unit,
    ) : Runnable {
        @Volatile var running = true
        private var serverSocket: ServerSocket? = null

        override fun run() {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(task.localPort))
                }
                db.updateTask(task.copy(status = PortForwardTask.STATUS_RUNNING))
                onStateChanged()

                Log.i(TAG, "Forwarding localhost:${task.localPort} -> $deviceIp:${task.remotePort}")

                while (running) {
                    try {
                        val client = serverSocket!!.accept()
                        executor.submit(ProxyThread(client, task, deviceIp, db))
                    } catch (e: IOException) {
                        if (running) Log.e(TAG, "Accept error", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error on port ${task.localPort}", e)
                db.updateTask(task.copy(status = PortForwardTask.STATUS_ERROR, errorMessage = e.message))
                onStateChanged()
            }
        }

        fun stop() {
            running = false
            try { serverSocket?.close() } catch (_: Exception) {}
        }

        private val executor: ExecutorService = Executors.newCachedThreadPool()
    }

    private class ProxyThread(
        private val client: Socket,
        private val task: PortForwardTask,
        private val deviceIp: String,
        private val db: PortForwardDatabase,
    ) : Runnable {
        override fun run() {
            var remote: Socket? = null
            try {
                remote = Socket().apply {
                    connect(InetSocketAddress(deviceIp, task.remotePort), 10_000)
                }
                val t1 = Thread(DataPump(client, remote, task, db))
                val t2 = Thread(DataPump(remote, client, task, db))
                t1.start(); t2.start()
                t1.join(); t2.join()
            } catch (e: Exception) {
                Log.e(TAG, "Proxy error: ${task.displayName}", e)
            } finally {
                try { client.close() } catch (_: Exception) {}
                try { remote?.close() } catch (_: Exception) {}
            }
        }
    }

    private class DataPump(
        private val from: Socket,
        private val to: Socket,
        private val task: PortForwardTask,
        private val db: PortForwardDatabase,
    ) : Runnable {
        override fun run() {
            val buf = ByteArray(8192)
            try {
                var total = task.bytesTransferred
                while (!from.isClosed && !to.isClosed) {
                    val n = from.getInputStream().read(buf)
                    if (n == -1) break
                    to.getOutputStream().write(buf, 0, n)
                    to.getOutputStream().flush()
                    total += n
                    if (total - task.bytesTransferred > 4096) {
                        db.updateTask(task.copy(bytesTransferred = total))
                    }
                }
                db.updateTask(task.copy(bytesTransferred = total))
            } catch (_: Exception) {}
        }
    }
}
