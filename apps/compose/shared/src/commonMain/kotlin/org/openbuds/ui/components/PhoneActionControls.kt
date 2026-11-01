// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbuds.core.*

@Composable fun PhoneActionControls(state: AppState, enabled: Boolean, selected: Int, options: List<Pair<String, String>>,
    running: Boolean, enable: (Boolean) -> Unit, choose: (Int) -> Unit, stop: () -> Unit, permissionNotice: String? = null) {
    val feature = state.profile?.feature("phone_action") ?: return
    val reported = state.snapshot.feature(feature.id)
    var menu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        Text(feature.name, style = MaterialTheme.typography.titleMedium)
        Text("${feature.detail} OpenBuds keeps a visible connection notification while running in the background.", fontSize = 12.sp)
        RowAction("Enable phone action", if (reported == null) "Device status unavailable" else if (enabled && reported) "On" else "Off",
            enabled = state.connected && !state.busy && reported != null,
            trailing = { if (reported != null) SettingSwitch("Enable phone action", enabled && reported, state.connected && !state.busy, enable) else Text("—") }) {
            enable(!(enabled && reported == true))
        }
        Box {
            OutlinedButton(onClick = { menu = true }, enabled = state.deviceAddress != null) { Text(options.getOrNull(selected)?.first.orEmpty()) }
            DropdownMenu(menu, { menu = false }) {
                options.forEachIndexed { index, option -> DropdownMenuItem(text = { Text(option.first) }, onClick = { menu = false; choose(index) }) }
            }
        }
        Text(options.getOrNull(selected)?.second.orEmpty(), fontSize = 12.sp)
        if (permissionNotice != null) Text(permissionNotice, fontSize = 12.sp)
        if (running) Button(onClick = stop) { Text("Stop phone action") }
    }
}
