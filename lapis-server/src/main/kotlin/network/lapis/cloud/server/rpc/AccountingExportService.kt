package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.accounting.export.AccountingExportPlan
import network.lapis.cloud.server.accounting.export.AccountingExportPlanner
import network.lapis.cloud.server.accounting.export.AccountingExportProviderAdapter
import network.lapis.cloud.server.accounting.export.AccountingExportStore
import network.lapis.cloud.server.accounting.export.CategoryListOutcome
import network.lapis.cloud.server.accounting.export.ConnectionTestOutcome
import network.lapis.cloud.server.accounting.export.buildJournalExportRequest
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.crypto.SecretBox
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AccountingExportBlockerDto
import network.lapis.cloud.shared.domain.AccountingExportBlockerKind
import network.lapis.cloud.shared.domain.AccountingExportConnectionDto
import network.lapis.cloud.shared.domain.AccountingExportItemDto
import network.lapis.cloud.shared.domain.AccountingExportItemStatus
import network.lapis.cloud.shared.domain.AccountingExportPreviewDto
import network.lapis.cloud.shared.domain.AccountingExportProvider
import network.lapis.cloud.shared.domain.AccountingExportRunDto
import network.lapis.cloud.shared.domain.AccountingExportUnknownItemResolution
import network.lapis.cloud.shared.domain.AccountingExportZeroVatDisclaimerDto
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.ExternalCategoryDto
import network.lapis.cloud.shared.domain.UnmappedAccountDto
import network.lapis.cloud.shared.domain.VoucherPreviewLineDto
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IAccountingExportService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.4.5.3 "lexoffice-Live-Anbindung" -- implements [IAccountingExportService]. See that
 * interface's own KDoc for the full, deliberately-NARROWER-than-[IAccountingService] role rationale
 * ([ACCOUNTING_EXPORT_ROLES] below, TREASURER/ADMIN, no BOARD, on EVERY method including reads).
 *
 * Every DB access goes through [AccountingExportStore] -- this class only orchestrates: role
 * checks, rate limiting, calling out to the provider adapter (HTTP, deliberately OUTSIDE any
 * `transaction {}` -- a network call must never hold a DB connection/transaction open), and mapping
 * store rows to DTOs.
 */
