// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbuds.ui.theme.*

@Composable internal fun InfoCard(title: String, description: String) {
    Column(Modifier.fillMaxWidth().border(1.dp, Color(0xFF343833), RoundedCornerShape(4.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(description, color = Muted, fontSize = 12.sp, lineHeight = 17.sp)
    }
}
@Composable internal fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, color = Muted, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp, fontFamily = FontFamily.Monospace)
}
@Composable internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) { Label(title); content() }
}
@Composable internal fun RowAction(title: String, subtitle: String? = null, enabled: Boolean = true, leading: (@Composable () -> Unit)? = null, trailing: @Composable () -> Unit = { Text("›", fontSize = 26.sp, color = Muted) }, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = action).padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) { leading(); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = if (enabled) Paper else Muted, fontSize = 16.sp)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 5.dp))
        }
        trailing()
    }
    HorizontalDivider(color = Color(0xFF242824), thickness = .5.dp)
}
