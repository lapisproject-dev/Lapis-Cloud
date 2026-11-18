package network.lapis.cloud.server.carpool

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.carpool.feed.CarpoolTeaserIds
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.db.generated.CarpoolTeaserTombstoneTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.clampHopLimit
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.core.vendors.currentDialect
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** The state of the carpool exchange of this server. [hopLimit] is always inside 1..3. */
internal data class CarpoolFederationSettings(
    val enabled: Boolean,
    val hopLimit: Int,
)

/**
 * One trip that is a CANDIDATE for the teaser feed of wave 2: opted in, an offer, inside the date window. Carries exactly the fields the one
 * eligibility rule and the one projection need -- no notes, no meeting note, no time, no author id.
 */
internal data class CarpoolTeaserCandidate(
    val postingId: Uuid,
    val type: CarpoolPostingType,
    val fromPlace: String,
    val toPlace: String,
    val departureDate: LocalDate,
    val seatsOffered: Int?,
    val authorActive: Boolean,
)

/**
 * V1.9.311 "Mitfahrzentrale-Foederation" W1 -- the ONLY reader and writer of `organization_settings.carpool_federation_*` and
 * `carpool_posting.federation_opt_in` (pinned by `CarpoolFederationTripwireTest`). V1.9.313 (W3): also the ONLY reader and writer of
 * `carpool_posting.federation_opt_in_at` and of the whole table `carpool_teaser_tombstone`. All functions run inside the caller's transaction.
 * Nothing here talks to the network.
 */
internal object CarpoolFederationStore {
    fun settings(): CarpoolFederationSettings {
        val row =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .single()
        return CarpoolFederationSettings(
            enabled = row[OrganizationSettingsTable.carpoolFederationEnabled],
            hopLimit = clampHopLimit(row[OrganizationSettingsTable.carpoolFederationHopLimit].toInt()),
        )
    }

