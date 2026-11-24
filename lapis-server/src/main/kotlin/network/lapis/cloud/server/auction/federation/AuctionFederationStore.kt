package network.lapis.cloud.server.auction.federation

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.AuctionTeaserSnapshotTable
import network.lapis.cloud.server.db.generated.AuctionTeaserTombstoneTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.AuctionCurrencies
import network.lapis.cloud.shared.domain.AuctionFederationLimits
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.AuctionTeaserPriceKind
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.PartnerAuditKind
import network.lapis.cloud.shared.domain.PartnerAuditSnapshot
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/** The state of the auction hint exchange of this server. [hopLimit] is always inside 1..2. */
internal data class AuctionFederationSettings(
    val enabled: Boolean,
    val hopLimit: Int,
)

/** One stored snapshot of a shared auction: the values that were last put on the wire (the throttle compares against them). */
internal data class AuctionTeaserSnapshotRow(
    val auctionId: Uuid,
    val teaserId: String,
    val revision: Long,
    val priceKind: AuctionTeaserPriceKind,
    val priceMinor: Long,
    val priceAsOf: LocalDateTime,
    val contentSha256: String,
)

/** One auction whose hint must be reported as withdrawn. [endsAt] bounds the life of the tombstone. */
internal data class AuctionWithdrawalTarget(
    val auctionId: Uuid,
    val endsAt: LocalDateTime,
)

/** A shareable-looking auction row plus whether its seller is still an active member. */
internal class AuctionTeaserCandidate(
    val row: ResultRow,
    val sellerActive: Boolean,
)

/** One live withdrawal as the feed reports it: the pseudonymous id and the UTC day. Nothing else. */
internal data class AuctionTeaserWithdrawalRow(
    val teaserId: String,
    val withdrawnAt: kotlinx.datetime.LocalDate,
)

/** The live withdrawals of one partner plus whether the scan limit cut the list (the feed is then marked incomplete). */
internal data class AuctionLiveWithdrawals(
    val rows: List<AuctionTeaserWithdrawalRow>,
    val scanCapped: Boolean,
)

/** The fixed bounds of the withdrawal list. Not configurable on purpose. */
internal object AuctionTeaserTombstonePolicy {
    const val TTL_DAYS = 14
    const val GRACE_DAYS_AFTER_END = 2
    const val MAX_WITHDRAWN = 1000
    const val WITHDRAWN_SCAN_LIMIT = 5000
    const val MAX_LAZY_SWEEP_PER_BUILD = 50

    /** `min(createdAt + 14 days, endsAt + 2 days)`. A partner never needs the tombstone longer than it may hold the hint (it drops it at the end anyway). */
    fun expiresAt(
        createdAt: LocalDateTime,
        endsAt: LocalDateTime,
    ): LocalDateTime {
        val byTtl = (createdAt.toInstant(TimeZone.UTC) + TTL_DAYS.days).toLocalDateTime(TimeZone.UTC)
        val byEnd = (endsAt.toInstant(TimeZone.UTC) + GRACE_DAYS_AFTER_END.days).toLocalDateTime(TimeZone.UTC)
        return if (byTtl < byEnd) byTtl else byEnd
    }
}

/**
 * V1.9.322 (Fiat wave c, step c1 "foundation"; c2 adds the snapshot and the tombstone) -- the ONLY reader and writer of `organization_settings.auction_federation_*` and
 * `auction.federation_opt_in/_at` (pinned by `AuctionFederationTripwireTest`). All functions run inside the caller's transaction. Nothing here
 * talks to the network. Since c2 it is also the ONLY reader and writer of `auction_teaser_snapshot` and `auction_teaser_tombstone` (the received
 * tables follow with c3).
 *
 * The consent RULES (Euro only, still OPEN, enough time left, a presentable title, instance switch on) live in
 * `mayConsentToAuctionFederation` (lapis-shared) and are checked by the RPC layer; this store only persists the decision.
 */
internal object AuctionFederationStore {
    fun settings(): AuctionFederationSettings {
        val row =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .single()
        return AuctionFederationSettings(
            enabled = row[OrganizationSettingsTable.auctionFederationEnabled],
            hopLimit =
                row[OrganizationSettingsTable.auctionFederationHopLimit]
                    .toInt()
                    .coerceIn(AuctionFederationLimits.MIN_HOPS, AuctionFederationLimits.MAX_HOPS),
        )
    }

