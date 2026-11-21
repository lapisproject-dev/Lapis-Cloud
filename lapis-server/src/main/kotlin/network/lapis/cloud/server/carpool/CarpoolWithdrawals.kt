package network.lapis.cloud.server.carpool

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.carpool.feed.CarpoolTeaserIds
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CarpoolFederationOptInAuditSnapshot
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/** One trip whose hint must be reported as withdrawn. [departureDate] is the date the partner may have cached (on an edit: the OLD date). */
internal data class CarpoolWithdrawalTarget(
    val postingId: Uuid,
    val departureDate: LocalDate,
)

/** One live withdrawal as the feed reports it: the pseudonymous id and the UTC day it was withdrawn. Nothing else. */
internal data class CarpoolTeaserWithdrawalRow(
    val teaserId: String,
    val withdrawnAt: LocalDate,
    /** Provenance: a withdrawal of THIS server (no relay origin). Own rows are served before pass-on rows and never pushed out by them. */
    val own: Boolean = false,
    /** The partner that reported a pass-on row (null for own rows and legacy rows): the feed cut is shared fairly per reporter. */
    val reporter: Uuid? = null,
)

/** The live withdrawals of one partner plus whether the scan limit cut the list (the feed is then marked incomplete). */
internal data class CarpoolLiveWithdrawals(
    val rows: List<CarpoolTeaserWithdrawalRow>,
    val scanCapped: Boolean,
)

/** V1.9.313 W3 -- the fixed bounds of the withdrawal list. Not configurable on purpose. */
internal object CarpoolTeaserTombstonePolicy {
    const val TTL_DAYS = 14
    const val GRACE_DAYS_AFTER_DEPARTURE = 2
    const val MAX_WITHDRAWN = 1000
    const val WITHDRAWN_SCAN_LIMIT = 5000
    const val MAX_LAZY_SWEEP_PER_BUILD = 50

    /** `min(createdAt + 14 days, (departure + 2 days) 00:00 UTC)`. A partner never needs the tombstone longer than it may hold the hint. */
    fun expiresAt(
        createdAt: LocalDateTime,
        departure: LocalDate,
    ): LocalDateTime {
        val byTtl = (createdAt.toInstant(TimeZone.UTC) + TTL_DAYS.days).toLocalDateTime(TimeZone.UTC)
        val byDeparture =
            departure
                .plus(GRACE_DAYS_AFTER_DEPARTURE, DateTimeUnit.DAY)
                .atStartOfDayIn(TimeZone.UTC)
                .toLocalDateTime(TimeZone.UTC)
        return if (byTtl < byDeparture) byTtl else byDeparture
    }
}

/**
 * The ONE holder of the HMAC deriver for the paths that have no constructor injection (the GDPR contributor, the poller, the RPC services). Set
 * once by `Application.kt` (pinned by `CarpoolFederationTripwireTest`) with the SAME instance the feed route uses. Same pattern as `LupaBbSuppression`.
 */
internal object CarpoolWithdrawalKey {
    @Volatile
    private var ids: CarpoolTeaserIds? = null

    /** Withdrawals that found no key: the consent was cleared but no tombstone could be written. */
    val skippedNoKey = AtomicLong(0)

    /** Events whose candidate list was longer than the scan limit (the withdrawal is then incomplete). */
    val scanCapped = AtomicLong(0)

    fun configure(ids: CarpoolTeaserIds?) {
        this.ids = ids
    }

    fun current(): CarpoolTeaserIds? = ids

    internal fun resetForTest() {
        ids = null
        skippedNoKey.set(0)
        scanCapped.set(0)
    }
}

/**
 * V1.9.313 "Mitfahrzentrale-Foederation" W3 -- the orchestration around [CarpoolFederationStore.withdraw] that is more than one store call: the
 * sweep of opted-in trips whose author left the organisation. Everything runs inside the caller's transaction.
 *
 * Lock order (one rule, everywhere): `carpool_posting` rows first (ascending id, FOR UPDATE), tombstone inserts after. Nothing here locks
 * `organization_settings`.
 */
internal object CarpoolWithdrawals {
    /**
     * Opted-in trips whose author is no longer an active member (left, banned, anonymised): the consent ends with the membership. Re-checks under
     * the lock, clears the consent, writes the tombstones and one audit entry per trip (no actor: the system). At most [limit] trips per call.
     * @return the number of trips withdrawn.
     */
    fun sweepInactiveAuthors(
        now: LocalDateTime,
        ids: CarpoolTeaserIds?,
        limit: Int,
    ): Int {
        val candidates = CarpoolFederationStore.optedInWithInactiveAuthor(limit)
        if (candidates.isEmpty()) return 0
        val confirmed = CarpoolFederationStore.lockStillInactiveOptIns(candidates)
        if (confirmed.isEmpty()) return 0
        CarpoolFederationStore.withdraw(
            targets = confirmed,
            partnerId = null,
            ids = ids,
            now = now,
            clearOptIn = true,
            requireServed = true,
        )
        confirmed.forEach {
            AuditLogRecorder.record(
                actorMemberId = null,
                actorRole = null,
                entityType = AuditEntityType.CARPOOL_FEDERATION_OPT_IN,
                entityId = it.postingId,
                action = AuditAction.UPDATE,
                after = Json.encodeToString(CarpoolFederationOptInAuditSnapshot.serializer(), CarpoolFederationOptInAuditSnapshot(false)),
                occurredAt = now,
            )
        }
        logger.info { "carpool withdrawal: ${confirmed.size} opted-in trip(s) of inactive authors withdrawn" }
        return confirmed.size
    }
}
