package network.lapis.cloud.server.ai.qa

import network.lapis.cloud.server.ai.audit.AiCallAuditEntry
import network.lapis.cloud.server.ai.audit.AiCallAuditSink
import network.lapis.cloud.server.ai.audit.AiCallOutcome
import network.lapis.cloud.server.ai.audit.sha256Hex
import network.lapis.cloud.server.ai.config.AiConfig
import network.lapis.cloud.server.ai.llm.LlmClient
import network.lapis.cloud.server.ai.llm.LlmFailureKind
import network.lapis.cloud.server.ai.llm.LlmRequest
import network.lapis.cloud.server.ai.llm.LlmResult
import network.lapis.cloud.server.ai.retrieval.KnowledgeRetriever
import network.lapis.cloud.server.ai.safety.PiiRedactor
import network.lapis.cloud.server.ai.safety.PlainTextSanitizer
import network.lapis.cloud.shared.domain.AiCitationDto
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.rpc.BadRequestException
import kotlin.uuid.Uuid

internal sealed interface QaOutcome {
    data class Answered(
        val summary: String,
        val citations: List<AiCitationDto>,
    ) : QaOutcome

    data object NothingFound : QaOutcome

    data class ProviderUnavailable(
        val kind: LlmFailureKind,
    ) : QaOutcome
}

/**
 * Statute Q&A: one deterministic retrieval, at most one tool-less model call, citations built from
 * stored records only. The model-facing path sees exactly three collaborators
 * ([KnowledgeRetriever], [LlmClient], [AiCallAuditSink]) -- no DB handle, no service (R1).
 *
 * Order is fixed: length check -> PII redaction (only the redacted text ever leaves) -> exactly one
 * retrieval -> **no chunks means no model call at all** (saves cost and structurally rules out an
 * unsupported summary) -> prompt -> model call -> parse -> citation validation against the retrieved
 * set -> **no valid citation means no answer** -> plain-text sanitizing -> audit (always).
 */
internal class StatuteQaPipeline(
    private val retriever: KnowledgeRetriever,
    private val llmClient: LlmClient,
    private val auditSink: AiCallAuditSink,
    private val config: AiConfig,
) {
    suspend fun answer(
        memberId: Uuid,
        question: String,
        allowedLevels: List<DocumentAccessLevel>,
    ): QaOutcome {
        val trimmed = question.trim()
        if (trimmed.length < config.minQuestionChars || trimmed.length > config.maxQuestionChars) {
            throw BadRequestException(
                "Question length must be between ${config.minQuestionChars} and ${config.maxQuestionChars} characters",
            )
        }
        val redacted = PiiRedactor.redact(trimmed).text
        val inputHash = sha256Hex(redacted)

        val budget = ToolCallBudget(max = minOf(config.maxToolCalls, AiConfig.HARD_MAX_TOOL_CALLS))
        budget.consume(AiTool.KNOWLEDGE_SEARCH)
        val retrieved = retriever.search(query = redacted, allowedLevels = allowedLevels, topK = config.topK)

        fun audit(
            outcome: AiCallOutcome,
            outputHash: String? = null,
            tokensIn: Int? = null,
            tokensOut: Int? = null,
        ) = auditSink.record(
            AiCallAuditEntry(
                memberId = memberId,
                agentType = AGENT_TYPE,
                provider = config.provider?.auditName ?: "none",
                model = config.model ?: "none",
                inputHash = inputHash,
                outputHash = outputHash,
                tokensIn = tokensIn,
                tokensOut = tokensOut,
                toolsCalled = listOf(AiTool.KNOWLEDGE_SEARCH.name),
                outcome = outcome,
                retrievedChunkCount = retrieved.size,
            ),
        )

        if (retrieved.isEmpty()) {
            audit(AiCallOutcome.NOTHING_FOUND)
            return QaOutcome.NothingFound
        }

        val prompt = StatuteQaPrompt.build(question = redacted, chunks = retrieved, maxPromptChars = config.maxPromptChars)
        if (prompt.includedChunks.isEmpty()) {
            audit(AiCallOutcome.NOTHING_FOUND)
            return QaOutcome.NothingFound
        }

        val result =
            llmClient.complete(
                LlmRequest(
                    systemPrompt = StatuteQaPrompt.SYSTEM_PROMPT,
                    userContent = prompt.userContent,
                    maxOutputTokens = config.maxOutputTokens,
                ),
            )
        val success =
            when (result) {
                is LlmResult.Failure -> {
                    audit(AiCallOutcome.PROVIDER_ERROR)
                    return QaOutcome.ProviderUnavailable(kind = result.kind)
                }
                is LlmResult.Success -> result
            }
        val outputHash = sha256Hex(success.text)

        val parsed = ModelAnswerParser.parse(success.text)
        val cited = parsed as? ParsedModelAnswer.Cited
        val citations =
            cited
                ?.let {
                    CitationValidator.validate(
                        sources = it.sources,
                        chunks = prompt.includedChunks,
                        maxCitations = config.maxCitations,
                        maxExcerptChars = config.maxExcerptChars,
                    )
                }.orEmpty()
        val summary = cited?.let { PlainTextSanitizer.sanitize(text = it.summary, maxChars = MAX_SUMMARY_CHARS) }.orEmpty()

        if (citations.isEmpty() || summary.isBlank()) {
            audit(AiCallOutcome.NO_VALID_CITATION, outputHash = outputHash, tokensIn = success.tokensIn, tokensOut = success.tokensOut)
            return QaOutcome.NothingFound
        }
        audit(AiCallOutcome.ANSWERED, outputHash = outputHash, tokensIn = success.tokensIn, tokensOut = success.tokensOut)
        return QaOutcome.Answered(summary = summary, citations = citations)
    }

    private companion object {
        const val AGENT_TYPE = "STATUTE_QA"
        const val MAX_SUMMARY_CHARS = 1_200
    }
}
