package network.lapis.cloud.server.ai.config

/** Wire dialect of the configured LLM provider. `OPENAI_COMPATIBLE` covers OpenAI, Mistral, OpenRouter and Ollama's compatible endpoint. */
internal enum class AiProviderKind {
    ANTHROPIC,
    OPENAI_COMPATIBLE,
    ;

    /** Short, stable, log-/audit-safe name (never a model name or URL). */
    val auditName: String get() = name.lowercase()
}
