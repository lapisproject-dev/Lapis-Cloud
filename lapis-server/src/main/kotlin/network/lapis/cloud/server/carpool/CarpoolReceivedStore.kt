package network.lapis.cloud.server.carpool

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.CarpoolReceivedHintTable
import network.lapis.cloud.server.db.generated.CarpoolReceivedTombstoneTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.partner.PartnerBlocklistPattern
import network.lapis.cloud.server.partner.PartnerHost
import network.lapis.cloud.server.partner.PartnerRow
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.shared.domain.CarpoolFederationLimits
import network.lapis.cloud.shared.domain.CarpoolReceivedRejectReason
import network.lapis.cloud.shared.domain.CarpoolTeaser
import network.lapis.cloud.shared.domain.PartnerCarpoolHintLimits
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** One hint of a received feed that passed the format checks of the reader (no key and no signature check yet; the store does those). */
internal class ParsedRelayItem(
    val teaser: CarpoolTeaser,
    val originHost: String,
    val wireHops: Int,
    val maxHops: Int,
    val via: List<String>,
    val originSignature: String,
)

/** What the reader makes of a feed: the valid items, the ids of the withdrawals, the number of rejected entries and whether the lists are complete. */
internal class ParsedTeaserFeed(
    val complete: Boolean,
    val items: List<ParsedRelayItem>,
    val withdrawnIds: List<String>,
    val rejected: Int,
    val rejectedUnsigned: Int,
    /** V1.9.318: [rejected] by cause, counted by the reader (format, missing signature, blocked, hop limit, expired). */
    val rejectedByReason: Map<CarpoolReceivedRejectReason, Int> = emptyMap(),
)

internal class CarpoolPullApplyResult(
    /** `false` = the source was no longer a valid source in this transaction (pin changed, switched off, blocked, removed): nothing was stored. */
    val applied: Boolean,
    val accepted: Int = 0,
    val rejected: Int = 0,
    val unknownOriginKey: Int = 0,
    val removed: Int = 0,
    val withdrawn: Int = 0,
)

/** A stored hint that may be handed on: what [CarpoolTeaserFeedBuilder][network.lapis.cloud.server.carpool.feed.CarpoolTeaserFeedBuilder] needs. */
internal class RepublishableHint(
    val teaser: CarpoolTeaser,
    val originHost: String,
    val storedHops: Int,
    val maxHops: Int,
    val via: List<String>,
    val originSignature: String,
)

/** V1.9.317 (wave 5): what an ACTIVE member sees of a received hint -- these four fields and nothing else. */
internal class MemberHint(
    val departureDate: LocalDate,
    val fromPostcodeArea: String,
    val toPostcodeArea: String,
    val originHost: String,
)

internal class MemberHintPage(
    val items: List<MemberHint>,
    val hasMore: Boolean,
)

/**
 * In-memory counters since the start, for the administrator only. Numbers only: no host, no id, no content. Every restart begins at 0 (a persisted
 * window is an open question, see the architecture document).
 */
internal object CarpoolPullCounters {
    @Volatile
    var startedAt: LocalDateTime? = null
        private set

    /** All rejected hints (the sum of every cause). */
    val rejected = AtomicLong(0)
    val unknownOriginKey = AtomicLong(0)
    val accepted = AtomicLong(0)
    val expiredDeleted = AtomicLong(0)
    val budgetDeferred = AtomicLong(0)
    val relayTombstonesSkipped = AtomicLong(0)
    private val byReason = EnumMap<CarpoolReceivedRejectReason, AtomicLong>(CarpoolReceivedRejectReason::class.java)

    init {
        CarpoolReceivedRejectReason.entries.forEach { byReason[it] = AtomicLong(0) }
    }

    /** Idempotent: the first call wins. */
    fun markStarted(now: LocalDateTime) {
        if (startedAt == null) startedAt = now
    }

    fun reject(
        reason: CarpoolReceivedRejectReason,
        n: Int = 1,
    ) {
        if (n <= 0) return
        requireNotNull(byReason[reason]).addAndGet(n.toLong())
        rejected.addAndGet(n.toLong())
        if (reason == CarpoolReceivedRejectReason.UNKNOWN_ORIGIN_KEY) unknownOriginKey.addAndGet(n.toLong())
    }

    fun rejectedByReason(): Map<CarpoolReceivedRejectReason, Long> =
        CarpoolReceivedRejectReason.entries.associateWith {
            requireNotNull(byReason[it]).get()
        }

    internal fun resetForTest() {
        startedAt = null
        rejected.set(0)
        unknownOriginKey.set(0)
        accepted.set(0)
        expiredDeleted.set(0)
        budgetDeferred.set(0)
        relayTombstonesSkipped.set(0)
        byReason.values.forEach { it.set(0) }
    }
}

