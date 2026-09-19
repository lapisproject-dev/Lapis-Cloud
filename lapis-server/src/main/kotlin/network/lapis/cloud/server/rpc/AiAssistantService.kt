package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import network.lapis.cloud.server.ai.config.AiConfig
import network.lapis.cloud.server.ai.kb.KnowledgeIndexer
import network.lapis.cloud.server.ai.kb.KnowledgeReleaseStore
import network.lapis.cloud.server.ai.optin.AiMemberOptInStore
import network.lapis.cloud.server.ai.qa.QaOutcome
import network.lapis.cloud.server.ai.qa.StatuteQaPipeline
import network.lapis.cloud.server.ai.ratelimit.AiQuestionRateLimiter
import network.lapis.cloud.server.ai.ratelimit.RateLimitDecision
import network.lapis.cloud.server.db.generated.DocumentTable
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.canAccessDocumentAtLevel
import network.lapis.cloud.server.security.isPrivileged
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AiAnswerOutcome
import network.lapis.cloud.shared.domain.AiAssistantStateDto
import network.lapis.cloud.shared.domain.AiFeature
import network.lapis.cloud.shared.domain.AiKnowledgeEntryDto
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.StatuteAnswerDto
import network.lapis.cloud.shared.rpc.AiDocumentNotReleasableException
import network.lapis.cloud.shared.rpc.AiFeatureDisabledException
import network.lapis.cloud.shared.rpc.AiOptInMissingException
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IAiAssistantService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.6.1 -- see [IAiAssistantService]. Only registered when [AiConfig.isOperational]; the
 * [ensureEnabled] check on every method is the belt to that suspenders.
 *
 * **Authorization shape.**
 * - Member methods: session, organization-member status, own opt-in, rate limit.
 * - Knowledge-base administration: [isPrivileged] (BOARD/ADMIN) -- deliberately **not**
 *   `ESCALATED_ROLES` (which includes TREASURER): what the AI may read is a governance decision.
 *   Only `PUBLIC_MEMBERS` documents are releasable, because a `BOARD_ONLY`/`ADMIN_ONLY` text would
 *   otherwise leave the server towards an external provider as soon as a privileged member asks.
 * - **Welle-1 hardening beyond the retriever contract:** the levels handed to the retriever are the
 *   caller's readable levels INTERSECTED with [AI_READABLE_LEVELS] (`PUBLIC_MEMBERS` only), so even a
 *   document that was released and later re-classified upwards can never reach the provider through
 *   a privileged member's question.
 */
