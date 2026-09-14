package network.lapis.cloud.server.payment.fints

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.uuid.Uuid

/**
 * Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf". The two interfaces below are the ENTIRE
 * touching surface between this codebase and hbci4j -- no `org.kapott.*` type appears in this file,
 * in [network.lapis.cloud.server.rpc.BankAccountService], in [network.lapis.cloud.server.payment.fints.FinTsPoller],
 * or in any shared/client module. [Hbci4jFinTsClient] is the ONLY implementation and the ONLY file
 * in this codebase that imports `org.kapott.*`.
 *
 * **Why TWO interfaces, not one.** [FinTsStatementFetcher] is what [FinTsPoller] holds: a
 * background loop with NO human present, so a TAN request during a routine fetch is structurally
 * UNSAGBAR -- there is no parameter through which a TAN could ever be supplied, and no code path
 * that could ever block waiting for one. [FinTsSetupClient] is what the ADMIN-facing RPC
 * (`BankAccountService.beginFinTsSetup`/`submitFinTsTan`) holds: a human IS present, across
 * possibly two separate RPC calls (bank prompts for a TAN -> UI shows a TAN field -> ADMIN types it
 * -> second RPC call). Collapsing these into one interface would either force the poller to accept
 * a TAN parameter it can never fill in, or force the setup RPC to give up after one dialog attempt
 * with no way to complete a bank that always requires a TAN on first contact.
 *
 * **Both interfaces are deliberately `fun`, NOT `suspend`.** The entire hbci4j interaction for one
 * call must run on a SINGLE dedicated thread from start to finish -- see [Hbci4jFinTsClient] KDoc
 * "Thread-lokale Initialisierung" for why a coroutine's ability to hop threads between suspension
 * points would corrupt hbci4j's thread-local state. Callers (`FinTsPoller`/`BankAccountService`,
 * both themselves `suspend`) wrap every call in `withContext(Dispatchers.IO) { ... }`.
 */
internal class FinTsCredentials(
    val bankAccountId: Uuid,
    val blz: String,
    val url: String,
    val userId: String,
    val pin: String,
    val iban: String,
) {
    /**
     * Deliberately NOT a `data class` -- a `data class`'s auto-generated `toString()`/`equals()`/
     * `hashCode()` would print (and therefore risk logging) [userId]/[pin] verbatim the first time
     * anyone reaches for `"$credentials"` in a log line or an exception message. This override is
     * the only `toString()` this class will ever have.
     */
    override fun toString(): String =
        "FinTsCredentials(bankAccountId=$bankAccountId, blz=$blz, url=<redacted>, userId=<redacted>, pin=<redacted>)"
}

/**
 * Held ONLY by [FinTsPoller] -- see class KDoc "why TWO interfaces". [fetch] is blocking (see class
 * KDoc "deliberately NOT suspend").
 */
internal interface FinTsStatementFetcher {
    fun fetch(
        credentials: FinTsCredentials,
        from: LocalDate,
        to: LocalDate,
    ): FinTsFetchResult
}

internal sealed interface FinTsFetchResult {
    /**
     * [bytes] is the RAW MT940 the bank actually sent, byte-for-byte (after hbci4j's own SWIFT-
     * umlaut decoding, which is a lossless charset transform, never a re-serialization) -- see
     * [Hbci4jRawMt940Extractor] KDoc for how this is obtained and why that matters for
     * [network.lapis.cloud.server.payment.bankstatement.BankStatementImportService.import] staying
     * completely untouched by this wave.
     */
    class Mt940(
        val bytes: ByteArray,
        val statementCount: Int,
    ) : FinTsFetchResult

    data object TanRequired : FinTsFetchResult

    data class Failed(
        val code: FinTsErrorCode,
    ) : FinTsFetchResult
}

/**
 * Held ONLY by [network.lapis.cloud.server.rpc.BankAccountService] -- see class KDoc "why TWO
 * interfaces". All three methods are blocking (see class KDoc "deliberately NOT suspend").
 */
internal interface FinTsSetupClient {
    fun begin(credentials: FinTsCredentials): FinTsSetupOutcome

    /** [handle] is the SAME opaque value [FinTsSetupOutcome.TanRequested.handle] returned from [begin]. */
    fun submitTan(
        handle: String,
        tan: String,
    ): FinTsSetupOutcome

    /** Invalidates an open dialog for [handle] -- ADMIN clicked "Abbrechen", or the handle's TTL expired. Idempotent. */
    fun cancel(handle: String)
}

internal sealed interface FinTsSetupOutcome {
    /**
     * [credentials] is the SAME value [FinTsSetupClient.begin] was originally called with -- the
     * RPC layer (`BankAccountService.submitFinTsTan`) has no other way to recover it: a `handle` is
     * the ONLY thing that survives between [FinTsSetupClient.begin] (RPC call #1) and
     * [FinTsSetupClient.submitTan] (RPC call #2, a SEPARATE, stateless request from the web
     * layer's own perspective). Carrying it here -- rather than the RPC layer maintaining its OWN
     * parallel handle-keyed cache of plaintext credentials -- keeps the plaintext PIN/user id alive
     * in exactly ONE place ([Hbci4jFinTsClient]'s handle table) for its entire in-memory lifetime.
     */
    data class Verified(
        val credentials: FinTsCredentials,
        val statementCount: Int,
    ) : FinTsSetupOutcome

    /**
     * [handle] is opaque, single-use, and lives ONLY in [Hbci4jFinTsClient]'s in-memory handle
     * table -- never persisted, never logged. [expiresAt] feeds the UI's countdown; see
     * [Hbci4jFinTsClient] KDoc "TAN-Behandlung" for why this is numerically tied to hbci4j's own
     * `kernel.threaded.maxwaittime` default (300 seconds), not an arbitrary UI choice.
     */
    data class TanRequested(
        val handle: String,
        val bankPrompt: String,
        val expiresAt: LocalDateTime,
    ) : FinTsSetupOutcome

    data class Failed(
        val code: FinTsErrorCode,
    ) : FinTsSetupOutcome
}

/**
 * The eight (and ONLY eight) machine-readable outcomes that ever escape [Hbci4jFinTsClient] --
 * never a raw hbci4j/bank message, never a hostname, never a resolved IP address (see that class's
 * own "Fehler-Mapping" KDoc). [STATEMENT_FORMAT_UNSUPPORTED] covers a camt-only bank (HKCAZ instead
 * of HKKAZ) -- deliberately out of scope, see `docs/architecture/bank-account.adoc` "camt-only
 * banks".
 */
internal enum class FinTsErrorCode {
    URL_REJECTED,
    AUTH_FAILED,
    TAN_REQUIRED,
    BANK_UNAVAILABLE,
    TIMEOUT,
    PROTOCOL_ERROR,
    STATEMENT_FORMAT_UNSUPPORTED,
    ENCRYPTION_KEY_MISSING,
}
