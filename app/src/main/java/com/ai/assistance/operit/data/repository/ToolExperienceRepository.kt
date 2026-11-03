package com.ai.assistance.operit.data.repository

import android.content.Context
import android.util.AtomicFile
import com.ai.assistance.operit.data.model.ToolExperience
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Stores tool execution observations separately from conversational memories.
 *
 * An observed failure is deliberately not promoted to a solution. Only a later,
 * explicit verification flow should change its status to VERIFIED_SOLUTION.
 */
object ToolExperienceRepository {
    private const val DIRECTORY_NAME = "tool-experiences"
    private const val MAX_RECORDS = 200
    private const val MAX_FIELD_CHARS = 1_200
    private const val MAX_INVOCATION_CHARS = 1_200
    private const val MAX_TASK_CONTEXT_CHARS = 300
    private const val MAX_ERROR_CHARS = 500
    private const val MAX_RESULT_CHARS = 600
    private const val MAX_SUMMARY_CHARS = 400
    private const val DEFAULT_RESULT_LIMIT = 5
    private const val MAX_RETRY_WINDOW_MS = 30 * 60 * 1000L
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun recordFailure(
        context: Context,
        memorySpaceId: String,
        toolName: String,
        invocation: String,
        error: String,
        chatId: String? = null,
        taskId: String = "",
        taskContext: String = ""
    ): ToolExperience = withContext(Dispatchers.IO) {
        val normalizedSpaceId = memorySpaceId.trim().ifBlank { "default" }
        val file = experienceFile(context, normalizedSpaceId)
        val lock = locks.getOrPut(file.absolutePath) { Mutex() }
        lock.withLock {
            val records = readRecords(file).toMutableList()
            val now = System.currentTimeMillis()
            val safeToolName = toolName.take(256)
            val safeInvocation = compactInvocation(invocation)
            val safeError = redactSensitiveData(error).take(MAX_ERROR_CHARS)
                .ifBlank { "Unknown tool execution failure" }
            val signature = signatureOf(safeToolName, safeInvocation, safeError)
            val existingIndex = records.indexOfFirst {
                signatureOf(it.toolName, it.invocation, it.error) == signature &&
                    it.taskId == taskId &&
                    it.status != ToolExperience.STATUS_INVALIDATED
            }

            val record = if (existingIndex >= 0) {
                val old = records[existingIndex]
                old.copy(
                    occurrences = old.occurrences + 1,
                    updatedAt = now,
                    chatId = chatId?.take(256) ?: old.chatId,
                    taskId = taskId.take(128),
                    taskContext = redactSensitiveData(taskContext).take(MAX_TASK_CONTEXT_CHARS).ifBlank { old.taskContext }
                ).also { records[existingIndex] = it }
            } else {
                ToolExperience(
                    id = UUID.randomUUID().toString(),
                    memorySpaceId = normalizedSpaceId,
                    chatId = chatId?.take(256),
                    taskId = taskId.take(128),
                    toolName = safeToolName,
                    invocation = safeInvocation,
                    taskContext = redactSensitiveData(taskContext).take(MAX_TASK_CONTEXT_CHARS),
                    error = safeError,
                    status = ToolExperience.STATUS_OBSERVED_FAILURE,
                    createdAt = now,
                    updatedAt = now
                ).also { records.add(it) }
            }

            val trimmed = records
                .sortedByDescending { it.updatedAt }
                .take(MAX_RECORDS)
            writeRecords(file, trimmed)
            record
        }
    }

    /**
     * Verify a previous failure only when the exact same tool invocation later succeeds.
     * Matching tool names alone is intentionally insufficient.
     */
    suspend fun recordSuccessfulRetry(
        context: Context,
        memorySpaceId: String,
        toolName: String,
        invocation: String,
        result: String,
        taskId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val file = experienceFile(context, memorySpaceId.trim().ifBlank { "default" })
        val lock = locks.getOrPut(file.absolutePath) { Mutex() }
        lock.withLock {
            val records = readRecords(file).toMutableList()
            val safeInvocation = compactInvocation(invocation)
            val normalizedInvocation = normalizeInvocation(safeInvocation)
            val index = records.indices
                .filter { records[it].toolName == toolName &&
                    records[it].taskId == taskId &&
                    normalizeInvocation(records[it].invocation) == normalizedInvocation &&
                    records[it].status == ToolExperience.STATUS_OBSERVED_FAILURE }
                .maxByOrNull { records[it].updatedAt }
                ?: return@withLock false

            val existing = records[index]
            val now = System.currentTimeMillis()
            records[index] = existing.copy(
                solution = "The exact same invocation later succeeded (tool-level success only; task not verified). Result: " +
                    redactSensitiveData(result).take(MAX_RESULT_CHARS),
                status = ToolExperience.STATUS_SOLUTION_CANDIDATE,
                verifiedAt = null,
                updatedAt = now
            )
            writeRecords(file, records)
            true
        }
    }