/**
 * V1.9.314 "Mitfahrzentrale-Foederation" W4 -- the ONLY reader and writer of `carpool_received_hint` and `carpool_received_tombstone` (pinned by
 * `CarpoolFederationTripwireTest`). A hint of another server is date + two postcode digits + the pseudonym of its origin; nothing else is stored.
 * Everything runs inside the caller's transaction and never touches the network.
 *
 * Locking, one rule: the `partner_server` row of the SOURCE first ([PartnerServerStore.lockPartner], so a switch-off or removal and an apply
 * serialise), then the received rows (ascending id), then tombstone inserts (`carpool_teaser_tombstone` only through [CarpoolFederationStore]).
 * Never `organization_settings`, never `carpool_posting`.
 */
internal object CarpoolReceivedStore {
    const val MAX_PER_SOURCE = 1000
    const val MAX_TOTAL = 5000

    /** V1.9.318: a source always gets at least this many rows, however many partners share (the fair share is `MAX_TOTAL / partners`). */
    const val MIN_PER_SOURCE = 200

    /** V1.9.318: caps of the local blocks (tombstones of withdrawals reported by partners): per reporting source and in total. */
    const val MAX_BLOCKS_PER_SOURCE = 5000
    const val MAX_BLOCKS_TOTAL = 20_000

    /** V1.9.318: cap of the pass-on tombstones (relayed withdrawals) in `carpool_teaser_tombstone`; our own withdrawals are never capped. */
    const val MAX_RELAY_TOMBSTONES_TOTAL = 20_000

    /** V1.9.319: the share of the table ONE reporting partner may fill with pass-on tombstones (the V1.9.318 open question "starvation by one reporter"). */
    const val MAX_RELAY_TOMBSTONES_PER_REPORTER = 5000

    /** The fair share of ONE source when [sharingPartners] partners share: never below [MIN_PER_SOURCE], never above [MAX_PER_SOURCE]. */
    fun effectivePerSourceCap(sharingPartners: Int): Int =
        minOf(
            MAX_PER_SOURCE,
            maxOf(
                MIN_PER_SOURCE,
                MAX_TOTAL / sharingPartners.coerceAtLeast(1),
            ),
        )

    private const val MAX_HOST_LENGTH = 253

    /** A copy of a source that has not been pulled successfully for this long is dropped (the pass-on of withdrawals must stay timely). */
    val STALE_AFTER = 24.hours
    private const val CHUNK = 400

    private class HintRow(
        val id: Uuid,
        val teaserId: String,
        val originHost: String,
        val hops: Int,
        val maxHops: Int,
        val via: List<String>,
        val sourcePartnerId: Uuid,
        val departureDate: LocalDate,
        val fromArea: String,
        val toArea: String,
        val signature: String,
        val receivedAt: LocalDateTime,
        val expiresAt: LocalDateTime,
    ) {
        val forwardable: Boolean get() = hops < maxHops
    }

    private fun ResultRow.toHint() =
        HintRow(
            id = this[CarpoolReceivedHintTable.id],
            teaserId = this[CarpoolReceivedHintTable.teaserId],
            originHost = this[CarpoolReceivedHintTable.originHost],
            hops = this[CarpoolReceivedHintTable.hops].toInt(),
            maxHops = this[CarpoolReceivedHintTable.maxHops].toInt(),
            via = this[CarpoolReceivedHintTable.viaHosts]?.split(',')?.filter { it.isNotEmpty() } ?: emptyList(),
            sourcePartnerId = this[CarpoolReceivedHintTable.sourcePartnerId],
            departureDate = this[CarpoolReceivedHintTable.departureDate],
            fromArea = this[CarpoolReceivedHintTable.fromPostcodeArea],
            toArea = this[CarpoolReceivedHintTable.toPostcodeArea],
            signature = this[CarpoolReceivedHintTable.originSignature],
            receivedAt = this[CarpoolReceivedHintTable.receivedAt],
            expiresAt = this[CarpoolReceivedHintTable.expiresAt],
        )

    /** `(departure day + 1) 00:00 UTC`: "at the latest with the day of departure". */
    internal fun expiresAtOf(departure: LocalDate): LocalDateTime =
        departure.plus(1, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC).toLocalDateTime(TimeZone.UTC)

    private fun derivedId(
        purpose: String,
        originHost: String,
        teaserId: String,
    ): Uuid {
        val digest = MessageDigest.getInstance("SHA-256").digest("$purpose\u0000$originHost\u0000$teaserId".toByteArray(Charsets.UTF_8))
        val bytes = digest.copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        return Uuid.fromByteArray(bytes)
    }

