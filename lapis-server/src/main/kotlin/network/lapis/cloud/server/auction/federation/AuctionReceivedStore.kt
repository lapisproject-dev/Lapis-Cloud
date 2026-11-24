package network.lapis.cloud.server.auction.federation

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.auction.federation.pull.AuctionTeaserValidator
import network.lapis.cloud.server.auction.federation.pull.ParsedAuctionFeed
import network.lapis.cloud.server.carpool.CarpoolOriginKeys
import network.lapis.cloud.server.db.generated.AuctionReceivedHintTable
import network.lapis.cloud.server.db.generated.AuctionReceivedTombstoneTable
import network.lapis.cloud.server.db.generated.PartnerServerTable
import network.lapis.cloud.server.partner.PartnerBlocklistPattern
import network.lapis.cloud.server.partner.PartnerHost
import network.lapis.cloud.server.partner.PartnerRow
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.shared.domain.AuctionCurrencies
import network.lapis.cloud.shared.domain.AuctionFederationLimits
import network.lapis.cloud.shared.domain.AuctionReceivedRejectReason
import network.lapis.cloud.shared.domain.AuctionTeaser
import network.lapis.cloud.shared.domain.AuctionTeaserPriceKind
import network.lapis.cloud.shared.domain.PartnerAuctionHintLimits
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

internal class AuctionPullApplyResult(
    /** `false` = the source was no longer a valid source in this transaction (pin changed, switched off, blocked, removed): nothing was stored. */
    val applied: Boolean,
    val accepted: Int = 0,
    val rejected: Int = 0,
    val unknownOriginKey: Int = 0,
    val removed: Int = 0,
    val withdrawn: Int = 0,
)

/** A stored hint that may be handed on: what the feed builder needs. [teaser] is rebuilt from the stored columns and verifies against [originSignature]. */
internal class RepublishableAuctionHint(
    val teaser: AuctionTeaser,
    val originHost: String,
    val storedHops: Int,
    val maxHops: Int,
    val via: List<String>,
    val originSignature: String,
)

/** What an ACTIVE member sees of a received hint: the DTO fields and nothing else (no signature, no path, no source). */
internal class MemberAuctionHint(
    val originHost: String,
    val teaserId: String,
    val title: String,
    val currency: String,
    val priceKind: AuctionTeaserPriceKind,
    val priceMinor: Long,
    val priceAsOf: kotlin.time.Instant,
    val buyNowMinor: Long?,
    val offersPossible: Boolean,
    val endsAt: kotlin.time.Instant,
)

internal class MemberAuctionHintPage(
    val items: List<MemberAuctionHint>,
    val hasMore: Boolean,
)

/** The outcome of an administrator hiding one hint: the source that delivered it (for the lock order and the audit entity). */
internal class HiddenResult(
    val sourcePartnerId: Uuid,
)

/**
 * In-memory counters since the start, for the administrator only. Numbers only: no host, no id, no content. Every restart begins at 0.
 */
internal object AuctionPullCounters {
    @Volatile
    var startedAt: LocalDateTime? = null
        private set

    val rejected = AtomicLong(0)
    val unknownOriginKey = AtomicLong(0)
    val accepted = AtomicLong(0)
    val expiredDeleted = AtomicLong(0)
    val budgetDeferred = AtomicLong(0)
    val relayTombstonesSkipped = AtomicLong(0)
    private val byReason = EnumMap<AuctionReceivedRejectReason, AtomicLong>(AuctionReceivedRejectReason::class.java)

    init {
        AuctionReceivedRejectReason.entries.forEach { byReason[it] = AtomicLong(0) }
    }

    /** Idempotent: the first call wins. */
    fun markStarted(now: LocalDateTime) {
        if (startedAt == null) startedAt = now
    }

    fun reject(
        reason: AuctionReceivedRejectReason,
        n: Int = 1,
    ) {
        if (n <= 0) return
        requireNotNull(byReason[reason]).addAndGet(n.toLong())
        rejected.addAndGet(n.toLong())
        if (reason == AuctionReceivedRejectReason.UNKNOWN_ORIGIN_KEY) unknownOriginKey.addAndGet(n.toLong())
    }

    fun rejectedByReason(): Map<AuctionReceivedRejectReason, Long> =
        AuctionReceivedRejectReason.entries.associateWith { requireNotNull(byReason[it]).get() }

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
 * V1.9.322 (step c3) -- the ONLY reader and writer of `auction_received_hint` and `auction_received_tombstone` (pinned by `AuctionFederationTripwireTest`).
 * A hint of another server is title, price, end and the pseudonymous id of its origin; nothing else is stored. Everything runs inside the caller's
 * transaction and never touches the network. It is also the ONLY server file (besides the origin projection) that rebuilds an `AuctionTeaser`: a
 * stored hint must be handed on EXACTLY as the origin signed it, so the teaser is rebuilt from the stored columns and the signature is checked
 * against it (pinned by a test).
 *
 * Locking, one rule: the `partner_server` row of the SOURCE first ([PartnerServerStore.lockPartner], so a switch-off or removal and an apply
 * serialise), then the received rows (ascending id), then tombstone inserts (`auction_teaser_tombstone` only through [AuctionFederationStore]).
 * Never `auction`, never `organization_settings`.
 *
 * Two tombstone kinds share ONE row per (origin host, teaser id): `WITHDRAWN` (a partner reported a withdrawal; the origin at hop 0 or the reporter may
 * bring the hint back) and `HIDDEN` (an administrator hid it; NOTHING brings it back, and a later withdrawal never weakens it).
 */
internal object AuctionReceivedStore {
    const val MAX_PER_SOURCE = 1000
    const val MAX_TOTAL = 5000