class AccountingExportService internal constructor(
    private val call: ApplicationCall,
    private val secretBox: SecretBox?,
    private val adaptersByProvider: Map<AccountingExportProvider, AccountingExportProviderAdapter>,
    private val testRateLimiter: FederationInboxRateLimiter,
    private val startExportRateLimiter: FederationInboxRateLimiter,
    // Security review Runde 3, Befund 5 (Fund 2026-09-07): previewExport used to have no budget at
    // all, unlike every other expensive method on this service -- see the call site below and
    // Application.kt's accountingExportPreviewRateLimiter KDoc.
    private val previewRateLimiter: FederationInboxRateLimiter,
) : IAccountingExportService {
    override suspend fun getConnection(provider: AccountingExportProvider): AccountingExportConnectionDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_EXPORT_ROLES)
        val row = transaction { AccountingExportStore.getOrCreateConnection(provider = provider, now = DbClock.nowLocalDateTime()) }
        return row.toDto()
    }

    override suspend fun setToken(
        provider: AccountingExportProvider,
        token: String,
    ): AccountingExportConnectionDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_EXPORT_ROLES)
        requireValidTokenFormat(token)
        val box =
            secretBox
                ?: throw ConflictException("Kein Verschlüsselungsschlüssel konfiguriert -- Anbieter-Token kann nicht gespeichert werden.")
        val now = DbClock.nowLocalDateTime()
        val existed = transaction { AccountingExportStore.getOrCreateConnection(provider = provider, now = now).hasToken }
        val row = AccountingExportStore.upsertToken(provider = provider, token = token, secretBox = box, now = now)
        transaction {
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.ACCOUNTING_EXPORT_CONNECTION,
                entityId = row.id,
                action = if (existed) AuditAction.UPDATE else AuditAction.CREATE,
            )
        }
        return row.toDto()
    }

    override suspend fun testConnection(provider: AccountingExportProvider): AccountingExportConnectionDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_EXPORT_ROLES)
        if (!testRateLimiter.checkAndRecord("member:${current.memberId}")) {
            throw ConflictException("Zu viele Anfragen -- bitte später erneut versuchen.")
        }
        val box = secretBox ?: throw ConflictException("Kein Verschlüsselungsschlüssel konfiguriert.")
        val token =
            AccountingExportStore.readToken(provider = provider, secretBox = box)
                ?: throw ConflictException("Kein Token hinterlegt.")
        val adapter = adaptersByProvider[provider] ?: throw ConflictException("Anbieter nicht verfügbar.")
        val now = DbClock.nowLocalDateTime()
        return when (val outcome = adapter.testConnection(token)) {
            is ConnectionTestOutcome.Success -> {
                AccountingExportStore.markTestSuccess(provider = provider, companyName = outcome.companyName, now = now)
                transaction { AccountingExportStore.getOrCreateConnection(provider = provider, now = now) }.toDto()
            }
            is ConnectionTestOutcome.Failure -> {
                if (outcome.errorCode == "401" || outcome.errorCode == "403") AccountingExportStore.markTestDue(provider)
                throw ConflictException("Verbindungstest fehlgeschlagen: ${outcome.message}")
            }
        }
    }

    override suspend fun removeToken(provider: AccountingExportProvider): AccountingExportConnectionDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_EXPORT_ROLES)
        val now = DbClock.nowLocalDateTime()
        val row = AccountingExportStore.removeToken(provider = provider, now = now)
        transaction {
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.ACCOUNTING_EXPORT_CONNECTION,
                entityId = row.id,
                action = AuditAction.UPDATE,
            )
        }
        return row.toDto()
    }

    override suspend fun getZeroVatDisclaimer(): AccountingExportZeroVatDisclaimerDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_EXPORT_ROLES)
        return AccountingExportZeroVatDisclaimerDto(
            version = ZeroVatExportDisclaimer.VERSION,
            text = ZeroVatExportDisclaimer.TEXT,
            sha256 = ZeroVatExportDisclaimer.SHA256,
        )
    }

    override suspend fun acknowledgeZeroVat(
        provider: AccountingExportProvider,
        disclaimerSha256: String,
    ): AccountingExportConnectionDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_EXPORT_ROLES)
        if (!ZeroVatExportDisclaimer.matches(version = ZeroVatExportDisclaimer.VERSION, sha256 = disclaimerSha256)) {
            throw ConflictException("Der quittierte Hinweistext entspricht nicht dem aktuellen -- bitte Seite neu laden.")
        }
        val now = DbClock.nowLocalDateTime()
        val row =
            AccountingExportStore.acknowledgeZeroVat(
                provider = provider,
                memberId = current.memberId,
                disclaimerVersion = ZeroVatExportDisclaimer.VERSION,
                disclaimerSha256 = ZeroVatExportDisclaimer.SHA256,
                now = now,
            )
        transaction {
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.ACCOUNTING_EXPORT_CONNECTION,
                entityId = row.id,
                action = AuditAction.UPDATE,
            )
        }
        return row.toDto()
    }

    override suspend fun listCategories(provider: AccountingExportProvider): List<ExternalCategoryDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_EXPORT_ROLES)
        if (!testRateLimiter.checkAndRecord("member:${current.memberId}")) {
            throw ConflictException("Zu viele Anfragen -- bitte später erneut versuchen.")
        }
        val box = secretBox ?: throw ConflictException("Kein Verschlüsselungsschlüssel konfiguriert.")
        val token =
            AccountingExportStore.readToken(provider = provider, secretBox = box)
                ?: throw ConflictException("Kein Token hinterlegt.")
        val adapter = adaptersByProvider[provider] ?: throw ConflictException("Anbieter nicht verfügbar.")
        return when (val outcome = adapter.listCategories(token)) {
            is CategoryListOutcome.Success ->
                outcome.categories.map { c ->
                    ExternalCategoryDto(id = c.id, name = c.name, groupName = c.groupName, direction = c.direction)
                }
            is CategoryListOutcome.Failure -> {
                if (outcome.errorCode == "401" || outcome.errorCode == "403") AccountingExportStore.markTestDue(provider)
                throw ConflictException("Kategorien konnten nicht geladen werden: ${outcome.message}")
            }
        }
    }

    override suspend fun mapAccount(
        provider: AccountingExportProvider,
        ledgerAccountId: String,
        externalCategoryId: String,
        externalCategoryName: String?,
    ) {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_EXPORT_ROLES)
        val accountId = ledgerAccountId.toExportUuid("LedgerAccount")
        // Security review Runde 3, Befund 3 (Fund 2026-09-07): externalCategoryId/-Name used to be
        // passed straight through to `accounting_export_category_map.external_category_id VARCHAR(64)`
        // / `external_category_name VARCHAR(200)` (V25__accounting_export.sql:75-76) with no length
        // check -- a manipulated/stale client sending an oversized value threw an ExposedSQLException
        // (generic HTTP 500) instead of a readable 400, same class of gap `requireValidTokenFormat`
        // already closes for the token itself. Mirrors that function's shape.
        requireValidExternalCategory(externalCategoryId = externalCategoryId, externalCategoryName = externalCategoryName)
        // Same finding, second half: an unknown ledgerAccountId only surfaced via
        // fk_accounting_export_category_map_ledger_account_id (V25:111-113) rejecting the INSERT --
        // also a generic 500. Checked in its own transaction, separate from AccountingExportStore
        // .mapAccount's own -- this class does not nest `transaction {}` blocks anywhere else either
        // (see previewExport's two independent blocks below), and the two-transaction TOCTOU window
        // this leaves (account deleted between the check and the insert) is no worse than the FK
        // itself already tolerates: the INSERT would still fail, just back to a 500 for that rare
        // race instead of silently succeeding.
        val accountExists =
            transaction {
                LedgerAccountTable
                    .selectAll()
                    .where { LedgerAccountTable.id eq accountId }
                    .limit(1)
                    .singleOrNull() != null
            }
        if (!accountExists) throw NotFoundException("LedgerAccount $accountId not found")
        val result =
            AccountingExportStore.mapAccount(
                provider = provider,
                ledgerAccountId = accountId,
                externalCategoryId = externalCategoryId,
                externalCategoryName = externalCategoryName,
                mappedBy = current.memberId,
                now = DbClock.nowLocalDateTime(),
            )
        // Security review Runde 3, Befund 4 (Fund 2026-09-07): previously no audit entry at all --
        // see AuditEntityType.ACCOUNTING_EXPORT_MAPPING KDoc.
        transaction {
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.ACCOUNTING_EXPORT_MAPPING,
                entityId = result.id,
                action = if (result.created) AuditAction.CREATE else AuditAction.UPDATE,
            )
        }
    }

    override suspend fun previewExport(
        provider: AccountingExportProvider,
        from: LocalDate,
        to: LocalDate,
    ): AccountingExportPreviewDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_EXPORT_ROLES)
        if (from > to) throw BadRequestException("from ($from) must not be after to ($to)")
        // Security review Runde 3, Befund 5 (Fund 2026-09-07): the only expensive method on this
        // service that previously had no rate limit of its own -- see [previewRateLimiter] KDoc.
        if (!previewRateLimiter.checkAndRecord("member:${current.memberId}")) {
            throw ConflictException("Zu viele Anfragen -- bitte später erneut versuchen.")
        }
        return buildPreview(provider = provider, from = from, to = to, exportedBy = current.displayName()).second
    }

    override suspend fun startExport(
        provider: AccountingExportProvider,
        from: LocalDate,
        to: LocalDate,
    ): AccountingExportRunDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_EXPORT_ROLES)
        if (from > to) throw BadRequestException("from ($from) must not be after to ($to)")
        if (!startExportRateLimiter.checkAndRecord("member:${current.memberId}")) {
            throw ConflictException("Zu viele Anfragen -- bitte später erneut versuchen.")
        }
        val (plan, preview) = buildPreview(provider = provider, from = from, to = to, exportedBy = current.displayName())
        if (!preview.exportable) {
            throw ConflictException(
                "Zeitraum ist nicht exportierbar: ${preview.blockers.joinToString("; ") { "${it.kind}: ${it.detail}" }}",
            )
        }
        val now = DbClock.nowLocalDateTime()
        val runId =
            try {
                AccountingExportStore.createRun(
                    provider = provider,
                    from = from,
                    to = to,
                    startedBy = current.memberId,
                    now = now,
                    vouchers = plan.vouchers,
                )
            } catch (e: org.jetbrains.exposed.v1.exceptions.ExposedSQLException) {
                // Race: a concurrent startExport won uq_accounting_export_run_active first -- see
                // AccountingExportStore.createRun KDoc.
                throw ConflictException("Für diesen Anbieter läuft bereits ein Export.")
            }
        // Fund 2026-09-07: createRun unconditionally creates the run RUNNING with active_key set --
        // if every planned voucher was ALREADY exported (all items land SKIPPED_ALREADY_EXPORTED, no
        // PENDING item ever created), AccountingExportPoller's send phase never touches this run
        // (duePendingItemIds finds nothing), so it would otherwise stay RUNNING/active_key set
        // forever, permanently blocking the provider (hasActiveRun() == true, every subsequent
        // previewExport/startExport rejected as RUN_ALREADY_IN_PROGRESS/Conflict, recoverable only by
        // a manual abortRun/retryFailed). Recomputing right here mirrors exactly what the poller does
        // after every item it touches -- it is a no-op (still RUNNING) whenever real PENDING items
        // remain.
        AccountingExportStore.recomputeRunCounts(runId = runId, now = now)
        // Security review Runde 3, Befund 4 (Fund 2026-09-07): previously no audit entry at all --
        // see AuditEntityType.ACCOUNTING_EXPORT_RUN KDoc. accounting_export_run.started_by already
        // names the actor on the row itself; this is the append-only, tamper-evident trail alongside
        // it, same reasoning setToken/removeToken already apply to ACCOUNTING_EXPORT_CONNECTION.
        transaction {
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.ACCOUNTING_EXPORT_RUN,
                entityId = runId,
                action = AuditAction.CREATE,
            )
        }
        return requireNotNull(AccountingExportStore.getRun(runId)).toDto()
    }

    override suspend fun getRun(runId: String): AccountingExportRunDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_EXPORT_ROLES)
        val id = runId.toExportUuid("AccountingExportRun")
        return (AccountingExportStore.getRun(id) ?: throw NotFoundException("AccountingExportRun $runId not found")).toDto()
    }

    override suspend fun getLatestRun(provider: AccountingExportProvider): List<AccountingExportRunDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_EXPORT_ROLES)
        return listOfNotNull(AccountingExportStore.getLatestRun(provider)?.toDto())
    }

    override suspend fun listRunItems(
        runId: String,
        status: AccountingExportItemStatus?,
        offset: Int,
        limit: Int,
    ): List<AccountingExportItemDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_EXPORT_ROLES)
        val id = runId.toExportUuid("AccountingExportRun")
        return AccountingExportStore.listRunItems(runId = id, status = status, offset = offset, limit = limit).map { it.toItemDto() }
    }

    override suspend fun retryFailed(runId: String): AccountingExportRunDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_EXPORT_ROLES)
        val id = runId.toExportUuid("AccountingExportRun")
        val now = DbClock.nowLocalDateTime()
        val row =
            try {
                AccountingExportStore.retryFailed(runId = id, now = now)
            } catch (e: org.jetbrains.exposed.v1.exceptions.ExposedSQLException) {
                // Fund 2026-09-07 review (Runde 2, Befund 3): same caller-translates-to-Conflict
                // contract [AccountingExportStore.retryFailed]'s own KDoc documents and [startExport]
                // above already implements -- retryFailed's reopen branch races the SAME
                // uq_accounting_export_run_active unique index (e.g. a second, stale browser tab
                // retrying an ABORTED run right after a fresh startExport for the same provider won
                // that index first). Without this catch the ExposedSQLException reached the client as
                // a generic 500 via StatusPages instead of a readable 409.
                throw ConflictException("Für diesen Anbieter läuft bereits ein Export.")
            } ?: throw NotFoundException("AccountingExportRun $runId not found")
        // Security review Runde 3, Befund 4 (Fund 2026-09-07): previously no audit entry at all --
        // this is the MAJOR-finding-relevant one, see AuditEntityType.ACCOUNTING_EXPORT_RUN KDoc.
        // retryFailed is exactly the operation that resends a run's FAILED items -- the only trail
        // of WHO triggered a resend after any duplicate-voucher incident, since
        // accounting_export_run/accounting_export_item carry no actor column for this action.
        transaction {
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.ACCOUNTING_EXPORT_RUN,
                entityId = id,
                action = AuditAction.UPDATE,
            )
        }
        return row.toDto()
    }

    override suspend fun abortRun(runId: String): AccountingExportRunDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_EXPORT_ROLES)
        val id = runId.toExportUuid("AccountingExportRun")
        val now = DbClock.nowLocalDateTime()
        val row =
            AccountingExportStore.abortRun(runId = id, now = now) ?: throw NotFoundException("AccountingExportRun $runId not found")
        // Security review Runde 3, Befund 4 (Fund 2026-09-07): previously no audit entry at all --
        // see AuditEntityType.ACCOUNTING_EXPORT_RUN KDoc.
        transaction {
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.ACCOUNTING_EXPORT_RUN,
                entityId = id,
                action = AuditAction.UPDATE,
            )
        }
        return row.toDto()
    }

    /** Security review Fund 2026-09-07 (Runde 4, Befund 2): the ONLY way to lift
     * [AccountingExportBlockerKind.UNRESOLVED_UNKNOWN_ITEMS] for a journal entry -- see
     * [IAccountingExportService.resolveUnknownItem] KDoc for the full contract. */
    override suspend fun resolveUnknownItem(
        itemId: String,
        resolution: AccountingExportUnknownItemResolution,
        externalVoucherId: String?,
    ): AccountingExportItemDto {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_EXPORT_ROLES)
        val id = itemId.toExportUuid("AccountingExportItem")
        val now = DbClock.nowLocalDateTime()
        val outcome =
            try {
                AccountingExportStore.resolveUnknown(id = id, resolution = resolution, externalVoucherId = externalVoucherId, now = now)
            } catch (e: org.jetbrains.exposed.v1.exceptions.ExposedSQLException) {
                // Security review Fund 2026-09-07 (Runde 5, MINOR): CONFIRMED_SENT writes
                // `exported_key = exportedKeyOf(provider, journalEntryId)`, which carries the SAME
                // UNIQUE index (`uq_accounting_export_item_exported`) `createRun`'s own
                // ConflictException-translating catch above guards. That collision is reachable here
                // too -- `AccountingExportStore.unknownJournalEntryIds` KDoc documents the case
                // itself: "an entry can appear in BOTH sets if an earlier run's item is UNKNOWN while
                // a LATER run already succeeded for the same entry" -- for pre-fix data, or a race of
                // two concurrent `resolveUnknownItem(CONFIRMED_SENT)` calls on two UNKNOWN items of
                // the SAME journal entry (the per-item status re-check in `resolveUnknown`'s UPDATE
                // WHERE guards each item individually, not the cross-item `exported_key` uniqueness).
                // Without this catch the ExposedSQLException reached the client as a generic 500
                // instead of a readable 409, same gap `startExport`/`retryFailed` already close for
                // their own unique-index collisions.
                throw ConflictException(
                    "Für diese Journalbuchung wurde bereits ein anderer Beleg erfolgreich übertragen -- bitte Ansicht neu laden.",
                )
            }
        val row =
            when (outcome) {
                is AccountingExportStore.ResolveUnknownOutcome.NotFound ->
                    throw NotFoundException("AccountingExportItem $itemId not found")
                is AccountingExportStore.ResolveUnknownOutcome.NotCurrentlyUnknown ->
                    throw ConflictException(
                        "Dieser Beleg hat inzwischen einen anderen Status -- bitte Ansicht neu laden.",
                    )
                is AccountingExportStore.ResolveUnknownOutcome.Resolved -> outcome.row
            }
        // Security review Fund 2026-09-07 (Runde 4, Befund 2): see AuditEntityType.ACCOUNTING_EXPORT_RUN
        // KDoc "Fund 2026-09-07 (Runde 4, Befund 2)" -- entityId is the OWNING run (same as
        // retryFailed/abortRun), not the item itself, matching that entity type's documented
        // "entityId = the accounting_export_run row's id" contract.
        transaction {
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.ACCOUNTING_EXPORT_RUN,
                entityId = row[network.lapis.cloud.server.db.generated.AccountingExportItemTable.runId],
                action = AuditAction.UPDATE,
            )
        }
        return row.toItemDto()
    }

    /** Security review Fund 2026-09-07 (Runde 5, MAJOR): see [IAccountingExportService
     * .listUnknownItems] KDoc -- the fix for the "an `UNKNOWN` item from an earlier run becomes
     * unreachable once a later run starts" gap. Read-only, so no audit entry (matches
     * [listRunItems]/[getRun]/[getLatestRun], none of which record one either). */
    override suspend fun listUnknownItems(
        provider: AccountingExportProvider,
        offset: Int,
        limit: Int,
    ): List<AccountingExportItemDto> {
        val current = resolveCurrentMember(call)
        current.requireRole(*ACCOUNTING_EXPORT_ROLES)
        return AccountingExportStore.unknownItems(provider = provider, offset = offset, limit = limit).map { it.toItemDto() }
    }

    // ── Shared preview/plan assembly ────────────────────────────────────────────────────────

    private fun buildPreview(
        provider: AccountingExportProvider,
        from: LocalDate,
        to: LocalDate,
        exportedBy: String,
    ): Pair<AccountingExportPlan, AccountingExportPreviewDto> {
        val now = DbClock.nowLocalDateTime()
        val connection = transaction { AccountingExportStore.getOrCreateConnection(provider = provider, now = now) }
        val connectionBlockers = mutableListOf<AccountingExportBlockerDto>()
        if (!connection.hasToken || connection.lastTestedAt == null) {
            connectionBlockers +=
                AccountingExportBlockerDto(
                    kind = AccountingExportBlockerKind.NOT_CONNECTED,
                    detail = "Es ist kein erfolgreich getestetes Anbieter-Token hinterlegt.",
                )
        }
        // Fund 2026-09-07: also re-blocks when zeroVatAcknowledgedAt IS set but
        // zeroVatDisclaimerVersion is stale (a prior acknowledgment of an EARLIER wording) -- same
        // "acknowledgment must match the CURRENT version, not merely exist" gate every other
        // versioned disclaimer in this codebase enforces (sepaDisclaimerIsCurrentlyAcknowledged,
        // paymentGatewayDisclaimerIsCurrentlyAcknowledged). Without this, revising
        // ZeroVatExportDisclaimer.VERSION/TEXT after a security/tax review would silently NOT
        // require re-quittance from any already-connected organization.
        if (connection.zeroVatAcknowledgedAt == null || connection.zeroVatDisclaimerVersion != ZeroVatExportDisclaimer.VERSION) {
            connectionBlockers +=
                AccountingExportBlockerDto(
                    kind = AccountingExportBlockerKind.ZERO_VAT_NOT_ACKNOWLEDGED,
                    detail = "Der Hinweis zur Umsatzsteuer (0 %-Übertragung) wurde noch nicht bestätigt.",
                )
        }
        if (AccountingExportStore.hasActiveRun(provider)) {
            connectionBlockers +=
                AccountingExportBlockerDto(
                    kind = AccountingExportBlockerKind.RUN_ALREADY_IN_PROGRESS,
                    detail = "Für diesen Anbieter läuft bereits ein Export.",
                )
        }

        val request = transaction { buildJournalExportRequest(from = from, to = to, exportedBy = exportedBy) }
        val categoryMap = AccountingExportStore.categoryMapByLedgerAccount(provider)
        val alreadyExported =
            AccountingExportStore.alreadyExportedJournalEntryIds(
                provider = provider,
                journalEntryIds = request.entries.map { it.id },
            )
        val plan =
            AccountingExportPlanner.plan(
                request = request,
                alreadyExportedJournalEntryIds = alreadyExported,
                categoryByLedgerAccount = categoryMap,
            )

        // Security review Fund 2026-09-07 (Runde 4, Befund 2): `retryFailed` already refuses to
        // silently reopen an `UNKNOWN` item (`AccountingExportStore.retryFailed` KDoc), but nothing
        // stopped a TREASURER from re-running the SAME period through "Prüfen" -> "Übertragen" and
        // planning a brand-new item for the exact journal entry `retryFailed` was just blocked from
        // touching -- see `AccountingExportBlockerKind.UNRESOLVED_UNKNOWN_ITEMS` KDoc. The `-
        // alreadyExported` subtraction excludes an entry whose UNKNOWN item is from an EARLIER run
        // that a LATER run has since fully resolved (SUCCEEDED) -- that entry is correctly excluded
        // from planning by `alreadyExportedJournalEntryIds` already, a stale UNKNOWN from before it
        // succeeded must not block it again.
        val unresolvedUnknown =
            AccountingExportStore.unknownJournalEntryIds(
                provider = provider,
                journalEntryIds = request.entries.map { it.id },
            ) - alreadyExported
        val connectionAndUnknownBlockers =
            if (unresolvedUnknown.isEmpty()) {
                connectionBlockers
            } else {
                connectionBlockers +
                    AccountingExportBlockerDto(
                        kind = AccountingExportBlockerKind.UNRESOLVED_UNKNOWN_ITEMS,
                        detail =
                            "${unresolvedUnknown.size} Journalbuchung(en) haben einen ungeklärten lexoffice-Sendestatus " +
                                "aus einem vorherigen Lauf. Bitte im Lauf-Detail unter \"Status unklar\" manuell prüfen " +
                                "und auflösen, bevor dieser Zeitraum erneut übertragen werden kann.",
                    )
            }

        val allBlockers = connectionAndUnknownBlockers + plan.blockers
        val sample =
            plan.vouchers.take(AccountingExportPlanner.MAX_LISTED_LINES_IN_PREVIEW).map { v ->
                VoucherPreviewLineDto(
                    journalEntryId = v.journalEntryId.toString(),
                    entryDate = v.entryDate,
                    voucherNumber = v.voucherNumber,
                    voucherType =
                        if (v.direction ==
                            network.lapis.cloud.shared.domain.AccountingExportDirection.INCOME
                        ) {
                            "salesinvoice"
                        } else {
                            "purchaseinvoice"
                        },
                    direction = v.direction,
                    categoryName = v.externalCategoryName,
                    grossAmount = v.grossAmount,
                    description = v.description,
                    alreadyExported = v.alreadyExported,
                )
            }
        val unmapped =
            transaction {
                plan.unmappedAccounts.map { u ->
                    val accountName =
                        LedgerAccountTable
                            .selectAll()
                            .where { LedgerAccountTable.id eq u.ledgerAccountId }
                            .singleOrNull()
                            ?.get(LedgerAccountTable.name)
                            .orEmpty()
                    UnmappedAccountDto(
                        ledgerAccountId = u.ledgerAccountId.toString(),
                        accountNumber = u.accountNumber,
                        accountName = accountName,
                        accountType = u.accountType,
                        entryCount = u.entryCount,
                    )
                }
            }

        val dto =
            AccountingExportPreviewDto(
                provider = provider,
                from = from,
                to = to,
                entryCount = plan.entryCount,
                alreadyExportedCount = plan.alreadyExportedCount,
                toSendCount = plan.toSendCount,
                totalGross = plan.totalGross,
                unmappedAccounts = unmapped,
                blockers = allBlockers,
                sampleLines = sample,
                totalLineCount = plan.vouchers.size,
                exportable = allBlockers.isEmpty(),
            )
        return plan to dto
    }

    /** [network.lapis.cloud.server.rpc.memberDisplayName] does a real DB lookup -- unlike
     * `AccountingService.displayName` (called from inside an already-open `transaction {}` at every
     * call site there), [previewExport]/[startExport] call this BEFORE opening [buildPreview]'s own
     * transaction, so this wraps its own. */
    private fun CurrentMember.displayName(): String = transaction { memberDisplayName(memberId) }.orEmpty()
}

