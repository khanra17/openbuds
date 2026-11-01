// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.core

import kotlin.test.*
import kotlinx.serialization.json.*

class EqualizerTest {
    @Test fun profileUsesItsOwnReportedFrequenciesAndGains() {
        val frequencies = TestProfile.eq!!.frequencies
        val profile = EqProfile(7,"暖かい",true,-6,6,listOf(3,2,1,0,-1,-2), frequencies)
        val raw = buildJsonObject { put("custom_eq",JsonArray(listOf(profile.json()))) }
        assertEquals(profile,Snapshot(raw).profiles.single())
        assertEquals(frequencies, profile.json()["bands"].arr().map { it.obj()["frequency"].int() })
    }
    @Test fun anotherModelCanUseAnotherBandLayoutWithoutChangingTheParser() {
        val frequencies = listOf(100,500,2500,12000)
        val profile = EqProfile(1,"Four bands",true,-3,3,listOf(1,2,0,-1), frequencies)
        assertEquals(profile, Snapshot(buildJsonObject { put("custom_eq",JsonArray(listOf(profile.json()))) }).profiles.single())
    }
    @Test fun missingBandValuesAreNotInventedAsNeutral() {
        assertTrue(Snapshot(Json.parseToJsonElement("""{"custom_eq":[{"id":1,"bands":[{"frequency":100}]}]}""").obj()).profiles.isEmpty())
    }
    @Test fun reservedGesturesArePreservedButNotExposedByTheModelPolicy() {
        val raw = Json.parseToJsonElement("""{"touch":[{"ear":1,"button":1,"gesture":1,"function":1},{"ear":1,"button":1,"gesture":2,"function":1},{"ear":2,"button":1,"gesture":6,"function":8}]}""").obj()
        val bindings = Snapshot(raw).touch
        assertEquals(3, bindings.size)
        assertEquals(listOf(2), bindings.filter(TestProfile::visibleGesture).map { it.gesture })
    }
    @Test fun caseBatteryCanBeRelayedByAnInCaseDisconnectedEarbud() {
        val s = Snapshot(Json.parseToJsonElement("""{"battery":[{"component":3,"percent":80}],"wear":[{"component":1,"connected":false,"in_case":true,"worn":false}]}""").obj())
        assertEquals(80, s.caseBattery)
        assertEquals("Via earbud", s.caseLinkStatus)
        assertEquals("In case", s.earStatus.first())
    }
    @Test fun unknownOfflineAndContradictoryReadingsAreDistinct() {
        assertEquals(listOf("Not reported", "Not reported"), Snapshot().earStatus)
        val s = Snapshot(Json.parseToJsonElement("""{"wear":[{"component":1,"connected":true,"in_case":true,"worn":true},{"component":2,"connected":false}]}""").obj())
        assertEquals(listOf("Status unclear", "Disconnected"), s.earStatus)
        assertNull(s.caseBattery)
    }
    @Test fun invalidBatteryDoesNotLookLikeARealPercentage() {
        val s = Snapshot(Json.parseToJsonElement("""{"battery":[{"component":1,"percent":101},{"component":2,"percent":-1},{"component":3,"percent":100}]}""").obj())
        assertEquals(listOf(null,null,100),s.batteries)
    }
    @Test fun absentAndZeroReadingsRemainDifferent() {
        assertEquals(listOf(null,null,null), Snapshot().batteries)
        val s = Snapshot(Json.parseToJsonElement("""{"battery":[{"component":1,"percent":0}]}""").obj())
        assertEquals(listOf(0,null,null),s.batteries)
    }
}
