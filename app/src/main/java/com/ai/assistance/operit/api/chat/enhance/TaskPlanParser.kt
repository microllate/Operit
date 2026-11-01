package com.ai.assistance.operit.api.chat.enhance

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class TaskPlanTask(
    val taskId: String,
    val toolName: String,
    val parameters: JsonObject,
    val dependsOn: List<String>
)

data class TaskPlan(
    val tasks: List<TaskPlanTask>,
    val executionBatches: List<List<TaskPlanTask>>
)

object TaskPlanParser {
    const val MAX_TASKS = 64

    private fun collectReferences(element: kotlinx.serialization.json.JsonElement): List<String> {
        return when (element) {
            is kotlinx.serialization.json.JsonObject -> {
                val direct = (element["\$ref"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }?.content?.let(::listOf).orEmpty()
                direct + element.values.flatMap(::collectReferences)
            }
            is kotlinx.serialization.json.JsonArray -> element.flatMap(::collectReferences)
            else -> emptyList()
        }
    }

    fun parse(rawJson: String): TaskPlan {
        val root = try {
            Json.parseToJsonElement(rawJson).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("Task plan must be a valid JSON object", e)
        }
        val elements = try {
            root["tasks"]?.jsonArray
                ?: throw IllegalArgumentException("Task plan is missing tasks array")
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalArgumentException("Task plan tasks must be an array", e)
        }
        require(elements.isNotEmpty()) { "Task plan cannot be empty" }
        require(elements.size <= MAX_TASKS) { "Task plan exceeds the maximum of $MAX_TASKS tasks" }

        val tasks = elements.mapIndexed { index, element ->
            val obj = try { element.jsonObject } catch (e: Exception) {
                throw IllegalArgumentException("Task at index $index must be an object", e)
            }
            val id = obj["task_id"]?.jsonPrimitive?.let { if (it.isString) it.content.trim() else "" }.orEmpty()
            val tool = obj["tool_name"]?.jsonPrimitive?.let { if (it.isString) it.content.trim() else "" }.orEmpty()
            require(id.isNotEmpty()) { "Task at index $index has an empty task_id" }
            require(tool.isNotEmpty()) { "Task '$id' has an empty tool_name" }
            val params = try { obj["parameters"]?.jsonObject ?: JsonObject(emptyMap()) } catch (e: Exception) {
                throw IllegalArgumentException("Task '$id' parameters must be an object", e)
            }
            val deps = try {
                obj["depends_on"]?.jsonArray?.map {
                    it.jsonPrimitive.let { value ->
                        require(value.isString) { "Task '$id' dependencies must be strings" }
                        value.content.trim()
                    }
                } ?: emptyList()
            } catch (e: IllegalArgumentException) { throw e } catch (e: Exception) {
                throw IllegalArgumentException("Task '$id' depends_on must be an array", e)
            }
            require(deps.distinct().size == deps.size) { "Task '$id' contains duplicate dependencies" }
            TaskPlanTask(id, tool, params, (deps + collectReferences(params)).distinct())
        }

        val byId = tasks.associateBy { it.taskId }
        require(byId.size == tasks.size) { "Task plan contains duplicate task_id values" }
        tasks.forEach { task ->
            task.dependsOn.forEach { dependency ->
                require(dependency != task.taskId) { "Task '${task.taskId}' cannot depend on itself" }
                require(byId.containsKey(dependency)) {
                    "Task '${task.taskId}' depends on unknown task '$dependency'"
                }
            }
        }

        val completed = mutableSetOf<String>()
        val batches = mutableListOf<List<TaskPlanTask>>()
        while (completed.size < tasks.size) {
            val ready = tasks.filter { it.taskId !in completed && it.dependsOn.all(completed::contains) }
            require(ready.isNotEmpty()) { "Task plan contains a dependency cycle" }
            batches += ready
            completed += ready.map { it.taskId }
        }
        return TaskPlan(tasks, batches)
    }
}
