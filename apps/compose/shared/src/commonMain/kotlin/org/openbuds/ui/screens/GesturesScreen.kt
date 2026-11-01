// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.*
import org.openbuds.core.*
import org.openbuds.ui.Page
import org.openbuds.ui.components.*
import org.openbuds.ui.theme.*
import kotlin.math.roundToInt

@Composable internal fun GesturesScreen(state: AppState, controller: DeviceController, confirm: (JsonObject, String) -> Unit) {
    val profile = state.profile ?: return
    fun functionName(id: Int) = profile.functions.firstOrNull { it.id == id }?.name ?: "Other stored action ($id)"
    var ear by rememberSaveable { mutableIntStateOf(1) }
    var binding by remember { mutableStateOf<Touch?>(null) }
    Text("GESTURES", fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp, modifier = Modifier.padding(top = 20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(1 to "Left", 2 to "Right").forEach { (id, title) -> ToneOption(title, selected = ear == id) { ear = id } }
    }
    Text("Choose an action for each gesture. Playback also uses your phone’s separate audio connection.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
    Section("Wear & call handling") {
        FeatureSettings(state, controller, profile.features.filter { it.group == "gestures" }.map { it.key })
    }
    val reportedRows = state.snapshot.touch.filter { it.ear == ear }
    val rows = reportedRows.filter(profile::visibleGesture)
    if (rows.isEmpty()) Text(when {
        !state.connected -> "Connect to read gesture settings."
        reportedRows.isEmpty() -> "No gesture-binding rows were reported."
        else -> "The device reported only reserved or unidentified gesture bindings. OpenBuds leaves these out of normal controls."
    }, color = Muted, modifier = Modifier.padding(vertical = 40.dp))
    rows.forEach { touch ->
        RowAction(profile.gestureRows.first { it.id == touch.gesture }.name, functionName(touch.function), enabled = state.connected && !state.busy,
            leading = { GestureGlyph(touch.gesture, profile) }) { binding = touch }
    }
    if (rows.any(profile::cyclesNoise)) Section("Hold noise-control cycle") {
        Text("These modes cycle only when a hold action is set to switch noise control. Link lists ANC, Off, and Transparency. The four cycle values are available in OpenBuds; physical cycling has not been independently verified.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            profile.cycles.forEach { cycle ->
                ToneOption(cycle.name, selected = state.snapshot.raw["noise_cycle"].obj()["value"].int() == cycle.id, enabled = state.connected && !state.busy) { controller.command(request("set_noise_cycle") { put("cycle", cycle.wireName) }, "noise_cycle", "noise_cycle") }
            }
        }
    }
    binding?.let { touch -> AlertDialog(onDismissRequest = { binding = null }, title = { Text(profile.gestureRows.first { it.id == touch.gesture }.name) }, text = {
        val options = profile.gestureOptions(touch.gesture)
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Choose an action", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            options.forEach { function ->
                val action = functionName(function)
                RowAction(action, if (touch.function == function) "Currently selected" else null, trailing = { if (touch.function == function) Text("●", color = Accent) }) {
                    binding = null
                    val command = request("set_touch") { put("bindings", buildJsonArray { add(buildJsonObject {
                        put("ear", touch.ear); put("button", touch.button); put("gesture", touch.gesture); put("function", function)
                    }) }) }
                    if (profile.standardGesture(touch.gesture, function)) controller.command(command, "touch", "touch")
                    else confirm(command, "This action is available in the SDK, but its physical behavior is not verified for this model. Saving the binding is not proof that it works.")
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { binding = null }) { Text("Cancel") } }) }
}
