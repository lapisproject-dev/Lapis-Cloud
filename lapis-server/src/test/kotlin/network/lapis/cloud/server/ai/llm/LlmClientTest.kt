package network.lapis.cloud.server.ai.llm

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

private const val KEY = "sk-secret-test-key"
private val request = LlmRequest(systemPrompt = "SYS", userContent = "USER", maxOutputTokens = 321)
private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

private fun mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
    HttpClient(MockEngine(handler)) {
        expectSuccess =
            false
    }

private fun anthropic(
    client: HttpClient,
    maxBytes: Int = 256 * 1024,
) = AnthropicMessagesLlmClient(
    http = client,
    baseUrl = "https://api.anthropic.com",
    model = "m-1",
    apiKey = KEY,
    maxResponseBytes = maxBytes,
)

private fun openAi(
    client: HttpClient,
    maxBytes: Int = 256 * 1024,
) = OpenAiCompatibleLlmClient(
    http = client,
    baseUrl = "https://gateway.example.eu/api",
    model = "m-2",
    apiKey = KEY,
    maxResponseBytes = maxBytes,
)

class LlmClientTest :
    FunSpec({
        test("anthropic: request shape -- URL, headers, body, and no tool definition") {
            var seen: HttpRequestData? = null
            var body = ""
            val client =
                mockClient { req ->
                    seen = req
                    body = req.body.toByteArray().decodeToString()
                    respond(
                        """{"content":[{"type":"text","text":"Antwort"}],"usage":{"input_tokens":11,"output_tokens":7}}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
            val result = anthropic(client = client).complete(request)
            result shouldBe LlmResult.Success(text = "Antwort", tokensIn = 11, tokensOut = 7)
            seen!!.url.toString() shouldBe "https://api.anthropic.com/v1/messages"
            seen!!.headers["x-api-key"] shouldBe KEY
            seen!!.headers["anthropic-version"] shouldBe "2023-06-01"
            val json = Json.parseToJsonElement(body).jsonObject
            json["model"]!!.jsonPrimitive.content shouldBe "m-1"
            json["max_tokens"]!!.jsonPrimitive.content shouldBe "321"
            json["system"]!!.jsonPrimitive.content shouldBe "SYS"
            json["messages"]!!
                .jsonArray
                .single()
                .jsonObject["content"]!!
                .jsonPrimitive.content shouldBe "USER"
            (json.containsKey("tools")) shouldBe false
        }

        test("openai-compatible: request shape -- URL under the pinned base, bearer header, system+user messages") {
            var seen: HttpRequestData? = null
            var body = ""
            val client =
                mockClient { req ->
                    seen = req
                    body = req.body.toByteArray().decodeToString()
                    respond(
                        """{"choices":[{"message":{"content":"Antwort"}}],"usage":{"prompt_tokens":5,"completion_tokens":3}}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }
            val result = openAi(client = client).complete(request)
            result shouldBe LlmResult.Success(text = "Antwort", tokensIn = 5, tokensOut = 3)
            seen!!.url.toString() shouldBe "https://gateway.example.eu/api/v1/chat/completions"
            seen!!.headers["Authorization"] shouldBe "Bearer $KEY"
            val messages = Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray
            messages[0].jsonObject["role"]!!.jsonPrimitive.content shouldBe "system"
            messages[1].jsonObject["role"]!!.jsonPrimitive.content shouldBe "user"
            (Json.parseToJsonElement(body).jsonObject.containsKey("tools")) shouldBe false
        }

        test("status codes map to failure kinds without carrying provider text") {
            listOf(
                HttpStatusCode.TooManyRequests to LlmFailureKind.UPSTREAM_RATE_LIMITED,
                HttpStatusCode.InternalServerError to LlmFailureKind.UPSTREAM_ERROR,
                HttpStatusCode.Unauthorized to LlmFailureKind.UPSTREAM_ERROR,
                HttpStatusCode.Found to LlmFailureKind.UPSTREAM_ERROR, // redirects are never followed
            ).forEach { (status, kind) ->
                val client = mockClient { respond("""{"error":"secret provider detail"}""", status, jsonHeaders) }
                anthropic(client = client).complete(request) shouldBe LlmResult.Failure(kind = kind)
                openAi(client = client).complete(request) shouldBe LlmResult.Failure(kind = kind)
            }
        }

        test("malformed or empty bodies are MALFORMED_RESPONSE") {
            val bodies =
                listOf("not json", "{}", """{"content":[]}""", """{"choices":[]}""", """{"choices":[{"message":{"content":""}}]}""")
            bodies.forEach { body ->
                val client = mockClient { respond(body, HttpStatusCode.OK, jsonHeaders) }
                anthropic(client = client).complete(request).shouldBeInstanceOf<LlmResult.Failure>().kind shouldBe
                    LlmFailureKind.MALFORMED_RESPONSE
                openAi(client = client).complete(request).shouldBeInstanceOf<LlmResult.Failure>().kind shouldBe
                    LlmFailureKind.MALFORMED_RESPONSE
            }
        }

        test("a response above the byte cap is RESPONSE_TOO_LARGE") {
            val big = """{"content":[{"type":"text","text":"${"x".repeat(5_000)}"}]}"""
            val client = mockClient { respond(big, HttpStatusCode.OK, jsonHeaders) }
            anthropic(client = client, maxBytes = 1_000).complete(request) shouldBe
                LlmResult.Failure(kind = LlmFailureKind.RESPONSE_TOO_LARGE)
        }

        test("a transport exception becomes TRANSPORT and its message is dropped") {
            val client = mockClient { throw IOException("connect to https://api.anthropic.com failed with key $KEY") }
            val failure = anthropic(client = client).complete(request)
            failure shouldBe LlmResult.Failure(kind = LlmFailureKind.TRANSPORT)
            failure.toString() shouldNotContain KEY
        }

        test("a request timeout becomes TIMEOUT") {
            val client = mockClient { throw HttpRequestTimeoutException("https://api.anthropic.com/v1/messages", 1_000L) }
            anthropic(client = client).complete(request) shouldBe LlmResult.Failure(kind = LlmFailureKind.TIMEOUT)
        }

        test("the parsers concatenate text blocks and ignore non-text blocks") {
            val result =
                AnthropicMessagesLlmClient.parseAnthropicResponse(
                    """{"content":[{"type":"thinking","text":"x"},{"type":"text","text":"a"},{"type":"text","text":"b"}]}""",
                )
            result.shouldBeInstanceOf<LlmResult.Success>().text shouldBe "a\nb"
        }
    })