    /** A source always gets at least this many rows, however many partners share (the fair share is `MAX_TOTAL / partners`). */
    const val MIN_PER_SOURCE = 200

    /** Caps of the local blocks (`WITHDRAWN` rows set because a partner reported a withdrawal): per reporting source and in total. */
    const val MAX_BLOCKS_PER_SOURCE = 5000
    const val MAX_BLOCKS_TOTAL = 20_000

    /** Cap of the pass-on tombstones in `auction_teaser_tombstone`; our own withdrawals are never capped. */
    const val MAX_RELAY_TOMBSTONES_TOTAL = 20_000
    const val MAX_RELAY_TOMBSTONES_PER_REPORTER = 5000

    private const val KIND_WITHDRAWN = "WITHDRAWN"
    private const val KIND_HIDDEN = "HIDDEN"
    private const val MAX_HOST_LENGTH = 253
    private const val CHUNK = 400

    /** A copy of a source that has not been pulled successfully for this long is dropped (the pass-on of withdrawals must stay timely). */
    val STALE_AFTER = 24.hours

    /** A block outlives the end of the auction by this long (the signature of the origin has no expiry, so a dishonest relay could otherwise replay). */
    val BLOCK_GRACE = 2.days

    /** The fair share of ONE source when [sharingPartners] partners share: never below [MIN_PER_SOURCE], never above [MAX_PER_SOURCE]. */
    fun effectivePerSourceCap(sharingPartners: Int): Int =
        minOf(
            MAX_PER_SOURCE,
            maxOf(
                MIN_PER_SOURCE,
                MAX_TOTAL / sharingPartners.coerceAtLeast(1),
            ),
        )

    private class HintRow(
        val id: Uuid,
        val teaserId: String,
        val originHost: String,
        val hops: Int,
        val maxHops: Int,
        val via: List<String>,
        val sourcePartnerId: Uuid,
        val revision: Long,
        val title: String,
        val currency: String,
        val priceKind: AuctionTeaserPriceKind,
        val priceMinor: Long,
        val buyNowMinor: Long?,
        val offersPossible: Boolean,
        val endsAt: LocalDateTime,
        val priceAsOf: LocalDateTime,
        val signature: String,
        val receivedAt: LocalDateTime,
        val expiresAt: LocalDateTime,
    ) {
        val forwardable: Boolean get() = hops < maxHops

        fun toTeaser(): AuctionTeaser =
            AuctionTeaser(
                teaserId = teaserId,
                revision = revision,
                title = title,
                currency = currency,
                priceKind = priceKind,
                priceMinor = priceMinor,
                buyNowMinor = buyNowMinor,
                offersPossible = offersPossible,
                endsAt = endsAt.toInstant(TimeZone.UTC),
                priceAsOf = priceAsOf.toInstant(TimeZone.UTC),
            )
    }

    private fun ResultRow.toHint() =
        HintRow(
            id = this[AuctionReceivedHintTable.id],
            teaserId = this[AuctionReceivedHintTable.teaserId],
            originHost = this[AuctionReceivedHintTable.originHost],
            hops = this[AuctionReceivedHintTable.hops].toInt(),
            maxHops = this[AuctionReceivedHintTable.maxHops].toInt(),
            via = this[AuctionReceivedHintTable.viaHosts]?.split(',')?.filter { it.isNotEmpty() } ?: emptyList(),
            sourcePartnerId = this[AuctionReceivedHintTable.sourcePartnerId],
            revision = this[AuctionReceivedHintTable.revision],
            title = this[AuctionReceivedHintTable.title],
            currency = this[AuctionReceivedHintTable.currency],
            priceKind = AuctionTeaserPriceKind.valueOf(this[AuctionReceivedHintTable.priceKind]),
            priceMinor = this[AuctionReceivedHintTable.priceMinor],
            buyNowMinor = this[AuctionReceivedHintTable.buyNowMinor],
            offersPossible = this[AuctionReceivedHintTable.offersPossible],
            endsAt = this[AuctionReceivedHintTable.endsAt],
            priceAsOf = this[AuctionReceivedHintTable.priceAsOf],
            signature = this[AuctionReceivedHintTable.originSignature],
            receivedAt = this[AuctionReceivedHintTable.receivedAt],
            expiresAt = this[AuctionReceivedHintTable.expiresAt],
        )

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
        AuctionReceivedHintTable
            .selectAll()
            .where(where)
            .orderBy(AuctionReceivedHintTable.id to SortOrder.ASC)
            .forUpdate()
            .map { it.toHint() }

    private fun blockUntil(endsAt: LocalDateTime): LocalDateTime =
        (endsAt.toInstant(TimeZone.UTC) + BLOCK_GRACE).toLocalDateTime(TimeZone.UTC)

    private class ReceivedTombstone(
        val kind: String,
        val reportedBy: Uuid?,
        val expiresAt: LocalDateTime,
    )

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
    // apply: the result of ONE pull of ONE partner
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The content a signature covers (everything except the key (id) and the revision, which are compared first) plus the hop limit of the origin. */
    private fun sameContent(
        current: HintRow,
        item: AuctionTeaser,
        maxHops: Int,
    ): Boolean =
        current.title == item.title &&
            current.currency == item.currency &&
            current.priceKind == item.priceKind &&
            current.priceMinor == item.priceMinor &&
            current.buyNowMinor == item.buyNowMinor &&
            current.offersPossible == item.offersPossible &&
            current.endsAt.toInstant(TimeZone.UTC) == item.endsAt &&
            current.priceAsOf.toInstant(TimeZone.UTC) == item.priceAsOf &&
            current.maxHops == maxHops

