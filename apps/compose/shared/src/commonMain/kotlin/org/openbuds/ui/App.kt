// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.*
import org.openbuds.core.*
import org.openbuds.ui.components.*
import org.openbuds.ui.screens.*
import org.openbuds.ui.theme.*

enum class Page(val title: String) { Home("Earbuds"), Sound("Sound & EQ"), Controls("Gestures"), Device("Device") }
fun availablePages(profile: DeviceProfile?): List<Page> = Page.entries.filter { page -> when (page) {
    Page.Home, Page.Device -> true
    Page.Sound -> profile?.eq != null || profile?.features?.any { it.group == "sound" } == true || profile?.hasTool("volume_enhancer") == true
    Page.Controls -> profile?.gestureRows?.isNotEmpty() == true || profile?.features?.any { it.group == "gestures" } == true
} }
class Navigation(page: Page = Page.Home) { var page by mutableStateOf(page); fun back() { page = Page.Home } }
@Composable fun rememberNavigation(): Navigation = rememberSaveable(saver = Saver({ it.page.name }, { Navigation(Page.valueOf(it)) })) { Navigation() }
@Composable fun OpenBudsApp(controller: DeviceController, navigation: Navigation,
    requestDevices: () -> Unit = controller::paired, initialReducedMotion: Boolean = false,
    onReducedMotionChange: (Boolean) -> Unit = {},
    phoneActions: (@Composable (AppState, DeviceController) -> Unit)? = null,
    connectDevice: (String, String?) -> Unit = { address, model -> controller.connect(address, model) }, bluetoothSettings: (() -> Unit)? = null) {
    val state by controller.state.collectAsState()
    val pages = rememberSaveableStateHolder()
    val destinations = availablePages(state.profile)
    LaunchedEffect(state.profile?.id) { if (navigation.page !in destinations) navigation.back() }
    var picker by remember { mutableStateOf(false) }
    LaunchedEffect(state.connected) { if (state.connected) picker = false }
    var confirmation by remember { mutableStateOf<JsonObject?>(null) }
    var confirmText by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }
    LaunchedEffect(state.deviceAddress, state.profile?.id) { confirmation = null; acknowledged = false }
    var reducedMotion by rememberSaveable { mutableStateOf(initialReducedMotion) }
    MaterialTheme(colorScheme = OpenBudsColors) { CompositionLocalProvider(LocalMotion provides !reducedMotion) {
        Surface(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
                val wide = maxWidth >= 900.dp
                Column(Modifier.fillMaxSize().widthIn(max = 1280.dp).align(Alignment.TopCenter).padding(horizontal = if (wide) 48.dp else 24.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (!wide && navigation.page != Page.Home) TextButton(onClick = navigation::back) { Text("‹ Back") }
                        else Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text("●", color = Accent, fontSize = 12.sp)
                            Text("  OPENBUDS", color = Paper, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace)
                        }
                        if (wide) {
                            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                                destinations.forEach { page -> TextButton(onClick = { navigation.page = page }) { Text(page.title, color = if (page == navigation.page) Paper else Muted) } }
                            }
                        } else if (navigation.page == Page.Home) TextButton(onClick = { navigation.page = Page.Device }) { Text("SETTINGS  ↗", fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = .5.sp) }
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Accent)
                    if (state.message != null && !(navigation.page == Page.Home && state.issue?.area == IssueArea.Noise)) {
                        FeedbackMessage(state.message!!, state.issue?.details, controller::dismiss)
                    }
                    Crossfade(navigation.page, modifier = Modifier.weight(1f), animationSpec = tween(if (reducedMotion) 0 else 160), label = "page") { page ->
                    pages.SaveableStateProvider("${state.profile?.id}:${state.deviceAddress.orEmpty()}:${page.name}") {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (page) {
                            Page.Home -> HomeScreen(state, controller, wide, !reducedMotion, {
                                val previous = state.deviceAddress
                                if (previous != null) connectDevice(previous, state.profile?.id) else { picker = true; requestDevices() }
                            }, { navigation.page = it })
                            Page.Sound -> SoundScreen(state, controller, wide) { command, text -> confirmation = command; confirmText = text }
                            Page.Controls -> GesturesScreen(state, controller) { command, text -> confirmation = command; confirmText = text }
                            Page.Device -> DeviceScreen(state, controller, { picker = true; requestDevices() }, bluetoothSettings, reducedMotion,
                                { reducedMotion = it; onReducedMotionChange(it) },
                                confirm = { command, text -> confirmation = command; confirmText = text }, phoneActions = phoneActions)
                        }
                    }
                    }
                    }
                }
            }
        }
        if (picker) AlertDialog(onDismissRequest = { picker = false }, title = { Text("Connect your earbuds") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Choose a supported paired device. This opens a separate connection for settings; music and calls use your phone’s audio connection.", color = Muted, fontSize = 13.sp)
                state.message?.let { Text(it, color = Accent, fontSize = 13.sp) }
                val devices = state.devices
                if (devices.isEmpty()) Text(if (state.busy) "Loading paired devices…" else "No supported paired device found.", modifier = Modifier.padding(vertical = 24.dp))
                devices.forEach { device -> RowAction(device.name, device.address, enabled = !state.busy) { picker = false; connectDevice(device.address, controller.profileFor(device)?.id) } }
                bluetoothSettings?.let { TextButton(onClick = it) { Text("Open system settings") } }
            }
        }, confirmButton = { TextButton(onClick = requestDevices, enabled = !state.busy) { Text("Reload") } }, dismissButton = { TextButton(onClick = { picker = false }) { Text("Close") } })
        confirmation?.let { command ->
            if (command["op"].text() == "run_fit_test") FitTestConsent(state.snapshot, minimumWorn = state.profile?.fitMinimumWorn ?: 0, components = state.profile?.components?.filter { it.icon != "case" } ?: NormalizedEarbudComponents.filter { it.icon != "case" },
                dismiss = { confirmation = null }, start = { confirmation = null; controller.command(command) })
            else AlertDialog(onDismissRequest = { confirmation = null; acknowledged = false }, title = { Text("Before continuing") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(confirmText)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(acknowledged, { acknowledged = it }); Text("I have checked and want to continue", fontSize = 13.sp)
                    }
                }
            }, confirmButton = { TextButton(enabled = acknowledged && !state.busy, onClick = {
                confirmation = null; acknowledged = false
                val op = command["op"].text()
                val readback = when (op) {
                    "set_touch" -> "touch"
                    "set_volume_enhancer" -> "volume_enhancer"
                    "multipoint_action" -> "multipoint"
                    else -> null
                }
                controller.command(if (op == "set_touch") JsonObject(command + ("confirm_experimental" to JsonPrimitive(true))) else command,
                    readback, readback)
            }) { Text("Continue") } }, dismissButton = { TextButton(onClick = { confirmation = null; acknowledged = false }) { Text("Cancel") } })
        }
        if (state.activeOperation == "run_fit_test") FitTestProgress(state.snapshot, state.profile?.components?.filter { it.icon != "case" } ?: NormalizedEarbudComponents.filter { it.icon != "case" }, controller::cancel)
        state.result?.let { DeviceResultDialog(it, controller::dismiss) }
    } }
}
