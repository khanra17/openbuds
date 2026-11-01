// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.android

import android.app.Application
import android.content.*
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.openbuds.core.*
import java.nio.ByteBuffer
import java.util.UUID

class OpenBudsApplication : Application() {
    val session by lazy { ConnectionSession(this) }
}

class ConnectionSession(private val application: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val stored = application.getSharedPreferences("devices", Context.MODE_PRIVATE)
    private val preferences = object : DevicePreferences {
        override fun get(key: String) = stored.getString(key, null)
        override fun put(key: String, value: String) { stored.edit { putString(key, value) } }
    }
    @Volatile private var transport: AndroidTransport? = null
    private val link = JvmDeviceLink(pairedProvider = { AndroidTransport.paired(application) }, transportFactory = { address, profile ->
        require(profile.serviceBytes.size == 16) { "The selected driver has no valid Bluetooth service" }
        val bytes = ByteBuffer.wrap(profile.serviceBytes.map { it.toByte() }.toByteArray())
        val next = AndroidTransport(application, address, UUID(bytes.long, bytes.long))
        transport = next
        next.connect()
    })
    val controller = DeviceController(link, preferences)
    var visible = false
    var service: ConnectionService? = null
    private var stopped: Job? = null
    private var reconnect: Job? = null
    private var activating: Job? = null
    val phone = PhoneActions(application, scope, { message -> service?.updateNotification(message) }, { visible })
    init {
        scope.launch { controller.state.map { state -> state.deviceAddress?.let { "${state.profile?.id}:$it" } }.distinctUntilChanged().collect { key -> phone.bind(key); service?.updateNotification() } }
        scope.launch { phone.running.collect { running ->
            service?.updateNotification(if (running) { if (phone.action.value == PhoneAction.Ring) "Finding your phone · stops in 15 seconds" else "Flashlight on · stops in 15 seconds" } else null)
        } }
        scope.launch { controller.hostRequests.collect { if (it == HostRequest.Start) phone.start() else phone.stop() } }
        scope.launch {
            controller.state.map { it.connected to controller.connectionAllowed }.distinctUntilChanged().collect { (connected, allowed) ->
                service?.updateNotification()
                if (!allowed && service != null) application.stopService(Intent(application, ConnectionService::class.java))
                else if (!connected && allowed && service != null) scheduleReconnect()
            }
        }
    }
    fun activate() {
        visible = true
        service?.updateNotification()
        activating?.cancel()
        // Start the visible lifetime before any slow socket/initialization work.
        // This also keeps an in-flight connection alive when the user switches apps.
        if (service == null && !controller.state.value.connected) {
            val supported = runCatching { AndroidTransport.bonded(application).filter { controller.profileFor(it) != null } }.getOrDefault(emptyList())
            val candidate = supported.firstOrNull { it.address == preferences.get("device_address") } ?: supported.singleOrNull()
            if (candidate != null) { connect(candidate.address, controller.profileFor(candidate)?.id); return }
        }
        activating = scope.launch {
            stopped?.join(); controller.resume()
            connectAvailable()
        }
    }
    fun background() { visible = false; controller.pause() }
    suspend fun connectAvailable(connectedAddress: String? = null) {
        if (!controller.connectionAllowed || controller.state.value.connected || controller.state.value.busy) return
        try {
            // ACL broadcasts include case/control links even when no audio profile is active.
            if (connectedAddress != null) {
                val device = AndroidTransport.bonded(application).firstOrNull { it.address == connectedAddress && controller.profileFor(it) != null }
                if (device != null) { connect(device.address, controller.profileFor(device)?.id); return }
            }
            val devices = AndroidTransport.paired(application)
            controller.knownDevices(devices)
            val eligible = devices.filter { it.connected && controller.profileFor(it) != null }
            val candidate = eligible.firstOrNull { it.address == preferences.get("device_address") } ?: eligible.singleOrNull()
                ?: if (visible && eligible.isEmpty()) devices.filter { controller.profileFor(it) != null }.let { supported ->
                    supported.firstOrNull { it.address == preferences.get("device_address") } ?: supported.singleOrNull()
                } else null
            if (candidate != null) connect(candidate.address, controller.profileFor(candidate)?.id)
        } catch (_: Exception) { if (visible) controller.notice("Allow Nearby devices and turn on Bluetooth to connect automatically.") }
    }
    fun connect(address: String, model: String?) {
        val profile = controller.profiles.firstOrNull { it.id == model } ?: controller.profiles.singleOrNull() ?: return
        phone.bind("${profile.id}:$address")
        ContextCompat.startForegroundService(application, Intent(application, ConnectionService::class.java)
            .putExtra("address", address).putExtra("model", profile.id))
    }
    fun begin(address: String?, model: String?) {
        scope.launch {
            stopped?.join(); controller.resume()
            if (!controller.state.value.connected && address != null) controller.connect(address, model)
        }
    }
    fun systemConnectionChanged(address: String? = null) {
        if (service == null || !controller.connectionAllowed) return
        reconnect?.cancel()
        reconnect = scope.launch { delay(350); connectAvailable(address) }
    }
    private fun scheduleReconnect() {
        reconnect?.cancel()
        reconnect = scope.launch {
            for (wait in listOf(600L, 1800L, 4000L)) {
                delay(wait)
                if (service == null || !controller.connectionAllowed || controller.state.value.connected) break
                connectAvailable()
            }
        }
    }
    fun stop() {
        activating?.cancel(); reconnect?.cancel(); phone.stop(); transport?.abortConnecting()
        stopped = controller.endSession()
    }
}
