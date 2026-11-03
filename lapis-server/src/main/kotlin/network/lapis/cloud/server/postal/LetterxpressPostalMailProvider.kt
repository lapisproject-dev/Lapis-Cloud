package network.lapis.cloud.server.postal

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.config.SecretEnv
import java.util.Base64

/**
 * ## !! WIRE FORMAT NOT VERIFIED AGAINST LIVE/CURRENT LETTERXPRESS DOCUMENTATION !!
 *
 * This sandbox has no network egress to `letterxpress.de` or its API (egress is allow-listed to a
 * small set of package registries only) -- the request/response JSON shape implemented here
 * ([LetterxpressJobRequest]/[LetterxpressJobResponse], the `setJob`-style endpoint path, the
 * `auth`/`letter` envelope, field names like `base64_file`/`recipient_zip`/`final`) is a
 * best-effort reconstruction from general knowledge of how the Letterxpress API is documented to
 * work, **not** something fetched or checked against their current live API reference.
 *
 * **A human MUST verify every wire-level detail below against the real, current Letterxpress API
 * documentation before this provider is used against the real Letterxpress service**: exact
 * endpoint path(s), exact JSON field names/casing, the authentication envelope shape, whether
 * `base64_file` is really base64-encoded PDF bytes or expects a different encoding/field name, the
 * exact shape of a success/error response body, and the exact sandbox/dry-run signal name (assumed
 * here to be a boolean `final` field on the letter payload -- `false` unless
 * [liveMode]/`LAPIS_LETTERXPRESS_LIVE_MODE` says otherwise). Same disclosed-uncertainty discipline
 * as `network.lapis.cloud.server.pdf.SpendenbescheinigungPdfGenerator`'s own unverified legal
 * wording (V0.4.1) -- ship the shape, flag it loudly, let a human check it before production use.
 *
 * ## Sandbox/live mode
 *
 * [liveMode] defaults to `false` (from `LAPIS_LETTERXPRESS_LIVE_MODE`, absent/unparseable ->
 * `false`) -- every dispatch is sent with the (assumed) dry-run indicator unless a deployment
 * explicitly opts into live/production dispatch. This is deliberate: it must never be possible to
 * accidentally mail a real physical letter during local development or automated testing.
 *
 * ## Credentials
 *
 * [username]/[apiKey] default from `LAPIS_LETTERXPRESS_USERNAME`/`LAPIS_LETTERXPRESS_APIKEY` (same
 * `LAPIS_`-prefixed plain-environment-variable idiom as `network.lapis.cloud.server.db
 * .DatabaseConfig`) -- **never** logged, never echoed into a [PostalDispatchOutcome.Failed]
 * message, never committed anywhere. A blank/missing credential short-circuits to
 * [PostalDispatchOutcome.Failed] before any HTTP call is attempted.
 *
 * Read as constructor-parameter defaults rather than inline `System.getenv` calls inside
 * [dispatchLetter] deliberately -- `System.getenv` cannot be mutated per-JVM-test-run, so reading
 * it inline would make [liveMode]/credentials effectively untestable without reflection hacks.
 * Production code's env-var idiom stays identical (`LetterxpressPostalMailProvider()` picks up the
 * real environment), while `LetterxpressPostalMailProviderTest` constructs instances with explicit
 * test values and a `MockEngine`-backed [httpClient] instead.
 *
 * ## Error handling
 *
 * Every failure path (missing credentials, non-2xx HTTP status, an unparseable response body, a
 * network-level exception) is caught and mapped to [PostalDispatchOutcome.Failed] with a short,
 * generic/templated [PostalDispatchOutcome.Failed.sanitizedErrorMessage] -- never the raw
 * exception message or raw response body verbatim. This discipline holds even though credentials
 * travel in the request body rather than a log-visible URL: the rule is "never trust that a
 * third-party error payload or a JVM exception's `toString()` cannot contain something sensitive",
 * not "only guard against the one known leak vector".
 */
