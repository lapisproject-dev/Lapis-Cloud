package network.lapis.cloud.server.ai.audit

import kotlin.uuid.Uuid

/**
 * Outcome of one AI request as recorded in `ai_call_audit.outcome`. Public because the (public)
 * hand-maintained Exposed table `AiCallAuditTable` stores it by name.
 */
enum class AiCallOutcome {
    ANSWERED,
    NOTHING_FOUND,
    NO_VALID_CITATION,
    PROVIDER_ERROR,
}

/**
 * One usage row per model call. **Hashes only, never clear text** -- [inputHash] is the SHA-256 of
 * the PII-redacted question, [outputHash] the SHA-256 of the raw model answer (`null` when there
 * was none).
 */
internal data class AiCallAuditEntry(
    val memberId: Uuid?,
    val agentType: String,
    val provider: String,
    val model: String,
    val inputHash: String,
    val outputHash: String?,
    val tokensIn: Int?,
    val tokensOut: Int?,
    val toolsCalled: List<String>,
    val outcome: AiCallOutcome,
    val retrievedChunkCount: Int,
)

/** The pipeline's only write path -- an interface, so the model-facing code holds no DB handle. */
internal interface AiCallAuditSink {
    /** Must not throw into the caller: an audit failure never turns a member's answer into an error. */
    fun record(entry: AiCallAuditEntry)
}
