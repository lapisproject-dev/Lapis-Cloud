package network.lapis.cloud.server.accounting.export

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.generated.AccountingExportCategoryMapTable
import network.lapis.cloud.server.db.generated.AccountingExportConnectionTable
import network.lapis.cloud.server.db.generated.AccountingExportItemTable
import network.lapis.cloud.server.db.generated.AccountingExportRunTable
import network.lapis.cloud.shared.domain.AccountingExportDirection
import network.lapis.cloud.shared.domain.AccountingExportItemStatus
import network.lapis.cloud.shared.domain.AccountingExportProvider
import network.lapis.cloud.shared.domain.AccountingExportRunStatus
import network.lapis.cloud.shared.domain.AccountingExportUnknownItemResolution
import network.lapis.cloud.shared.domain.displayName
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.4.5.3 "lexoffice-Live-Anbindung" -- all DB access for the four
 * `accounting_export_*` tables lives here, no other file touches those Exposed tables directly.
 * Every function opens its OWN `transaction {}`, same idiom
 * `network.lapis.cloud.server.webhook.WebhookDeliveryQueue` establishes -- callers never need an
 * already-open transaction of their own to call into this object (unlike `AuditLogRecorder`).
 *
 * [ACTIVE_KEY]/[exportedKeyOf] are the ONLY two functions in this codebase that ever write
 * `accounting_export_run.active_key`/`accounting_export_item.exported_key` -- see
 * `43-accounting-export.kuml.kts` file header "Why a partial unique index is NOT used here" for the
 * full rationale these two application-maintained shadow columns replace.
 */
internal object AccountingExportStore {
    // ── Connection lifecycle ────────────────────────────────────────────────────────────────

    data class ConnectionRow(
        val id: Uuid,
        val provider: AccountingExportProvider,
        val hasToken: Boolean,
        val tokenLast4: String?,
        val connectedCompanyName: String?,
        val lastTestedAt: LocalDateTime?,
        val zeroVatAcknowledgedAt: LocalDateTime?,
        val zeroVatDisclaimerVersion: String?,
        // Security-Audit-Fund 2026-09-07 (Runde 6): read back alongside the version so a caller can
        // require BOTH `zeroVatDisclaimerVersion == VERSION` AND `zeroVatDisclaimerSha256 ==
        // sha256For(provider)`, not the version alone -- see `ZeroVatExportDisclaimer.sha256For`
        // KDoc. Without this column round-tripping, a `displayName`/text edit in a later wave that
        // does NOT also bump `VERSION` would silently stop matching the stored hash while every gate
        // that checks only the version keeps treating the connection as acknowledged.
        val zeroVatDisclaimerSha256: String?,
    )

