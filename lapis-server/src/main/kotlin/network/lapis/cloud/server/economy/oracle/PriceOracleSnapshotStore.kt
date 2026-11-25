package network.lapis.cloud.server.economy.oracle

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.PriceOracleSnapshotSourceTable
import network.lapis.cloud.server.db.generated.PriceOracleSnapshotTable
import network.lapis.cloud.server.db.truncatedToDbPrecision
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AnchorAsset
import network.lapis.cloud.shared.domain.PriceSnapshotDto
import network.lapis.cloud.shared.domain.PriceStatus
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.min
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** Name of the unique index (V41__price_oracle_snapshot.sql) that backstops [PriceOracleSnapshotStore.recordIfAbsent] -- used to tell the EXPECTED replay-race collision apart from any other [ExposedSQLException] (missing table, a value exceeding `sources_used VARCHAR(500)`/`median_price DECIMAL(38,18)`, an exhausted connection pool, ...), which must never fail silently -- see Security-Audit finding "Stiller Schluck-Pfad". */
private const val UNIQUE_INDEX_NAME = "uq_price_oracle_snapshot_anchor_ts"

/** Width of `price_oracle_snapshot_source.source_id`. */
private const val MAX_SOURCE_ID_LENGTH = 64

/** Scale of `price_oracle_snapshot_source.raw_price` (DECIMAL(38,18)). */
private const val RAW_PRICE_SCALE = 18

/** Hard cap for [PriceOracleSnapshotStore.loadDay]: an hourly poller writes at most 24 rows per day (25 on a clock change); anything beyond the cap is never read. */
internal const val MAX_SNAPSHOTS_PER_DAY = 48

/** A stored snapshot together with its raw source values (empty for a snapshot taken before V171). */
internal data class SnapshotWithSources(
    val id: Uuid,
    val status: PriceStatus,
    val medianPrice: BigDecimal,
    val sourceCount: Int,
    val sourcesUsed: String,
    val priceTimestamp: LocalDateTime,
    /** V1.9.327: when the poller wrote the row (UTC, as stored) -- compared with the raw-values cutoff. */
    val capturedAt: LocalDateTime,
    val sources: List<StoredSourceValue>,
)

