// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbuds.ui.theme.Muted
import org.openbuds.ui.theme.Paper

/** Human recovery first; wire/transport details are available only on request. */
@Composable
internal fun FeedbackMessage(message: String, details: String?, dismiss: () -> Unit) {
    var showDetails by remember(message) { mutableStateOf(false) }
    Surface(color = Color(0xFF242824), shape = RoundedCornerShape(6.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(message, color = Paper, fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (details != null) TextButton(onClick = { showDetails = true }) { Text("Details", color = Muted) }
                TextButton(onClick = dismiss) { Text("Dismiss", color = Muted) }
            }
        }
    }
    if (showDetails && details != null) AlertDialog(onDismissRequest = { showDetails = false },
        title = { Text("Technical details") }, text = {
            SelectionContainer { Text(details, fontSize = 12.sp,
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) }
        }, confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Close") } })
}
