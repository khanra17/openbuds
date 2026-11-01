// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.*
import org.openbuds.core.*
import org.openbuds.ui.components.*
import org.openbuds.ui.theme.*
import kotlin.math.roundToInt

@Composable internal fun SoundEffects(state: AppState, controller: DeviceController, confirm: (JsonObject, String) -> Unit) {
    val profile = state.profile ?: return
    if (profile.features.none { it.group == "sound" } && !profile.hasTool("volume_enhancer")) return
    val editable = state.connected && !state.busy
    val dynamic = profile.feature("dynamic")
    Section("Sound effects") {
        FeatureSettings(state, controller, listOf("dynamic"))
        if (dynamic != null && state.snapshot.feature(dynamic.id) == true) Section("Dynamic sound strength") {
            if (state.snapshot.dynamic.isEmpty()) Text("Band readings are unavailable. Refresh your device.", color = Muted, fontSize = 12.sp)
            state.snapshot.dynamic.filter { band -> profile.dynamicBands.any { it.id == band.kind } }.forEach { band ->
                var value by remember(band) { mutableFloatStateOf(band.value.toFloat()) }
                Text(profile.dynamicBands.first { it.id == band.kind }.name, fontSize = 13.sp)
                if (band.min < band.max) Slider(value, { value = it }, enabled = editable,
                    valueRange = band.min.toFloat()..band.max.toFloat(), steps = (band.max - band.min - 1).coerceAtLeast(0),
                    colors = SliderDefaults.colors(thumbColor = Paper, activeTrackColor = Paper, inactiveTrackColor = Color(0xFF343833)), onValueChangeFinished = {
                        controller.command(request("set_dynamic_band") { put("band", buildJsonObject {
                            put("kind", band.kind); put("min", band.min); put("max", band.max); put("value", value.roundToInt())
                        }) }, "dynamic_bands", "dynamic_bands")
                    })
                Text("${value.roundToInt()} · range ${band.min}–${band.max}", color = Muted, fontSize = 12.sp)
            }
        }
        FeatureSettings(state, controller, profile.features.filter { it.group == "sound" && it.key != "dynamic" }.map { it.key })
        if (profile.hasTool("volume_enhancer")) {
            val volume = state.snapshot.raw["volume_enhancer"].bool()
            if (volume != null) RowAction("Volume enhancer", "Increase earbud loudness. Lower your phone's volume before listening.", enabled = editable,
                trailing = { SettingSwitch("Volume enhancer", volume, editable) { value ->
                    val command = request("set_volume_enhancer") { put("enabled", value); put("confirm_louder", value) }
                    if (value) confirm(command, "This can increase loudness. Remove both earbuds before enabling, then lower system volume before listening.")
                    else controller.command(command, "volume_enhancer", "volume_enhancer")
                } }) {}
            else RowAction("Volume enhancer", "Status unavailable", enabled = false, trailing = { Text("—", color = Muted) }) {}
        }
    }
}
