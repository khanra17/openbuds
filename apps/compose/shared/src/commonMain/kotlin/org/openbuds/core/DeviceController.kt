// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

interface DeviceLink {
    fun profiles(): List<DeviceProfile> = emptyList()
    suspend fun paired(): List<PairedDevice>
    suspend fun connect(address: String)
    suspend fun connectModel(address: String, profile: DeviceProfile) = connect(address)
    suspend fun timeout(milliseconds: Long) {}
    suspend fun call(request: JsonObject): JsonElement
    fun cancel()
    suspend fun close()
}
enum class HostRequest { Start, Stop }
data class AppState(
    val connected: Boolean = false, val busy: Boolean = false, val deviceAddress: String? = null,
    val devices: List<PairedDevice> = emptyList(), val snapshot: Snapshot = Snapshot(),
    val profile: DeviceProfile? = null, val lastAnc: Int? = null, val activeOperation: String? = null,
    val message: String? = null, val issue: UserIssue? = null, val result: DeviceResult? = null, val savedProfileId: Int? = null,
)
class DeviceController(private val link: DeviceLink, private val preferences: DevicePreferences = NoDevicePreferences) {
    val profiles = link.profiles()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(AppState(profile = profiles.singleOrNull()))
    val state = mutable.asStateFlow()
    private val host = MutableSharedFlow<HostRequest>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val hostRequests = host.asSharedFlow() // No replay: an old request must never ring a newly opened app.
    private var events: Job? = null
    private var active = true
    private var epoch = 0
    private var manualDisconnect = false
    val connectionAllowed: Boolean get() = active && !manualDisconnect
    fun knownDevices(devices: List<PairedDevice>) = update { it.copy(devices = devices.filter { d -> profileFor(d) != null }) }
    private fun update(block: (AppState) -> AppState) {
        val before = mutable.value
        mutable.update { previous ->
            var next = block(previous)
            val observedAnc = next.snapshot.noise?.takeIf { value -> next.profile?.noise?.levels?.any { it.id == value } == true }
            if (observedAnc != null) next = next.copy(lastAnc = observedAnc)
            val wearChanged = previous.snapshot.earStatus != next.snapshot.earStatus
            val resolved = when (next.issue?.wearRequirement) {
                WearRequirement.OneEar -> next.snapshot.noiseWearReadiness == WearReadiness.Ready
                WearRequirement.BothEars -> next.snapshot.fitWearReadiness == WearReadiness.Ready
                null -> false
            }
            if (wearChanged && resolved) next.copy(message = null, issue = null) else next
        }
        val next = mutable.value
        if (next.lastAnc != null && next.lastAnc != before.lastAnc && next.deviceAddress != null)
            preferences.put("anc:${next.profile?.id}:${next.deviceAddress}", next.lastAnc.toString())
    }
    fun dismiss() = update { it.copy(message = null, issue = null, result = null) }
    fun notice(message: String) = update { it.copy(message = message, issue = null) }
    private fun report(issue: UserIssue) = update { it.copy(message = issue.message, issue = issue) }
    private fun operation(action: String? = null, block: suspend () -> Unit) {
        if (state.value.busy || !active) return
        val generation = epoch
        update { it.copy(busy = true, activeOperation = action, message = null, issue = null) }
        scope.launch { mutex.withLock {
            try { if (active && generation == epoch) block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (active && generation == epoch) report(presentFailure(error, action, state.value.profile)) }
            finally { update { it.copy(busy = false, activeOperation = null) } }
        } }
    }
    fun profileFor(device: PairedDevice): DeviceProfile? = profiles.firstOrNull {
        it.matches(device) || (preferences.get("device_address") == device.address && preferences.get("device_model") == it.id)
    }
    fun paired() = operation("paired") { val devices = link.paired(); knownDevices(devices) }
    fun autoConnect() = operation("paired") {
        if (state.value.connected || manualDisconnect) return@operation
        val devices = link.paired().filter { profileFor(it) != null }
        update { it.copy(devices = devices) }
        val connected = devices.filter { it.connected }
        val candidate = connected.firstOrNull { it.address == preferences.get("device_address") } ?: connected.singleOrNull()
            ?: if (connected.isEmpty()) devices.firstOrNull { it.address == preferences.get("device_address") } ?: devices.singleOrNull() else null
        if (candidate != null) connectInside(candidate.address, profileFor(candidate)!!)
    }
    fun connect(address: String, modelId: String? = null) = operation("connect") {
        val profile = profiles.firstOrNull { it.id == modelId }
            ?: state.value.devices.firstOrNull { it.address == address }?.let(::profileFor)
            ?: profiles.singleOrNull()
            ?: error("Select a supported device model first")
        manualDisconnect = false
        connectInside(address, profile)
    }
    private suspend fun connectInside(address: String, profile: DeviceProfile) {
        val generation = epoch
        events?.cancel(); link.close()
        update { it.copy(connected = false, deviceAddress = address, profile = profile, snapshot = Snapshot(),
            lastAnc = preferences.get("anc:${profile.id}:$address")?.toIntOrNull()) }
        link.connectModel(address, profile)
        if (!active || generation != epoch) { link.close(); return }
        try {
            link.timeout(12_000)
            link.call(request("initialize"))
            if (!active || generation != epoch) { link.close(); return }
            var snapshot = Snapshot(link.call(request("snapshot")).obj()["raw"].obj())
            // A short, read-only optional probe must not stall normal startup.
            val optional = profile.features.filter { it.experimental }
            if (optional.isNotEmpty()) try {
                link.timeout(750)
                val data = link.call(request("feature_ids") { put("ids", JsonArray(optional.map { JsonPrimitive(it.id) })) })
                snapshot = mergeRows(snapshot, "features", JsonArray(data.arr().mapNotNull { it.obj()["raw"] }), "id")
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            finally { link.timeout(12_000) }
            if (!active || generation != epoch) { link.close(); return }
            if (snapshot.anyInCase) try {
                val battery = link.call(request("battery"))
                snapshot = mergeRows(snapshot, "battery", JsonArray(battery.arr().mapNotNull { it.obj()["raw"] }), "component")
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            link.call(request("subscribe"))
            if (!active || generation != epoch) { link.close(); return }
            preferences.put("device_address", address); preferences.put("device_model", profile.id)
            update { it.copy(connected = true, snapshot = snapshot) }
            listen()
        } catch (error: Exception) { link.close(); throw error }
    }
    fun refresh() {
        if (!state.value.connected) return
        operation("snapshot") {
            if (state.value.connected) {
                val snapshot = Snapshot(link.call(request("snapshot")).obj()["raw"].obj())
                update { it.copy(snapshot = snapshot) }
            }
        }
    }
    fun command(command: JsonObject, read: String? = null, key: String? = null) = operation(command["op"].text()) {
        if (!state.value.connected) { notice("Connect your earbuds first."); return@operation }
        val op = command["op"].text()
        if (op == "set_noise" && state.value.snapshot.wearReadiness(state.value.profile?.noise?.minimumWorn ?: 0) == WearReadiness.NeedsWear) {
            report(if (state.value.profile?.noise?.minimumWorn == 2) UserIssue("Put on both earbuds to change noise control.", IssueArea.Noise, wearRequirement = WearRequirement.BothEars) else wearIssue(WearRequirement.OneEar)); return@operation
        }
        if (op == "run_fit_test" && state.value.snapshot.wearReadiness(state.value.profile?.fitMinimumWorn ?: 0) == WearReadiness.NeedsWear) {
            report(wearIssue(WearRequirement.BothEars)); return@operation
        }
        val result = link.call(command)
        if (read != null && key != null) {
            val query = if (read == "feature_ids") request(read) { put("ids", JsonArray(listOf(command["id"] ?: JsonNull))) } else request(read)
            val data = link.call(query)
            val raw = when (read) {
                "noise", "noise_cycle" -> data.obj()["raw"] ?: JsonNull
                "eq_preset" -> data.obj()["id"] ?: JsonNull
                "touch", "features", "feature_ids", "dynamic_bands", "multipoint" -> JsonArray(data.arr().mapNotNull { it.obj()["raw"] })
                else -> data
            }
            update { it.copy(snapshot = if (key == "features") mergeRows(it.snapshot, key, raw, "id") else it.snapshot.replace(key, raw)) }
            if (op == "set_eq_preset") {
                val profiles = link.call(request("custom_eq")); update { it.copy(snapshot = it.snapshot.replace("custom_eq", profiles)) }
            }
        } else if (result !is JsonNull) update { it.copy(result = describeResult(op, result, state.value.profile)) }
    }
    fun setNoise(value: Int) {
        val spec = state.value.profile?.noise ?: return
        val choice = (spec.levels + spec.modes).firstOrNull { it.id == value } ?: return
        command(request("set_noise") { put("mode", choice.wireName) }, "noise", "noise")
    }
    fun feature(spec: FeatureSpec, value: Boolean) {
        if (spec.wireName != null) feature(spec.wireName, value)
        else command(request("set_feature_id") { put("id", spec.id); put("enabled", value); put("confirm_experimental", true) }, "feature_ids", "features")
    }
    fun feature(name: String, value: Boolean) = command(request("set_feature") { put("feature", name); put("enabled", value) }, "features", "features")
    fun save(profile: EqProfile, add: Boolean) = operation("set_custom_eq") {
        if (!state.value.connected) { notice("Connect your earbuds before saving."); return@operation }
        val previous = state.value.snapshot.profiles.map { it.id }.toSet()
        update { it.copy(savedProfileId = null) }
        link.call(request("set_custom_eq") { put("eq", profile.json()); put("action", if (add) "Add" else "Update") })
        var data = link.call(request("custom_eq")); var snapshot = state.value.snapshot.replace("custom_eq", data)
        val actual = snapshot.profiles.singleOrNull { it.name == profile.name && it.gains == profile.gains && if (add) it.id !in previous else it.id == profile.id }
        update { it.copy(snapshot = snapshot) }
        if (actual == null) { notice("Couldn't confirm this EQ was saved. Refresh your saved EQs before trying again."); return@operation }
        if (!actual.selected && active) {
            link.call(request("set_custom_eq") { put("eq", actual.json()); put("action", "Update") })
            data = link.call(request("custom_eq")); snapshot = snapshot.replace("custom_eq", data)
        }
        update { it.copy(snapshot = snapshot, savedProfileId = actual.id) }
        if (snapshot.profiles.count { it.selected } != 1 || snapshot.profiles.none { it.id == actual.id && it.selected })
            notice("Your EQ was saved, but its selection wasn't confirmed. Refresh before applying it again.")
    }
    fun cancel() { link.cancel() }
    fun pause() { if (state.value.activeOperation in setOf("run_fit_test", "find_for")) link.cancel() }
    fun resume() { active = true; manualDisconnect = false }
    fun endSession(): Job {
        active = false; epoch++; link.cancel(); events?.cancel(); host.tryEmit(HostRequest.Stop)
        return scope.launch { mutex.withLock { link.close(); update { it.copy(connected = false, busy = false, activeOperation = null, snapshot = Snapshot()) } } }
    }
    fun shutdown() { val stop = endSession(); scope.launch { stop.join(); scope.cancel() } }
    suspend fun close() { endSession().join(); scope.cancel() }
    fun disconnect() { manualDisconnect = true; endSession() }
    private fun listen() {
        events = scope.launch {
            while (isActive && active && state.value.connected) {
                mutex.withLock {
                    try { applyEvent(link.call(request("next_event") { put("timeout_ms", 250) }).obj()) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { if (!error.isExpectedEventWait()) {
                        link.close(); host.tryEmit(HostRequest.Stop)
                        update { it.copy(connected = false, snapshot = Snapshot(), message = "The control connection was lost. OpenBuds will reconnect when your earbuds are available.", issue = null) }
                    } }
                }; yield()
            }
        }
    }
    private fun applyEvent(event: JsonObject) {
        val (kind, data) = event.entries.singleOrNull() ?: return
        if (kind == "HostOperation") {
            val profile = state.value.profile ?: return
            val op = data.obj()["operation"].obj()["id"].int()
            if (op == profile.hostStop) host.tryEmit(HostRequest.Stop)
            else if (op == profile.hostStart && profile.feature("phone_action")?.let { state.value.snapshot.feature(it.id) } == true) host.tryEmit(HostRequest.Start)
            return
        }
        val key = mapOf("Battery" to "battery", "Wear" to "wear", "Noise" to "noise", "NoiseCycle" to "noise_cycle", "DynamicBand" to "dynamic_bands", "Features" to "features", "EqPreset" to "eq_preset", "CustomEq" to "custom_eq", "Multipoint" to "multipoint")[kind] ?: return
        val raw = when (kind) {
            "Battery", "Wear", "Features", "Multipoint" -> JsonArray(data.arr().mapNotNull { it.obj()["raw"]?.takeIf { v -> v !is JsonNull } })
            "Noise", "NoiseCycle" -> data.obj()["raw"] ?: return
            "DynamicBand" -> JsonArray(listOfNotNull(data.obj()["raw"]))
            "EqPreset" -> data.obj()["id"] ?: return
            else -> data
        }
        val previousCase = state.value.snapshot.inCaseComponents
        update { it.copy(snapshot = if (kind in listOf("Battery", "Wear", "Features", "DynamicBand"))
            mergeRows(it.snapshot, key, raw, when (kind) { "Features" -> "id"; "DynamicBand" -> "kind"; else -> "component" }) else it.snapshot.replace(key, raw)) }
        if (kind == "Wear" && (state.value.snapshot.inCaseComponents - previousCase).isNotEmpty()) scope.launch {
            mutex.withLock { if (active && state.value.connected) try {
                val battery = link.call(request("battery"))
                update { it.copy(snapshot = mergeRows(it.snapshot, "battery", JsonArray(battery.arr().mapNotNull { b -> b.obj()["raw"] }), "component")) }
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { } }
        }
    }
    private fun mergeRows(snapshot: Snapshot, key: String, raw: JsonElement, id: String): Snapshot {
        val changed = raw.arr().associateBy { it.obj()[id] }
        return snapshot.replace(key, JsonArray(snapshot.raw[key].arr().filter { it.obj()[id] !in changed } + changed.values))
    }
}