    private fun lockedRows(where: Op<Boolean>): List<HintRow> =
        CarpoolReceivedHintTable
            .selectAll()
            .where(where)
            .orderBy(CarpoolReceivedHintTable.id to SortOrder.ASC)
            .forUpdate()
            .map { it.toHint() }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
    // apply: the result of ONE pull of ONE partner
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Stores the result of a pull. [source] is the row as it was BEFORE the request (its pinned key and ETag): the request was signed for and
     * answered by exactly that key, so a pin that changed meanwhile voids the answer. Order inside the one transaction: the source row is locked and
     * checked, the withdrawals are applied, the items are verified and stored, the missing ones of a complete list are removed, the pull state is
     * written last.
     */
    fun apply(
        source: PartnerRow,
        feed: ParsedTeaserFeed,
        etag: String?,
        next: LocalDateTime,
        now: LocalDateTime,
    ): CarpoolPullApplyResult {
        val row = PartnerServerStore.lockPartner(source.id) ?: return CarpoolPullApplyResult(applied = false)
        val settings = CarpoolFederationStore.settings()
        if (!settings.enabled || !row.active || !row.carpoolSharing) return CarpoolPullApplyResult(applied = false)
        if (row.signingPublicKeyPem == null ||
            row.signingKeyChangedAt != null ||
            row.signingKeyId != source.signingKeyId ||
            row.signingPublicKeyPem != source.signingPublicKeyPem
        ) {
            return CarpoolPullApplyResult(applied = false)
        }
        if (PartnerBlocklistPattern.isBlocked(host = row.host, patterns = PartnerServerStore.blockPatterns())) {
            return CarpoolPullApplyResult(applied = false)
        }

        // (2) withdrawals: only on copies that came from this source or whose origin IS this source.
        val hits = ArrayList<HintRow>()
        feed.withdrawnIds.distinct().chunked(CHUNK).forEach { chunk ->
            hits +=
                lockedRows(
                    (CarpoolReceivedHintTable.teaserId inList chunk) and
                        ((CarpoolReceivedHintTable.sourcePartnerId eq row.id) or (CarpoolReceivedHintTable.originHost eq row.host)),
                )
        }
        var blocksTotal = if (hits.isEmpty()) 0 else countBlocksActive(now)
        if (hits.isNotEmpty()) {
            // The blocks this source has set stay bounded: the oldest ones of THIS source make room (never those of another source).
            val ofSource = countBlocksOfReporter(reporter = row.id, now = now)
            val needed = ofSource + hits.size - MAX_BLOCKS_PER_SOURCE
            if (needed > 0) blocksTotal -= deleteOldestBlocks(reporter = row.id, count = needed, now = now)
        }
        val withdrawn = hits.size
        hits.forEach { h ->
            val writeBlock = blocksTotal < MAX_BLOCKS_TOTAL
            if (writeBlock) blocksTotal++
            removeAsWithdrawn(h = h, reporter = row.id, now = now, writeBlock = writeBlock)
        }

        // (3) items
        var accepted = 0
        var rejected = feed.rejected
        var unknownKey = 0
        val reasons = EnumMap<CarpoolReceivedRejectReason, Int>(CarpoolReceivedRejectReason::class.java)

        fun reject(reason: CarpoolReceivedRejectReason) {
            rejected++
            reasons.merge(reason, 1, Int::plus)
        }
        // A total, deterministic order (day, origin, id): two applies that run in parallel take the same rows in the same order, and a full table is
        // filled with the nearest departures first.
        val ordered = feed.items.sortedWith(compareBy({ it.teaser.departureDate }, { it.originHost }, { it.teaser.teaserId }))
        val ids = ordered.map { it.teaser.teaserId }.distinct()
        val existing = HashMap<Pair<String, String>, HintRow>()
        val tombs = HashMap<Pair<String, String>, ReceivedTombstone>()
        ids.chunked(CHUNK).forEach { chunk ->
            lockedRows(CarpoolReceivedHintTable.teaserId inList chunk).forEach { existing[it.originHost to it.teaserId] = it }
            CarpoolReceivedTombstoneTable
                .selectAll()
                .where { (CarpoolReceivedTombstoneTable.teaserId inList chunk) and (CarpoolReceivedTombstoneTable.expiresAt greater now) }
                .forEach {
                    tombs[it[CarpoolReceivedTombstoneTable.originHost] to it[CarpoolReceivedTombstoneTable.teaserId]] =
                        ReceivedTombstone(reportedBy = it[CarpoolReceivedTombstoneTable.reportedByPartnerId])
                }
        }
        val sharingPartners =
            PartnerServerTable
                .selectAll()
                .where { (PartnerServerTable.active eq true) and (PartnerServerTable.carpoolSharing eq true) }
                .count()
                .toInt()
        val cap = effectivePerSourceCap(sharingPartners)
        var perSource =
            CarpoolReceivedHintTable
                .selectAll()
                .where { CarpoolReceivedHintTable.sourcePartnerId eq row.id }
                .count()
                .toInt()
        var total = CarpoolReceivedHintTable.selectAll().count().toInt()
        val keyMemo = HashMap<String, String?>()
        val listedKeys = HashSet<Pair<String, String>>()
        ordered.forEach { item ->
            val key = item.originHost to item.teaser.teaserId
            listedKeys += key
            val tomb = tombs[key]
            if (tomb != null) {
                // E7: only the source that reported the withdrawal, or the origin itself (hop 0), may bring it back (a renewed consent).
                val allowed = tomb.reportedBy == row.id || (item.wireHops == 0 && row.host == item.originHost)
                if (!allowed) {
                    reject(CarpoolReceivedRejectReason.BLOCKED)
                    return@forEach
                }
            }
            val pem = keyMemo.getOrPut(item.originHost) { CarpoolOriginKeys.resolve(item.originHost) }
            if (pem == null) {
                unknownKey++
                reject(CarpoolReceivedRejectReason.UNKNOWN_ORIGIN_KEY)
                return@forEach
            }
            if (!CarpoolOriginSignatures.verify(
                    teaser = item.teaser,
                    originHost = item.originHost,
                    maxHops = item.maxHops,
                    signatureB64 = item.originSignature,
                    publicKeyPem = pem,
                )
            ) {
                reject(CarpoolReceivedRejectReason.SIGNATURE)
                return@forEach
            }
            val storedHops = item.wireHops + 1
            val expires = expiresAtOf(item.teaser.departureDate)
            if (expires <= now) {
                reject(CarpoolReceivedRejectReason.EXPIRED)
                return@forEach
            }
            val current = existing[key]
            if (current == null) {
                // Reject, never displace: a full table or a full share turns THIS item away; no row of another source is ever deleted for it.
                if (perSource >= cap || total >= MAX_TOTAL) {
                    reject(CarpoolReceivedRejectReason.CAPACITY)
                    return@forEach
                }
                if (tomb != null) clearTombstone(originHost = item.originHost, teaserId = item.teaser.teaserId)
                val inserted =
                    CarpoolReceivedHintTable.insertIgnore {
                        it[id] = derivedId(purpose = "carpool-received/v1", originHost = item.originHost, teaserId = item.teaser.teaserId)
                        it[teaserId] = item.teaser.teaserId
                        it[originHost] = item.originHost
                        it[hops] = storedHops.toShort()
                        it[maxHops] = item.maxHops.toShort()
                        it[viaHosts] = item.via.joinToString(",").ifEmpty { null }
                        it[sourcePartnerId] = row.id
                        it[departureDate] = item.teaser.departureDate
                        it[fromPostcodeArea] = item.teaser.fromPostcodeArea
                        it[toPostcodeArea] = item.teaser.toPostcodeArea
                        it[originSignature] = item.originSignature
                        it[receivedAt] = now
                        it[lastSeenAt] = now
                        it[expiresAt] = expires
                    }
                if (inserted.insertedCount > 0) {
                    perSource++
                    total++
                    accepted++
                }
            } else {
                val sameSource = current.sourcePartnerId == row.id
                val shorter = storedHops < current.hops
                if (tomb != null) clearTombstone(originHost = item.originHost, teaserId = item.teaser.teaserId)
                if (!sameSource && shorter && perSource >= cap) {
                    reject(CarpoolReceivedRejectReason.CAPACITY)
                } else if (sameSource || shorter) {
                    CarpoolReceivedHintTable.update({ CarpoolReceivedHintTable.id eq current.id }) {
                        it[hops] = storedHops.toShort()
                        it[maxHops] = item.maxHops.toShort()
                        it[viaHosts] = item.via.joinToString(",").ifEmpty { null }
                        it[sourcePartnerId] = row.id
                        it[departureDate] = item.teaser.departureDate
                        it[fromPostcodeArea] = item.teaser.fromPostcodeArea
                        it[toPostcodeArea] = item.teaser.toPostcodeArea
                        it[originSignature] = item.originSignature
                        it[lastSeenAt] = now
                        it[expiresAt] = expires
                    }
                    if (!sameSource) perSource++
                    accepted++
                }
                // else: another source already holds a shorter path (or the per-source cap): nothing stored here, so not counted as accepted.
            }
        }

        // (4) a complete list: what this source no longer lists (neither as item nor as withdrawn) is gone. No tombstone: "missing" is not "withdrawn".
        var removed = 0
        if (feed.complete) {
            val withdrawnSet = feed.withdrawnIds.toSet()
            lockedRows(CarpoolReceivedHintTable.sourcePartnerId eq row.id)
                .filter { (it.originHost to it.teaserId) !in listedKeys && it.teaserId !in withdrawnSet }
                .forEach {
                    CarpoolReceivedHintTable.deleteWhere { CarpoolReceivedHintTable.id eq it.id }
                    removed++
                }
        }

        // (5) the pull state, last.
        PartnerServerStore.recordCarpoolPullSuccess(
            id = row.id,
            status = "OK",
            accepted = accepted,
            rejected = rejected,
            etag = etag,
            keepEtag = false,
            next = next,
            now = now,
        )
        feed.rejectedByReason.forEach { (reason, n) -> CarpoolPullCounters.reject(reason = reason, n = n) }
        reasons.forEach { (reason, n) -> CarpoolPullCounters.reject(reason = reason, n = n) }
        if (accepted > 0) CarpoolPullCounters.accepted.addAndGet(accepted.toLong())
        return CarpoolPullApplyResult(
            applied = true,
            accepted = accepted,
            rejected = rejected,
            unknownOriginKey = unknownKey,
            removed = removed,
            withdrawn = withdrawn,
        )
    }

