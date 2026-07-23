package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.economy.LtrBalanceProvider
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuctionBidDto
import network.lapis.cloud.shared.domain.AuctionBidResultDto
import network.lapis.cloud.shared.domain.AuctionComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.AuctionComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionSettingsDto
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.CreateAuctionListingInput
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IAuctionService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** "Aktuelle Annahme, vor Produktiveinsatz zu verifizieren" -- same disclaimer class as [PeerTransferService]'s own `MIN_TRANSFER_LTR`. Pure Spamschutz, no fachlich motivation. */
private val LISTING_FEE_LTR = BigDecimal("0.01")

/** "Aktuelle Annahme, vor Produktiveinsatz zu verifizieren" -- the minimum a new bid must clear the current second-highest bid by. */
private val MIN_INCREMENT_LTR = BigDecimal("0.01")

private const val MIN_DURATION_HOURS = 1
private const val MAX_DURATION_HOURS = 24 * 90

/** DoS guard for [listAuctions]/[listMyBids]/[listMyAuctions] -- same class of cap `PoliticianService.getTopPoliticians`'s own `limit` parameter enforces. */
private const val MAX_LIST_RESULTS = 200

private val ZERO_2DP: BigDecimal = BigDecimal.ZERO.setScale(2)

/**
 * V0.6.2 LTR-Auktion -- see [IAuctionService] KDoc and `21-auction.kuml.kts` file header for the
 * full fachlich model. [ltrBalanceProvider] defaults to [LedgerBackedLtrBalanceProvider], same
 * seam [CrowdfundingService]/[PeerTransferService]/[PoliticianService] use.
 *
 * ## Lock ordering (deadlock-free, see class KDoc of [PeerTransferService.lockBothAccounts] for
 * the two-account precedent this generalizes)
 *
 * EVERY mutating method here locks the [AuctionTable] row FOR UPDATE FIRST -- via
 * [lockAndCloseIfDue] -- before touching any [MemberTable] row. This serializes ALL operations
 * against the SAME auction (two concurrent bids, a bid racing a buyNow, a buyNow racing the
 * time-based lazy-close) through that single lock. Only after the auction row is locked does a
 * method additionally lock one member row ([placeBid], via [LtrBalanceProvider.lockForDebit]) or
 * two ([buyNow]/[settleAuctionLocked]'s sale path, via [lockBothAccounts], in ascending
 * [Uuid.toString] order -- the same order [PeerTransferService.lockBothAccounts] uses, so a
 * concurrent settlement of a DIFFERENT auction touching the SAME two members can never form a
 * cycle with this one (both always acquire their own auction lock first, then member locks in the
 * same global order).
 *
 * ## Reservation model
 *
 * See `21-auction.kuml.kts` file header "Reservation model" for the full rationale. In this
 * implementation: [placeBid] release-then-rebooks the CALLER's own hold if they were already
 * leading and are raising their own bid; releases the FORMER leader's hold (a different member)
 * and books a fresh hold for the caller if the caller newly takes the lead; and writes NOTHING to
 * the ledger at all if the caller's bid does not (yet) take the lead -- see [placeBid] KDoc for the
 * three-way case split this collapses to.
 *
 * ## Lazy-Close
 *
 * [lockAndCloseIfDue] is the single choke point every mutating (and [getAuction]) method funnels
 * through -- if the locked row is still OPEN but `endsAt` has already passed, it is settled in
 * place (via [settleAuctionLocked]) before the caller proceeds. [listAuctions] deliberately never
 * calls this -- see its own KDoc.
 */
