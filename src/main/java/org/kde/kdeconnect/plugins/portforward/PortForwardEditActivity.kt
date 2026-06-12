package org.kde.kdeconnect.plugins.portforward

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import org.kde.kdeconnect_tp.R

class PortForwardEditActivity : AppCompatActivity() {

    private lateinit var database: PortForwardDatabase
    private lateinit var editName: TextInputEditText
    private lateinit var editRemotePort: TextInputEditText
    private lateinit var editLocalPort: TextInputEditText
    private lateinit var radioProtocol: RadioGroup
    private lateinit var switchAutoStart: SwitchMaterial
    private var editingTaskId: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_port_forward_edit)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        database = PortForwardDatabase.getInstance(this)
        editName = findViewById(R.id.editTaskName)
        editRemotePort = findViewById(R.id.editRemotePort)
        editLocalPort = findViewById(R.id.editLocalPort)
        radioProtocol = findViewById(R.id.radioProtocol)
        switchAutoStart = findViewById(R.id.switchAutoStart)

        editingTaskId = intent.getLongExtra("task_id", -1)
        if (editingTaskId > 0) {
            supportActionBar?.title = "编辑任务"
            loadTask()
        } else {
            supportActionBar?.title = "创建任务"
        }

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener { saveTask() }
    }

    private fun loadTask() {
        database.getTask(editingTaskId)?.let { task ->
            editName.setText(task.name)
            editRemotePort.setText(task.remotePort.toString())
            editLocalPort.setText(task.localPort.toString())
            switchAutoStart.isChecked = task.autoStart
            if (task.protocol == "udp") findViewById<RadioButton>(R.id.radioUdp).isChecked = true
            else findViewById<RadioButton>(R.id.radioTcp).isChecked = true
        }
    }

    private fun saveTask() {
        val name = editName.text?.toString()?.trim() ?: ""
        val remotePortStr = editRemotePort.text?.toString()?.trim() ?: ""
        val localPortStr = editLocalPort.text?.toString()?.trim() ?: ""

        val remotePort = remotePortStr.toIntOrNull()?.takeIf { it in 1..65535 }
        if (remotePort == null) { toast("远程端口范围 1-65535"); return }

        val localPort = localPortStr.toIntOrNull()?.takeIf { it in 1..65535 }
        if (localPort == null) { toast("本地端口范围 1-65535"); return }

        val protocol = if (findViewById<RadioButton>(R.id.radioUdp).isChecked) "udp" else "tcp"
        val taskName = name.ifBlank { "未命名任务" }
        val autoStart = switchAutoStart.isChecked

        if (editingTaskId > 0) {
            database.getTask(editingTaskId)?.let {
                database.updateTask(it.copy(name = taskName, remotePort = remotePort, localPort = localPort, protocol = protocol, autoStart = autoStart))
            }
            toast("任务已更新")
        } else {
            database.addTask(PortForwardTask(name = taskName, remotePort = remotePort, localPort = localPort, protocol = protocol, autoStart = autoStart))
            toast("任务已创建")
        }
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
