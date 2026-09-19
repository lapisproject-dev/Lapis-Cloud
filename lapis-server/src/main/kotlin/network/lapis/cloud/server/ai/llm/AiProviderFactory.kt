package network.lapis.cloud.server.ai.llm

import network.lapis.cloud.server.ai.config.AiConfig
import network.lapis.cloud.server.ai.config.AiProviderKind

/** Builds the process-wide [LlmClient] -- exactly once, and **only** when the feature is operational. */
internal object AiProviderFactory {
    /**
     * `null` when [AiConfig.isOperational] is `false`: no HTTP client, no provider class is even
     * constructed while the feature is off ("loads no provider").
     */
    fun create(config: AiConfig): LlmClient? {
        if (!config.isOperational) return null
        val baseUrl = config.baseUrl ?: return null
        val model = config.model ?: return null
        val apiKey = config.apiKey ?: return null
        val http = aiHttpClient(config)
        return when (config.provider) {
            AiProviderKind.ANTHROPIC ->
                AnthropicMessagesLlmClient(
                    http = http,
                    baseUrl = baseUrl,
                    model = model,
                    apiKey = apiKey,
                    maxResponseBytes = config.maxResponseBytes,
                )
            AiProviderKind.OPENAI_COMPATIBLE ->
                OpenAiCompatibleLlmClient(
                    http = http,
                    baseUrl = baseUrl,
                    model = model,
                    apiKey = apiKey,
                    maxResponseBytes = config.maxResponseBytes,
                )
            null -> null
        }
    }
}
