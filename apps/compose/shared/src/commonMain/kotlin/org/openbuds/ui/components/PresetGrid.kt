// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbuds.core.Choice
import org.openbuds.ui.theme.*
import kotlin.math.ceil

@Composable internal fun PresetGrid(presets: List<Choice>, selected: Int?, enabled: Boolean, apply: (Int) -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = ((maxWidth.value + 12) / (126 * density.fontScale + 12)).toInt().coerceIn(1, 4)
        val gap = with(density) { ceil(12.dp.toPx()).toInt() }
        val width = with(density) { ((constraints.maxWidth - gap * (columns - 1)) / columns).toDp() }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            presets.forEach { preset ->
                val active = preset.id == selected
                val ink = if (active) Color(0xFF11130F) else if (enabled) Paper else Muted
                val shape = RoundedCornerShape(6.dp)
                Column(Modifier.width(width).heightIn(min = 76.dp).clip(shape)
                    .background(if (active) Paper else Color(0xFF191B19)).border(1.dp, if (active) Paper else Color(0xFF353A33), shape)
                    .selectable(active, enabled = enabled, role = Role.RadioButton, onClick = { apply(preset.id) }).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(preset.name, color = ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    if (active) Text("● Applied", color = ink, fontSize = 10.sp)
                }
            }
        }
    }
}
