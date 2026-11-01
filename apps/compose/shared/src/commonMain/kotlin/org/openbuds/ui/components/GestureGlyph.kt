// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import org.openbuds.core.*
import org.openbuds.ui.theme.Paper
import org.openbuds.ui.theme.Muted

/** A pointing finger plus tap-count dots or a hold ring; no simulated touch event. */
@Composable internal fun GestureGlyph(gesture: Int, profile: DeviceProfile) {
    val spec = profile.gestures["rows"].arr().firstOrNull { it.obj()["id"].int() == gesture }.obj()
    val taps = spec["count"].int() ?: 0
    val hold = spec["hold"].bool() == true
    Canvas(Modifier.size(44.dp).clearAndSetSemantics {}) {
        val u = size.width / 44
        val p = Path().apply {
            moveTo(15*u, 37*u); lineTo(9*u, 27*u); quadraticTo(8*u,24*u,11*u,24*u)
            lineTo(17*u,29*u); lineTo(17*u,14*u); cubicTo(17*u,9*u,23*u,9*u,23*u,14*u)
            lineTo(23*u,23*u); cubicTo(32*u,21*u,35*u,25*u,33*u,32*u)
            lineTo(30*u,38*u)
        }
        drawPath(p, Paper, style=Stroke(1.4f*u))
        if (hold) drawCircle(Muted,7*u,Offset(20*u,11*u),style=Stroke(1.2f*u))
        else repeat(taps) { index -> drawCircle(Muted,1.8f*u,Offset((20+(index-(taps-1)/2f)*7)*u,4*u)) }
    }
}
