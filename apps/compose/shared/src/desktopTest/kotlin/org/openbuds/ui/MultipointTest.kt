// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
import org.openbuds.core.*
import org.openbuds.ui.screens.DeviceScreen

@OptIn(ExperimentalTestApi::class)
class MultipointTest {
    @Test fun hostMenusOfferOnlyTheRelevantConnectionAction() = runDesktopComposeUiTest(400,800) {
        var calls=0
        val controller=DeviceController(object:DeviceLink{
            override fun profiles()=listOf(TestProfile)
            override suspend fun paired()=emptyList<PairedDevice>()
            override suspend fun connect(address:String){error("No Bluetooth")}
            override suspend fun call(request:JsonObject):JsonElement{calls++;error("No Bluetooth")}
            override fun cancel(){}
            override suspend fun close(){}
        })
        val state=AppState(connected=true,profile=TestProfile,snapshot=Snapshot(Json.parseToJsonElement("""{"multipoint":[{"name":"Phone","state":2,"address":"00:11:22:33:44:55"},{"name":"Laptop","state":0,"address":"00:11:22:33:44:66"}]}""").obj()))
        val confirmed=mutableListOf<JsonObject>()
        try {
            setContent { MaterialTheme { Surface { Column(Modifier.verticalScroll(rememberScrollState())) {
                DeviceScreen(state,controller,{},null,false,{},confirm={request,_->confirmed+=request})
            } } } }
            onNodeWithText("Phone").performScrollTo().performClick()
            onNodeWithText("Disconnect").assertExists()
            onNodeWithText("Connect").assertDoesNotExist()
            onNodeWithText("Disconnect").performClick()
            assertEquals(TestProfile.peerDisconnectAction,confirmed.single()["action"].int())
            onNodeWithText("Laptop").performScrollTo().performClick()
            onNodeWithText("Connect").assertExists()
            onNodeWithText("Disconnect").assertDoesNotExist()
            assertEquals(0,calls)
        } finally {runBlocking{controller.close()}}
    }
}
