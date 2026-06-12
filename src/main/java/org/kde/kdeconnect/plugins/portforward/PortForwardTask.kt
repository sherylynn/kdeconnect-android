package org.kde.kdeconnect.plugins.portforward

data class PortForwardTask(
    val id: Long = 0,
    val name: String = "",
    val remotePort: Int = 0,
    val localPort: Int = 0,
    val protocol: String = "tcp",
    val autoStart: Boolean = false,
    val status: Int = STATUS_STOPPED,
    val createdAt: Long = System.currentTimeMillis(),
    val bytesTransferred: Long = 0,
    val errorMessage: String? = null,
) {
    companion object {
        const val STATUS_STOPPED = 0
        const val STATUS_RUNNING = 1
        const val STATUS_CONNECTING = 2
        const val STATUS_ERROR = 3
    }

    val displayName: String get() = name.ifBlank { "未命名任务" }

    val statusText: String get() = when (status) {
        STATUS_RUNNING -> "运行中"
        STATUS_CONNECTING -> "连接中"
        STATUS_ERROR -> "错误"
        else -> "已停止"
    }
}
