package com.ai.assistance.operit.core.config

import com.ai.assistance.operit.data.model.ConversationSummaryConfig
import com.ai.assistance.operit.data.model.SummarySectionOverride
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionalPromptsSummaryTest {
    @Test
    fun buildSummarySystemPrompt_withoutOverridesContainsNoSectionSpecificRules() {
        val prompt = FunctionalPrompts.buildSummarySystemPrompt(
            previousSummary = null,
            useEnglish = false
        )

        assertTrue(prompt.startsWith(FunctionalPrompts.SUMMARY_PROMPT.trimIndent()))
        assertTrue(prompt.contains("任务过程简要记录"))
        assertTrue(prompt.contains("已完成事项"))
        assertFalse(prompt.contains("只记录关键问题和根因"))
        assertFalse(prompt.contains("只记录最终交付结果"))
        assertTrue(prompt.contains("每个板块具体记录什么"))
    }

    @Test
    fun buildSummarySystemPrompt_disablingMiddleSectionDoesNotRemoveLaterSections() {
        val prompt = FunctionalPrompts.buildSummarySystemPrompt(
            previousSummary = null,
            useEnglish = false,
            summaryConfig = ConversationSummaryConfig(
                sectionOverrides = listOf(
                    SummarySectionOverride(
                        id = "core_task",
                        title = "工程状态",
                        instruction = "仅记录已验证的工程变更。"
                    ),
                    SummarySectionOverride(id = "interaction", enabled = false)
                )
            )
        )

        assertTrue(prompt.contains("【工程状态】"))
        assertTrue(prompt.contains("仅记录已验证的工程变更。"))
        assertFalse(prompt.contains("【任务过程简要记录】"))
        assertTrue(prompt.contains("【已完成事项】"))
        assertTrue(prompt.contains("【下一步计划任务】"))
        assertTrue(prompt.contains("只输出已启用的板块"))
    }

    @Test
    fun buildSummarySystemPrompt_unknownOverrideKeepsLegacyTemplate() {
        val prompt = FunctionalPrompts.buildSummarySystemPrompt(
            previousSummary = null,
            useEnglish = true,
            summaryConfig = ConversationSummaryConfig(
                sectionOverrides = listOf(SummarySectionOverride(id = "unknown"))
            )
        )

        assertTrue(prompt.startsWith(FunctionalPrompts.SUMMARY_PROMPT_EN.trimIndent()))
        assertTrue(prompt.contains("Brief Task Process"))
        assertTrue(prompt.contains("Completed Items"))
    }

    @Test
    fun buildSummarySectionOverrides_onlyPersistsChangedFields() {
        val sections = FunctionalPrompts.resolveSummarySections(emptyList(), useEnglish = false)
            .map { section ->
                if (section.id == "core_task") section.copy(title = "工程状态") else section
            }

        assertEquals(
            listOf(SummarySectionOverride(id = "core_task", title = "工程状态")),
            FunctionalPrompts.buildSummarySectionOverrides(sections, useEnglish = false)
        )
    }

    @Test
    fun buildSummarySystemPrompt_customInstructionsAreAuthoritativeAndSectionsStayDistinct() {
        val prompt = FunctionalPrompts.buildSummarySystemPrompt(
            previousSummary = null,
            useEnglish = false,
            summaryConfig = ConversationSummaryConfig(
                sectionOverrides = listOf(
                    SummarySectionOverride(
                        id = "interaction",
                        instruction = "只记录关键问题和根因，不记录修复成果。"
                    ),
                    SummarySectionOverride(
                        id = "progress",
                        instruction = "只记录最终交付结果，不记录排查过程。"
                    )
                )
            )
        )

        assertTrue(prompt.contains("只记录关键问题和根因，不记录修复成果。"))
        assertTrue(prompt.contains("只记录最终交付结果，不记录排查过程。"))
        assertTrue(prompt.contains("每个板块的具体内容要求只以对应的设置说明为准"))
        assertFalse(prompt.contains("模型执行过程出现的错误"))
        assertFalse(prompt.contains("模型已完成的项目"))
    }
}
