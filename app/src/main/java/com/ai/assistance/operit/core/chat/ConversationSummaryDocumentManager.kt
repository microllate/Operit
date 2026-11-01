package com.ai.assistance.operit.core.chat

import android.content.Context
import com.ai.assistance.operit.core.tools.AIToolHandler
import com.ai.assistance.operit.core.tools.FileContentData
import com.ai.assistance.operit.data.model.AITool
import com.ai.assistance.operit.data.model.ToolParameter
import com.ai.assistance.operit.util.AppLogger

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
        private const val SUMMARY_FILE_NAME = "上下文总结.md"

        @Volatile
        private var INSTANCE: ConversationSummaryDocumentManager? = null

        fun getInstance(context: Context): ConversationSummaryDocumentManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConversationSummaryDocumentManager(context.applicationContext)
                    .also { INSTANCE = it }
            }
    }

    private val toolHandler = AIToolHandler.getInstance(context)

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

            // Restore historical records omitted by the model before replacing the file.
            val mergedContent = mergeHistoricalRecords(expectedCurrentContent, newContent)

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
                    ToolParameter("content", mergedContent),
                    ToolParameter("append", "false")
                ),
                workspaceEnv
            )
            if (!write.success) {
                return DocumentResult(false, error = "Unable to write summary document")
            }

            val verified = read(workspacePath, workspaceEnv, chatId)
            if (!verified.success || verified.content != mergedContent) {
                return DocumentResult(false, error = "Summary document read-back verification failed")
            }
            DocumentResult(true, content = verified.content)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to write summary document", e)
            DocumentResult(false, error = e.message ?: "Unknown write error")
        }
    }

    /**
     * Merges cumulative summary sections while preserving omitted historical records.
     * Both escaped "\\n" sequences and real line breaks are normalized to real newlines.
     */
    private fun mergeHistoricalRecords(previousContent: String, generatedContent: String): String {
        val previous = normalizeLineBreaks(previousContent)
        val generated = normalizeLineBreaks(generatedContent)
        if (previous.isBlank()) return generated
        if (generated.isBlank()) return previous

        val headers = Regex("(?m)^[ \\t]*(?:【[^】]+】|\\[[^]]+])[ \\t]*$")
        val old = headers.findAll(previous).toList()
        val fresh = headers.findAll(generated).toList()

        fun key(line: String): String = line
            .trim()
            .removePrefix("-").removePrefix("•").removePrefix("*")
            .replace(Regex("[\\s，。；：:、,.!！?？*_#]"), "")
            .lowercase()

        // If section headings differ, retain generated content and append only
        // historical lines that are not already represented.
        if (old.size < 3 || fresh.size < 3) {
            // Preserve chronology: existing records first, newly generated records last.
            val lines = previous.lines().toMutableList()
            val present = lines.map(::key).filter { it.isNotBlank() }.toMutableSet()
            generated.lines().forEach { line ->
                val normalized = key(line)
                if (normalized.isNotBlank() && normalized !in present) {
                    lines += line
                    present += normalized
                }
            }
            return lines.joinToString("\n").trim()
        }

        val result = StringBuilder()
        for (i in 0 until 3) {
            val newEnd = fresh.getOrNull(i + 1)?.range?.first ?: generated.length
            val oldEnd = old.getOrNull(i + 1)?.range?.first ?: previous.length
            // Start with the existing section body, then append only new records.
            // This keeps the document in chronological order (oldest to newest).
            val lines = previous.substring(old[i].range.first, oldEnd)
                .trimEnd().lines().toMutableList()
            val present = lines.map(::key).filter { it.isNotBlank() }.toMutableSet()
            generated.substring(fresh[i].range.last + 1, newEnd).lines().forEach { line ->
                val normalized = key(line)
                if (normalized.isNotBlank() && normalized !in present) {
                    lines += line
                    present += normalized
                }
            }
            if (i > 0) result.append("\n\n")
            result.append(lines.joinToString("\n").trimEnd())
        }
        return result.toString().trim()
    }

    /** Converts escaped and platform line endings into actual newline characters. */
    private fun normalizeLineBreaks(content: String): String =
        content.replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
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
