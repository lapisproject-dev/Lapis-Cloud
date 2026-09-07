package network.lapis.cloud.server.accounting.export.lexoffice

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.serializer
import network.lapis.cloud.server.accounting.export.CategoryListOutcome
import network.lapis.cloud.server.accounting.export.ConnectionTestOutcome
import network.lapis.cloud.server.accounting.export.ExternalCategory
import network.lapis.cloud.server.accounting.export.OutboundVoucher
import network.lapis.cloud.server.accounting.export.VoucherPushOutcome
import network.lapis.cloud.shared.domain.AccountingExportDirection
import java.io.IOException
import java.math.RoundingMode
import java.net.ConnectException
import java.net.UnknownHostException
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/** Hard cap on how many bytes of a lexoffice response body are ever read into memory -- same
 * "bounded read, discard rather than partially parse" idiom
 * [network.lapis.cloud.server.payment.psp.StripeCheckoutClient]'s own `readCappedStripeBody`
 * establishes. */
private const val MAX_LEXOFFICE_RESPONSE_BYTES = 64 * 1024

/** Live since December 2025 (see the adoc "Verified API facts") -- deliberately a hardcoded
 * constant, NOT an environment-configurable value (see `AccountingExportConfig` KDoc "no
 * user/operator-configurable target URL" -- the strongest available posture against SSRF: there is
 * no input anywhere that ever reaches this string). Overridable ONLY as a constructor parameter,
 * for tests (`MockEngine`). */
internal const val LEXOFFICE_API_BASE_URL = "https://api.lexware.io"

/**
 * Welle V1.4.5.3 "lexoffice-Live-Anbindung" -- the ONLY place this codebase makes outbound HTTP to
 * lexoffice. Same hardened shape [network.lapis.cloud.server.payment.psp.StripeCheckoutClient]
 * establishes: `followRedirects = false`, `expectSuccess = false` (every call site inspects the
 * status itself), hard [HttpTimeout], bounded response read, and deliberately NO
 * `ContentNegotiation`/`Logging` Ktor plugin -- a request-logging plugin would risk the
 * `Authorization: Bearer <token>` header reaching a log line, and responses are decoded manually
 * via [LEXOFFICE_JSON] after [readCappedLexofficeBody] instead.
 *
 * [rateLimiter] MUST be shared (module-scoped, constructed once by
 * [network.lapis.cloud.server.Application.module]) across every caller of this class, and every
 * public method here calls [network.lapis.cloud.server.accounting.export.lexoffice
 * .LexofficeRateLimiter.acquire] as the FIRST thing it does, before the actual HTTP call -- see that
 * class' own KDoc for why a per-call instance would defeat the whole point.
 *
 * **Classification** (mirrors [network.lapis.cloud.server.webhook.WebhookDeliveryPoller]'s own
 * "Klassifikation" KDoc shape): 2xx with a body -> success (except [createVoucher], see that
 * method's own KDoc for why a MISSING `id` in an otherwise-2xx body is `Indeterminate`, not
 * success). 429 -> [VoucherPushOutcome.Retryable] (`Retry-After` header read when present). 503 ->
 * `Retryable` too -- both signal the request was NOT processed (rate limit / service temporarily
 * unable to accept work), never that it was accepted-then-failed. Every OTHER 5xx (500/502/504/...)
 * -> [VoucherPushOutcome.Indeterminate] with `errorCode = "RESPONSE_LOST"` -- see
 * "Security review Fund 2026-09-07 (Runde 5)" on [createVoucher] below for why. 401/403 ->
 * [VoucherPushOutcome.Rejected] with `errorCode = "UNAUTHORIZED"` (the caller,
 * `AccountingExportPoller`/`AccountingExportService`, reacts to that specific code by marking the
 * connection's `last_tested_at` `NULL` -- see `AccountingExportStore.markTestDue`). Every other 4xx
 * (400/404/405/406/409/415) -> `Rejected`. A network [IOException] BEFORE any response is read ->
 * `Retryable`; one that occurs mid-read AFTER a request has already reached lexoffice is
 * indistinguishable from "sent successfully but the response was lost" from this client's own
 * vantage point -- see [createVoucher] KDoc for why THAT specific case is `Indeterminate`, never
 * `Retryable`, for the send path only (a lost profile/category-list response has no persistence side
 * effect worth worrying about, so those two methods keep the simpler `Failure` shape).
 *
 * Security review Fund 2026-09-07 (Runde 4, Befund 1): [createVoucher]'s own pre-send [IOException]
 * handler used to classify EVERY [IOException] from `httpClient.post` as `Retryable`, including
 * [io.ktor.client.plugins.HttpRequestTimeoutException] (the `requestTimeoutMillis` budget, which
 * spans the FULL round trip, not just connection setup) and [java.net.SocketTimeoutException]
 * (`socketTimeoutMillis`, which can elapse while waiting for the response after the request body
 * was already fully written). Both extend [IOException] but are exactly as ambiguous as the
 * post-send read failure below -- the request may well have reached lexoffice, `POST /v1/vouchers`
 * has no idempotency key, and a `Retryable` classification feeds straight into
 * `AccountingExportStore.markRetryScheduled`, resending the SAME voucher. Only [ConnectException]
 * (which [io.ktor.client.network.sockets.ConnectTimeoutException] itself extends) and
 * [UnknownHostException] are safe as `Retryable` here -- both fail during connection setup, before
 * a single byte of the request body was ever written, so lexoffice never saw the request at all.
 */
