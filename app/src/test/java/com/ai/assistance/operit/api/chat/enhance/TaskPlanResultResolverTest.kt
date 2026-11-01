package com.ai.assistance.operit.api.chat.enhance

import com.ai.assistance.operit.core.tools.FileContentData
import com.ai.assistance.operit.core.tools.StringResultData
import com.ai.assistance.operit.data.model.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TaskPlanResultResolverTest {
    @Test
    fun resolvesReadableResultAndNestedStructuredPath() {
        val results = mapOf(
            "listing" to ToolResult("list_files", true, StringResultData("/storage/test.txt")),
            "read" to ToolResult("read_file", true, FileContentData(
                path = "/storage/test.txt",
                content = "hello",
                size = 5
            ))
        )
        val params = buildJsonObject {
            put("path", buildJsonObject { put("\$ref", JsonPrimitive("listing")) })
            put("content", buildJsonObject {
                put("\$ref", JsonPrimitive("read"))
                put("path", JsonPrimitive("content"))
            })
        }
        val resolved = TaskPlanResultResolver.resolveParameters(params, results)
        assertEquals("/storage/test.txt", resolved["path"]?.let { (it as JsonPrimitive).content })
        assertEquals("hello", resolved["content"]?.let { (it as JsonPrimitive).content })
    }

    @Test
    fun resolvesReferencesInsideArraysAndObjects() {
        val results = mapOf("a" to ToolResult("x", true, StringResultData("value")))
        val params = JsonObject(mapOf(
            "items" to kotlinx.serialization.json.JsonArray(listOf(
                buildJsonObject { put("\$ref", JsonPrimitive("a")) }
            ))
        ))
        val resolved = TaskPlanResultResolver.resolveParameters(params, results)
        assertEquals("value", resolved["items"]!!.let {
            ((it as kotlinx.serialization.json.JsonArray).first() as JsonPrimitive).content
        })
    }

    @Test
    fun rejectsMissingAndFailedReferences() {
        assertFailsWith<IllegalArgumentException> {
            TaskPlanResultResolver.resolveParameters(
                buildJsonObject { put("path", buildJsonObject { put("\$ref", JsonPrimitive("missing")) }) },
                emptyMap()
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TaskPlanResultResolver.resolveParameters(
                buildJsonObject { put("path", buildJsonObject { put("\$ref", JsonPrimitive("bad")) }) },
                mapOf("bad" to ToolResult("x", false, StringResultData(""), "failed"))
            )
        }
    }
}
