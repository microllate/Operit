package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.data.model.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Resolves {"\$ref":"task-id"} parameters after their dependency has completed.
 *
 * A reference without path becomes the readable tool output. A reference with
 * path reads a field from ToolResultData.toJson(), e.g. {"\$ref":"scan","path":"entries.0.name"}.
 */
object TaskPlanResultResolver {
    fun resolveParameters(
        parameters: JsonObject,
        resultsByTaskId: Map<String, ToolResult>
    ): JsonObject {
        return JsonObject(parameters.mapValues { (_, value) ->
            resolveElement(value, resultsByTaskId)
        })
    }

    private fun resolveElement(
        element: JsonElement,
        resultsByTaskId: Map<String, ToolResult>
    ): JsonElement {
        if (element is JsonObject && element.containsKey("\$ref")) {
            val taskId = (element["\$ref"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw IllegalArgumentException("Task result reference must contain a string reference key")
            val result = resultsByTaskId[taskId]
                ?: throw IllegalArgumentException("Task result '$taskId' is unavailable")
            require(result.success) { "Task result '$taskId' is not successful: ${result.error.orEmpty()}" }
            val path = (element["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (path.isNullOrBlank()) return JsonPrimitive(result.result.toString())
            val root = runCatching { Json.parseToJsonElement(result.result.toJson()) }
                .getOrElse { JsonPrimitive(result.result.toString()) }
            return resolvePath(root, path)
        }
        return when (element) {
            is JsonObject -> JsonObject(element.mapValues { (_, value) ->
                resolveElement(value, resultsByTaskId)
            })
            is JsonArray -> JsonArray(element.map { resolveElement(it, resultsByTaskId) })
            else -> element
        }
    }

    private fun resolvePath(root: JsonElement, path: String): JsonElement {
        var current = root
        path.split('.').filter { it.isNotEmpty() }.forEach { segment ->
            current = when (current) {
                is JsonObject -> current[segment]
                    ?: throw IllegalArgumentException("Result path '$path' has no field '$segment'")
                is JsonArray -> segment.toIntOrNull()?.let { index ->
                    if (index in current.indices) current[index] else null
                } ?: throw IllegalArgumentException("Result path '$path' has invalid array index '$segment'")
                else -> throw IllegalArgumentException("Result path '$path' cannot access '$segment'")
            }
        }
        return current
    }
}
