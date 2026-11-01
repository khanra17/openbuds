// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

internal val Paper = Color(0xFFF4F2EC)
internal val Muted = Color(0xFF9A9C96)
internal val Accent = Color(0xFFE84A3C)
internal val OpenBudsColors = darkColorScheme(primary = Paper, onPrimary = Color.Black, secondary = Accent,
    background = Color(0xFF080A09), onBackground = Paper,
    surface = Color(0xFF080A09), surfaceVariant = Color(0xFF202321),
    onSurface = Paper, onSurfaceVariant = Muted, outline = Color(0xFF383D39), error = Accent,
    surfaceTint = Color.Transparent, surfaceContainerLowest = Color(0xFF080A09),
    surfaceContainerLow = Color(0xFF141715), surfaceContainer = Color(0xFF191C19),
    surfaceContainerHigh = Color(0xFF202420), surfaceContainerHighest = Color(0xFF292E29))
