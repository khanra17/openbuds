// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbuds.core.*
import org.openbuds.ui.theme.Muted
import org.openbuds.ui.theme.Paper
import kotlin.math.ceil

/** Fixed identification icons; placement is stated in words, never a moving invented scene. */
@Composable
internal fun DeviceReadings(snapshot: Snapshot, motion: Boolean, components: List<ComponentSpec> = NormalizedEarbudComponents) {
    val density = LocalDensity.current
    val fontScale = density.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth().border(1.dp, Color(0xFF353733), RoundedCornerShape(6.dp))
        .padding(horizontal = 14.dp, vertical = 14.dp)) {
        val columns = ((maxWidth.value + 12) / (72 * fontScale + 12)).toInt().coerceIn(1, components.size.coerceIn(1, 3))
        val gapPixels = with(density) { ceil(12.dp.toPx()).toInt() }
        val cellPixels = ((constraints.maxWidth - gapPixels * (columns - 1)) / columns).coerceAtLeast(0)
        val cellWidth = with(density) { cellPixels.toDp() }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            components.forEach { component ->
                val index = component.id - 1
                val label = component.name
                val tag = component.icon.let { if (it == "case") "case" else if (it == "earbud_right") "right" else "left" }
                val status = if (index in 0..1) snapshot.earStatus[index] else snapshot.caseLinkStatus
                Column(Modifier.width(cellWidth).semantics(mergeDescendants = true) {},
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, color = Muted, fontFamily = FontFamily.Monospace, fontSize = 10.sp,
                        letterSpacing = 1.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().testTag("$tag-label"))
                    Box(Modifier.fillMaxWidth().height(56.dp).testTag("$tag-icon"), contentAlignment = Alignment.Center) {
                        ReadingIcon(if (component.icon == "case") 2 else if (component.icon == "earbud_right") 1 else 0, status, motion)
                    }
                    // A returned number can outlive its component link. Keep it in
                    // diagnostics, but don't present it as a live offline battery.
                    val percentage = if (index == 2) snapshot.caseBattery else snapshot.batteries.getOrNull(index).takeUnless { status == "Disconnected" }
                    Crossfade(percentage, modifier = Modifier.fillMaxWidth(),
                        animationSpec = tween(if (motion) 180 else 0), label = "$label battery") { battery ->
                        Text(battery?.let { "$it%" } ?: "—", color = Paper, fontSize = 23.sp, lineHeight = 30.sp,
                            fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("$tag-battery"))
                    }
                    Text(status, color = Muted, fontSize = 10.sp, lineHeight = 14.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("$tag-status"))
                }
            }
        }
    }
}

@Composable
internal fun ReadingIcon(component: Int, status: String, motion: Boolean) {
    val docked = status == "In case"
    val target = when { docked -> Color(0xFFB9AA8F); status in setOf("In ear", "Connected", "Via earbud", "Good fit") -> Paper; else -> Muted }
    val dock by animateFloatAsState(if (docked) 1f else 0f, tween(if (motion) 260 else 0), label = "docked indicator")
    val color by animateColorAsState(target,
        tween(if (motion) 320 else 0), label = "reported status")
    Canvas(Modifier.size(52.dp).clearAndSetSemantics {}) {
        val u = size.minDimension / 56f
        val x = size.width / 2
        val y = size.height / 2
        val stroke = Stroke(1.6f * u)
        if (component == 2) {
            // Generic closed charging-case symbol, not lid or LED telemetry.
            drawRoundRect(color, Offset(x - 22 * u, y - 14 * u), Size(44 * u, 28 * u), CornerRadius(9 * u), style = stroke)
            drawLine(color, Offset(x - 21 * u, y - 2 * u), Offset(x + 21 * u, y - 2 * u), 1.4f * u)
        } else scale(if (component == 1) -1f else 1f, 1f, Offset(x, y)) {
            val cx = x + 2.5f * u
            val cy = y - u
            val bud = Path().apply {
                moveTo(cx + 3 * u, cy)
                cubicTo(cx - 9 * u, cy, cx - 15 * u, cy - 3 * u, cx - 15 * u, cy - 10 * u)
                cubicTo(cx - 15 * u, cy - 17 * u, cx - 8 * u, cy - 19 * u, cx - u, cy - 16 * u)
                cubicTo(cx + 6 * u, cy - 14 * u, cx + 10 * u, cy - 9 * u, cx + 10 * u, cy - 3 * u)
                lineTo(cx + 10 * u, cy + 15 * u)
                quadraticTo(cx + 10 * u, cy + 19 * u, cx + 6.5f * u, cy + 19 * u)
                quadraticTo(cx + 3 * u, cy + 19 * u, cx + 3 * u, cy + 15 * u)
                close()
            }
            drawPath(bud, color, style = stroke)
            drawLine(color, Offset(cx - 9 * u, cy - 12 * u), Offset(cx - 9 * u, cy - 6 * u), 1.4f * u)
            if (dock > 0f) drawLine(color.copy(alpha = dock), Offset(x - 8 * u, y + 23 * u), Offset(x + 8 * u, y + 23 * u), 2 * u)
        }
    }
}