    /**
     * Record a changed invocation as a candidate when it follows a failure of the same
     * tool in the same chat. This is deliberately NOT marked verified: tool-level success
     * does not prove that the user's overall task was completed.
     */
    suspend fun recordSolutionCandidate(
        context: Context,
        memorySpaceId: String,
        chatId: String?,
        toolName: String,
        invocation: String,
        result: String,
        taskId: String,
        taskContext: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        if (chatId.isNullOrBlank()) return@withContext false
        val file = experienceFile(context, memorySpaceId.trim().ifBlank { "default" })
        val lock = locks.getOrPut(file.absolutePath) { Mutex() }
        lock.withLock {
            val records = readRecords(file).toMutableList()
            val safeInvocation = compactInvocation(invocation)
            val safeResult = redactSensitiveData(result).take(MAX_RESULT_CHARS)
            val normalizedInvocation = normalizeInvocation(safeInvocation)
            val now = System.currentTimeMillis()
            val index = records.indices
                .filter { i ->
                    val record = records[i]
                    record.chatId == chatId &&
                        record.taskId == taskId &&
                        record.toolName == toolName &&
                        (taskContext.isBlank() || record.taskContext == redactSensitiveData(taskContext).take(MAX_TASK_CONTEXT_CHARS)) &&
                        record.status == ToolExperience.STATUS_OBSERVED_FAILURE &&
                        normalizeInvocation(record.invocation) != normalizedInvocation &&
                        now - record.updatedAt <= MAX_RETRY_WINDOW_MS
                }
                .maxByOrNull { records[it].updatedAt }
                ?: return@withLock false

            val existing = records[index]
            records[index] = existing.copy(
                solution = "Candidate successful retry invocation: " +
                    safeInvocation.take(MAX_INVOCATION_CHARS / 2) +
                    "\nTool result (not task-verified): " +
                    safeResult.take(MAX_RESULT_CHARS),
                status = ToolExperience.STATUS_SOLUTION_CANDIDATE,
                updatedAt = now
            )
            writeRecords(file, records)
            true
        }
    }

    /**
     * Attach the assistant's final task summary to candidates from this chat.
     * A final answer is not sufficient proof of correctness, so candidates remain unverified.
     */
    suspend fun recordTaskCompletionSummary(
        context: Context,
        memorySpaceId: String,
        chatId: String?,
        taskContext: String,
        summary: String,
        taskId: String
    ): Int = withContext(Dispatchers.IO) {
        if (chatId.isNullOrBlank() || summary.isBlank()) return@withContext 0
        val file = experienceFile(context, memorySpaceId.trim().ifBlank { "default" })
        val lock = locks.getOrPut(file.absolutePath) { Mutex() }
        lock.withLock {
            val records = readRecords(file).toMutableList()
            val safeSummary = summarizeForStorage(summary)
            val now = System.currentTimeMillis()
            var updated = 0
            for (index in records.indices) {
                val record = records[index]
                if (record.chatId == chatId &&
                    record.taskId == taskId &&
                    record.taskContext == redactSensitiveData(taskContext).take(MAX_TASK_CONTEXT_CHARS) &&
                    record.status == ToolExperience.STATUS_SOLUTION_CANDIDATE
                ) {
                    val previous = record.solution.orEmpty()
                    records[index] = record.copy(
                        solution = (previous + "\nTask completion summary (not independently verified): " +
                            safeSummary).take(MAX_FIELD_CHARS),
                        updatedAt = now
                    )
                    updated++
                }
            }
            if (updated > 0) writeRecords(file, records)
            updated
        }
    }

