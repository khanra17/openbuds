// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.core

import kotlinx.serialization.json.*
import kotlin.test.*

class WearReadinessTest {
    private fun snapshot(left: Boolean, right: Boolean) = Snapshot(buildJsonObject {
        put("wear", buildJsonArray {
            listOf(left, right).forEachIndexed { index, worn -> add(buildJsonObject {
                put("component", index + 1); put("connected", true); put("in_case", false); put("worn", worn)
            }) }
        })
    })
    @Test fun noiseNeedsOneEarButFitNeedsBoth() {
        for (left in listOf(false, true)) for (right in listOf(false, true)) {
            val s = snapshot(left, right)
            assertEquals(if (left || right) WearReadiness.Ready else WearReadiness.NeedsWear, s.noiseWearReadiness)
            assertEquals(if (left && right) WearReadiness.Ready else WearReadiness.NeedsWear, s.fitWearReadiness)
        }
    }
    @Test fun missingSensorReadingsDoNotInventANeitherWornRestriction() {
        assertEquals(WearReadiness.Unknown, Snapshot().noiseWearReadiness)
        assertEquals(WearReadiness.Unknown, Snapshot().fitWearReadiness)
        val partial = Snapshot(Json.parseToJsonElement("""{"wear":[{"component":1,"worn":false}]}""").obj())
        assertEquals(WearReadiness.Unknown, partial.noiseWearReadiness)
        assertEquals(WearReadiness.NeedsWear, partial.fitWearReadiness)
    }
    @Test fun contradictoryReadingsAreNotProofOfWear() {
        val s = Snapshot(Json.parseToJsonElement("""{"wear":[{"component":1,"connected":true,"in_case":true,"worn":true}]}""").obj())
        assertEquals(WearReadiness.Unknown, s.noiseWearReadiness)
        assertEquals(WearReadiness.Unknown, s.fitWearReadiness)
    }
}
