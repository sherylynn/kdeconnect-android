/*
 * SPDX-FileCopyrightText: 2024 KDE Connect Contributors
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.adbconnection

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
}
