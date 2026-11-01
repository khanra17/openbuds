// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.core
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class ControllerTest {
    private class FakeLink : DeviceLink {
        var models = listOf(TestProfile)
        override fun profiles() = models
        var connections = 0; var closes = 0; var requests = mutableListOf<String>(); var failConnect = false
        val responses = mutableMapOf<String, JsonElement>()
        val failures = mutableMapOf<String, Exception>()
        var pendingEvent: JsonElement? = null
        var pairedDevices = listOf(PairedDevice("Test buds","00:11:22:33:44:55",true))
        var connectDelay = 0L
        val commands = mutableListOf<JsonObject>()
        override suspend fun paired() = pairedDevices
        override suspend fun connect(address: String) { connections++; delay(connectDelay); if (failConnect) error("Connection failed") }
        override suspend fun call(request: JsonObject): JsonElement {
            val op=request["op"].text()!!; requests += op; commands += request
            failures[op]?.let { throw it }
            responses[op]?.let { return it }
            return when (op) {
                "snapshot" -> buildJsonObject { put("raw",buildJsonObject { put("battery", buildJsonArray { add(buildJsonObject { put("component",1);put("percent",70) }) }) }) }
                "next_event" -> { delay(250); pendingEvent?.let { pendingEvent = null; return it }; error("timeout") }
                else -> JsonNull
            }
        }
        override fun cancel() {}
        override suspend fun close() { closes++ }
    }
    @Test fun failedConnectionClearsBusyWithoutInventingState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val link=FakeLink().apply { failConnect=true }; val controller=DeviceController(link)
        try {
            controller.connect("00:11:22:33:44:55"); runCurrent()
            assertFalse(controller.state.value.busy); assertFalse(controller.state.value.connected)
            assertEquals(listOf(null,null,null),controller.state.value.snapshot.batteries)
            assertTrue(controller.state.value.message!!.startsWith("Couldn't connect to your earbuds"))
            assertTrue(controller.state.value.issue!!.details!!.contains("Connection failed"))
        } finally { controller.close(); Dispatchers.resetMain() }
    }
    @Test fun disconnectedActionsCannotOpenBluetoothOrInventReadings() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val link=FakeLink();val controller=DeviceController(link)
        try {
            controller.command(request("find_for") { put("duration_ms",3000);put("confirm_sound",true) }); runCurrent()
            controller.refresh(); runCurrent()
            assertEquals(0,link.connections); assertTrue(link.requests.isEmpty())
            assertFalse(controller.state.value.connected)
            assertNotNull(controller.state.value.message)
            controller.pause();runCurrent();controller.resume()
            assertEquals(listOf(null,null,null),controller.state.value.snapshot.batteries)
            assertTrue(controller.state.value.snapshot.raw.isEmpty())
        } finally { controller.close();Dispatchers.resetMain() }
    }
    @Test fun presetReadbackUsesItsIdentifierAndDoesNotInventMissingValues() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val link = FakeLink().apply {
            responses["eq_preset"] = Json.parseToJsonElement("""{"id":2,"label":"Vocal"}""")
            responses["custom_eq"] = JsonArray(emptyList())
        }
        val controller = DeviceController(link)
        try {
            controller.connect("00:11:22:33:44:55"); runCurrent()
            controller.command(request("set_eq_preset") { put("id", 2) }, "eq_preset", "eq_preset")
            advanceTimeBy(251); runCurrent()
            assertEquals(2, controller.state.value.snapshot.preset)
            assertTrue("custom_eq" in link.requests)
            link.responses["eq_preset"] = JsonObject(emptyMap())
            controller.command(request("set_eq_preset") { put("id", 3) }, "eq_preset", "eq_preset")
            advanceTimeBy(251); runCurrent()
            assertNull(controller.state.value.snapshot.preset)
        } finally { controller.close(); Dispatchers.resetMain() }
    }
    @Test fun knownNeitherWornBlocksNoiseAndFitWithoutSendingEitherCommand() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val link = FakeLink().apply {
            responses["snapshot"] = Json.parseToJsonElement("""{"raw":{"wear":[{"component":1,"worn":false},{"component":2,"worn":false}]}}""")
        }
        val controller = DeviceController(link)
        try {
            controller.connect("00:11:22:33:44:55"); runCurrent()
            controller.command(request("set_noise") { put("mode", "Transparency") })
            advanceTimeBy(251); runCurrent()
            assertEquals(WearRequirement.OneEar, controller.state.value.issue?.wearRequirement)
            controller.command(request("run_fit_test") { put("confirm_sound", true); put("timeout_ms", 15000) })
            advanceTimeBy(251); runCurrent()
            assertEquals(WearRequirement.BothEars, controller.state.value.issue?.wearRequirement)
            assertFalse("set_noise" in link.requests)
            assertFalse("run_fit_test" in link.requests)
        } finally { controller.close(); Dispatchers.resetMain() }
    }
    @Test fun actualRejectionIsReadableAndNewWearDataClearsItWithoutReplayingTheWrite() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val link = FakeLink().apply { failures["set_noise"] = DeviceException(6, 0x0404, 14, "earbuds rejected command 0x0404: status 14") }
        val controller = DeviceController(link)
        try {
            controller.connect("00:11:22:33:44:55"); runCurrent()
            controller.command(request("set_noise") { put("mode", "Transparency") })
            advanceTimeBy(251); runCurrent()
            assertEquals("Put on at least one earbud to change noise control.", controller.state.value.message)
            link.pendingEvent = Json.parseToJsonElement("""{"Wear":[{"raw":{"component":1,"connected":true,"in_case":false,"worn":true}}]}""")
            advanceTimeBy(251); runCurrent()
            assertNull(controller.state.value.message)
            assertNull(controller.state.value.issue)
            assertEquals(1, link.requests.count { it == "set_noise" })
        } finally { controller.close(); Dispatchers.resetMain() }
    }
    @Test fun startupConnectsOneSupportedDeviceButNeverBorrowsAProtocolForAnUnknownName() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val link=FakeLink();val controller=DeviceController(link)
        try {
            controller.autoConnect();runCurrent()
            assertTrue(controller.state.value.connected);assertEquals(1,link.connections)
            controller.endSession().join()
            controller.resume()
            link.pairedDevices=listOf(PairedDevice("Unknown headphones","AA",true))
            controller.autoConnect();runCurrent()
            assertFalse(controller.state.value.connected);assertEquals(1,link.connections)
        } finally {controller.close();Dispatchers.resetMain()}
    }
    @Test fun endingAConnectingTaskCannotPublishALateConnectedState() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val link=FakeLink().apply{connectDelay=500};val controller=DeviceController(link)
        try {
            controller.connect("00:11:22:33:44:55");runCurrent()
            controller.endSession();advanceTimeBy(501);runCurrent()
            assertFalse(controller.state.value.connected)
            assertFalse("initialize" in link.requests);assertFalse("subscribe" in link.requests)
        } finally {controller.close();Dispatchers.resetMain()}
    }
    @Test fun enteringTheCaseRefreshesItsBatteryWithoutAnySettingWrite() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val link=FakeLink().apply{responses["battery"]=Json.parseToJsonElement("""[{"raw":{"component":3,"percent":80}}]""")}
        val controller=DeviceController(link)
        try {
            controller.connect("00:11:22:33:44:55");runCurrent()
            link.pendingEvent=Json.parseToJsonElement("""{"Wear":[{"raw":{"component":1,"connected":false,"in_case":true,"worn":false}}]}""")
            advanceTimeBy(251);runCurrent()
            assertEquals(80,controller.state.value.snapshot.caseBattery)
            assertTrue("battery" in link.requests);assertFalse(link.requests.any{it.startsWith("set_")})
        } finally {controller.close();Dispatchers.resetMain()}
    }
    @Test fun rememberedAncIsDeviceScopedAndLoadedWhenReturningFromTransparency() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val values=mutableMapOf<String,String>()
        val prefs=object:DevicePreferences{override fun get(key:String)=values[key];override fun put(key:String,value:String){values[key]=value}}
        val link=FakeLink().apply{responses["snapshot"]=Json.parseToJsonElement("""{"raw":{"noise":{"value":8}}}""")}
        val controller=DeviceController(link,prefs)
        try {
            controller.connect("00:11:22:33:44:55");runCurrent()
            assertEquals(8,controller.state.value.lastAnc)
            controller.endSession().join();controller.resume()
            link.responses["snapshot"]=Json.parseToJsonElement("""{"raw":{"noise":{"value":2}}}""")
            controller.connect("00:11:22:33:44:55");runCurrent()
            assertEquals(8,controller.state.value.lastAnc)
            controller.setNoise(controller.state.value.lastAnc!!);advanceTimeBy(251);runCurrent()
            assertEquals("Maximum",link.commands.first{it["op"].text()=="set_noise"}["mode"].text())
        } finally {controller.close();Dispatchers.resetMain()}
    }
    @Test fun hostRequestsArePreferenceGatedAndHaveNoReplay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val link=FakeLink().apply{responses["snapshot"]=Json.parseToJsonElement("""{"raw":{"features":[{"id":38,"enabled":false}]}}""")}
        val controller=DeviceController(link);val received=mutableListOf<HostRequest>()
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){controller.hostRequests.collect{received+=it}}
            controller.connect("00:11:22:33:44:55");runCurrent()
            link.pendingEvent=Json.parseToJsonElement("""{"HostOperation":{"operation":{"id":5}}}""")
            advanceTimeBy(251);runCurrent();assertTrue(received.isEmpty())
            link.pendingEvent=Json.parseToJsonElement("""{"Features":[{"raw":{"id":38,"enabled":true}}]}""")
            advanceTimeBy(251);runCurrent()
            link.pendingEvent=Json.parseToJsonElement("""{"HostOperation":{"operation":{"id":5}}}""")
            advanceTimeBy(251);runCurrent();assertEquals(listOf(HostRequest.Start),received)
            assertTrue(controller.hostRequests.replayCache.isEmpty())
            link.pendingEvent=Json.parseToJsonElement("""{"HostOperation":{"operation":{"id":6}}}""")
            advanceTimeBy(251);runCurrent();assertEquals(listOf(HostRequest.Start,HostRequest.Stop),received)
        } finally {controller.close();Dispatchers.resetMain()}
    }
    @Test fun backgroundKeepsConnectionAndTaskEndClosesIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val link=FakeLink();val controller=DeviceController(link)
        try {
            controller.connect("00:11:22:33:44:55");runCurrent()
            assertTrue(controller.state.value.connected)
            controller.pause();runCurrent()
            assertTrue(controller.state.value.connected)
            controller.resume();runCurrent();assertEquals(1,link.connections)
            controller.endSession();runCurrent();assertFalse(controller.state.value.connected)
        } finally { controller.close();Dispatchers.resetMain() }
    }
}
