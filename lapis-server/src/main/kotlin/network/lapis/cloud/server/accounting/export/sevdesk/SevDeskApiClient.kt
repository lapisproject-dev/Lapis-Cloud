package network.lapis.cloud.server.accounting.export.sevdesk

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
import network.lapis.cloud.server.accounting.export.CategoryListOutcome
import network.lapis.cloud.server.accounting.export.ConnectionTestOutcome
import network.lapis.cloud.server.accounting.export.ExternalCategory
import network.lapis.cloud.server.accounting.export.OutboundVoucher
import network.lapis.cloud.server.accounting.export.VoucherPushOutcome
import network.lapis.cloud.shared.domain.AccountingExportDirection
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/** Hard cap on how many bytes of a sevDesk response body are ever read into memory -- same
 * "bounded read, discard rather than partially parse" idiom
 * [network.lapis.cloud.server.accounting.export.lexoffice.LexofficeApiClient]'s own
 * `readCappedLexofficeBody` establishes. See [readCappedSevDeskBody] KDoc "Scope of the guarantee"
 * for what this cap does and does NOT bound. */
private const val MAX_SEVDESK_RESPONSE_BYTES = 64 * 1024

/** Fest im Code, KEIN Env-Override -- gleiche SSRF-Haltung wie
 * [network.lapis.cloud.server.accounting.export.lexoffice.LEXOFFICE_API_BASE_URL] (siehe
 * `AccountingExportConfig` KDoc "No user/operator-configurable target URL"). Überschreibbar
 * AUSSCHLIESSLICH als Konstruktorparameter, für `MockEngine`-Tests. Live-verified 2026-09-07
 * gegen `https://api.sevdesk.de/openapi.yaml` `servers[0].url`. */
internal const val SEVDESK_API_BASE_URL = "https://my.sevdesk.de/api/v1"

/**
 * Welle V1.4.5.4 "sevDesk-Live-Anbindung" -- the ONLY place this codebase makes outbound HTTP to
 * sevDesk. Same hardened shape [network.lapis.cloud.server.accounting.export.lexoffice
 * .LexofficeApiClient] establishes: `followRedirects = false`, `expectSuccess = false`, hard
 * [HttpTimeout], bounded response read, and deliberately NO `ContentNegotiation`/`Logging` Ktor
 * plugin -- a request-logging plugin would risk the raw `Authorization` header (see [Auth header]
 * below) reaching a log line. Responses are decoded manually via [SEVDESK_JSON] after
 * [readCappedSevDeskBody] instead.
 *
 * **Auth header**: sevDesk's `api_key` security scheme puts the RAW token in the `Authorization`
 * header, with NO `Bearer ` prefix -- live-verified against the spec's
 * `securitySchemes.api_key: {type: apiKey, name: Authorization, in: header}`. This is the single
 * biggest wire-format difference from the lexoffice client (which uses `Bearer $token`). The spec
 * also documents that sevDesk issues exactly ONE token per administrator account, with NO
 * permission-scoping of its own -- a stored token has full account access, not a narrower scope.
 *
 * [rateLimiter] MUST be shared (module-scoped, constructed once by
 * [network.lapis.cloud.server.Application.module]) across every caller of this class, exactly the
 * same contract [SevDeskRateLimiter] KDoc documents.
 *
 * **Klassifikation** -- IDENTICAL doctrine to [network.lapis.cloud.server.accounting.export
 * .lexoffice.LexofficeApiClient], no exceptions: 2xx with an `id` -> success. 2xx without an `id`
 * -> [VoucherPushOutcome.Indeterminate]`("MISSING_ID")`. 429/503 -> [VoucherPushOutcome.Retryable]
 * (both mean "not processed"). Every OTHER 5xx -> `Indeterminate("RESPONSE_LOST")` -- `saveVoucher`
 * has no idempotency key, a resend risks a genuine duplicate voucher. 401/403 ->
 * [VoucherPushOutcome.Rejected]`("UNAUTHORIZED", ...)`. Every other 4xx -> `Rejected`. A
 * pre-send [ConnectException]/[UnknownHostException] -> `Retryable`; every OTHER [IOException]
 * (timeouts, post-send read failures) -> `Indeterminate("RESPONSE_LOST")`.
 *
 * **`"401"` vs `"UNAUTHORIZED"` -- two DIFFERENT conventions in the SAME class, deliberately.**
 * [getBookkeepingSystemVersion]/[listReceiptGuidance] return [ConnectionTestOutcome.Failure]/
 * [CategoryListOutcome.Failure] with `errorCode = "401"`/`"403"` (the literal status code as a
 * string) -- `AccountingExportService.testConnection`/`.listCategories` compare AGAINST those
 * exact literals to decide whether to call `AccountingExportStore.markTestDue`. [createVoucher],
 * by contrast, returns [VoucherPushOutcome.Rejected] with `errorCode = "UNAUTHORIZED"` for the
 * SAME 401/403 statuses -- `AccountingExportPoller` compares against THAT literal instead. This is
 * not an inconsistency to "clean up": it mirrors
 * [network.lapis.cloud.server.accounting.export.lexoffice.LexofficeApiClient] exactly, and mixing
 * the two conventions up (e.g. returning `"UNAUTHORIZED"` from [getBookkeepingSystemVersion])
 * silently breaks the `markTestDue` re-authentication prompt after a revoked token, with no test
 * failure to catch it -- see that method's own test for the regression guard.
 */
