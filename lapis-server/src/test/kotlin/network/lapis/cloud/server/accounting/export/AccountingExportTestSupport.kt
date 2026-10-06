package network.lapis.cloud.server.accounting.export

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.AccountingExportItemTable
import network.lapis.cloud.shared.domain.AccountingExportItemStatus
import network.lapis.cloud.shared.domain.AccountingExportProvider
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Since V1.9.65 every write after a claim is fenced by the claim's `claimGeneration` value. Tests that put an item into a state "as the
 * poller would have" use these helpers: they claim a still-`PENDING` item first and then pass the item's CURRENT fencing token.
 */
internal fun fencingTokenOf(itemId: Uuid): Int =
    transaction {
        AccountingExportItemTable
            .selectAll()
            .where {
                AccountingExportItemTable.id eq itemId
            }.single()[AccountingExportItemTable.claimGeneration]
    }

private fun ensureClaimed(
    itemId: Uuid,
    now: LocalDateTime,
) {
    val status =
        transaction {
            AccountingExportItemTable
                .selectAll()
                .where {
                    AccountingExportItemTable.id eq itemId
                }.single()[AccountingExportItemTable.status]
        }
    if (status == AccountingExportItemStatus.PENDING) AccountingExportStore.claim(id = itemId, now = now)
}

internal fun testMarkSucceeded(
    id: Uuid,
    provider: AccountingExportProvider,
    journalEntryId: Uuid,
    externalVoucherId: String,
    now: LocalDateTime,
): AccountingExportStore.FencedWrite {
    ensureClaimed(itemId = id, now = now)
    return AccountingExportStore.markSucceeded(
        id = id,
        claimedGeneration = fencingTokenOf(id),
        provider = provider,
        journalEntryId = journalEntryId,
        externalVoucherId = externalVoucherId,
        now = now,
    )
}

internal fun testMarkUnknown(
    id: Uuid,
    errorCode: String,
    now: LocalDateTime,
): AccountingExportStore.FencedWrite {
    ensureClaimed(itemId = id, now = now)
    return AccountingExportStore.markUnknown(id = id, claimedGeneration = fencingTokenOf(id), errorCode = errorCode, now = now)
}
