// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui.components

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/** A labelled device preference with the same neutral palette in every state. */
@Composable
internal fun SettingSwitch(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
        modifier = Modifier.semantics { contentDescription = label },
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color(0xFF080A09), checkedTrackColor = Color(0xFFF4F2EC),
            checkedBorderColor = Color(0xFFF4F2EC),
            uncheckedThumbColor = Color(0xFF9A9C96), uncheckedTrackColor = Color(0xFF202420),
            uncheckedBorderColor = Color(0xFF50574F),
            disabledCheckedThumbColor = Color(0xFF191C19), disabledCheckedTrackColor = Color(0xFF777D75),
            disabledCheckedBorderColor = Color(0xFF777D75),
            disabledUncheckedThumbColor = Color(0xFF62685F), disabledUncheckedTrackColor = Color(0xFF191C19),
            disabledUncheckedBorderColor = Color(0xFF343A33)))
}