internal class SevDeskApiClient(
    private val rateLimiter: SevDeskRateLimiter,
    private val httpClient: HttpClient = defaultSevDeskHttpClient(),
    private val baseUrl: String = SEVDESK_API_BASE_URL,
) {
    /** `GET /Tools/bookkeepingSystemVersion` -- doubles as [AccountingExportProviderAdapter
     * .testConnection]. sevDesk's Tools endpoint family carries no organization/company name the
     * way lexoffice's `/v1/profile` does, so [ConnectionTestOutcome.Success.companyName] is always
     * `null` here -- the UI's "Verbunden mit: ..." line simply has nothing to show for sevDesk,
     * which is expected, not a bug. */
    suspend fun getBookkeepingSystemVersion(token: String): ConnectionTestOutcome {
        rateLimiter.acquire()
        val response =
            try {
                httpClient.get("$baseUrl/Tools/bookkeepingSystemVersion") {
                    header(HttpHeaders.Authorization, token)
                    header(HttpHeaders.Accept, "application/json")
                }
            } catch (e: IOException) {
                logger.warn(e) { "SevDeskApiClient: network failure calling GET /Tools/bookkeepingSystemVersion" }
                return ConnectionTestOutcome.Failure(errorCode = "NETWORK_ERROR", message = "Netzwerkfehler beim Aufruf von sevDesk")
            }
        val bodyBytes =
            try {
                response.readCappedSevDeskBody()
            } catch (e: IOException) {
                logger.warn(e) { "SevDeskApiClient: network failure reading GET /Tools/bookkeepingSystemVersion response" }
                return ConnectionTestOutcome.Failure(errorCode = "NETWORK_ERROR", message = "Netzwerkfehler beim Aufruf von sevDesk")
            }
        if (response.status.value in 200..299) {
            val parsed = bodyBytes?.let { decodeOrNull<SevDeskObjectsEnvelope<SevDeskBookkeepingVersion>>(it) }
            val version = parsed?.objects?.version
            return when (version) {
                "2.0" -> ConnectionTestOutcome.Success(companyName = null)
                "1.0" ->
                    ConnectionTestOutcome.Failure(
                        errorCode = "UNSUPPORTED_BOOKKEEPING_VERSION",
                        message = "Ihr sevDesk-Konto nutzt noch Buchhaltungsversion 1.0. Diese Anbindung setzt Version 2.0 voraus.",
                    )
                else -> {
                    logger.warn { "SevDeskApiClient: 2xx /Tools/bookkeepingSystemVersion but unexpected version '$version'" }
                    ConnectionTestOutcome.Failure(errorCode = "UNEXPECTED_RESPONSE", message = "Unerwartete Antwort von sevDesk")
                }
            }
        }
        val (code, message) = classifyErrorBody(response = response, bodyBytes = bodyBytes)
        return ConnectionTestOutcome.Failure(errorCode = code, message = message)
    }

    /**
     * `GET /ReceiptGuidance/forRevenue` + `GET /ReceiptGuidance/forExpense`, gated by a leading
     * `GET /Tools/bookkeepingSystemVersion` check. Three requests instead of two -- deliberate:
     * `accountDatev` (required by [SevDeskVoucherPos]) only exists under bookkeeping version
     * "2.0", so without this upfront gate a "1.0" account would populate the mapping UI with
     * categories that are GUARANTEED to fail the moment a voucher is actually sent. This method
     * is nutzerausgelöst (opens the mapping screen) and rare -- the extra round trip is the right
     * trade for catching the failure at mapping time instead of send time.
     *
     * Each of the three GETs individually goes through [SevDeskRateLimiter.acquire] -- NOT a
     * single acquire for all three, matching the "call acquire() immediately before every actual
     * HTTP request" contract [SevDeskRateLimiter] documents.
     */
    suspend fun listReceiptGuidance(token: String): CategoryListOutcome {
        when (val versionCheck = getBookkeepingSystemVersion(token)) {
            is ConnectionTestOutcome.Success -> Unit
            is ConnectionTestOutcome.Failure ->
                return CategoryListOutcome.Failure(errorCode = versionCheck.errorCode, message = versionCheck.message)
        }
        val revenue =
            fetchReceiptGuidance(token = token, path = "/ReceiptGuidance/forRevenue", direction = AccountingExportDirection.INCOME)
        if (revenue is CategoryListOutcome.Failure) return revenue
        val expense =
            fetchReceiptGuidance(token = token, path = "/ReceiptGuidance/forExpense", direction = AccountingExportDirection.EXPENSE)
        if (expense is CategoryListOutcome.Failure) return expense
        val revenueGuides = (revenue as CategoryListOutcome.Success).categories
        val expenseGuides = (expense as CategoryListOutcome.Success).categories
        return CategoryListOutcome.Success(categories = revenueGuides + expenseGuides)
    }

    private suspend fun fetchReceiptGuidance(
        token: String,
        path: String,
        direction: AccountingExportDirection,
    ): CategoryListOutcome {
        rateLimiter.acquire()
        val response =
            try {
                httpClient.get("$baseUrl$path") {
                    header(HttpHeaders.Authorization, token)
                    header(HttpHeaders.Accept, "application/json")
                }
            } catch (e: IOException) {
                logger.warn(e) { "SevDeskApiClient: network failure calling GET $path" }
                return CategoryListOutcome.Failure(errorCode = "NETWORK_ERROR", message = "Netzwerkfehler beim Aufruf von sevDesk")
            }
        val bodyBytes =
            try {
                response.readCappedSevDeskBody()
            } catch (e: IOException) {
                logger.warn(e) { "SevDeskApiClient: network failure reading GET $path response" }
                return CategoryListOutcome.Failure(errorCode = "NETWORK_ERROR", message = "Netzwerkfehler beim Aufruf von sevDesk")
            }
        if (response.status.value in 200..299) {
            val parsed = bodyBytes?.let { decodeOrNull<SevDeskObjectsEnvelope<List<SevDeskReceiptGuide>>>(it) }
            val guides = parsed?.objects
            if (guides == null) {
                logger.warn { "SevDeskApiClient: 2xx $path but unparseable body" }
                return CategoryListOutcome.Failure(errorCode = "UNEXPECTED_RESPONSE", message = "Unerwartete Antwort von sevDesk")
            }
            return CategoryListOutcome.Success(categories = guides.flatMap { it.toExternalCategories(direction) })
        }
        val (code, message) = classifyErrorBody(response = response, bodyBytes = bodyBytes)
        return CategoryListOutcome.Failure(errorCode = code, message = message)
    }

    /** One [ExternalCategory] per (Konto x ZERO-fähiger `taxRule`) -- see
     * [network.lapis.cloud.server.accounting.export.sevdesk.SevDeskVoucherMapper] KDoc "why the
     * external category id is composite". **Filter, verbindlich**: only `allowedTaxRules` entries
     * whose `taxRates` contains the literal `"ZERO"` are ever surfaced -- an account that does not
     * permit a 0%-VAT posting is not offered at all, rather than offered and then rejected by
     * sevDesk mid-send (Norman/Jobs: fehlervermeidung statt fehlermeldung). */
    private fun SevDeskReceiptGuide.toExternalCategories(direction: AccountingExportDirection): List<ExternalCategory> {
        val accountId = this.accountDatevId ?: return emptyList()
        return this.allowedTaxRules
            .filter { rule -> rule.taxRates.contains("ZERO") }
            .mapNotNull { rule ->
                val ruleId = rule.id ?: return@mapNotNull null
                ExternalCategory(
                    id = "$accountId:$ruleId",
                    name = "${this.accountName.orEmpty()} — ${rule.description.orEmpty()}",
                    groupName = this.accountNumber,
                    direction = direction,
                )
            }
    }

    /**
     * `POST /Voucher/Factory/saveVoucher`. See class KDoc "Klassifikation" for the full
     * status-code mapping -- identical doctrine to [network.lapis.cloud.server.accounting.export
     * .lexoffice.LexofficeApiClient.createVoucher], down to the "only a failure GUARANTEED to have
     * happened before any byte left this process is Retryable" rule.
     *
     * **Response-envelope tolerance**: the spec documents `saveVoucherResponse` UNGWRAPPED
     * (`{voucher: {...}}`), unlike every other endpoint this client calls, which wraps in
     * `{"objects": ...}`. This method therefore tries the wrapped shape FIRST, then the unwrapped
     * shape -- see [SevDeskSaveVoucherResponse] KDoc "Spec inconsistency".
     */
    suspend fun createVoucher(
        token: String,
        voucher: OutboundVoucher,
    ): VoucherPushOutcome {
        rateLimiter.acquire()
        val mapped = SevDeskVoucherMapper.toRequest(voucher)
        val body =
            when (mapped) {
                is SevDeskVoucherMapper.MappingResult.Unmappable ->
                    return VoucherPushOutcome.Rejected(errorCode = mapped.errorCode, message = mapped.message)
                is SevDeskVoucherMapper.MappingResult.Ok -> mapped.request
            }
        val response =
            try {
                httpClient.post("$baseUrl/Voucher/Factory/saveVoucher") {
                    header(HttpHeaders.Authorization, token)
                    header(HttpHeaders.Accept, "application/json")
                    contentType(ContentType.Application.Json)
                    setBody(SEVDESK_JSON.encodeToString(SevDeskSaveVoucherRequest.serializer(), body))
                }
            } catch (e: IOException) {
                when (e) {
                    is ConnectException, is UnknownHostException -> {
                        logger.warn(e) { "SevDeskApiClient: pre-send network failure calling POST /Voucher/Factory/saveVoucher" }
                        return VoucherPushOutcome.Retryable(
                            errorCode = "NETWORK_ERROR",
                            message = "Netzwerkfehler beim Aufruf von sevDesk",
                            retryAfter = null,
                        )
                    }
                    else -> {
                        logger.warn(e) {
                            "SevDeskApiClient: ambiguous network failure calling POST /Voucher/Factory/saveVoucher -- " +
                                "request may already have reached sevDesk"
                        }
                        return VoucherPushOutcome.Indeterminate(errorCode = "RESPONSE_LOST")
                    }
                }
            }
        val bodyBytes =
            try {
                response.readCappedSevDeskBody()
            } catch (e: IOException) {
                logger.warn(e) { "SevDeskApiClient: network failure reading POST /Voucher/Factory/saveVoucher response (post-send)" }
                return VoucherPushOutcome.Indeterminate(errorCode = "RESPONSE_LOST")
            }
        if (response.status.value in 200..299) {
            val externalId =
                bodyBytes
                    ?.let { decodeOrNull<SevDeskObjectsEnvelope<SevDeskSaveVoucherResponse>>(it) }
                    ?.objects
                    ?.voucher
                    ?.id
                    ?: bodyBytes?.let { decodeOrNull<SevDeskSaveVoucherResponse>(it) }?.voucher?.id
            if (externalId == null) {
                logger.warn { "SevDeskApiClient: 2xx POST /Voucher/Factory/saveVoucher but missing 'id' in response body" }
                return VoucherPushOutcome.Indeterminate(errorCode = "MISSING_ID")
            }
            return VoucherPushOutcome.Succeeded(externalVoucherId = externalId)
        }
        if (response.status.value == HTTP_TOO_MANY_REQUESTS) {
            val retryAfter = response.headers[HttpHeaders.RetryAfter]?.toLongOrNull()?.seconds
            return VoucherPushOutcome.Retryable(
                errorCode = "RATE_LIMITED",
                message = "sevDesk Rate-Limit erreicht",
                retryAfter = retryAfter,
            )
        }
        if (response.status.value == HTTP_SERVICE_UNAVAILABLE) {
            val (code, message) = classifyErrorBody(response = response, bodyBytes = bodyBytes)
            return VoucherPushOutcome.Retryable(errorCode = code, message = message, retryAfter = null)
        }
        if (response.status.value in 500..599) {
            logger.warn {
                "SevDeskApiClient: 5xx (${response.status.value}) on POST /Voucher/Factory/saveVoucher -- post-send, " +
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

    /** Tries the `422` `{"error": {...}}` shape first (see [SevDeskValidationError]), falls back to
     * a generic, status-code-only message if it does not parse -- NEVER the raw body verbatim (an
     * unparsed body could carry anything, and this is exactly the string that can land in
     * `accounting_export_item.error_message VARCHAR(500)` or a log line). */
    private fun classifyErrorBody(
        response: HttpResponse,
        bodyBytes: ByteArray?,
    ): Pair<String, String> {
        val status = response.status.value
        val validation = bodyBytes?.let { decodeOrNull<SevDeskValidationError>(it) }
        val message = validation?.error?.message
        if (message != null) return status.toString() to message
        return status.toString() to "sevDesk hat die Anfrage abgelehnt (Status $status)"
    }

    private inline fun <reified T> decodeOrNull(bytes: ByteArray): T? =
        runCatching { SEVDESK_JSON.decodeFromString(kotlinx.serialization.serializer<T>(), bytes.toString(Charsets.UTF_8)) }.getOrNull()

    private companion object {
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_SERVICE_UNAVAILABLE = 503
    }
}

/** Same hardened shape [network.lapis.cloud.server.accounting.export.lexoffice
 * .defaultLexofficeHttpClient] establishes. */
internal fun defaultSevDeskHttpClient(): HttpClient =
    HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 15_000
        }
        expectSuccess = false
        followRedirects = false
    }

/**
 * Bounded read, same [network.lapis.cloud.server.accounting.export.lexoffice.LexofficeApiClient
 * .readCappedLexofficeBody] idiom -- `null` if [MAX_SEVDESK_RESPONSE_BYTES] is exceeded, the body
 * discarded rather than partially parsed.
 *
 * **Scope of the guarantee** (same correction [network.lapis.cloud.server.economy.oracle
 * .readCappedBodyOrNull] KDoc documents for the oracle client -- Security-Audit-Runde 1 / S3 --
 * apply verbatim here): every current call site (`getBookkeepingSystemVersion`,
 * `fetchReceiptGuidance`, `createVoucher`) uses the non-streaming `httpClient.get(...)`/`post(...)`
 * request form, under which Ktor 3.5.1's internal `SaveBody` plugin has already buffered the
 * ENTIRE response body into memory before this function -- or any of this class's code -- ever
 * runs. This function's own read loop therefore bounds the cost of the copy/parse step that
 * follows, but it does **NOT** bound how much a single `my.sevdesk.de` response can make the JVM
 * buffer before that -- a malicious or compromised peer (or a CA-level MITM) streaming at line
 * rate for the full `requestTimeoutMillis` (15s, see [defaultSevDeskHttpClient]) could still make
 * `SaveBody` buffer hundreds of MB to roughly 1GB. Genuinely closing that gap requires switching
 * every call site to Ktor's streaming `preparePost(...)`/`prepareGet(...).execute { response -> ...
 * }` idiom (reading/capping directly off [HttpResponse.bodyAsChannel] before the body is
 * materialized) -- not done here, same "call-site-shape-changing restructuring, deferred" trade-off
 * the oracle client's own KDoc makes; revisit together with that one if it is ever tackled.
 */
private suspend fun HttpResponse.readCappedSevDeskBody(): ByteArray? {
    val channel = bodyAsChannel()
    val buffer = ByteArray(MAX_SEVDESK_RESPONSE_BYTES + 1)
    var total = 0
    while (total < buffer.size) {
        val read = channel.readAvailable(buffer, total, buffer.size - total)
        if (read == -1) break
        total += read
    }
    return if (total > MAX_SEVDESK_RESPONSE_BYTES) null else buffer.copyOf(total)
}