internal data class StoredSourceValue(
    val sourceId: String,
    val rawPrice: BigDecimal,
    val observedAt: LocalDateTime,
    val included: Boolean,
)

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
                    val snapshotId = Uuid.random()
                    PriceOracleSnapshotTable.insert {
                        it[id] = snapshotId
                        it[PriceOracleSnapshotTable.anchorAsset] = anchorAsset
                        it[PriceOracleSnapshotTable.donationCurrency] = normalizedDonationCurrency
                        it[medianPrice] = quote.medianPrice
                        it[priceStatus] = quote.status
                        it[sourceCount] = quote.contributingSourceIds.size
                        it[sourcesUsed] = quote.contributingSourceIds.joinToString(",")
                        it[PriceOracleSnapshotTable.priceTimestamp] = priceTimestamp
                        it[PriceOracleSnapshotTable.capturedAt] = capturedAt
                    }
                    // V1.9.324: the raw source values, in THE SAME transaction as the snapshot -- a unique-index collision on the snapshot rolls the
                    // source rows back with it, so there are never orphan source rows. A quote without observations (a CACHED replay) writes none.
                    val observations = quote.observations.distinctBy { it.sourceId }
                    if (observations.isNotEmpty()) {
                        PriceOracleSnapshotSourceTable.batchInsert(observations, shouldReturnGeneratedValues = false) { obs ->
                            this[PriceOracleSnapshotSourceTable.id] = Uuid.random()
                            this[PriceOracleSnapshotSourceTable.snapshotId] = snapshotId
                            this[PriceOracleSnapshotSourceTable.sourceId] = obs.sourceId.take(MAX_SOURCE_ID_LENGTH)
                            this[PriceOracleSnapshotSourceTable.rawPrice] = obs.price.setScale(RAW_PRICE_SCALE, RoundingMode.HALF_UP)
                            this[PriceOracleSnapshotSourceTable.observedAt] =
                                obs.observedAt.toLocalDateTime(ServerClock.zone).truncatedToDbPrecision()
                            this[PriceOracleSnapshotSourceTable.included] = obs.included
                        }
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
     * V1.9.324: every snapshot of (anchor, currency) whose `priceTimestamp` lies in `[fromUtc, toUtcExclusive)`, with its raw source values.
     * At most [MAX_SNAPSHOTS_PER_DAY] rows (the newest), sources read in ONE second query (no N+1). Must be called inside an open
     * transaction. ONLY `PriceOracleReferenceRateResolver` calls this (tripwire).
     */
    internal fun loadDay(
        anchorAsset: AnchorAsset,
        donationCurrency: String,
        fromUtc: LocalDateTime,
        toUtcExclusive: LocalDateTime,
    ): List<SnapshotWithSources> {
        val rows =
            PriceOracleSnapshotTable
                .selectAll()
                .where {
                    (PriceOracleSnapshotTable.anchorAsset eq anchorAsset) and
                        (PriceOracleSnapshotTable.donationCurrency eq donationCurrency.uppercase()) and
                        (PriceOracleSnapshotTable.priceTimestamp greaterEq fromUtc) and
                        (PriceOracleSnapshotTable.priceTimestamp less toUtcExclusive)
                }.orderBy(PriceOracleSnapshotTable.priceTimestamp to SortOrder.DESC)
                .limit(MAX_SNAPSHOTS_PER_DAY)
                .toList()
        if (rows.isEmpty()) return emptyList()
        val sourcesBySnapshot =
            PriceOracleSnapshotSourceTable
                .selectAll()
                .where { PriceOracleSnapshotSourceTable.snapshotId inList rows.map { it[PriceOracleSnapshotTable.id] } }
                .groupBy({ it[PriceOracleSnapshotSourceTable.snapshotId] }) {
                    StoredSourceValue(
                        sourceId = it[PriceOracleSnapshotSourceTable.sourceId],
                        rawPrice = it[PriceOracleSnapshotSourceTable.rawPrice],
                        observedAt = it[PriceOracleSnapshotSourceTable.observedAt],
                        included = it[PriceOracleSnapshotSourceTable.included],
                    )
                }
        return rows.map { row ->
            SnapshotWithSources(
                id = row[PriceOracleSnapshotTable.id],
                status = row[PriceOracleSnapshotTable.priceStatus],
                medianPrice = row[PriceOracleSnapshotTable.medianPrice],
                sourceCount = row[PriceOracleSnapshotTable.sourceCount],
                sourcesUsed = row[PriceOracleSnapshotTable.sourcesUsed],
                priceTimestamp = row[PriceOracleSnapshotTable.priceTimestamp],
                capturedAt = row[PriceOracleSnapshotTable.capturedAt],
                sources = sourcesBySnapshot[row[PriceOracleSnapshotTable.id]].orEmpty().sortedBy { it.sourceId },
            )
        }
    }

    /**
     * V1.9.327: the earliest `captured_at` (UTC) of a snapshot that HAS stored raw values, or `null` when there is none. It proves from when on the poller
     * stores them: part of the raw-values cutoff ([FlywayRawValuesCutoff]). Inside an open transaction. Lives here because only this store names the raw table.
     */
    internal fun earliestCapturedWithRawValues(): LocalDateTime? =
        PriceOracleSnapshotTable
            .select(PriceOracleSnapshotTable.capturedAt.min())
            .where {
                exists(
                    PriceOracleSnapshotSourceTable
                        .selectAll()
                        .where { PriceOracleSnapshotSourceTable.snapshotId eq PriceOracleSnapshotTable.id },
                )
            }.singleOrNull()
            ?.get(PriceOracleSnapshotTable.capturedAt.min())

    /** V1.9.325: the stored raw source values of one snapshot (empty for a snapshot from before V171), ordered by source id. Inside an open transaction. */
    internal fun sourcesOf(snapshotId: Uuid): List<StoredSourceValue> =
        PriceOracleSnapshotSourceTable
            .selectAll()
            .where { PriceOracleSnapshotSourceTable.snapshotId eq snapshotId }
            .map {
                StoredSourceValue(
                    sourceId = it[PriceOracleSnapshotSourceTable.sourceId],
                    rawPrice = it[PriceOracleSnapshotSourceTable.rawPrice],
                    observedAt = it[PriceOracleSnapshotSourceTable.observedAt],
                    included = it[PriceOracleSnapshotSourceTable.included],
                )
            }.sortedBy { it.sourceId }

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
