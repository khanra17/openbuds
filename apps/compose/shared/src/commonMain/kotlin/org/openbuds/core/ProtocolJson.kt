// SPDX-License-Identifier: GPL-3.0-or-later
package org.openbuds.core

import kotlinx.serialization.json.*

fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray)?.toList() ?: emptyList()
fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull
fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
fun request(op: String, block: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject { put("op", op); block() }