internal class LexofficeApiClient(
    private val rateLimiter: LexofficeRateLimiter,
    private val httpClient: HttpClient = defaultLexofficeHttpClient(),
    private val baseUrl: String = LEXOFFICE_API_BASE_URL,
) {
    suspend fun getProfile(token: String): ConnectionTestOutcome {
        rateLimiter.acquire()
        val response =
            try {
                httpClient.get("$baseUrl/v1/profile") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(HttpHeaders.Accept, "application/json")
                }
            } catch (e: IOException) {
                logger.warn(e) { "LexofficeApiClient: network failure calling GET /v1/profile" }
                return ConnectionTestOutcome.Failure(errorCode = "NETWORK_ERROR", message = "Netzwerkfehler beim Aufruf von Lexware Office")
            }
        val bodyBytes =
            try {
                response.readCappedLexofficeBody()
            } catch (e: IOException) {
                // Same post-send read failure [createVoucher] guards against -- neither read here
                // has a persistence side effect, so `Failure` (not `Indeterminate`) is the right
                // shape either way, see class KDoc "Klassifikation".
                logger.warn(e) { "LexofficeApiClient: network failure reading GET /v1/profile response" }
                return ConnectionTestOutcome.Failure(errorCode = "NETWORK_ERROR", message = "Netzwerkfehler beim Aufruf von Lexware Office")
            }
        if (response.status.value in 200..299) {
            val parsed = bodyBytes?.let { decodeOrNull<LexofficeProfileResponse>(it) }
            if (parsed == null) {
                logger.warn { "LexofficeApiClient: 2xx /v1/profile but unparseable body" }
                return ConnectionTestOutcome.Failure(errorCode = "UNEXPECTED_RESPONSE", message = "Unerwartete Antwort von Lexware Office")
            }
            return ConnectionTestOutcome.Success(companyName = parsed.companyName)
        }
        val (code, message) = classifyErrorBody(response = response, bodyBytes = bodyBytes)
        return ConnectionTestOutcome.Failure(errorCode = code, message = message)
    }

    suspend fun listPostingCategories(token: String): CategoryListOutcome {
        rateLimiter.acquire()
        val response =
            try {
                httpClient.get("$baseUrl/v1/posting-categories") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(HttpHeaders.Accept, "application/json")
                }
            } catch (e: IOException) {
                logger.warn(e) { "LexofficeApiClient: network failure calling GET /v1/posting-categories" }
                return CategoryListOutcome.Failure(errorCode = "NETWORK_ERROR", message = "Netzwerkfehler beim Aufruf von Lexware Office")
            }
        val bodyBytes =
            try {
                response.readCappedLexofficeBody()
            } catch (e: IOException) {
                // Same post-send read failure [createVoucher] guards against -- see [getProfile]'s
                // identical guard for the rationale (no persistence side effect either way).
                logger.warn(e) { "LexofficeApiClient: network failure reading GET /v1/posting-categories response" }
                return CategoryListOutcome.Failure(errorCode = "NETWORK_ERROR", message = "Netzwerkfehler beim Aufruf von Lexware Office")
            }
        if (response.status.value in 200..299) {
            val parsed = bodyBytes?.let { decodeOrNull<List<LexofficePostingCategory>>(it) }
            if (parsed == null) {
                logger.warn { "LexofficeApiClient: 2xx /v1/posting-categories but unparseable body" }
                return CategoryListOutcome.Failure(errorCode = "UNEXPECTED_RESPONSE", message = "Unerwartete Antwort von Lexware Office")
            }
            return CategoryListOutcome.Success(
                categories =
                    parsed.mapNotNull { c ->
                        val direction =
                            when (c.type) {
                                "income" -> AccountingExportDirection.INCOME
                                "outgo" -> AccountingExportDirection.EXPENSE
                                else -> null
                            } ?: return@mapNotNull null
                        ExternalCategory(id = c.id, name = c.name, groupName = c.groupName, direction = direction)
                    },
            )
        }
        val (code, message) = classifyErrorBody(response = response, bodyBytes = bodyBytes)
        return CategoryListOutcome.Failure(errorCode = code, message = message)
    }

    /**
     * `POST /v1/vouchers`. A MISSING `id` on an otherwise-2xx response is treated as
     * [VoucherPushOutcome.Indeterminate], NEVER [VoucherPushOutcome.Succeeded] -- without the id
     * this server can never record which lexoffice voucher an
     * `accounting_export_item.external_voucher_id` corresponds to, which is functionally
     * indistinguishable from not knowing whether the send worked at all.
     *
     * Security review Fund 2026-09-07 (Runde 5, MAJOR -- duplicate of the pre-send [IOException]
     * finding above, same fix shape): every 5xx status used to be classified
     * [VoucherPushOutcome.Retryable] uniformly, which directly contradicted the doctrine the
     * pre-send [IOException] handler above states explicitly -- "only a failure that is GUARANTEED
     * to have happened before any byte of the request left this process is safe to classify as
     * `Retryable`". A 5xx response is by definition POST-send: lexoffice (or an intermediate
     * gateway, e.g. a reverse proxy that times out waiting on lexoffice's own backend and answers
     * 504 itself -- a status this class' own KDoc "Klassifikation" already lists as documented)
     * received and answered the request. Feeding that into `Retryable` resends the SAME
     * `voucherNumber` -- `POST /v1/vouchers` has no idempotency key, [isAlreadyExported] only
     * recognizes a `SUCCEEDED` item, and the item goes right back through
     * `AccountingExportStore.markRetryScheduled` -> `PENDING` for up to `MAX_ATTEMPTS` further
     * sends. Only 429 and 503 genuinely mean "not processed, safe to retry" (rate limit / transient
     * capacity refusal); every OTHER 5xx (500/502/504/...) is classified `Indeterminate` here, same
     * as the post-send read failure below and the ambiguous pre-send [IOException] case above --
     * resolution is left to a human via `AccountingExportService.resolveUnknownItem`, never an
     * automatic resend.
     */
    suspend fun createVoucher(
        token: String,
        voucher: OutboundVoucher,
    ): VoucherPushOutcome {
        rateLimiter.acquire()
        val body = LexofficeVoucherMapper.toRequest(voucher)
        val response =
            try {
                httpClient.post("$baseUrl/v1/vouchers") {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    header(HttpHeaders.Accept, "application/json")
                    contentType(ContentType.Application.Json)
                    setBody(LEXOFFICE_JSON.encodeToString(LexofficeVoucherRequest.serializer(), body))
                }
            } catch (e: IOException) {
                // Security review Fund 2026-09-07 (Runde 4, Befund 1): only a failure that is
                // GUARANTEED to have happened before any byte of the request left this process is
                // safe to classify as `Retryable` -- see class KDoc "Klassifikation" for the full
                // rationale. `ConnectException` (its ktor subtype `ConnectTimeoutException`
                // included) and `UnknownHostException` both fail during connection setup. Every
                // other `IOException` here -- chiefly `HttpRequestTimeoutException`
                // (`requestTimeoutMillis`, spans the full round trip including waiting for the
                // response) and `SocketTimeoutException` (`socketTimeoutMillis`, can elapse after
                // the request body was already fully written) -- is indistinguishable from "sent
                // successfully but the response was lost", exactly like the post-send read failure
                // below, so it must be `Indeterminate`, never `Retryable`.
                when (e) {
                    is ConnectException, is UnknownHostException -> {
                        logger.warn(e) { "LexofficeApiClient: pre-send network failure calling POST /v1/vouchers" }
                        return VoucherPushOutcome.Retryable(
                            errorCode = "NETWORK_ERROR",
                            message = "Netzwerkfehler beim Aufruf von Lexware Office",
                            retryAfter = null,
                        )
                    }
                    else -> {
                        logger.warn(e) {
                            "LexofficeApiClient: ambiguous network failure calling POST /v1/vouchers -- " +
                                "request may already have reached lexoffice"
                        }
                        return VoucherPushOutcome.Indeterminate(errorCode = "RESPONSE_LOST")
                    }
                }
            }
        val bodyBytes =
            try {
                response.readCappedLexofficeBody()
            } catch (e: IOException) {
                // Post-send failure -- the request DID reach lexoffice; whether it was actually
                // processed is now unknown from here. See class KDoc "Klassifikation".
                logger.warn(e) { "LexofficeApiClient: network failure reading POST /v1/vouchers response (post-send)" }
                return VoucherPushOutcome.Indeterminate(errorCode = "RESPONSE_LOST")
            }
        if (response.status.value in 200..299) {
            val parsed = bodyBytes?.let { decodeOrNull<LexofficeVoucherResponse>(it) }
            val externalId = parsed?.id
            if (externalId == null) {
                logger.warn { "LexofficeApiClient: 2xx POST /v1/vouchers but missing 'id' in response body" }
                return VoucherPushOutcome.Indeterminate(errorCode = "MISSING_ID")
            }
            return VoucherPushOutcome.Succeeded(externalVoucherId = externalId)
        }
        if (response.status.value == HTTP_TOO_MANY_REQUESTS) {
            val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.seconds
            return VoucherPushOutcome.Retryable(
                errorCode = "RATE_LIMITED",
                message = "Lexware Office Rate-Limit erreicht",
                retryAfter = retryAfter,
            )
        }
        if (response.status.value == HTTP_SERVICE_UNAVAILABLE) {
            val (code, message) = classifyErrorBody(response = response, bodyBytes = bodyBytes)
            return VoucherPushOutcome.Retryable(errorCode = code, message = message, retryAfter = null)
        }
        if (response.status.value in 500..599) {
            // See class KDoc "Klassifikation" and this method's own KDoc (Runde 5) -- every 5xx
            // OTHER than 503 above is post-send and therefore `Indeterminate`, never `Retryable`.
            logger.warn {
                "LexofficeApiClient: 5xx (${response.status.value}) on POST /v1/vouchers -- post-send, " +
                    "classified Indeterminate (not Retryable) to avoid a duplicate voucher"
            }
            return VoucherPushOutcome.Indeterminate(errorCode = "RESPONSE_LOST")
        }
        if (response.status.value == HTTP_UNAUTHORIZED || response.status.value == HTTP_FORBIDDEN) {
            val (_, message) = classifyErrorBody(response = response, bodyBytes = bodyBytes)
            return VoucherPushOutcome.Rejected(errorCode = "UNAUTHORIZED", message = message)
        }
        val (code, message) = classifyErrorBody(response = response, bodyBytes = bodyBytes)
        return VoucherPushOutcome.Rejected(errorCode = code, message = message)
    }

    /** Tries the plain `{"message": ...}` shape first (401/500/504, see the live docs'
     * "Authorization and Connection Error Responses"), then the "legacy error response" `IssueList`
     * shape (`vouchers`/`contacts`/`files`) -- see [LexofficeLegacyErrorEnvelope] KDoc. Falls back to
     * a generic, status-code-only message if neither parses (NEVER the raw body verbatim -- an
     * unparsed body could carry anything). */
    private fun classifyErrorBody(
        response: HttpResponse,
        bodyBytes: ByteArray?,
    ): Pair<String, String> {
        val status = response.status.value
        val simple = bodyBytes?.let { decodeOrNull<LexofficeSimpleErrorEnvelope>(it) }
        if (simple?.message != null) return status.toString() to simple.message
        val legacy = bodyBytes?.let { decodeOrNull<LexofficeLegacyErrorEnvelope>(it) }
        if (legacy != null && legacy.issueList.isNotEmpty()) {
            val message =
                legacy.issueList.joinToString("; ") { issue ->
                    listOfNotNull(issue.source, issue.i18nKey).joinToString(": ").ifBlank { "unbekannter Fehler" }
                }
            return status.toString() to message
        }
        return status.toString() to "Lexware Office hat die Anfrage abgelehnt (Status $status)"
    }

    private inline fun <reified T> decodeOrNull(bytes: ByteArray): T? =
        runCatching { LEXOFFICE_JSON.decodeFromString(kotlinx.serialization.serializer<T>(), bytes.toString(Charsets.UTF_8)) }.getOrNull()

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_SERVICE_UNAVAILABLE = 503
    }
}

