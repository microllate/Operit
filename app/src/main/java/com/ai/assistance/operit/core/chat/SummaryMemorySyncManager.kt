package com.ai.assistance.operit.core.chat

import android.content.Context
import com.ai.assistance.operit.data.preferences.preferencesManager
import com.ai.assistance.operit.data.repository.MemoryRepository
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

/**
 * Mirrors each completed summary into a workspace-scoped memory event.
 * The Markdown document remains the source of truth; sync failures never delete it.
 */
internal class SummaryMemorySyncManager(private val context: Context) {
    suspend fun sync(workspacePath: String, chatId: String, timestamp: String, summary: String) {
        val sections = parseSections(summary)
        if (sections.isEmpty()) return

        val workspaceName = workspacePath.trimEnd('/', '\\')
            .substringAfterLast('/').substringAfterLast('\\').ifBlank { "workspace" }
            .replace("/", "_").replace("\\", "_")
        val workspaceKey = digest(workspacePath.trimEnd('/', '\\')).take(12)
        val folder = "工作区/$workspaceName-$workspaceKey/历史摘要"
        val eventKey = digest("$workspacePath|$chatId|$timestamp|$summary").take(16)
        val title = "摘要 $timestamp [$eventKey]"
        val body = buildString {
            appendLine("工作区：$workspacePath")
            appendLine("聊天：$chatId")
            appendLine("事件时间：$timestamp")
            for ((heading, value) in sections) {
                appendLine()
                appendLine("【$heading】")
                appendLine(value)
            }
        }
        val profileId = preferencesManager.activeMemorySpaceIdFlow.first()
        val repository = MemoryRepository(context, profileId)
        if (repository.findMemoryByTitleInFolder(title, folder) != null) return
        repository.createMemory(
            title = title,
            content = body,
            source = "chat_summary",
            folderPath = folder,
            tags = listOf("历史摘要", "工作区:$workspaceName")
        ) ?: error("Unable to save summary memory")
    }

    private fun parseSections(summary: String): List<Pair<String, String>> {
        val pattern = Regex("(?m)^【(用户目标|任务过程|完成项目)】\\s*$")
        val matches = pattern.findAll(summary).toList()
        return matches.mapIndexedNotNull { index, match ->
            val body = summary.substring(
                match.range.last + 1,
                matches.getOrNull(index + 1)?.range?.first ?: summary.length
            ).trim()
            if (body.isBlank()) null else match.groupValues[1] to body
        }
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
