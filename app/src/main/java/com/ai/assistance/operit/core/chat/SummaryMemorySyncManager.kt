package com.ai.assistance.operit.core.chat

import android.content.Context
import com.ai.assistance.operit.data.model.Memory
import com.ai.assistance.operit.data.preferences.preferencesManager
import com.ai.assistance.operit.data.repository.MemoryRepository
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mirrors each completed summary into a workspace-scoped memory event.
 * The Markdown document remains the source of truth; sync failures never delete it.
 */
internal class SummaryMemorySyncManager(private val context: Context) {
    data class Issue(
        val key: String,
        val title: String,
        val keywords: List<String>,
        val goal: String,
        val progress: String,
        val result: String,
        val next: String
    ) {
        fun toMarkdown(): String = buildString {
            appendLine("### ${title.replace('\n', ' ')}")
            appendLine("问题编号：$key")
            appendLine("关键词：${keywords.joinToString("、")}")
            appendLine("【用户目标】$goal")
            appendLine("【任务过程】$progress")
            appendLine("【完成项目】$result")
            append("【下一步策略】$next")
        }
    }

    companion object {
        internal fun workspaceFolder(workspacePath: String): String {
            val path = workspacePath.trim().trimEnd('/', '\\')
            val name = path.substringAfterLast('/').substringAfterLast('\\')
                .ifBlank { "workspace" }.replace("/", "_").replace("\\", "_")
            return "工作区/$name-${digest(path).take(12)}"
        }

        private fun digest(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private val issueKeyPattern = Regex("[a-z0-9][a-z0-9_-]{0,63}")

        fun issueTag(key: String): String? =
            key.trim().takeIf(issueKeyPattern::matches)?.let { "问题:$it" }

        fun parseIssues(response: String): List<Issue> {
            val json = response.trim().removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val array = JSONObject(json).getJSONArray("issues")
            require(array.length() in 1..30) { "Expected 1–30 issue summaries" }
            return List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val key = item.getString("key").trim()
                val title = item.getString("title").trim()
                require(issueTag(key) != null && title.isNotBlank())
                val terms: JSONArray = item.getJSONArray("keywords")
                val keywords = List(terms.length().coerceAtMost(8)) { terms.getString(it).trim().take(80) }
                    .filter { it.isNotBlank() }.distinct()
                Issue(key, title.take(140), keywords, item.getString("goal").trim(),
                    item.getString("progress").trim(), item.getString("result").trim(),
                    item.getString("next").trim())
            }.also { issues ->
                require(issues.distinctBy { it.key }.size == issues.size) { "Duplicate issue keys" }
            }
        }

        suspend fun linkIssueMemory(
            repository: MemoryRepository,
            workspaceFolder: String,
            issueTag: String,
            memory: Memory
        ) {
            val isSummary = memory.source == "chat_summary"
            val candidates = repository.getMemoriesByFolderPath(workspaceFolder)
                .asSequence()
                .filter { it.id != memory.id && (it.source == "chat_summary") != isSummary }
                .filter { candidate -> candidate.tags.any { it.name == issueTag } }
                .sortedByDescending { it.createdAt }
                .take(if (isSummary) 3 else 1)
                .toList()
            for (candidate in candidates) {
                val summary = if (isSummary) memory else candidate
                val note = if (isSummary) candidate else memory
                repository.linkMemories(summary, note, "same_issue")
            }
        }
    }

    suspend fun recentIssueKeys(workspacePath: String): List<Pair<String, String>> {
        val repository = MemoryRepository(context, preferencesManager.activeMemorySpaceIdFlow.first())
        return repository.getMemoriesByFolderPath(workspaceFolder(workspacePath))
            .sortedByDescending { it.createdAt }
            .asSequence()
            .mapNotNull { memory ->
                val tag = memory.tags.firstOrNull { it.name.startsWith("问题:") } ?: return@mapNotNull null
                val key = tag.name.removePrefix("问题:")
                if (issueTag(key) != tag.name) return@mapNotNull null
                key to memory.title.substringBefore(" [").replace('\n', ' ').take(100)
            }
            .distinctBy { it.first }
            .take(24)
            .toList()
    }

    suspend fun sync(workspacePath: String, chatId: String, timestamp: String, summary: String, issues: List<Issue>) {
        val sections = parseSections(summary)
        if (sections.isEmpty() && issues.isEmpty()) return

        val workspaceFolder = workspaceFolder(workspacePath)
        val workspaceName = workspacePath.trim().trimEnd('/', '\\')
            .substringAfterLast('/').substringAfterLast('\\').ifBlank { "workspace" }
        val folder = "$workspaceFolder/历史摘要"
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
        if (issues.isNotEmpty()) {
            for (issue in issues) {
                val tag = requireNotNull(issueTag(issue.key))
                val issueTitle = "${issue.title} [$timestamp/$eventKey/${issue.key}]"
                val memory = repository.findMemoryByTitleInFolder(issueTitle, folder)
                    ?: repository.createMemory(
                        title = issueTitle,
                        content = "工作区：$workspacePath\n聊天：$chatId\n事件时间：$timestamp\n\n${issue.toMarkdown()}",
                        source = "chat_summary",
                        folderPath = folder,
                        tags = (listOf("历史摘要", "工作区:$workspaceName", tag) + issue.keywords).distinct()
                    ) ?: error("Unable to save issue summary")
                linkIssueMemory(repository, workspaceFolder, tag, memory)
            }
            return
        }
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
}