    /** The local block against re-importing a withdrawn foreign hint: `(departure + 2 days) 00:00 UTC` (no 14-day cap). */
    internal object CarpoolReceivedBlockPolicy {
        const val GRACE_DAYS_AFTER_DEPARTURE = 2

        fun expiresAt(departure: LocalDate): LocalDateTime =
            departure.plus(GRACE_DAYS_AFTER_DEPARTURE, DateTimeUnit.DAY).atStartOfDayIn(TimeZone.UTC).toLocalDateTime(TimeZone.UTC)
    }

    private class ReceivedTombstone(
        val reportedBy: Uuid?,
    )

    /**
     * V1.9.319: a hint came back (its origin or the reporter listed it again): the local block and ONLY the pass-on tombstone of exactly this
     * (origin, id) are moot. Our OWN global tombstone for the same id is never touched, so a hostile partner that learned one of our teaser ids can
     * no longer erase our withdrawal (the V1.9.314 "known limit").
     */
    private fun clearTombstone(
        originHost: String,
        teaserId: String,
    ) {
        CarpoolReceivedTombstoneTable.deleteWhere {
            (CarpoolReceivedTombstoneTable.originHost eq originHost) and (CarpoolReceivedTombstoneTable.teaserId eq teaserId)
        }
        // The renewed consent: the global pass-on tombstone of this hint is moot.
        CarpoolFederationStore.forgetRelayedTombstone(originHost = originHost, teaserId = teaserId)
    }

