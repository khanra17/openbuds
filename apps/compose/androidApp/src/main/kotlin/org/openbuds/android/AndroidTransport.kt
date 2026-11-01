// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.android

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.*
import org.openbuds.core.PairedDevice
import java.io.IOException
import java.util.UUID
import java.util.concurrent.*

/** One blocking reader + bounded 64 KiB queue. No scan or InputStream.available polling. */
class AndroidTransport(context: Context, address: String, service: UUID) : AutoCloseable {
    private val socket = adapter(context).getRemoteDevice(address).createRfcommSocketToServiceRecord(service)
    private val received = ArrayBlockingQueue<ByteArray>(16)
    @Volatile private var closed = false
    @Volatile private var failure = "Bluetooth disconnected"
    private var reader: Thread? = null
    private var pending: ByteArray? = null
    private var offset = 0
    suspend fun connect(): AndroidTransport = withContext(Dispatchers.IO) {
        val timeout = deadlines.schedule({ close() }, 15, TimeUnit.SECONDS)
        try {
            socket.connect()
            if (closed) throw IOException("Connection was cancelled")
            reader = Thread({
                try {
                    val buffer = ByteArray(4096)
                    val input = socket.inputStream
                    while (!closed) {
                        val count = input.read(buffer)
                        if (count < 0) throw IOException("Earbuds closed the control connection")
                        if (count > 0) received.put(buffer.copyOf(count))
                    }
                } catch (error: Exception) {
                    failure = error.message ?: "Bluetooth disconnected"
                } finally { close() }
            }, "earbuds-rx").apply { isDaemon = true; start() }
            this@AndroidTransport
        } catch (error: Exception) { close(); throw error }
        finally { timeout.cancel(false) }
    }
    @Throws(IOException::class)
    fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (closed) throw IOException(failure)
        try {
            if (pending == null) { pending = received.poll(timeoutMs.coerceAtLeast(1).toLong(), TimeUnit.MILLISECONDS); offset = 0 }
            if (closed) throw IOException(failure)
            val data = pending ?: return -1
            val count = minOf(buffer.size, data.size - offset)
            data.copyInto(buffer, 0, offset, offset + count)
            offset += count
            if (offset == data.size) pending = null
            return count
        } catch (error: InterruptedException) { Thread.currentThread().interrupt(); throw IOException("Bluetooth read interrupted", error) }
    }
    @Throws(IOException::class)
    fun write(bytes: ByteArray) {
        if (closed) throw IOException(failure)
        val timeout = deadlines.schedule({ close() }, 12, TimeUnit.SECONDS)
        try { socket.outputStream.write(bytes) } finally { timeout.cancel(false) }
    }
    fun abortConnecting() { if (!socket.isConnected) close() }
    override fun close() {
        closed = true
        runCatching { socket.close() }
        reader?.interrupt()
        received.clear(); received.offer(ByteArray(0))
    }
    companion object {
        private suspend fun connected(context: Context, adapter: BluetoothAdapter, profile: Int): Set<String> =
            kotlinx.coroutines.withTimeoutOrNull(2500) {
                kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                    val listener = object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(type: Int, proxy: BluetoothProfile) {
                            try {
                                val addresses = proxy.connectedDevices.map { it.address }.toSet()
                                if (continuation.isActive) continuation.resumeWith(Result.success(addresses))
                            } catch (error: Exception) {
                                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                            } finally { adapter.closeProfileProxy(type, proxy) }
                        }
                        override fun onServiceDisconnected(type: Int) { if (continuation.isActive) continuation.resumeWith(Result.success(emptySet())) }
                    }
                    if (!adapter.getProfileProxy(context, listener, profile)) continuation.resumeWith(Result.success(emptySet()))
                }
            } ?: emptySet()

        private val deadlines = ScheduledThreadPoolExecutor(1) { job -> Thread(job, "earbud-deadlines").apply { isDaemon = true } }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(30, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }
        private fun adapter(context: Context): BluetoothAdapter {
            if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                throw SecurityException("Allow Nearby devices to connect your earbuds.")
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException("This device has no Bluetooth adapter")
            if (!adapter.isEnabled) throw IOException("Turn on Bluetooth in system settings")
            return adapter
        }
        fun bonded(context: Context): List<PairedDevice> = try {
            adapter(context).bondedDevices.map { PairedDevice(it.name ?: "Unnamed device", it.address) }
        } catch (error: SecurityException) { throw IOException("Nearby devices permission was denied or revoked. Allow it in OpenBuds app settings.", error) }
        suspend fun paired(context: Context): List<PairedDevice> = try {
            val adapter = adapter(context)
            val connected = kotlinx.coroutines.coroutineScope {
                listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET).map { profile ->
                    async { connected(context, adapter, profile) }
                }.flatMap { it.await() }.toSet()
            }
            adapter.bondedDevices.map { PairedDevice(it.name ?: "Unnamed device", it.address, it.address in connected) }.sortedBy { it.name }
        } catch (error: SecurityException) {
            throw IOException("Nearby devices permission was denied or revoked. Allow it in OpenBuds app settings.", error)
        }
    }
}
