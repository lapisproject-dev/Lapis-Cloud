package network.lapis.cloud.server.payment.fints

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.crypto.SecretBoxException
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.BankAccountTable
import network.lapis.cloud.server.db.generated.BankStatementImportTable
import network.lapis.cloud.server.mail.FinTsReauthNotificationMailer
import network.lapis.cloud.server.payment.bankstatement.BankStatementImportService
import network.lapis.cloud.server.payment.bankstatement.BankStatementRejectedException
import network.lapis.cloud.server.webhook.WEBHOOK_NOTIFICATION_MAX_RECIPIENTS
import network.lapis.cloud.server.webhook.WebhookUrlCheck
import network.lapis.cloud.server.webhook.boardAndAdminMemberEmails
import network.lapis.cloud.server.webhook.checkWebhookUrl
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.BankAccountSnapshot
import network.lapis.cloud.shared.domain.FinTsStatus
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Review fix (MEDIUM): persisted marker for a skipped fetch-window gap (see [FinTsPoller
 * .processOneAccount]'s own KDoc on the watermark floor) -- an ad hoc poller-bookkeeping string,
 * same idiom as the existing `"URL_REJECTED"`/`"ENCRYPTION_KEY_MISSING"` literals elsewhere in this
 * file, NOT a [FinTsErrorCode] member (that enum is explicitly capped at the eight outcomes that
 * ever escape [Hbci4jFinTsClient] -- see its own KDoc).
 */
private const val FETCH_WINDOW_GAP_ERROR_CODE = "FETCH_WINDOW_GAP"

/**
 * Welle V1.4.14 Wave 2 "FinTS/HBCI-Live-Kontoabruf". Application-scoped poller, structured wörtlich
 * nach `network.lapis.cloud.server.payment.dunning.DunningPoller`/
 * `network.lapis.cloud.server.conference.RecordingPoller` -- ONE coroutine
 * (`SupervisorJob() + Dispatchers.IO`), `while (isActive) { tick(); delay(interval) }`, [tick]
 * public and exception-safe at two levels, [start]/[stop] idempotent, **NO in-memory state** --
 * every tick re-queries its candidates fresh from the DB (restart reconciliation).
 *
 * **`import()` is never modified.** [BankStatementImportService.import] is called EXACTLY the same
 * way the file-upload route calls it -- the only difference is the caller and the synthetic
 * `fileName`. Provenance (poller vs. manual upload) lives entirely in that filename, never in a new
 * column or parameter -- see `docs/architecture/bank-account.adoc` "Digest-Vorprüfung als
 * Wiederverwendungs-Mechanik".
 *
 * **Digest-Vorprüfung, nicht 409-als-Steuerfluss.** [import]'s own `ALREADY_IMPORTED` 409 is a
 * legitimate outcome for a manual re-upload, but for a poller running every few hours over an
 * overlapping window it would be the COMMON case, not the exception -- so [tick] computes the same
 * SHA-256 digest [import] itself computes and checks it against `bank_statement_import.file_digest`
 * BEFORE calling [import] at all (see [BankStatementImportService.MAX_UPLOAD_BYTES]/the digest
 * helper reused below). A hit advances only `fints_last_success_at`/`fints_last_fetch_to`, writes NO
 * new `bank_statement_import` row, and throws nothing.
 */
internal class FinTsPoller(
    private val config: FinTsConfig,
    private val fetcher: FinTsStatementFetcher,
    private val importService: BankStatementImportService,
    private val secretBox: SecretBox?,
    private val reauthMailer: FinTsReauthNotificationMailer,
    private val clock: () -> LocalDateTime = { DbClock.nowLocalDateTime() },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    /** Idempotent -- a second call while already running is a no-op. */
    fun start() {
        if (loopJob != null) return
        loopJob =
            scope.launch {
                while (isActive) {
                    tick()
                    delay(config.pollIntervalSeconds.seconds)
                }
            }
    }

    /** Cancels the poll loop -- for tests/graceful shutdown. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
    }

    /**
     * One poll pass. Exception-safe at two levels (whole tick + each account individually), no
     * timing dependency -- tests call this directly.
     */
    suspend fun tick() {
        try {
            if (secretBox == null) {
                logger.warn { "FinTsPoller: LAPIS_SECRET_ENCRYPTION_KEY not configured -- skipping tick entirely" }
                return
            }
            val candidates =
                transaction {
                    BankAccountTable
                        .selectAll()
                        .where { BankAccountTable.fintsStatus eq FinTsStatus.ACTIVE }
                        .orderBy(BankAccountTable.fintsLastSuccessAt to SortOrder.ASC_NULLS_FIRST, BankAccountTable.id to SortOrder.ASC)
                        .limit(config.maxAccountsPerTick)
                        .map { it[BankAccountTable.id] }
                }
            val now = clock()
            for (bankAccountId in candidates) {
                try {
                    processOneAccount(bankAccountId = bankAccountId, secretBox = secretBox, now = now)
                } catch (e: Throwable) {
                    logger.warn(e) { "FinTsPoller: account $bankAccountId failed, continuing with the next" }
                }
            }
        } catch (e: Throwable) {
            logger.warn(e) { "FinTsPoller: tick failed" }
        }
    }

    private suspend fun processOneAccount(
        bankAccountId: Uuid,
        secretBox: SecretBox,
        now: LocalDateTime,
    ) {
        val row =
            transaction {
                BankAccountTable.selectAll().where { BankAccountTable.id eq bankAccountId }.singleOrNull()
            } ?: return
        val url = row[BankAccountTable.fintsUrl] ?: return
        val urlCheck = checkWebhookUrl(raw = url, allowInsecureHttp = false)
        if (urlCheck is WebhookUrlCheck.Rejected) {
            recordTransientError(bankAccountId = bankAccountId, errorCode = "URL_REJECTED")
            return
        }

        val userIdCiphertext = row[BankAccountTable.fintsUserIdCiphertext] ?: return
        val pinCiphertext = row[BankAccountTable.fintsPinCiphertext] ?: return
        val userId =
            try {
                secretBox.open(sealed = userIdCiphertext, aad = bankAccountId.toString())
            } catch (e: SecretBoxException) {
                recordTransientError(bankAccountId = bankAccountId, errorCode = "ENCRYPTION_KEY_MISSING")
                return
            }
        val pin =
            try {
                secretBox.open(sealed = pinCiphertext, aad = bankAccountId.toString())
            } catch (e: SecretBoxException) {
                recordTransientError(bankAccountId = bankAccountId, errorCode = "ENCRYPTION_KEY_MISSING")
                return
            }
        val iban = row[BankAccountTable.iban]
        val blz = row[BankAccountTable.fintsBlz] ?: return

        // Review fix (MEDIUM): the watermark branch below must not be allowed to grow past
        // config.fetchWindowDays (itself already coerced into FinTsConfig.MAX_FETCH_WINDOW_DAYS,
        // i.e. <= 90 -- the range banks typically still serve for HKKAZ). Without this floor, an
        // account that sat disabled for months keeps its OLD fints_last_fetch_to as the watermark
        // (BankAccountStore.activateFinTs now resets it on a fresh (re-)activation -- see that
        // function's own KDoc -- but an account that stays continuously ACTIVE yet simply misses
        // polls for a long stretch, e.g. the poller itself being down, would hit the exact same
        // problem with a non-null but very old watermark), requesting a window the bank is likely to
        // reject -- which would then never self-heal, because only a SUCCESSFUL fetch
        // (handleMt940) ever advances the watermark.
        val oldestAllowedFrom = now.date.minus(config.fetchWindowDays, DateTimeUnit.DAY)
        val watermark = row[BankAccountTable.fintsLastFetchTo]
        // Review fix (MEDIUM, follow-up to the watermark-floor fix above): the floor above silently
        // widened the gap between an old watermark and `oldestAllowedFrom` -- a fetch that then
        // SUCCEEDS clears fintsLastErrorCode/advances fintsLastFetchTo exactly as if nothing had
        // ever been missed (see [handleMt940]), trading a loud, visible failure (the old behaviour,
        // before the floor existed) for a silent, invisible import gap -- in a GoBD-Vollständigkeit
        // context strictly the worse of the two failure modes. Logged AND persisted (see
        // [FETCH_WINDOW_GAP_ERROR_CODE]) so the gap is at least findable after the fact, even though
        // the fetch itself still only ever covers `oldestAllowedFrom..to` -- recovering the actually
        // skipped statements remains a manual re-import, same as before this fix.
        val fetchWindowGapDetected = watermark != null && watermark < oldestAllowedFrom
        val from = if (watermark == null || watermark < oldestAllowedFrom) oldestAllowedFrom else watermark
        val to = now.date
        if (fetchWindowGapDetected) {
            logger.warn {
                "FinTsPoller: account $bankAccountId watermark ($watermark) is older than the " +
                    "fetch window (${config.fetchWindowDays}d) -- skipping the gap $watermark..$oldestAllowedFrom, " +
                    "fetching $oldestAllowedFrom..$to instead"
            }
        }

        val credentials = FinTsCredentials(bankAccountId = bankAccountId, blz = blz, url = url, userId = userId, pin = pin, iban = iban)
        val result = withContext(Dispatchers.IO) { fetcher.fetch(credentials = credentials, from = from, to = to) }

        when (result) {
            is FinTsFetchResult.Mt940 ->
                handleMt940(
                    bankAccountId = bankAccountId,
                    bytes = result.bytes,
                    from = from,
                    to = to,
                    now = now,
                    row = row,
                    fetchWindowGapDetected = fetchWindowGapDetected,
                    // Review fix (MEDIUM, Runde 3): the SKIPPED range, not the fetched one -- see
                    // handleMt940's own KDoc for why these two are persisted separately from
                    // `from`/`to`.
                    gapFrom = watermark,
                    gapTo = oldestAllowedFrom,
                )
            FinTsFetchResult.TanRequired ->
                markReauthRequired(
                    bankAccountId = bankAccountId,
                    errorCode = "TAN_REQUIRED",
                    row = row,
                    now = now,
                )
            is FinTsFetchResult.Failed ->
                when (result.code) {
                    FinTsErrorCode.AUTH_FAILED ->
                        markReauthRequired(
                            bankAccountId = bankAccountId,
                            errorCode = "AUTH_FAILED",
                            row = row,
                            now = now,
                        )
                    FinTsErrorCode.BANK_UNAVAILABLE, FinTsErrorCode.TIMEOUT ->
                        recordTransientError(bankAccountId = bankAccountId, errorCode = result.code.name)
                    FinTsErrorCode.STATEMENT_FORMAT_UNSUPPORTED, FinTsErrorCode.PROTOCOL_ERROR ->
                        recordTransientError(bankAccountId = bankAccountId, errorCode = result.code.name)
                    FinTsErrorCode.URL_REJECTED, FinTsErrorCode.TAN_REQUIRED, FinTsErrorCode.ENCRYPTION_KEY_MISSING ->
                        recordTransientError(bankAccountId = bankAccountId, errorCode = result.code.name)
                }
        }
    }

    private fun handleMt940(
        bankAccountId: Uuid,
        bytes: ByteArray,
        from: LocalDate,
        to: LocalDate,
        now: LocalDateTime,
        row: ResultRow,
        fetchWindowGapDetected: Boolean,
        /** The SKIPPED range (`watermark`..`oldestAllowedFrom`), only meaningful when [fetchWindowGapDetected]. */
        gapFrom: LocalDate?,
        gapTo: LocalDate?,
    ) {
        // DoS-Deckel -- dasselbe Limit die Upload-Route bereits durchsetzt (Plan §6.2), wiederverwendet statt neu erfunden.
        if (bytes.size > BankStatementImportService.MAX_UPLOAD_BYTES) {
            recordTransientError(bankAccountId = bankAccountId, errorCode = "PROTOCOL_ERROR")
            return
        }
        val digest = sha256Hex(bytes)
        val alreadyImported =
            transaction {
                BankStatementImportTable.selectAll().where { BankStatementImportTable.fileDigest eq digest }.count() > 0
            }
        if (!alreadyImported && bytes.isNotEmpty()) {
            val ibanLast4 = row[BankAccountTable.iban].takeLast(4)
            val fileName = "fints-$ibanLast4-$from-$to.sta"
            val activatedBy = row[BankAccountTable.fintsActivatedBy]
            if (activatedBy == null) {
                // S19 guard -- the alles-oder-nichts CHECK constraint already forbids this state,
                // but a poller must never NPE on a data condition it can defensively detect instead.
                recordTransientError(bankAccountId = bankAccountId, errorCode = "PROTOCOL_ERROR")
                return
            }
            try {
                importService.import(
                    bytes = bytes,
                    fileName = fileName,
                    uploadedBy = activatedBy,
                    uploaderRole = AccountRole.ADMIN,
                    bankAccountId = bankAccountId,
                )
            } catch (e: BankStatementRejectedException) {
                recordTransientError(bankAccountId = bankAccountId, errorCode = "PROTOCOL_ERROR")
                return
            }
        }
        transaction {
            BankAccountTable.update({ BankAccountTable.id eq bankAccountId }) {
                it[fintsLastSuccessAt] = now
                it[fintsLastFetchTo] = to
                // Review fix (MEDIUM): a skipped fetch-window gap (see the caller's own KDoc) leaves
                // a mark here instead of being overwritten with `null` like every other successful
                // fetch -- a CURRENT-tick signal. Clears itself automatically on the NEXT tick if
                // that tick has no gap of its own (the same unconditional overwrite every other
                // successful fetch already relied on) -- see fintsGapFrom/fintsGapTo/
                // fintsGapDetectedAt below for the DURABLE record this transient one is deliberately
                // NOT (a Review-fix Runde 3 finding: this column alone made a gap invisible again
                // within one poll interval, ~6h by default -- long before an ADMIN checking the next
                // morning would ever see it).
                it[fintsLastErrorCode] = if (fetchWindowGapDetected) FETCH_WINDOW_GAP_ERROR_CODE else null
                // Review fix (MEDIUM, Runde 3): the DURABLE record of the MOST RECENTLY detected
                // fetch-window gap -- deliberately NEVER cleared by an ordinary gap-free success
                // (unlike fintsLastErrorCode above), so it stays findable long after this tick's own
                // transient marker has self-healed. `from`/`to` are exactly the range that got
                // skipped (see the caller's own `fetchWindowGapDetected` computation: `watermark`..
                // `oldestAllowedFrom`), not the range that was actually fetched.
                if (fetchWindowGapDetected) {
                    it[fintsGapFrom] = gapFrom
                    it[fintsGapTo] = gapTo
                    it[fintsGapDetectedAt] = now
                }
            }
        }
    }

    private fun recordTransientError(
        bankAccountId: Uuid,
        errorCode: String,
    ) {
        transaction {
            BankAccountTable.update({ BankAccountTable.id eq bankAccountId }) {
                it[fintsLastErrorCode] = errorCode
            }
        }
    }

    /**
     * Status -> REAUTH_REQUIRED, audit entry with `actorMemberId = null` (SYSTEM actor, same
     * convention `DunningPoller`/`RecordingPoller` establish), mail sent ONLY on the transition
     * (previous status != REAUTH_REQUIRED) -- a second consecutive tick observing the same failure
     * must NOT send a second mail.
     */
    private fun markReauthRequired(
        bankAccountId: Uuid,
        errorCode: String,
        row: ResultRow,
        now: LocalDateTime,
    ) {
        val wasAlreadyReauth = row[BankAccountTable.fintsStatus] == FinTsStatus.REAUTH_REQUIRED
        val before = row.toFinTsSnapshot()
        transaction {
            BankAccountTable.update({ BankAccountTable.id eq bankAccountId }) {
                it[fintsStatus] = FinTsStatus.REAUTH_REQUIRED
                it[fintsLastErrorCode] = errorCode
            }
            AuditLogRecorder.record(
                actorMemberId = null,
                actorRole = null,
                entityType = AuditEntityType.BANK_ACCOUNT,
                entityId = bankAccountId,
                action = AuditAction.UPDATE,
                before = Json.encodeToString(BankAccountSnapshot.serializer(), before),
                after =
                    Json.encodeToString(
                        BankAccountSnapshot.serializer(),
                        before.copy(finTsStatus = FinTsStatus.REAUTH_REQUIRED),
                    ),
            )
        }
        if (!wasAlreadyReauth) {
            val recipients = boardAndAdminMemberEmails().take(WEBHOOK_NOTIFICATION_MAX_RECIPIENTS)
            val accountLabel = row[BankAccountTable.label]
            val ibanMasked = BankStatementImportService.maskIban(row[BankAccountTable.iban]).orEmpty()
            reauthMailer.send(recipients = recipients, accountLabel = accountLabel, ibanMasked = ibanMasked, occurredAt = now)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun ResultRow.toFinTsSnapshot(): BankAccountSnapshot =
        BankAccountSnapshot(
            label = this[BankAccountTable.label],
            ibanMasked = BankStatementImportService.maskIban(this[BankAccountTable.iban]) ?: this[BankAccountTable.iban],
            bic = this[BankAccountTable.bic],
            bankName = this[BankAccountTable.bankName],
            isDefault = this[BankAccountTable.isDefault],
            finTsStatus = this[BankAccountTable.fintsStatus],
        )
}