    /** Locks the settings row, then writes. The caller validates [hopLimit] (1..3). Returns the settings as they were BEFORE. */
    fun setSettings(
        enabled: Boolean,
        hopLimit: Int,
    ): CarpoolFederationSettings {
        require(hopLimit in 1..3) { "hop limit out of range" }
        OrganizationSettingsTable
            .selectAll()
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .forUpdate()
            .single()
        val before = settings()
        OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
            it[carpoolFederationEnabled] = enabled
            it[carpoolFederationHopLimit] = hopLimit.toShort()
        }
        return before
    }

    fun optIn(postingId: Uuid): Boolean =
        CarpoolPostingTable
            .selectAll()
            .where { CarpoolPostingTable.id eq postingId }
            .singleOrNull()
            ?.get(CarpoolPostingTable.federationOptIn) ?: false

    fun setOptIn(
        postingId: Uuid,
        value: Boolean,
        now: LocalDateTime = DbClock.nowLocalDateTime(),
    ) {
        CarpoolPostingTable.update({ CarpoolPostingTable.id eq postingId }) {
            it[federationOptIn] = value
            it[federationOptInAt] = if (value) now else null
        }
    }

    /**
     * V1.9.312: the opted-in OFFER trips departing in [from]..[until] (both inclusive), ordered by `(departure_date, id)` so the result is
     * deterministic. At most [scanLimit] + 1 rows come back: the caller detects "more than the scan limit" by the extra row (the feed is then
     * marked incomplete). The only reader of `federation_opt_in` for the feed (pinned by `CarpoolFederationTripwireTest`). The author is "active"
     * when his status is an organisation-member status and he is not anonymised -- the same floor as `requireActiveMembership`, one query.
     */
    fun teaserCandidates(
        from: LocalDate,
        until: LocalDate,
        scanLimit: Int,
    ): List<CarpoolTeaserCandidate> =
        CarpoolPostingTable
            .innerJoin(MemberTable)
            .selectAll()
            .where {
                (CarpoolPostingTable.federationOptIn eq true) and
                    (CarpoolPostingTable.type eq CarpoolPostingType.OFFER) and
                    (CarpoolPostingTable.departureDate greaterEq from) and
                    (CarpoolPostingTable.departureDate lessEq until)
            }.orderBy(CarpoolPostingTable.departureDate to SortOrder.ASC, CarpoolPostingTable.id to SortOrder.ASC)
            .limit(scanLimit + 1)
            .withPostingShareLock()
            .map {
                CarpoolTeaserCandidate(
                    postingId = it[CarpoolPostingTable.id],
                    type = it[CarpoolPostingTable.type],
                    fromPlace = it[CarpoolPostingTable.fromPlace],
                    toPlace = it[CarpoolPostingTable.toPlace],
                    departureDate = it[CarpoolPostingTable.departureDate],
                    seatsOffered = it[CarpoolPostingTable.seatsOffered],
                    authorActive =
                        it[MemberTable.status] in MemberStatusSets.ORGANIZATION_MEMBER && it[MemberTable.anonymizedAt] == null,
                )
            }

    /**
     * PostgreSQL: `FOR SHARE OF carpool_posting`. The feed transaction keeps the shared lock on every trip it hands out until it commits (and
     * with it the served-at moment); a consent-driven withdrawal holds `FOR UPDATE` on the trip, so it either waits for that commit and then
     * sees the served-at moment, or it commits first and the feed no longer sees the consent. Without this a withdrawal racing the FIRST fetch
     * after a consent saw no served-at moment (not committed yet) and wrote no tombstone, while the feed still handed the hint out. The H2 of
     * the normal test task has no such option and is left alone.
     */
    private fun Query.withPostingShareLock(): Query =
        if (currentDialect is PostgreSQLDialect) {
            forUpdate(ForUpdateOption.PostgreSQL.ForShare(null, CarpoolPostingTable))
        } else {
            this
        }

    /** A trip that turns into a REQUEST loses the consent (it can never be shared). */
    fun clearOptIn(postingId: Uuid) = setOptIn(postingId = postingId, value = false)

    // ---------------------------------------------------------------------------------------------------------------------------------
    // V1.9.313 W3: withdrawal. Lock order everywhere: carpool_posting rows first (ascending id), tombstone inserts after.
    // ---------------------------------------------------------------------------------------------------------------------------------

    /**
     * The primary key of a tombstone is DERIVED from (teaser id, scope): a second withdrawal of the same hint for the same scope hits the same key
     * and is a no-op (`ON CONFLICT DO NOTHING`), so the life of a tombstone is never extended and no row-lock on the settings is needed. Not a
     * secret: the teaser id is a pseudonym already.
     */
    internal fun tombstoneId(
        teaserId: String,
        partnerId: Uuid?,
    ): Uuid {
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest("carpool-tombstone/v1\u0000$teaserId\u0000${partnerId ?: "*"}".toByteArray(Charsets.UTF_8))
        val bytes = digest.copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        return Uuid.fromByteArray(bytes)
    }

    /**
     * Reports the hints of [targets] as withdrawn. [partnerId] `null` = for every partner. [clearOptIn] also ends the consent of the trip.
     * [requireServed] (the consent-driven triggers) writes a tombstone only when some partner fetched the feed AFTER the consent was given:
     * nothing can have been handed out before, and the filter takes the lever out of an "create, consent, delete" flood. A tombstone is not
     * written when the departure grace period is already over, nor when [ids] is `null` (no key: the consent is still cleared, the miss is
     * counted). The caller holds the locks of the trips. @return the number of tombstone rows written.
     */
    fun withdraw(
        targets: Collection<CarpoolWithdrawalTarget>,
        partnerId: Uuid?,
        ids: CarpoolTeaserIds?,
        now: LocalDateTime,
        clearOptIn: Boolean,
        requireServed: Boolean,
    ): Int {
        if (targets.isEmpty()) return 0
        val ordered = targets.sortedBy { it.postingId }
        // The moment of the consent must be read BEFORE it is cleared.
        val relevant =
            if (requireServed) {
                val optInAtById = optInMoments(ordered.map { it.postingId })
                val lastServed = latestFeedServedAt()
                ordered.filter { t ->
                    val at = optInAtById[t.postingId]
                    at == null || (lastServed != null && lastServed >= at)
                }
            } else {
                ordered
            }
        if (clearOptIn) {
            ordered.map { it.postingId }.chunked(CHUNK).forEach { chunk ->
                CarpoolPostingTable.update({ CarpoolPostingTable.id inList chunk }) {
                    it[federationOptIn] = false
                    it[federationOptInAt] = null
                }
            }
        }
        if (ids == null) {
            if (relevant.isNotEmpty()) CarpoolWithdrawalKey.skippedNoKey.addAndGet(relevant.size.toLong())
            return 0
        }
        var written = 0
        relevant.forEach { t ->
            val expires = CarpoolTeaserTombstonePolicy.expiresAt(createdAt = now, departure = t.departureDate)
            if (expires <= now) return@forEach
            val teaserId = ids.of(t.postingId)
            // The key is derived, so an expired row that the retention sweep has not removed yet would swallow this withdrawal
            // (ON CONFLICT DO NOTHING) although the feed no longer reports it: drop it first, a live row is left alone.
            val tid = tombstoneId(teaserId = teaserId, partnerId = partnerId)
            CarpoolTeaserTombstoneTable.deleteWhere {
                (CarpoolTeaserTombstoneTable.id eq tid) and (CarpoolTeaserTombstoneTable.expiresAt lessEq now)
            }
            val inserted =
                CarpoolTeaserTombstoneTable.insertIgnore {
                    it[id] = tid
                    it[CarpoolTeaserTombstoneTable.teaserId] = teaserId
                    it[CarpoolTeaserTombstoneTable.partnerId] = partnerId
                    it[createdAt] = now
                    it[expiresAt] = expires
                }
            if (inserted.insertedCount > 0) written++
        }
        return written
    }

    private fun optInMoments(postingIds: List<Uuid>): Map<Uuid, LocalDateTime?> =
        postingIds
            .chunked(CHUNK)
            .flatMap { chunk ->
                CarpoolPostingTable
                    .selectAll()
                    .where { CarpoolPostingTable.id inList chunk }
                    .map { it[CarpoolPostingTable.id] to it[CarpoolPostingTable.federationOptInAt] }
            }.toMap()

    private fun latestFeedServedAt(): LocalDateTime? =
        network.lapis.cloud.server.partner.PartnerServerStore
            .latestCarpoolFeedServedAt()

    /** The consent is given again: the global tombstone of this hint is moot (and "item wins" in the feed would hide it anyway). */
    fun forgetGlobalTombstone(teaserId: String) {
        CarpoolTeaserTombstoneTable.deleteWhere {
            (CarpoolTeaserTombstoneTable.teaserId eq teaserId) and (CarpoolTeaserTombstoneTable.partnerId.isNull())
        }
    }

    /** The partner switch is on again: the tombstones scoped to this partner are moot. */
    fun forgetPartnerTombstones(partnerId: Uuid) = deletePartnerTombstones(partnerId)

    /** A partner was removed: its scoped tombstones go, explicitly (the generated table cannot express the foreign key's ON DELETE CASCADE). */
    fun deletePartnerTombstones(partnerId: Uuid) {
        CarpoolTeaserTombstoneTable.deleteWhere { CarpoolTeaserTombstoneTable.partnerId eq partnerId }
    }

    /**
     * The live withdrawals a partner has to be told: scope = every partner or exactly [partnerId], not yet expired. At most [scanLimit] rows are
     * read (+1 to detect an overflow); one row per hint (the earliest day wins), ordered by day then id so the cut is deterministic.
     */
    fun liveWithdrawals(
        partnerId: Uuid,
        now: LocalDateTime,
        scanLimit: Int,
    ): CarpoolLiveWithdrawals {
        val rows =
            CarpoolTeaserTombstoneTable
                .selectAll()
                .where {
                    (CarpoolTeaserTombstoneTable.expiresAt greater now) and
                        (CarpoolTeaserTombstoneTable.partnerId.isNull() or (CarpoolTeaserTombstoneTable.partnerId eq partnerId))
                }.orderBy(CarpoolTeaserTombstoneTable.createdAt to SortOrder.ASC, CarpoolTeaserTombstoneTable.id to SortOrder.ASC)
                .limit(scanLimit + 1)
                .map { it[CarpoolTeaserTombstoneTable.teaserId] to it[CarpoolTeaserTombstoneTable.createdAt] }
        val capped = rows.size > scanLimit
        val earliest = LinkedHashMap<String, LocalDate>()
        rows.take(scanLimit).forEach { (teaserId, createdAt) ->
            val day = createdAt.date
            val known = earliest[teaserId]
            if (known == null || day < known) earliest[teaserId] = day
        }
        return CarpoolLiveWithdrawals(
            rows =
                earliest.map {
                    CarpoolTeaserWithdrawalRow(teaserId = it.key, withdrawnAt = it.value)
                },
            scanCapped = capped,
        )
    }

    fun hasLiveWithdrawalsFor(
        partnerId: Uuid,
        now: LocalDateTime,
    ): Boolean =
        !CarpoolTeaserTombstoneTable
            .selectAll()
            .where {
                (CarpoolTeaserTombstoneTable.expiresAt greater now) and
                    (CarpoolTeaserTombstoneTable.partnerId.isNull() or (CarpoolTeaserTombstoneTable.partnerId eq partnerId))
            }.limit(1)
            .empty()

    /** Live tombstones of every scope, for the admin counter. */
    fun countLiveWithdrawals(now: LocalDateTime): Int =
        CarpoolTeaserTombstoneTable
            .selectAll()
            .where { CarpoolTeaserTombstoneTable.expiresAt greater now }
            .count()
            .toInt()

    /** Live tombstones scoped to exactly one partner, per partner (one query). */
    fun countLiveWithdrawalsByPartner(now: LocalDateTime): Map<Uuid, Int> =
        CarpoolTeaserTombstoneTable
            .selectAll()
            .where { (CarpoolTeaserTombstoneTable.expiresAt greater now) and CarpoolTeaserTombstoneTable.partnerId.isNotNull() }
            .groupingBy { requireNotNull(it[CarpoolTeaserTombstoneTable.partnerId]) }
            .eachCount()

    /** Idempotent. @return the number of rows deleted. */
    fun deleteExpiredTombstones(now: LocalDateTime): Int = CarpoolTeaserTombstoneTable.deleteWhere { expiresAt lessEq now }

    /** Opted-in trips whose author is no longer an active member -- the same author rule as [teaserCandidates]. At most [limit]. */
    fun optedInWithInactiveAuthor(limit: Int): List<CarpoolWithdrawalTarget> =
        CarpoolPostingTable
            .innerJoin(MemberTable)
            .selectAll()
            .where {
                (CarpoolPostingTable.federationOptIn eq true) and
                    ((MemberTable.status notInList MemberStatusSets.ORGANIZATION_MEMBER.toList()) or MemberTable.anonymizedAt.isNotNull())
            }.orderBy(CarpoolPostingTable.id to SortOrder.ASC)
            .limit(limit)
            .map { CarpoolWithdrawalTarget(postingId = it[CarpoolPostingTable.id], departureDate = it[CarpoolPostingTable.departureDate]) }

    /** Locks the given trips (ascending id) and keeps those that are STILL opted in and whose author is STILL inactive. */
    fun lockStillInactiveOptIns(candidates: List<CarpoolWithdrawalTarget>): List<CarpoolWithdrawalTarget> {
        val locked =
            candidates
                .map { it.postingId }
                .sorted()
                .chunked(CHUNK)
                .flatMap { chunk ->
                    CarpoolPostingTable
                        .selectAll()
                        .where { CarpoolPostingTable.id inList chunk }
                        .orderBy(CarpoolPostingTable.id to SortOrder.ASC)
                        .forUpdate()
                        .toList()
                }.filter { it[CarpoolPostingTable.federationOptIn] }
        if (locked.isEmpty()) return emptyList()
        val authors = locked.associate { it[CarpoolPostingTable.id] to it[CarpoolPostingTable.authorMemberId] }
        val inactiveAuthors =
            authors.values
                .toSet()
                .chunked(CHUNK)
                .flatMap { chunk ->
                    MemberTable
                        .selectAll()
                        .where { MemberTable.id inList chunk }
                        .filter {
                            !(it[MemberTable.status] in MemberStatusSets.ORGANIZATION_MEMBER && it[MemberTable.anonymizedAt] == null)
                        }.map { it[MemberTable.id] }
                }.toSet()
        return locked
            .filter { authors.getValue(it[CarpoolPostingTable.id]) in inactiveAuthors }
            .map { CarpoolWithdrawalTarget(postingId = it[CarpoolPostingTable.id], departureDate = it[CarpoolPostingTable.departureDate]) }
    }

    /** The opted-in trips of one author, locked in ascending id order (GDPR erasure). */
    fun lockOptedInTargetsOfAuthor(memberId: Uuid): List<CarpoolWithdrawalTarget> =
        CarpoolPostingTable
            .selectAll()
            .where { (CarpoolPostingTable.authorMemberId eq memberId) and (CarpoolPostingTable.federationOptIn eq true) }
            .orderBy(CarpoolPostingTable.id to SortOrder.ASC)
            .forUpdate()
            .map { CarpoolWithdrawalTarget(postingId = it[CarpoolPostingTable.id], departureDate = it[CarpoolPostingTable.departureDate]) }

    private const val CHUNK = 500
}
