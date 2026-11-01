// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

/** Device-defined frequencies. Drag, keyboard and accessibility operate on the same control. */
@Composable
internal fun EqualizerPad(gains: List<Int>, min: Int, max: Int, frequencies: List<Int>, onChange: (List<Int>) -> Unit) {
    if (gains.isEmpty() || gains.size != frequencies.size) return
    var latest by remember(gains) { mutableStateOf(gains) }
    val change by rememberUpdatedState(onChange)
    val measurer = rememberTextMeasurer()
    val count = gains.size
    val focus = remember { FocusRequester() }
    val directions = remember(count) { List(count) { i ->
        val angle = -PI.toFloat() / 2f + i * 2 * PI.toFloat() / count
        Offset(cos(angle), sin(angle))
    } }
    var selected by remember(frequencies) { mutableIntStateOf(0) }
    var dragging by remember { mutableIntStateOf(-1) }
    fun adjust(index: Int, value: Int): Boolean {
        if (index !in latest.indices) return false
        val next = latest.toMutableList().also { it[index] = value.coerceIn(min, max) }
        latest = next; change(next); return true
    }
    val labelStyle = TextStyle(color = Color(0xFFADB1AA), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    val labels = frequencies.map { frequency -> measurer.measure(if (frequency < 1000) "$frequency" else "${frequency / 1000}k", labelStyle) }
    val activeFrequency = frequencies[selected.coerceIn(frequencies.indices)]
    val activeGain = gains[selected.coerceIn(gains.indices)]
    val readout = "$activeFrequency Hz  ·  ${if (activeGain > 0) "+" else ""}$activeGain dB"
    Column {
    Canvas(Modifier.fillMaxWidth().heightIn(max = 360.dp).aspectRatio(1f)
        .semantics {
            contentDescription = "Equalizer. Drag a frequency handle. Arrow keys select and adjust bands."
            stateDescription = "$activeFrequency Hz, $activeGain dB"
            customActions = frequencies.flatMapIndexed { index, frequency -> listOf(
                CustomAccessibilityAction("Increase $frequency Hz") { selected = index; adjust(index, latest[index] + 1) },
                CustomAccessibilityAction("Decrease $frequency Hz") { selected = index; adjust(index, latest[index] - 1) }) }
        }.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                Key.DirectionLeft -> { selected = (selected + count - 1) % count; true }
                Key.DirectionRight -> { selected = (selected + 1) % count; true }
                Key.DirectionUp -> adjust(selected, latest[selected] + 1)
                Key.DirectionDown -> adjust(selected, latest[selected] - 1)
                else -> false
            }
        }.focusRequester(focus).focusable()
        .pointerInput(min, max, frequencies) {
            var origin = Offset.Zero; var startGain = 0
            detectDragGestures(onDragStart = { position ->
                val center = Offset(size.width / 2f, size.height / 2f)
                val radius = minOf(size.width, size.height) * .38f
                val delta = position - center
                val direction = delta / delta.getDistance().coerceAtLeast(1f)
                val index = directions.indices.maxBy { direction.x * directions[it].x + direction.y * directions[it].y }
                val fraction = (latest[index] - min).toFloat() / (max - min).coerceAtLeast(1)
                val edge = radius * (.25f + .65f * fraction)
                dragging = index.takeIf { abs(delta.getDistance() - edge) <= 60.dp.toPx() } ?: -1
                if (dragging >= 0) { focus.requestFocus(); selected = dragging; startGain = latest[dragging] }; origin = position
            }, onDragEnd = { dragging = -1 }, onDragCancel = { dragging = -1 }, onDrag = { event, _ ->
                if (dragging >= 0 && max > min) {
                    event.consume(); val movement = event.position - origin
                    val radius = minOf(size.width, size.height) * .38f
                    val projection = movement.x * directions[dragging].x + movement.y * directions[dragging].y
                    adjust(dragging, (startGain + projection / (radius * .65f) * (max - min)).roundToInt())
                }
            })
        }) {
        val radius = size.minDimension * .38f
        drawCircle(Color(0xFF303430), radius, center, style = Stroke(1.dp.toPx()))
        drawCircle(Color(0xFF252A25), radius * .575f, center, style = Stroke(1.dp.toPx()))
        val points = directions.mapIndexed { i, direction -> center + direction * (radius * (.25f + .65f * (gains[i] - min).toFloat() / (max - min).coerceAtLeast(1))) }
        directions.forEachIndexed { i, direction ->
            val position = center + direction * radius * 1.16f
            drawText(labels[i], topLeft = position - Offset(labels[i].size.width / 2f, labels[i].size.height / 2f),
                color = if (i == selected) Color(0xFFF4F2EC) else Color(0xFF92998E))
            drawLine(if (i == selected) Color(0xFF797F73) else Color(0xFF383D38), center + direction * radius * .25f, center + direction * radius * .9f, 1.dp.toPx())
        }
        val curve = Path().apply {
            moveTo(points[0].x, points[0].y)
            points.indices.forEach { i ->
                val previous = points[(i + count - 1) % count]; val current = points[i]
                val next = points[(i + 1) % count]; val after = points[(i + 2) % count]
                val c1 = current + (next - previous) / 6f; val c2 = next - (after - current) / 6f
                cubicTo(c1.x, c1.y, c2.x, c2.y, next.x, next.y)
            }; close()
        }
        drawPath(curve, Brush.radialGradient(listOf(Color(0xFF56604F), Color(0xFF151A17)), center, radius))
        drawPath(curve, Color(0xFF87928A), style = Stroke(1.5.dp.toPx()))
        points.forEachIndexed { i, point -> drawCircle(if (i == selected) Color(0xFFE84A3C) else Color(0xFFCAD0C4), if (i == selected) 5.dp.toPx() else 3.dp.toPx(), point) }
    }
    Text(readout, Modifier.fillMaxWidth().padding(top = 8.dp), color = Color(0xFFF4F2EC), fontSize = 12.sp,
        fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center)
    }
}
