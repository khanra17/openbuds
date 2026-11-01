// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.android

import androidx.compose.runtime.*
import org.openbuds.core.*
import org.openbuds.ui.components.PhoneActionControls

@Composable fun PhoneActionSettings(session: ConnectionSession, state: AppState, controller: DeviceController,
    enable: (Boolean) -> Unit, choose: (PhoneAction) -> Unit) {
    val localEnabled by session.phone.enabled.collectAsState()
    val action by session.phone.action.collectAsState()
    val running by session.phone.running.collectAsState()
    val permissions by session.phone.permissionRevision.collectAsState()
    val allowed = remember(action, permissions) { session.phone.permissionsAllowed() }
    PhoneActionControls(state, localEnabled && allowed, action.ordinal, PhoneAction.entries.map { it.title to it.description },
        running, enable, { choose(PhoneAction.entries[it]) }, session.phone::stop,
        permissionNotice = if (allowed) null else if (action == PhoneAction.Ring) "Phone-speaker ringing needs Android 9 or newer. Choose Flashlight or Show OpenBuds instead." else if (action == PhoneAction.Torch) "Allow Camera permission to enable the flashlight action." else "Allow notifications to enable Show OpenBuds.")
}
