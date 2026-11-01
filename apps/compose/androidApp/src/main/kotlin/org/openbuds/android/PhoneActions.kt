// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.android

import android.content.Context
import androidx.core.content.edit
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.openbuds.core.BoundedAction

enum class PhoneAction(val title: String, val description: String) {
    Ring("Ring phone", "Ring the phone speaker for up to 15 seconds. Your volume and Do Not Disturb settings are respected."),
    Torch("Flashlight", "Turn on the flashlight for up to 15 seconds. Camera permission is used only for the torch; no images are captured."),
    Open("Show OpenBuds", "Show a notification that opens OpenBuds. Android restricts automatic app launches from the background.");
}

class PhoneActions(private val context: Context, private val scope: CoroutineScope,
    private val notify: (String?) -> Unit, private val isVisible: () -> Boolean) {
    private val preferences = context.getSharedPreferences("phone_actions", Context.MODE_PRIVATE)
    val enabled = MutableStateFlow(false)
    val action = MutableStateFlow(PhoneAction.Ring)
    val permissionRevision = MutableStateFlow(0)
    var deviceKey: String? = null
        private set
    fun bind(key: String?) {
        if (deviceKey == key) return
        stop(); deviceKey = key
        enabled.value = key != null && preferences.getBoolean("enabled:$key", false)
        action.value = runCatching { PhoneAction.valueOf(preferences.getString("action:$key", "Ring")!!) }.getOrDefault(PhoneAction.Ring)
    }
    fun permissionsChanged() { permissionRevision.value++ }
    fun permissionsAllowed(): Boolean = when (action.value) {
        PhoneAction.Ring -> android.os.Build.VERSION.SDK_INT >= 28
        PhoneAction.Torch -> androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        PhoneAction.Open -> android.os.Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    private var player: MediaPlayer? = null
    private var torch: String? = null
    private val wake by lazy { context.getSystemService(android.os.PowerManager::class.java)
        .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "OpenBuds:phoneAction").apply { setReferenceCounted(false) } }
    private val runner = BoundedAction(scope, begin = ::begin, end = ::release, failed = { notify("This phone action is unavailable. Check its permission and system settings.") })
    val running = runner.running
    fun configure(value: PhoneAction) { stop(); action.value = value; preferences.edit { putString("action:$deviceKey", value.name) } }
    fun enable(value: Boolean) { if (!value) stop(); enabled.value = value; preferences.edit { putBoolean("enabled:$deviceKey", value) } }
    fun start() { if (enabled.value && deviceKey != null && permissionsAllowed()) runner.start() }
    private fun begin(): Boolean {
            when (action.value) {
                PhoneAction.Ring -> {
                    if (android.os.Build.VERSION.SDK_INT < 28) throw IllegalStateException("Phone-speaker ringing needs Android 9 or newer")
                    val audio = context.getSystemService(AudioManager::class.java)
                    val speaker = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?: error("The phone speaker is unavailable")
                    val next = MediaPlayer()
                    player = next
                    next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    check(next.setPreferredDevice(speaker)) { "The phone speaker could not be selected" }
                    next.addOnRoutingChangedListener({ routing ->
                        if (player === routing && running.value) {
                            val routed = runCatching { routing.routedDevice }.getOrNull()
                            if (routed != null && routed.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                                stop(); notify("Android could not route the phone sound to its speaker.")
                            }
                        }
                    }, android.os.Handler(android.os.Looper.getMainLooper()))
                    next.setDataSource(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
                    next.setOnPreparedListener { ready -> if (player === ready && running.value) { ready.isLooping = true; ready.start() } }
                    next.setOnErrorListener { _, _, _ -> stop(); notify("Phone sound is unavailable. Check system volume and sound settings."); true }
                    next.prepareAsync()
                }
                PhoneAction.Torch -> {
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        throw SecurityException("Allow Camera permission for the flashlight action")
                    val camera = context.getSystemService(CameraManager::class.java)
                    val id = camera.cameraIdList.firstOrNull { camera.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
                        ?: error("This phone has no available flashlight")
                    camera.setTorchMode(id, true); torch = id
                }
                PhoneAction.Open -> {
                    if (isVisible()) context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    notify("OpenBuds requested by your earbuds — tap to open")
                    return false
                }
            }
            wake.acquire(16_000) // Keep the finite stop deadline running with the screen off; never held at idle.
            return true
    }
    fun stop() = runner.stop()
    private fun release(wasRunning: Boolean) {
        player?.let { runCatching { it.stop() }; it.release() }; player = null
        torch?.let { id -> runCatching { context.getSystemService(CameraManager::class.java).setTorchMode(id, false) } }; torch = null
        if (wake.isHeld) wake.release()
        if (wasRunning) notify(null)
    }
}
