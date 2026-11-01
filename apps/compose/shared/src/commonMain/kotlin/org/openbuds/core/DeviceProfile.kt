// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.core

import kotlinx.serialization.json.*

data class ComponentSpec(val id: Int, val name: String, val icon: String)
val NormalizedEarbudComponents = listOf(ComponentSpec(1, "LEFT", "earbud_left"), ComponentSpec(2, "RIGHT", "earbud_right"), ComponentSpec(3, "CASE", "case"))
data class Choice(val id: Int, val name: String, val wireName: String = "")
data class FeatureSpec(val key: String, val id: Int, val name: String, val detail: String,
    val group: String, val wireName: String?, val experimental: Boolean, val experimentalReason: String?)
data class NoiseSpec(val off: Int, val transparency: Int, val defaultAnc: Int, val minimumWorn: Int,
    val levels: List<Choice>, val modes: List<Choice>, val wearStatus: Int?, val noiseCommand: Int?, val fitCommand: Int?)
data class EqSpec(val frequencies: List<Int>, val min: Int, val max: Int, val presets: List<Choice>, val nameBytesMax: Int?)

/** Supplied by the SDK model registry. Shared screens never choose wire IDs or a Bluetooth UUID. */
data class DeviceProfile(val raw: JsonObject) {
    val id = raw["id"].text().orEmpty()
    val name = raw["name"].text() ?: "Earbuds"
    val components = raw["components"].arr().map { val c=it.obj(); ComponentSpec(c["id"].int() ?: 0, c["name"].text().orEmpty(), c["icon"].text().orEmpty()) }.ifEmpty { NormalizedEarbudComponents }
    val aliases = raw["name_aliases"].arr().mapNotNull { it.text() }
    val serviceBytes = raw["service_uuid_be"].arr().mapNotNull { it.int() }
    val features = raw["features"].arr().map { item -> val f = item.obj()
        FeatureSpec(f["key"].text().orEmpty(), f["id"].int() ?: -1, f["name"].text().orEmpty(),
            f["detail"].text().orEmpty(), f["group"].text().orEmpty(), f["wire_name"].text(), f["experimental"].bool() == true, f["experimental_reason"].text())
    }
    val noise = raw["noise"]?.obj()?.takeIf { it.isNotEmpty() }?.let { n ->
        val failures = n["wear_rejection"].obj()
        NoiseSpec(n["off"].int() ?: 0, n["transparency"].int() ?: 0, n["default_anc"].int() ?: 0,
            n["minimum_worn"].int() ?: 0, choices(n["levels"]), choices(n["modes"]),
            failures["status"].int(), failures["noise_command"].int(), failures["fit_command"].int())
    }
    val eq = raw["eq"]?.obj()?.takeIf { it.isNotEmpty() }?.let { e ->
        EqSpec(e["frequencies"].arr().mapNotNull { it.int() }, e["min"].int() ?: 0, e["max"].int() ?: 0, choices(e["presets"]), e["name_bytes_max"].int())
    }
    val gestures = raw["gestures"].obj()
    fun visibleGesture(touch: Touch) = touch.button in gestures["button_ids"].arr().mapNotNull { it.int() } && gestureRows.any { it.id == touch.gesture }
    fun cyclesNoise(touch: Touch) = gestures["cycle_binding"].obj().let { it["gesture"].int() == touch.gesture && it["function"].int() == touch.function }
    val firmwareKind = raw["firmware_kind"].int()
    val gestureRows = choices(gestures["rows"])
    val functions = choices(gestures["functions"])
    val cycles = choices(gestures["cycles"])
    val dynamicBands = choices(raw["dynamic_bands"])
    val multipoint = raw["multipoint"].obj()
    val peerStates = choices(multipoint["states"])
    val peerConnected = multipoint["connected"].int()
    val peerDisconnected = multipoint["disconnected"].int()
    val peerConnectAction = multipoint["connect_action"].int()
    val peerDisconnectAction = multipoint["disconnect_action"].int()
    val peerRemoveAction = multipoint["remove_action"].int()
    val hostStart = raw["host_events"].obj()["start"].int()
    val hostStop = raw["host_events"].obj()["stop"].int()
    val fitMinimumWorn = raw["tools"].obj()["fit_minimum_worn"].int() ?: 0
    fun hasTool(key: String) = raw["tools"].obj()[key].bool() == true
    fun feature(key: String) = features.firstOrNull { it.key == key }
    fun matches(device: PairedDevice) = device.driverId == id || aliases.any { it.equals(device.name, true) }
    fun gestureOptions(gesture: Int) = gestures["options"].obj()[gesture.toString()].arr().mapNotNull { it.int() }
    fun standardGesture(gesture: Int, function: Int) = function in gestures["standard"].obj()[gesture.toString()].arr().mapNotNull { it.int() }
    companion object {
        private fun choices(data: JsonElement?) = data.arr().mapNotNull { item -> val c = item.obj()
            c["id"].int()?.let { Choice(it, c["name"].text().orEmpty(), c["wire_name"].text().orEmpty()) }
        }
        fun catalog(json: String) = Json.parseToJsonElement(json).obj()["profiles"].arr().map { DeviceProfile(it.obj()) }
    }
}

interface DevicePreferences {
    fun get(key: String): String?
    fun put(key: String, value: String)
}
object NoDevicePreferences : DevicePreferences {
    override fun get(key: String): String? = null
    override fun put(key: String, value: String) {}
}
