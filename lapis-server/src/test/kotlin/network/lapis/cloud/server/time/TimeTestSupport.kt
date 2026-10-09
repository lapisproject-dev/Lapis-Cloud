package network.lapis.cloud.server.time

import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Clock
import kotlin.time.Instant

/** A settable [Clock] (V1.9.81): lets a test advance the server clock while a background worker is waiting on it. */
internal class MutableTestClock(
    initial: String,
) : Clock {
    @Volatile
    var current: Instant = Instant.parse(initial)

    fun advance(duration: kotlin.time.Duration) {
        current += duration
    }

    override fun now(): Instant = current
}

/** Test fixtures of the V1.9.38 time-zone work: pin the server clock, switch the organization zone, always restore. */
internal object TimeTestSupport {
    /** Runs [block] with `ServerClock` set to [clock] and restores the real clock afterwards. */
    inline fun <T> withMutableServerClock(
        clock: MutableTestClock,
        block: () -> T,
    ): T {
        val original = ServerClock.source
        ServerClock.source = clock
        try {
            return block()
        } finally {
            ServerClock.source = original
        }
    }

    fun fixedClock(instant: String): Clock =
        object : Clock {
            override fun now(): Instant = Instant.parse(instant)
        }

    /** Runs [block] with `ServerClock` pinned to [instant] and restores the real clock afterwards. */
    inline fun <T> withServerClock(
        instant: String,
        block: () -> T,
    ): T {
        val original = ServerClock.source
        ServerClock.source = fixedClock(instant)
        try {
            return block()
        } finally {
            ServerClock.source = original
        }
    }

    /** Stores [zoneId] as the organization zone (bypassing the RPC) and drops the cache; returns the previous zone id. */
    fun setOrganizationZone(zoneId: String) {
        transaction { OrganizationSettingsTable.update({ OrganizationSettingsTable.timezone neq zoneId }) { it[timezone] = zoneId } }
        OrganizationTimeZone.invalidate()
    }

    fun resetOrganizationZone() = setOrganizationZone(OrganizationTimeZoneRules.DEFAULT_ZONE_ID)
}
