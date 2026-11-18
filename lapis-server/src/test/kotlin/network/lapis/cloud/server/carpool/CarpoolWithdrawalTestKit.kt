package network.lapis.cloud.server.carpool

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.carpool.feed.CarpoolTeaserIds
import network.lapis.cloud.server.carpool.feed.TEST_IDS
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.CarpoolTeaserTombstoneTable
import network.lapis.cloud.server.partner.PartnerServerStore
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** Runs [block] with the withdrawal key set to the SAME deriver the test feed uses; ALWAYS resets it (no test leaves the key set). */
internal inline fun <T> withWithdrawalKey(
    ids: CarpoolTeaserIds? = TEST_IDS,
    block: () -> T,
): T {
    CarpoolWithdrawalKey.configure(ids)
    try {
        return block()
    } finally {
        CarpoolWithdrawalKey.resetForTest()
    }
}

internal fun allTombstones(): List<ResultRow> = transaction { CarpoolTeaserTombstoneTable.selectAll().toList() }

internal fun clearTombstones() = transaction { CarpoolTeaserTombstoneTable.deleteAll() }

/** A partner fetched the feed at [at] (store call, never a raw UPDATE). */
internal fun recordServed(
    partnerId: Uuid,
    at: LocalDateTime = DbClock.nowLocalDateTime(),
) = transaction { PartnerServerStore.recordCarpoolFeedServed(id = partnerId, itemCount = 1, now = at) }
