/*
 * SPDX-FileCopyrightText: 2024 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.adbconnection

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.base.BaseActivity
import org.kde.kdeconnect_tp.databinding.ActivityShareBinding // Reusing for now, will create a dedicated layout later
import java.io.File
import java.io.FileOutputStream

class AdbInstallActivity : BaseActivity<ActivityShareBinding>() {

    override val binding: ActivityShareBinding by lazy { ActivityShareBinding.inflate(layoutInflater) }
    override val isScrollable: Boolean = false // Not scrollable for now

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        val apkUri: Uri? = intent.getParcelableExtra(EXTRA_APK_URI)
        val deviceId: String? = intent.getStringExtra(EXTRA_DEVICE_ID)

        if (apkUri != null && deviceId != null) {
            Log.i(TAG, "Received APK URI: $apkUri for device: $deviceId")
            Toast.makeText(this, "Attempting to install APK on device: $deviceId", Toast.LENGTH_LONG).show()

            lifecycleScope.launch {
                installApk(apkUri, deviceId)
            }

        } else {
            Log.e(TAG, "AdbInstallActivity started with missing APK URI or Device ID.")
            Toast.makeText(this, "Error: Missing APK URI or Device ID.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private suspend fun installApk(apkUri: Uri, deviceId: String) {
        withContext(Dispatchers.IO) {
            val adbConnectionPlugin = KdeConnect.getInstance().getDevicePlugin(
                deviceId,
                AdbConnectionPlugin::class.java
            )

            if (adbConnectionPlugin == null) {
                Log.e(TAG, "AdbConnectionPlugin not found for device: $deviceId")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AdbInstallActivity, "Error: ADB connection not available for device.", Toast.LENGTH_LONG).show()
                    finish()
                }
                return@withContext
            }

            // Create a temporary local file from the APK URI
            val tempFile = File(cacheDir, "temp_apk_to_install.apk")
            var remoteApkPath: String? = null

            try {
                contentResolver.openInputStream(apkUri)?.use { inputStream ->
                    FileOutputStream(tempFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                if (!tempFile.exists() || tempFile.length() == 0L) {
                    throw Exception("Failed to create temporary APK file locally.")
                }

                remoteApkPath = "$REMOTE_TEMP_PATH${tempFile.name}"
                val pushSuccess = adbConnectionPlugin.pushFile(tempFile.absolutePath, remoteApkPath)

                if (pushSuccess) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AdbInstallActivity, "APK pushed to device. Installing...", Toast.LENGTH_LONG).show()
                    }
                    val installCommand = "pm install -r $remoteApkPath" // -r for replace existing app
                    val installOutput = adbConnectionPlugin.executeCommand(installCommand)
                    Log.i(TAG, "ADB install command output: $installOutput")

                    if (installOutput?.contains("Success") == true) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@AdbInstallActivity, "APK installed successfully!", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@AdbInstallActivity, "APK installation failed: $installOutput", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@AdbInstallActivity, "Failed to push APK to device.", Toast.LENGTH_LONG).show()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error during APK installation: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AdbInstallActivity, "Error installing APK: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                // Clean up temporary files
                tempFile.delete()
                remoteApkPath?.let { path ->
                    adbConnectionPlugin.executeCommand("rm $path")
                    Log.i(TAG, "Cleaned up remote temporary APK: $path")
                }
                withContext(Dispatchers.Main) {
                    finish()
                }
            }
        }
    }

    companion object {
        private const val TAG = "AdbInstallActivity"
        const val ACTION_INSTALL_APK = "org.kde.kdeconnect.plugins.adbconnection.INSTALL_APK"
        const val EXTRA_APK_URI = "extra_apk_uri"
        const val EXTRA_DEVICE_ID = "extra_device_id"
        private const val REMOTE_TEMP_PATH = "/data/local/tmp/"
    }
}
