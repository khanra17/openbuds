// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.openbuds.core.describeResult
import org.openbuds.ui.components.DeviceResultDialog

@OptIn(ExperimentalTestApi::class)
class DeviceResultDialogTest {
    @Test fun fitResultDoesNotDumpProtocolDataUnlessDetailsAreRequested() = runDesktopComposeUiTest(400, 400) {
        val result = describeResult("run_fit_test", Json.parseToJsonElement("""[{"component":{"id":2},"outcome":{"Unknown":99},"raw":{"ear":2,"result":99}}]"""))
        var visible by mutableStateOf(true)
        setContent { MaterialTheme { Surface { if (visible) DeviceResultDialog(result) { visible = false } } } }
        onNodeWithText("Ear tip fit test").assertExists()
        onNodeWithText("Right earbud: Result unavailable", substring = true).assertExists()
        onNodeWithText("99", substring = true).assertDoesNotExist()
        onNodeWithText("Details").performClick()
        onNodeWithText("Technical details").assertExists()
        onNodeWithText("99", substring = true).assertExists()
        onNodeWithText("Back").performClick()
        onNodeWithText("99", substring = true).assertDoesNotExist()
        onNodeWithText("Close").performClick()
        onNodeWithText("Ear tip fit test").assertDoesNotExist()
    }
}
