package com.ai.assistance.operit.data.model

import kotlinx.serialization.Serializable

/** A durable observation about a tool failure. Observations are not treated as verified fixes. */
@Serializable
data class ToolExperience(
    val id: String,
    val memorySpaceId: String,
    val chatId: String? = null,
    val taskId: String = "",
    val toolName: String,
    val invocation: String,
    val taskContext: String = "",
    val error: String,
    val solution: String? = null,
    val verifiedAt: Long? = null,
    val status: String = STATUS_OBSERVED_FAILURE,
    val occurrences: Int = 1,
    val createdAt: Long,
    val updatedAt: Long
) {
    companion object {
        const val STATUS_OBSERVED_FAILURE = "OBSERVED_FAILURE"
        const val STATUS_SOLUTION_CANDIDATE = "SOLUTION_CANDIDATE"
        const val STATUS_VERIFIED_SOLUTION = "VERIFIED_SOLUTION"
        const val STATUS_INVALIDATED = "INVALIDATED"
    }
}