/** Own, file-private constant -- NEVER shared with [ACCOUNTING_READ_ROLES]/[TREASURY_ROLES] from
 * [AccountingService]. Deliberately narrower than [ACCOUNTING_READ_ROLES] (no BOARD) -- see
 * [network.lapis.cloud.shared.rpc.IAccountingExportService] KDoc for the full rationale. */
private val ACCOUNTING_EXPORT_ROLES = arrayOf(AccountRole.TREASURER, AccountRole.ADMIN)

private fun String.toExportUuid(entityName: String): Uuid =
    runCatching { Uuid.parse(this) }.getOrElse { throw BadRequestException("Invalid $entityName id: $this") }

private fun AccountingExportStore.ConnectionRow.toDto(): AccountingExportConnectionDto =
    AccountingExportConnectionDto(
        provider = provider,
        connected = hasToken && lastTestedAt != null,
        tokenLast4 = tokenLast4,
        connectedCompanyName = connectedCompanyName,
        lastTestedAt = lastTestedAt,
        // Fund 2026-09-07: version-checked, same gate buildPreview's own blocker now applies -- a
        // stale-version acknowledgment must read as "not (currently) acknowledged" here too, not
        // just at the export-blocker level.
        zeroVatAcknowledged = zeroVatAcknowledgedAt != null && zeroVatDisclaimerVersion == ZeroVatExportDisclaimer.VERSION,
        zeroVatAcknowledgedAt = zeroVatAcknowledgedAt,
    )