    /** V1.9.322 c3: `true` while the auction module is on (the RECEIVING side needs this and the instance switch, not the Euro module). */
    fun auctionModuleOn(): Boolean =
        OrganizationSettingsTable
            .selectAll()
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .singleOrNull()
            ?.get(OrganizationSettingsTable.auctionEnabled) ?: false

    /** `true` only while the auction module AND the Euro module are on (the exchange needs both). */
    fun modulesOn(): Boolean {
        val row =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .singleOrNull() ?: return false
        return row[OrganizationSettingsTable.auctionEnabled] && row[OrganizationSettingsTable.auctionFiatEnabled]
    }

    /**
     * Locks the settings row, then writes. A hop limit outside 1..2 is REJECTED, never silently clamped: the admin must see that three hops are
     * not offered. Returns the settings as they were BEFORE (the caller audits the change).
     */
    fun setSettings(
        enabled: Boolean,
        hopLimit: Int,
    ): AuctionFederationSettings {
        require(hopLimit in AuctionFederationLimits.MIN_HOPS..AuctionFederationLimits.MAX_HOPS) { "hop limit out of range" }
        OrganizationSettingsTable
            .selectAll()
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .forUpdate()
            .single()
        val before = settings()
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
            it[auctionFederationEnabled] = enabled
            it[auctionFederationHopLimit] = hopLimit.toShort()
        }
        return before
    }

    fun optIn(auctionId: Uuid): Boolean =
        AuctionTable
            .select(AuctionTable.federationOptIn)
            .where { AuctionTable.id eq auctionId }
            .singleOrNull()
            ?.get(AuctionTable.federationOptIn) ?: false

    /** When the seller gave the consent, or `null` (never, or taken back). */
    fun optInAt(auctionId: Uuid): LocalDateTime? =
        AuctionTable
            .select(AuctionTable.federationOptInAt)
            .where { AuctionTable.id eq auctionId }
            .singleOrNull()
            ?.get(AuctionTable.federationOptInAt)

    /** Persists the decision of the seller (or the system, when it withdraws). The moment is kept only while the consent stands. */
    fun setOptIn(
        auctionId: Uuid,
        value: Boolean,
        now: LocalDateTime = DbClock.nowLocalDateTime(),
    ) {
        AuctionTable.update({ AuctionTable.id eq auctionId }) {
            it[federationOptIn] = value
            it[federationOptInAt] = if (value) now else null
        }
    }

    fun countOptIns(): Long =
        AuctionTable
            .selectAll()
            .where { AuctionTable.federationOptIn eq true }
            .count()

    // ---------------------------------------------------------------------------------------------------------------------------------
    // V1.9.322 c2: snapshot (the throttle) and tombstone (the withdrawal). Lock order everywhere: auction rows first (ascending id), then the
    // snapshot/tombstone rows, the audit entry last.
    // ---------------------------------------------------------------------------------------------------------------------------------

    private const val CHUNK = 500

    private fun epochMinute(now: LocalDateTime): Long = Math.floorDiv(now.toInstant(TimeZone.UTC).epochSeconds, 60L)

    /** Strictly above [prev] and never below the wall-clock minute, so a snapshot that was deleted and recreated can never fall behind an old revision. */
    fun newRevision(
        prev: Long,
        now: LocalDateTime,
    ): Long = maxOf(prev + 1, epochMinute(now))

    private fun ResultRow.toSnapshotRow() =
        AuctionTeaserSnapshotRow(
            auctionId = this[AuctionTeaserSnapshotTable.auctionId],
            teaserId = this[AuctionTeaserSnapshotTable.teaserId],
            revision = this[AuctionTeaserSnapshotTable.revision],
            priceKind = AuctionTeaserPriceKind.valueOf(this[AuctionTeaserSnapshotTable.priceKind]),
            priceMinor = this[AuctionTeaserSnapshotTable.priceMinor],
            priceAsOf = this[AuctionTeaserSnapshotTable.priceAsOf],
            contentSha256 = this[AuctionTeaserSnapshotTable.contentSha256],
        )

    fun snapshot(auctionId: Uuid): AuctionTeaserSnapshotRow? =
        AuctionTeaserSnapshotTable
            .selectAll()
            .where { AuctionTeaserSnapshotTable.auctionId eq auctionId }
            .singleOrNull()
            ?.toSnapshotRow()

    fun snapshotByTeaserId(teaserId: String): AuctionTeaserSnapshotRow? =
        AuctionTeaserSnapshotTable
            .selectAll()
            .where { AuctionTeaserSnapshotTable.teaserId eq teaserId }
            .singleOrNull()
            ?.toSnapshotRow()

    /**
     * The seller gave the consent: creates the snapshot, or (a consent after an earlier withdrawal) lets the revision jump, so a partner that
     * dropped the hint never takes the new one for a replay. Returns the stored row.
     */
    fun upsertSnapshotOnConsent(
        auctionId: Uuid,
        teaserId: String,
        priceKind: AuctionTeaserPriceKind,
        priceMinor: Long,
        priceAsOf: LocalDateTime,
        contentSha256: String,
        now: LocalDateTime,
    ): AuctionTeaserSnapshotRow {
        val previous = snapshot(auctionId)
        if (previous == null) {
            val revision = epochMinute(now)
            AuctionTeaserSnapshotTable.insert {
                it[AuctionTeaserSnapshotTable.auctionId] = auctionId
                it[AuctionTeaserSnapshotTable.teaserId] = teaserId
                it[AuctionTeaserSnapshotTable.revision] = revision
                it[AuctionTeaserSnapshotTable.priceKind] = priceKind.name
                it[AuctionTeaserSnapshotTable.priceMinor] = priceMinor
                it[AuctionTeaserSnapshotTable.priceAsOf] = priceAsOf
                it[AuctionTeaserSnapshotTable.contentSha256] = contentSha256
                it[updatedAt] = now
            }
        } else {
            val revision = newRevision(prev = previous.revision, now = now)
            AuctionTeaserSnapshotTable.update({ AuctionTeaserSnapshotTable.auctionId eq auctionId }) {
                it[AuctionTeaserSnapshotTable.teaserId] = teaserId
                it[AuctionTeaserSnapshotTable.revision] = revision
                it[AuctionTeaserSnapshotTable.priceKind] = priceKind.name
                it[AuctionTeaserSnapshotTable.priceMinor] = priceMinor
                it[AuctionTeaserSnapshotTable.priceAsOf] = priceAsOf
                it[AuctionTeaserSnapshotTable.contentSha256] = contentSha256
                it[updatedAt] = now
            }
        }
        return requireNotNull(snapshot(auctionId))
    }

    /**
     * Moves the snapshot forward, but ONLY if nobody else did meanwhile (`WHERE revision = old.revision`): two parallel builds can never overwrite
     * each other. `false` = lost the race (the caller re-reads and uses the stored state).
     */
    fun advanceSnapshot(
        old: AuctionTeaserSnapshotRow,
        teaserId: String,
        priceKind: AuctionTeaserPriceKind,
        priceMinor: Long,
        priceAsOf: LocalDateTime,
        contentSha256: String,
        now: LocalDateTime,
    ): Boolean {
        val revision = newRevision(prev = old.revision, now = now)
        val changed =
            AuctionTeaserSnapshotTable.update(
                { (AuctionTeaserSnapshotTable.auctionId eq old.auctionId) and (AuctionTeaserSnapshotTable.revision eq old.revision) },
            ) {
                // The id follows the key: after a rotation of the secret key the stored id must become the published one, or the hint link of a
                // partner member would not resolve.
                it[AuctionTeaserSnapshotTable.teaserId] = teaserId
                it[AuctionTeaserSnapshotTable.revision] = revision
                it[AuctionTeaserSnapshotTable.priceKind] = priceKind.name
                it[AuctionTeaserSnapshotTable.priceMinor] = priceMinor
                it[AuctionTeaserSnapshotTable.priceAsOf] = priceAsOf
                it[AuctionTeaserSnapshotTable.contentSha256] = contentSha256
                it[updatedAt] = now
            }
        return changed > 0
    }

    private fun shareableFilter(now: LocalDateTime): Op<Boolean> =
        (AuctionTable.federationOptIn eq true) and
            (AuctionTable.status eq AuctionStatus.OPEN) and
            (AuctionTable.currency neq AuctionCurrencies.LTR) and
            (AuctionTable.endsAt greater now)

    private fun activeSellers(sellerIds: Collection<Uuid>): Set<Uuid> =
        sellerIds
            .toSet()
            .chunked(CHUNK)
            .flatMap { chunk ->
                MemberTable
                    .selectAll()
                    .where { MemberTable.id inList chunk }
                    .filter { it[MemberTable.status] in MemberStatusSets.ORGANIZATION_MEMBER && it[MemberTable.anonymizedAt] == null }
                    .map { it[MemberTable.id] }
            }.toSet()

    /**
     * Auctions that look shareable (consent, OPEN, Euro, `endsAt` in the future), ordered by id. At most [scanLimit] + 1 rows come back (the extra row
     * tells the caller that the list was cut). The seller flag is read in the same transaction.
     */
    fun teaserCandidates(
        scanLimit: Int,
        now: LocalDateTime,
    ): List<AuctionTeaserCandidate> {
        val rows =
            AuctionTable
                .selectAll()
                .where { shareableFilter(now) }
                .orderBy(AuctionTable.id to SortOrder.ASC)
                .limit(scanLimit + 1)
                .toList()
        val active = activeSellers(rows.map { it[AuctionTable.sellerMemberId] })
        return rows.map { AuctionTeaserCandidate(row = it, sellerActive = it[AuctionTable.sellerMemberId] in active) }
    }

    /** What a switch-off has to report as withdrawn: the consented, open Euro auctions, whoever the seller is. */
    fun sharedTargets(
        now: LocalDateTime,
        limit: Int = AuctionTeaserTombstonePolicy.WITHDRAWN_SCAN_LIMIT,
    ): List<AuctionWithdrawalTarget> =
        AuctionTable
            .selectAll()
            .where { shareableFilter(now) }
            .orderBy(AuctionTable.id to SortOrder.ASC)
            .limit(limit)
            .map { AuctionWithdrawalTarget(auctionId = it[AuctionTable.id], endsAt = it[AuctionTable.endsAt]) }

    /** "Derzeit geteilt": the consented auctions that are really open (a lazily expired one does not count). */
    fun countSharedActive(now: LocalDateTime): Int =
        AuctionTable
            .selectAll()
            .where { shareableFilter(now) }
            .count()
            .toInt()

    fun countSharedOfSeller(
        sellerId: Uuid,
        now: LocalDateTime,
    ): Int =
        AuctionTable
            .selectAll()
            .where { shareableFilter(now) and (AuctionTable.sellerMemberId eq sellerId) }
            .count()
            .toInt()

    /**
     * Consented auctions that are no longer shareable although the consent still stands: ended (or lazily expired), no longer OPEN or Euro, or the
     * seller left. They are withdrawn (and the consent ends with them). At most [limit], ascending id.
     */
    fun optedInNoLongerShareable(
        limit: Int,
        now: LocalDateTime,
    ): List<AuctionWithdrawalTarget> {
        val rows =
            AuctionTable
                .selectAll()
                .where {
                    (AuctionTable.federationOptIn eq true) and
                        (
                            (AuctionTable.status neq AuctionStatus.OPEN) or
                                (AuctionTable.endsAt lessEq now) or
                                (AuctionTable.currency eq AuctionCurrencies.LTR)
                        )
                }.orderBy(AuctionTable.id to SortOrder.ASC)
                .limit(limit)
                .toList()
        val inactiveSeller =
            AuctionTable
                .selectAll()
                .where {
                    (AuctionTable.federationOptIn eq true) and (AuctionTable.status eq AuctionStatus.OPEN) and
                        (AuctionTable.endsAt greater now)
                }.orderBy(AuctionTable.id to SortOrder.ASC)
                .limit(limit)
                .toList()
                .let { open ->
                    val active = activeSellers(open.map { it[AuctionTable.sellerMemberId] })
                    open.filter { it[AuctionTable.sellerMemberId] !in active }
                }
        return (rows + inactiveSeller)
            .distinctBy { it[AuctionTable.id] }
            .sortedBy { it[AuctionTable.id] }
            .take(limit)
            .map { AuctionWithdrawalTarget(auctionId = it[AuctionTable.id], endsAt = it[AuctionTable.endsAt]) }
    }

    /** Locks the given auctions (ascending id) and keeps those that STILL carry a consent. */
    fun lockOptedIn(candidates: List<AuctionWithdrawalTarget>): List<AuctionWithdrawalTarget> =
        candidates
            .map { it.auctionId }
            .sorted()
            .chunked(CHUNK)
            .flatMap { chunk ->
                AuctionTable
                    .selectAll()
                    .where { AuctionTable.id inList chunk }
                    .orderBy(AuctionTable.id to SortOrder.ASC)
                    .forUpdate()
                    .toList()
            }.filter { it[AuctionTable.federationOptIn] }
            .map { AuctionWithdrawalTarget(auctionId = it[AuctionTable.id], endsAt = it[AuctionTable.endsAt]) }

    /**
     * The primary key of a tombstone is DERIVED from (teaser id, scope): a second withdrawal of the same hint for the same scope hits the same key and
     * is a no-op, so the life of a tombstone is never extended.
     */
    internal fun tombstoneId(
        teaserId: String,
        partnerId: Uuid?,
        relayOriginHost: String? = null,
    ): Uuid {
        // A pass-on tombstone has its own key space (the origin host is part of it), so it never collides with an own withdrawal. With
        // `relayOriginHost == null` the seed is byte-identical to c2 (live rows must still be found).
        val seed =
            if (relayOriginHost == null) {
                "auction-tombstone/v1\u0000$teaserId\u0000${partnerId ?: "*"}"
            } else {
                "auction-tombstone-relay/v1\u0000$teaserId\u0000$relayOriginHost\u0000${partnerId ?: "*"}"
            }
        val bytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8)).copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        return Uuid.fromByteArray(bytes)
    }

    /**
     * Reports the hints of [targets] as withdrawn. [partnerId] `null` = for every partner. [clearOptIn] also ends the consent. [requireServed] (the
     * consent-driven triggers) writes a tombstone only when some partner fetched the feed AT OR AFTER the consent was given (nothing can have been
     * handed out before). Known limit: the feed commits its served moment together with its body, so a feed transaction that is still open while
     * the consent is withdrawn is not seen here; the hint it hands out is then not withdrawn explicitly (a `complete` next answer drops it, an
     * incomplete one keeps it until the end of the auction). The moment of the consent is read BEFORE it is cleared. No tombstone without [ids] (no key): the consent is still cleared
     * and the miss is counted. The caller holds the locks of the auctions. @return the number of tombstone rows written.
     */
    fun withdraw(
        targets: Collection<AuctionWithdrawalTarget>,
        partnerId: Uuid?,
        ids: AuctionTeaserIds?,
        now: LocalDateTime,
        clearOptIn: Boolean,
        requireServed: Boolean,
    ): Int {
        if (targets.isEmpty()) return 0
        val ordered = targets.sortedBy { it.auctionId }
        val relevant =
            if (requireServed) {
                val optInAtById = optInMoments(ordered.map { it.auctionId })
                val lastServed = PartnerServerStore.latestAuctionFeedServedAt()
                ordered.filter { t ->
                    val at = optInAtById[t.auctionId]
                    at == null || (lastServed != null && lastServed >= at)
                }
            } else {
                ordered
            }
        if (clearOptIn) {
            ordered.map { it.auctionId }.chunked(CHUNK).forEach { chunk ->
                AuctionTable.update({ AuctionTable.id inList chunk }) {
                    it[federationOptIn] = false
                    it[federationOptInAt] = null
                }
            }
        }
        if (ids == null) {
            if (relevant.isNotEmpty()) AuctionFederationKey.skippedNoKey.addAndGet(relevant.size.toLong())
            return 0
        }
        var written = 0
        relevant.forEach { t ->
            if (insertTombstone(teaserId = ids.of(t.auctionId), endsAt = t.endsAt, partnerId = partnerId, now = now)) written++
        }
        return written
    }

    private fun optInMoments(auctionIds: List<Uuid>): Map<Uuid, LocalDateTime?> =
        auctionIds
            .chunked(CHUNK)
            .flatMap { chunk ->
                AuctionTable
                    .selectAll()
                    .where { AuctionTable.id inList chunk }
                    .map { it[AuctionTable.id] to it[AuctionTable.federationOptInAt] }
            }.toMap()

    /**
     * Writes ONE tombstone unless its life is already over. The key is derived, so an expired row that the retention sweep has not removed yet would
     * swallow this withdrawal (`insertIgnore`) although the feed no longer reports it: drop it first, a live row is left alone.
     */
    private fun insertTombstone(
        teaserId: String,
        endsAt: LocalDateTime,
        partnerId: Uuid?,
        now: LocalDateTime,
        relayOrigin: String? = null,
        relayReporter: Uuid? = null,
    ): Boolean {
        val expires = AuctionTeaserTombstonePolicy.expiresAt(createdAt = now, endsAt = endsAt)
        if (expires <= now) return false
        val tid = tombstoneId(teaserId = teaserId, partnerId = partnerId, relayOriginHost = relayOrigin)
        AuctionTeaserTombstoneTable.deleteWhere {
            (AuctionTeaserTombstoneTable.id eq tid) and (AuctionTeaserTombstoneTable.expiresAt lessEq now)
        }
        val inserted =
            AuctionTeaserTombstoneTable.insertIgnore {
                it[id] = tid
                it[AuctionTeaserTombstoneTable.teaserId] = teaserId
                it[AuctionTeaserTombstoneTable.partnerId] = partnerId
                it[relayOriginHost] = relayOrigin
                it[relayReporterPartnerId] = relayReporter
                it[createdAt] = now
                it[expiresAt] = expires
            }
        return inserted.insertedCount > 0
    }

    /** A hint of ANOTHER origin that this server relayed and now has to report as withdrawn. [endsAt] bounds the life of the tombstone. */
    internal data class RelayedTarget(
        val teaserId: String,
        val endsAt: LocalDateTime,
        val originHost: String,
    )

    /**
     * Reports hints of ANOTHER origin that this server relayed as withdrawn (the pass-on). [partnerId] `null` = for every partner.
     * [requireServedSince] writes a tombstone only if some partner fetched this server's feed at or after that moment (nothing can have been handed
     * on before). The caller holds the locks of the received rows. @return the number of tombstone rows written.
     */
    fun withdrawRelayed(
        targets: Collection<RelayedTarget>,
        partnerId: Uuid?,
        now: LocalDateTime,
        requireServedSince: LocalDateTime?,
        maxRelayRows: Int? = null,
        reporterId: Uuid? = null,
        maxRelayRowsPerReporter: Int? = null,
    ): Int {
        if (targets.isEmpty()) return 0
        if (requireServedSince != null) {
            val lastServed = PartnerServerStore.latestAuctionFeedServedAt()
            if (lastServed == null || lastServed < requireServedSince) return 0
        }
        var written = 0
        var rows = if (maxRelayRows != null) AuctionTeaserTombstoneTable.selectAll().count().toInt() else 0
        var ofReporter = if (reporterId != null && maxRelayRowsPerReporter != null) countRelayRowsOfReporter(reporterId) else 0
        targets.sortedBy { it.teaserId }.forEach { t ->
            if ((maxRelayRows != null && rows >= maxRelayRows) ||
                (reporterId != null && maxRelayRowsPerReporter != null && ofReporter >= maxRelayRowsPerReporter)
            ) {
                AuctionPullCounters.relayTombstonesSkipped.incrementAndGet()
                return@forEach
            }
            if (insertTombstone(
                    teaserId = t.teaserId,
                    endsAt = t.endsAt,
                    partnerId = partnerId,
                    now = now,
                    relayOrigin = t.originHost,
                    relayReporter = reporterId,
                )
            ) {
                written++
                rows++
                ofReporter++
            }
        }
        return written
    }

    private fun countRelayRowsOfReporter(reporterId: Uuid): Int =
        AuctionTeaserTombstoneTable
            .selectAll()
            .where { AuctionTeaserTombstoneTable.relayReporterPartnerId eq reporterId }
            .count()
            .toInt()

    /** A foreign hint came back (its origin or reporter relisted it): the global pass-on tombstone of exactly that (origin, id) is moot. */
    fun forgetRelayedTombstone(
        originHost: String,
        teaserId: String,
    ) {
        AuctionTeaserTombstoneTable.deleteWhere {
            (AuctionTeaserTombstoneTable.teaserId eq teaserId) and AuctionTeaserTombstoneTable.partnerId.isNull() and
                (AuctionTeaserTombstoneTable.relayOriginHost eq originHost)
        }
    }

    /** Every tombstone row, live or expired (the pass-on cap counts these). */
    fun countTombstoneRows(): Int = AuctionTeaserTombstoneTable.selectAll().count().toInt()

    /** The seller gave the consent again: the global tombstone of this server's own earlier withdrawal is moot. */
    fun forgetOwnGlobalTombstone(teaserId: String) {
        AuctionTeaserTombstoneTable.deleteWhere {
            (AuctionTeaserTombstoneTable.teaserId eq teaserId) and AuctionTeaserTombstoneTable.partnerId.isNull() and
                AuctionTeaserTombstoneTable.relayOriginHost.isNull()
        }
    }

    /** The partner switch is on again: the tombstones scoped to this partner are moot. */
    fun forgetPartnerTombstones(partnerId: Uuid) = deletePartnerTombstones(partnerId)

    /** A partner was removed: its scoped tombstones go, explicitly (independent of the foreign key's ON DELETE rule). */
    fun deletePartnerTombstones(partnerId: Uuid) {
        AuctionTeaserTombstoneTable.deleteWhere { AuctionTeaserTombstoneTable.partnerId eq partnerId }
    }

    /** The teaser ids (of [candidates]) that still carry a LIVE global tombstone of this server: their hint is on again ("item wins"). */
    fun liveOwnGlobalTombstoneIds(
        candidates: Collection<String>,
        now: LocalDateTime,
    ): Set<String> =
        candidates
            .toSet()
            .chunked(CHUNK)
            .flatMap { chunk ->
                AuctionTeaserTombstoneTable
                    .select(AuctionTeaserTombstoneTable.teaserId)
                    .where {
                        (AuctionTeaserTombstoneTable.teaserId inList chunk) and
                            AuctionTeaserTombstoneTable.partnerId.isNull() and
                            AuctionTeaserTombstoneTable.relayOriginHost.isNull() and
                            (AuctionTeaserTombstoneTable.expiresAt greater now)
                    }.map { it[AuctionTeaserTombstoneTable.teaserId] }
            }.toSet()

    /**
     * The live withdrawals a partner has to be told: scope = every partner or exactly [partnerId], not yet expired. Own rows (consent withdrawn by a
     * member of THIS server) have their own scan budget; pass-on rows (c3) come on top, except to the partner that CAUSED them (the reporter). One row
     * per hint (the earliest day wins, an own row beats a pass-on row of the same id), ordered by day then id so the cut is deterministic.
     */
    fun liveWithdrawals(
        partnerId: Uuid,
        now: LocalDateTime,
        scanLimit: Int,
    ): AuctionLiveWithdrawals {
        val scope =
            (AuctionTeaserTombstoneTable.expiresAt greater now) and
                (AuctionTeaserTombstoneTable.partnerId.isNull() or (AuctionTeaserTombstoneTable.partnerId eq partnerId))

        fun scan(extra: Op<Boolean>) =
            AuctionTeaserTombstoneTable
                .selectAll()
                .where { scope and extra }
                .orderBy(AuctionTeaserTombstoneTable.createdAt to SortOrder.ASC, AuctionTeaserTombstoneTable.id to SortOrder.ASC)
                .limit(scanLimit + 1)
                .toList()

        val ownScan = scan(AuctionTeaserTombstoneTable.relayOriginHost.isNull())
        val relayScan =
            scan(
                AuctionTeaserTombstoneTable.relayOriginHost.isNotNull() and
                    (
                        AuctionTeaserTombstoneTable.relayReporterPartnerId.isNull() or
                            (AuctionTeaserTombstoneTable.relayReporterPartnerId neq partnerId)
                    ),
            )
        val capped = ownScan.size > scanLimit || relayScan.size > scanLimit

        fun earliest(rows: List<ResultRow>): Map<String, AuctionTeaserWithdrawalRow> {
            val map = LinkedHashMap<String, AuctionTeaserWithdrawalRow>()
            rows.forEach {
                val row =
                    AuctionTeaserWithdrawalRow(
                        teaserId = it[AuctionTeaserTombstoneTable.teaserId],
                        withdrawnAt = it[AuctionTeaserTombstoneTable.createdAt].date,
                    )
                val known = map[row.teaserId]
                if (known == null || row.withdrawnAt < known.withdrawnAt) map[row.teaserId] = row
            }
            return map
        }
        val own = earliest(ownScan.take(scanLimit))
        val relay = earliest(relayScan.take(scanLimit)).filterKeys { it !in own }
        val rows =
            (own.values + relay.values)
                .sortedWith(compareByDescending<AuctionTeaserWithdrawalRow> { it.withdrawnAt }.thenBy { it.teaserId })
        return AuctionLiveWithdrawals(rows = rows, scanCapped = capped)
    }

    /**
     * Teaser ids of our OWN live withdrawals (no relay origin) that apply to [partnerId]. The feed build drops relayed copies of these ids, so a foreign
     * origin listing OUR id cannot make "item wins" swallow our withdrawal.
     */
    fun liveOwnWithdrawnIds(
        partnerId: Uuid,
        now: LocalDateTime,
        scanLimit: Int,
    ): Set<String> =
        AuctionTeaserTombstoneTable
            .selectAll()
            .where {
                (AuctionTeaserTombstoneTable.expiresAt greater now) and AuctionTeaserTombstoneTable.relayOriginHost.isNull() and
                    (AuctionTeaserTombstoneTable.partnerId.isNull() or (AuctionTeaserTombstoneTable.partnerId eq partnerId))
            }
            // Same order as the own scan of [liveWithdrawals]: with more rows than the budget both cut at the same place, so an id that is
            // published as withdrawn is always also in this set.
            .orderBy(AuctionTeaserTombstoneTable.createdAt to SortOrder.ASC, AuctionTeaserTombstoneTable.id to SortOrder.ASC)
            .limit(scanLimit)
            .map { it[AuctionTeaserTombstoneTable.teaserId] }
            .toSet()

    fun hasLiveWithdrawalsFor(
        partnerId: Uuid,
        now: LocalDateTime,
    ): Boolean =
        !AuctionTeaserTombstoneTable
            .selectAll()
            .where {
                (AuctionTeaserTombstoneTable.expiresAt greater now) and
                    (AuctionTeaserTombstoneTable.partnerId.isNull() or (AuctionTeaserTombstoneTable.partnerId eq partnerId)) and
                    (
                        AuctionTeaserTombstoneTable.relayOriginHost.isNull() or
                            AuctionTeaserTombstoneTable.relayReporterPartnerId.isNull() or
                            (AuctionTeaserTombstoneTable.relayReporterPartnerId neq partnerId)
                    )
            }.limit(1)
            .empty()

    /** Live tombstones of every scope, for the admin counter. */
    fun countLiveWithdrawals(now: LocalDateTime): Int =
        AuctionTeaserTombstoneTable
            .selectAll()
            .where { AuctionTeaserTombstoneTable.expiresAt greater now }
            .count()
            .toInt()

    /** Idempotent. At most [limit] rows per call (ids ascending). @return the number of rows deleted. */
    fun deleteExpiredTombstones(
        now: LocalDateTime,
        limit: Int = Int.MAX_VALUE,
    ): Int {
        require(limit > 0) { "limit must be positive" }
        val ids =
            AuctionTeaserTombstoneTable
                .select(AuctionTeaserTombstoneTable.id)
                .where { AuctionTeaserTombstoneTable.expiresAt lessEq now }
                .orderBy(AuctionTeaserTombstoneTable.id to SortOrder.ASC)
                .limit(limit)
                .map { it[AuctionTeaserTombstoneTable.id] }
        ids.chunked(CHUNK).forEach { chunk -> AuctionTeaserTombstoneTable.deleteWhere { AuctionTeaserTombstoneTable.id inList chunk } }
        return ids.size
    }

    /**
     * A restore brings back a foreign state: the exchange goes OFF and every consent is cleared, so an old backup can never re-publish what has been
     * withdrawn since. Returns `true` when something had to be reset (then ONE audit entry without an actor is written).
     */
    fun resetAfterRestore(now: LocalDateTime): Boolean {
        val wasEnabled = settings().enabled
        val consents = countOptIns()
        if (wasEnabled) {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[auctionFederationEnabled] = false
            }
        }
        if (consents > 0) {
            AuctionTable.update({ AuctionTable.federationOptIn eq true }) {
                it[federationOptIn] = false
                it[federationOptInAt] = null
            }
        }
        if (!wasEnabled && consents == 0L) return false
        auditResetByRestore(consents = consents, now = now)
        return true
    }

    /** The audit entry of [resetAfterRestore]: the number of consents cleared, nothing else (no auction id, no title, no amount). */
    fun auditResetByRestore(
        consents: Long,
        now: LocalDateTime,
    ) {
        AuditLogRecorder.record(
            actorMemberId = null,
            actorRole = null,
            entityType = AuditEntityType.PARTNER_SERVER,
            entityId = ORGANIZATION_SETTINGS_ID,
            action = AuditAction.UPDATE,
            after =
                Json.encodeToString(
                    PartnerAuditSnapshot.serializer(),
                    PartnerAuditSnapshot(kind = PartnerAuditKind.AUCTION_RESET_BY_RESTORE, subject = consents.toString()),
                ),
            occurredAt = now,
        )
    }
}