    /**
     * Stores the result of a pull. [source] is the row as it was BEFORE the request (its pinned key and ETag): the request was signed for and
     * answered by exactly that key, so a pin that changed meanwhile voids the answer. Order inside the one transaction: the source row is locked and
     * checked, the withdrawals are applied, the items are verified and stored, the missing ones of a complete list are removed, the pull state is
     * written last.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount", "NestedBlockDepth")
    fun apply(
        source: PartnerRow,
        feed: ParsedAuctionFeed,
        etag: String?,
        next: LocalDateTime,
        now: LocalDateTime,
    ): AuctionPullApplyResult {
        val row = PartnerServerStore.lockPartner(source.id) ?: return AuctionPullApplyResult(applied = false)
        val settings = AuctionFederationStore.settings()
        if (!settings.enabled || !AuctionFederationStore.auctionModuleOn() || !row.active || !row.auctionSharing) {
            return AuctionPullApplyResult(applied = false)
        }
        if (row.signingPublicKeyPem == null ||
            row.signingKeyChangedAt != null ||
            row.signingKeyId != source.signingKeyId ||
            row.signingPublicKeyPem != source.signingPublicKeyPem
        ) {
            return AuctionPullApplyResult(applied = false)
        }
        if (PartnerBlocklistPattern.isBlocked(host = row.host, patterns = PartnerServerStore.blockPatterns())) {
            return AuctionPullApplyResult(applied = false)
        }
        val nowInstant = now.toInstant(TimeZone.UTC)

        // (2) withdrawals: only on copies that came from this source or whose origin IS this source.
        val hits = ArrayList<HintRow>()
        feed.withdrawnIds.distinct().chunked(CHUNK).forEach { chunk ->
            hits +=
                lockedRows(
                    (AuctionReceivedHintTable.teaserId inList chunk) and
                        (
                            (AuctionReceivedHintTable.sourcePartnerId eq row.id) or
                                (AuctionReceivedHintTable.originHost eq row.host)
                        ),
                )
        }
        var blocksTotal = if (hits.isEmpty()) 0 else countBlocksActive(now)
        if (hits.isNotEmpty()) {
            // The blocks this source has set stay bounded: the oldest ones of THIS source make room (never those of another source).
            val needed = countBlocksOfReporter(reporter = row.id, now = now) + hits.size - MAX_BLOCKS_PER_SOURCE
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
        val reasons = EnumMap<AuctionReceivedRejectReason, Int>(AuctionReceivedRejectReason::class.java)

        fun reject(reason: AuctionReceivedRejectReason) {
            rejected++
            reasons.merge(reason, 1, Int::plus)
        }
        // A total, deterministic order (end, origin, id): two applies that run in parallel take the same rows in the same order, and a full table is
        // filled with the nearest ends first.
        val ordered = feed.items.sortedWith(compareBy({ it.teaser.endsAt }, { it.originHost }, { it.teaser.teaserId }))
        val ids = ordered.map { it.teaser.teaserId }.distinct()
        val existing = HashMap<Pair<String, String>, HintRow>()
        val tombs = HashMap<Pair<String, String>, ReceivedTombstone>()
        ids.chunked(CHUNK).forEach { chunk ->
            lockedRows(AuctionReceivedHintTable.teaserId inList chunk).forEach { existing[it.originHost to it.teaserId] = it }
            AuctionReceivedTombstoneTable
                .selectAll()
                .where { (AuctionReceivedTombstoneTable.teaserId inList chunk) and (AuctionReceivedTombstoneTable.expiresAt greater now) }
                .forEach {
                    tombs[it[AuctionReceivedTombstoneTable.originHost] to it[AuctionReceivedTombstoneTable.teaserId]] =
                        ReceivedTombstone(
                            kind = it[AuctionReceivedTombstoneTable.kind],
                            reportedBy = it[AuctionReceivedTombstoneTable.reportedByPartnerId],
                            expiresAt = it[AuctionReceivedTombstoneTable.expiresAt],
                        )
                }
        }
        val sharingPartners =
            PartnerServerTable
                .selectAll()
                .where { (PartnerServerTable.active eq true) and (PartnerServerTable.auctionSharing eq true) }
                .count()
                .toInt()
        val cap = effectivePerSourceCap(sharingPartners)
        var perSource =
            AuctionReceivedHintTable
                .selectAll()
                .where { AuctionReceivedHintTable.sourcePartnerId eq row.id }
                .count()
                .toInt()
        var total = AuctionReceivedHintTable.selectAll().count().toInt()
        val keyMemo = HashMap<String, String?>()
        val listedKeys = HashSet<Pair<String, String>>()
        ordered.forEach { item ->
            val key = item.originHost to item.teaser.teaserId
            listedKeys += key
            val tomb = tombs[key]
            // A HIDDEN block is final: not even the origin brings the hint back. (Checked first; the signature is verified below only to decide whether
            // the block may be prolonged, so an unsigned item can never extend it.)
            if (tomb != null && tomb.kind != KIND_WITHDRAWN) {
                val pem = keyMemo.getOrPut(item.originHost) { CarpoolOriginKeys.resolve(item.originHost) }
                if (pem != null &&
                    AuctionOriginSignatures.verify(
                        teaser = item.teaser,
                        originHost = item.originHost,
                        maxHops = item.maxHops,
                        signatureB64 = item.originSignature,
                        publicKeyPem = pem,
                    )
                ) {
                    prolongBlock(
                        originHost = item.originHost,
                        teaserId = item.teaser.teaserId,
                        until = blockUntil(item.teaser.endsAt.toLocalDateTime(TimeZone.UTC)),
                    )
                }
                reject(AuctionReceivedRejectReason.BLOCKED)
                return@forEach
            }
            if (tomb != null) {
                // The source that reported the withdrawal, or the origin itself (hop 0), may bring it back (a renewed consent).
                val allowed = tomb.reportedBy == row.id || (item.wireHops == 0 && row.host == item.originHost)
                if (!allowed) {
                    reject(AuctionReceivedRejectReason.BLOCKED)
                    return@forEach
                }
            }
            // The hop limit may have changed since the reader ran.
            if (item.wireHops + 1 > settings.hopLimit) {
                reject(AuctionReceivedRejectReason.HOP_LIMIT)
                return@forEach
            }
            val pem = keyMemo.getOrPut(item.originHost) { CarpoolOriginKeys.resolve(item.originHost) }
            if (pem == null) {
                unknownKey++
                reject(AuctionReceivedRejectReason.UNKNOWN_ORIGIN_KEY)
                return@forEach
            }
            if (!AuctionOriginSignatures.verify(
                    teaser = item.teaser,
                    originHost = item.originHost,
                    maxHops = item.maxHops,
                    signatureB64 = item.originSignature,
                    publicKeyPem = pem,
                )
            ) {
                reject(AuctionReceivedRejectReason.SIGNATURE)
                return@forEach
            }
            // The content rules once more at the moment of storing (a hint may have run out between the reader and now).
            AuctionTeaserValidator.check(teaser = item.teaser, now = nowInstant)?.let {
                reject(it)
                return@forEach
            }
            val storedHops = item.wireHops + 1
            val endsAt = item.teaser.endsAt.toLocalDateTime(TimeZone.UTC)
            val priceAsOf = item.teaser.priceAsOf.toLocalDateTime(TimeZone.UTC)
            val current = existing[key]
            if (current == null) {
                // Reject, never displace: a full table or a full share turns THIS item away; no row of another source is ever deleted for it.
                if (perSource >= cap || total >= MAX_TOTAL) {
                    reject(AuctionReceivedRejectReason.CAPACITY)
                    return@forEach
                }
                val inserted =
                    AuctionReceivedHintTable.insertIgnore {
                        it[id] = derivedId(purpose = "auction-received/v1", originHost = item.originHost, teaserId = item.teaser.teaserId)
                        it[teaserId] = item.teaser.teaserId
                        it[originHost] = item.originHost
                        it[hops] = storedHops.toShort()
                        it[maxHops] = item.maxHops.toShort()
                        it[viaHosts] = item.via.joinToString(",").ifEmpty { null }
                        it[sourcePartnerId] = row.id
                        it[revision] = item.teaser.revision
                        it[title] = item.teaser.title
                        it[currency] = item.teaser.currency
                        it[priceKind] = item.teaser.priceKind.name
                        it[priceMinor] = item.teaser.priceMinor
                        it[buyNowMinor] = item.teaser.buyNowMinor
                        it[offersPossible] = item.teaser.offersPossible
                        it[AuctionReceivedHintTable.endsAt] = endsAt
                        it[AuctionReceivedHintTable.priceAsOf] = priceAsOf
                        it[originSignature] = item.originSignature
                        it[receivedAt] = now
                        it[lastSeenAt] = now
                        it[expiresAt] = endsAt
                    }
                if (inserted.insertedCount > 0) {
                    if (tomb != null) clearWithdrawnBlock(originHost = item.originHost, teaserId = item.teaser.teaserId)
                    perSource++
                    total++
                    accepted++
                }
            } else {
                val rev = item.teaser.revision
                if (rev < current.revision) {
                    reject(AuctionReceivedRejectReason.REPLAY)
                    return@forEach
                }
                if (rev == current.revision && !sameContent(current = current, item = item.teaser, maxHops = item.maxHops)) {
                    reject(AuctionReceivedRejectReason.TAMPER)
                    return@forEach
                }
                if (tomb != null) clearWithdrawnBlock(originHost = item.originHost, teaserId = item.teaser.teaserId)
                val sameSource = current.sourcePartnerId == row.id
                val shorter = storedHops < current.hops
                // The stored path must stay inside the (possibly new) hop limit of the origin.
                val pathForced = rev > current.revision && current.hops > item.maxHops
                val pathWanted = sameSource || shorter || pathForced
                val capBlocked = pathWanted && !sameSource && perSource >= cap
                if (capBlocked && (pathForced || rev == current.revision)) {
                    // Nothing can be stored: the path would not fit the cap of THIS source and the old path violates the new hop limit (or nothing is new).
                    reject(AuctionReceivedRejectReason.CAPACITY)
                    return@forEach
                }
                val adoptPath = pathWanted && !capBlocked
                // The cap of THIS source is full: the other source keeps the copy, but the signed content may still move forward.
                if (capBlocked) reject(AuctionReceivedRejectReason.CAPACITY)
                AuctionReceivedHintTable.update({ AuctionReceivedHintTable.id eq current.id }) {
                    it[lastSeenAt] = now
                    if (rev > current.revision) {
                        it[revision] = rev
                        it[title] = item.teaser.title
                        it[currency] = item.teaser.currency
                        it[priceKind] = item.teaser.priceKind.name
                        it[priceMinor] = item.teaser.priceMinor
                        it[buyNowMinor] = item.teaser.buyNowMinor
                        it[offersPossible] = item.teaser.offersPossible
                        it[AuctionReceivedHintTable.endsAt] = endsAt
                        it[AuctionReceivedHintTable.priceAsOf] = priceAsOf
                        it[originSignature] = item.originSignature
                        it[maxHops] = item.maxHops.toShort()
                        it[expiresAt] = endsAt
                    }
                    if (adoptPath) {
                        it[hops] = storedHops.toShort()
                        it[viaHosts] = item.via.joinToString(",").ifEmpty { null }
                        it[sourcePartnerId] = row.id
                    }
                }
                if (adoptPath && !sameSource) perSource++
                if (rev > current.revision || adoptPath) accepted++
            }
        }

        // (4) a complete list: what this source no longer lists (neither as item nor as withdrawn) is gone. No block: "missing" is not "withdrawn".
        var removed = 0
        if (feed.complete) {
            val withdrawnSet = feed.withdrawnIds.toSet()
            lockedRows(AuctionReceivedHintTable.sourcePartnerId eq row.id)
                .filter { (it.originHost to it.teaserId) !in listedKeys && it.teaserId !in withdrawnSet }
                .forEach {
                    AuctionReceivedHintTable.deleteWhere { AuctionReceivedHintTable.id eq it.id }
                    removed++
                }
        }

        // (5) the pull state, last.
        PartnerServerStore.recordAuctionPullSuccess(
            id = row.id,
            status = "OK",
            accepted = accepted,
            rejected = rejected,
            etag = etag,
            keepEtag = false,
            next = next,
            now = now,
        )
        feed.rejectedByReason.forEach { (reason, n) -> AuctionPullCounters.reject(reason = reason, n = n) }
        reasons.forEach { (reason, n) -> AuctionPullCounters.reject(reason = reason, n = n) }
        if (accepted > 0) AuctionPullCounters.accepted.addAndGet(accepted.toLong())
        return AuctionPullApplyResult(
            applied = true,
            accepted = accepted,
            rejected = rejected,
            unknownOriginKey = unknownKey,
            removed = removed,
            withdrawn = withdrawn,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
    // tombstones (blocks): ONE row per (origin host, teaser id); HIDDEN is never weakened
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Writes or strengthens the block of (origin, id). Rules (portable select-then-write, no `ON CONFLICT`): no row or an expired one gets the new
     * kind; a live `HIDDEN` row is never changed by a `WITHDRAWN` write; a `WITHDRAWN` row is upgraded by `HIDDEN`; the expiry only ever moves LATER.
     */
    private fun upsertBlock(
        originHost: String,
        teaserId: String,
        kind: String,
        reporter: Uuid?,
        expires: LocalDateTime,
        now: LocalDateTime,
    ) {
        if (expires <= now) return
        val existing =
            AuctionReceivedTombstoneTable
                .selectAll()
                .where { (AuctionReceivedTombstoneTable.originHost eq originHost) and (AuctionReceivedTombstoneTable.teaserId eq teaserId) }
                .forUpdate()
                .singleOrNull()
        if (existing == null) {
            AuctionReceivedTombstoneTable.insertIgnore {
                it[id] = derivedId(purpose = "auction-received-block/v1", originHost = originHost, teaserId = teaserId)
                it[AuctionReceivedTombstoneTable.teaserId] = teaserId
                it[AuctionReceivedTombstoneTable.originHost] = originHost
                it[AuctionReceivedTombstoneTable.kind] = kind
                it[reportedByPartnerId] = reporter
                it[createdAt] = now
                it[expiresAt] = expires
            }
            return
        }
        val oldKind = existing[AuctionReceivedTombstoneTable.kind]
        val oldExpires = existing[AuctionReceivedTombstoneTable.expiresAt]
        val live = oldExpires > now
        if (live && oldKind == KIND_HIDDEN && kind != KIND_HIDDEN) return
        val newKind = if (live && oldKind == KIND_HIDDEN) KIND_HIDDEN else kind
        val newExpires = if (live && oldExpires > expires) oldExpires else expires
        AuctionReceivedTombstoneTable.update({ AuctionReceivedTombstoneTable.id eq existing[AuctionReceivedTombstoneTable.id] }) {
            it[AuctionReceivedTombstoneTable.kind] = newKind
            it[reportedByPartnerId] = if (newKind == KIND_HIDDEN) null else reporter
            it[expiresAt] = newExpires
            if (!live) it[createdAt] = now
        }
    }

