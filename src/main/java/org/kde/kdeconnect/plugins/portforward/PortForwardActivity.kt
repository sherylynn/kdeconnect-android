package org.kde.kdeconnect.plugins.portforward

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.kde.kdeconnect_tp.R

class PortForwardActivity : AppCompatActivity() {

    private lateinit var database: PortForwardDatabase
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: TaskListAdapter
    private lateinit var emptyView: View
    private var deviceIp: String = ""

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            loadTasks()
            refreshHandler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_port_forward)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.port_forward_title)

        deviceIp = intent.getStringExtra(PortForwardPlugin.EXTRA_DEVICE_IP) ?: ""

        database = PortForwardDatabase.getInstance(this)
        recyclerView = findViewById(R.id.recyclerView)
        emptyView = findViewById(R.id.emptyView)
        adapter = TaskListAdapter()

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<View>(R.id.fabAdd).setOnClickListener {
            val intent = Intent(this, PortForwardEditActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        loadTasks()
        autoStartPendingTasks()
        refreshHandler.postDelayed(refreshRunnable, 2000)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun loadTasks() {
        val tasks = database.getAllTasks()
        adapter.submitList(tasks)
        emptyView.visibility = if (tasks.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (tasks.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun autoStartPendingTasks() {
        if (deviceIp.isBlank()) return
        val tasks = database.getAllTasks()
        for (task in tasks) {
            if (task.autoStart && task.status != PortForwardTask.STATUS_RUNNING) {
                startTask(task)
            }
        }
    }

    private fun startTask(task: PortForwardTask) {
        if (deviceIp.isBlank()) {
            Toast.makeText(this, "未获取到设备 IP", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, PortForwardService::class.java).apply {
            action = PortForwardService.ACTION_START
            putExtra(PortForwardService.EXTRA_TASK_ID, task.id)
            putExtra(PortForwardService.EXTRA_DEVICE_IP, deviceIp)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun stopTask(task: PortForwardTask) {
        val intent = Intent(this, PortForwardService::class.java).apply {
            action = PortForwardService.ACTION_STOP
            putExtra(PortForwardService.EXTRA_TASK_ID, task.id)
        }
        startService(intent)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    inner class TaskListAdapter : RecyclerView.Adapter<TaskListAdapter.VH>() {
        private var items = listOf<PortForwardTask>()

        fun submitList(list: List<PortForwardTask>) {
            items = list; notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            LayoutInflater.from(parent.context).inflate(R.layout.item_port_forward_task, parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val name: TextView = v.findViewById(R.id.taskName)
            private val status: TextView = v.findViewById(R.id.taskStatus)
            private val remote: TextView = v.findViewById(R.id.remoteAddress)
            private val local: TextView = v.findViewById(R.id.localPort)
            private val proto: TextView = v.findViewById(R.id.protocol)
            private val autoStartLabel: TextView = v.findViewById(R.id.autoStartLabel)
            private val btnToggle: ImageButton = v.findViewById(R.id.btnToggle)

            fun bind(task: PortForwardTask) {
                name.text = task.displayName
                status.text = task.statusText
                val statusColor = when (task.status) {
                    PortForwardTask.STATUS_RUNNING -> 0xFF00B42A.toInt()
                    PortForwardTask.STATUS_CONNECTING -> 0xFFFF7D00.toInt()
                    PortForwardTask.STATUS_ERROR -> 0xFFF53F3F.toInt()
                    else -> 0xFF86909C.toInt()
                }
                status.setBackgroundColor(statusColor)
                remote.text = "${deviceIp.ifBlank { "?" }}:${task.remotePort}"
                local.text = "${task.localPort}"
                proto.text = task.protocol.uppercase()
                autoStartLabel.visibility = if (task.autoStart) View.VISIBLE else View.GONE
                btnToggle.setImageResource(
                    if (task.status == PortForwardTask.STATUS_RUNNING) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play
                )

                itemView.setOnClickListener {
                    val intent = Intent(this@PortForwardActivity, PortForwardEditActivity::class.java)
                    intent.putExtra("task_id", task.id)
                    startActivity(intent)
                }

                itemView.setOnLongClickListener {
                    AlertDialog.Builder(this@PortForwardActivity)
                        .setTitle("删除任务")
                        .setMessage("确定删除 ${task.displayName}？")
                        .setPositiveButton("删除") { _, _ ->
                            stopTask(task)
                            database.deleteTask(task.id)
                            loadTasks()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }

                btnToggle.setOnClickListener {
                    if (task.status == PortForwardTask.STATUS_RUNNING) stopTask(task)
                    else startTask(task)
                }
            }
        }
    }
}
