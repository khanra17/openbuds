// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.core
import kotlin.test.*
import kotlinx.serialization.json.*

class DeviceProfileTest {
    @Test fun anotherModelHasOnlyItsDeclaredControls() {
        val other=DeviceProfile(Json.parseToJsonElement("""{"id":"other.driver","name":"Other buds","name_aliases":["Other buds"],"eq":{"frequencies":[100,500,2500,12000],"min":-3,"max":3,"presets":[{"id":5,"name":"Reference"}]},"features":[{"key":"extra","id":71,"wire_name":"OtherProcessing","name":"Extra processing","group":"sound"}],"tools":{"fit_test":false}}""").obj())
        assertNull(other.noise);assertNull(other.feature("wind"));assertNull(other.feature("phone_action"))
        assertEquals(71,other.feature("extra")!!.id)
        assertEquals(listOf(100,500,2500,12000),other.eq!!.frequencies)
        assertFalse(other.hasTool("fit_test"));assertFalse(other.matches(PairedDevice("realme Buds Air7","00")))
        assertTrue(other.matches(PairedDevice("Other buds","00")))
    }
    @Test fun aMissingCapabilityDoesNotInheritTheCurrentModelsFeatureSet() {
        val empty=DeviceProfile(Json.parseToJsonElement("""{"id":"minimal","name":"Minimal"}""").obj())
        assertTrue(empty.features.isEmpty());assertNull(empty.eq);assertNull(empty.noise);assertTrue(empty.gestureRows.isEmpty())
        assertFalse(empty.hasTool("find_earbuds"))
    }
}
