// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbuds.core.*
import org.openbuds.ui.components.*
import org.openbuds.ui.theme.*

@Composable internal fun NoiseControls(state: AppState, controller: DeviceController) {
    val spec = state.profile?.noise ?: return
    val mode = state.snapshot.noise
    val anc = spec.levels.any { it.id == mode }
    val needsWear = state.snapshot.wearReadiness(spec.minimumWorn) == WearReadiness.NeedsWear
    val canDispatch = state.connected && !state.busy && mode != null
    val motion = LocalMotion.current
    var wearDialog by remember { mutableStateOf(false) }
    val wearText = if (spec.minimumWorn >= 2) "Put on both earbuds to change noise control." else "Put on at least one earbud to change noise control."
    LaunchedEffect(state.issue) { if (state.issue?.area == IssueArea.Noise && state.issue.wearRequirement != null) wearDialog = true }
    LaunchedEffect(state.snapshot.noiseWearReadiness) { if (state.snapshot.wearReadiness(spec.minimumWorn) == WearReadiness.Ready) wearDialog = false }
    val labels = listOf("Cancellation", "Off", "Transparency")
    Surface(shape = RoundedCornerShape(8.dp), color = Color(0xFF191B19)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Noise control", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val style = LocalTextStyle.current.copy(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium)
            val labelWidth = with(density) { labels.maxOf { measurer.measure(AnnotatedString(it), style).size.width }.toDp() }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val horizontal = maxWidth >= (labelWidth + 16.dp) * 3 + 12.dp
                val choice: @Composable (Int, String, Modifier) -> Unit = { index, label, modifier ->
                    val selected = when (index) { 0 -> anc; 1 -> mode == spec.off; else -> mode == spec.transparency }
                    val background by animateColorAsState(if (selected) Paper else Color(0xFF0B0C0B), tween(if (motion) 180 else 0), label = "noise selection")
                    val ink = if (selected) Color(0xFF11130F) else if (canDispatch && !needsWear) Paper else Muted
                    Column(modifier.heightIn(min = if (horizontal) 92.dp else 52.dp).clip(RoundedCornerShape(5.dp)).background(background)
                        .selectable(selected, enabled = canDispatch, role = Role.RadioButton, onClick = {
                            if (needsWear) wearDialog = true else controller.setNoise(when (index) {
                                0 -> state.lastAnc?.takeIf { value -> spec.levels.any { it.id == value } } ?: spec.defaultAnc
                                1 -> spec.off
                                else -> spec.transparency
                            })
                        }).semantics { if (needsWear) stateDescription = wearText }
                        .padding(horizontal = 8.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
                        if (horizontal) NoiseGlyph(index, ink)
                        Text(label, color = ink, style = style, textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
                if (horizontal) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    labels.forEachIndexed { index, label -> choice(index, label, Modifier.weight(1f)) }
                } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    labels.forEachIndexed { index, label -> choice(index, label, Modifier.fillMaxWidth()) }
                }
            }
            val issue = state.issue?.takeIf { it.area == IssueArea.Noise }
            if (issue != null) FeedbackMessage(issue.message, issue.details, controller::dismiss)
            else Text(when {
                !state.connected -> "Connect your earbuds to use noise control."
                needsWear -> "$wearText Tap a mode for help."
                mode == null -> "Noise control status is unavailable. Refresh the device readings."
                mode == spec.transparency -> "Hear nearby sounds while listening."
                mode == spec.off -> "Noise cancellation and Transparency are off."
                anc -> "${spec.levels.firstOrNull { it.id == mode }?.name.orEmpty()} cancellation"
                else -> "Noise mode unavailable"
            }, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            if (anc) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                spec.levels.forEach { level -> ToneOption(level.name, mode == level.id, canDispatch) {
                    if (needsWear) wearDialog = true else controller.setNoise(level.id)
                } }
            }
            if (anc || mode == spec.transparency) FeatureSettings(state, controller, listOf("wind"))
            if (mode == spec.transparency) FeatureSettings(state, controller, state.profile.features.filter { it.group == "transparency" }.map { it.key })
        }
    }
    if (wearDialog) AlertDialog(onDismissRequest = { wearDialog = false; controller.dismiss() },
        title = { Text("Wear your earbuds") }, text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ReadingIcon(0, "In ear", motion)
                Text(state.issue?.message ?: wearText, fontSize = 14.sp)
            }
        }, confirmButton = { TextButton(onClick = { wearDialog = false; controller.dismiss() }) { Text("Got it") } })
}

@Composable private fun NoiseGlyph(kind: Int, color: Color) {
    Canvas(Modifier.size(26.dp).clearAndSetSemantics {}) {
        val c = center; val u = size.minDimension / 26
        drawCircle(color, 4 * u, Offset(c.x, c.y - 4 * u), style = Stroke(1.5f * u))
        drawArc(color, 185f, 170f, false, Offset(c.x - 7 * u, c.y + u), androidx.compose.ui.geometry.Size(14 * u, 12 * u), style = Stroke(1.5f * u))
        if (kind != 1) {
            drawArc(color, 140f, 80f, false, Offset(c.x - 12 * u, c.y - 11 * u), androidx.compose.ui.geometry.Size(24 * u, 20 * u), style = Stroke(1.4f * u))
            drawArc(color, -40f, 80f, false, Offset(c.x - 12 * u, c.y - 11 * u), androidx.compose.ui.geometry.Size(24 * u, 20 * u), style = Stroke(1.4f * u))
            if (kind == 0) drawLine(color, Offset(c.x - 9 * u, c.y - 10 * u), Offset(c.x + 9 * u, c.y + 9 * u), 1.3f * u)
        }
    }
}
