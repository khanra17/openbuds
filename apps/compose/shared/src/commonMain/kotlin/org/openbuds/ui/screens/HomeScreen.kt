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
import org.openbuds.ui.availablePages
import org.openbuds.ui.components.*
import org.openbuds.ui.theme.*
import kotlin.math.roundToInt

@Composable internal fun HomeScreen(state: AppState, controller: DeviceController, wide: Boolean, motion: Boolean, connect: () -> Unit, navigate: (Page) -> Unit) {
    val summary: @Composable () -> Unit = {
        Column(Modifier.padding(top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Label(if (state.connected) "CONNECTED" else "NOT CONNECTED")
            Text(state.profile?.name ?: "Earbud controls", fontSize = if (wide) 38.sp else 28.sp, lineHeight = if (wide) 44.sp else 34.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
            DeviceReadings(state.snapshot, motion, state.profile?.components ?: NormalizedEarbudComponents)
            if (!state.connected) Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(Paper, RoundedCornerShape(4.dp)).clickable(enabled = !state.busy, onClick = connect).padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(if (state.busy) "CONNECTING…" else "CONNECT EARBUDS", color = Color.Black, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text("↗", color = Color.Black, fontSize = 20.sp) }
            }
        }
    }
    val workspace: @Composable () -> Unit = {
        Column {
            if (state.profile?.noise != null) NoiseControls(state, controller)
            Spacer(Modifier.height(20.dp))
            if (availablePages(state.profile).any { it == Page.Sound || it == Page.Controls }) Label("CONTROLS")
            if (Page.Sound in availablePages(state.profile)) EditorialLink("01", if (state.profile?.eq != null) "Equalizer" else "Sound settings", "PRESETS & AUDIO PREFERENCES") { navigate(Page.Sound) }
            if (Page.Controls in availablePages(state.profile)) EditorialLink("02", "Gestures", "LEFT & RIGHT CONTROLS") { navigate(Page.Controls) }
        }
    }
    if (wide) Row(horizontalArrangement = Arrangement.spacedBy(80.dp), modifier = Modifier.fillMaxWidth().padding(top = 28.dp)) {
        Box(Modifier.weight(.9f)) { summary() }; Box(Modifier.weight(1.1f).padding(top = 28.dp)) { workspace() }
    } else { summary(); workspace() }
}
