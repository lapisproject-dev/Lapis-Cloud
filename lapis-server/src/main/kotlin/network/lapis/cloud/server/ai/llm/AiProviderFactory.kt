package network.lapis.cloud.server.ai.llm

import network.lapis.cloud.server.ai.config.AiConfig
import network.lapis.cloud.server.ai.config.AiProviderKind

/** Builds the process-wide [LlmClient] -- exactly once, and **only** when the feature is operational. */
internal object AiProviderFactory {
    /**
     * `null` when [AiConfig.isOperational] is `false`: no HTTP client, no provider class is even
     * constructed while the feature is off ("loads no provider").
     */
    fun create(config: AiConfig): LlmClient? = config.providerProfileOrNull()?.let(::create)

    /** Builds the client for an already validated [profile]. */
    fun create(profile: LlmProviderProfile): LlmClient {
        val http = aiHttpClient(connectTimeoutMs = profile.connectTimeoutMs, requestTimeoutMs = profile.requestTimeoutMs)
        return when (profile.provider) {
            AiProviderKind.ANTHROPIC ->
                AnthropicMessagesLlmClient(
                    http = http,
                    baseUrl = profile.baseUrl,
                    model = profile.model,
                    apiKey = profile.apiKey,
                    maxResponseBytes = profile.maxResponseBytes,
                )
            AiProviderKind.OPENAI_COMPATIBLE ->
                OpenAiCompatibleLlmClient(
                    http = http,
                    baseUrl = profile.baseUrl,
                    model = profile.model,
                    apiKey = profile.apiKey,
                    maxResponseBytes = profile.maxResponseBytes,
                )
        }
    }
}
