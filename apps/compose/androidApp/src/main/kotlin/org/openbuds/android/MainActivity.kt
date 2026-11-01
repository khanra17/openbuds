// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.android

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.graphics.Color
import android.bluetooth.*
import androidx.activity.SystemBarStyle
import androidx.core.net.toUri
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import org.openbuds.core.*
import org.openbuds.ui.*

class MainActivity : ComponentActivity() {
    private val session get() = (application as OpenBudsApplication).session
    private var permissionAsked = false
    private var registered = false
    private var enableAfterCamera = false
    private var cameraDeviceKey: String? = null
    private var notificationDeviceKey: String? = null
    private var enableAfterNotification = false
    private val bluetooth = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { session.activate(); notifications() }
        else session.controller.notice("Allow Nearby devices in OpenBuds app settings to connect automatically.")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        session.phone.permissionsChanged()
        if (notificationDeviceKey != null) {
            if (granted && notificationDeviceKey == session.phone.deviceKey) {
                session.phone.configure(PhoneAction.Open)
                if (enableAfterNotification) enablePhoneAction(true)
            } else session.controller.notice("Allow notifications to use Show OpenBuds for the current earbuds.")
        }
        notificationDeviceKey = null; enableAfterNotification = false
    }
    private val camera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        session.phone.permissionsChanged()
        if (granted && cameraDeviceKey == session.phone.deviceKey) {
            session.phone.configure(PhoneAction.Torch)
            if (enableAfterCamera) enablePhoneAction(true)
            session.service?.updateNotification()
        } else session.controller.notice("Camera permission is needed only for the flashlight action. No images are captured.")
        enableAfterCamera = false; cameraDeviceKey = null
    }
    private val connectionChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (session.service == null && hasBluetooth()) session.activate() }
    }
    private fun hasBluetooth() = Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun notifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("interface", MODE_PRIVATE).getBoolean("notification_asked", false)) {
            getSharedPreferences("interface", MODE_PRIVATE).edit { putBoolean("notification_asked", true) }
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    private fun paired() { if (hasBluetooth()) session.controller.paired() else bluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT) }
    private fun enablePhoneAction(enabled: Boolean) {
        if (enabled && session.phone.action.value == PhoneAction.Ring && Build.VERSION.SDK_INT < 28) {
            session.controller.notice("Phone-speaker ringing needs Android 9 or newer. Choose Flashlight or Show OpenBuds instead."); return
        }
        if (enabled && session.phone.action.value == PhoneAction.Torch && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            enableAfterCamera = true; cameraDeviceKey = session.phone.deviceKey; camera.launch(Manifest.permission.CAMERA); return
        }
        if (enabled && session.phone.action.value == PhoneAction.Open && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            enableAfterNotification = true; notificationDeviceKey = session.phone.deviceKey; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS); return
        }
        val spec = session.controller.state.value.profile?.feature("phone_action") ?: return
        session.phone.enable(enabled); session.service?.updateNotification()
        session.controller.feature(spec, enabled)
    }
    private fun choosePhoneAction(action: PhoneAction) {
        if (action == PhoneAction.Torch && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraDeviceKey = session.phone.deviceKey; camera.launch(Manifest.permission.CAMERA)
        } else if (action == PhoneAction.Open && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationDeviceKey = session.phone.deviceKey; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else { session.phone.configure(action); session.service?.updateNotification() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        permissionAsked = savedInstanceState?.getBoolean("nearby_asked") ?: false
        enableAfterCamera = savedInstanceState?.getBoolean("enable_after_camera") ?: false
        cameraDeviceKey = savedInstanceState?.getString("camera_device")
        notificationDeviceKey = savedInstanceState?.getString("notification_device")
        enableAfterNotification = savedInstanceState?.getBoolean("enable_after_notification") ?: false
        val preferences = getSharedPreferences("interface", MODE_PRIVATE)
        splash.setOnExitAnimationListener { provider ->
            if (preferences.getBoolean("reduce_motion", false)) provider.remove()
            else provider.view.animate().alpha(0f).setDuration(140).withEndAction { provider.remove() }.start()
        }
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.BLACK))
        setContent {
            val navigation = rememberNavigation()
            BackHandler(navigation.page != Page.Home) { navigation.back() }
            OpenBudsApp(session.controller, navigation, ::paired,
                initialReducedMotion = preferences.getBoolean("reduce_motion", false),
                onReducedMotionChange = { preferences.edit { putBoolean("reduce_motion", it) } },
                connectDevice = { address, model -> notifications(); session.connect(address, model) },
                phoneActions = { state, controller -> PhoneActionSettings(session, state, controller,
                    enable = ::enablePhoneAction, choose = ::choosePhoneAction) }) {
                if (!hasBluetooth()) startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                else startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        }
    }
    override fun onStart() {
        super.onStart(); session.visible = true; session.phone.permissionsChanged()
        ContextCompat.registerReceiver(this, connectionChanges, IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED); addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }, ContextCompat.RECEIVER_EXPORTED); registered = true
        if (hasBluetooth()) { session.activate(); notifications() }
        else if (!permissionAsked) { permissionAsked = true; bluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT) }
    }
    override fun onStop() {
        if (registered) { unregisterReceiver(connectionChanges); registered = false }
        if (!isChangingConfigurations) session.background()
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("nearby_asked", permissionAsked); outState.putBoolean("enable_after_camera", enableAfterCamera); outState.putString("camera_device", cameraDeviceKey); outState.putString("notification_device", notificationDeviceKey); outState.putBoolean("enable_after_notification", enableAfterNotification); super.onSaveInstanceState(outState) }
}
