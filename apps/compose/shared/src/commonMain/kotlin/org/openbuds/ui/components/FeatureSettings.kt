// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.sp
import org.openbuds.core.*
import org.openbuds.ui.theme.Muted

@Composable
internal fun FeatureSettings(state: AppState, controller: DeviceController, keys: List<String>) {
    var pending by remember { mutableStateOf<Pair<FeatureSpec, Boolean>?>(null) }
    keys.mapNotNull { state.profile?.feature(it) }.forEach { setting ->
        val value = state.snapshot.feature(setting.id)
        val available = state.connected && !state.busy && value != null
        fun change(enabled: Boolean) {
            if (setting.experimental) pending = setting to enabled else controller.feature(setting, enabled)
        }
        RowAction(setting.name,
            "${if (setting.experimental) "Experimental mapping · " else ""}${value?.let { if (it) "On" else "Off" } ?: if (state.connected) "Status unavailable" else "Connect to change"} · ${setting.detail}",
            enabled = available,
            trailing = { if (value != null) SettingSwitch(setting.name, value, available, ::change) else Text("—", color = Muted) }) {
            if (value != null) change(!value)
        }
    }
    pending?.let { (setting, value) -> AlertDialog(onDismissRequest = { pending = null },
        title = { Text(setting.name) }, text = {
            Text(setting.experimentalReason ?: "The mapping and effect of this preference on this model have not been confirmed. Apply it only if you intend to test it.", fontSize = 13.sp)
        }, confirmButton = { TextButton(onClick = { pending = null; controller.feature(setting, value) }) { Text("Apply experimental setting") } },
        dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } }) }
}
