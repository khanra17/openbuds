// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Only platform I/O is bridged. JSON requests use the same implementation as the C SDK/CLI. */
object NativeBridge {
    init {
        if (System.getProperty("java.vm.name").orEmpty().contains("Dalvik", true)) System.loadLibrary("openbuds")
        else {
            val name = System.mapLibraryName("openbuds")
            val directory = File.createTempFile("openbuds-native-", "").apply { check(delete()); check(mkdir()); deleteOnExit() }
            val file = directory.resolve(name).apply { deleteOnExit() }
            requireNotNull(NativeBridge::class.java.getResourceAsStream("/$name")) { "Native library missing: $name" }.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            System.load(file.absolutePath)
        }
    }
    external fun styleWindow(handle: Long)
    external fun catalog(): String
    external fun devices(): String
    external fun open(transport: Any?, address: String, model: String): Long
    external fun timeout(handle: Long, milliseconds: Int)
    external fun request(handle: Long, json: String): String
    external fun cancel(handle: Long)
    external fun close(handle: Long)
}
class JvmDeviceLink(
    private val pairedProvider: (suspend () -> List<PairedDevice>)? = null,
    private val transportFactory: (suspend (String, DeviceProfile) -> Any)? = null,
) : DeviceLink {
    private val handle = AtomicLong(0)
    override fun profiles() = DeviceProfile.catalog(NativeBridge.catalog())
    override suspend fun paired(): List<PairedDevice> = withContext(Dispatchers.IO) {
        pairedProvider?.invoke() ?: Json.parseToJsonElement(NativeBridge.devices()).arr().map {
            val device = it.obj(); PairedDevice(device["name"].text().orEmpty(), device["address"].text().orEmpty(), device["connected"].bool() == true)
        }
    }
    override suspend fun connect(address: String) { error("Select a supported device model first") }
    override suspend fun connectModel(address: String, profile: DeviceProfile) = withContext(Dispatchers.IO) {
        check(handle.get() == 0L) { "Disconnect before connecting again" }
        val transport = transportFactory?.invoke(address, profile)
        val opened = try { NativeBridge.open(transport, address, profile.id) }
        catch (error: Throwable) { (transport as? AutoCloseable)?.close(); throw error }
        check(opened != 0L) { "Native connection did not return a session" }
        handle.set(opened)
    }
    override suspend fun timeout(milliseconds: Long) = withContext(Dispatchers.IO) { NativeBridge.timeout(handle.get(), milliseconds.toInt()) }
    override suspend fun call(request: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        val id = handle.get(); check(id != 0L) { "Connect your earbuds first" }
        decodeDeviceResponse(NativeBridge.request(id, request.toString()))
    }
    override fun cancel() { handle.get().takeIf { it != 0L }?.let { runCatching { NativeBridge.cancel(it) } } }
    override suspend fun close() = withContext(Dispatchers.IO) {
        val id = handle.get()
        if (id != 0L) { NativeBridge.close(id); handle.compareAndSet(id, 0) }
    }
}
