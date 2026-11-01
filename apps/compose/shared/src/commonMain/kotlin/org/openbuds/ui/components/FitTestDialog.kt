// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.openbuds.core.*
import org.openbuds.ui.theme.*

@Composable internal fun FitTestConsent(snapshot: Snapshot, minimumWorn: Int = 2, components: List<ComponentSpec> = NormalizedEarbudComponents.filter { it.icon != "case" }, dismiss: () -> Unit, start: () -> Unit) {
    var consent by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Check your ear-tip seal") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FitPair(components.map { snapshot.earStatus.getOrNull(it.id - 1) ?: "Not reported" }, components.map { it.name.lowercase().replaceFirstChar { c -> c.titlecase() } })
            Text("1  ${if (minimumWorn == 1) "Wear the earbud securely." else "Wear both earbuds securely."}\n2  Sit somewhere quiet.\n3  Start the short sound test.", fontSize = 14.sp, lineHeight = 23.sp)
            Text("The earbuds will play a test sound and report a result for each ear.", color = Muted, fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(consent, { consent = it }); Text(if (minimumWorn == 1) "I'm wearing the earbud and agree to the test sound." else "I'm wearing both earbuds and agree to the test sound.", fontSize = 12.sp)
            }
        }
    }, confirmButton = { Button(onClick = start, enabled = consent && snapshot.wearReadiness(minimumWorn) != WearReadiness.NeedsWear) { Text("Start fit test") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable internal fun FitPair(statuses: List<String>, labels: List<String> = listOf("Left", "Right")) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        labels.forEachIndexed { index, name ->
            Column(Modifier.weight(1f).padding(horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ReadingIcon(index, statuses.getOrNull(index) ?: "Not reported", LocalMotion.current)
                Text(name, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(statuses.getOrNull(index) ?: "Not reported", color = Muted, fontSize = 11.sp, lineHeight = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable internal fun FitTestProgress(snapshot: Snapshot, components: List<ComponentSpec> = NormalizedEarbudComponents.filter { it.icon != "case" }, stop: () -> Unit) {
    AlertDialog(onDismissRequest = {}, title = { Text("Checking the seal…") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            FitPair(components.map { snapshot.earStatus.getOrNull(it.id - 1) ?: "Not reported" }, components.map { it.name.lowercase().replaceFirstChar { c -> c.titlecase() } })
            if (LocalMotion.current) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Paper)
            Text("Keep both earbuds in place. Results appear when the earbuds report them.", fontSize = 13.sp)
        }
    }, confirmButton = { TextButton(onClick = stop) { Text("Stop fit test") } })
}
