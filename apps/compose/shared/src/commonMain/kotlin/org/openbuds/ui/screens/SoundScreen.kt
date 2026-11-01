// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.*
import org.openbuds.core.*
import org.openbuds.ui.components.*
import org.openbuds.ui.theme.*

@Composable internal fun SoundScreen(state: AppState, controller: DeviceController, wide: Boolean, confirm: (JsonObject, String) -> Unit) {
    val eq = state.profile?.eq
    var editor by rememberSaveable { mutableStateOf(false) }
    var gains by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    var name by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<Int?>(null) }
    var minimum by rememberSaveable { mutableIntStateOf(0) }
    var maximum by rememberSaveable { mutableIntStateOf(0) }
    var frequencies by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    var delete by remember { mutableStateOf<EqProfile?>(null) }
    val editable = state.connected && !state.busy
    val current = state.snapshot.profiles.filter { it.selected }
    val applied = when {
        !state.connected -> "Connect to read the applied EQ"
        current.size > 1 -> "Selection conflict — refresh before changing EQ"
        current.size == 1 -> current.single().name
        else -> eq?.presets?.firstOrNull { it.id == state.snapshot.preset }?.name ?: "Selection unavailable"
    }
    LaunchedEffect(state.savedProfileId) {
        if (editor && state.savedProfileId != null && state.snapshot.profiles.any { it.id == state.savedProfileId && it.name == name.trim() && it.gains == gains }) editor = false
    }
    Text("SOUND & EQ", fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
    InfoCard("${if (state.connected) "Applied EQ" else "Equalizer"}", applied)
    if (eq != null) {
        Section("Built-in sounds") {
            PresetGrid(eq.presets, state.snapshot.preset.takeIf { current.isEmpty() && state.connected }, editable) { id ->
                controller.command(request("set_eq_preset") { put("id", id) }, "eq_preset", "eq_preset")
            }
        }
        Section("Your EQ profiles") {
            if (state.snapshot.profiles.isEmpty()) Text(if (state.connected) "No custom profiles were returned." else "Connect to read your saved profiles.", color = Muted, fontSize = 12.sp)
            state.snapshot.profiles.forEach { profile ->
                var menu by remember(profile.id) { mutableStateOf(false) }
                Box {
                    RowAction(profile.name, if (profile.selected) "Applied" else "Saved on your earbuds", trailing = { Text("•••", color = Muted) }) { menu = true }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Apply") }, enabled = editable, onClick = { menu = false; controller.save(profile, false) })
                        DropdownMenuItem(text = { Text("Edit / rename") }, onClick = {
                            menu = false; editingId = profile.id; name = profile.name; gains = profile.gains
                            minimum = profile.min; maximum = profile.max; frequencies = profile.frequencies; editor = true
                        })
                        DropdownMenuItem(text = { Text("Delete") }, enabled = editable, onClick = { menu = false; delete = profile })
                    }
                }
            }
            if (!editor) OutlinedButton(onClick = {
                editingId = null; name = "My sound"; frequencies = eq.frequencies
                gains = List(frequencies.size) { 0 }; minimum = eq.min; maximum = eq.max; editor = true
            }) { Text("Create EQ") }
        }
        if (editor) Section(if (editingId == null) "Create EQ · local draft" else "Edit EQ · local draft") {
            Text("Drag a frequency handle. The highlighted frequency and gain update as you move. Nothing is sent until Save & apply.", color = Muted, fontSize = 12.sp)
            EqualizerPad(gains, minimum, maximum, frequencies) { gains = it }
            OutlinedTextField(name, { name = it }, label = { Text("Profile name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = name.isBlank() || (eq.nameBytesMax?.let { name.encodeToByteArray().size > it } == true))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled = editable && name.isNotBlank() && (eq.nameBytesMax?.let { name.encodeToByteArray().size <= it } != false),
                    onClick = { controller.save(EqProfile(editingId ?: 0, name.trim(), false, minimum, maximum, gains, frequencies), editingId == null) }) { Text("Save & apply") }
                TextButton(onClick = { editor = false }) { Text("Cancel") }
                TextButton(onClick = { gains = List(gains.size) { 0 } }) { Text("Reset draft") }
            }
        }
    }
    delete?.let { profile -> AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete ${profile.name}?") }, text = { Text("Remove this profile from your earbuds?") },
        confirmButton = { TextButton(onClick = {
            delete = null; controller.command(request("set_custom_eq") { put("eq", profile.json()); put("action", "Delete") }, "custom_eq", "custom_eq")
            if (editingId == profile.id) editor = false
        }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } }) }
    SoundEffects(state, controller, confirm)
}
