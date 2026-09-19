package network.lapis.cloud.server.ai.llm

/**
 * Provider-independent, deliberately tiny model interface: one tool-less completion. There is no
 * agent loop, no tool definition, no streaming -- the caller (`StatuteQaPipeline`) does retrieval
 * itself and hands the model plain text.
 */
internal interface LlmClient {
    /** One completion. **Never throws** -- every failure becomes [LlmResult.Failure]. */
    suspend fun complete(request: LlmRequest): LlmResult
}

internal data class LlmRequest(
    val systemPrompt: String,
    val userContent: String,
    val maxOutputTokens: Int,
)

internal sealed interface LlmResult {
    data class Success(
        val text: String,
        val tokensIn: Int?,
        val tokensOut: Int?,
    ) : LlmResult

    /**
     * Carries **no provider text** by design: neither a provider name, a model name nor an error
     * message can reach the UI or a log through this type.
     */
    data class Failure(
        val kind: LlmFailureKind,
    ) : LlmResult
}

internal enum class LlmFailureKind {
    TIMEOUT,
    UPSTREAM_RATE_LIMITED,
    UPSTREAM_ERROR,
    RESPONSE_TOO_LARGE,
    MALFORMED_RESPONSE,
    TRANSPORT,
}