    fun getOrCreateConnection(
        provider: AccountingExportProvider,
        now: LocalDateTime,
    ): ConnectionRow =
        transaction {
            existingConnectionRow(provider) ?: run {
                val id = Uuid.random()
                AccountingExportConnectionTable.insert {
                    it[AccountingExportConnectionTable.id] = id
                    it[AccountingExportConnectionTable.provider] = provider
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                requireNotNull(existingConnectionRow(provider)) { "AccountingExportConnection insert did not round-trip" }
            }
        }

    private fun existingConnectionRow(provider: AccountingExportProvider): ConnectionRow? =
        AccountingExportConnectionTable
            .selectAll()
            .where { AccountingExportConnectionTable.provider eq provider }
            .singleOrNull()
            ?.toConnectionRow()

    private fun org.jetbrains.exposed.v1.core.ResultRow.toConnectionRow(): ConnectionRow =
        ConnectionRow(
            id = this[AccountingExportConnectionTable.id],
            provider = this[AccountingExportConnectionTable.provider],
            hasToken = this[AccountingExportConnectionTable.tokenCiphertext] != null,
            tokenLast4 = this[AccountingExportConnectionTable.tokenLast4],
            connectedCompanyName = this[AccountingExportConnectionTable.connectedCompanyName],
            lastTestedAt = this[AccountingExportConnectionTable.lastTestedAt],
            zeroVatAcknowledgedAt = this[AccountingExportConnectionTable.zeroVatAcknowledgedAt],
            zeroVatDisclaimerVersion = this[AccountingExportConnectionTable.zeroVatDisclaimerVersion],
            zeroVatDisclaimerSha256 = this[AccountingExportConnectionTable.zeroVatDisclaimerSha256],
        )

    /** Row id stays STABLE across a token replacement -- an `UPDATE`, never a delete+insert (see
     * `SecretBox` class KDoc "AAD binding to the owning row": the id IS the AAD, so a fresh row
     * would make an old-but-still-around ciphertext elsewhere unopenable, and there is no such
     * ciphertext anywhere else here in the first place -- this simply keeps that invariant true by
     * construction). */
    fun upsertToken(
        provider: AccountingExportProvider,
        token: String,
        secretBox: SecretBox,
        now: LocalDateTime,
    ): ConnectionRow =
        transaction {
            val row = existingConnectionRow(provider) ?: getOrCreateConnection(provider = provider, now = now)
            val ciphertext = secretBox.seal(plaintext = token, aad = row.id.toString())
            AccountingExportConnectionTable.update({ AccountingExportConnectionTable.id eq row.id }) {
                it[tokenCiphertext] = ciphertext
                it[tokenLast4] = token.takeLast(4)
                it[connectedCompanyName] = null
                it[lastTestedAt] = null
                it[updatedAt] = now
            }
            requireNotNull(existingConnectionRow(provider))
        }

    fun readToken(
        provider: AccountingExportProvider,
        secretBox: SecretBox,
    ): String? =
        transaction {
            val row =
                AccountingExportConnectionTable
                    .selectAll()
                    .where { AccountingExportConnectionTable.provider eq provider }
                    .singleOrNull() ?: return@transaction null
            val ciphertext = row[AccountingExportConnectionTable.tokenCiphertext] ?: return@transaction null
            secretBox.open(sealed = ciphertext, aad = row[AccountingExportConnectionTable.id].toString())
        }

    fun markTestSuccess(
        provider: AccountingExportProvider,
        companyName: String?,
        now: LocalDateTime,
    ) {
        transaction {
            AccountingExportConnectionTable.update({ AccountingExportConnectionTable.provider eq provider }) {
                // Truncated defensively -- companyName comes straight from the provider's own
                // response body, and connected_company_name is VARCHAR(300); an untruncated write
                // would throw an ExposedSQLException here (surfacing as an opaque HTTP 500 from
                // testConnection) instead of just storing a shortened, still-useful name. Same
                // guard errorMessage already gets via MAX_ERROR_MESSAGE_LENGTH below.
                it[connectedCompanyName] = companyName?.take(MAX_COMPANY_NAME_LENGTH)
                it[lastTestedAt] = now
                it[updatedAt] = now
            }
        }
    }

    /** Called after a `401`/`403` from the provider -- see `AccountingExportPoller`/
     * `AccountingExportService` KDoc "Klassifikation". Never touches the token itself, only the
     * "last known good" timestamp, so the UI stops claiming a connection is verified. */
    fun markTestDue(provider: AccountingExportProvider) {
        transaction {
            AccountingExportConnectionTable.update({ AccountingExportConnectionTable.provider eq provider }) {
                it[lastTestedAt] = null
            }
        }
    }

    /** Nulls ONLY the token columns -- the row (and its zero-VAT quittance) survives, see
     * `43-accounting-export.kuml.kts` "Stolperfalle 9": deleting the row would force a re-quittance
     * on every token replacement, against Jobs' "einmal, nicht bei jedem Lauf". */
    fun removeToken(
        provider: AccountingExportProvider,
        now: LocalDateTime,
    ): ConnectionRow =
        transaction {
            AccountingExportConnectionTable.update({ AccountingExportConnectionTable.provider eq provider }) {
                it[tokenCiphertext] = null
                it[tokenLast4] = null
                it[connectedCompanyName] = null
                it[lastTestedAt] = null
                it[updatedAt] = now
            }
            requireNotNull(existingConnectionRow(provider))
        }

    fun acknowledgeZeroVat(
        provider: AccountingExportProvider,
        memberId: Uuid,
        disclaimerVersion: String,
        disclaimerSha256: String,
        now: LocalDateTime,
    ): ConnectionRow =
        transaction {
            val row = existingConnectionRow(provider) ?: getOrCreateConnection(provider = provider, now = now)
            AccountingExportConnectionTable.update({ AccountingExportConnectionTable.id eq row.id }) {
                it[zeroVatAcknowledgedAt] = now
                it[zeroVatAcknowledgedBy] = memberId
                it[zeroVatDisclaimerVersion] = disclaimerVersion
                it[zeroVatDisclaimerSha256] = disclaimerSha256
                it[updatedAt] = now
            }
            requireNotNull(existingConnectionRow(provider))
        }

    // ── Category mapping ────────────────────────────────────────────────────────────────────

    fun categoryMapByLedgerAccount(provider: AccountingExportProvider): Map<Uuid, MappedCategory> =
        transaction {
            AccountingExportCategoryMapTable
                .selectAll()
                .where { AccountingExportCategoryMapTable.provider eq provider }
                .associate {
                    it[AccountingExportCategoryMapTable.ledgerAccountId] to
                        MappedCategory(
                            externalCategoryId = it[AccountingExportCategoryMapTable.externalCategoryId],
                            externalCategoryName = it[AccountingExportCategoryMapTable.externalCategoryName],
                        )
                }
        }

    /** [id]/[created] of the row this call touched -- [created] `false` means an existing mapping for
     * [ledgerAccountId] was overwritten. Security review Runde 3, Befund 4 (Fund 2026-09-07):
     * `AccountingExportService.mapAccount` needs both to write a CREATE/UPDATE
     * [network.lapis.cloud.shared.domain.AuditEntityType.ACCOUNTING_EXPORT_MAPPING] audit entry, same
     * "existed-before-the-write, `row.id` after" idiom [AccountingExportService.setToken] already
     * uses for `ACCOUNTING_EXPORT_CONNECTION`. Previously returned `Unit`. */
    data class MapAccountResult(
        val id: Uuid,
        val created: Boolean,
    )

    fun mapAccount(
        provider: AccountingExportProvider,
        ledgerAccountId: Uuid,
        externalCategoryId: String,
        externalCategoryName: String?,
        mappedBy: Uuid,
        now: LocalDateTime,
    ): MapAccountResult =
        transaction {
            val existingId =
                AccountingExportCategoryMapTable
                    .select(AccountingExportCategoryMapTable.id)
                    .where {
                        (AccountingExportCategoryMapTable.provider eq provider) and
                            (AccountingExportCategoryMapTable.ledgerAccountId eq ledgerAccountId)
                    }.singleOrNull()
                    ?.get(AccountingExportCategoryMapTable.id)
            if (existingId != null) {
                AccountingExportCategoryMapTable.update({ AccountingExportCategoryMapTable.id eq existingId }) {
                    it[AccountingExportCategoryMapTable.externalCategoryId] = externalCategoryId
                    it[AccountingExportCategoryMapTable.externalCategoryName] = externalCategoryName
                    it[AccountingExportCategoryMapTable.mappedBy] = mappedBy
                    it[mappedAt] = now
                }
                MapAccountResult(id = existingId, created = false)
            } else {
                val newId = Uuid.random()
                AccountingExportCategoryMapTable.insert {
                    it[id] = newId
                    it[AccountingExportCategoryMapTable.provider] = provider
                    it[AccountingExportCategoryMapTable.ledgerAccountId] = ledgerAccountId
                    it[AccountingExportCategoryMapTable.externalCategoryId] = externalCategoryId
                    it[AccountingExportCategoryMapTable.externalCategoryName] = externalCategoryName
                    it[AccountingExportCategoryMapTable.mappedBy] = mappedBy
                    it[mappedAt] = now
                }
                MapAccountResult(id = newId, created = true)
            }
        }

    // ── Idempotency lookup ──────────────────────────────────────────────────────────────────

    fun alreadyExportedJournalEntryIds(
        provider: AccountingExportProvider,
        journalEntryIds: Collection<Uuid>,
    ): Set<Uuid> {
        if (journalEntryIds.isEmpty()) return emptySet()
        return transaction {
            AccountingExportItemTable
                .select(AccountingExportItemTable.journalEntryId)
                .where {
                    (AccountingExportItemTable.provider eq provider) and
                        (AccountingExportItemTable.status eq AccountingExportItemStatus.SUCCEEDED) and
                        (AccountingExportItemTable.journalEntryId inList journalEntryIds)
                }.map { it[AccountingExportItemTable.journalEntryId] }
                .toSet()
        }
    }

    /** Security review Fund 2026-09-07 (Runde 4, Befund 2): ids of [journalEntryIds] that currently
     * have an `UNKNOWN` item for [provider] -- the re-export guard
     * `network.lapis.cloud.server.rpc.AccountingExportService.buildPreview` applies via
     * [network.lapis.cloud.shared.domain.AccountingExportBlockerKind.UNRESOLVED_UNKNOWN_ITEMS]. Same
     * shape [alreadyExportedJournalEntryIds] already establishes, filtered on the opposite status --
     * an entry can appear in BOTH sets if an earlier run's item is `UNKNOWN` while a LATER run
     * already succeeded for the same entry; the caller subtracts [alreadyExportedJournalEntryIds]
     * from this result so an entry that is fully resolved is never blocked on a stale `UNKNOWN` from
     * before it succeeded. */
    fun unknownJournalEntryIds(
        provider: AccountingExportProvider,
        journalEntryIds: Collection<Uuid>,
    ): Set<Uuid> {
        if (journalEntryIds.isEmpty()) return emptySet()
        return transaction {
            AccountingExportItemTable
                .select(AccountingExportItemTable.journalEntryId)
                .where {
                    (AccountingExportItemTable.provider eq provider) and
                        (AccountingExportItemTable.status eq AccountingExportItemStatus.UNKNOWN) and
                        (AccountingExportItemTable.journalEntryId inList journalEntryIds)
                }.map { it[AccountingExportItemTable.journalEntryId] }
                .toSet()
        }
    }

    // ── Runs ─────────────────────────────────────────────────────────────────────────────────

    data class RunRow(
        val id: Uuid,
        val provider: AccountingExportProvider,
        val from: LocalDate,
        val to: LocalDate,
        val status: AccountingExportRunStatus,
        val startedAt: LocalDateTime,
        val finishedAt: LocalDateTime?,
        val total: Int,
        val succeeded: Int,
        val failed: Int,
        val skipped: Int,
        val unknown: Int,
    )

    fun hasActiveRun(provider: AccountingExportProvider): Boolean =
        transaction {
            AccountingExportRunTable
                .select(AccountingExportRunTable.id)
                .where { AccountingExportRunTable.activeKey eq activeKeyOf(provider) }
                .limit(1)
                .count() > 0
        }

    /** Creates the run row (`RUNNING`, `active_key` set) and one item per [vouchers] entry --
     * already-exported vouchers get an immediately-terminal `SKIPPED_ALREADY_EXPORTED` item, every
     * other one starts `PENDING`. Throws on a UNIQUE-constraint violation
     * (`uq_accounting_export_run_active`) if a concurrent caller won the race for [provider]'s
     * active-run slot first -- the caller (`AccountingExportService.startExport`) translates that
     * into a [network.lapis.cloud.shared.rpc.ConflictException]. */
    fun createRun(
        provider: AccountingExportProvider,
        from: LocalDate,
        to: LocalDate,
        startedBy: Uuid,
        now: LocalDateTime,
        vouchers: List<PlannedVoucher>,
    ): Uuid =
        transaction {
            val runId = Uuid.random()
            val skippedItemCount = vouchers.count { it.alreadyExported }
            AccountingExportRunTable.insert {
                it[id] = runId
                it[AccountingExportRunTable.provider] = provider
                it[periodFrom] = from
                it[periodTo] = to
                it[status] = AccountingExportRunStatus.RUNNING
                it[activeKey] = activeKeyOf(provider)
                it[AccountingExportRunTable.startedBy] = startedBy
                it[startedAt] = now
                it[totalCount] = vouchers.size
                it[succeededCount] = 0
                it[failedCount] = 0
                it[skippedCount] = skippedItemCount
                it[unknownCount] = 0
            }
            vouchers.forEach { voucher ->
                val alreadyExported = voucher.alreadyExported
                val externalCategoryId =
                    requireNotNull(voucher.externalCategoryId) {
                        "createRun called with an unmapped voucher (journalEntryId=${voucher.journalEntryId}) -- " +
                            "callers must only pass plan.exportable == true plans."
                    }
                AccountingExportItemTable.insert {
                    it[id] = Uuid.random()
                    it[AccountingExportItemTable.runId] = runId
                    it[AccountingExportItemTable.provider] = provider
                    it[journalEntryId] = voucher.journalEntryId
                    it[entryDate] = voucher.entryDate
                    it[AccountingExportItemTable.externalCategoryId] = externalCategoryId
                    it[voucherNumber] = voucher.voucherNumber
                    it[direction] = voucher.direction
                    it[grossAmount] = voucher.grossAmount
                    it[status] =
                        if (alreadyExported) AccountingExportItemStatus.SKIPPED_ALREADY_EXPORTED else AccountingExportItemStatus.PENDING
                    it[exportedKey] = null
                    it[attempts] = 0
                    it[finishedAt] = if (alreadyExported) now else null
                }
            }
            runId
        }

    fun getRun(runId: Uuid): RunRow? =
        transaction {
            AccountingExportRunTable
                .selectAll()
                .where { AccountingExportRunTable.id eq runId }
                .singleOrNull()
                ?.toRunRow()
        }

    fun getLatestRun(provider: AccountingExportProvider): RunRow? =
        transaction {
            AccountingExportRunTable
                .selectAll()
                .where { AccountingExportRunTable.provider eq provider }
                .orderBy(AccountingExportRunTable.startedAt, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
                ?.toRunRow()
        }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toRunRow(): RunRow =
        RunRow(
            id = this[AccountingExportRunTable.id],
            provider = this[AccountingExportRunTable.provider],
            from = this[AccountingExportRunTable.periodFrom],
            to = this[AccountingExportRunTable.periodTo],
            status = this[AccountingExportRunTable.status],
            startedAt = this[AccountingExportRunTable.startedAt],
            finishedAt = this[AccountingExportRunTable.finishedAt],
            total = this[AccountingExportRunTable.totalCount],
            succeeded = this[AccountingExportRunTable.succeededCount],
            failed = this[AccountingExportRunTable.failedCount],
            skipped = this[AccountingExportRunTable.skippedCount],
            unknown = this[AccountingExportRunTable.unknownCount],
        )

    /** Every non-terminal run across every provider -- there is at most one per provider, enforced
     * by `uq_accounting_export_run_active`, so this is a cheap query. Fund 2026-09-07 review Runde 2
     * (Befund 4): fed into `AccountingExportPoller.tick`'s Phase B (`finalizeTouchedRuns`) EVERY
     * tick, unconditionally, as a self-healing safety net alongside the reap-phase/send-phase
     * touched-run sets -- neither of THOSE two ever discovers a run that started `RUNNING` with zero
     * `PENDING` items and was never otherwise touched by any codepath (e.g. a crash between
     * `createRun` committing and `AccountingExportService.startExport`'s own follow-up
     * `recomputeRunCounts` call -- see that call site's own KDoc for the normal-path fix this covers
     * as a backstop, not a replacement). Previously called from nowhere at all (this KDoc used to
     * claim the poller already iterated it, which was false). */
    fun activeRuns(): List<RunRow> =
        transaction {
            AccountingExportRunTable
                .selectAll()
                .where {
                    (AccountingExportRunTable.status eq AccountingExportRunStatus.PLANNED) or
                        (AccountingExportRunTable.status eq AccountingExportRunStatus.RUNNING)
                }.map { it.toRunRow() }
        }

    /** Resets every `FAILED` item of [runId] back to `PENDING`, reopens the run if it had already
     * reached a terminal state. Deliberately does NOT touch `UNKNOWN` items -- those were either
     * reaped by [reapStaleClaims] or aborted mid-`SENDING` by [abortRun], both cases where the item
     * may already be sitting in lexoffice; resending it here without a human first checking lexoffice
     * risks a genuine duplicate voucher (lexoffice has no idempotency key on `POST /v1/vouchers`), so
     * `UNKNOWN` items are left exactly as they are by this bulk reopen. Throws on a unique-constraint
     * violation if another run for the same provider became active in the meantime -- same
     * caller-translates-to-Conflict contract as [createRun]. */
    fun retryFailed(
        runId: Uuid,
        now: LocalDateTime,
    ): RunRow? =
        transaction {
            val run =
                AccountingExportRunTable.selectAll().where { AccountingExportRunTable.id eq runId }.singleOrNull()
                    ?: return@transaction null
            AccountingExportItemTable.update({
                (AccountingExportItemTable.runId eq runId) and (AccountingExportItemTable.status eq AccountingExportItemStatus.FAILED)
            }) {
                it[status] = AccountingExportItemStatus.PENDING
                it[attempts] = 0
                it[nextAttemptAt] = null
                it[errorCode] = null
                it[errorMessage] = null
                it[finishedAt] = null
                it[claimedAt] = null
            }
            val runStatus = run[AccountingExportRunTable.status]
            // ABORTED belongs in this reopen set too (Fund 2026-09-07): the item-level UPDATE above
            // resets that run's FAILED items -- including the ones abortRun itself created with
            // errorCode "ABORTED_BY_USER" -- back to PENDING regardless of the run's own status, and
            // duePendingItemIds (below) does not filter by run status either. Without reopening the
            // run here, the poller would actually resend those items while the run row stays
            // ABORTED/active_key=NULL forever -- UI shows "abgebrochen" while sends happen behind
            // it, and hasActiveRun() being false lets a concurrent startExport race a second run for
            // the same provider. Reopening mirrors the COMPLETED/COMPLETED_WITH_ERRORS case exactly.
            if (runStatus == AccountingExportRunStatus.COMPLETED ||
                runStatus == AccountingExportRunStatus.COMPLETED_WITH_ERRORS ||
                runStatus == AccountingExportRunStatus.ABORTED
            ) {
                AccountingExportRunTable.update({ AccountingExportRunTable.id eq runId }) {
                    it[status] = AccountingExportRunStatus.RUNNING
                    it[activeKey] = activeKeyOf(run[AccountingExportRunTable.provider])
                    it[finishedAt] = null
                }
            }
            recomputeRunCountsInternal(runId = runId, now = now)
            AccountingExportRunTable
                .selectAll()
                .where { AccountingExportRunTable.id eq runId }
                .single()
                .toRunRow()
        }

    /** `PENDING` items (never sent) are marked `FAILED` (`errorCode = "ABORTED_BY_USER"`) --
     * `retryFailed` may safely reopen those, nothing was ever transmitted. `SENDING` items are marked
     * `UNKNOWN` instead (`errorCode = "ABORTED_WHILE_SENDING"`), NEVER `FAILED` -- same doctrine as
     * the Phase A0 stale-claim reaper ([reapStaleClaims] / `AccountingExportPoller` KDoc "Phase A0"):
     * a `SENDING` item may already be in flight to lexoffice (the `POST /v1/vouchers` may have
     * succeeded there even though this process never saw the response), and lexoffice has no
     * idempotency key on that endpoint, so resending it risks creating a genuine DUPLICATE voucher.
     * `retryFailed` only reopens `FAILED` items, so routing these through `UNKNOWN` instead means they
     * are never silently resent -- resolution is left to a human, same as a reaped stale claim.
     * Fund 2026-09-07 security review Runde 3, Befund 1: previously both `PENDING` and `SENDING`
     * landed on `FAILED` here, and `retryFailed` reset every `FAILED` item uniformly -- reopening a
     * run this way could resend an item that was already accepted by lexoffice, producing a duplicate
     * voucher in the tax advisor's books. There is no dedicated item-level `ABORTED` status (see
     * `43-accounting-export.kuml.kts`); items already `SUCCEEDED`/`FAILED`/`SKIPPED_ALREADY_EXPORTED`
     * are never touched either way. */
    fun abortRun(
        runId: Uuid,
        now: LocalDateTime,
    ): RunRow? =
        transaction {
            AccountingExportItemTable.update({
                (AccountingExportItemTable.runId eq runId) and (AccountingExportItemTable.status eq AccountingExportItemStatus.PENDING)
            }) {
                it[status] = AccountingExportItemStatus.FAILED
                it[errorCode] = "ABORTED_BY_USER"
                it[errorMessage] = "Lauf durch Nutzer abgebrochen."
                it[finishedAt] = now
            }
            AccountingExportItemTable.update({
                (AccountingExportItemTable.runId eq runId) and (AccountingExportItemTable.status eq AccountingExportItemStatus.SENDING)
            }) {
                it[status] = AccountingExportItemStatus.UNKNOWN
                it[errorCode] = "ABORTED_WHILE_SENDING"
                it[errorMessage] =
                    "Lauf durch Nutzer abgebrochen, während der Beleg möglicherweise bereits an lexoffice " +
                    "unterwegs war. Bitte manuell in lexoffice prüfen, bevor erneut gesendet wird."
                it[finishedAt] = now
            }
            AccountingExportRunTable.update({ AccountingExportRunTable.id eq runId }) {
                it[status] = AccountingExportRunStatus.ABORTED
                it[activeKey] = null
                it[finishedAt] = now
            }
            recomputeRunCountsInternal(runId = runId, now = now)
            AccountingExportRunTable
                .selectAll()
                .where { AccountingExportRunTable.id eq runId }
                .singleOrNull()
                ?.toRunRow()
        }

    data class ItemRow(
        val id: Uuid,
        val runId: Uuid,
        val provider: AccountingExportProvider,
        val journalEntryId: Uuid,
        val entryDate: LocalDate,
        val externalCategoryId: String,
        val voucherNumber: String,
        val direction: AccountingExportDirection,
        val grossAmount: BigDecimal,
        val attempts: Int,
    )

    fun listRunItems(
        runId: Uuid,
        status: AccountingExportItemStatus?,
        offset: Int,
        limit: Int,
    ): List<org.jetbrains.exposed.v1.core.ResultRow> =
        transaction {
            // Condition built up-front as a nullable Op<Boolean> -- this codebase does not use
            // Exposed's separate `andWhere` extension anywhere, see CrmContactStore.list's own
            // comment establishing this idiom.
            var condition: org.jetbrains.exposed.v1.core.Op<Boolean> = AccountingExportItemTable.runId eq runId
            if (status != null) condition = condition and (AccountingExportItemTable.status eq status)
            AccountingExportItemTable
                .selectAll()
                .where { condition }
                .orderBy(AccountingExportItemTable.voucherNumber, SortOrder.ASC)
                .limit(limit.coerceIn(1, MAX_LIST_ITEMS_LIMIT))
                .offset(offset.coerceAtLeast(0).toLong())
                .toList()
        }

    /** Security review Fund 2026-09-07 (Runde 5, MAJOR): every `UNKNOWN` item for [provider] across
     * ALL runs -- not scoped to a single `runId` like [listRunItems]. Fixes a dead end
     * `resolveUnknown` itself otherwise leaves open: [AccountingExportRunDto]/[getLatestRun] only
     * ever surfaces the MOST RECENT run for a provider, so once a later run starts (e.g. exporting
     * February after an earlier January run left an `UNKNOWN` item behind), that earlier run --
     * and the only screen path to its `UNKNOWN` items -- becomes unreachable from the UI, even
     * though `resolveUnknownItem` itself works fine on any item id. This is that missing listing;
     * `network.lapis.cloud.server.rpc.AccountingExportService.listUnknownItems` is its only caller.
     * Ordered oldest-first (`finished_at ASC`, the point [markUnknown]/[reapStaleClaims]/[abortRun]
     * write) -- deliberately the OPPOSITE of [listRunItems]' voucher-number ordering (meaningful only
     * within one run): the longest-unresolved items should surface first here. */
    fun unknownItems(
        provider: AccountingExportProvider,
        offset: Int,
        limit: Int,
    ): List<org.jetbrains.exposed.v1.core.ResultRow> =
        transaction {
            AccountingExportItemTable
                .selectAll()
                .where {
                    (AccountingExportItemTable.provider eq provider) and
                        (AccountingExportItemTable.status eq AccountingExportItemStatus.UNKNOWN)
                }.orderBy(AccountingExportItemTable.finishedAt, SortOrder.ASC)
                .limit(limit.coerceIn(1, MAX_LIST_ITEMS_LIMIT))
                .offset(offset.coerceAtLeast(0).toLong())
                .toList()
        }

    // ── Poller-facing ───────────────────────────────────────────────────────────────────────

    /** Ids of `PENDING` items across [providers] that are due to send now (no backoff pending). */
    fun duePendingItemIds(
        providers: Collection<AccountingExportProvider>,
        now: LocalDateTime,
        limit: Int,
    ): List<Uuid> {
        if (providers.isEmpty()) return emptyList()
        return transaction {
            AccountingExportItemTable
                .select(AccountingExportItemTable.id)
                .where {
                    (AccountingExportItemTable.provider inList providers) and
                        (AccountingExportItemTable.status eq AccountingExportItemStatus.PENDING) and
                        ((AccountingExportItemTable.nextAttemptAt.isNull()) or (AccountingExportItemTable.nextAttemptAt lessEq now))
                }.orderBy(AccountingExportItemTable.claimedAt, SortOrder.ASC)
                .limit(limit)
                .map { it[AccountingExportItemTable.id] }
        }
    }

    /** Atomic claim -- `null` if lost the race (another claimer, or the reaper). */
    fun claim(
        id: Uuid,
        now: LocalDateTime,
    ): ItemRow? =
        transaction {
            val current =
                AccountingExportItemTable
                    .select(AccountingExportItemTable.attempts)
                    .where { AccountingExportItemTable.id eq id }
                    .singleOrNull() ?: return@transaction null
            val updated =
                AccountingExportItemTable.update({
                    (AccountingExportItemTable.id eq id) and (AccountingExportItemTable.status eq AccountingExportItemStatus.PENDING)
                }) {
                    it[status] = AccountingExportItemStatus.SENDING
                    it[claimedAt] = now
                    it[attempts] = current[AccountingExportItemTable.attempts] + 1
                }
            if (updated != 1) return@transaction null
            AccountingExportItemTable
                .selectAll()
                .where { AccountingExportItemTable.id eq id }
                .single()
                .let {
                    ItemRow(
                        id = it[AccountingExportItemTable.id],
                        runId = it[AccountingExportItemTable.runId],
                        provider = it[AccountingExportItemTable.provider],
                        journalEntryId = it[AccountingExportItemTable.journalEntryId],
                        entryDate = it[AccountingExportItemTable.entryDate],
                        externalCategoryId = it[AccountingExportItemTable.externalCategoryId],
                        voucherNumber = it[AccountingExportItemTable.voucherNumber],
                        direction = it[AccountingExportItemTable.direction],
                        grossAmount = it[AccountingExportItemTable.grossAmount],
                        attempts = it[AccountingExportItemTable.attempts],
                    )
                }
        }

    fun isAlreadyExported(
        provider: AccountingExportProvider,
        journalEntryId: Uuid,
    ): Boolean =
        transaction {
            AccountingExportItemTable
                .select(AccountingExportItemTable.id)
                .where {
                    (AccountingExportItemTable.provider eq provider) and
                        (AccountingExportItemTable.journalEntryId eq journalEntryId) and
                        (AccountingExportItemTable.status eq AccountingExportItemStatus.SUCCEEDED)
                }.limit(1)
                .count() > 0
        }

    fun markSkippedAlreadyExported(
        id: Uuid,
        now: LocalDateTime,
    ) {
        transaction {
            AccountingExportItemTable.update({ AccountingExportItemTable.id eq id }) {
                it[status] = AccountingExportItemStatus.SKIPPED_ALREADY_EXPORTED
                it[finishedAt] = now
            }
        }
    }

    fun markSucceeded(
        id: Uuid,
        provider: AccountingExportProvider,
        journalEntryId: Uuid,
        externalVoucherId: String,
        now: LocalDateTime,
    ) {
        transaction {
            AccountingExportItemTable.update({ AccountingExportItemTable.id eq id }) {
                it[status] = AccountingExportItemStatus.SUCCEEDED
                // Truncated defensively -- externalVoucherId comes straight from the provider's own
                // response body, and external_voucher_id is VARCHAR(64); an untruncated write would
                // throw an ExposedSQLException here (Poller:144 only logs it, the item stays
                // SENDING and is later wrongly reaped to UNKNOWN even though lexoffice DID accept
                // the voucher). Same guard errorMessage already gets via MAX_ERROR_MESSAGE_LENGTH.
                it[AccountingExportItemTable.externalVoucherId] = externalVoucherId.take(MAX_EXTERNAL_VOUCHER_ID_LENGTH)
                it[exportedKey] = exportedKeyOf(provider = provider, journalEntryId = journalEntryId)
                it[errorCode] = null
                it[errorMessage] = null
                it[finishedAt] = now
            }
        }
    }

    fun markFailed(
        id: Uuid,
        errorCode: String,
        errorMessage: String?,
        now: LocalDateTime,
    ) {
        transaction {
            AccountingExportItemTable.update({ AccountingExportItemTable.id eq id }) {
                it[status] = AccountingExportItemStatus.FAILED
                it[AccountingExportItemTable.errorCode] = errorCode
                it[AccountingExportItemTable.errorMessage] = errorMessage?.take(MAX_ERROR_MESSAGE_LENGTH)
                it[finishedAt] = now
            }
        }
    }

    fun markUnknown(
        id: Uuid,
        errorCode: String,
        now: LocalDateTime,
    ) {
        transaction {
            AccountingExportItemTable.update({ AccountingExportItemTable.id eq id }) {
                it[status] = AccountingExportItemStatus.UNKNOWN
                it[AccountingExportItemTable.errorCode] = errorCode
                it[finishedAt] = now
            }
        }
    }

    /** Security review Fund 2026-09-07 (Runde 4, Befund 2): outcome of [resolveUnknown] --
     * distinguishes "id doesn't exist" from "exists but is not currently `UNKNOWN`" so the caller
     * (`AccountingExportService.resolveUnknownItem`) can throw the right exception type
     * ([network.lapis.cloud.shared.rpc.NotFoundException] vs
     * [network.lapis.cloud.shared.rpc.ConflictException]) instead of collapsing both into `null`. */
    sealed interface ResolveUnknownOutcome {
        data object NotFound : ResolveUnknownOutcome

        /** [itemId] exists but its status was not `UNKNOWN` at UPDATE time -- either it was never
         * `UNKNOWN` (a normal PENDING/SENDING/SUCCEEDED/FAILED/SKIPPED item), or a concurrent
         * `resolveUnknownItem`/`retryFailed`/`reapStaleClaims` already moved it since the caller last
         * read it. */
        data object NotCurrentlyUnknown : ResolveUnknownOutcome

        data class Resolved(
            val row: org.jetbrains.exposed.v1.core.ResultRow,
        ) : ResolveUnknownOutcome
    }

    /** Security review Fund 2026-09-07 (Runde 4, Befund 2): moves item [id] out of `UNKNOWN` after a
     * TREASURER/ADMIN manually checked lexoffice -- see
     * [network.lapis.cloud.shared.domain.AccountingExportUnknownItemResolution] KDoc for the two
     * outcomes this maps to. `CONFIRMED_NOT_SENT` -> `FAILED` (errorCode
     * `"CONFIRMED_NOT_SENT_TO_PROVIDER"`), exactly the shape [retryFailed] already reopens back to
     * `PENDING`. `CONFIRMED_SENT` -> `SUCCEEDED` with [exportedKeyOf] set -- the SAME
     * idempotency-guard shape [markSucceeded] produces for a normal successful send, so
     * [alreadyExportedJournalEntryIds]/[isAlreadyExported] recognize this entry from here on.
     * [externalVoucherId] is nullable here (unlike [markSucceeded]'s non-null parameter) -- a human
     * may confirm the voucher exists without having copied its id.
     *
     * The status re-check in the UPDATE's own WHERE is the same concurrency guard [reapStaleClaims]
     * already documents -- an item a concurrent writer already moved out of `UNKNOWN` (another
     * `resolveUnknownItem` call, or a fresh `SENDING` claim that raced in) is left untouched, never
     * clobbered; the caller sees [ResolveUnknownOutcome.NotCurrentlyUnknown] and reports a 409
     * instead of silently double-applying a human's decision. Recomputes the owning run's counts
     * afterward, same as every other item-mutating function here. */
    fun resolveUnknown(
        id: Uuid,
        resolution: AccountingExportUnknownItemResolution,
        externalVoucherId: String?,
        now: LocalDateTime,
    ): ResolveUnknownOutcome =
        transaction {
            val before =
                AccountingExportItemTable
                    .selectAll()
                    .where { AccountingExportItemTable.id eq id }
                    .singleOrNull() ?: return@transaction ResolveUnknownOutcome.NotFound
            val runId = before[AccountingExportItemTable.runId]
            val provider = before[AccountingExportItemTable.provider]
            val journalEntryId = before[AccountingExportItemTable.journalEntryId]
            val updated =
                AccountingExportItemTable.update({
                    (AccountingExportItemTable.id eq id) and (AccountingExportItemTable.status eq AccountingExportItemStatus.UNKNOWN)
                }) {
                    when (resolution) {
                        AccountingExportUnknownItemResolution.CONFIRMED_NOT_SENT -> {
                            it[status] = AccountingExportItemStatus.FAILED
                            it[errorCode] = "CONFIRMED_NOT_SENT_TO_PROVIDER"
                            it[errorMessage] =
                                // Welle V1.4.5.4 "sevDesk-Live-Anbindung" (Fund): `provider` is
                                // already resolved above -- this note used to hardcode "Lexware
                                // Office", which would have been wrong for a SEVDESK item.
                                "Manuell durch Schatzmeister/Admin geprüft: Beleg wurde nicht bei ${provider.displayName} gefunden."
                        }
                        AccountingExportUnknownItemResolution.CONFIRMED_SENT -> {
                            it[status] = AccountingExportItemStatus.SUCCEEDED
                            it[exportedKey] = exportedKeyOf(provider = provider, journalEntryId = journalEntryId)
                            it[AccountingExportItemTable.externalVoucherId] =
                                externalVoucherId?.take(MAX_EXTERNAL_VOUCHER_ID_LENGTH)
                            it[errorCode] = null
                            it[errorMessage] =
                                "Manuell durch Schatzmeister/Admin geprüft: Beleg wurde bei ${provider.displayName} gefunden."
                        }
                    }
                    it[finishedAt] = now
                }
            if (updated != 1) return@transaction ResolveUnknownOutcome.NotCurrentlyUnknown
            recomputeRunCountsInternal(runId = runId, now = now)
            val row = AccountingExportItemTable.selectAll().where { AccountingExportItemTable.id eq id }.single()
            ResolveUnknownOutcome.Resolved(row)
        }

    fun markRetryScheduled(
        id: Uuid,
        nextAttemptAt: LocalDateTime,
        errorCode: String,
        errorMessage: String?,
    ) {
        transaction {
            AccountingExportItemTable.update({ AccountingExportItemTable.id eq id }) {
                it[status] = AccountingExportItemStatus.PENDING
                it[AccountingExportItemTable.nextAttemptAt] = nextAttemptAt
                it[AccountingExportItemTable.errorCode] = errorCode
                it[AccountingExportItemTable.errorMessage] = errorMessage?.take(MAX_ERROR_MESSAGE_LENGTH)
            }
        }
    }

    /** Phase A0 -- any `SENDING` item claimed before [staleCutoff] is reset to `UNKNOWN`, NEVER back
     * to `PENDING` (unlike `WebhookDeliveryQueue.reapStaleClaims`) -- see `AccountingExportPoller`
     * class KDoc "Phase A0" for why: lexoffice has no idempotency key, so a resend after a crash
     * mid-attempt risks a genuine duplicate voucher, not merely a duplicate delivery attempt.
     *
     * Returns the `run_id` of every reaped item (one entry per item, so callers can both count items
     * via `.size` and derive the DISTINCT touched runs) -- NOT just a count. Fund 2026-09-07: a plain
     * `Int` here starved `AccountingExportPoller`'s Phase B (`recomputeRunCounts`) of exactly the run
     * ids it needed, so a run whose only remaining item got reaped here stayed `RUNNING` forever
     * (counts never updated, provider permanently locked via `active_key`) -- contradicting this
     * class' own KDoc "After every item this tick (sent or reaped), the owning run's counts are
     * recomputed". */
    fun reapStaleClaims(
        staleCutoff: LocalDateTime,
        now: LocalDateTime,
    ): List<Uuid> =
        transaction {
            val stale =
                AccountingExportItemTable
                    .select(AccountingExportItemTable.id, AccountingExportItemTable.runId)
                    .where {
                        (AccountingExportItemTable.status eq AccountingExportItemStatus.SENDING) and
                            (AccountingExportItemTable.claimedAt less staleCutoff)
                    }.map { it[AccountingExportItemTable.id] to it[AccountingExportItemTable.runId] }
            if (stale.isEmpty()) return@transaction emptyList()
            // Fund 2026-09-07 review (Runde 2, Befund 2): the SELECT above is only a snapshot -- the
            // status re-check in the UPDATE's own WHERE is what makes this safe against a
            // concurrent writer (abortRun, retryFailed, or the poller's own send-phase finishing this
            // exact item between the SELECT and here) racing in on the SAME item. Without it this
            // UPDATE would blindly overwrite whatever the concurrent writer just committed, keyed
            // only by id -- e.g. clobbering an item abortRun just marked UNKNOWN/"ABORTED_WHILE_SENDING"
            // back to UNKNOWN/"STALE_CLAIM_REAPED", losing the more specific errorCode. Re-checking
            // status also means an item a concurrent writer already moved to SUCCEEDED is silently
            // excluded from this UPDATE (0 rows for it) rather than corrupted -- the CHECK constraint
            // chk_accounting_export_item_exported_key would otherwise reject the whole reap
            // transaction outright for that case (SUCCEEDED requires exported_key, which this UPDATE
            // never sets), so the predicate protects both outcomes with one condition.
            AccountingExportItemTable.update({
                (AccountingExportItemTable.id inList stale.map { it.first }) and
                    (AccountingExportItemTable.status eq AccountingExportItemStatus.SENDING)
            }) {
                it[status] = AccountingExportItemStatus.UNKNOWN
                it[errorCode] = "STALE_CLAIM_REAPED"
                it[finishedAt] = now
            }
            stale.map { it.second }
        }

    /** Recomputes `total/succeeded/failed/skipped/unknown` counts from the actual item rows and, if
     * none remain `PENDING`/`SENDING`, finalizes the run (`COMPLETED`/`COMPLETED_WITH_ERRORS`,
     * `active_key = NULL`). Safe to call redundantly (idempotent). */
    fun recomputeRunCounts(
        runId: Uuid,
        now: LocalDateTime,
    ) {
        transaction { recomputeRunCountsInternal(runId = runId, now = now) }
    }

    private fun recomputeRunCountsInternal(
        runId: Uuid,
        now: LocalDateTime,
    ) {
        val items = AccountingExportItemTable.selectAll().where { AccountingExportItemTable.runId eq runId }.toList()
        val byStatus = items.groupingBy { it[AccountingExportItemTable.status] }.eachCount()
        val pending = byStatus[AccountingExportItemStatus.PENDING] ?: 0
        val sending = byStatus[AccountingExportItemStatus.SENDING] ?: 0
        val succeeded = byStatus[AccountingExportItemStatus.SUCCEEDED] ?: 0
        val failed = byStatus[AccountingExportItemStatus.FAILED] ?: 0
        val skipped = byStatus[AccountingExportItemStatus.SKIPPED_ALREADY_EXPORTED] ?: 0
        val unknown = byStatus[AccountingExportItemStatus.UNKNOWN] ?: 0

        val currentStatus =
            AccountingExportRunTable
                .select(AccountingExportRunTable.status, AccountingExportRunTable.provider)
                .where { AccountingExportRunTable.id eq runId }
                .singleOrNull() ?: return
        // A run already ABORTED stays ABORTED regardless of remaining item counts -- abortRun sets
        // that status itself before this function ever runs for that transition.
        if (currentStatus[AccountingExportRunTable.status] == AccountingExportRunStatus.ABORTED) {
            AccountingExportRunTable.update({ AccountingExportRunTable.id eq runId }) {
                it[succeededCount] = succeeded
                it[failedCount] = failed
                it[skippedCount] = skipped
                it[unknownCount] = unknown
            }
            return
        }

        val stillRunning = pending > 0 || sending > 0
        AccountingExportRunTable.update({ AccountingExportRunTable.id eq runId }) {
            it[succeededCount] = succeeded
            it[failedCount] = failed
            it[skippedCount] = skipped
            it[unknownCount] = unknown
            if (!stillRunning) {
                it[status] =
                    if (failed == 0 &&
                        unknown == 0
                    ) {
                        AccountingExportRunStatus.COMPLETED
                    } else {
                        AccountingExportRunStatus.COMPLETED_WITH_ERRORS
                    }
                it[activeKey] = null
                it[finishedAt] = now
            }
        }
    }

    // ── Shadow-column helpers (see class KDoc) ─────────────────────────────────────────────

    private fun activeKeyOf(provider: AccountingExportProvider): String = provider.name

    private fun exportedKeyOf(
        provider: AccountingExportProvider,
        journalEntryId: Uuid,
    ): String = "$provider:$journalEntryId"

    private const val MAX_LIST_ITEMS_LIMIT = 200
    private const val MAX_ERROR_MESSAGE_LENGTH = 500

    /** Matches `accounting_export_connection.connected_company_name VARCHAR(300)` (see
     * `V25__accounting_export.sql`) -- [markTestSuccess] writes lexoffice's own profile response
     * here, unbounded from this server's own perspective. */
    private const val MAX_COMPANY_NAME_LENGTH = 300

    /** Matches `accounting_export_item.external_voucher_id VARCHAR(64)` (see
     * `V25__accounting_export.sql`) -- [markSucceeded] writes lexoffice's own voucher-response `id`
     * here, unbounded from this server's own perspective. */
    private const val MAX_EXTERNAL_VOUCHER_ID_LENGTH = 64
}