    private fun redactSensitiveData(value: String): String {
        val sensitiveName = Regex(
            """(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|authorization|cookie|client[_-]?secret)(\s*[=:]\s*)([^,;\s]+|"[^"]*"|'[^']*')"""
        )
        val bearer = Regex("""(?i)\b(bearer\s+)[A-Za-z0-9._~+/-]+=*""")
        return bearer.replace(
            sensitiveName.replace(value) { match ->
                match.groupValues[1] + match.groupValues[2] + "[REDACTED]"
            }
        ) { match -> match.groupValues[1] + "[REDACTED]" }
    }
    private fun compactInvocation(value: String): String {
        val redacted = redactSensitiveData(value)
        val compacted = redacted
            .replace(Regex("(?is)(<(?:old|new|content|code)>).*?(</(?:old|new|content|code)>)")) {
                it.groupValues[1] + "[内容已省略]" + it.groupValues[2]
            }
            .replace(Regex("(?is)(\"(?:old|new|content|code)\"\\s*:\\s*\")[^\"]{200,}(\")")) {
                it.groupValues[1] + "[内容已省略]" + it.groupValues[2]
            }
        return compacted.take(MAX_INVOCATION_CHARS)
    }

    private fun summarizeForStorage(value: String): String =
        redactSensitiveData(value).replace(Regex("\\s+"), " ").trim().take(MAX_SUMMARY_CHARS)

