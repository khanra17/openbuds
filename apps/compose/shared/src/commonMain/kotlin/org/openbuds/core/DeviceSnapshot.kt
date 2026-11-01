// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.core

import kotlinx.serialization.json.*

data class PairedDevice(val name: String, val address: String, val connected: Boolean = false, val driverId: String? = null)
data class EqProfile(val id: Int, val name: String, val selected: Boolean, val min: Int, val max: Int, val gains: List<Int>, val frequencies: List<Int> = emptyList()) {
    fun json() = buildJsonObject {
        put("id", id); put("name", name); put("selected", selected); put("min", min); put("max", max)
        put("bands", buildJsonArray { frequencies.zip(gains).forEach { (frequency, gain) -> add(buildJsonObject { put("frequency", frequency); put("gain", gain) }) } })
    }
}
data class Touch(val ear: Int, val gesture: Int, val function: Int, val button: Int)
data class DynamicBand(val kind: Int, val min: Int, val max: Int, val value: Int)
enum class WearReadiness { Ready, NeedsWear, Unknown }

data class Snapshot(val raw: JsonObject = JsonObject(emptyMap())) {
    private val worn: List<Boolean?> = (1..2).map { ear ->
        val reading = raw["wear"].arr().firstOrNull { it.obj()["component"].int() == ear }.obj()
        when {
            reading["connected"].bool() == false -> false
            reading["in_case"].bool() == true && reading["worn"].bool() == true -> null
            reading["in_case"].bool() == true -> false
            else -> reading["worn"].bool()
        }
    }
    fun wearReadiness(required: Int): WearReadiness = when {
        required <= 0 -> WearReadiness.Ready
        required == 1 && worn.any { it == true } -> WearReadiness.Ready
        required >= 2 && worn.all { it == true } -> WearReadiness.Ready
        required == 1 && worn.all { it == false } -> WearReadiness.NeedsWear
        required >= 2 && worn.any { it == false } -> WearReadiness.NeedsWear
        else -> WearReadiness.Unknown
    }
    val noiseWearReadiness = when {
        worn.any { it == true } -> WearReadiness.Ready
        worn.all { it == false } -> WearReadiness.NeedsWear
        else -> WearReadiness.Unknown
    }
    val fitWearReadiness = when {
        worn.all { it == true } -> WearReadiness.Ready
        worn.any { it == false } -> WearReadiness.NeedsWear
        else -> WearReadiness.Unknown
    }
    val batteries: List<Int?> = (1..3).map { ear -> raw["battery"].arr().firstOrNull { it.obj()["component"].int() == ear }.obj()["percent"].int()?.takeIf { it in 0..100 } }
    val earStatus: List<String> = (1..2).map { ear ->
        val observation = raw["wear"].arr().firstOrNull { it.obj()["component"].int() == ear }.obj()
        when {
            observation.isEmpty() -> "Not reported"
            observation["in_case"].bool() == true && observation["worn"].bool() == true -> "Status unclear"
            observation["in_case"].bool() == true -> "In case"
            observation["connected"].bool() == false -> "Disconnected"
            observation["worn"].bool() == true -> "In ear"
            observation["in_case"].bool() == false && observation["worn"].bool() == false -> "Not in ear"
            else -> "Not reported"
        }
    }
    val inCaseComponents = raw["wear"].arr().filter { it.obj()["component"].int() in 1..2 && it.obj()["in_case"].bool() == true && it.obj()["worn"].bool() != true }.mapNotNull { it.obj()["component"].int() }.toSet()
    val anyInCase = inCaseComponents.isNotEmpty()
    val caseBattery: Int? get() = batteries[2].takeIf { anyInCase || raw["wear"].arr().any { it.obj()["component"].int() == 3 && it.obj()["connected"].bool() == true } }
    val caseLinkStatus: String = raw["wear"].arr().firstOrNull { it.obj()["component"].int() == 3 }.obj().let { observation ->
        when (observation["connected"].bool()) {
            true -> "Connected"
            false -> "Not connected"
            null -> if (anyInCase) "Via earbud" else "Not available"
        }.let { if (anyInCase && it == "Not connected") "Via earbud" else it }
    }
    val noise: Int? = raw["noise"].obj()["value"].int()
    val preset: Int? = raw["eq_preset"].int()
    val profiles: List<EqProfile> = raw["custom_eq"].arr().mapNotNull { item ->
        val e = item.obj(); val bands = e["bands"].arr()
        // Unknown frequency layouts are not silently coerced into the six-band editor.
        if (bands.isEmpty() || bands.any { it.obj()["frequency"].int() == null || it.obj()["gain"].int() == null }) null else EqProfile(
            e["id"].int() ?: return@mapNotNull null, e["name"].text().orEmpty(), e["selected"].bool() == true,
            e["min"].int() ?: 0, e["max"].int() ?: 0, bands.map { it.obj()["gain"].int()!! }, bands.map { it.obj()["frequency"].int()!! })
    }
    val touch: List<Touch> = raw["touch"].arr().mapNotNull {
        val t = it.obj(); val gesture = t["gesture"].int(); val ear = t["ear"].int()
        if (gesture == null || ear !in 1..2 || t["button"].int() == null || t["function"].int() == null) null
        else Touch(ear!!, gesture, t["function"].int()!!, t["button"].int()!!)
    }
    val dynamic: List<DynamicBand> = raw["dynamic_bands"].arr().map {
        val b = it.obj(); DynamicBand(b["kind"].int() ?: 0, b["min"].int() ?: 0, b["max"].int() ?: 0, b["value"].int() ?: 0)
    }.filter { it.min <= it.max && it.value in it.min..it.max }
    private val features = raw["features"].arr().associate { it.obj()["id"].int() to it.obj()["enabled"].bool() }
    fun feature(id: Int): Boolean? = features[id]
    fun replace(key: String, value: JsonElement) = Snapshot(JsonObject(raw + (key to value)))
}
