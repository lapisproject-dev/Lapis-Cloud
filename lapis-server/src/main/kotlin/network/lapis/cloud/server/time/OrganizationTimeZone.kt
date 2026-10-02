package network.lapis.cloud.server.time

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

private val logger = KotlinLogging.logger {}

/**
 * The organization's zone (V1.9.38): the zone in which class-B wall-clock values are typed in and in
 * which class-A system timestamps are displayed. Read from `organization_settings.timezone` (one row by
 * convention), cached, and invalidated by the update RPC; the 60 s TTL is only a safety net for a write
 * that bypassed it (a second server instance, a manual SQL fix).
 *
 * A missing row or an invalid stored value falls back to [OrganizationTimeZoneRules.DEFAULT_ZONE_ID]
 * with a warning. A cache hit needs no database access, so calling this inside a query loop is cheap.
 */
object OrganizationTimeZone {
    private const val TTL_MILLIS = 60_000L

    private data class Entry(
        val zone: TimeZone,
        val loadedAtMillis: Long,
    )

    private val cache = AtomicReference<Entry?>(null)

    // Bumped by invalidate(). A cache miss only stores what it loaded if no invalidation happened in between,
    // otherwise a read that started before an admin write could re-cache the stale zone after invalidate().
    private val generation = AtomicLong(0)

    fun current(): TimeZone {
        val now = System.currentTimeMillis()
        val hit = cache.get()
        if (hit != null && now - hit.loadedAtMillis < TTL_MILLIS) return hit.zone
        val startGeneration = generation.get()
        val zone = load()
        synchronized(this) {
            if (generation.get() == startGeneration) {
                cache.set(Entry(zone = zone, loadedAtMillis = now))
            }
        }
        return zone
    }

    /** Class-B "now": the wall-clock in the organization zone, as of the real clock. */
    fun wallNow(): LocalDateTime = ServerClock.nowIn(current())

    /** [systemNow] (a class-A UTC stamp, possibly an injected test clock) re-expressed as the organization wall-clock. */
    fun wallNowOf(systemNow: LocalDateTime): LocalDateTime = ServerClock.systemToWall(utc = systemNow, orgZone = current())

    /** The calendar date (class D) of [systemNow] (a class-A UTC stamp, possibly an injected test clock) in the organization zone. */
    fun dateOf(systemNow: LocalDateTime): LocalDate = wallNowOf(systemNow).date

    /** Class-D "today" in the organization zone. */
    fun today(): LocalDate = ServerClock.todayIn(current())

    /** Drops the cached value; the next [current] re-reads the database. */
    fun invalidate() {
        synchronized(this) {
            generation.incrementAndGet()
            cache.set(null)
        }
    }

    private fun load(): TimeZone {
        val raw =
            transaction {
                OrganizationSettingsTable
                    .selectAll()
                    .limit(1)
                    .firstOrNull()
                    ?.get(OrganizationSettingsTable.timezone)
            }
        if (raw == null) return fallback("no organization_settings row")
        if (!OrganizationTimeZoneRules.isValid(raw)) return fallback("invalid stored timezone")
        return TimeZone.of(raw)
    }

    // The stored value is deliberately not logged: it is unvalidated admin input.
    private fun fallback(reason: String): TimeZone {
        logger.warn { "Organization time zone: $reason, falling back to ${OrganizationTimeZoneRules.DEFAULT_ZONE_ID}" }
        return TimeZone.of(OrganizationTimeZoneRules.DEFAULT_ZONE_ID)
    }
}