class LetterxpressPostalMailProvider(
    private val username: String? = SecretEnv.get("LAPIS_LETTERXPRESS_USERNAME"),
    private val apiKey: String? = SecretEnv.get("LAPIS_LETTERXPRESS_APIKEY"),
    private val liveMode: Boolean = SecretEnv.get("LAPIS_LETTERXPRESS_LIVE_MODE")?.toBoolean() ?: false,
    private val baseUrl: String = SecretEnv.get("LAPIS_LETTERXPRESS_BASE_URL") ?: "https://api.letterxpress.de/v1",
    private val httpClient: HttpClient = defaultLetterxpressHttpClient(),
) : PostalMailProvider {
    override suspend fun dispatchLetter(
        pdfBytes: ByteArray,
        recipientName: String,
        recipientStreet: String,
        recipientPostalCode: String,
        recipientCity: String,
        recipientCountry: String,
    ): PostalDispatchOutcome {
        val user = username
        val key = apiKey
        if (user.isNullOrBlank() || key.isNullOrBlank()) {
            // Never echo the (absent) credential values themselves.
            return PostalDispatchOutcome.Failed(
                "Letterxpress credentials (LAPIS_LETTERXPRESS_USERNAME/LAPIS_LETTERXPRESS_APIKEY) are not configured",
            )
        }

        return try {
            val response =
                httpClient.post("$baseUrl/setJob") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        LetterxpressJobRequest(
                            auth = LetterxpressAuth(username = user, apiKey = key),
                            letter =
                                LetterxpressLetterPayload(
                                    base64File = Base64.getEncoder().encodeToString(pdfBytes),
                                    recipientName = recipientName,
                                    recipientStreet = recipientStreet,
                                    recipientZip = recipientPostalCode,
                                    recipientCity = recipientCity,
                                    recipientCountry = recipientCountry,
                                    final = liveMode,
                                ),
                        ),
                    )
                }
            parseResponse(response)
        } catch (e: Exception) {
            // Catches connect failures, timeouts, serialization errors, and anything else --
            // never let a raw exception message (which could in principle embed request/response
            // internals) reach the caller/log/DB verbatim. See class KDoc "Error handling".
            PostalDispatchOutcome.Failed("Letterxpress dispatch failed (${e::class.simpleName ?: "unknown error"})")
        }
    }

    private suspend fun parseResponse(response: HttpResponse): PostalDispatchOutcome {
        if (!response.status.isSuccess()) {
            return PostalDispatchOutcome.Failed("Letterxpress returned HTTP ${response.status.value}")
        }
        return try {
            val body = response.body<LetterxpressJobResponse>()
            val jobId = body.data?.jobId
            if (body.status == "success" && !jobId.isNullOrBlank()) {
                PostalDispatchOutcome.Dispatched(jobId)
            } else {
                PostalDispatchOutcome.Failed("Letterxpress reported a non-success response (status=${body.status ?: "unknown"})")
            }
        } catch (e: Exception) {
            PostalDispatchOutcome.Failed("Letterxpress returned an unparseable response body")
        }
    }
}

private fun defaultLetterxpressHttpClient(): HttpClient =
    HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                },
            )
        }
    }

// ── Request/response wire shapes -- see class KDoc, NOT verified against live Letterxpress docs ──

@Serializable
private data class LetterxpressAuth(
    val username: String,
    @SerialName("apikey") val apiKey: String,
)

@Serializable
private data class LetterxpressLetterPayload(
    @SerialName("base64_file") val base64File: String,
    @SerialName("recipient_name") val recipientName: String,
    @SerialName("recipient_street") val recipientStreet: String,
    @SerialName("recipient_zip") val recipientZip: String,
    @SerialName("recipient_city") val recipientCity: String,
    @SerialName("recipient_country") val recipientCountry: String,
    /** Assumed sandbox/dry-run indicator -- see class KDoc "Sandbox/live mode". */
    val final: Boolean,
)

@Serializable
private data class LetterxpressJobRequest(
    val auth: LetterxpressAuth,
    val letter: LetterxpressLetterPayload,
)

@Serializable
private data class LetterxpressJobResponseData(
    @SerialName("job_id") val jobId: String? = null,
)

@Serializable
private data class LetterxpressJobResponse(
    val status: String? = null,
    val data: LetterxpressJobResponseData? = null,
)
