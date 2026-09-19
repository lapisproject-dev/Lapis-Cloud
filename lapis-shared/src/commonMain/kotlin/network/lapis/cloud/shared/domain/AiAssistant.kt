package network.lapis.cloud.shared.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * Welle V1.6.1 "KI-Fundament + Pilot Satzungs-Q&A" -- wire types of the optional, default-OFF AI
 * assistance layer, see [network.lapis.cloud.shared.rpc.IAiAssistantService]. Only [STATUTE_QA]
 * exists so far; every further AI feature is a separate, individually opt-in-gated value here.
 */
@Serializable
enum class AiFeature { STATUTE_QA, }

/**
 * Index state of a document in the AI knowledge base. [NOT_RELEASED] is a DTO-only state (a
 * document without a release row has no index-state row at all); the four others are persisted in
 * `ai_knowledge_index_state.status`. [FAILED] is deliberately distinct from [UNSUPPORTED_FORMAT]:
 * a corrupt PDF is an extraction failure, not "format not readable" -- showing the latter would be
 * untrue.
 */
@Serializable
enum class AiIndexStatus { NOT_RELEASED, PENDING, INDEXED, UNSUPPORTED_FORMAT, FAILED }

/**
 * Outcome of one statute question. Rate-limit and provider failures are DTO outcomes on purpose,
 * not exceptions: Kilua RPC never transmits an exception's message, so an exception could not
 * carry the "retry after" hint the client shows.
 */
@Serializable
enum class AiAnswerOutcome { ANSWERED, NOTHING_FOUND, RATE_LIMITED, PROVIDER_UNAVAILABLE }

/**
 * One cited passage. Every field is taken from the stored document/chunk record, never from model
 * output -- a citation the model invented is therefore not expressible. [locator] is the
 * section label (e.g. "§ 7 Abs. 2") when the chunk has one, otherwise "Seite N".
 */
@Serializable
data class AiCitationDto(
    val documentTitle: String,
    val versionNumber: Int,
    val locator: String,
    val excerpt: String,
)

/**
 * One document of the searchable scope. [unindexedReason] is `null` for an indexed document, else
 * a stable code (`UNSUPPORTED_FORMAT`, `FAILED`, `PENDING`) the client maps to a display text.
 */
@Serializable
data class AiKnowledgeScopeItemDto(
    val documentTitle: String,
    val versionNumber: Int,
    val unindexedReason: String? = null,
)

@Serializable
data class StatuteAnswerDto(
    val outcome: AiAnswerOutcome,
    val summary: String? = null,
    val citations: List<AiCitationDto> = emptyList(),
    val searchedDocuments: List<AiKnowledgeScopeItemDto> = emptyList(),
    val retryAfterSeconds: Int? = null,
)

@Serializable
data class AiAssistantStateDto(
    val featureEnabled: Boolean,
    val optIn: Boolean,
    val optInDefault: Boolean,
    val minQuestionChars: Int,
    val maxQuestionChars: Int,
    val indexedScope: List<AiKnowledgeScopeItemDto>,
    val unindexedScope: List<AiKnowledgeScopeItemDto>,
)

/** Admin view (BOARD/ADMIN) of one document's knowledge-base state; [releasable] iff `PUBLIC_MEMBERS`. */
@Serializable
data class AiKnowledgeEntryDto(
    val documentId: String,
    val title: String,
    val accessLevel: DocumentAccessLevel,
    val released: Boolean,
    val status: AiIndexStatus,
    val chunkCount: Int,
    val indexedAt: LocalDateTime? = null,
    val releasable: Boolean,
)
