package network.lapis.cloud.server.ai.config

/**
 * Shared, pure parsing of an LLM provider profile (provider / model / API key / base URL) from
 * environment values. Used by [AiConfig] (`LAPIS_AI_*`) and by the translation configuration
 * (`LAPIS_TRANSLATE_*`); the environment variable NAME is a parameter so the `invalid` list names the
 * right variable. Behaviour is byte-identical to the former private functions of [AiConfig].
 */
internal object ProviderProfileParsing {
    const val DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com"
    private const val MAX_MODEL_LENGTH = 120

    fun parseProvider(
        raw: String?,
        envName: String,
        invalid: MutableList<String>,
    ): AiProviderKind? {
        if (raw.isNullOrEmpty()) {
            invalid += envName
            return null
        }
        val normalized = raw.uppercase().replace('-', '_')
        val parsed = AiProviderKind.entries.firstOrNull { it.name == normalized }
        if (parsed == null) invalid += envName
        return parsed
    }

    fun parseModel(
        raw: String?,
        envName: String,
        invalid: MutableList<String>,
    ): String? {
        if (raw.isNullOrEmpty()) {
            invalid += envName
            return null
        }
        if (raw.length > MAX_MODEL_LENGTH || raw.any { it.code < 0x20 || it.code == 0x7f }) {
            invalid += envName
            return null
        }
        return raw
    }

    fun parseApiKey(
        raw: String?,
        envName: String,
        invalid: MutableList<String>,
    ): String? {
        if (raw.isNullOrEmpty()) {
            invalid += envName
            return null
        }
        // A header value containing a control character would be a header-injection vector.
        if (raw.any { it.code < 0x20 || it.code == 0x7f }) {
            invalid += envName
            return null
        }
        return raw
    }

    fun resolveBaseUrl(
        provider: AiProviderKind?,
        raw: String?,
        allowPlaintext: Boolean,
        envName: String,
        invalid: MutableList<String>,
    ): String? {
        val candidate =
            when {
                !raw.isNullOrEmpty() -> raw
                provider == AiProviderKind.ANTHROPIC -> DEFAULT_ANTHROPIC_BASE_URL
                else -> {
                    // OPENAI_COMPATIBLE has no unambiguous default host, and an unknown provider
                    // has no profile at all: the variable is required in both cases.
                    if (provider != null) invalid += envName
                    return null
                }
            }
        val validated = AiBaseUrlGuard.validateOrNull(raw = candidate, allowPlaintextLoopback = allowPlaintext)
        if (validated == null) invalid += envName
        return validated
    }
}
