// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.awt.ComposeWindow
import org.openbuds.core.NativeBridge
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

@Composable
internal fun WindowAppearance(window: ComposeWindow) {
    DisposableEffect(window) {
        window.background = java.awt.Color(8, 10, 9)
        fun style() {
            if (System.getProperty("os.name").startsWith("Windows")) {
                runCatching { NativeBridge.styleWindow(window.windowHandle) }
                    .onFailure { System.err.println("Native title-bar styling unavailable: ${it.message}") }
            }
        }
        val listener = object : WindowAdapter() {
            override fun windowOpened(event: WindowEvent) = style()
            override fun windowActivated(event: WindowEvent) = style()
        }
        window.addWindowListener(listener)
        if (window.isDisplayable) style()
        onDispose { window.removeWindowListener(listener) }
    }
}
