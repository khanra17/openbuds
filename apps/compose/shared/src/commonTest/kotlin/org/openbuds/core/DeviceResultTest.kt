// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.core

import kotlinx.serialization.json.Json
import kotlin.test.*

class DeviceResultTest {
    @Test fun fitReadingsAreNamedWithoutInventingAMissingEar() {
        val result = describeResult("run_fit_test", Json.parseToJsonElement("""[{"component":{"id":1},"outcome":"GoodFit","raw":{"ear":1,"result":1}}]"""))
        assertEquals("Ear tip fit test", result.title)
        assertTrue(result.text.contains("Left earbud: Good fit"))
        assertFalse(result.text.contains("Right earbud: Good fit"))
        assertTrue(result.text.contains("Missing components have no reported verdict"))
    }
    @Test fun unknownFitValuesRemainUnknownAndKeepRawEvidence() {
        val result = describeResult("run_fit_test", Json.parseToJsonElement("""[{"component":{"id":2},"outcome":{"Unknown":99},"raw":{"ear":2,"result":99}}]"""))
        assertTrue(result.text.contains("Right earbud: Result unavailable"))
        assertFalse(result.text.contains("99"))
        assertTrue(result.details!!.contains("99"))
        assertFalse(result.text.contains("Good fit"))
    }
    @Test fun emptyFitResponseDoesNotClaimSuccess() {
        assertTrue(describeResult("run_fit_test", Json.parseToJsonElement("[]")).text.contains("No fit verdicts were reported"))
    }
}
