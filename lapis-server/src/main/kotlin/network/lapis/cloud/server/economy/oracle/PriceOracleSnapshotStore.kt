package network.lapis.cloud.server.economy.oracle

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.PriceOracleSnapshotTable
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.PriceSnapshotDto
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Name of the unique index (V41__price_oracle_snapshot.sql) that backstops [PriceOracleSnapshotStore.recordIfAbsent] -- used to tell the EXPECTED replay-race collision apart from any other [ExposedSQLException] (missing table, a value exceeding `sources_used VARCHAR(500)`/`median_price DECIMAL(38,18)`, an exhausted connection pool, ...), which must never fail silently -- see Security-Audit finding "Stiller Schluck-Pfad". */
private const val UNIQUE_INDEX_NAME = "uq_price_oracle_snapshot_anchor_ts"

/**
 * Welle "Price-Oracle-Preishistorie" -- the shared read/write layer for `price_oracle_snapshot`,
 * used by BOTH [PriceOracleSnapshotPoller] (writes) and
 * `network.lapis.cloud.server.rpc.PriceOracleService.getPriceHistory` (reads). A plain `object`,
 * not a service class -- the poller runs with no `ApplicationCall` at all, so it cannot go through
 * an RPC service constructor, exactly the same reason `EventVolunteerStore` exists as its own
 * object rather than living inside `EventVolunteerService`.
 */
object PriceOracleSnapshotStore {
    /**
     * Writes exactly one row IF NONE already exists for (anchor, currency, priceTimestamp) --
     * dedupe against the Orchestrator's refresh-interval replay (GOLD_XAU/FIAT can serve the SAME
     * cached quote, with the SAME [network.lapis.cloud.server.economy.oracle.PriceQuote
     * .priceTimestamp], for up to their whole 12h refresh window). The unique index
     * `uq_price_oracle_snapshot_anchor_ts` is the hard backstop -- a caught
     * [ExposedSQLException] on the insert (two ticks racing, structurally excluded by this
     * poller's own single-coroutine loop, but not by a future caller) is treated as "already
     * present", never propagated.
     *
     * Returns `true` iff a NEW row was written.
     */
    fun recordIfAbsent(
        anchorAsset: AnchorAsset,
        donationCurrency: String,
        quote: PriceQuote,
        priceTimestamp: LocalDateTime,
        capturedAt: LocalDateTime,
    ): Boolean {
        // Normalize to upper case at the write boundary -- the read path
        // (`PriceOracleService.getPriceHistory`) queries with `.uppercase()`. The persisted
        // `price_oracle_config.donation_currency` value is not itself guaranteed upper case (a
        // direct SQL edit, a restored backup, or a migration from a foreign system all bypass the
        // RPC's `SUPPORTED_DONATION_CURRENCIES` membership check, which never normalizes case) --
        // writing it through verbatim would silently desync the two paths: the poller keeps writing
        // rows the read path can then never find, with no error and no log.
        val normalizedDonationCurrency = donationCurrency.uppercase()
        return try {
            transaction {
                val exists =
                    PriceOracleSnapshotTable
                        .selectAll()
                        .where {
                            (PriceOracleSnapshotTable.anchorAsset eq anchorAsset) and
                                (PriceOracleSnapshotTable.donationCurrency eq normalizedDonationCurrency) and
                                (PriceOracleSnapshotTable.priceTimestamp eq priceTimestamp)
                        }.limit(1)
                        .any()
                if (exists) {
                    false
                } else {
                    PriceOracleSnapshotTable.insert {
                        it[id] = Uuid.random()
                        it[PriceOracleSnapshotTable.anchorAsset] = anchorAsset
                        it[PriceOracleSnapshotTable.donationCurrency] = normalizedDonationCurrency
                        it[medianPrice] = quote.medianPrice
                        it[priceStatus] = quote.status
                        it[sourceCount] = quote.contributingSourceIds.size
                        it[sourcesUsed] = quote.contributingSourceIds.joinToString(",")
                        it[PriceOracleSnapshotTable.priceTimestamp] = priceTimestamp
                        it[PriceOracleSnapshotTable.capturedAt] = capturedAt
                    }
                    true
                }
            }
        } catch (e: ExposedSQLException) {
            // Unique-index race backstop -- see KDoc above. Caught OUTSIDE the transaction {} block
            // so a unique-constraint violation cleanly rolls the whole attempt back (Exposed's
            // default propagate-and-rollback behavior) instead of leaving a half-failed JDBC
            // connection inside a still-open transaction. Never propagated: a duplicate snapshot is
            // a no-op, not a poller failure.
            //
            // Security-Audit finding "Stiller Schluck-Pfad": distinguish the EXPECTED collision
            // (the [UNIQUE_INDEX_NAME] index this class's own dedupe check races against) from any
            // OTHER SQL failure -- a missing `price_oracle_snapshot` table (V41 not yet applied), a
            // `sources_used`/`median_price` value that violates its column constraint, an exhausted
            // connection pool, etc. Both cases still return `false` (a poller write failure must
            // never crash the loop -- [PriceOracleSnapshotPoller.tick] only logs on success), but an
            // unexpected failure now leaves a `warn`-level trace with anchor/currency/timestamp
            // (never the exception's raw SQL/parameters, which could carry connection details) --
            // without this, a permanently broken write path ran forever with zero observability,
            // `price_oracle_snapshot` stayed silently empty, and `getPriceHistory` kept returning
            // `[]` to TREASURER/BOARD/ADMIN with no error anywhere.
            val isExpectedUniqueCollision = e.message?.contains(UNIQUE_INDEX_NAME, ignoreCase = true) == true
            if (isExpectedUniqueCollision) {
                logger.debug {
                    "PriceOracleSnapshotStore.recordIfAbsent: expected unique-index collision for anchor=$anchorAsset currency=$normalizedDonationCurrency priceTimestamp=$priceTimestamp (already recorded)"
                }
            } else {
                logger.warn(e) {
                    "PriceOracleSnapshotStore.recordIfAbsent: unexpected ExposedSQLException (NOT the dedupe unique-index collision) for anchor=$anchorAsset currency=$normalizedDonationCurrency priceTimestamp=$priceTimestamp -- snapshot NOT written"
                }
            }
            false
        }
    }