class AuctionService(
    private val call: ApplicationCall,
    private val ltrBalanceProvider: LtrBalanceProvider = LedgerBackedLtrBalanceProvider(),
) : IAuctionService {
    override suspend fun createListing(input: CreateAuctionListingInput): AuctionDto {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        if (input.title.isBlank()) throw ConflictException("title must not be blank")
        if (input.durationHours < MIN_DURATION_HOURS || input.durationHours > MAX_DURATION_HOURS) {
            throw ConflictException("durationHours must be between $MIN_DURATION_HOURS and $MAX_DURATION_HOURS, got ${input.durationHours}")
        }
        val startingBid = validateAndNormalizeAmount(input.startingBidLtr, "startingBidLtr")
        val buyNowPrice = input.buyNowPriceLtr?.let { validateAndNormalizeAmount(it, "buyNowPriceLtr") }
        if (buyNowPrice != null && buyNowPrice <= startingBid) {
            throw ConflictException("buyNowPriceLtr $buyNowPrice must be strictly greater than startingBidLtr $startingBid")
        }
        val now = nowLocalDateTime()
        // Named "computedEndsAt", NOT "endsAt" -- deliberately avoids colliding with
        // AuctionTable.endsAt's own column property name; see writeLedger/upsertBid KDoc below and
        // PeerTransferService.executeTransfer's own KDoc for the shadowing footgun this sidesteps
        // (a same-named bare reference inside an insert{}/update{} body resolves against the
        // Table receiver's column property, not the outer local variable).
        val computedEndsAt =
            now.toInstant(TimeZone.currentSystemDefault()).plus(input.durationHours.hours).toLocalDateTime(TimeZone.currentSystemDefault())
        return transaction {
            requireActiveMembership(current.memberId)
            val maxValue = currentAuctionMaxValueLtr()
            if (maxValue != null) {
                if (startingBid > maxValue) {
                    throw ConflictException("startingBidLtr $startingBid exceeds the configured auctionMaxValueLtr $maxValue")
                }
                if (buyNowPrice != null && buyNowPrice > maxValue) {
                    throw ConflictException("buyNowPriceLtr $buyNowPrice exceeds the configured auctionMaxValueLtr $maxValue")
                }
            }
            ltrBalanceProvider.lockForDebit(current.memberId)
            val freeBalance = ltrBalanceProvider.freeBalance(current.memberId)
            if (LISTING_FEE_LTR > freeBalance) {
                throw ConflictException("Listing fee $LISTING_FEE_LTR LTR exceeds your free LTR balance $freeBalance")
            }
            val auctionId = Uuid.random()
            AuctionTable.insert {
                it[id] = auctionId
                it[title] = input.title
                it[description] = input.description
                it[startingBidLtr] = startingBid
                it[buyNowPriceLtr] = buyNowPrice
                it[status] = AuctionStatus.OPEN
                it[sellerMemberId] = current.memberId
                it[winnerMemberId] = null
                it[finalPriceLtr] = null
                it[listingFeeLtr] = LISTING_FEE_LTR
                it[createdAt] = now
                it[AuctionTable.endsAt] = computedEndsAt
                it[settledAt] = null
            }
            writeLedger(current.memberId, LtrLedgerEntryType.AUCTION_LISTING_FEE, LISTING_FEE_LTR.negate(), auctionId, now)
            val row = AuctionTable.selectAll().where { AuctionTable.id eq auctionId }.single()
            rowToDto(row, now, current.memberId)
        }
    }

    /**
     * Three-way case split (see class KDoc "Reservation model"):
     * 1. Caller was already the leader -> release their OLD hold, book their NEW (raised) hold.
     *    A leader can never lose the lead through their own raise (their new bid is, by the
     *    validity check below, strictly greater than their own previous one, which was already
     *    the maximum) -- so this branch always keeps them the leader afterward.
     * 2. Caller newly becomes the leader (was not leading before, is now) -> release the FORMER
     *    leader's hold (if any -- there might not have been one yet), book the caller's new hold.
     * 3. Neither of the above (the bid does not take the lead) -> no ledger write at all; the bid
     *    is still persisted (it legitimately sets the second price) but holds nothing.
     */
    override suspend fun placeBid(
        auctionId: String,
        maxBidLtr: BigDecimal,
    ): AuctionBidResultDto {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        val id = auctionId.toAuctionUuid()
        val bidAmount = validateAndNormalizeAmount(maxBidLtr, "maxBidLtr")
        val now = nowLocalDateTime()
        return transaction {
            requireActiveMembership(current.memberId)
            val row = lockAndCloseIfDue(id, now)
            if (row[AuctionTable.status] != AuctionStatus.OPEN) {
                throw ConflictException("Auction $id is not open for bidding (status=${row[AuctionTable.status]})")
            }
            if (row[AuctionTable.sellerMemberId] == current.memberId) {
                throw ConflictException("Seller cannot bid on their own auction")
            }
            val startingBid = row[AuctionTable.startingBidLtr]
            if (bidAmount < startingBid) {
                throw ConflictException("maxBidLtr $bidAmount is below the starting bid $startingBid")
            }
            val existingBids = currentBidViews(id)
            val myExisting = existingBids.firstOrNull { it.bidderMemberId == current.memberId }
            if (myExisting != null && bidAmount <= myExisting.maxBidLtr) {
                throw ConflictException(
                    "maxBidLtr $bidAmount must exceed your current maxBidLtr ${myExisting.maxBidLtr} -- you may only raise, never lower",
                )
            }
            val outcomeBefore = computeAuctionOutcome(startingBid, MIN_INCREMENT_LTR, existingBids)
            val wasLeader = outcomeBefore.leaderMemberId == current.memberId

            // Coverage check applies to EVERY bid, whether or not it will take the lead -- see
            // 21-auction.kuml.kts file header "Reservation model".
            ltrBalanceProvider.lockForDebit(current.memberId)
            val freeBalance = ltrBalanceProvider.freeBalance(current.memberId)
            val releasedIfLeader = if (wasLeader) outcomeBefore.leaderMaxBidLtr!! else ZERO_2DP
            val available = freeBalance + releasedIfLeader
            if (bidAmount > available) {
                throw ConflictException("maxBidLtr $bidAmount exceeds your available LTR balance $available")
            }

            upsertBid(id, current.memberId, bidAmount, now)
            val outcomeAfter = computeAuctionOutcome(startingBid, MIN_INCREMENT_LTR, currentBidViews(id))
            val isNowLeader = outcomeAfter.leaderMemberId == current.memberId

            if (wasLeader) {
                writeLedger(current.memberId, LtrLedgerEntryType.AUCTION_HOLD_RELEASE, outcomeBefore.leaderMaxBidLtr!!, id, now)
                writeLedger(current.memberId, LtrLedgerEntryType.AUCTION_HOLD, bidAmount.negate(), id, now)
            } else if (isNowLeader) {
                val formerLeader = outcomeBefore.leaderMemberId
                if (formerLeader != null) {
                    writeLedger(formerLeader, LtrLedgerEntryType.AUCTION_HOLD_RELEASE, outcomeBefore.leaderMaxBidLtr!!, id, now)
                }
                writeLedger(current.memberId, LtrLedgerEntryType.AUCTION_HOLD, bidAmount.negate(), id, now)
            }

            AuctionBidResultDto(
                auctionId = id.toString(),
                accepted = true,
                youAreLeader = isNowLeader,
                currentPriceLtr = outcomeAfter.currentPriceLtr!!,
                yourMaxBidLtr = bidAmount,
                endsAt = row[AuctionTable.endsAt],
            )
        }
    }

    override suspend fun buyNow(auctionId: String): AuctionDto {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        val id = auctionId.toAuctionUuid()
        val now = nowLocalDateTime()
        return transaction {
            requireActiveMembership(current.memberId)
            val row = lockAndCloseIfDue(id, now)
            if (row[AuctionTable.status] != AuctionStatus.OPEN) {
                throw ConflictException("Auction $id is not open (status=${row[AuctionTable.status]})")
            }
            val seller = row[AuctionTable.sellerMemberId]
            if (seller == current.memberId) throw ConflictException("Seller cannot buy their own auction")
            val buyNowPrice = row[AuctionTable.buyNowPriceLtr] ?: throw ConflictException("Auction $id has no Sofortkauf price")

            val outcome = computeAuctionOutcome(row[AuctionTable.startingBidLtr], MIN_INCREMENT_LTR, currentBidViews(id))
            if (outcome.currentPriceLtr != null && outcome.currentPriceLtr >= buyNowPrice) {
                throw ConflictException(
                    "Sofortkauf for auction $id is no longer available -- the current price already reached buyNowPriceLtr",
                )
            }

            val wasLeader = outcome.leaderMemberId == current.memberId
            lockBothAccounts(current.memberId, seller)
            val freeBalance = ltrBalanceProvider.freeBalance(current.memberId)
            val releasedIfLeader = if (wasLeader) outcome.leaderMaxBidLtr!! else ZERO_2DP
            val available = freeBalance + releasedIfLeader
            if (buyNowPrice > available) {
                throw ConflictException("buyNowPriceLtr $buyNowPrice exceeds your available LTR balance $available")
            }

            if (wasLeader) {
                writeLedger(current.memberId, LtrLedgerEntryType.AUCTION_HOLD_RELEASE, outcome.leaderMaxBidLtr!!, id, now)
            } else if (outcome.leaderMemberId != null) {
                writeLedger(outcome.leaderMemberId, LtrLedgerEntryType.AUCTION_HOLD_RELEASE, outcome.leaderMaxBidLtr!!, id, now)
            }
            writeLedger(current.memberId, LtrLedgerEntryType.AUCTION_SALE_OUT, buyNowPrice.negate(), id, now)
            writeLedger(seller, LtrLedgerEntryType.AUCTION_SALE_IN, buyNowPrice, id, now)
            AuctionTable.update({ AuctionTable.id eq id }) {
                it[status] = AuctionStatus.SETTLED
                it[winnerMemberId] = current.memberId
                it[finalPriceLtr] = buyNowPrice
                it[settledAt] = now
            }
            val fresh = AuctionTable.selectAll().where { AuctionTable.id eq id }.single()
            rowToDto(fresh, now, current.memberId)
        }
    }

    override suspend fun getAuction(id: String): AuctionDto {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        val auctionId = id.toAuctionUuid()
        val now = nowLocalDateTime()
        return transaction {
            val row = lockAndCloseIfDue(auctionId, now)
            rowToDto(row, now, current.memberId)
        }
    }

    /**
     * Deliberately does NOT call [lockAndCloseIfDue] for any row -- a bulk list must never trigger
     * a settlement side effect for potentially many auctions in one request (surprising and
     * DoS-shaped). [AuctionDto.effectiveStatus] shows what a subsequent [getAuction]/[placeBid]/
     * [buyNow]/[settleAuction] call on that SAME auction would lazily settle it to, without writing
     * anything here.
     */
    override suspend fun listAuctions(statusFilter: AuctionStatus?): List<AuctionDto> {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        val now = nowLocalDateTime()
        return transaction {
            val condition: Op<Boolean>? = statusFilter?.let { AuctionTable.status eq it }
            val query = if (condition != null) AuctionTable.selectAll().where { condition } else AuctionTable.selectAll()
            val rows = query.orderBy(AuctionTable.createdAt, SortOrder.DESC).limit(MAX_LIST_RESULTS).toList()
            rows.map { row -> rowToDto(row, now, current.memberId) }
        }
    }

    override suspend fun listMyBids(): List<AuctionBidDto> {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        return transaction {
            val bidRows =
                AuctionBidTable
                    .selectAll()
                    .where { AuctionBidTable.bidderMemberId eq current.memberId }
                    .orderBy(AuctionBidTable.createdAt, SortOrder.DESC)
                    .limit(MAX_LIST_RESULTS)
                    .toList()
            bidRows.map { bidRow ->
                val theAuctionId = bidRow[AuctionBidTable.auctionId]
                val auctionRow = AuctionTable.selectAll().where { AuctionTable.id eq theAuctionId }.single()
                val outcome =
                    computeAuctionOutcome(auctionRow[AuctionTable.startingBidLtr], MIN_INCREMENT_LTR, currentBidViews(theAuctionId))
                AuctionBidDto(
                    id = bidRow[AuctionBidTable.id].toString(),
                    auctionId = theAuctionId.toString(),
                    auctionTitle = auctionRow[AuctionTable.title],
                    maxBidLtr = bidRow[AuctionBidTable.maxBidLtr],
                    createdAt = bidRow[AuctionBidTable.createdAt],
                    isCurrentLeader = outcome.leaderMemberId == current.memberId,
                    auctionStatus = auctionRow[AuctionTable.status],
                )
            }
        }
    }

    override suspend fun listMyAuctions(): List<AuctionDto> {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        val now = nowLocalDateTime()
        return transaction {
            AuctionTable
                .selectAll()
                .where { AuctionTable.sellerMemberId eq current.memberId }
                .orderBy(AuctionTable.createdAt, SortOrder.DESC)
                .limit(MAX_LIST_RESULTS)
                .map { row -> rowToDto(row, now, current.memberId) }
        }
    }

    override suspend fun settleAuction(id: String): AuctionDto {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        val auctionId = id.toAuctionUuid()
        val now = nowLocalDateTime()
        return transaction {
            requireActiveMembership(current.memberId)
            val row =
                AuctionTable
                    .selectAll()
                    .where { AuctionTable.id eq auctionId }
                    .forUpdate()
                    .singleOrNull()
                    ?: throw NotFoundException("Auction $auctionId not found")
            if (row[AuctionTable.status] == AuctionStatus.OPEN) {
                if (now < row[AuctionTable.endsAt]) {
                    throw ConflictException("Auction $auctionId has not ended yet -- cannot be settled early")
                }
                settleAuctionLocked(row, now)
            }
            val fresh = AuctionTable.selectAll().where { AuctionTable.id eq auctionId }.single()
            rowToDto(fresh, now, current.memberId)
        }
    }

    override suspend fun getAuctionComplianceDisclaimer(): AuctionComplianceDisclaimerDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return AuctionComplianceDisclaimerDto(
            version = AuctionComplianceDisclaimer.VERSION,
            text = AuctionComplianceDisclaimer.TEXT,
            sha256 = AuctionComplianceDisclaimer.SHA256,
        )
    }

    override suspend fun enableAuction(input: AuctionComplianceAcknowledgmentInput): AuctionSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        if (!AuctionComplianceDisclaimer.matches(input.disclaimerVersion, input.disclaimerSha256)) {
            throw ConflictException(
                "disclaimerVersion/disclaimerSha256 do not match the current AuctionComplianceDisclaimer -- " +
                    "call getAuctionComplianceDisclaimer again and submit its CURRENT version/sha256 unmodified",
            )
        }
        val now = nowLocalDateTime()
        return transaction {
            AuctionComplianceAcknowledgmentTable.insert {
                it[id] = Uuid.random()
                it[acknowledgedByMemberId] = current.memberId
                it[acknowledgedAt] = now
                it[disclaimerVersion] = input.disclaimerVersion
                it[disclaimerSha256] = input.disclaimerSha256
            }
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[auctionEnabled] = true
            }
            loadAuctionSettingsDto()
        }
    }

    override suspend fun disableAuction(): AuctionSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[auctionEnabled] = false
            }
            loadAuctionSettingsDto()
        }
    }

    override suspend fun setAuctionMaxValueLtr(maxValueLtr: BigDecimal?): AuctionSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        val normalized = maxValueLtr?.let { validateAndNormalizeAmount(it, "auctionMaxValueLtr") }
        return transaction {
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[auctionMaxValueLtr] = normalized
            }
            loadAuctionSettingsDto()
        }
    }

    override suspend fun getAuctionSettings(): AuctionSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return transaction { loadAuctionSettingsDto() }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /** See class KDoc "The auctionEnabled gate" (in [IAuctionService] KDoc). */
    private fun requireAuctionEnabled() {
        val enabled =
            transaction {
                OrganizationSettingsTable
                    .selectAll()
                    .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                    .singleOrNull()
                    ?.get(OrganizationSettingsTable.auctionEnabled)
                    ?: false
            }
        if (!enabled) {
            throw ConflictException(
                "Auction is disabled (OrganizationSettings.auctionEnabled=false) -- an ADMIN must call " +
                    "enableAuction after confirming the current legal disclaimer first",
            )
        }
    }

    private fun currentAuctionMaxValueLtr(): BigDecimal? =
        OrganizationSettingsTable
            .selectAll()
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .singleOrNull()
            ?.get(OrganizationSettingsTable.auctionMaxValueLtr)

    private fun loadAuctionSettingsDto(): AuctionSettingsDto {
        val settingsRow =
            OrganizationSettingsTable
                .selectAll()
                .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
                .single()
        val lastAck =
            AuctionComplianceAcknowledgmentTable
                .selectAll()
                .orderBy(AuctionComplianceAcknowledgmentTable.acknowledgedAt, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
        return AuctionSettingsDto(
            auctionEnabled = settingsRow[OrganizationSettingsTable.auctionEnabled],
            auctionMaxValueLtr = settingsRow[OrganizationSettingsTable.auctionMaxValueLtr],
            lastAcknowledgedByDisplayName =
                lastAck?.let { memberDisplayName(it[AuctionComplianceAcknowledgmentTable.acknowledgedByMemberId]) },
            lastAcknowledgedAt = lastAck?.get(AuctionComplianceAcknowledgmentTable.acknowledgedAt),
            lastDisclaimerVersion = lastAck?.get(AuctionComplianceAcknowledgmentTable.disclaimerVersion),
        )
    }

    /**
     * Locks the [AuctionTable] row FOR UPDATE (see class KDoc "Lock ordering") and, if it is
     * OPEN and `endsAt` has already passed, settles it in place ([settleAuctionLocked]) before
     * returning the (possibly just-updated) row. MUST be the first lock any mutating method takes.
     */
    private fun lockAndCloseIfDue(
        auctionId: Uuid,
        now: LocalDateTime,
    ): ResultRow {
        val row =
            AuctionTable
                .selectAll()
                .where { AuctionTable.id eq auctionId }
                .forUpdate()
                .singleOrNull()
                ?: throw NotFoundException("Auction $auctionId not found")
        if (row[AuctionTable.status] != AuctionStatus.OPEN || now < row[AuctionTable.endsAt]) return row
        settleAuctionLocked(row, now)
        return AuctionTable.selectAll().where { AuctionTable.id eq auctionId }.single()
    }

    /**
     * Settles [row] (already locked FOR UPDATE by the caller) purely by time/outcome -- no
     * Sofortkauf involved (that is [buyNow]'s own, separate path). If [computeAuctionOutcome]
     * finds no leader at all, the auction closes with no sale ([AuctionStatus.CLOSED_NO_SALE], the
     * listing fee is never refunded -- see `21-auction.kuml.kts` file header). Otherwise the
     * leader wins at the live second price: their hold is released, [LtrLedgerEntryType.AUCTION_SALE_OUT]/
     * [LtrLedgerEntryType.AUCTION_SALE_IN] move the second price from winner to seller atomically.
     */
    private fun settleAuctionLocked(
        row: ResultRow,
        now: LocalDateTime,
    ) {
        val auctionId = row[AuctionTable.id]
        val seller = row[AuctionTable.sellerMemberId]
        val outcome = computeAuctionOutcome(row[AuctionTable.startingBidLtr], MIN_INCREMENT_LTR, currentBidViews(auctionId))
        if (outcome.leaderMemberId == null) {
            AuctionTable.update({ AuctionTable.id eq auctionId }) {
                it[status] = AuctionStatus.CLOSED_NO_SALE
                it[settledAt] = now
            }
            return
        }
        val winner = outcome.leaderMemberId
        val finalPrice = outcome.currentPriceLtr!!
        lockBothAccounts(winner, seller)
        writeLedger(winner, LtrLedgerEntryType.AUCTION_HOLD_RELEASE, outcome.leaderMaxBidLtr!!, auctionId, now)
        writeLedger(winner, LtrLedgerEntryType.AUCTION_SALE_OUT, finalPrice.negate(), auctionId, now)
        writeLedger(seller, LtrLedgerEntryType.AUCTION_SALE_IN, finalPrice, auctionId, now)
        AuctionTable.update({ AuctionTable.id eq auctionId }) {
            it[status] = AuctionStatus.SETTLED
            it[winnerMemberId] = winner
            it[finalPriceLtr] = finalPrice
            it[settledAt] = now
        }
    }

    /**
     * Canonical lock order: the two ids are compared lexicographically by [Uuid.toString], the
     * "smaller" one locked first -- identical idiom to [PeerTransferService.lockBothAccounts],
     * reused here for the winner/seller (or buyer/seller) pair at settlement.
     */
    private fun lockBothAccounts(
        a: Uuid,
        b: Uuid,
    ) {
        val (first, second) = if (a.toString() <= b.toString()) a to b else b to a
        ltrBalanceProvider.lockForDebit(first)
        ltrBalanceProvider.lockForDebit(second)
    }

    private fun currentBidViews(auctionId: Uuid): List<AuctionBidView> =
        AuctionBidTable
            .selectAll()
            .where { AuctionBidTable.auctionId eq auctionId }
            .map { row ->
                AuctionBidView(
                    bidId = row[AuctionBidTable.id],
                    bidderMemberId = row[AuctionBidTable.bidderMemberId],
                    maxBidLtr = row[AuctionBidTable.maxBidLtr],
                    createdAt = row[AuctionBidTable.createdAt],
                )
            }

    /**
     * Upsert-by-(auctionId, bidderMemberId) -- see `21-auction.kuml.kts` file header "auction_bid
     * is an upsert table". Parameter names deliberately avoid [AuctionBidTable]'s own column
     * property names (`auctionId`/`bidderMemberId`/`maxBidLtr`) -- same shadowing footgun
     * [writeLedger] KDoc documents, doubly important here since ALL THREE parameters would
     * otherwise collide.
     */
    private fun upsertBid(
        targetAuctionId: Uuid,
        callerMemberId: Uuid,
        newMaxBidLtr: BigDecimal,
        now: LocalDateTime,
    ) {
        val existingId =
            AuctionBidTable
                .selectAll()
                .where { (AuctionBidTable.auctionId eq targetAuctionId) and (AuctionBidTable.bidderMemberId eq callerMemberId) }
                .singleOrNull()
                ?.get(AuctionBidTable.id)
        if (existingId != null) {
            AuctionBidTable.update({ AuctionBidTable.id eq existingId }) {
                it[AuctionBidTable.maxBidLtr] = newMaxBidLtr
                it[AuctionBidTable.createdAt] = now
            }
        } else {
            AuctionBidTable.insert {
                it[id] = Uuid.random()
                it[AuctionBidTable.auctionId] = targetAuctionId
                it[AuctionBidTable.bidderMemberId] = callerMemberId
                it[AuctionBidTable.maxBidLtr] = newMaxBidLtr
                it[AuctionBidTable.createdAt] = now
            }
        }
    }

    /**
     * Parameter names deliberately avoid [LtrLedgerEntryTable]'s own column property names
     * (`memberId`/`amountLtr`) -- same shadowing footgun [PeerTransferService.executeTransfer]'s
     * own KDoc documents: inside `LtrLedgerEntryTable.insert { ... }` below, that Table is the
     * lambda's implicit receiver, so a same-named outer parameter would be shadowed by the
     * Table's own column property for any bare reference in that scope.
     */
    private fun writeLedger(
        beneficiaryMemberId: Uuid,
        type: LtrLedgerEntryType,
        signedAmountLtr: BigDecimal,
        auctionId: Uuid,
        now: LocalDateTime,
    ) {
        LtrLedgerEntryTable.insert {
            it[id] = Uuid.random()
            it[LtrLedgerEntryTable.memberId] = beneficiaryMemberId
            it[entryType] = type
            it[LtrLedgerEntryTable.amountLtr] = signedAmountLtr
            it[referenceType] = LtrLedgerReferenceType.AUCTION
            it[referenceId] = auctionId
            it[note] = null
            it[createdBy] = null
            it[createdAt] = now
        }
    }

    private fun validateAndNormalizeAmount(
        amount: BigDecimal,
        fieldName: String,
    ): BigDecimal {
        if (amount.scale() > 2) throw ConflictException("$fieldName must have at most 2 decimal places")
        val normalized = amount.setScale(2, RoundingMode.UNNECESSARY)
        if (normalized <= ZERO_2DP) throw ConflictException("$fieldName must be strictly positive")
        return normalized
    }

    private fun memberDisplayName(memberId: Uuid): String =
        MemberTable.selectAll().where { MemberTable.id eq memberId }.single()[MemberTable.displayName]

    /**
     * Maps one [AuctionTable] row to its [AuctionDto], deriving the live proxy-bid outcome from
     * [currentBidViews] every time -- correct whether [row] was just freshly settled (SETTLED/
     * CLOSED_NO_SALE, in which case the persisted [AuctionTable.finalPriceLtr]/
     * [AuctionTable.winnerMemberId] are shown instead) or is still (possibly overdue-but-not-yet-
     * lazily-closed) OPEN -- see [AuctionDto] KDoc "effectiveStatus".
     */
    private fun rowToDto(
        row: ResultRow,
        now: LocalDateTime,
        callerId: Uuid,
    ): AuctionDto {
        val theAuctionId = row[AuctionTable.id]
        val status = row[AuctionTable.status]
        val outcome = computeAuctionOutcome(row[AuctionTable.startingBidLtr], MIN_INCREMENT_LTR, currentBidViews(theAuctionId))
        val effectiveStatus =
            if (status == AuctionStatus.OPEN && now >= row[AuctionTable.endsAt]) {
                if (outcome.leaderMemberId != null) AuctionStatus.SETTLED else AuctionStatus.CLOSED_NO_SALE
            } else {
                status
            }
        val isSettled = status == AuctionStatus.SETTLED
        return AuctionDto(
            id = theAuctionId.toString(),
            title = row[AuctionTable.title],
            description = row[AuctionTable.description],
            sellerMemberId = row[AuctionTable.sellerMemberId].toString(),
            sellerDisplayName = memberDisplayName(row[AuctionTable.sellerMemberId]),
            startingBidLtr = row[AuctionTable.startingBidLtr],
            buyNowPriceLtr = row[AuctionTable.buyNowPriceLtr],
            status = status,
            effectiveStatus = effectiveStatus,
            currentPriceLtr = if (isSettled) row[AuctionTable.finalPriceLtr] else outcome.currentPriceLtr,
            currentLeaderDisplayName =
                if (isSettled) {
                    row[AuctionTable.winnerMemberId]?.let { memberDisplayName(it) }
                } else {
                    outcome.leaderMemberId?.let { memberDisplayName(it) }
                },
            leaderIsMe = if (isSettled) row[AuctionTable.winnerMemberId] == callerId else outcome.leaderMemberId == callerId,
            bidCount = outcome.bidCount,
            winnerMemberId = row[AuctionTable.winnerMemberId]?.toString(),
            winnerDisplayName = row[AuctionTable.winnerMemberId]?.let { memberDisplayName(it) },
            finalPriceLtr = row[AuctionTable.finalPriceLtr],
            listingFeeLtr = row[AuctionTable.listingFeeLtr],
            createdAt = row[AuctionTable.createdAt],
            endsAt = row[AuctionTable.endsAt],
            settledAt = row[AuctionTable.settledAt],
        )
    }

    private fun nowLocalDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

    private fun String.toAuctionUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
}
