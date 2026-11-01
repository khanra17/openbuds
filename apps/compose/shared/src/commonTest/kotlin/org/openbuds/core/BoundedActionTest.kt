// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.core
import kotlin.test.*
import kotlinx.coroutines.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BoundedActionTest {
    @Test fun duplicateStartsDoNotExtendTheDeadline() = runTest {
        var starts=0;var stops=0
        val action=BoundedAction(backgroundScope, begin={starts++;true},end={if(it)stops++},failed={error("unexpected")})
        action.start();runCurrent();advanceTimeBy(10000);action.start()
        advanceTimeBy(5001);runCurrent()
        assertEquals(1,starts);assertEquals(1,stops);assertFalse(action.running.value)
    }
    @Test fun explicitStopCancelsTheTimerAndReleasesTheEffect() = runTest {
        var ends=0
        val action=BoundedAction(backgroundScope,begin={true},end={if(it)ends++},failed={})
        action.start();runCurrent();action.stop();advanceTimeBy(20000);runCurrent()
        assertEquals(1,ends);assertFalse(action.running.value)
    }
    @Test fun failedStartsCleanUpPartialResources() = runTest {
        var released=false;var failures=0
        val action=BoundedAction(backgroundScope,begin={error("partial effect")},end={released=true},failed={failures++})
        action.start()
        assertTrue(released);assertEquals(1,failures);assertFalse(action.running.value)
    }
}
