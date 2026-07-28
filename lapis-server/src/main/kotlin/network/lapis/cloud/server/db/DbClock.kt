package network.lapis.cloud.server.db

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toLocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.time.Clock

/**
 * Single source of truth for "now, as a DB-storable [LocalDateTime]" across the whole server --
 * see [DatabaseConfig]'s own KDoc ("MODE=PostgreSQL for SQL-dialect parity with prod") for why
 * this truncation exists at all.
 *
 * PostgreSQL's `TIMESTAMP` (and H2's `MODE=PostgreSQL` emulation of it, verified by a live
 * round-trip in `DbClockTest`) has never supported sub-microsecond precision -- it silently
 * TRUNCATES (not rounds) to 6 fractional digits on write. [kotlin.time.Clock.System.now] on Linux
 * can return genuine nanosecond-resolution `Instant`s (confirmed on the GitHub Actions CI runner,
 * e.g. `2026-07-29T00:42:18.185317372`). Without this truncation, a value captured in memory and
 * the SAME value read back after an INSERT are byte-different, which breaks anything that
 * re-derives a hash/signature from a read-back row (see `network.lapis.cloud.server.audit
 * .AuditHashChain`) -- indistinguishable from real tampering. Truncating HERE, at capture time,
 * before the value is used for ANYTHING (hashing, business logic, insertion), is what makes the
 * in-memory and persisted values identical from the moment `now()` returns -- truncating only at
 * the DB boundary would still leave a window where in-memory code sees a value that later
 * silently changes underneath it.
 *
 * Discovered 2026-07-28 via a GitHub Actions CI failure (Linux runner) that had gone unactioned
 * for 7+ days -- see the CHANGELOG entry for this fix for the full account. This object replaces
 * ~25 independently duplicated `nowLocalDateTime()`/`nowUtc()`/`trustAnchorNowLocalDateTime()`
 * function bodies across the codebase, all of which reimplemented the same un-truncated capture
 * and were therefore all equally exposed to this bug.
 */
object DbClock {
    fun nowLocalDateTime(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDateTime =
        Clock.System
            .now()
            .toLocalDateTime(zone)
            .truncatedToDbPrecision()
}

/**
 * Truncates (not rounds) [this] to microsecond precision -- the precision ceiling every
 * `TIMESTAMP` column in this schema can actually store (see [DbClock] KDoc). Exposed as a public
 * extension, not just folded into [DbClock.nowLocalDateTime], so a value captured from an
 * EXTERNAL source (e.g. a third-party price-oracle timestamp, see `PriceOracleService`) can also
 * be normalized to DB-storable precision before use, not only this server's own clock reads.
 */
fun LocalDateTime.truncatedToDbPrecision(): LocalDateTime = toJavaLocalDateTime().truncatedTo(ChronoUnit.MICROS).toKotlinLocalDateTime()
