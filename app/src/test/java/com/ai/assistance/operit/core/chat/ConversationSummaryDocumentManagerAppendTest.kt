package com.ai.assistance.operit.core.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationSummaryDocumentManagerAppendTest {
    @Test
    fun mergeAppendContent_preservesOldLinesAndAddsOnlyNewLines() {
        val old = "【用户目标】\n- 保留原目标"
        val incoming = "【用户目标】\n- 保留原目标\n- 新增目标"
        assertEquals("【用户目标】\n- 保留原目标\n\n- 新增目标", mergeAppendContent(old, incoming))
    }

    @Test
    fun mergeAppendContent_ignoresWhitespaceDifferencesWhenDeduplicating() {
        val old = "【执行错误】\n- 文件读取失败"
        val incoming = "【执行错误】\n-   文件读取失败  "
        assertEquals(old, mergeAppendContent(old, incoming))
    }

    @Test
    fun mergeAppendContent_deduplicatesLinesWithinIncomingContent() {
        val incoming = "【完成进度】\n- 已完成修复\n- 已完成修复"
        assertEquals("【完成进度】\n- 已完成修复", mergeAppendContent("", incoming))
    }

    @Test
    fun mergeAppendContent_keepsOldContentWhenIncomingIsBlank() {
        assertEquals("旧记忆", mergeAppendContent("旧记忆", "  \n"))
    }

    @Test
    fun mergeTimestampedMemorySections_appendsUnderExistingHeadingsWithoutDuplicatingThem() {
        val existing = """【用户目标】
2026-11-05 10:00:00
旧目标

【模型已完成的项目】
2026-11-05 10:00:00
旧成果
"""
        val incoming = """【用户目标】
新目标

【模型已完成的项目】
新成果
"""

        val actual = mergeTimestampedMemorySections(existing, incoming, "2026-11-05 11:00:00")

        assertEquals(1, Regex("【用户目标】").findAll(actual).count())
        assertEquals(1, Regex("【模型已完成的项目】").findAll(actual).count())
        assertEquals(true, actual.contains("2026-11-05 11:00:00\\n新目标"))
        assertEquals(true, actual.contains("2026-11-05 11:00:00\\n新成果"))
        assertEquals(true, actual.contains("旧目标"))
        assertEquals(true, actual.contains("旧成果"))
    }

    @Test
    fun mergeTimestampedMemorySections_doesNotAppendAnIdenticalSectionBodyTwice() {
        val existing = """【用户目标】
2026-11-05 10:00:00
保持旧目标
"""
        val incoming = """【用户目标】
保持旧目标
"""

        assertEquals(existing, mergeTimestampedMemorySections(existing, incoming, "2026-11-05 11:00:00"))
    }
}
