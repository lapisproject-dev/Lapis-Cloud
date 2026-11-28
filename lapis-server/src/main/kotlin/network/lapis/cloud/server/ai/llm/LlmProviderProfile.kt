package network.lapis.cloud.server.ai.llm

import network.lapis.cloud.server.ai.config.AiProviderKind

/**
 * Everything [AiProviderFactory] needs to build an [LlmClient], independent of which feature
 * (statute Q&A or content translation) owns the configuration. The API key is exposed only through
 * [apiKey] and never appears in [toString].
 */
internal class LlmProviderProfile(
    val provider: AiProviderKind,
    val model: String,
    private val apiKeyValue: String,
    val baseUrl: String,
    val connectTimeoutMs: Long,
    val requestTimeoutMs: Long,
    val maxResponseBytes: Int,
) {
    val apiKey: String get() = apiKeyValue

    override fun toString(): String = "LlmProviderProfile(provider=$provider, model=$model, baseUrl=$baseUrl, apiKey=***)"
}