    /**
     * Reads up to [limit] rows for (anchor, currency), optionally bounded below by [since]
     * (`priceTimestamp >= since`), sorted ASCENDING by `priceTimestamp` (chart-ready). When more
     * than [limit] rows match, the NEWEST [limit] rows are kept -- achieved by querying DESC with
     * the cap, then reversing in Kotlin.
     */
    fun loadHistory(
        anchorAsset: AnchorAsset,
        donationCurrency: String,
        since: LocalDateTime?,
        limit: Int,
    ): List<PriceSnapshotDto> =
        transaction {
            PriceOracleSnapshotTable
                .selectAll()
                .where {
                    (PriceOracleSnapshotTable.anchorAsset eq anchorAsset) and
                        (PriceOracleSnapshotTable.donationCurrency eq donationCurrency) and
                        (since?.let { PriceOracleSnapshotTable.priceTimestamp greaterEq it } ?: Op.TRUE)
                }.orderBy(PriceOracleSnapshotTable.priceTimestamp to SortOrder.DESC)
                .limit(limit)
                .map { it.toPriceSnapshotDto() }
                .reversed()
        }
}

private fun ResultRow.toPriceSnapshotDto(): PriceSnapshotDto =
    PriceSnapshotDto(
        id = this[PriceOracleSnapshotTable.id].toString(),
        anchorAsset = this[PriceOracleSnapshotTable.anchorAsset],
        donationCurrency = this[PriceOracleSnapshotTable.donationCurrency],
        medianPrice = this[PriceOracleSnapshotTable.medianPrice],
        priceStatus = this[PriceOracleSnapshotTable.priceStatus],
        sourceCount = this[PriceOracleSnapshotTable.sourceCount],
        sourcesUsed = this[PriceOracleSnapshotTable.sourcesUsed],
        priceTimestamp = this[PriceOracleSnapshotTable.priceTimestamp],
        capturedAt = this[PriceOracleSnapshotTable.capturedAt],
    )
