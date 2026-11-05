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
     * Appends new durable memory without replacing existing document content.
     * The document is checked before writing and verified after read-back.
     */
    suspend fun append(
        workspacePath: String?,
        workspaceEnv: String?,
        chatId: String,
        expectedCurrentContent: String,
        newContent: String
    ): DocumentResult = appendMutex.withLock {
        val current = read(workspacePath, workspaceEnv, chatId)
        if (!current.success) return@withLock current
        val existing = current.content.orEmpty()
        if (existing != expectedCurrentContent) {
            return@withLock DocumentResult(false, error = "Summary document changed; refusing to append")
        }
        // Treat the three model-generated sections as one record. Never deduplicate
        // individual headings or paragraph lines, because that can detach content from its section.
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())
        val sectionHeader = Regex("(?m)^(【[^】]+】|\\\\[[^\\\\]]+\\\\])\\\\s*$")
        val timestampedContent = sectionHeader.replace(newContent) { match ->
            "${match.value}\\n$timestamp "
        }.trim()
        if (timestampedContent.isBlank()) {
            return@withLock DocumentResult(true, content = existing)
        }
        val normalizedIncoming = normalizeMemoryRecord(newContent)
        val normalizedExisting = normalizeMemoryRecord(
            existing.replace(
                Regex("(?m)^\\\\d{4}-\\\\d{2}-\\\\d{2} \\\\d{2}:\\\\d{2}:\\\\d{2} "),
                ""
            )
        )
        if (normalizedIncoming.isNotBlank() && normalizedExisting.contains(normalizedIncoming)) {
            return@withLock DocumentResult(true, content = existing)
        }
        val separator = when {
            existing.isEmpty() -> ""
            existing.endsWith("\\n\\n") -> ""
            existing.endsWith("\\n") -> "\\n"
            else -> "\\n\\n"
        }
        val delta = separator + timestampedContent + "\\n"
        val latest = read(workspacePath, workspaceEnv, chatId)
        if (!latest.success) return@withLock latest
        if (latest.content.orEmpty() != existing) {
            return@withLock DocumentResult(false, error = "Summary document changed before append")
        }
        val path = resolveDocumentPath(workspacePath, chatId)
            ?: return@withLock DocumentResult(false, error = "Workspace path is empty")
        try {
            val mkdir = execute("make_directory", listOf(
                ToolParameter("path", path.substringBeforeLast("/")),
                ToolParameter("create_parents", "true")
            ), workspaceEnv)
            if (!mkdir.success) return@withLock DocumentResult(false, error = "Unable to create summary directory")
            val write = execute("write_file", listOf(
                ToolParameter("path", path),
                ToolParameter("content", delta),
                ToolParameter("append", "true")
            ), workspaceEnv)
            if (!write.success) return@withLock DocumentResult(false, error = "Unable to append summary document")
            val verified = read(workspacePath, workspaceEnv, chatId)
            if (!verified.success) return@withLock verified
            val actual = verified.content.orEmpty()
            if (!actual.startsWith(existing) || !actual.contains(delta.trim())) {
                return@withLock DocumentResult(false, error = "Append verification failed")
            }
            DocumentResult(true, content = actual)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to append summary document", e)
            DocumentResult(false, error = e.message ?: "Unknown append error")
        }
    }
    /**
     * Writes a complete replacement only if the document still matches the content
     * previously read by the caller. The write is accepted only after read-back
     * verification succeeds.
     */
    suspend fun write(
        workspacePath: String?,
        workspaceEnv: String?,
        chatId: String,
        expectedCurrentContent: String,
        newContent: String
    ): DocumentResult {
        val path = resolveDocumentPath(workspacePath, chatId)
            ?: return DocumentResult(false, error = "Workspace path is empty")
        if (newContent.isBlank()) {
            return DocumentResult(false, error = "Refusing to write an empty summary")
        }

        return try {
            val current = read(workspacePath, workspaceEnv, chatId)
            if (!current.success) return current
            if (current.content.orEmpty() != expectedCurrentContent) {
                return DocumentResult(false, error = "Summary document changed; refusing to overwrite newer content")
            }

            val directory = path.substringBeforeLast('/')
            val mkdir = execute(
                "make_directory",
                listOf(
                    ToolParameter("path", directory),
                    ToolParameter("create_parents", "true")
                ),
                workspaceEnv
            )
            if (!mkdir.success) {
                return DocumentResult(false, error = "Unable to create summary directory")
            }

            val write = execute(
                "write_file",
                listOf(
                    ToolParameter("path", path),
                    ToolParameter("content", newContent),
                    ToolParameter("append", "false")
                ),
                workspaceEnv
            )
            if (!write.success) {
                return DocumentResult(false, error = "Unable to write summary document")
            }

            val verified = read(workspacePath, workspaceEnv, chatId)
            if (!verified.success || verified.content != newContent) {
                return DocumentResult(false, error = "Summary document read-back verification failed")
            }
            DocumentResult(true, content = verified.content)
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


internal fun normalizeMemoryRecord(content: String): String = content
    .replace("\\r\\n", "\\n")
    .replace(Regex("(?m)^\\\\d{4}-\\\\d{2}-\\\\d{2} \\\\d{2}:\\\\d{2}:\\\\d{2} "), "")
    .replace(Regex("\\\\s+"), " ")
    .trim()

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
