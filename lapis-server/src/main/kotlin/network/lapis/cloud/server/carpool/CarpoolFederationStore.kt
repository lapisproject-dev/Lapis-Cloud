package network.lapis.cloud.server.carpool

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.carpool.feed.CarpoolTeaserIds
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.CarpoolPostingTable
import network.lapis.cloud.server.db.generated.CarpoolTeaserTombstoneTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.rpc.ORGANIZATION_SETTINGS_ID
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CarpoolPostingType
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.domain.PartnerAuditKind
import network.lapis.cloud.shared.domain.PartnerAuditSnapshot
import network.lapis.cloud.shared.domain.clampHopLimit
import org.jetbrains.exposed.v1.core.Op
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
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** The state of the carpool exchange of this server. [hopLimit] is always inside 1..3. */
internal data class CarpoolFederationSettings(
    val enabled: Boolean,
    val hopLimit: Int,
)

/** V1.9.314: a hint of another origin that this server relayed and now has to report as withdrawn. The pseudonymous id and the day, nothing else. */
internal data class CarpoolRelayedTarget(
    val teaserId: String,
    val departureDate: LocalDate,
    /** V1.9.319: the origin server of the relayed hint. It is part of the identity of the pass-on tombstone, so it can never collide with our own. */
    val originHost: String,
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

    /**
     * V1.9.319 (wave 7): called ONLY by the restore of a backup, after every table is back. A restore brings back consents that were withdrawn after
     * the backup was taken, and it does not bring back the tombstones (they are local state), so the next feed would hand those trips out again.
     * The safe direction: the instance switch goes off and EVERY consent is cleared -- the members agree again, after an administrator switched the
     * exchange on. Writes one audit entry (no actor: a restore is a system event). @return `true` when anything had to be reset.
     */
    fun resetAfterRestore(now: LocalDateTime): Boolean {
        val wasEnabled = settings().enabled
        val consents =
            CarpoolPostingTable
                .selectAll()
                .where { CarpoolPostingTable.federationOptIn eq true }
                .count()
        if (wasEnabled) {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[carpoolFederationEnabled] = false
            }
        }
        if (consents > 0) {
            CarpoolPostingTable.update({ CarpoolPostingTable.federationOptIn eq true }) {
                it[federationOptIn] = false
                it[federationOptInAt] = null
            }
        }
        // The in-feed opt-in moment may survive on a row whose flag is false (never written that way, but a restored row is foreign input).
        CarpoolPostingTable.update({ CarpoolPostingTable.federationOptInAt.isNotNull() }) { it[federationOptInAt] = null }
        if (!wasEnabled && consents == 0L) return false
        auditResetByRestore(consents = consents, now = now)
        return true
    }

    /** The audit entry of [resetAfterRestore]; also used by the restore when it neutralized the rows while writing them (nothing left to reset). */
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
                    PartnerAuditSnapshot(kind = PartnerAuditKind.CARPOOL_RESET_BY_RESTORE, subject = consents.toString()),
                ),
            occurredAt = now,
        )
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
        relayOriginHost: String? = null,
    ): Uuid {
        // V1.9.319: a pass-on tombstone has its own key space (the origin host is part of it), so it never collides with an own withdrawal.
        val seed =
            if (relayOriginHost == null) {
                "carpool-tombstone/v1\u0000$teaserId\u0000${partnerId ?: "*"}"
            } else {
                "carpool-tombstone-relay/v1\u0000$teaserId\u0000$relayOriginHost\u0000${partnerId ?: "*"}"
            }
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
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
            if (insertTombstone(teaserId = ids.of(t.postingId), departure = t.departureDate, partnerId = partnerId, now = now)) written++
        }
        return written
    }

    /**
     * Writes ONE tombstone unless its life is already over. The key is derived, so an expired row that the retention sweep has not removed yet
     * would swallow this withdrawal (ON CONFLICT DO NOTHING) although the feed no longer reports it: drop it first, a live row is left alone.
     */
    private fun insertTombstone(
        teaserId: String,
        departure: LocalDate,
        partnerId: Uuid?,
        now: LocalDateTime,
        relayOrigin: String? = null,
        relayReporter: Uuid? = null,
    ): Boolean {
        val expires = CarpoolTeaserTombstonePolicy.expiresAt(createdAt = now, departure = departure)
        if (expires <= now) return false
        val tid = tombstoneId(teaserId = teaserId, partnerId = partnerId, relayOriginHost = relayOrigin)
        CarpoolTeaserTombstoneTable.deleteWhere {
            (CarpoolTeaserTombstoneTable.id eq tid) and (CarpoolTeaserTombstoneTable.expiresAt lessEq now)
        }
        val inserted =
            CarpoolTeaserTombstoneTable.insertIgnore {
                it[id] = tid
                it[CarpoolTeaserTombstoneTable.teaserId] = teaserId
                it[CarpoolTeaserTombstoneTable.partnerId] = partnerId
                it[relayOriginHost] = relayOrigin
                it[relayReporterPartnerId] = relayReporter
                it[createdAt] = now
                it[expiresAt] = expires
            }
        return inserted.insertedCount > 0
    }

    /**
     * V1.9.314 (wave 4): reports hints of ANOTHER origin that this server relayed as withdrawn (the pass-on of an erasure). [partnerId] `null` = for
     * every partner. [requireServedSince] (when set) writes a tombstone only if some partner fetched this server's feed at or after that moment: nothing
     * can have been handed on before. The caller holds the locks of the received rows. @return the number of tombstone rows written.
     */
    fun withdrawRelayed(
        targets: Collection<CarpoolRelayedTarget>,
        partnerId: Uuid?,
        now: LocalDateTime,
        requireServedSince: LocalDateTime?,
        maxRelayRows: Int? = null,
        /** V1.9.319: the partner that caused these rows (the source of the copies). With [maxRelayRowsPerReporter] it bounds one reporter's rows. */
        reporterId: Uuid? = null,
        maxRelayRowsPerReporter: Int? = null,
    ): Int {
        if (targets.isEmpty()) return 0
        if (requireServedSince != null) {
            val lastServed = latestFeedServedAt()
            if (lastServed == null || lastServed < requireServedSince) return 0
        }
        var written = 0
        // V1.9.318: a global cap on the table (the rows do not record which source caused them). Our OWN withdrawals ([withdraw]) are never capped.
        var rows = if (maxRelayRows != null) countTombstoneRows() else 0
        // V1.9.319: one reporter cannot use up the whole table (a hostile partner would otherwise starve the pass-on tombstones of every other source).
        var ofReporter = if (reporterId != null && maxRelayRowsPerReporter != null) countRelayRowsOfReporter(reporterId) else 0
        targets.sortedBy { it.teaserId }.forEach { t ->
            if ((maxRelayRows != null && rows >= maxRelayRows) ||
                (reporterId != null && maxRelayRowsPerReporter != null && ofReporter >= maxRelayRowsPerReporter)
            ) {
                CarpoolPullCounters.relayTombstonesSkipped.incrementAndGet()
                return@forEach
            }
            if (insertTombstone(
                    teaserId = t.teaserId,
                    departure = t.departureDate,
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

    private fun countRelayRowsOfReporter(reporterId: Uuid): Int =
        CarpoolTeaserTombstoneTable
            .selectAll()
            .where { CarpoolTeaserTombstoneTable.relayReporterPartnerId eq reporterId }
            .count()
            .toInt()

    /**
     * The owner gave the consent again: the global tombstone of THIS server's OWN withdrawal is moot (and "item wins" in the feed would hide it
     * anyway). Never touches a pass-on row (`relay_origin_host` set): a foreign hint with the same id must not be able to erase our own withdrawal.
     */
    fun forgetOwnGlobalTombstone(teaserId: String) {
        CarpoolTeaserTombstoneTable.deleteWhere {
            (CarpoolTeaserTombstoneTable.teaserId eq teaserId) and (CarpoolTeaserTombstoneTable.partnerId.isNull()) and
                CarpoolTeaserTombstoneTable.relayOriginHost.isNull()
        }
    }

    /** A foreign hint came back (its origin or reporter relisted it): the global pass-on tombstone of exactly that (origin, id) is moot. */
    fun forgetRelayedTombstone(
        originHost: String,
        teaserId: String,
    ) {
        CarpoolTeaserTombstoneTable.deleteWhere {
            (CarpoolTeaserTombstoneTable.teaserId eq teaserId) and (CarpoolTeaserTombstoneTable.partnerId.isNull()) and
                (CarpoolTeaserTombstoneTable.relayOriginHost eq originHost)
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
        data class Scanned(
            val teaserId: String,
            val createdAt: LocalDateTime,
            val reporter: Uuid?,
        )

        fun live(): Op<Boolean> =
            (CarpoolTeaserTombstoneTable.expiresAt greater now) and
                (CarpoolTeaserTombstoneTable.partnerId.isNull() or (CarpoolTeaserTombstoneTable.partnerId eq partnerId))

        fun scan(
            where: Op<Boolean>,
            limit: Int,
        ): List<Scanned> =
            CarpoolTeaserTombstoneTable
                .selectAll()
                .where { live() and where }
                .orderBy(CarpoolTeaserTombstoneTable.createdAt to SortOrder.ASC, CarpoolTeaserTombstoneTable.id to SortOrder.ASC)
                .limit(limit + 1)
                .map {
                    Scanned(
                        teaserId = it[CarpoolTeaserTombstoneTable.teaserId],
                        createdAt = it[CarpoolTeaserTombstoneTable.createdAt],
                        reporter = it[CarpoolTeaserTombstoneTable.relayReporterPartnerId],
                    )
                }

        // Own withdrawals (consent withdrawn by a member of THIS server) have their OWN scan budget: pass-on rows from partners can never push them out.
        val ownRows = scan(where = CarpoolTeaserTombstoneTable.relayOriginHost.isNull(), limit = scanLimit)
        // Pass-on rows share their budget FAIRLY per reporting partner: one hostile reporter fills at most its own share of the scan window, so the rows
        // of every other reporter (written later) are still read. Rows without a reporter (legacy) form one group of their own.
        val relayWhere = CarpoolTeaserTombstoneTable.relayOriginHost.isNotNull()
        val reporters =
            CarpoolTeaserTombstoneTable
                .select(CarpoolTeaserTombstoneTable.relayReporterPartnerId)
                .withDistinct()
                .where { live() and relayWhere }
                .map { it[CarpoolTeaserTombstoneTable.relayReporterPartnerId] }
        val share = maxOf(MIN_RELAY_SCAN_SHARE, scanLimit / reporters.size.coerceAtLeast(1))
        var relayCapped = false
        val relayRows =
            reporters.flatMap { reporter ->
                val own =
                    if (reporter ==
                        null
                    ) {
                        CarpoolTeaserTombstoneTable.relayReporterPartnerId.isNull()
                    } else {
                        CarpoolTeaserTombstoneTable.relayReporterPartnerId eq
                            reporter
                    }
                val rows = scan(where = relayWhere and own, limit = share)
                if (rows.size > share) relayCapped = true
                rows.take(share)
            }
        val capped = ownRows.size > scanLimit || relayCapped

        fun earliest(rows: List<Scanned>): LinkedHashMap<String, Scanned> {
            val map = LinkedHashMap<String, Scanned>()
            rows.forEach { row ->
                val known = map[row.teaserId]
                if (known == null || row.createdAt.date < known.createdAt.date) map[row.teaserId] = row
            }
            return map
        }
        val own = earliest(ownRows.take(scanLimit))
        val relay = earliest(relayRows)
        return CarpoolLiveWithdrawals(
            rows =
                own.map { CarpoolTeaserWithdrawalRow(teaserId = it.key, withdrawnAt = it.value.createdAt.date, own = true) } +
                    relay
                        .filterKeys { it !in own }
                        .map {
                            CarpoolTeaserWithdrawalRow(
                                teaserId = it.key,
                                withdrawnAt = it.value.createdAt.date,
                                reporter = it.value.reporter,
                            )
                        },
            scanCapped = capped,
        )
    }

    private const val MIN_RELAY_SCAN_SHARE = 100

    /**
     * Teaser ids of our OWN live withdrawals (provenance: no relay origin) that apply to [partnerId] (scope = every partner or exactly this one).
     * The feed build drops relayed copies of these ids, so a foreign origin listing our id cannot make "item wins" swallow our withdrawal.
     */
    fun liveOwnWithdrawnIds(
        partnerId: Uuid,
        now: LocalDateTime,
        scanLimit: Int,
    ): Set<String> =
        CarpoolTeaserTombstoneTable
            .selectAll()
            .where {
                (CarpoolTeaserTombstoneTable.expiresAt greater now) and
                    CarpoolTeaserTombstoneTable.relayOriginHost.isNull() and
                    (CarpoolTeaserTombstoneTable.partnerId.isNull() or (CarpoolTeaserTombstoneTable.partnerId eq partnerId))
            }.limit(scanLimit)
            .map { it[CarpoolTeaserTombstoneTable.teaserId] }
            .toSet()

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

    /**
     * Idempotent. At most [limit] rows per call (ids ascending); a caller that must empty the backlog calls again while a full batch comes back.
     * @return the number of rows deleted.
     */
    fun deleteExpiredTombstones(
        now: LocalDateTime,
        limit: Int = Int.MAX_VALUE,
    ): Int {
        require(limit > 0) { "limit must be positive" }
        val ids =
            CarpoolTeaserTombstoneTable
                .select(CarpoolTeaserTombstoneTable.id)
                .where { CarpoolTeaserTombstoneTable.expiresAt lessEq now }
                .orderBy(CarpoolTeaserTombstoneTable.id to SortOrder.ASC)
                .limit(limit)
                .map { it[CarpoolTeaserTombstoneTable.id] }
        ids.chunked(CHUNK).forEach { chunk -> CarpoolTeaserTombstoneTable.deleteWhere { CarpoolTeaserTombstoneTable.id inList chunk } }
        return ids.size
    }

    /** Every tombstone row, live or expired. */
    fun countTombstoneRows(): Int = CarpoolTeaserTombstoneTable.selectAll().count().toInt()

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
