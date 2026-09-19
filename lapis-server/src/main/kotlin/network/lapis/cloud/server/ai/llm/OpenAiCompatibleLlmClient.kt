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

/**
 * OpenAI-compatible Chat Completions (`POST {baseUrl}/v1/chat/completions`) -- covers OpenAI,
 * Mistral, OpenRouter and Ollama's compatible endpoint. One tool-less completion, see [LlmClient].
 */
internal class OpenAiCompatibleLlmClient(
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
                    put(
                        "messages",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("role", "system")
                                    put("content", request.systemPrompt)
                                },
                            )
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
                    url = "$baseUrl/v1/chat/completions",
                    pinnedBaseUrl = baseUrl,
                    headers = mapOf("Authorization" to "Bearer $apiKey"),
                    jsonBody = body.toString(),
                    maxBytes = maxResponseBytes,
                )
            statusFailureOrNull(response.status)?.let { return@guardedLlmCall it }
            val bytes = response.body ?: return@guardedLlmCall LlmResult.Failure(kind = LlmFailureKind.RESPONSE_TOO_LARGE)
            parseOpenAiResponse(bytes.decodeToString())
        }

    internal companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Extracted for direct testing: pure parsing, no I/O. */
        fun parseOpenAiResponse(raw: String): LlmResult {
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return malformed()
            val choices = root["choices"] as? JsonArray ?: return malformed()
            val message = (choices.firstOrNull() as? JsonObject)?.get("message") as? JsonObject ?: return malformed()
            val text =
                (message["content"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?.trim()
                    .orEmpty()
            if (text.isEmpty()) return malformed()
            val usage = root["usage"] as? JsonObject
            return LlmResult.Success(
                text = text,
                tokensIn = (usage?.get("prompt_tokens") as? JsonPrimitive)?.intOrNull,
                tokensOut = (usage?.get("completion_tokens") as? JsonPrimitive)?.intOrNull,
            )
        }

        private fun malformed() = LlmResult.Failure(kind = LlmFailureKind.MALFORMED_RESPONSE)
    }
}
