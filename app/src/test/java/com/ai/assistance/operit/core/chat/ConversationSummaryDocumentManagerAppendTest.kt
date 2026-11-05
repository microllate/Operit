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
}
