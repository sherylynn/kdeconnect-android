/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.plugins.share

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.webkit.URLUtil
import android.widget.Toast
import androidx.appcompat.app.ActionBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.preference.PreferenceManager
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.base.BaseActivity
import org.kde.kdeconnect.plugins.adbconnection.AdbInstallActivity
import org.kde.kdeconnect.ui.compose.KdeTheme
import org.kde.kdeconnect.ui.compose.extensions.device.toUiModel
import org.kde.kdeconnect.ui.compose.model.device.DeviceUiModel
import org.kde.kdeconnect.ui.compose.screen.share.ShareScreen
import org.kde.kdeconnect_tp.R
import org.kde.kdeconnect_tp.databinding.ActivityShareBinding

class ShareActivity : BaseActivity<ActivityShareBinding>() {

    private lateinit var mSharedPrefs: SharedPreferences

    override val binding: ActivityShareBinding by lazy { ActivityShareBinding.inflate(layoutInflater) }

    override val isScrollable: Boolean = true

    private var isRefreshing by mutableStateOf(value = false)
    private var uiDevices by mutableStateOf<List<DeviceUiModel>>(value = emptyList())
    private var intentHasUrl by mutableStateOf(value = false)

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.refresh, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.menu_refresh) {
            refreshDevicesAction()
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    private fun refreshDevicesAction() {
        isRefreshing = true

        BackgroundService.ForceRefreshConnections(context = this)

        binding.devicesListLayout.composeView.postDelayed({
            isRefreshing = false
        }, 1500)
    }

    private fun updateDeviceList() {
        val intent = intent
        val action = intent.action
        if (Intent.ACTION_SEND != action && Intent.ACTION_SEND_MULTIPLE != action) {
            finish()
            return
        }
        val devices = KdeConnect.getInstance().devices.values
        this.intentHasUrl = doesIntentContainUrl(intent)
        this.uiDevices = devices
            .filter { device -> device.isPaired && (intentHasUrl || device.isReachable) }
            .map { it.toUiModel() }
    }

    private fun deviceClicked(
        device: Device,
        intentHasUrl: Boolean,
        intent: Intent
    ) {
        val plugin: SharePlugin? =
            KdeConnect.getInstance().getDevicePlugin(
                deviceId = device.deviceId,
                pluginClass = SharePlugin::class.java
            )

        val isApk = intent.type == APK_MIME_TYPE && (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE)

        if (isApk) {
            val apkUri: Uri? = if (intent.action == Intent.ACTION_SEND) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            } else {
                (intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))?.firstOrNull()
            }

            if (apkUri != null) {
                val installIntent = Intent(this, AdbInstallActivity::class.java).apply {
                    action = AdbInstallActivity.ACTION_INSTALL_APK
                    putExtra(AdbInstallActivity.EXTRA_APK_URI, apkUri)
                    putExtra(AdbInstallActivity.EXTRA_DEVICE_ID, device.deviceId)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(installIntent)
            } else {
                Toast.makeText(this, "Failed to get APK URI for installation.", Toast.LENGTH_LONG).show()
                Log.e(TAG, "Failed to get APK URI for installation.")
            }
        } else if (intentHasUrl && !device.isReachable) {
            // Store the URL to be delivered once device becomes online
            storeUrlForFutureDelivery(
                device = device,
                url = intent.getStringExtra(Intent.EXTRA_TEXT)
            )
        } else {
            plugin?.share(intent)
        }
        finish()
    }

    private fun doesIntentContainUrl(intent: Intent?): Boolean {
        intent?.extras?.let { extras ->
            val url = extras.getString(Intent.EXTRA_TEXT)
            return URLUtil.isHttpUrl(url) || URLUtil.isHttpsUrl(url)
        }
        return false
    }

    private fun storeUrlForFutureDelivery(
        device: Device,
        url: String?
    ) {
        val key = KEY_UNREACHABLE_URL_LIST + device.deviceId
        val oldUrlSet = mSharedPrefs.getStringSet(key, null)
        // According to the API docs, we should not directly modify the set returned above
        val newUrlSet = mutableSetOf<String>()
        url?.let { urlSet -> newUrlSet.add(urlSet) }
        if (oldUrlSet != null) {
            newUrlSet.addAll(oldUrlSet)
        }

        mSharedPrefs.edit().putStringSet(key, newUrlSet).apply()
        Toast.makeText(this, getString(R.string.unreachable_share_toast), Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mSharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)

        setSupportActionBar(binding.toolbarLayout.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            displayOptions =
                ActionBar.DISPLAY_SHOW_HOME or ActionBar.DISPLAY_SHOW_TITLE or ActionBar.DISPLAY_SHOW_CUSTOM
        }

        binding.devicesListLayout.composeView.setContent {
            KdeTheme(this) {
                ShareScreen(
                    devices = uiDevices,
                    intentHasUrl = intentHasUrl,
                    isRefreshing = isRefreshing,
                    onDeviceClick = { deviceId ->
                        val device = KdeConnect.getInstance().getDevice(id = deviceId)
                            ?: return@ShareScreen
                        deviceClicked(
                            device = device,
                            intentHasUrl = intentHasUrl,
                            intent = intent
                        )
                    },
                    onRefresh = { refreshDevicesAction() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()

        val intent = intent
        var deviceId = intent.getStringExtra("deviceId")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && deviceId == null) {
            deviceId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID)
        }

        if (deviceId != null) {
            val plugin: SharePlugin? =
                KdeConnect.getInstance().getDevicePlugin(deviceId, SharePlugin::class.java)
            val device = KdeConnect.getInstance().getDevice(id = deviceId)

            val isApk = intent.type == APK_MIME_TYPE && (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE)

            if (isApk && device != null) {
                val apkUri: Uri? = if (intent.action == Intent.ACTION_SEND) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                } else {
                    (intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))?.firstOrNull()
                }

                if (apkUri != null) {
                    val installIntent = Intent(this, AdbInstallActivity::class.java).apply {
                        action = AdbInstallActivity.ACTION_INSTALL_APK
                        putExtra(AdbInstallActivity.EXTRA_APK_URI, apkUri)
                        putExtra(AdbInstallActivity.EXTRA_DEVICE_ID, device.deviceId)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(installIntent)
                } else {
                    Toast.makeText(this, "Failed to get APK URI for installation.", Toast.LENGTH_LONG).show()
                    Log.e(TAG, "Failed to get APK URI for installation.")
                }
            } else if (plugin != null) {
                plugin.share(intent)
            } else {
                val extras = intent.extras
                if (extras != null && extras.containsKey(Intent.EXTRA_TEXT)) {
                    if (doesIntentContainUrl(intent) && device != null && !device.isReachable) {
                        val text = extras.getString(Intent.EXTRA_TEXT)
                        storeUrlForFutureDelivery(
                            device = device,
                            url = text
                        )
                    }
                }
            }
            finish()
        } else {
            KdeConnect.getInstance().addDeviceListChangedCallback(key = "ShareActivity") {
                runOnUiThread { updateDeviceList() }
            }
            BackgroundService.ForceRefreshConnections(context = this) // force a network re-discover
            updateDeviceList()
        }
    }

    override fun onStop() {
        KdeConnect.getInstance().removeDeviceListChangedCallback(key = "ShareActivity")
        super.onStop()
    }

    companion object {
        private const val KEY_UNREACHABLE_URL_LIST = "key_unreachable_url_list"
        private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        private const val TAG = "ShareActivity"
    }
}