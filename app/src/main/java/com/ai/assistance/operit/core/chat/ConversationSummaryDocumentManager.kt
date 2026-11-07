package com.ai.assistance.operit.core.chat

import android.content.Context
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.FileContentData
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.util.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists the durable, cumulative part of a conversation summary in its workspace.
 *
 * All filesystem operations go through AIToolHandler so the workspace environment
 * (Android, remote, etc.) is respected.
 */
class ConversationSummaryDocumentManager private constructor(context: Context) {

    companion object {
        private const val TAG = "SummaryDocumentManager"
        private const val SUMMARY_DIRECTORY = ".operit/chat-summaries"
        private const val SUMMARY_FILE_NAME = "历史摘要.md"

        @Volatile
        private var INSTANCE: ConversationSummaryDocumentManager? = null

        fun getInstance(context: Context): ConversationSummaryDocumentManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConversationSummaryDocumentManager(context.applicationContext)
                    .also { INSTANCE = it }
            }
    }

    private val toolHandler = AIToolHandler.getInstance(context)
    private val appendMutex = Mutex()

    data class DocumentResult(
        val success: Boolean,
        val content: String? = null,
        val error: String? = null
    )

    /**
     * Reads the cumulative summary document for one chat.
     * A missing file is represented by successful empty content.
     */
    suspend fun read(
        workspacePath: String?,
        workspaceEnv: String?,
        chatId: String
    ): DocumentResult {
        val path = resolveDocumentPath(workspacePath, chatId)
            ?: return DocumentResult(false, error = "Workspace path is empty")

        return try {
            val exists = execute(
                "file_exists",
                listOf(ToolParameter("path", path)),
                workspaceEnv
            )
            if (!exists.success) {
                return DocumentResult(false, error = "Unable to check summary document")
            }
            val existsData = exists.result as? com.ai.assistance.operit.core.tools.FileExistsData
                ?: return DocumentResult(false, error = "Invalid file_exists result")
            if (!existsData.exists) return DocumentResult(true, content = "")

            val result = execute(
                "read_file_full",
                listOf(
                    ToolParameter("path", path),
                    ToolParameter("text_only", "true")
                ),
                workspaceEnv
            )
            if (!result.success) {
                return DocumentResult(false, error = "Unable to read summary document")
            }
            val content = (result.result as? FileContentData)?.content
                ?: return DocumentResult(false, error = "Invalid summary document content")
            DocumentResult(true, content = content)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to read summary document", e)
            DocumentResult(false, error = e.message ?: "Unknown read error")
        }
    }

    /**
     * Writes newly extracted memory into the cumulative workspace document.
     * Read once to preserve previous content, then write the merged document once.
     */
    suspend fun write(
        workspacePath: String?,
        workspaceEnv: String?,
        chatId: String,
        newContent: String
    ): DocumentResult = appendMutex.withLock {
        val path = resolveDocumentPath(workspacePath, chatId)
            ?: return@withLock DocumentResult(false, error = "Workspace path is empty")
        if (newContent.isBlank()) return@withLock DocumentResult(true)

        try {
            val current = read(workspacePath, workspaceEnv, chatId)
            if (!current.success) return@withLock current

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date())
            val existing = current.content.orEmpty().trimEnd()
            val merged = listOf(
                existing,
                "$timestamp\\n$newContent"
            ).filter { it.isNotBlank() }.joinToString("\\n\\n") + "\\n"
            val mkdir = execute(
                "make_directory",
                listOf(
                    ToolParameter("path", path.substringBeforeLast("/")),
                    ToolParameter("create_parents", "true")
                ),
                workspaceEnv
            )
            if (!mkdir.success) {
                return@withLock DocumentResult(false, error = "Unable to create summary directory")
            }

            val write = execute(
                "write_file",
                listOf(
                    ToolParameter("path", path),
                    ToolParameter("content", merged),
                    ToolParameter("append", "false")
                ),
                workspaceEnv
            )
            if (!write.success) {
                return@withLock DocumentResult(false, error = "Unable to write summary document")
            }
            DocumentResult(true, content = merged)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to write summary document", e)
            DocumentResult(false, error = e.message ?: "Unknown write error")
        }
    }

    private suspend fun execute(
        toolName: String,
        parameters: List<ToolParameter>,
        workspaceEnv: String?
    ) = toolHandler.executeTool(
        AITool(
            name = toolName,
            parameters = if (workspaceEnv.isNullOrBlank()) {
                parameters
            } else {
                parameters + ToolParameter("environment", workspaceEnv)
            }
        )
    )

    private fun resolveDocumentPath(workspacePath: String?, chatId: String): String? {
        val root = workspacePath?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val safeChatId = chatId.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "__default__" }
        return "$root/$SUMMARY_DIRECTORY/$safeChatId/$SUMMARY_FILE_NAME"
    }
}


internal fun mergeTimestampedMemorySections(
    existing: String,
    incoming: String,
    timestamp: String
): String {
    val headerPattern = Regex("(?m)^(【[^】]+】|\\[[^\\]]+\\])\\s*$")
    data class Section(val header: String, val body: String)

    fun parseSections(content: String): Pair<String, List<Section>> {
        val matches = headerPattern.findAll(content).toList()
        if (matches.isEmpty()) return content.trimEnd() to emptyList()
        val preamble = content.substring(0, matches.first().range.first).trimEnd()
        val sections = matches.mapIndexed { index, match ->
            val bodyStart = match.range.last + 1
            val bodyEnd = matches.getOrNull(index + 1)?.range?.first ?: content.length
            Section(match.value, content.substring(bodyStart, bodyEnd).trim())
        }
        return preamble to sections
    }

    fun normalize(value: String): String = value.replace(Regex("\\s+"), " ").trim()

    val (preamble, existingSections) = parseSections(existing)
    val (_, incomingSections) = parseSections(incoming)
    if (incomingSections.isEmpty()) return existing
    val updated = existingSections.toMutableList()

    incomingSections.forEach { incomingSection ->
        if (incomingSection.body.isBlank()) return@forEach
        val index = updated.indexOfFirst { it.header == incomingSection.header }
        val oldBody = if (index >= 0) updated[index].body else ""
        if (normalize(oldBody).contains(normalize(incomingSection.body))) return@forEach
        val addition = "$timestamp\n${incomingSection.body.trim()}"
        if (index >= 0) {
            updated[index] = updated[index].copy(
                body = listOf(oldBody, addition).filter { it.isNotBlank() }.joinToString("\n\n")
            )
        } else {
            updated.add(Section(incomingSection.header, addition))
        }
    }

    if (updated == existingSections) return existing
    return buildString {
        if (preamble.isNotBlank()) append(preamble + "\n\n")
        updated.forEachIndexed { index, section ->
            if (index > 0) append("\n\n")
            append(section.header)
            if (section.body.isNotBlank()) append("\n" + section.body)
        }
        append("\n")
    }
}

internal fun mergeAppendContent(existing: String, incoming: String): String {
    if (incoming.isBlank()) return existing
    val known = existing.lineSequence()
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotEmpty() }
        .toMutableSet()
    val additions = incoming.lineSequence().filter { line ->
        val normalized = line.replace(Regex("\\s+"), " ").trim()
        normalized.isNotEmpty() && known.add(normalized)
    }.toList()
    if (additions.isEmpty()) return existing
    val separator = when {
        existing.isEmpty() -> ""
        existing.endsWith("\n\n") -> ""
        existing.endsWith("\n") -> "\n"
        else -> "\n\n"
    }
    return existing + separator + additions.joinToString("\n")
}
