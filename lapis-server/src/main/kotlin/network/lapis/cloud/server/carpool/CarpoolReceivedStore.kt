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
import network.lapis.cloud.server.partner.PartnerRow
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.shared.domain.CarpoolFederationLimits
import network.lapis.cloud.shared.domain.CarpoolTeaser
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
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

/** In-memory counters since the start, for the administrator only. */
internal object CarpoolPullCounters {
    val rejected = AtomicLong(0)
    val unknownOriginKey = AtomicLong(0)

    internal fun resetForTest() {
        rejected.set(0)
        unknownOriginKey.set(0)
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
            row.signingKeyId != source.signingKeyId ||
            row.signingPublicKeyPem != source.signingPublicKeyPem
        ) {
            return CarpoolPullApplyResult(applied = false)
        }
        if (PartnerBlocklistPattern.isBlocked(host = row.host, patterns = PartnerServerStore.blockPatterns())) {
            return CarpoolPullApplyResult(applied = false)
        }

        // (2) withdrawals: only on copies that came from this source or whose origin IS this source.
        var withdrawn = 0
        feed.withdrawnIds.distinct().chunked(CHUNK).forEach { chunk ->
            val hit =
                lockedRows(
                    (CarpoolReceivedHintTable.teaserId inList chunk) and
                        ((CarpoolReceivedHintTable.sourcePartnerId eq row.id) or (CarpoolReceivedHintTable.originHost eq row.host)),
                )
            hit.forEach { h ->
                removeAsWithdrawn(h = h, reporter = row.id, now = now)
                withdrawn++
            }
        }

        // (3) items
        var accepted = 0
        var rejected = feed.rejected
        var unknownKey = 0
        val ordered = feed.items.sortedWith(compareBy({ it.originHost }, { it.teaser.teaserId }))
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
                    rejected++
                    return@forEach
                }
            }
            val pem = keyMemo.getOrPut(item.originHost) { CarpoolOriginKeys.resolve(item.originHost) }
            if (pem == null) {
                unknownKey++
                rejected++
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
                rejected++
                return@forEach
            }
            val storedHops = item.wireHops + 1
            val expires = expiresAtOf(item.teaser.departureDate)
            if (expires <= now) {
                rejected++
                return@forEach
            }
            val current = existing[key]
            if (current == null) {
                if (perSource >= MAX_PER_SOURCE || total >= MAX_TOTAL) {
                    rejected++
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
                if ((sameSource || shorter) && (sameSource || perSource < MAX_PER_SOURCE)) {
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
        if (rejected > 0) CarpoolPullCounters.rejected.addAndGet(rejected.toLong())
        if (unknownKey > 0) CarpoolPullCounters.unknownOriginKey.addAndGet(unknownKey.toLong())
        return CarpoolPullApplyResult(
            applied = true,
            accepted = accepted,
            rejected = rejected,
            unknownOriginKey = unknownKey,
            removed = removed,
            withdrawn = withdrawn,
        )
    }

    private class ReceivedTombstone(
        val reportedBy: Uuid?,
    )

    /**
     * Known limit (documented, V1.9.314 review): withdrawals and the global pass-on tombstones are keyed by the pseudonymous teaser id only. A hostile
     * partner that learned one of OUR teaser ids can list (itself, id) as its own hint and thereby clear our global tombstone for that id; the pass-on
     * of that withdrawal to other partners may then be delayed until a complete snapshot is served. Nothing is displaced locally.
     */
    private fun clearTombstone(
        originHost: String,
        teaserId: String,
    ) {
        CarpoolReceivedTombstoneTable.deleteWhere {
            (CarpoolReceivedTombstoneTable.originHost eq originHost) and (CarpoolReceivedTombstoneTable.teaserId eq teaserId)
        }
        // The renewed consent: the global pass-on tombstone of this hint is moot.
        CarpoolFederationStore.forgetGlobalTombstone(teaserId)
    }

    /** A withdrawal reported by [reporter]: the copy goes, a local block is written, and (when it could have been handed on) the pass-on tombstone. */
    private fun removeAsWithdrawn(
        h: HintRow,
        reporter: Uuid,
        now: LocalDateTime,
    ) {
        CarpoolReceivedHintTable.deleteWhere { CarpoolReceivedHintTable.id eq h.id }
        val expires = CarpoolTeaserTombstonePolicy.expiresAt(createdAt = now, departure = h.departureDate)
        if (expires > now) {
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
                targets = listOf(CarpoolRelayedTarget(teaserId = it.teaserId, departureDate = it.departureDate)),
                partnerId = null,
                now = now,
                requireServedSince = it.receivedAt,
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
            .map { CarpoolRelayedTarget(teaserId = it.teaserId, departureDate = it.departureDate) }

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

    /** Idempotent: expired copies and expired blocks. @return the number of rows deleted. */
    fun deleteExpired(now: LocalDateTime): Int =
        CarpoolReceivedHintTable.deleteWhere { expiresAt lessEq now } + CarpoolReceivedTombstoneTable.deleteWhere { expiresAt lessEq now }

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