/** Security review Fund 2026-09-07 (Runde 4, Befund 2): shared by [AccountingExportService.listRunItems]
 * and [AccountingExportService.resolveUnknownItem] -- previously duplicated inline at the
 * `listRunItems` call site (and did not carry [AccountingExportItemDto.id] at all). */
private fun org.jetbrains.exposed.v1.core.ResultRow.toItemDto(): AccountingExportItemDto =
    AccountingExportItemDto(
        id = this[network.lapis.cloud.server.db.generated.AccountingExportItemTable.id].toString(),
        runId = this[network.lapis.cloud.server.db.generated.AccountingExportItemTable.runId].toString(),
        journalEntryId = this[network.lapis.cloud.server.db.generated.AccountingExportItemTable.journalEntryId].toString(),
        entryDate = this[network.lapis.cloud.server.db.generated.AccountingExportItemTable.entryDate],
        voucherNumber = this[network.lapis.cloud.server.db.generated.AccountingExportItemTable.voucherNumber],
        grossAmount = this[network.lapis.cloud.server.db.generated.AccountingExportItemTable.grossAmount],
        status = this[network.lapis.cloud.server.db.generated.AccountingExportItemTable.status],
        externalVoucherId = this[network.lapis.cloud.server.db.generated.AccountingExportItemTable.externalVoucherId],
        errorCode = this[network.lapis.cloud.server.db.generated.AccountingExportItemTable.errorCode],
        errorMessage = this[network.lapis.cloud.server.db.generated.AccountingExportItemTable.errorMessage],
    )

