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

@Composable internal fun DeviceScreen(state: AppState, controller: DeviceController, connect: () -> Unit, bluetoothSettings: (() -> Unit)?, reducedMotion: Boolean, setReducedMotion: (Boolean) -> Unit, confirm: (JsonObject, String) -> Unit, phoneActions: (@Composable (AppState, DeviceController) -> Unit)? = null) {
    val profile = state.profile
    Text("DEVICE", fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp, modifier = Modifier.padding(top = 20.dp))
    RowAction(if (state.connected) "Close control connection" else "Connect controls", "Separate from your system’s Bluetooth audio connection.", enabled = !state.busy) { if (state.connected) controller.disconnect() else connect() }
    bluetoothSettings?.let { RowAction("System Bluetooth settings", action = it) }
    if (state.connected) RowAction("Refresh device readings", enabled = !state.busy, action = controller::refresh)
    if (profile?.features?.any { it.group == "device" } == true) Section("Device connections") {
        FeatureSettings(state, controller, profile.features.filter { it.group == "device" }.map { it.key })
    }
    val hosts = state.snapshot.raw["multipoint"].arr()
    if (profile?.hasTool("multipoint") == true && hosts.isNotEmpty()) Section("Connected devices · connection history") {
        hosts.forEach { host ->
            val h = host.obj(); var menu by remember { mutableStateOf(false) }
            Box {
                RowAction(h["name"].text().orEmpty().ifBlank { "Unnamed device" }, profile.peerStates.firstOrNull { it.id == h["state"].int() }?.name ?: "State not reported", enabled = !state.busy) { menu = true }
                DropdownMenu(menu, { menu = false }) { (when (h["state"].int()) { profile.peerDisconnected -> profile.peerConnectAction?.let { listOf(it to "Connect") }.orEmpty(); profile.peerConnected -> profile.peerDisconnectAction?.let { listOf(it to "Disconnect") }.orEmpty(); else -> emptyList() } + profile.peerRemoveAction?.let { listOf(it to "Remove pairing") }.orEmpty()).forEach { (action, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = {
                        menu = false
                        val bytes = h["address"].text().orEmpty().split(':').mapNotNull { it.toIntOrNull(16) }
                        if (bytes.size == 6) confirm(request("multipoint_action") { put("address", JsonArray(bytes.map(::JsonPrimitive))); put("action", action); put("confirm", true) }, if (action == profile.peerRemoveAction) "Remove this host's stored pairing from the earbuds?" else "Change this host's Bluetooth connection?")
                    })
                } }
            }
        }
    } else if (profile?.hasTool("multipoint") == true) Section("Connection history") {
        Text(if (state.connected) "No host rows were returned by this device; this does not prove the earbuds have no saved pairings." else "Connect to read the device's host list.", color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
    }
    if (state.connected && (profile?.hasTool("fit_test") == true || profile?.hasTool("find_earbuds") == true)) Section("Tools") {
        if (profile.hasTool("fit_test")) RowAction("Ear tip fit test", if (state.snapshot.fitWearReadiness == WearReadiness.NeedsWear) "Put on both earbuds to start. The test plays a sound." else "Check your ear-tip seal. A test sound will play.", enabled = !state.busy) {
            confirm(request("run_fit_test") { put("timeout_ms", 15000); put("confirm_sound", true) }, "Wear both earbuds with the tips fitted securely. This test plays a sound. Continue only when ready.")
        }
        if (profile.hasTool("find_earbuds")) RowAction("Find earbuds", "Remove both earbuds from your ears first.", enabled = !state.busy) {
            confirm(request("find_for") { put("duration_ms", 3000); put("confirm_sound", true) }, "Physically remove BOTH earbuds from your ears. A loud tone may play for three seconds. Sensors are not a safety guarantee; cleanup is best-effort.")
        }
        if (state.activeOperation == "run_fit_test") TextButton(onClick = controller::cancel) { Text("Stop fit test") }
        if (state.activeOperation == "find_for") TextButton(onClick = controller::cancel) { Text("Stop finding sound") }
    }
    phoneActions?.invoke(state, controller)
    val firmware = state.snapshot.raw["versions"].arr().firstOrNull {
        profile?.firmwareKind != null && (it.obj()["kind"].int() == profile.firmwareKind || it.obj()["kind"].obj()["id"].int() == profile.firmwareKind)
    }?.obj()?.get("value")?.text()
    Section("Device information") {
        if (profile?.firmwareKind != null) InfoCard("Firmware version", firmware ?: if (state.connected) "Not reported by the device" else "Connect to read the reported version.")
        state.deviceAddress?.let { InfoCard("Bluetooth address", it) }
        if (profile?.components?.any { it.icon == "case" } == true) InfoCard("Case information", "Case battery can be relayed through an earbud in the case. A missing direct case link does not mean its battery is unavailable.")
        InfoCard("About device readings", "Settings show values read back from your earbuds. Stored values do not independently verify sound effects or physical gestures. Missing readings stay unknown.")
        InfoCard("Open-source licences", "OpenBuds third-party dependency notices are included with the source and release package. Manufacturer apps and artwork are not bundled.")
        InfoCard("Pairing & unpairing", "Use your system Bluetooth settings to pair or forget the earbuds. OpenBuds never removes the system’s pairing record.")
    }
    if (state.connected) RowAction("Read-only device diagnostics", "Reported identifiers and supported protocol properties; no settings are changed.", enabled = !state.busy) { controller.command(request("diagnostics")) }
    Section("Interface") {
        RowAction("Reduce motion", "Keep status indicators still and update readings immediately. This changes only the interface, not your earbuds.",
            trailing = { SettingSwitch("Reduce motion", reducedMotion, true, setReducedMotion) }) { setReducedMotion(!reducedMotion) }
    }
    Text("OpenBuds 0.4 · Independent earbud controller\nAudio playback uses your system's separate Bluetooth audio connection.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 24.dp))
}