    private fun normalizeInvocation(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    /**
     * Find successful retry candidates that pair an observed failure with a changed invocation.
     * These are useful correction hints, but remain explicitly unverified at task level.
     */
    /**
     * Expand natural-language file-operation requests to the tool vocabulary used in
     * recorded experiences. This runs before the model's first tool call.
     */
    private fun expandToolExperienceQuery(query: String): String {
        val normalized = query.lowercase()
        val additions = linkedSetOf<String>()
        fun addWhenMatched(vararg terms: String, expansion: String) {
            if (terms.any { normalized.contains(it) }) additions += expansion
        }

        addWhenMatched("修改文件", "编辑文件", "编辑代码", "替换内容", "修改代码", "写入文件", "更新文件",
            "改文件", "patch", "edit file", expansion = "edit_file old new required parameters")
        addWhenMatched("读取文件", "查看文件", "阅读文件", "打开文件", "文件内容",
            "read file", expansion = "read_file read_file_part read_file_full")
        addWhenMatched("列出文件", "文件列表", "目录列表", "查看目录", "列出目录",
            "list files", expansion = "list_files")
        addWhenMatched("搜索文件", "查找文件", "搜索代码", "查找代码", "代码搜索",
            "grep", "find files", expansion = "find_files grep_code")
        return (query + " " + additions.joinToString(" ")).trim()
    }

    suspend fun findRelevantCorrections(
        context: Context,
        memorySpaceId: String,
        query: String,
        limit: Int = 3
    ): List<ToolExperience> = withContext(Dispatchers.IO) {
        if (query.isBlank() || limit <= 0) return@withContext emptyList()
        val file = experienceFile(context, memorySpaceId.trim().ifBlank { "default" })
        val lock = locks.getOrPut(file.absolutePath) { Mutex() }
        lock.withLock {
            val queryTokens = tokenize(expandToolExperienceQuery(query))
            if (queryTokens.isEmpty()) return@withLock emptyList()

            readRecords(file)
                .asSequence()
                .filter {
                    it.status == ToolExperience.STATUS_SOLUTION_CANDIDATE &&
                        it.solution.orEmpty().contains("Candidate successful retry invocation:")
                }
                .map { record ->
                    val searchable = listOf(
                        record.toolName,
                        record.invocation,
                        record.taskContext,
                        record.error,
                        record.solution.orEmpty()
                    ).joinToString(" ").lowercase()
                    record to queryTokens.count { searchable.contains(it) }
                }
                .filter { (_, score) -> score > 0 }
                .sortedWith(
                    compareByDescending<Pair<ToolExperience, Int>> { it.second }
                        .thenByDescending { it.first.updatedAt }
                )
                .take(limit)
                .map { it.first }
                .toList()
        }
    }

    suspend fun findRelevant(
        context: Context,
        memorySpaceId: String,
        query: String,
        limit: Int = DEFAULT_RESULT_LIMIT
    ): List<ToolExperience> = withContext(Dispatchers.IO) {
        if (query.isBlank() || limit <= 0) return@withContext emptyList()
        val file = experienceFile(context, memorySpaceId.trim().ifBlank { "default" })
        val lock = locks.getOrPut(file.absolutePath) { Mutex() }
        lock.withLock {
            val queryTokens = tokenize(expandToolExperienceQuery(query))
            if (queryTokens.isEmpty()) return@withLock emptyList()

            readRecords(file)
                .asSequence()
                .filter {
                    (it.status == ToolExperience.STATUS_VERIFIED_SOLUTION && it.verifiedAt != null) ||
                        it.status == ToolExperience.STATUS_OBSERVED_FAILURE
                }
                .map { record ->
                    val searchable = listOf(
                        record.toolName,
                        record.invocation,
                        record.taskContext,
                        record.error
                    ).joinToString(" ").lowercase()
                    val score = queryTokens.count { searchable.contains(it) }
                    record to score
                }
                .filter { (_, score) -> score > 0 }
                .sortedWith(
                    compareByDescending<Pair<ToolExperience, Int>> { it.second }
                        .thenByDescending { it.first.updatedAt }
                )
                .take(limit)
                .map { it.first }
                .toList()
        }
    }

    /**
     * Promote a candidate only when a machine-checkable post-condition was observed.
     * Tool execution success and assistant final text are not verification evidence.
     */
    /**
     * Attach machine-collected evidence to the exact successful retry candidate.
     * Evidence is retained for later task-level verification and never promotes status.
     */
    suspend fun recordVerificationEvidence(
        context: Context,
        memorySpaceId: String,
        chatId: String?,
        toolName: String,
        invocation: String,
        verificationSource: String,
        evidence: String,
        taskContext: String,
        taskId: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (chatId.isNullOrBlank() || verificationSource.isBlank() || evidence.isBlank()) {
            return@withContext false
        }
        val file = experienceFile(context, memorySpaceId.trim().ifBlank { "default" })
        val lock = locks.getOrPut(file.absolutePath) { Mutex() }
        lock.withLock {
            val records = readRecords(file).toMutableList()
            val safeInvocation = compactInvocation(invocation)
            val index = records.indices
                .filter { i ->
                    val record = records[i]
                    record.chatId == chatId &&
                        record.taskId == taskId &&
                        record.toolName == toolName &&
                        record.taskContext == redactSensitiveData(taskContext).take(MAX_TASK_CONTEXT_CHARS) &&
                        record.status == ToolExperience.STATUS_SOLUTION_CANDIDATE &&
                        record.solution.orEmpty().contains("Candidate successful retry invocation:") &&
                        record.solution.orEmpty().contains(safeInvocation)
                }
                .maxByOrNull { records[it].updatedAt }
                ?: return@withLock false

            val now = System.currentTimeMillis()
            val record = records[index]
            val safeEvidence = summarizeForStorage(evidence).take(MAX_RESULT_CHARS)
            val evidenceEntry = "\nVerification evidence [$verificationSource]: $safeEvidence"
            records[index] = record.copy(
                solution = (record.solution.orEmpty() + evidenceEntry).take(MAX_FIELD_CHARS),
                updatedAt = now
            )
            writeRecords(file, records)
            true
        }
    }

    /**
     * Promote a candidate only when an independently observed result matches the
     * explicitly supplied expected post-condition. A tool's success flag or exit code
     * alone is never sufficient.
     */
    suspend fun verifyTaskResult(
        context: Context,
        memorySpaceId: String,
        experienceId: String,
        verificationSource: String,
        expectedOutcome: String,
        observedOutcome: String,
        taskContext: String,
        taskId: String
    ): Boolean = withContext(Dispatchers.IO) {
        val allowedSources = setOf(
            "FILE_READBACK",
            "COMMAND_EXIT_CODE",
            "HTTP_STATUS",
            "DATABASE_QUERY",
            "STRUCTURED_ASSERTION"
        )
        require(verificationSource in allowedSources) {
            "Unsupported task verification source: $verificationSource"
        }
        require(expectedOutcome.isNotBlank() && observedOutcome.isNotBlank()) {
            "Expected and observed outcomes must not be blank."
        }

        fun canonical(value: String): String =
            value.trim().replace(Regex("\\s+"), " ")

        // Exact comparison is intentionally conservative: no keyword-overlap heuristic
        // may turn a partially completed task into a verified solution.
        if (canonical(expectedOutcome) != canonical(observedOutcome)) {
            return@withContext false
        }

        val file = experienceFile(context, memorySpaceId.trim().ifBlank { "default" })
        val lock = locks.getOrPut(file.absolutePath) { Mutex() }
        lock.withLock {
            val records = readRecords(file).toMutableList()
            val index = records.indexOfFirst {
                it.id == experienceId &&
                    it.taskId == taskId &&
                    it.status == ToolExperience.STATUS_SOLUTION_CANDIDATE &&
                    it.taskContext == redactSensitiveData(taskContext).take(MAX_TASK_CONTEXT_CHARS) &&
                    !it.solution.isNullOrBlank()
            }
            if (index < 0) return@withLock false

            val now = System.currentTimeMillis()
            val evidence = redactSensitiveData(
                "Expected outcome matched observed outcome: ${canonical(observedOutcome)}"
            ).take(MAX_FIELD_CHARS / 2)
            val existing = records[index]
            records[index] = existing.copy(
                solution = (existing.solution.orEmpty() +
                    "\nTask verified [$verificationSource]: " + evidence).take(MAX_FIELD_CHARS),
                status = ToolExperience.STATUS_VERIFIED_SOLUTION,
                verifiedAt = now,
                updatedAt = now
            )
            writeRecords(file, records)
            true
        }
    }

    /**
     * Generic status changes may never mark an experience as verified.
     * Use verifyTaskResult with concrete post-condition evidence instead.
     */
    suspend fun updateStatus(
        context: Context,
        memorySpaceId: String,
        experienceId: String,
        newStatus: String
    ): Boolean = withContext(Dispatchers.IO) {
        require(newStatus in setOf(
            ToolExperience.STATUS_OBSERVED_FAILURE,
            ToolExperience.STATUS_SOLUTION_CANDIDATE,
            ToolExperience.STATUS_INVALIDATED
        )) { "Unsupported status transition: $newStatus" }

        val file = experienceFile(context, memorySpaceId.trim().ifBlank { "default" })
        val lock = locks.getOrPut(file.absolutePath) { Mutex() }
        lock.withLock {
            val records = readRecords(file).toMutableList()
            val index = records.indexOfFirst { it.id == experienceId }
            if (index < 0) return@withLock false
            records[index] = records[index].copy(
                status = newStatus,
                verifiedAt = null,
                updatedAt = System.currentTimeMillis()
            )
            writeRecords(file, records)
            true
        }
    }

    /**
     * Updating a solution always creates an unverified candidate.
     * Verification must go through verifyTaskResult.
     */
    suspend fun updateSolution(
        context: Context,
        memorySpaceId: String,
        experienceId: String,
        solution: String,
        verified: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        require(!verified) {
            "Direct verification is disabled. Use verifyTaskResult with concrete evidence."
        }
        require(solution.isNotBlank()) { "Solution must not be blank." }
        val file = experienceFile(context, memorySpaceId.trim().ifBlank { "default" })
        val lock = locks.getOrPut(file.absolutePath) { Mutex() }
        lock.withLock {
            val records = readRecords(file).toMutableList()
            val index = records.indexOfFirst { it.id == experienceId }
            if (index < 0) return@withLock false
            val now = System.currentTimeMillis()
            records[index] = records[index].copy(
                solution = redactSensitiveData(solution).take(MAX_FIELD_CHARS),
                status = ToolExperience.STATUS_SOLUTION_CANDIDATE,
                verifiedAt = null,
                updatedAt = now
            )
            writeRecords(file, records)
            true
        }
    }

    private fun experienceFile(context: Context, memorySpaceId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(memorySpaceId.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, DIRECTORY_NAME), "$digest.json")
    }

