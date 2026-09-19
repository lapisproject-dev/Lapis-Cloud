package network.lapis.cloud.server.ai.llm

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Anthropic Messages API (`POST {baseUrl}/v1/messages`). One tool-less completion, see [LlmClient]. */
internal class AnthropicMessagesLlmClient(
    private val http: HttpClient,
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String,
    private val maxResponseBytes: Int,
) : LlmClient {
    override suspend fun complete(request: LlmRequest): LlmResult =
        guardedLlmCall {
            val body =
                buildJsonObject {
                    put("model", model)
                    put("max_tokens", request.maxOutputTokens)
                    put("system", request.systemPrompt)
                    put(
                        "messages",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("role", "user")
                                    put("content", request.userContent)
                                },
                            )
                        },
                    )
                }
            val response =
                http.postJsonCapped(
                    url = "$baseUrl/v1/messages",
                    pinnedBaseUrl = baseUrl,
                    headers = mapOf("x-api-key" to apiKey, "anthropic-version" to ANTHROPIC_VERSION),
                    jsonBody = body.toString(),
                    maxBytes = maxResponseBytes,
                )
            statusFailureOrNull(response.status)?.let { return@guardedLlmCall it }
            val bytes = response.body ?: return@guardedLlmCall LlmResult.Failure(kind = LlmFailureKind.RESPONSE_TOO_LARGE)
            parseAnthropicResponse(bytes.decodeToString())
        }

    internal companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        private val json = Json { ignoreUnknownKeys = true }

        /** Extracted for direct testing: pure parsing, no I/O. */
        fun parseAnthropicResponse(raw: String): LlmResult {
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return malformed()
            val content = root["content"] as? JsonArray ?: return malformed()
            val text =
                content
                    .mapNotNull { it as? JsonObject }
                    .filter { (it["type"] as? JsonPrimitive)?.content == "text" }
                    .joinToString(separator = "\n") { (it["text"] as? JsonPrimitive)?.content.orEmpty() }
                    .trim()
            if (text.isEmpty()) return malformed()
            val usage = root["usage"] as? JsonObject
            return LlmResult.Success(
                text = text,
                tokensIn = (usage?.get("input_tokens") as? JsonPrimitive)?.intOrNull,
                tokensOut = (usage?.get("output_tokens") as? JsonPrimitive)?.intOrNull,
            )
        }

        private fun malformed() = LlmResult.Failure(kind = LlmFailureKind.MALFORMED_RESPONSE)
    }
}
