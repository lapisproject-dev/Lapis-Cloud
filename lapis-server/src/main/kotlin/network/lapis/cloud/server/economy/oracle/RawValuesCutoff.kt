package network.lapis.cloud.server.economy.oracle

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.time.ServerClock
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.toKotlinInstant

private val logger = KotlinLogging.logger {}

/** The "nothing is a legacy snapshot" boundary: every real `captured_at` lies after it. */
internal val NO_LEGACY_CUTOFF: LocalDateTime = LocalDateTime(1970, 1, 1, 0, 0)

/**
 * V1.9.327 -- the instant from which the poller stores the single source values of a snapshot (UTC, same basis as `price_oracle_snapshot.captured_at`).
 * A snapshot captured BEFORE it that has no stored values is a "legacy" snapshot (taken before V171): its median is accepted as a closing rate,
 * flagged `rawValuesStored = false`. A snapshot captured AT/AFTER it without complete values is a defect or a manipulation and is never a rate.
 */
internal fun interface RawValuesCutoff {
    fun cutoffUtc(): LocalDateTime
}

/**
 * Process-wide holder, set at startup by [FlywayRawValuesCutoff.load] and re-read by [refresh] after every restore (a restore changes which
 * snapshots carry values while the server keeps running). The default is the conservative one: no snapshot is a legacy one.
 */
internal object RawValuesCutoffHolder {
    @Volatile
    var current: RawValuesCutoff = RawValuesCutoff { NO_LEGACY_CUTOFF }

    /** Re-reads the cutoff from the database; never throws (a failed read gives [NO_LEGACY_CUTOFF], see [FlywayRawValuesCutoff.readCutoff]). */
    fun refresh() {
        current = FlywayRawValuesCutoff.load()
    }

    /** Test seam: runs [block] with [cutoff] and always restores the previous value. */
    fun <T> withCutoff(
        cutoff: LocalDateTime,
        block: () -> T,
    ): T {
        val before = current
        current = RawValuesCutoff { cutoff }
        try {
            return block()
        } finally {
            current = before
        }
    }
}

/**
 * Reads the cutoff from the database, once, after Flyway has migrated:
 * `min(flyway installed_on of V171, earliest captured_at of a snapshot that has stored values)`. The minimum only ever makes the boundary stricter:
 * after a restore onto a fresh server `installed_on` is later than every snapshot, but a snapshot WITH values proves V171 was active earlier.
 * A missing history entry or a failed read gives [NO_LEGACY_CUTOFF] (no legacy snapshot) and a warning, never a crash.
 */
internal object FlywayRawValuesCutoff {
    fun load(): RawValuesCutoff = readCutoff().let { cutoff -> RawValuesCutoff { cutoff } }

    fun readCutoff(): LocalDateTime =
        try {
            transaction {
                val connection = TransactionManager.current()
                var installed: LocalDateTime? = null
                connection.exec("SELECT installed_on FROM flyway_schema_history WHERE version = '171' AND success = TRUE") { rs ->
                    if (rs.next()) {
                        // Flyway writes CURRENT_TIMESTAMP of the database session, which runs in the zone of the JVM (pinned to UTC): getTimestamp()
                        // reads it back in that same zone, so toInstant() is the true instant.
                        installed =
                            rs
                                .getTimestamp(1)
                                ?.toInstant()
                                ?.toKotlinInstant()
                                ?.toLocalDateTime(ServerClock.zone)
                    }
                }
                val earliestWithValues: LocalDateTime? = PriceOracleSnapshotStore.earliestCapturedWithRawValues()
                val inst = installed
                val earliest = earliestWithValues
                when {
                    inst == null -> {
                        logger.warn {
                            "Raw-values cutoff: no successful Flyway entry for V171 -- no snapshot is treated as a legacy snapshot"
                        }
                        NO_LEGACY_CUTOFF
                    }
                    earliest != null && earliest < inst -> earliest
                    else -> inst
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Raw-values cutoff could not be read -- no snapshot is treated as a legacy snapshot" }
            NO_LEGACY_CUTOFF
        }
}
