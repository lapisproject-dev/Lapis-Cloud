package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.AccountingExportConnectionDto
import network.lapis.cloud.shared.domain.AccountingExportItemDto
import network.lapis.cloud.shared.domain.AccountingExportItemStatus
import network.lapis.cloud.shared.domain.AccountingExportPreviewDto
import network.lapis.cloud.shared.domain.AccountingExportProvider
import network.lapis.cloud.shared.domain.AccountingExportRunDto
import network.lapis.cloud.shared.domain.AccountingExportUnknownItemResolution
import network.lapis.cloud.shared.domain.AccountingExportZeroVatDisclaimerDto
import network.lapis.cloud.shared.domain.ExternalCategoryDto

/**
 * Welle V1.4.5.3 "lexoffice-Live-Anbindung" -- live, provider-authenticated push of POSTED journal
 * entries to an external bookkeeping SaaS. Own interface, deliberately NOT folded into
 * [IAccountingService] (already well over 1700 lines server-side) -- see
 * `network.lapis.cloud.server.rpc.AccountingExportService` for the implementation.
 *
 * **Role: every method requires TREASURER/ADMIN -- deliberately NARROWER than
 * [IAccountingService]'s own [IAccountingService] read tier (TREASURER/BOARD/ADMIN), including the
 * read-only history methods below.** [AccountingExportItemDto] carries a voucher amount and (via
 * [AccountingExportPreviewDto.sampleLines]) a `description` free-text field that can name a donor
 * -- the same reasoning `network.lapis.cloud.server.routes.DatevRoutes.DATEV_FILE_DOWNLOAD_ROLES`
 * already gives for withholding the DATEV FILE (as opposed to its own BOARD-readable preview) from
 * BOARD. Here that reasoning applies to every method, including the preview, because unlike DATEV's
 * preview this one's `sampleLines` already carries the description text itself, not just aggregate
 * counts. This is a deliberate, narrower role split than the DATEV path -- documented, not a
 * copy-paste oversight.
 *
 * **Provider-neutral surface, [AccountingExportProvider]-parameterized.** Every method after the
 * connection lifecycle ones takes `provider` explicitly rather than assuming `LEXOFFICE` -- the
 * seam sevDesk (V1.4.5.4) plugs into without a second interface.
 */
@RpcService
interface IAccountingExportService {
    /** The current connection state for [provider] -- never the token itself. */
    suspend fun getConnection(provider: AccountingExportProvider): AccountingExportConnectionDto

    /** Stores (or replaces) the access token for [provider], sealed at rest -- see
     * `network.lapis.cloud.server.crypto.SecretBox`. Format-validated only (length, printable
     * ASCII, no CR/LF) -- the real proof the token works is [testConnection]. Throws
     * [ConflictException] if `LAPIS_SECRET_ENCRYPTION_KEY` is not configured. */
    suspend fun setToken(
        provider: AccountingExportProvider,
        token: String,
    ): AccountingExportConnectionDto

    /** A real `GET /v1/profile` call against the stored token -- fills
     * [AccountingExportConnectionDto.connectedCompanyName]/[AccountingExportConnectionDto.lastTestedAt]
     * on success. Throws [ConflictException] if no token is stored. */
    suspend fun testConnection(provider: AccountingExportProvider): AccountingExportConnectionDto

    /** Clears the stored token (and the cached company name/test timestamp) but keeps the
     * connection row and its zero-VAT quittance -- see `AccountingExportStore.removeToken` KDoc. */
    suspend fun removeToken(provider: AccountingExportProvider): AccountingExportConnectionDto

    /** The current version/text/hash of the zero-VAT export disclaimer for [provider] -- shown once
     * before the first `startExport` for a newly connected provider. Welle V1.4.5.4
     * "sevDesk-Live-Anbindung": [provider]-parameterized since the disclaimer text now names the
     * target provider by [network.lapis.cloud.shared.domain.displayName] -- see
     * `network.lapis.cloud.server.rpc.ZeroVatExportDisclaimer` KDoc. */
    suspend fun getZeroVatDisclaimer(provider: AccountingExportProvider): AccountingExportZeroVatDisclaimerDto

    /** Records that a TREASURER/ADMIN was shown [disclaimerSha256] and accepted it -- throws
     * [ConflictException] if it does not match the CURRENT disclaimer's hash (the shown text has
     * since changed). */
    suspend fun acknowledgeZeroVat(
        provider: AccountingExportProvider,
        disclaimerSha256: String,
    ): AccountingExportConnectionDto

    /** The provider's booking-category catalogue -- what [mapAccount] picks from. */
    suspend fun listCategories(provider: AccountingExportProvider): List<ExternalCategoryDto>

    /** Creates or replaces the mapping from [ledgerAccountId] to [externalCategoryId] for
     * [provider]. [externalCategoryName] is display-only (see `AccountingExportCategoryMapTable
     * .externalCategoryName` KDoc) -- the client passes the name it already has from
     * [listCategories] rather than this method re-fetching the catalogue itself. */
    suspend fun mapAccount(
        provider: AccountingExportProvider,
        ledgerAccountId: String,
        externalCategoryId: String,
        externalCategoryName: String?,
    )