internal class AiAssistantService(
    private val call: ApplicationCall,
    private val config: AiConfig,
    private val pipeline: StatuteQaPipeline,
    private val indexer: KnowledgeIndexer,
    private val rateLimiter: AiQuestionRateLimiter,
) : IAiAssistantService {
    override suspend fun getAssistantState(): AiAssistantStateDto {
        val current = requireMember()
        return stateFor(current)
    }

    override suspend fun setMemberOptIn(
        feature: AiFeature,
        enabled: Boolean,
    ): AiAssistantStateDto {
        val current = requireMember()
        AiMemberOptInStore.set(memberId = current.memberId, feature = feature, enabled = enabled)
        return stateFor(current)
    }

    override suspend fun askStatuteQuestion(question: String): StatuteAnswerDto {
        val current = requireMember()
        if (!AiMemberOptInStore.isEnabled(
                memberId = current.memberId,
                feature = AiFeature.STATUTE_QA,
                default = config.memberOptInDefault,
            )
        ) {
            throw AiOptInMissingException()
        }
        // Validated BEFORE the rate limiter so a malformed question never burns quota.
        val length = question.trim().length
        if (length < config.minQuestionChars || length > config.maxQuestionChars) {
            throw BadRequestException(
                "Question length must be between ${config.minQuestionChars} and ${config.maxQuestionChars} characters",
            )
        }
        when (val decision = rateLimiter.checkAndRecord(current.memberId)) {
            RateLimitDecision.Allowed -> Unit
            is RateLimitDecision.MemberLimited ->
                return StatuteAnswerDto(outcome = AiAnswerOutcome.RATE_LIMITED, retryAfterSeconds = decision.retryAfterSeconds)
            is RateLimitDecision.ServerLimited ->
                return StatuteAnswerDto(outcome = AiAnswerOutcome.RATE_LIMITED, retryAfterSeconds = decision.retryAfterSeconds)
        }
        val levels = readableLevels(current)
        return when (val outcome = pipeline.answer(memberId = current.memberId, question = question, allowedLevels = levels)) {
            is QaOutcome.Answered ->
                StatuteAnswerDto(outcome = AiAnswerOutcome.ANSWERED, summary = outcome.summary, citations = outcome.citations)
            QaOutcome.NothingFound ->
                StatuteAnswerDto(
                    outcome = AiAnswerOutcome.NOTHING_FOUND,
                    searchedDocuments = KnowledgeReleaseStore.listScope(levels).indexed,
                )
            is QaOutcome.ProviderUnavailable -> StatuteAnswerDto(outcome = AiAnswerOutcome.PROVIDER_UNAVAILABLE)
        }
    }

    override suspend fun listKnowledgeEntries(): List<AiKnowledgeEntryDto> {
        val current = requireKnowledgeAdmin()
        return KnowledgeReleaseStore.listEntries(visibleLevels = documentLevels(current))
    }

    override suspend fun setKnowledgeBaseRelease(
        documentId: String,
        released: Boolean,
    ): AiKnowledgeEntryDto {
        val current = requireKnowledgeAdmin()
        val id = parseId(documentId)
        val level = requireVisibleLevel(current = current, id = id, documentId = documentId)
        if (released) {
            if (level != DocumentAccessLevel.PUBLIC_MEMBERS) throw AiDocumentNotReleasableException()
            KnowledgeReleaseStore.release(documentId = id, releasedBy = current.memberId)
            withContext(Dispatchers.IO) { indexer.indexDocument(id) }
        } else {
            KnowledgeReleaseStore.revoke(id)
        }
        return entryOrNotFound(current = current, id = id)
    }

    override suspend fun reindexKnowledgeDocument(documentId: String): AiKnowledgeEntryDto {
        val current = requireKnowledgeAdmin()
        val id = parseId(documentId)
        val level = requireVisibleLevel(current = current, id = id, documentId = documentId)
        if (level != DocumentAccessLevel.PUBLIC_MEMBERS) throw AiDocumentNotReleasableException()
        if (!KnowledgeReleaseStore.isReleased(id)) throw ConflictException("Document is not released to the knowledge base")
        withContext(Dispatchers.IO) { indexer.indexDocument(id) }
        return entryOrNotFound(current = current, id = id)
    }

    private fun ensureEnabled() {
        if (!config.isOperational) throw AiFeatureDisabledException()
    }

    /** Session + feature switch + organization-member status. */
    private fun requireMember(): CurrentMember {
        val current = resolveCurrentMember(call)
        ensureEnabled()
        if (current.status !in MemberStatusSets.ORGANIZATION_MEMBER) throw ForbiddenException()
        return current
    }

    private fun requireKnowledgeAdmin(): CurrentMember {
        val current = resolveCurrentMember(call)
        ensureEnabled()
        if (!current.isPrivileged) throw ForbiddenException()
        return current
    }

    private fun entryOrNotFound(
        current: CurrentMember,
        id: Uuid,
    ): AiKnowledgeEntryDto =
        KnowledgeReleaseStore.entryFor(documentId = id, visibleLevels = documentLevels(current))
            ?: throw NotFoundException("Document $id not found")

    /** Every document level the caller may see at all (title/classification included), not just the AI-readable ones. */
    private fun documentLevels(current: CurrentMember): List<DocumentAccessLevel> =
        DocumentAccessLevel.entries.filter { current.canAccessDocumentAtLevel(it) }

    /**
     * The document's level, but a document ABOVE the caller's level is reported as not found -- same
     * as a missing one -- so the endpoint neither leaks its existence nor its title.
     */
    private fun requireVisibleLevel(
        current: CurrentMember,
        id: Uuid,
        documentId: String,
    ): DocumentAccessLevel {
        val level = accessLevelOf(id)
        if (!current.canAccessDocumentAtLevel(level)) throw NotFoundException("Document $documentId not found")
        return level
    }

    private fun readableLevels(current: CurrentMember): List<DocumentAccessLevel> =
        DocumentAccessLevel.entries.filter { it in AI_READABLE_LEVELS && current.canAccessDocumentAtLevel(it) }

    private fun stateFor(current: CurrentMember): AiAssistantStateDto {
        val scope = KnowledgeReleaseStore.listScope(readableLevels(current))
        return AiAssistantStateDto(
            featureEnabled = true,
            optIn =
                AiMemberOptInStore.isEnabled(
                    memberId = current.memberId,
                    feature = AiFeature.STATUTE_QA,
                    default = config.memberOptInDefault,
                ),
            optInDefault = config.memberOptInDefault,
            minQuestionChars = config.minQuestionChars,
            maxQuestionChars = config.maxQuestionChars,
            indexedScope = scope.indexed,
            unindexedScope = scope.unindexed,
        )
    }

    private fun parseId(documentId: String): Uuid =
        runCatching {
            Uuid.parse(documentId)
        }.getOrElse { throw BadRequestException("Invalid document id") }

    private fun accessLevelOf(documentId: Uuid): DocumentAccessLevel =
        transaction {
            val row = DocumentTable.selectAll().where { DocumentTable.id eq documentId }.singleOrNull()
            if (row == null || row[DocumentTable.isDeleted]) throw NotFoundException("Document $documentId not found")
            row[DocumentTable.accessLevel]
        }

    private companion object {
        /** See class KDoc "Welle-1 hardening". */
        val AI_READABLE_LEVELS: Set<DocumentAccessLevel> = setOf(DocumentAccessLevel.PUBLIC_MEMBERS)
    }
}