    private fun readRecords(file: File): List<ToolExperience> {
        if (!file.isFile) return emptyList()
        return runCatching {
            json.decodeFromString<List<ToolExperience>>(file.readText(Charsets.UTF_8))
        }.getOrElse { emptyList() }
    }

    private fun writeRecords(file: File, records: List<ToolExperience>) {
        file.parentFile?.mkdirs()
        val atomicFile = AtomicFile(file)
        val output = atomicFile.startWrite()
        try {
            output.write(json.encodeToString(records).toByteArray(Charsets.UTF_8))
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            atomicFile.failWrite(output)
            throw error
        }
    }

    private fun signatureOf(toolName: String, invocation: String, error: String): String {
        val canonical = listOf(toolName, invocation, error)
            .joinToString("|") { it.trim().lowercase().replace(Regex("\\s+"), " ") }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Includes Latin words and CJK bigrams without requiring an embedding request. */
    private fun tokenize(text: String): Set<String> {
        val normalized = text.lowercase()
        val tokens = linkedSetOf<String>()
        Regex("[a-z0-9_:-]{2,}").findAll(normalized).forEach { tokens.add(it.value) }
        val cjkRuns = Regex("[\\u3400-\\u9fff]+").findAll(normalized)
        cjkRuns.forEach { match ->
            val run = match.value
            if (run.length == 1) tokens.add(run)
            else for (index in 0 until run.length - 1) {
                tokens.add(run.substring(index, index + 2))
            }
        }
        return tokens
    }
}
