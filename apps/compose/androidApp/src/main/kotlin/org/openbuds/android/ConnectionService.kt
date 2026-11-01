// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.android

import android.app.*
import android.bluetooth.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.openbuds.air7.R

/** User-visible connected-device lifetime. Task removal stops the service; no sticky restart or boot receiver. */
class ConnectionService : Service() {
    private val session get() = (application as OpenBudsApplication).session
    private var message: String? = null
    private var foregroundTypes = 0
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val connected = intent.action == BluetoothDevice.ACTION_ACL_CONNECTED || intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED
            @Suppress("DEPRECATION")
            val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) else intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val address = if (connected) runCatching { device?.address }.getOrNull() else null
            session.systemConnectionChanged(address)
        }
    }
    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Earbud connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Connection status and controls while OpenBuds is in the background"; setSound(null, null)
            })
            notifications.createNotificationChannel(NotificationChannel(ACTIONS, "Earbud phone actions", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) })
        }
        session.service = this
        syncForeground()
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP_SESSION -> { session.stop(); stopSelf() }
            STOP_PHONE -> session.phone.stop()
            else -> session.begin(intent?.getStringExtra("address"), intent?.getStringExtra("model"))
        }
        return START_NOT_STICKY
    }
    private fun open() = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun command(action: String, code: Int) = PendingIntent.getService(this, code, Intent(this, ConnectionService::class.java).setAction(action), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notification(): Notification {
        val state = session.controller.state.value
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome).setContentTitle(message ?: state.profile?.name ?: "OpenBuds")
            .setContentText(if (session.phone.running.value) "Tap Stop phone action to end it now" else if (state.connected) "Connected · continues while you use other apps" else "Waiting for your connected earbuds")
            .setContentIntent(open()).setOngoing(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, if (session.phone.running.value) "Stop phone action" else "Disconnect", if (session.phone.running.value) command(STOP_PHONE, 3) else command(STOP_SESSION, 2))
            .build()
    }
    fun updateNotification(text: String? = null) {
        message = text
        if (text?.startsWith("OpenBuds requested") == true) {
            getSystemService(NotificationManager::class.java).notify(ID + 1, NotificationCompat.Builder(this, ACTIONS)
                .setSmallIcon(R.drawable.ic_launcher_monochrome).setContentTitle("OpenBuds requested by your earbuds")
                .setContentText("Tap to open OpenBuds").setContentIntent(open()).setAutoCancel(true).setSilent(true).build())
            message = null
        }
        syncForeground()
        getSystemService(NotificationManager::class.java).notify(ID, notification())
    }
    private fun syncForeground() {
        var types = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        if (Build.VERSION.SDK_INT >= 29 && session.phone.running.value && session.phone.action.value == PhoneAction.Ring)
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (Build.VERSION.SDK_INT >= 30 && session.phone.enabled.value && session.phone.action.value == PhoneAction.Torch &&
            checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            (session.visible || foregroundTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA != 0))
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (foregroundTypes != types || foregroundTypes == 0) {
            ServiceCompat.startForeground(this, ID, notification(), types)
            foregroundTypes = types
        }
    }
    override fun onTaskRemoved(rootIntent: Intent?) { session.stop(); stopSelf(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        unregisterReceiver(receiver)
        session.service = null; session.stop()
        getSystemService(NotificationManager::class.java).cancel(ID + 1)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val ID = 42
        const val CHANNEL = "earbud_connection"
        const val ACTIONS = "earbud_phone_actions"
        const val STOP_SESSION = "org.openbuds.STOP_CONNECTION"
        const val STOP_PHONE = "org.openbuds.STOP_PHONE_ACTION"
    }
}