/** Same hardened shape [network.lapis.cloud.server.payment.psp.defaultStripeHttpClient]
 * establishes. */
internal fun defaultLexofficeHttpClient(): HttpClient =
    HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 15_000
        }
        expectSuccess = false
        followRedirects = false
    }

/** Bounded read, same [network.lapis.cloud.server.payment.psp.StripeCheckoutClient
 * .readCappedStripeBody] idiom -- `null` if [MAX_LEXOFFICE_RESPONSE_BYTES] is exceeded, the body
 * discarded rather than partially parsed. */
private suspend fun HttpResponse.readCappedLexofficeBody(): ByteArray? {
    val channel = bodyAsChannel()
    val buffer = ByteArray(MAX_LEXOFFICE_RESPONSE_BYTES + 1)
    var total = 0
    while (total < buffer.size) {
        val read = channel.readAvailable(buffer, total, buffer.size - total)
        if (read == -1) break
        total += read
    }
    return if (total > MAX_LEXOFFICE_RESPONSE_BYTES) null else buffer.copyOf(total)
}

/** Formats a decimal amount as lexoffice expects (`"119.00"`, plain, dot decimal separator, always
 * 2 fractional digits) -- see [LexofficeVoucherRequest] KDoc. */
internal fun java.math.BigDecimal.toLexofficeAmountString(): String = this.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
