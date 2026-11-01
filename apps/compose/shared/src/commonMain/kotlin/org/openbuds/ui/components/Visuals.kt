// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun EditorialLink(number: String, title: String, subtitle: String, action: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = action).padding(vertical = 17.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(number, color = Color(0xFFE84A3C), fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.width(44.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color(0xFFF4F2EC), fontWeight = FontWeight.SemiBold, fontSize = 19.sp)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = Color(0xFF999D97), fontFamily = FontFamily.Monospace, fontSize = 9.sp, letterSpacing = .5.sp)
            }
            Text("↗", color = Color(0xFFF4F2EC), fontSize = 22.sp)
        }
        Spacer(Modifier.height(17.dp))
        Canvas(Modifier.fillMaxWidth().height(1.dp)) { drawLine(Color(0xFF343733), Offset.Zero, Offset(size.width, 0f)) }
    }
}

@Composable
internal fun ToneOption(label: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val foreground = if (selected) Color(0xFF10110F) else if (enabled) Color(0xFFF4F2EC) else Color(0xFF70736D)
    Box(Modifier.padding(bottom = 6.dp).sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .background(if (selected) Color(0xFFF4F2EC) else Color.Transparent, RoundedCornerShape(3.dp))
        .border(1.dp, if (selected) Color(0xFFF4F2EC) else Color(0xFF50544D), RoundedCornerShape(3.dp))
        .semantics { this.selected = selected }
        .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(label.uppercase(), color = foreground, fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = .5.sp)
    }
}
