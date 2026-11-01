// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.core

import kotlinx.serialization.json.*

/** Existing C SDK error categories, preserved by JNI rather than flattened to text. */
class DeviceException(val status: Int, val command: Int?, val deviceStatus: Int?, message: String) : Exception(message)
enum class IssueArea { General, Noise }
enum class WearRequirement { OneEar, BothEars }
data class UserIssue(
    val message: String,
    val area: IssueArea = IssueArea.General,
    val details: String? = null,
    val wearRequirement: WearRequirement? = null,
)

internal fun decodeDeviceResponse(response: String): JsonElement {
    val envelope = Json.parseToJsonElement(response) as? JsonObject
        ?: throw DeviceException(11, null, null, "Expected an SDK response object")
    envelope["error"]?.takeIf { it !is JsonNull }?.obj()?.let { error ->
        throw DeviceException(error["status"].int() ?: 254, error["command"].int()?.takeIf { it != 0 },
            error["device_status"].int(), error["message"].text().orEmpty())
    }
    return envelope["data"] ?: throw DeviceException(11, null, null, "SDK response has no data or error field")
}

internal fun wearIssue(requirement: WearRequirement, details: String? = null) = when (requirement) {
    WearRequirement.OneEar -> UserIssue("Put on at least one earbud to change noise control.", IssueArea.Noise, details, requirement)
    WearRequirement.BothEars -> UserIssue("Put on both earbuds before starting the ear tip fit test.", details = details, wearRequirement = requirement)
}

internal fun presentFailure(error: Throwable, operation: String?, profile: DeviceProfile? = null): UserIssue {
    val raw = generateSequence(error) { it.cause }.take(8).mapNotNull { it.message }.distinct().joinToString("\n")
    // Compatibility with native open/platform exceptions and older JNI error text.
    // Current request failures use DeviceException and never depend on this parser.
    val rejected = Regex("rejected command 0x([0-9a-f]+):?\\s*status\\s*(\\d+)", RegexOption.IGNORE_CASE).find(raw)
    val sdk = error as? DeviceException
    val command = sdk?.command ?: rejected?.groupValues?.get(1)?.toIntOrNull(16)
    val deviceStatus = sdk?.deviceStatus ?: rejected?.groupValues?.get(2)?.toIntOrNull()
    val status = sdk?.status ?: if (rejected != null) 6 else null
    val details = buildString {
        if (status != null) append("SDK category: $status\n")
        if (command != null) append("Command: 0x${command.toString(16).padStart(4, '0')}\n")
        if (deviceStatus != null) append("Device status: $deviceStatus\n")
        append(raw.ifBlank { "No additional details were provided." })
    }
    // Status 14 is contextual evidence, not a universal status-to-wear dictionary.
    if (status == 6 && deviceStatus != null && deviceStatus == profile?.noise?.wearStatus) {
        if (operation == "set_noise" && (command == null || command == profile.noise.noiseCommand)) return if (profile.noise.minimumWorn >= 2) UserIssue("Put on both earbuds to change noise control.", IssueArea.Noise, details, WearRequirement.BothEars)
            else wearIssue(WearRequirement.OneEar, details)
        if (operation == "run_fit_test" && (command == null || command == profile.noise.fitCommand)) return if (profile.fitMinimumWorn == 1) UserIssue("Put on an earbud before starting the fit test.", details = details, wearRequirement = WearRequirement.OneEar)
            else wearIssue(WearRequirement.BothEars, details)
    }
    val area = if (operation in setOf("set_noise", "set_transparency")) IssueArea.Noise else IssueArea.General
    val text = when {
        raw.contains("permission", true) || raw.contains("Nearby devices", true) ->
            "Allow Nearby devices in OpenBuds app settings, then connect again."
        raw.contains("Turn on Bluetooth", true) || raw.contains("Bluetooth is off", true) ->
            "Turn on Bluetooth in system settings, then connect your earbuds."
        raw.contains("no Bluetooth adapter", true) -> "This device has no Bluetooth adapter available."
        status in setOf(3, 10) -> "Reconnect your earbuds to continue."
        status == 2 -> "Choose a device supported by the installed model drivers."
        status == 7 -> "Your earbuds doesn't report support for this control."
        status == 8 -> "Another request is still in progress. Wait a moment and try again."
        status == 9 -> "Stop requested. Refresh before applying another change."
        status == 4 || raw.contains("timed out", true) || raw.contains("timeout", true) ->
            if (operation.isWriteOperation()) "The earbuds didn't confirm the change. It may already have been saved; refresh before trying again."
            else "Your earbuds didn't reply. Keep it nearby and refresh, or reconnect if needed."
        status == 6 -> "Your earbuds couldn't apply this setting in its current state. Check that the earbuds are connected and ready, then try again."
        status == 1 -> "This value couldn't be applied. Refresh the device settings before trying again."
        operation in setOf("connect", "paired") -> "Couldn't connect to your earbuds. Check Bluetooth, keep the earbuds nearby, and try again."
        status == 5 -> "The control connection was interrupted. Reconnect your earbuds to continue."
        status == 11 -> "The earbuds returned an unreadable reply. Refresh their settings or reconnect."
        else -> "OpenBuds couldn't complete this request. Refresh the device settings or reconnect, then try again."
    }
    return UserIssue(text, area, details)
}

private fun String?.isWriteOperation() = this?.startsWith("set_") == true || this in setOf("multipoint_action", "run_fit_test", "find_for")

internal fun Throwable.isExpectedEventWait(): Boolean =
    (this as? DeviceException)?.status in setOf(4, 9) ||
        message?.contains("timed out", true) == true || message?.contains("timeout", true) == true
