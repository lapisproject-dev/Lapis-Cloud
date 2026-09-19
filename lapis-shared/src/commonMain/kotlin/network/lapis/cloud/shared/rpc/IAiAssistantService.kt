package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import network.lapis.cloud.shared.domain.AiAssistantStateDto
import network.lapis.cloud.shared.domain.AiFeature
import network.lapis.cloud.shared.domain.AiKnowledgeEntryDto
import network.lapis.cloud.shared.domain.StatuteAnswerDto

/**
 * Welle V1.6.1 "KI-Fundament + Pilot Satzungs-Q&A" -- optional AI assistance, **default OFF**.
 *
 * **Only registered when the operator switched the feature on and configured a provider**
 * (`LAPIS_AI_ENABLED=true` plus a complete provider profile); otherwise every call on this
 * interface answers 404 and every server-side method would throw [AiFeatureDisabledException]
 * anyway. The client learns whether to show the entry from
 * [network.lapis.cloud.shared.domain.SessionInfoDto.aiAssistantEnabled].
 *
 * - Every member call additionally requires the member's own opt-in ([AiOptInMissingException]).
 * - Knowledge-base administration is BOARD/ADMIN only (not TREASURER) and limited to documents at
 *   access level `PUBLIC_MEMBERS` ([AiDocumentNotReleasableException]).
 * - [askStatuteQuestion] never returns a summary without at least one verified citation.
 */
@RpcService
interface IAiAssistantService {
    /** State the screen needs: feature flag, own opt-in, limits and the searchable/unsearchable scope. */
    suspend fun getAssistantState(): AiAssistantStateDto

    /** Records the caller's own consent for [feature]; idempotent. */
    suspend fun setMemberOptIn(
        feature: AiFeature,
        enabled: Boolean,
    ): AiAssistantStateDto

    /**
     * One statute question: PII-redacted retrieval over released documents the caller may read,
     * one tool-less model call, citations built from stored records only.
     */
    suspend fun askStatuteQuestion(question: String): StatuteAnswerDto

    /** Role: BOARD/ADMIN. Every non-deleted document with its knowledge-base state. */
    suspend fun listKnowledgeEntries(): List<AiKnowledgeEntryDto>

    /** Role: BOARD/ADMIN. Releases/revokes one document; releasing indexes it synchronously. */
    suspend fun setKnowledgeBaseRelease(
        documentId: String,
        released: Boolean,
    ): AiKnowledgeEntryDto

    /** Role: BOARD/ADMIN. Re-extracts and re-chunks an already released document. */
    suspend fun reindexKnowledgeDocument(documentId: String): AiKnowledgeEntryDto
}
