/*
 * SPDX-FileCopyrightText: 2024 KDE Connect Contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.adbconnection

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.kde.kdeconnect_tp.R

class AdbTerminalActivity : AppCompatActivity() {

    private lateinit var terminalOutput: TextView
    private lateinit var commandInput: EditText
    private lateinit var scrollView: ScrollView
    private var adbClient: SimpleAdbClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_adb_terminal)

        terminalOutput = findViewById(R.id.terminal_output)
        commandInput = findViewById(R.id.command_input)
        scrollView = findViewById(R.id.scroll_view)

        val connectButton = findViewById<Button>(R.id.connect_button)
        val executeButton = findViewById<Button>(R.id.execute_button)
        val clearButton = findViewById<Button>(R.id.clear_button)

        connectButton.setOnClickListener {
            connectToAdb()
        }

        executeButton.setOnClickListener {
            executeCommand()
        }

        clearButton.setOnClickListener {
            terminalOutput.text = ""
        }

        appendOutput("ADB Terminal Ready")
        appendOutput("Enter host and port, then tap Connect")
    }

    private fun connectToAdb() {
        val hostInput = findViewById<EditText>(R.id.host_input)
        val portInput = findViewById<EditText>(R.id.port_input)

        val host = hostInput.text.toString().trim()
        val port = portInput.text.toString().trim().toIntOrNull() ?: 5555

        if (host.isEmpty()) {
            appendOutput("Error: Host cannot be empty")
            return
        }

        appendOutput("Connecting to $host:$port...")

        Thread {
            try {
                val client = SimpleAdbClient(host, port, getSharedPreferences("adb_terminal_prefs", MODE_PRIVATE))
                val connected = client.connect()

                runOnUiThread {
                    if (connected) {
                        adbClient = client
                        appendOutput("Connected successfully!")
                    } else {
                        appendOutput("Connection failed")
                        client.close()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    appendOutput("Error: ${e.message}")
                }
            }
        }.start()
    }

    private fun executeCommand() {
        val command = commandInput.text.toString().trim()
        if (command.isEmpty()) {
            appendOutput("Error: Command cannot be empty")
            return
        }

        if (adbClient?.isConnected != true) {
            appendOutput("Error: Not connected to ADB")
            return
        }

        appendOutput("\n$ $command")

        Thread {
            try {
                val result = adbClient?.shell(command)
                runOnUiThread {
                    if (result != null) {
                        appendOutput(result)
                    } else {
                        appendOutput("No output")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    appendOutput("Error: ${e.message}")
                }
            }
        }.start()

        commandInput.text.clear()
    }

    private fun appendOutput(text: String) {
        terminalOutput.append("$text\n")
        scrollView.post {
            scrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        adbClient?.close()
    }

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
    }
}