    /** A withdrawal reported by [reporter]: the copy goes, a local block is written, and (when it could have been handed on) the pass-on tombstone. */
    private fun removeAsWithdrawn(
        h: HintRow,
        reporter: Uuid,
        now: LocalDateTime,
        writeBlock: Boolean,
    ) {
        CarpoolReceivedHintTable.deleteWhere { CarpoolReceivedHintTable.id eq h.id }
        // V1.9.319: the LOCAL block lasts until the hint could no longer be imported anyway (departure + 2 days), NOT 14 days: the signature of the
        // origin has no expiry, so a dishonest relay could otherwise replay a withdrawn hint after the block ran out. (The pass-on tombstone on the wire
        // below keeps its 14 days: a partner never needs it longer.)
        val expires = CarpoolReceivedBlockPolicy.expiresAt(departure = h.departureDate)
        if (expires > now && writeBlock) {
            val tid = derivedId(purpose = "carpool-received-block/v1", originHost = h.originHost, teaserId = h.teaserId)
            CarpoolReceivedTombstoneTable.deleteWhere {
                (CarpoolReceivedTombstoneTable.id eq tid) and
                    (CarpoolReceivedTombstoneTable.expiresAt lessEq now)
            }
            CarpoolReceivedTombstoneTable.insertIgnore {
                it[id] = tid
                it[teaserId] = h.teaserId
                it[originHost] = h.originHost
                it[reportedByPartnerId] = reporter
                it[createdAt] = now
                it[expiresAt] = expires
            }
        }
        relayWithdrawal(rows = listOf(h), now = now)
    }

