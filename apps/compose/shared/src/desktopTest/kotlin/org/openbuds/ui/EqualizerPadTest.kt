// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.junit.Test
import org.junit.Assert.*
import org.openbuds.ui.components.EqualizerPad

@OptIn(ExperimentalTestApi::class)
class EqualizerPadTest {
    @Test fun theGraphicSupportsKeyboardAndAccessibilityWithoutSixSliders() = runDesktopComposeUiTest(400,500) {
        var gains by mutableStateOf(listOf(0,0,0,0))
        val frequencies=listOf(100,500,2500,12000)
        setContent { MaterialTheme { Surface { EqualizerPad(gains,-3,3,frequencies){gains=it} } } }
        val chart=onNodeWithContentDescription("Equalizer. Drag a frequency handle. Arrow keys select and adjust bands.")
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).assertCountEquals(0)
        chart.performSemanticsAction(SemanticsActions.RequestFocus){it()}
        chart.performKeyInput { pressKey(Key.DirectionUp);pressKey(Key.DirectionRight);pressKey(Key.DirectionDown) }
        runOnIdle { assertEquals(listOf(1,-1,0,0),gains) }
        val actions=chart.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(8,actions.size)
        runOnIdle { repeat(10){actions.first{it.label=="Increase 500 Hz"}.action()} }
        runOnIdle { assertEquals(3,gains[1]) }
    }
}
