// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Host effects are finite, coalesced, and stopped on failure as well as normal completion. */
class BoundedAction(private val scope: CoroutineScope, private val durationMillis: Long = 15_000,
    private val begin: () -> Boolean, private val end: (Boolean) -> Unit, private val failed: () -> Unit) {
    private val active = MutableStateFlow(false)
    val running = active.asStateFlow()
    private var deadline: Job? = null
    fun start() {
        if (active.value) return
        try {
            if (!begin()) return
            active.value = true
            deadline = scope.launch { delay(durationMillis); stop() }
        } catch (_: Exception) { stop(); failed() }
    }
    fun stop() {
        deadline?.cancel(); deadline = null
        val wasRunning = active.value; active.value = false
        end(wasRunning)
    }
}