    /** A signed item showed up again for a HIDDEN block: the block lasts until the end of that auction plus the grace (never shorter). */
    private fun prolongBlock(
        originHost: String,
        teaserId: String,
        until: LocalDateTime,
    ) {
        AuctionReceivedTombstoneTable.update(
            {
                (AuctionReceivedTombstoneTable.originHost eq originHost) and (AuctionReceivedTombstoneTable.teaserId eq teaserId) and
                    (AuctionReceivedTombstoneTable.expiresAt lessEq until)
            },
        ) {
            it[expiresAt] = until
        }
    }

    /**
     * A WITHDRAWN hint came back (its origin or the reporter listed it again): the local block of exactly this (origin, id) and the pass-on
     * tombstone are moot. The WHERE names the kind: a `HIDDEN` row is never deleted here. Our OWN global tombstone is never touched.
     */
    private fun clearWithdrawnBlock(
        originHost: String,
        teaserId: String,
    ) {
        AuctionReceivedTombstoneTable.deleteWhere {
            (AuctionReceivedTombstoneTable.originHost eq originHost) and (AuctionReceivedTombstoneTable.teaserId eq teaserId) and
                (AuctionReceivedTombstoneTable.kind eq KIND_WITHDRAWN)
        }
        AuctionFederationStore.forgetRelayedTombstone(originHost = originHost, teaserId = teaserId)
    }

