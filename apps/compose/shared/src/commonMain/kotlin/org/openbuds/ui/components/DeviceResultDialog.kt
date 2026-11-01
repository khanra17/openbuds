// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbuds.core.DeviceResult

@Composable
internal fun DeviceResultDialog(result: DeviceResult, close: () -> Unit) {
    var details by remember(result) { mutableStateOf(false) }
    fun back() { if (details) details = false else close() }
    AlertDialog(onDismissRequest = ::back,
        title = { Text(if (details) "Technical details" else result.title) },
        text = { Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!details) result.fit?.let { verdicts ->
                FitPair(result.components.map { c -> verdicts.firstOrNull { it.component == c.id }?.outcome ?: "Not reported" }, result.components.map { it.name.lowercase().replaceFirstChar { c -> c.titlecase() } })
            }
            SelectionContainer { Text(if (details) result.details.orEmpty() else result.text, fontSize = 13.sp) }
        } },
        confirmButton = { TextButton(onClick = ::back) { Text(if (details) "Back" else "Close") } },
        dismissButton = {
            if (!details && result.details != null) TextButton(onClick = { details = true }) { Text("Details") }
        })
}
