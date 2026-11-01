// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui

import org.openbuds.ui.components.DeviceReadings
import androidx.compose.ui.test.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.graphics.asSkiaBitmap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.serialization.json.*
import org.jetbrains.skia.Image
import org.jetbrains.skia.EncodedImageFormat
import org.junit.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.openbuds.core.*
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class ScreenTest(private val width: Int, private val height: Int) {
    private val controller = DeviceController(object : DeviceLink {
        override fun profiles() = listOf(TestProfile)
        override suspend fun paired() = emptyList<PairedDevice>()
        override suspend fun connect(address: String) { error("Tests must never use Bluetooth") }
        override suspend fun call(request: JsonObject): JsonElement { error("Tests must never use Bluetooth") }
        override fun cancel() {}
        override suspend fun close() {}
    })
    private val navigation = Navigation()
    private var dialogController: DeviceController? = null
    @After fun close() { runBlocking { controller.close(); dialogController?.close() } }
    private fun DesktopComposeUiTest.screenshot(page: String) {
        val bitmap = onAllNodes(isRoot()).onLast().captureToImage().asSkiaBitmap()
        try { Image.makeFromBitmap(bitmap).use { image -> image.encodeToData(EncodedImageFormat.PNG)?.use { png ->
            File("build/reports/ui/$width-$height-$page.png").apply { parentFile.mkdirs(); writeBytes(png.bytes) }
        } } } finally { bitmap.close() }
    }
    @Test fun pagesAreUsableAndEqDraftSurvivesNavigation() = runDesktopComposeUiTest(width, height) {
        var savedReducedMotion = false
        setContent { OpenBudsApp(controller, navigation, onReducedMotionChange = { savedReducedMotion = it }) }
        onNodeWithText("CONNECT EARBUDS").performScrollTo().assertIsDisplayed()
        screenshot("home")
        runOnIdle { navigation.page = Page.Sound }
        onNode(hasSetTextAction()).assertDoesNotExist() // The custom editor is not the default view.
        onNodeWithText("Original sound").performScrollTo().assertExists()
        screenshot("eq")
        onNodeWithText("Create EQ").performScrollTo().performClick()
        onNodeWithContentDescription("Equalizer. Drag a frequency handle. Arrow keys select and adjust bands.").performScrollTo().assertExists()
        onNode(hasSetTextAction()).performScrollTo().performTextReplacement("Warm")
        screenshot("fine-eq")
        onNodeWithText("Dynamic audio").performScrollTo().assertExists()
        onNodeWithText("Dynamic sound strength").assertDoesNotExist()
        onNodeWithText("Game mode").performScrollTo().assertExists()
        onNodeWithText("Volume enhancer").performScrollTo().assertExists()
        onNodeWithText("High-definition sound · LHDC 5.0").performScrollTo().assertExists()
        runOnIdle { navigation.page = Page.Device }
        onNodeWithText("Dual-device connection").performScrollTo().assertExists()
        onNodeWithText("Find earbuds").assertDoesNotExist()
        onNodeWithText("Stop active test / sound").assertDoesNotExist()
        screenshot("device")
        onNodeWithText("Firmware version").performScrollTo().assertExists()
        onNodeWithText("Reduce motion").performScrollTo().performClick()
        runOnIdle { Assert.assertTrue(savedReducedMotion) }
        onNodeWithContentDescription("Reduce motion").assertIsOn()
        runOnIdle { navigation.page = Page.Sound }
        onNodeWithText("Warm").performScrollTo().assertExists()
        runOnIdle { navigation.page = Page.Controls }
        onNodeWithText("Auto play/pause").performScrollTo().assertExists()
        onNodeWithText("Auto-answer calls").performScrollTo().assertExists()
        onNodeWithText("Connect to read gesture settings.").performScrollTo().assertExists()
        screenshot("gestures")
    }
    @Test fun gestureAndSoundDialogsStayScrollableAndRequireConsent() = runDesktopComposeUiTest(width, height) {
        val reads = mutableListOf<String>()
        val readOnlyController = DeviceController(object : DeviceLink {
            override fun profiles() = listOf(TestProfile)
            override suspend fun paired() = emptyList<PairedDevice>()
            override suspend fun connect(address: String) {}
            override suspend fun call(request: JsonObject): JsonElement {
                val op = request["op"].text()!!; reads += op
                return when (op) {
                    "initialize", "subscribe", "feature_ids" -> JsonNull
                    "snapshot" -> Json.parseToJsonElement("""{"raw":{"touch":[{"ear":1,"button":1,"gesture":1,"function":1},{"ear":1,"button":1,"gesture":3,"function":12}]}}""")
                    "next_event" -> awaitCancellation()
                    else -> error("UI review must never write to a device: $op")
                }
            }
            override fun cancel() {}
            override suspend fun close() {}
        })
        dialogController = readOnlyController
        setContent { OpenBudsApp(readOnlyController, navigation) }
        runOnIdle { readOnlyController.connect("00:11:22:33:44:55"); navigation.page = Page.Controls }
        waitUntil(timeoutMillis = 3000) { readOnlyController.state.value.connected && !readOnlyController.state.value.busy }
        onNodeWithText("Single tap").assertDoesNotExist()
        onNodeWithText("Triple tap").performScrollTo().performClick()
        onNodeWithText("None").performScrollTo().assertIsDisplayed()
        screenshot("gesture-dialog")
        onNodeWithText("Cancel").performClick()
        runOnIdle { navigation.page = Page.Device }
        onNodeWithText("Ear tip fit test").performScrollTo().performClick()
        onNodeWithText("Start fit test").assertIsNotEnabled()
        onNodeWithText("I'm wearing both earbuds and agree to the test sound.").performScrollTo().assertIsDisplayed()
        screenshot("fit-consent")
        onNodeWithText("Cancel").performClick()
        Assert.assertTrue(reads.all { it in listOf("initialize", "snapshot", "subscribe", "next_event", "feature_ids") })
    }
    @Test fun reportedPlacementChangesStayLabelledAtLargeTextSizes() = runDesktopComposeUiTest(width, height) {
        var snapshot by mutableStateOf(Snapshot())
        var motion by mutableStateOf(true)
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF080A09)) { Column { DeviceReadings(snapshot, motion) } }
            }
        }
        onAllNodesWithText("Not reported").assertCountEquals(2)
        runOnIdle { snapshot = Snapshot(Json.parseToJsonElement("""{"battery":[{"component":1,"percent":83},{"component":2,"percent":72},{"component":3,"percent":80}],"wear":[{"component":1,"connected":true,"worn":true,"in_case":false},{"component":2,"connected":false,"worn":false,"in_case":true}]}""").obj()) }
        onNodeWithText("In ear").assertExists()
        onNodeWithText("In case").assertExists()
        onNodeWithText("80%").assertExists()
        waitForIdle(); screenshot("status-large-text")
        runOnIdle { motion = false; snapshot = Snapshot(Json.parseToJsonElement("""{"wear":[{"component":1,"connected":true,"worn":false,"in_case":false},{"component":2,"connected":false}]}""").obj()) }
        onNodeWithText("Not in ear").assertExists()
        onNodeWithText("Disconnected").assertExists()
        onNodeWithText("Not available").assertExists()
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}x{1}")
        fun sizes() = listOf(arrayOf(320,640), arrayOf(400,400), arrayOf(400,500), arrayOf(400,1000),
            arrayOf(610,400), arrayOf(610,500), arrayOf(610,1000), arrayOf(900,400), arrayOf(900,500), arrayOf(900,1000), arrayOf(1280,800))
    }
}
