/*
 * SPDX-FileCopyrightText: 2024 KDE Connect Contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.adbconnection

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import org.kde.kdeconnect.ui.PluginSettingsFragment
import org.kde.kdeconnect_tp.R

class AdbConnectionSettingsFragment : PluginSettingsFragment() {

    companion object {
        fun newInstance(pluginKey: String, preferencesXmlId: Int): AdbConnectionSettingsFragment {
            val fragment = AdbConnectionSettingsFragment()
            fragment.setArguments(pluginKey, preferencesXmlId)
            return fragment
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        
        // 设置 EditTextPreference 的 SummaryProvider 以显示当前值
        findPreference<EditTextPreference>("scrcpy_max_size")?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        findPreference<EditTextPreference>("scrcpy_max_fps")?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        findPreference<EditTextPreference>("scrcpy_video_bit_rate")?.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
    }
}
