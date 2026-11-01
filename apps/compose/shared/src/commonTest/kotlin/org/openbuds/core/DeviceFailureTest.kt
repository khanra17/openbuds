// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.core

import kotlinx.serialization.json.JsonNull
import kotlin.test.*

class DeviceFailureTest {
    @Test fun jniPreservesTheExistingSdkErrorFields() {
        val error = assertFailsWith<DeviceException> {
            decodeDeviceResponse("""{"error":{"status":6,"command":1028,"device_status":14,"message":"earbuds rejected command 0x0404: status 14"}}""")
        }
        assertEquals(6, error.status)
        assertEquals(0x0404, error.command)
        assertEquals(14, error.deviceStatus)
        val issue = presentFailure(error, "set_noise", TestProfile)
        assertEquals("Put on at least one earbud to change noise control.", issue.message)
        assertEquals(IssueArea.Noise, issue.area)
        assertTrue(issue.details!!.contains("0x0404"))
    }
    @Test fun theExactPreviouslyExposedErrorHasAPlainLanguageRecovery() {
        val issue = presentFailure(IllegalStateException("Rust error: earbuds rejected command 0x0404: status 14"), "set_noise", TestProfile)
        assertEquals(WearRequirement.OneEar, issue.wearRequirement)
        assertFalse(issue.message.contains("Rust"))
        assertFalse(issue.message.contains("14"))
    }
    @Test fun statusFourteenIsInterpretedInContextNotGlobally() {
        val error = DeviceException(6, 0x0405, 14, "device rejected request")
        assertEquals(WearRequirement.BothEars, presentFailure(error, "run_fit_test", TestProfile).wearRequirement)
        assertNull(presentFailure(error, "set_custom_eq").wearRequirement)
        assertNull(presentFailure(DeviceException(6, 0x0404, 14, "cycle rejected"), "set_noise_cycle").wearRequirement)
        assertNull(presentFailure(DeviceException(6, 0x010c, 14, "readback rejected"), "set_noise").wearRequirement)
    }
    @Test fun everySdkCategoryKeepsTechnicalTextOutOfTheNormalMessage() {
        for (status in listOf(1,2,3,4,5,6,7,8,9,10,11,254,255)) {
            val issue = presentFailure(DeviceException(status, 0x0410, null, "Rust error: private SDK diagnostic"), "set_feature")
            assertFalse(issue.message.contains("Rust"), "category $status")
            assertFalse(issue.message.contains("0x"), "category $status")
            assertTrue(issue.details!!.contains("private SDK diagnostic"))
        }
    }
    @Test fun timeoutDoesNotClaimTheWriteFailedOrReplayIt() {
        val issue = presentFailure(DeviceException(4, null, null, "request timed out"), "set_custom_eq")
        assertTrue(issue.message.contains("may already have been saved"))
        assertTrue(issue.message.contains("refresh before"))
    }
    @Test fun platformPermissionFailuresAlsoHaveAUsefulRecovery() {
        val issue = presentFailure(IllegalStateException("java.lang.SecurityException: BLUETOOTH_CONNECT permission denied"), "connect")
        assertTrue(issue.message.startsWith("Allow Nearby devices"))
        assertFalse(issue.message.contains("java.lang"))
    }
    @Test fun unknownErrorsAreAvailableOnlyAsDetails() {
        val issue = presentFailure(IllegalArgumentException("vendor packet fragment 0xbeef"), "snapshot")
        assertFalse(issue.message.contains("0xbeef"))
        assertTrue(issue.details!!.contains("0xbeef"))
    }
    @Test fun nullSuccessIsValidButMissingEnvelopeDataIsNot() {
        assertEquals(JsonNull, decodeDeviceResponse("""{"data":null}"""))
        assertEquals(11, assertFailsWith<DeviceException> { decodeDeviceResponse("{}") }.status)
    }
}