    /** E6 / E9: hints that were forwardable are reported as withdrawn to EVERY partner, but only if a partner fetched our feed since we got them. */
    private fun relayWithdrawal(
        rows: Collection<HintRow>,
        now: LocalDateTime,
    ) {
        rows.filter { it.forwardable }.forEach {
            CarpoolFederationStore.withdrawRelayed(
                targets =
                    listOf(
                        CarpoolRelayedTarget(teaserId = it.teaserId, departureDate = it.departureDate, originHost = it.originHost),
                    ),
                partnerId = null,
                now = now,
                requireServedSince = it.receivedAt,
                maxRelayRows = MAX_RELAY_TOMBSTONES_TOTAL,
                reporterId = it.sourcePartnerId,
                maxRelayRowsPerReporter = MAX_RELAY_TOMBSTONES_PER_REPORTER,
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
    // reads for the feed
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The stored hints that may be handed on to [partner]: not expired, the date inside the window, the path still within `min(maxHops, ownHopLimit,
     * 3)`, the partner is neither the source nor the origin nor a relay on the path, and the origin is not blocked. Sorted by (hops, date, id).
     */
    fun republishableFor(
        partner: PartnerRow,
        ownHopLimit: Int,
        today: LocalDate,
        now: LocalDateTime,
        limit: Int,
    ): List<RepublishableHint> {
        val patterns = PartnerServerStore.blockPatterns()
        // A deactivated partner is not a source we hand on from (its copies only age out; they are not relayed meanwhile).
        val inactiveSources =
            PartnerServerTable
                .selectAll()
                .where { PartnerServerTable.active eq false }
                .map { it[PartnerServerTable.id] }
                .toSet()
        val until = today.plus(CarpoolFederationLimits.MAX_DAYS_AHEAD, DateTimeUnit.DAY)
        return CarpoolReceivedHintTable
            .selectAll()
            .where {
                (CarpoolReceivedHintTable.expiresAt greater now) and (CarpoolReceivedHintTable.departureDate greaterEq today) and
                    (CarpoolReceivedHintTable.departureDate lessEq until) and (CarpoolReceivedHintTable.sourcePartnerId neq partner.id)
            }.orderBy(
                CarpoolReceivedHintTable.hops to SortOrder.ASC,
                CarpoolReceivedHintTable.departureDate to SortOrder.ASC,
                CarpoolReceivedHintTable.teaserId to SortOrder.ASC,
            ).map { it.toHint() }
            .asSequence()
            .filter { it.sourcePartnerId !in inactiveSources }
            .filter { it.hops + 1 <= minOf(it.maxHops, ownHopLimit, CarpoolFederationLimits.MAX_HOPS) }
            .filter { it.originHost != partner.host && partner.host !in it.via }
            .filter { !PartnerBlocklistPattern.isBlocked(host = it.originHost, patterns = patterns) }
            .take(limit)
            .map {
                RepublishableHint(
                    teaser =
                        CarpoolTeaser(
                            teaserId = it.teaserId,
                            departureDate = it.departureDate,
                            fromPostcodeArea = it.fromArea,
                            toPostcodeArea = it.toArea,
                        ),
                    originHost = it.originHost,
                    storedHops = it.hops,
                    maxHops = it.maxHops,
                    via = it.via,
                    originSignature = it.signature,
                )
            }.toList()
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
    // V1.9.317 (wave 5): the read path for members (the only one outside the feed)
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    private val AREA = Regex("^[0-9]{2}$")

    /**
     * The hints an ACTIVE member may see, sorted by (day, from, to, origin host, id), one page of [PAGE_SIZE]. Pure read in the caller's
     * transaction: no lock, no audit, no network. Never reads or returns teaser id, hops, path, signature or source.
     *
     * Filters (all of them, in this order): not expired, day not past, no live local block (tombstone of the SAME (origin host, teaser id)),
     * syntax of the two areas and of the host (second layer, the reader checked them already), origin not blocked, source partner still an active,
     * sharing, not blocked partner (defence in depth: the drop on switch-off or staleness may lag behind).
     *
     * Known limit: offset paging; rows can shift between two requests, which can skip or repeat an entry.
     */
    fun listForMembers(
        today: LocalDate,
        now: LocalDateTime,
        page: Int,
    ): MemberHintPage {
        val patterns = PartnerServerStore.blockPatterns()
        val sources = PartnerServerStore.listPartners().associateBy { it.id }
        val candidates =
            CarpoolReceivedHintTable
                .select(
                    CarpoolReceivedHintTable.id,
                    CarpoolReceivedHintTable.teaserId,
                    CarpoolReceivedHintTable.originHost,
                    CarpoolReceivedHintTable.sourcePartnerId,
                    CarpoolReceivedHintTable.departureDate,
                    CarpoolReceivedHintTable.fromPostcodeArea,
                    CarpoolReceivedHintTable.toPostcodeArea,
                ).where { (CarpoolReceivedHintTable.expiresAt greater now) and (CarpoolReceivedHintTable.departureDate greaterEq today) }
                .orderBy(
                    CarpoolReceivedHintTable.departureDate to SortOrder.ASC,
                    CarpoolReceivedHintTable.fromPostcodeArea to SortOrder.ASC,
                    CarpoolReceivedHintTable.toPostcodeArea to SortOrder.ASC,
                    CarpoolReceivedHintTable.originHost to SortOrder.ASC,
                    CarpoolReceivedHintTable.id to SortOrder.ASC,
                ).limit(MAX_TOTAL + 1)
                .toList()
        // Only the tombstones of the (at most MAX_TOTAL + 1) candidates are read, chunked: the tombstone table itself is not capped.
        val blocked = HashSet<Pair<String, String>>()
        candidates.map { it[CarpoolReceivedHintTable.teaserId] }.distinct().chunked(CHUNK).forEach { chunk ->
            CarpoolReceivedTombstoneTable
                .select(CarpoolReceivedTombstoneTable.originHost, CarpoolReceivedTombstoneTable.teaserId)
                .where { (CarpoolReceivedTombstoneTable.teaserId inList chunk) and (CarpoolReceivedTombstoneTable.expiresAt greater now) }
                .forEach { blocked += it[CarpoolReceivedTombstoneTable.originHost] to it[CarpoolReceivedTombstoneTable.teaserId] }
        }
        val visible =
            candidates
                .asSequence()
                .filter { (it[CarpoolReceivedHintTable.originHost] to it[CarpoolReceivedHintTable.teaserId]) !in blocked }
                .filter {
                    AREA.matches(it[CarpoolReceivedHintTable.fromPostcodeArea]) && AREA.matches(it[CarpoolReceivedHintTable.toPostcodeArea])
                }.filter {
                    val host = it[CarpoolReceivedHintTable.originHost]
                    host.length <= MAX_HOST_LENGTH && PartnerHost.normalizeHostOnly(host) == host
                }.filter { !PartnerBlocklistPattern.isBlocked(host = it[CarpoolReceivedHintTable.originHost], patterns = patterns) }
                .filter {
                    val src = sources[it[CarpoolReceivedHintTable.sourcePartnerId]]
                    src != null &&
                        src.active &&
                        src.carpoolSharing &&
                        !PartnerBlocklistPattern.isBlocked(host = src.host, patterns = patterns)
                }.drop(page * PartnerCarpoolHintLimits.PAGE_SIZE)
                .take(PartnerCarpoolHintLimits.PAGE_SIZE + 1)
                .toList()
        val hasMore = visible.size > PartnerCarpoolHintLimits.PAGE_SIZE && page < PartnerCarpoolHintLimits.MAX_PAGE
        return MemberHintPage(
            items =
                visible.take(PartnerCarpoolHintLimits.PAGE_SIZE).map {
                    MemberHint(
                        departureDate = it[CarpoolReceivedHintTable.departureDate],
                        fromPostcodeArea = it[CarpoolReceivedHintTable.fromPostcodeArea],
                        toPostcodeArea = it[CarpoolReceivedHintTable.toPostcodeArea],
                        originHost = it[CarpoolReceivedHintTable.originHost],
                    )
                },
            hasMore = hasMore,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
    // switching off, removal, block, staleness, retention
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Deletes the copies of [sourceId] after reporting the forwardable ones as withdrawn (E9 b). */
    fun dropFromSource(
        sourceId: Uuid,
        now: LocalDateTime,
    ): Int = dropRows(rows = lockedRows(CarpoolReceivedHintTable.sourcePartnerId eq sourceId), now = now)

    /** The instance switch went off: every copy goes, the forwardable ones are reported as withdrawn, every ETag is forgotten (E9). */
    fun withdrawAllForSwitchOff(now: LocalDateTime): Int {
        // Partner rows first (the lock order of an apply), so a pull that is being applied finishes before the copies are taken away.
        PartnerServerStore.lockAllPartners()
        val n = dropRows(rows = lockedRows(Op.TRUE), now = now)
        PartnerServerStore.clearCarpoolPullEtag(null)
        return n
    }

    /** A block pattern was added: copies whose source or origin it matches go (E9). */
    fun dropMatchingBlock(
        patterns: Collection<String>,
        now: LocalDateTime,
    ): Int {
        val sourceHosts =
            PartnerServerTable
                .selectAll()
                .associate { it[PartnerServerTable.id] to it[PartnerServerTable.host] }
        val hit =
            lockedRows(Op.TRUE).filter { h ->
                PartnerBlocklistPattern.isBlocked(host = h.originHost, patterns = patterns) ||
                    sourceHosts[h.sourcePartnerId]?.let { PartnerBlocklistPattern.isBlocked(host = it, patterns = patterns) } == true
            }
        return dropRows(rows = hit, now = now)
    }

    private fun dropRows(
        rows: List<HintRow>,
        now: LocalDateTime,
    ): Int {
        if (rows.isEmpty()) return 0
        relayWithdrawal(rows = rows, now = now)
        rows.map { it.id }.chunked(CHUNK).forEach { chunk ->
            CarpoolReceivedHintTable.deleteWhere { CarpoolReceivedHintTable.id inList chunk }
        }
        return rows.size
    }

    /** The copies that a partner may have received from this server (for the scope tombstone of a switch-off for that partner, E9 a). */
    fun scopedTombstoneTargetsFor(partner: PartnerRow): List<CarpoolRelayedTarget> =
        CarpoolReceivedHintTable
            .selectAll()
            .where { CarpoolReceivedHintTable.sourcePartnerId neq partner.id }
            .map { it.toHint() }
            .filter { it.forwardable && it.originHost != partner.host && partner.host !in it.via }
            .map { CarpoolRelayedTarget(teaserId = it.teaserId, departureDate = it.departureDate, originHost = it.originHost) }

    /** Sources whose last successful pull is older than [STALE_AFTER] (or never succeeded) lose their copies (E9). */
    fun dropStale(now: LocalDateTime): Int {
        val cutoff = (now.toInstant(TimeZone.UTC) - STALE_AFTER).toLocalDateTime(TimeZone.UTC)
        val sources =
            CarpoolReceivedHintTable
                .selectAll()
                .map { it[CarpoolReceivedHintTable.sourcePartnerId] }
                .distinct()
        var dropped = 0
        sources.forEach { sid ->
            val ok = PartnerServerStore.findPartner(sid)?.carpoolPullOkAt
            if (ok == null || ok < cutoff) {
                // Re-check on the LOCKED row: a pull may have committed between the read above and the lock (then the copies are fresh).
                val locked = PartnerServerStore.lockPartner(sid)
                val lockedOk = locked?.carpoolPullOkAt
                if (locked != null && lockedOk != null && lockedOk >= cutoff) return@forEach
                dropped += dropFromSource(sourceId = sid, now = now)
            }
        }
        return dropped
    }

    /**
     * Idempotent: expired copies and expired blocks. At most [limit] rows of EACH table per call (ids ascending, so two callers never lock in opposite
     * order); a caller that must empty the backlog calls again while a table returns a full batch.
     * @return the number of rows deleted.
     */
    fun deleteExpired(
        now: LocalDateTime,
        limit: Int = Int.MAX_VALUE,
    ): Int =
        deleteExpiredFrom(
            table = CarpoolReceivedHintTable,
            idColumn = CarpoolReceivedHintTable.id,
            expiresColumn = CarpoolReceivedHintTable.expiresAt,
            now = now,
            limit = limit,
        ) +
            deleteExpiredFrom(
                table = CarpoolReceivedTombstoneTable,
                idColumn = CarpoolReceivedTombstoneTable.id,
                expiresColumn = CarpoolReceivedTombstoneTable.expiresAt,
                now = now,
                limit = limit,
            )

    private fun <T : Table> deleteExpiredFrom(
        table: T,
        idColumn: Column<Uuid>,
        expiresColumn: Column<LocalDateTime>,
        now: LocalDateTime,
        limit: Int,
    ): Int {
        require(limit > 0) { "limit must be positive" }
        val ids =
            table
                .select(idColumn)
                .where { expiresColumn lessEq now }
                .orderBy(idColumn to SortOrder.ASC)
                .limit(limit)
                .map { it[idColumn] }
        ids.chunked(CHUNK).forEach { chunk -> table.deleteWhere { idColumn inList chunk } }
        return ids.size
    }

    /** Every row, expired or not (the lag of the retention sweep is visible as the difference to [countTotal]). */
    fun countStoredRows(): Int = CarpoolReceivedHintTable.selectAll().count().toInt()

    /** Live local blocks (set because a partner reported a withdrawal). */
    fun countBlocksActive(now: LocalDateTime): Int =
        CarpoolReceivedTombstoneTable
            .selectAll()
            .where { CarpoolReceivedTombstoneTable.expiresAt greater now }
            .count()
            .toInt()

    private fun countBlocksOfReporter(
        reporter: Uuid,
        now: LocalDateTime,
    ): Int =
        CarpoolReceivedTombstoneTable
            .selectAll()
            .where {
                (CarpoolReceivedTombstoneTable.reportedByPartnerId eq reporter) and
                    (CarpoolReceivedTombstoneTable.expiresAt greater now)
            }.count()
            .toInt()

    /** Live blocks per reporting partner (one query). A count only. */
    fun countBlocksByReporter(now: LocalDateTime): Map<Uuid, Int> =
        CarpoolReceivedTombstoneTable
            .selectAll()
            .where {
                (CarpoolReceivedTombstoneTable.expiresAt greater now) and CarpoolReceivedTombstoneTable.reportedByPartnerId.isNotNull()
            }.groupingBy { requireNotNull(it[CarpoolReceivedTombstoneTable.reportedByPartnerId]) }
            .eachCount()

    /** Deletes the [count] oldest live blocks of [reporter]. @return how many were deleted. */
    private fun deleteOldestBlocks(
        reporter: Uuid,
        count: Int,
        now: LocalDateTime,
    ): Int {
        val ids =
            CarpoolReceivedTombstoneTable
                .select(CarpoolReceivedTombstoneTable.id)
                .where {
                    (CarpoolReceivedTombstoneTable.reportedByPartnerId eq reporter) and
                        (CarpoolReceivedTombstoneTable.expiresAt greater now)
                }.orderBy(CarpoolReceivedTombstoneTable.createdAt to SortOrder.ASC, CarpoolReceivedTombstoneTable.id to SortOrder.ASC)
                .limit(count)
                .map { it[CarpoolReceivedTombstoneTable.id] }
        ids.chunked(CHUNK).forEach { chunk -> CarpoolReceivedTombstoneTable.deleteWhere { CarpoolReceivedTombstoneTable.id inList chunk } }
        return ids.size
    }

    fun countBySource(): Map<Uuid, Int> =
        CarpoolReceivedHintTable
            .selectAll()
            .groupingBy { it[CarpoolReceivedHintTable.sourcePartnerId] }
            .eachCount()

    fun countTotal(now: LocalDateTime): Int =
        CarpoolReceivedHintTable
            .selectAll()
            .where { CarpoolReceivedHintTable.expiresAt greater now }
            .count()
            .toInt()
}
