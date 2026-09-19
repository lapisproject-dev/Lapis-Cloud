package network.lapis.cloud.server.rpc

import network.lapis.cloud.shared.domain.AiAssistantStateDto
import network.lapis.cloud.shared.domain.AiFeature
import network.lapis.cloud.shared.domain.AiKnowledgeEntryDto
import network.lapis.cloud.shared.domain.StatuteAnswerDto
import network.lapis.cloud.shared.rpc.AiFeatureDisabledException
import network.lapis.cloud.shared.rpc.IAiAssistantService

/**
 * Registered instead of [AiAssistantService] whenever the AI layer is NOT operational
 * (`LAPIS_AI_ENABLED` not `true` or an incomplete/invalid provider profile).
 *
 * Why this exists: Kilua RPC generates the route for [IAiAssistantService] at compile time, so leaving
 * the service unregistered does NOT make the path answer 404 -- the handler throws
 * `IllegalStateException("Service IAiAssistantService not found")` and the caller gets an unhandled
 * HTTP 500 plus an ERROR stack trace in the server log for every unauthenticated request (log noise,
 * found in the post-deploy check of V1.6.1). This stub answers every call with the typed
 * [AiFeatureDisabledException] through the normal RPC protocol instead: no stack trace, no service
 * logic, no database access, no authentication or role lookup (nothing to leak), no provider client.
 */
internal class DisabledAiAssistantService : IAiAssistantService {
    override suspend fun getAssistantState(): AiAssistantStateDto = throw AiFeatureDisabledException()

    override suspend fun setMemberOptIn(
        feature: AiFeature,
        enabled: Boolean,
    ): AiAssistantStateDto = throw AiFeatureDisabledException()

    override suspend fun askStatuteQuestion(question: String): StatuteAnswerDto = throw AiFeatureDisabledException()

    override suspend fun listKnowledgeEntries(): List<AiKnowledgeEntryDto> = throw AiFeatureDisabledException()

    override suspend fun setKnowledgeBaseRelease(
        documentId: String,
        released: Boolean,
    ): AiKnowledgeEntryDto = throw AiFeatureDisabledException()

    override suspend fun reindexKnowledgeDocument(documentId: String): AiKnowledgeEntryDto = throw AiFeatureDisabledException()
}
