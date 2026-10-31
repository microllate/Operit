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

    /** Keeps historical records in the first three cumulative sections. */
    private fun mergeHistoricalRecords(previousContent: String, generatedContent: String): String {
        if (previousContent.isBlank()) return generatedContent
        val headers = Regex("(?m)^\\s*(?:【[^】]+】|\\[[^]]+])\\s*$")
        val old = headers.findAll(previousContent).toList()
        val fresh = headers.findAll(generatedContent).toList()
        if (old.size < 3 || fresh.size < 3) {
            return if (generatedContent.contains(previousContent.trim())) generatedContent
            else "$generatedContent\\n\\n${previousContent.trim()}"
        }
        fun key(line: String) = line.trim().removePrefix("-").removePrefix("•").removePrefix("*")
            .replace(Regex("[\\s，。；：:、,.!！?？]"), "").lowercase()
        val result = StringBuilder()
        for (i in 0 until 3) {
            val newEnd = fresh.getOrNull(i + 1)?.range?.first ?: generatedContent.length
            val oldEnd = old.getOrNull(i + 1)?.range?.first ?: previousContent.length
            val lines = generatedContent.substring(fresh[i].range.first, newEnd).trimEnd().lines().toMutableList()
            val present = lines.map { key(it) }.filter { it.isNotBlank() && it != "无" && it != "none" }.toMutableSet()
            val missing = previousContent.substring(old[i].range.last + 1, oldEnd).lines().filter {
                val k = key(it); k.isNotBlank() && k != "无" && k != "none" && k !in present
            }
            lines += missing
            if (i > 0) result.append("\\n\\n")
            result.append(lines.joinToString("\\n").trimEnd())
        }
        return result.toString().trim()
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
