// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import javax.imageio.ImageIO
import java.util.prefs.Preferences
import androidx.compose.ui.window.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.openbuds.core.*
import org.openbuds.ui.*

fun main(args: Array<String>) = application {
    val devicePreferences = remember { Preferences.userRoot().node("org/openbuds/devices") }
    val controller = remember { DeviceController(JvmDeviceLink(), object : DevicePreferences {
        override fun get(key: String) = devicePreferences.get(key, null)
        override fun put(key: String, value: String) { devicePreferences.put(key, value) }
    }) }
    val navigation = rememberNavigation()
    val preferences = remember { runCatching { Preferences.userRoot().node("org/openbuds/interface") }.getOrNull() }
    val reducedMotion = remember { runCatching { preferences?.getBoolean("reduce_motion", false) ?: false }.getOrDefault(false) }
    val scope = rememberCoroutineScope()
    val icon = remember {
        val bitmap = requireNotNull(ClassLoader.getSystemResourceAsStream("openbuds.png")).use { ImageIO.read(it) }
        BitmapPainter(bitmap.toComposeImageBitmap())
    }
    Window(title = "OpenBuds", icon = icon,
        state = rememberWindowState(width = 1120.dp, height = 820.dp),
        onCloseRequest = { scope.launch { controller.close(); exitApplication() } },
        onPreviewKeyEvent = { event ->
            when {
                event.type != KeyEventType.KeyDown -> false
                event.isAltPressed && event.key == Key.DirectionLeft -> { navigation.back(); true }
                event.isCtrlPressed && event.key in listOf(Key.One, Key.Two, Key.Three, Key.Four) -> {
                    val page = Page.entries[listOf(Key.One, Key.Two, Key.Three, Key.Four).indexOf(event.key)]
                    if (page in availablePages(controller.state.value.profile)) { navigation.page = page; true } else false
                }
                event.key == Key.F5 && controller.state.value.connected && !controller.state.value.busy -> { controller.refresh(); true }
                else -> false // Escape is left to dialogs/menus; text-editing shortcuts are untouched.
            }
        }) {
        WindowAppearance(window)
        LaunchedEffect(Unit) {
            if ("--smoke-test" !in args) controller.autoConnect()
            if ("--smoke-test" in args) {
                // CI exercises the real renderer and native resources, with no Bluetooth.
                withFrameNanos { }
                withFrameNanos { }
                delay(250)
                controller.close()
                exitApplication()
            }
        }
        OpenBudsApp(controller, navigation, initialReducedMotion = reducedMotion,
            onReducedMotionChange = { value ->
                runCatching { preferences?.putBoolean("reduce_motion", value) }
                    .onFailure { controller.notice("Motion preference could not be saved; it will apply for this session.") }
            }, bluetoothSettings = {
            if (System.getProperty("os.name").startsWith("Windows")) ProcessBuilder("explorer.exe", "ms-settings:bluetooth").start()
            else if (System.getProperty("os.name").startsWith("Linux")) runCatching { ProcessBuilder("blueman-manager").start() }
        })
    }
}
