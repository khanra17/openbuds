// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.core

import kotlinx.serialization.json.*

data class FitVerdict(val component: Int?, val label: String, val outcome: String)
data class DeviceResult(val title: String, val text: String, val details: String? = null,
    val fit: List<FitVerdict>? = null, val components: List<ComponentSpec> = NormalizedEarbudComponents.filter { it.icon != "case" })

internal fun describeResult(operation: String?, data: JsonElement, profile: DeviceProfile? = null): DeviceResult {
    val raw = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), data)
    if (operation != "run_fit_test") return DeviceResult("Device diagnostics", raw)
    val readings = data.arr().map { item ->
        val row = item.obj()
        val id = row["component"].obj()["id"].int() ?: row["raw"].obj()["ear"].int()
        val component = profile?.components?.firstOrNull { it.id == id }?.name?.lowercase()?.replaceFirstChar { it.titlecase() }
            ?: when (id) { 1 -> "Left earbud"; 2 -> "Right earbud"; null -> "Unreported component"; else -> "Component $id" }
        val outcome = when (row["outcome"].text()) {
            "GeneralFit" -> "General fit"
            "GoodFit" -> "Good fit"
            "DetectionFailed" -> "Detection failed"
            "InterruptedByWear" -> "Interrupted by a wear-state change"
            "InterruptedByApp" -> "Interrupted by the app"
            else -> "Result unavailable"
        }
        FitVerdict(id, component, outcome)
    }
    val summary = readings.joinToString("\n") { "${it.label}: ${it.outcome}" }.ifEmpty { "No fit verdicts were reported." }
    return DeviceResult("Ear tip fit test", "$summary\n\nMissing components have no reported verdict.", raw, readings, profile?.components?.filter { it.icon != "case" } ?: NormalizedEarbudComponents.filter { it.icon != "case" })
}
