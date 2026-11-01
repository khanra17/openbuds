// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Test
import org.junit.Assert.*
import org.openbuds.core.*
import org.openbuds.ui.screens.NoiseControls
import org.openbuds.ui.theme.OpenBudsColors
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalTestApi::class)
class NoiseControlsTest {
    private class TestLink : DeviceLink {
        override fun profiles() = listOf(TestProfile)
        val requests = CopyOnWriteArrayList<JsonObject>()
        var rejectNoise = false
        override suspend fun paired() = emptyList<PairedDevice>()
        override suspend fun connect(address: String) {}
        override suspend fun call(request: JsonObject): JsonElement {
            requests += request
            if (rejectNoise && request["op"].text() == "set_noise") throw DeviceException(6, 0x0404, 14, "earbuds rejected command 0x0404: status 14")
            return when (request["op"].text()) {
                "snapshot" -> Json.parseToJsonElement("""{"raw":{"noise":{"value":2},"features":[{"id":26,"enabled":false}]}}""")
                "features" -> Json.parseToJsonElement("""[{"raw":{"id":26,"enabled":true}}]""")
                "next_event" -> { delay(150); error("timeout") }
                else -> JsonNull
            }
        }
        override fun cancel() {}
        override suspend fun close() {}
    }
    @Test fun windControlAndWarningAreOnlyShownInTransparency() = runDesktopComposeUiTest(320, 640) {
        val link = TestLink()
        val controller = DeviceController(link)
        var mode by mutableStateOf<Int?>(null)
        try {
            setContent {
                MaterialTheme(colorScheme = OpenBudsColors) { Surface {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NoiseControls(AppState(connected = true, profile = TestProfile, snapshot = Snapshot(buildJsonObject {
                            put("noise", buildJsonObject { put("value", mode?.let(::JsonPrimitive) ?: JsonNull) })
                            put("features", Json.parseToJsonElement("""[{"id":26,"enabled":false}]"""))
                        })), controller)
                    }
                } }
            }
            listOf(null, 1, 2).forEach { value ->
                runOnIdle { mode = value }
                if (value == 2) {
                    onNodeWithText("Wind-noise reduction").performScrollTo().assertIsDisplayed()
                    onNodeWithText("When wind picks up", substring = true).assertExists()
                } else onNodeWithText("Wind-noise reduction").assertDoesNotExist()
            }
            assertTrue(link.requests.isEmpty())
        } finally { runBlocking { controller.close() } }
    }
    @Test fun knownNeitherWornExplainsDisabledNoiseChoicesBeforeAnyRequest() = runDesktopComposeUiTest(320, 640) {
        val link = TestLink()
        val controller = DeviceController(link)
        try {
            setContent {
                MaterialTheme(colorScheme = OpenBudsColors) { Surface {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        NoiseControls(AppState(connected = true, profile = TestProfile, snapshot = Snapshot(Json.parseToJsonElement("""{"noise":{"value":1},"wear":[{"component":1,"worn":false},{"component":2,"worn":false}]}""").obj())), controller)
                    }
                } }
            }
            onNodeWithText("Cancellation").performClick()
            onNodeWithText("Wear your earbuds").assertExists()
            onNodeWithText("Put on at least one earbud to change noise control.").assertExists()
            onNodeWithText("Got it").performClick()
            onNodeWithText("Off").assertIsSelected()
            assertTrue(link.requests.isEmpty())
        } finally { runBlocking { controller.close() } }
    }
    @Test fun normalWearRejectionUsesAReadablePopupWithoutReplaying() = runDesktopComposeUiTest(320, 640) {
        val link = TestLink().apply { rejectNoise = true }
        val controller = DeviceController(link)
        try {
            setContent {
                val state by controller.state.collectAsState()
                MaterialTheme(colorScheme = OpenBudsColors) { Surface {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NoiseControls(state, controller) }
                } }
            }
            controller.connect("00:11:22:33:44:55")
            waitUntil(timeoutMillis = 3000) { controller.state.value.connected && !controller.state.value.busy }
            onNodeWithText("Off").performClick()
            waitUntil(timeoutMillis = 3000) { controller.state.value.issue != null }
            onAllNodesWithText("Put on at least one earbud to change noise control.").onLast().assertIsDisplayed()
            onNodeWithText("0x0404", substring = true).assertDoesNotExist()
            onNodeWithText("Got it").performClick()
            onNodeWithText("0x0404", substring = true).assertDoesNotExist()
            assertEquals(1, link.requests.count { it["op"].text() == "set_noise" })
        } finally { runBlocking { controller.close() } }
    }
    @Test fun windToggleWritesOnlyItsFlagAndUsesActualReadback() = runDesktopComposeUiTest(320, 640) {
        val link = TestLink()
        val controller = DeviceController(link)
        try {
            setContent {
                val state by controller.state.collectAsState()
                MaterialTheme(colorScheme = OpenBudsColors) { Surface {
                    Column(Modifier.verticalScroll(rememberScrollState())) { NoiseControls(state, controller) }
                } }
            }
            controller.connect("00:11:22:33:44:55")
            waitUntil(timeoutMillis = 3000) { controller.state.value.connected && !controller.state.value.busy }
            onNodeWithText("Wind-noise reduction").performScrollTo().performClick()
            waitUntil(timeoutMillis = 3000) { controller.state.value.snapshot.feature(26) == true && !controller.state.value.busy }
            val writes = link.requests.filter { it["op"].text()?.startsWith("set_") == true }
            assertEquals(1, writes.size)
            assertEquals("set_feature", writes.single()["op"].text())
            assertEquals("WindNoiseReduction", writes.single()["feature"].text())
            assertEquals(true, writes.single()["enabled"].bool())
            val bitmap = onNode(isRoot()).captureToImage().asSkiaBitmap()
            try {
                Image.makeFromBitmap(bitmap).use { image ->
                    image.encodeToData(EncodedImageFormat.PNG)?.use { png ->
                        File("build/reports/ui/320-640-transparency.png").apply { parentFile.mkdirs(); writeBytes(png.bytes) }
                    }
                }
            } finally { bitmap.close() }
        } finally { runBlocking { controller.close() } }
    }
}