    /** A withdrawal reported by [reporter]: the copy goes, a local block is written, and (when it could have been handed on) the pass-on tombstone. */
    private fun removeAsWithdrawn(
        h: HintRow,
        reporter: Uuid,
        now: LocalDateTime,
        writeBlock: Boolean,
    ) {
        AuctionReceivedHintTable.deleteWhere { AuctionReceivedHintTable.id eq h.id }
        if (writeBlock) {
            upsertBlock(
                originHost = h.originHost,
                teaserId = h.teaserId,
                kind = KIND_WITHDRAWN,
                reporter = reporter,
                expires = blockUntil(h.endsAt),
                now = now,
            )
        }
        relayWithdrawal(rows = listOf(h), now = now)
    }

    /** Hints that were forwardable are reported as withdrawn to EVERY partner, but only if a partner fetched our feed since we got them. */
    private fun relayWithdrawal(
        rows: Collection<HintRow>,
        now: LocalDateTime,
    ) {
        rows.filter { it.forwardable }.forEach {
            AuctionFederationStore.withdrawRelayed(
                targets =
                    listOf(
                        AuctionFederationStore.RelayedTarget(teaserId = it.teaserId, endsAt = it.endsAt, originHost = it.originHost),
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
     * The stored hints that may be handed on to [partner]: not ended, the path still within `min(maxHops, ownHopLimit, 2)`, the partner is neither the
     * source nor the origin nor a relay on the path, the source is still an active, sharing, unblocked partner, the origin is not blocked, and NO live
     * block of any kind (a WITHDRAWN or HIDDEN hint is never handed on). Sorted by (hops, end, id).
     */
    fun republishableFor(
        partner: PartnerRow,
        ownHopLimit: Int,
        now: LocalDateTime,
        limit: Int,
    ): List<RepublishableAuctionHint> {
        val patterns = PartnerServerStore.blockPatterns()
        val sources = PartnerServerStore.listPartners().associateBy { it.id }
        val candidates =
            AuctionReceivedHintTable
                .selectAll()
                .where {
                    (AuctionReceivedHintTable.expiresAt greater now) and (AuctionReceivedHintTable.endsAt greater now) and
                        (AuctionReceivedHintTable.sourcePartnerId neq partner.id)
                }.orderBy(
                    AuctionReceivedHintTable.hops to SortOrder.ASC,
                    AuctionReceivedHintTable.endsAt to SortOrder.ASC,
                    AuctionReceivedHintTable.teaserId to SortOrder.ASC,
                    AuctionReceivedHintTable.originHost to SortOrder.ASC,
                ).map { it.toHint() }
                .filter { it.hops + 1 <= minOf(it.maxHops, ownHopLimit, AuctionFederationLimits.MAX_HOPS) }
                .filter { it.originHost != partner.host && partner.host !in it.via }
                .filter { h ->
                    val src = sources[h.sourcePartnerId]
                    src != null && src.mayPullAuctionFeed(patterns)
                }.filter { !PartnerBlocklistPattern.isBlocked(host = it.originHost, patterns = patterns) }
        if (candidates.isEmpty()) return emptyList()
        val blocked = liveBlockedKeys(teaserIds = candidates.map { it.teaserId }, now = now)
        return candidates
            .asSequence()
            .filter { (it.originHost to it.teaserId) !in blocked }
            .take(limit)
            .map {
                RepublishableAuctionHint(
                    teaser = it.toTeaser(),
                    originHost = it.originHost,
                    storedHops = it.hops,
                    maxHops = it.maxHops,
                    via = it.via,
                    originSignature = it.signature,
                )
            }.toList()
    }

    private fun liveBlockedKeys(
        teaserIds: Collection<String>,
        now: LocalDateTime,
    ): Set<Pair<String, String>> {
        val blocked = HashSet<Pair<String, String>>()
        teaserIds.distinct().chunked(CHUNK).forEach { chunk ->
            AuctionReceivedTombstoneTable
                .select(AuctionReceivedTombstoneTable.originHost, AuctionReceivedTombstoneTable.teaserId)
                .where { (AuctionReceivedTombstoneTable.teaserId inList chunk) and (AuctionReceivedTombstoneTable.expiresAt greater now) }
                .forEach { blocked += it[AuctionReceivedTombstoneTable.originHost] to it[AuctionReceivedTombstoneTable.teaserId] }
        }
        return blocked
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
    // the read path for members (the only one outside the feed)
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The hints an ACTIVE member may see, sorted by (end, teaser id, origin host), one page of [PartnerAuctionHintLimits.PAGE_SIZE]. Pure read in the
     * caller's transaction: no lock, no audit, no network. Never returns a signature, hops, path or source.
     *
     * Filters (all of them): the end lies in the future, the row is not expired, no live block of any kind, the currency is in the supported list
     * (second layer, the reader checked it already), the host is canonical, the origin is not blocked, the path fits our CURRENT hop limit, and the
     * source is still an active, sharing, not blocked partner without a pending key (defence in depth: the drop may lag behind).
     *
     * Known limit: offset paging; rows can shift between two requests, which can skip or repeat an entry.
     */
    fun listForMembers(
        now: LocalDateTime,
        hopLimit: Int,
        page: Int,
    ): MemberAuctionHintPage {
        val patterns = PartnerServerStore.blockPatterns()
        val sources = PartnerServerStore.listPartners().associateBy { it.id }
        val candidates =
            AuctionReceivedHintTable
                .selectAll()
                .where { (AuctionReceivedHintTable.endsAt greater now) and (AuctionReceivedHintTable.expiresAt greater now) }
                .orderBy(
                    AuctionReceivedHintTable.endsAt to SortOrder.ASC,
                    AuctionReceivedHintTable.teaserId to SortOrder.ASC,
                    AuctionReceivedHintTable.originHost to SortOrder.ASC,
                ).limit(MAX_TOTAL + 1)
                .map { it.toHint() }
        val blocked = liveBlockedKeys(teaserIds = candidates.map { it.teaserId }, now = now)
        val visible =
            candidates
                .asSequence()
                .filter { (it.originHost to it.teaserId) !in blocked }
                .filter { it.currency != AuctionCurrencies.LTR && it.currency in AuctionCurrencies.SUPPORTED }
                .filter { it.hops <= hopLimit }
                .filter { it.originHost.length <= MAX_HOST_LENGTH && PartnerHost.normalizeHostOnly(it.originHost) == it.originHost }
                .filter { !PartnerBlocklistPattern.isBlocked(host = it.originHost, patterns = patterns) }
                .filter {
                    val src = sources[it.sourcePartnerId]
                    src != null && src.mayPullAuctionFeed(patterns)
                }.drop(page * PartnerAuctionHintLimits.PAGE_SIZE)
                .take(PartnerAuctionHintLimits.PAGE_SIZE + 1)
                .toList()
        val hasMore = visible.size > PartnerAuctionHintLimits.PAGE_SIZE && page < PartnerAuctionHintLimits.MAX_PAGE
        return MemberAuctionHintPage(
            items =
                visible.take(PartnerAuctionHintLimits.PAGE_SIZE).map {
                    MemberAuctionHint(
                        originHost = it.originHost,
                        teaserId = it.teaserId,
                        title = it.title,
                        currency = it.currency,
                        priceKind = it.priceKind,
                        priceMinor = it.priceMinor,
                        priceAsOf = it.priceAsOf.toInstant(TimeZone.UTC),
                        buyNowMinor = it.buyNowMinor,
                        offersPossible = it.offersPossible,
                        endsAt = it.endsAt.toInstant(TimeZone.UTC),
                    )
                },
            hasMore = hasMore,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
    // the administrator hides one hint
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The source partner of a stored hint, read WITHOUT a lock (the caller then locks that partner first, as the lock order demands). */
    fun sourceOf(
        originHost: String,
        teaserId: String,
    ): Uuid? =
        AuctionReceivedHintTable
            .select(AuctionReceivedHintTable.sourcePartnerId)
            .where { (AuctionReceivedHintTable.originHost eq originHost) and (AuctionReceivedHintTable.teaserId eq teaserId) }
            .singleOrNull()
            ?.get(AuctionReceivedHintTable.sourcePartnerId)

    /**
     * An administrator hides ONE stored hint: the row goes and a HIDDEN block is written (an existing WITHDRAWN block is upgraded, an existing HIDDEN
     * block only ever moves later). No row = `null` (no block for a hint we never held). NO pass-on tombstone: the copy only disappears from our own
     * feed, nothing is reported to a partner or to the origin. The caller holds the lock of the source partner.
     */
    fun hide(
        originHost: String,
        teaserId: String,
        now: LocalDateTime,
    ): HiddenResult? {
        val h =
            lockedRows(
                (AuctionReceivedHintTable.originHost eq originHost) and (AuctionReceivedHintTable.teaserId eq teaserId),
            ).firstOrNull()
                ?: return null
        AuctionReceivedHintTable.deleteWhere { AuctionReceivedHintTable.id eq h.id }
        upsertBlock(
            originHost = h.originHost,
            teaserId = h.teaserId,
            kind = KIND_HIDDEN,
            reporter = null,
            expires = blockUntil(h.endsAt),
            now = now,
        )
        return HiddenResult(sourcePartnerId = h.sourcePartnerId)
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
    // switching off, removal, block, staleness, retention
    // ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Deletes the copies of [sourceId] after reporting the forwardable ones as withdrawn. */
    fun dropFromSource(
        sourceId: Uuid,
        now: LocalDateTime,
    ): Int = dropRows(rows = lockedRows(AuctionReceivedHintTable.sourcePartnerId eq sourceId), now = now)

    /** The instance switch went off: every copy goes, the forwardable ones are reported as withdrawn, every ETag is forgotten. */
    fun withdrawAllForSwitchOff(now: LocalDateTime): Int {
        // Partner rows first (the lock order of an apply), so a pull that is being applied finishes before the copies are taken away.
        PartnerServerStore.lockAllPartners()
        val n = dropRows(rows = lockedRows(Op.TRUE), now = now)
        PartnerServerStore.clearAuctionPullEtag(null)
        return n
    }

    /** A block pattern was added or a key is held: copies whose source or origin it matches go. */
    fun dropMatchingBlock(
        patterns: Collection<String>,
        now: LocalDateTime,
    ): Int {
        val sourceHosts = PartnerServerTable.selectAll().associate { it[PartnerServerTable.id] to it[PartnerServerTable.host] }
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
            AuctionReceivedHintTable.deleteWhere { AuctionReceivedHintTable.id inList chunk }
        }
        return rows.size
    }

    /**
     * Sources whose last successful pull is older than [STALE_AFTER] lose their copies; a source that never succeeded loses copies older than that.
     * Sources are visited in ascending id, so two sweeps never lock partner rows in opposite order. A parallel apply() of another source can still
     * meet a sweep on the received rows of a shared teaser id; PostgreSQL then aborts one side (no data loss, the next tick repeats it).
     */
    fun dropStale(now: LocalDateTime): Int {
        val cutoff = (now.toInstant(TimeZone.UTC) - STALE_AFTER).toLocalDateTime(TimeZone.UTC)
        val sources =
            AuctionReceivedHintTable
                .selectAll()
                .map { it[AuctionReceivedHintTable.sourcePartnerId] }
                .distinct()
                .sorted()
        var dropped = 0
        sources.forEach { sid ->
            val ok = PartnerServerStore.findPartner(sid)?.auctionPullOkAt
            if (ok == null || ok < cutoff) {
                // Re-check on the LOCKED row: a pull may have committed between the read above and the lock (then the copies are fresh).
                val locked = PartnerServerStore.lockPartner(sid)
                val lockedOk = locked?.auctionPullOkAt
                if (locked != null && lockedOk != null && lockedOk >= cutoff) return@forEach
                dropped +=
                    if (lockedOk == null) {
                        dropRows(
                            rows =
                                lockedRows(
                                    (AuctionReceivedHintTable.sourcePartnerId eq sid) and
                                        (AuctionReceivedHintTable.receivedAt lessEq cutoff),
                                ),
                            now = now,
                        )
                    } else {
                        dropFromSource(sourceId = sid, now = now)
                    }
            }
        }
        return dropped
    }

    /**
     * Idempotent: expired copies and expired blocks. At most [limit] rows of EACH table per call (ids ascending, so two callers never lock in opposite
     * order). @return the number of rows deleted.
     */
    fun deleteExpired(
        now: LocalDateTime,
        limit: Int = Int.MAX_VALUE,
    ): Int =
        deleteExpiredFrom(
            table = AuctionReceivedHintTable,
            idColumn = AuctionReceivedHintTable.id,
            expiresColumn = AuctionReceivedHintTable.expiresAt,
            now = now,
            limit = limit,
        ) +
            deleteExpiredFrom(
                table = AuctionReceivedTombstoneTable,
                idColumn = AuctionReceivedTombstoneTable.id,
                expiresColumn = AuctionReceivedTombstoneTable.expiresAt,
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
    fun countStoredRows(): Int = AuctionReceivedHintTable.selectAll().count().toInt()

    /** Live WITHDRAWN and HIDDEN blocks. */
    fun countBlocksActive(now: LocalDateTime): Int =
        AuctionReceivedTombstoneTable
            .selectAll()
            .where { AuctionReceivedTombstoneTable.expiresAt greater now }
            .count()
            .toInt()

    /** Live blocks an administrator set ("ausgeblendet"). A count only. */
    fun countHiddenActive(now: LocalDateTime): Int =
        AuctionReceivedTombstoneTable
            .selectAll()
            .where { (AuctionReceivedTombstoneTable.expiresAt greater now) and (AuctionReceivedTombstoneTable.kind eq KIND_HIDDEN) }
            .count()
            .toInt()

    private fun countBlocksOfReporter(
        reporter: Uuid,
        now: LocalDateTime,
    ): Int =
        AuctionReceivedTombstoneTable
            .selectAll()
            .where {
                (AuctionReceivedTombstoneTable.reportedByPartnerId eq reporter) and
                    (AuctionReceivedTombstoneTable.expiresAt greater now) and
                    (AuctionReceivedTombstoneTable.kind eq KIND_WITHDRAWN)
            }.count()
            .toInt()

    /** Deletes the [count] oldest live WITHDRAWN blocks of [reporter]. @return how many were deleted. */
    private fun deleteOldestBlocks(
        reporter: Uuid,
        count: Int,
        now: LocalDateTime,
    ): Int {
        val ids =
            AuctionReceivedTombstoneTable
                .select(AuctionReceivedTombstoneTable.id)
                .where {
                    (AuctionReceivedTombstoneTable.reportedByPartnerId eq reporter) and
                        (AuctionReceivedTombstoneTable.expiresAt greater now) and
                        (AuctionReceivedTombstoneTable.kind eq KIND_WITHDRAWN)
                }.orderBy(AuctionReceivedTombstoneTable.createdAt to SortOrder.ASC, AuctionReceivedTombstoneTable.id to SortOrder.ASC)
                .limit(count)
                .map { it[AuctionReceivedTombstoneTable.id] }
        ids.chunked(CHUNK).forEach { chunk -> AuctionReceivedTombstoneTable.deleteWhere { AuctionReceivedTombstoneTable.id inList chunk } }
        return ids.size
    }

    fun countBySource(): Map<Uuid, Int> =
        AuctionReceivedHintTable.selectAll().groupingBy { it[AuctionReceivedHintTable.sourcePartnerId] }.eachCount()

    fun countTotal(now: LocalDateTime): Int =
        AuctionReceivedHintTable
            .selectAll()
            .where { AuctionReceivedHintTable.expiresAt greater now }
            .count()
            .toInt()
}