private fun AccountingExportStore.RunRow.toDto(): AccountingExportRunDto =
    AccountingExportRunDto(
        id = id.toString(),
        provider = provider,
        from = from,
        to = to,
        status = status,
        startedAt = startedAt,
        finishedAt = finishedAt,
        total = total,
        succeeded = succeeded,
        failed = failed,
        skipped = skipped,
        unknown = unknown,
    )

private fun requireValidTokenFormat(token: String) {
    if (token.length !in TOKEN_MIN_LENGTH..TOKEN_MAX_LENGTH) {
        throw BadRequestException("Token muss zwischen $TOKEN_MIN_LENGTH und $TOKEN_MAX_LENGTH Zeichen lang sein.")
    }
    // No CR/LF, no non-ASCII -- the token later lands verbatim in an `Authorization` header; this
    // is a header-injection guard, not a lexoffice-specific format check (lexoffice documents no
    // token prefix/shape of its own).
    if (token.any { it.code < PRINTABLE_ASCII_MIN || it.code > PRINTABLE_ASCII_MAX }) {
        throw BadRequestException("Token enthält unzulässige Zeichen (nur druckbares ASCII, kein Zeilenumbruch).")
    }
}

private const val TOKEN_MIN_LENGTH = 20
private const val TOKEN_MAX_LENGTH = 500
private const val PRINTABLE_ASCII_MIN = 0x21
private const val PRINTABLE_ASCII_MAX = 0x7E

/** Mirrors the column widths of `accounting_export_category_map.external_category_id VARCHAR(64)`
 * / `.external_category_name VARCHAR(200)` (V25__accounting_export.sql:75-76) -- an oversized value
 * must fail here with a readable [BadRequestException], not as an ExposedSQLException from the
 * INSERT/UPDATE further down the call stack. */
private fun requireValidExternalCategory(
    externalCategoryId: String,
    externalCategoryName: String?,
) {
    if (externalCategoryId.isBlank() || externalCategoryId.length > EXTERNAL_CATEGORY_ID_MAX_LENGTH) {
        throw BadRequestException(
            "externalCategoryId muss zwischen 1 und $EXTERNAL_CATEGORY_ID_MAX_LENGTH Zeichen lang sein.",
        )
    }
    if (externalCategoryName != null && externalCategoryName.length > EXTERNAL_CATEGORY_NAME_MAX_LENGTH) {
        throw BadRequestException("externalCategoryName darf höchstens $EXTERNAL_CATEGORY_NAME_MAX_LENGTH Zeichen lang sein.")
    }
}

private const val EXTERNAL_CATEGORY_ID_MAX_LENGTH = 64
private const val EXTERNAL_CATEGORY_NAME_MAX_LENGTH = 200