    /** Dry-run for `[from, to]` -- see [AccountingExportPreviewDto] KDoc. Never sends anything. */
    suspend fun previewExport(
        provider: AccountingExportProvider,
        from: LocalDate,
        to: LocalDate,
    ): AccountingExportPreviewDto

    /** Creates the run row and its PENDING items, returns immediately -- `AccountingExportPoller`
     * does the actual sending asynchronously. Throws [ConflictException] if a non-terminal run for
     * [provider] already exists ([AccountingExportBlockerKind.RUN_ALREADY_IN_PROGRESS]) or if
     * [previewExport] would report the period as not exportable. */
    suspend fun startExport(
        provider: AccountingExportProvider,
        from: LocalDate,
        to: LocalDate,
    ): AccountingExportRunDto

    suspend fun getRun(runId: String): AccountingExportRunDto

    /** At most one element -- empty if [provider] has never had a run. Lets a freshly opened screen
     * show the most recent run without the client remembering a `runId` across a reload. A `List`
     * rather than a nullable [AccountingExportRunDto], deliberately: this codebase's Kilua-RPC KSP
     * codegen does not support a nullable non-collection RPC return type (confirmed by a build
     * failure during this wave's own implementation -- every other RPC method in this codebase
     * already avoids the shape for the same reason, `listX`-style empty-collection being the
     * established "nothing to return" idiom throughout). */
    suspend fun getLatestRun(provider: AccountingExportProvider): List<AccountingExportRunDto>

    /** Paged; [status] filters when given. [limit] is hard-capped server-side (see
     * `AccountingExportService` KDoc "DoS"). */
    suspend fun listRunItems(
        runId: String,
        status: AccountingExportItemStatus?,
        offset: Int,
        limit: Int,
    ): List<AccountingExportItemDto>

    /** Resets every FAILED item of [runId] back to PENDING (attempts/backoff cleared) and, if the
     * run had already finished, reopens it to RUNNING so the poller picks the items back up. */
    suspend fun retryFailed(runId: String): AccountingExportRunDto

    /** Marks every still-PENDING/SENDING item ABORTED and the run ABORTED -- does not roll back
     * items already SUCCEEDED/FAILED. */
    suspend fun abortRun(runId: String): AccountingExportRunDto

    /**
     * Security review Fund 2026-09-07 (Runde 4, Befund 2): resolves an
     * [network.lapis.cloud.shared.domain.AccountingExportItemStatus.UNKNOWN] item after a
     * TREASURER/ADMIN manually checked lexoffice for the corresponding voucher -- see
     * [AccountingExportUnknownItemResolution] KDoc for the two outcomes. The ONLY way to lift
     * [network.lapis.cloud.shared.domain.AccountingExportBlockerKind.UNRESOLVED_UNKNOWN_ITEMS] for
     * the affected journal entry -- see that blocker's own KDoc for why re-planning an `UNKNOWN`
     * item's journal entry without this step risks a duplicate voucher.
     *
     * [externalVoucherId] is only read for [AccountingExportUnknownItemResolution.CONFIRMED_SENT]
     * (ignored otherwise) and is itself optional there -- a TREASURER may confirm the voucher
     * exists in lexoffice without copying its id.
     *
     * Throws [NotFoundException] if [itemId] does not exist. Throws [ConflictException] if the item
     * exists but is not currently `UNKNOWN` (already resolved, e.g. by a concurrent request, or
     * never `UNKNOWN` to begin with).
     */
    suspend fun resolveUnknownItem(
        itemId: String,
        resolution: AccountingExportUnknownItemResolution,
        externalVoucherId: String?,
    ): AccountingExportItemDto

    /**
     * Security review Fund 2026-09-07 (Runde 5, MAJOR): every currently-`UNKNOWN` item for
     * [provider] across EVERY run -- not just the most recently started one. [getLatestRun] only
     * ever returns the single most recent run for [provider], so without this method an `UNKNOWN`
     * item from an EARLIER run becomes permanently unreachable from the UI the moment a later run
     * starts for the same provider (e.g. an unresolved January item once a February export has
     * begun) -- there is no run-history listing, and `retryFailed`/`getRun` both require a `runId`
     * the client has no way to learn any more. [resolveUnknownItem] itself has always worked on any
     * item id; this closes the gap of DISCOVERING that id in the first place. The affected period
     * stays blocked by [network.lapis.cloud.shared.domain.AccountingExportBlockerKind
     * .UNRESOLVED_UNKNOWN_ITEMS] until every item this returns is resolved.
     *
     * [limit] is hard-capped server-side, same posture [listRunItems] already establishes.
     */
    suspend fun listUnknownItems(
        provider: AccountingExportProvider,
        offset: Int,
        limit: Int,
    ): List<AccountingExportItemDto>
}
