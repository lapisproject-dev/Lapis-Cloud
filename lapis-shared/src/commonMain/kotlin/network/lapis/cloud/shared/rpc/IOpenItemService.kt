package network.lapis.cloud.shared.rpc

import dev.kilua.rpc.annotations.RpcService
import dev.kilua.rpc.types.Decimal
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.CounterpartyDefaultsDto
import network.lapis.cloud.shared.domain.NettingCandidateDto
import network.lapis.cloud.shared.domain.NettingPreviewDto
import network.lapis.cloud.shared.domain.OpenItemDetailDto
import network.lapis.cloud.shared.domain.OpenItemDirection
import network.lapis.cloud.shared.domain.OpenItemDto
import network.lapis.cloud.shared.domain.OpenItemInput
import network.lapis.cloud.shared.domain.OpenItemNettingDto
import network.lapis.cloud.shared.domain.OpenItemSummaryDto

/**
 * Welle V1.4.15 "Kreditoren-/Debitorenbuchhaltung" (`OpenItem`). See
 * `network.lapis.cloud.server.rpc.OpenItemService` KDoc for the full write-path rationale and
 * `docs/architecture/open-items.adoc` for the fachlich rationale (two-booking rule, netting
 * without four-eyes, server-computed aging).
 */
@RpcService
interface IOpenItemService {
    // ── Reading (TREASURER/BOARD/ADMIN) ──────────────────────────────────────────────

    suspend fun getOpenItemSummary(direction: OpenItemDirection? = null): OpenItemSummaryDto

    /**
     * Keyset cursor `(dueDate, id)`; both `null` means "first page". Sort order is always
     * `daysOverdue DESC, dueDate ASC, id ASC` -- same cursor contract `listDunningCases`
     * establishes.
     */
    suspend fun listOpenItems(
        direction: OpenItemDirection? = null,
        onlyOpen: Boolean = true,
        limit: Int = 50,
        afterDueDate: LocalDate? = null,
        afterOpenItemId: String? = null,
    ): List<OpenItemDto>

    /** Empty list instead of a nullable DTO -- kilua-rpc-KSP cannot generate a nullable DTO return. */
    suspend fun getOpenItem(openItemId: String): List<OpenItemDetailDto>

    // ── Writing (TREASURER/ADMIN) ─────────────────────────────────────────────────────

    suspend fun createOpenItem(input: OpenItemInput): OpenItemDetailDto

    suspend fun updateOpenItemMetadata(
        openItemId: String,
        reference: String?,
        note: String?,
    ): OpenItemDetailDto

    suspend fun cancelOpenItem(
        openItemId: String,
        reason: String,
    ): OpenItemDetailDto

    suspend fun settleOpenItem(
        openItemId: String,
        amount: Decimal,
        settledOn: LocalDate,
        bankAccountId: String? = null,
    ): OpenItemDetailDto

    suspend fun reverseSettlement(
        settlementId: String,
        reason: String,
    ): OpenItemDetailDto

    /** Retries the booking after a `creationPostingError` -- mirrors `TravelExpenseService.retryPosting`. */
    suspend fun retryOpenItemPosting(openItemId: String): OpenItemDetailDto

    suspend fun retrySettlementPosting(settlementId: String): OpenItemDetailDto

    // ── Netting (TREASURER/ADMIN, single actor, NO four-eyes) ─────────────────────────

    suspend fun listNettingCandidates(limit: Int = 50): List<NettingCandidateDto>

    /** Pure preview, no write. Mandatory step before [executeNetting]. */
    suspend fun previewNetting(
        payableItemId: String,
        receivableItemId: String,
        amount: Decimal,
    ): List<NettingPreviewDto>

    suspend fun executeNetting(
        payableItemId: String,
        receivableItemId: String,
        amount: Decimal,
    ): OpenItemNettingDto

    suspend fun reverseNetting(
        nettingId: String,
        reason: String,
    ): OpenItemNettingDto

    /**
     * Pre-fill memory (Raskin-Ruling): the most recently used contra-account/sphere for this
     * counterparty. Empty list means no prior item.
     */
    suspend fun getCounterpartyDefaults(
        counterpartyName: String,
        direction: OpenItemDirection,
    ): List<CounterpartyDefaultsDto>
}
