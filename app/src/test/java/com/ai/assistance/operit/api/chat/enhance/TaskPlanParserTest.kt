package com.ai.assistance.operit.api.chat.enhance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TaskPlanParserTest {
    @Test
    fun createsStableParallelBatches() {
        val plan = TaskPlanParser.parse(
            """{"tasks":[
              {"task_id":"a","tool_name":"list_files","parameters":{"path":"/"},"depends_on":[]},
              {"task_id":"b","tool_name":"read_file","parameters":{},"depends_on":[]},
              {"task_id":"c","tool_name":"read_file","parameters":{},"depends_on":["a","b"]},
              {"task_id":"d","tool_name":"file_info","parameters":{},"depends_on":["a"]}
            ]}"""
        )
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")),
            plan.executionBatches.map { batch -> batch.map { it.taskId } })
    }

    @Test
    fun rejectsDuplicateIdsAndUnknownDependencies() {
        assertFailsWith<IllegalArgumentException> {
            TaskPlanParser.parse("""{"tasks":[{"task_id":"a","tool_name":"x"},{"task_id":"a","tool_name":"y"}]}""")
        }
        assertFailsWith<IllegalArgumentException> {
            TaskPlanParser.parse("""{"tasks":[{"task_id":"a","tool_name":"x","depends_on":["missing"]}]}""")
        }
    }

    @Test
    fun rejectsSelfDependencyAndCycles() {
        assertFailsWith<IllegalArgumentException> {
            TaskPlanParser.parse("""{"tasks":[{"task_id":"a","tool_name":"x","depends_on":["a"]}]}""")
        }
        assertFailsWith<IllegalArgumentException> {
            TaskPlanParser.parse("""{"tasks":[
              {"task_id":"a","tool_name":"x","depends_on":["b"]},
              {"task_id":"b","tool_name":"y","depends_on":["a"]}
            ]}""")
        }
    }

    @Test
    fun rejectsEmptyAndOversizedPlans() {
        assertFailsWith<IllegalArgumentException> { TaskPlanParser.parse("""{"tasks":[]}""") }
        val tasks = (0..TaskPlanParser.MAX_TASKS).joinToString(",") {
            """{"task_id":"t$it","tool_name":"noop"}"""
        }
        assertFailsWith<IllegalArgumentException> { TaskPlanParser.parse("""{"tasks":[$tasks]}""") }
    }
}
