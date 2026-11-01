// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui

import org.openbuds.ui.components.DeviceReadings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.openbuds.core.Snapshot
import kotlinx.serialization.json.*

@OptIn(ExperimentalTestApi::class)
@RunWith(Parameterized::class)
class PlacementLayoutTest(private val density: Float) {
    @Test fun defaultTextKeepsAllThreeReadingsOnOneRowAtFractionalDensities() = runDesktopComposeUiTest(1080, 1920) {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(this@PlacementLayoutTest.density, 1f)) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF080A09)) {
                    Column(Modifier.padding(horizontal = 24.dp)) { DeviceReadings(Snapshot(Json.parseToJsonElement("""{"battery":[{"component":1,"percent":100},{"component":2,"percent":80},{"component":3,"percent":0}],"wear":[{"component":1,"connected":true,"in_case":false,"worn":true},{"component":2,"connected":true,"in_case":false,"worn":false},{"component":3,"connected":false}]}""").jsonObject), motion = false) }
                }
            }
        }
        onNodeWithTag("left-battery", useUnmergedTree = true).assertTextEquals("100%")
        onNodeWithTag("right-battery", useUnmergedTree = true).assertTextEquals("80%")
        onNodeWithTag("case-battery", useUnmergedTree = true).assertTextEquals("—")
        val labels = listOf("left", "right", "case").map { tag ->
            val icon = onNodeWithTag("$tag-icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val battery = onNodeWithTag("$tag-battery", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val label = onNodeWithTag("$tag-label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val status = onNodeWithTag("$tag-status", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(icon.center.x, battery.center.x, .5f)
            assertEquals(icon.center.x, label.center.x, .5f)
            assertEquals(icon.center.x, status.center.x, .5f)
            icon
        }
        assertEquals(labels[0].center.y, labels[1].center.y, .5f)
        assertEquals(labels[0].center.y, labels[2].center.y, .5f)
    }
    companion object {
        @JvmStatic @Parameterized.Parameters(name = "density={0}")
        fun densities() = listOf(arrayOf(2.625f), arrayOf(2.7f), arrayOf(3f))
    }
}
