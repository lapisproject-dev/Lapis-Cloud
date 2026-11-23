package network.lapis.cloud.server.rpc

import io.ktor.server.application.ApplicationCall
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.auction.AuctionPaymentDetailsRetention
import network.lapis.cloud.server.auction.AuctionPaymentDetailsStore
import network.lapis.cloud.server.auction.AuctionPaymentDetailsValidator
import network.lapis.cloud.server.auction.PaymentDetailsUnavailableException
import network.lapis.cloud.server.auction.SellerPaymentDetails
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AuctionBidTable
import network.lapis.cloud.server.db.generated.AuctionComplianceAcknowledgmentTable
import network.lapis.cloud.server.db.generated.AuctionFiatAcknowledgmentTable
import network.lapis.cloud.server.db.generated.AuctionTable
import network.lapis.cloud.server.db.generated.LtrLedgerEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.economy.LedgerBackedLtrBalanceProvider
import network.lapis.cloud.server.economy.LtrBalanceProvider
import network.lapis.cloud.server.gallery.AttachedImageStore
import network.lapis.cloud.server.gallery.GalleryCoverRef
import network.lapis.cloud.server.gallery.GalleryUrls
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.requireRole
import network.lapis.cloud.server.security.resolveCurrentMember
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttachedImageOwnerType
import network.lapis.cloud.shared.domain.AuctionBidDto
import network.lapis.cloud.shared.domain.AuctionBidResultDto
import network.lapis.cloud.shared.domain.AuctionComplianceAcknowledgmentInput
import network.lapis.cloud.shared.domain.AuctionComplianceDisclaimerDto
import network.lapis.cloud.shared.domain.AuctionCurrencies
import network.lapis.cloud.shared.domain.AuctionDto
import network.lapis.cloud.shared.domain.AuctionDurationRules
import network.lapis.cloud.shared.domain.AuctionFiatAuditSnapshot
import network.lapis.cloud.shared.domain.AuctionFiatAvailabilityDto
import network.lapis.cloud.shared.domain.AuctionFiatSwitchAuditSnapshot
import network.lapis.cloud.shared.domain.AuctionPaymentDetailsDto
import network.lapis.cloud.shared.domain.AuctionPaymentDetailsInput
import network.lapis.cloud.shared.domain.AuctionSettingsDto
import network.lapis.cloud.shared.domain.AuctionStatus
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CreateAuctionListingInput
import network.lapis.cloud.shared.domain.CreateFiatAuctionListingInput
import network.lapis.cloud.shared.domain.LtrLedgerEntryType
import network.lapis.cloud.shared.domain.LtrLedgerReferenceType
import network.lapis.cloud.shared.domain.UpdateAuctionTermsInput
import network.lapis.cloud.shared.rpc.AuctionAlreadyConfirmedException
import network.lapis.cloud.shared.rpc.AuctionAmountOutOfRangeException
import network.lapis.cloud.shared.rpc.AuctionCurrencyLockedException
import network.lapis.cloud.shared.rpc.AuctionCurrencyMismatchException
import network.lapis.cloud.shared.rpc.AuctionCurrencyUnsupportedException
import network.lapis.cloud.shared.rpc.AuctionFiatDisabledException
import network.lapis.cloud.shared.rpc.AuctionFiatDisclaimerRequiredException
import network.lapis.cloud.shared.rpc.AuctionFiatEncryptionUnavailableException
import network.lapis.cloud.shared.rpc.AuctionPaymentDetailsInvalidException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.IAuctionService
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

/** "Aktuelle Annahme, vor Produktiveinsatz zu verifizieren" -- same disclaimer class as [PeerTransferService]'s own `MIN_TRANSFER_LTR`. Pure Spamschutz, no fachlich motivation. */
private val LISTING_FEE_LTR = BigDecimal("0.01")

/** "Aktuelle Annahme, vor Produktiveinsatz zu verifizieren" -- the minimum a new bid must clear the current second-highest bid by. */
internal val MIN_INCREMENT_LTR = BigDecimal("0.01")

// V1.9.222: the duration bounds live in lapis-shared (AuctionDurationRules) so the create form uses the very same values.
private const val MIN_DURATION_HOURS = AuctionDurationRules.MIN_HOURS
private const val MAX_DURATION_HOURS = AuctionDurationRules.MAX_HOURS

/** DoS guard for [listAuctions]/[listMyBids]/[listMyAuctions] -- same class of cap `PoliticianService.getTopPoliticians`'s own `limit` parameter enforces. */
private const val MAX_LIST_RESULTS = 200

private val ZERO_2DP: BigDecimal = BigDecimal.ZERO.setScale(2)

/** `auction_fiat_acknowledgment.kind` values. */
private const val FIAT_ACK_OPERATOR = "OPERATOR"
private const val FIAT_ACK_SELLER = "SELLER"

/** The ONE answer of `getAuctionPaymentDetails`/the confirmations for a stranger, a missing row, an unawarded or an LTR auction. No id in it. */
private const val PAYMENT_DETAILS_NOT_FOUND = "Auction not found"

/** The statuses of an awarded Fiat auction (the payment details can be opened, the confirmations run). */
private val AWARDED_FIAT_STATUSES = setOf(AuctionStatus.AWAITING_PAYMENT, AuctionStatus.PAID, AuctionStatus.COMPLETED)

private fun ResultRow.isFiat(): Boolean = this[AuctionTable.currency] != AuctionCurrencies.LTR

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
 *
 * ## Fiat path (V1.9.320)
 *
 * An auction with `currency != LTR` (today: EUR) is settled directly between seller and winner. **No method of the Fiat path ever writes
 * `ltr_ledger_entry` or locks a member account for debit** ([writeLedger] refuses a non-LTR auction as a last line of defence, and
 * `AuctionFiatLedgerTripwireTest` scans the source). Lock order stays: auction row FOR UPDATE first, the audit chain row (via
 * [AuditLogRecorder.record]) strictly LAST. The seller's payment details live sealed in [AuctionPaymentDetailsStore] and appear in no [AuctionDto];
 * they are opened only by [getAuctionPaymentDetails] for the winner and the seller of an awarded auction.
 */
class AuctionService(
    private val call: ApplicationCall,
    private val ltrBalanceProvider: LtrBalanceProvider = LedgerBackedLtrBalanceProvider(),
    /** V1.9.320: `LAPIS_SECRET_ENCRYPTION_KEY`; without it no Fiat auction can be created (the payment details could not be sealed). */
    secretEncryptionKey: ByteArray? = null,
) : IAuctionService {
    private val paymentStore = AuctionPaymentDetailsStore(secretEncryptionKey)

    override suspend fun createListing(input: CreateAuctionListingInput): AuctionDto {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        if (input.title.isBlank()) throw ConflictException("title must not be blank")
        if (input.durationHours < MIN_DURATION_HOURS || input.durationHours > MAX_DURATION_HOURS) {
            throw ConflictException("durationHours must be between $MIN_DURATION_HOURS and $MAX_DURATION_HOURS, got ${input.durationHours}")
        }
        val startingBid = validateAndNormalizeAmount(amount = input.startingBidLtr, fieldName = "startingBidLtr")
        val buyNowPrice = input.buyNowPriceLtr?.let { validateAndNormalizeAmount(amount = it, fieldName = "buyNowPriceLtr") }
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
            now.toInstant(ServerClock.zone).plus(input.durationHours.hours).toLocalDateTime(ServerClock.zone)
        return transaction {
            requireActiveMembership(memberId = current.memberId)
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
                it[currency] = AuctionCurrencies.LTR
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
            writeLedger(
                beneficiaryMemberId = current.memberId,
                type = LtrLedgerEntryType.AUCTION_LISTING_FEE,
                signedAmountLtr = LISTING_FEE_LTR.negate(),
                auctionId = auctionId,
                now = now,
            )
            val row = AuctionTable.selectAll().where { AuctionTable.id eq auctionId }.single()
            rowToDto(row = row, now = now, callerId = current.memberId)
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
        val bidAmount = validateAndNormalizeAmount(amount = maxBidLtr, fieldName = "maxBidLtr")
        val now = nowLocalDateTime()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val row = lockAndCloseIfDue(auctionId = id, now = now)
            if (row[AuctionTable.status] != AuctionStatus.OPEN) {
                throw ConflictException("Auction $id is not open for bidding (status=${row[AuctionTable.status]})")
            }
            if (row.isFiat()) throw AuctionCurrencyMismatchException()
            if (row[AuctionTable.sellerMemberId] == current.memberId) {
                throw ConflictException("Seller cannot bid on their own auction")
            }
            val startingBid = row[AuctionTable.startingBidLtr]!!
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
            val outcomeBefore =
                computeAuctionOutcome(startingBidLtr = startingBid, minIncrementLtr = MIN_INCREMENT_LTR, bids = existingBids)
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

            upsertBid(targetAuctionId = id, callerMemberId = current.memberId, newMaxBidLtr = bidAmount, now = now)
            val outcomeAfter =
                computeAuctionOutcome(startingBidLtr = startingBid, minIncrementLtr = MIN_INCREMENT_LTR, bids = currentBidViews(id))
            val isNowLeader = outcomeAfter.leaderMemberId == current.memberId

            if (wasLeader) {
                writeLedger(
                    beneficiaryMemberId = current.memberId,
                    type = LtrLedgerEntryType.AUCTION_HOLD_RELEASE,
                    signedAmountLtr = outcomeBefore.leaderMaxBidLtr!!,
                    auctionId = id,
                    now = now,
                )
                writeLedger(
                    beneficiaryMemberId = current.memberId,
                    type = LtrLedgerEntryType.AUCTION_HOLD,
                    signedAmountLtr = bidAmount.negate(),
                    auctionId = id,
                    now = now,
                )
            } else if (isNowLeader) {
                val formerLeader = outcomeBefore.leaderMemberId
                if (formerLeader != null) {
                    writeLedger(
                        beneficiaryMemberId = formerLeader,
                        type = LtrLedgerEntryType.AUCTION_HOLD_RELEASE,
                        signedAmountLtr = outcomeBefore.leaderMaxBidLtr!!,
                        auctionId = id,
                        now = now,
                    )
                }
                writeLedger(
                    beneficiaryMemberId = current.memberId,
                    type = LtrLedgerEntryType.AUCTION_HOLD,
                    signedAmountLtr = bidAmount.negate(),
                    auctionId = id,
                    now = now,
                )
            }

            AuctionBidResultDto(
                auctionId = id.toString(),
                accepted = true,
                youAreLeader = isNowLeader,
                currentPriceLtr = outcomeAfter.currentPriceLtr!!,
                yourMaxBidLtr = bidAmount,
                endsAt = row[AuctionTable.endsAt],
                currency = AuctionCurrencies.LTR,
            )
        }
    }

    override suspend fun buyNow(auctionId: String): AuctionDto {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        val id = auctionId.toAuctionUuid()
        val now = nowLocalDateTime()
        return transaction {
            // ANTRAG membership-gate audit (2026-07-30): this call was the sole gap found in this
            // file -- createListing/placeBid/settleAuction all already call requireActiveMembership
            // as their first transaction statement, but buyNow itself did not, despite spending LTR
            // (AUCTION_SALE_OUT below) and settling ownership transfer. Same idiom as the sibling
            // methods in this file.
            requireActiveMembership(memberId = current.memberId)
            val row = lockAndCloseIfDue(auctionId = id, now = now)
            if (row[AuctionTable.status] != AuctionStatus.OPEN) {
                throw ConflictException("Auction $id is not open (status=${row[AuctionTable.status]})")
            }
            val seller = row[AuctionTable.sellerMemberId]
            if (seller == current.memberId) throw ConflictException("Seller cannot buy their own auction")
            if (row.isFiat()) return@transaction buyNowFiat(row = row, current = current, now = now)
            val buyNowPrice = row[AuctionTable.buyNowPriceLtr] ?: throw ConflictException("Auction $id has no Sofortkauf price")

            val outcome =
                computeAuctionOutcome(
                    startingBidLtr = row[AuctionTable.startingBidLtr]!!,
                    minIncrementLtr = MIN_INCREMENT_LTR,
                    bids = currentBidViews(id),
                )
            if (outcome.currentPriceLtr != null && outcome.currentPriceLtr >= buyNowPrice) {
                throw ConflictException(
                    "Sofortkauf for auction $id is no longer available -- the current price already reached buyNowPriceLtr",
                )
            }

            val wasLeader = outcome.leaderMemberId == current.memberId
            lockBothAccounts(a = current.memberId, b = seller)
            val freeBalance = ltrBalanceProvider.freeBalance(current.memberId)
            val releasedIfLeader = if (wasLeader) outcome.leaderMaxBidLtr!! else ZERO_2DP
            val available = freeBalance + releasedIfLeader
            if (buyNowPrice > available) {
                throw ConflictException("buyNowPriceLtr $buyNowPrice exceeds your available LTR balance $available")
            }

            if (wasLeader) {
                writeLedger(
                    beneficiaryMemberId = current.memberId,
                    type = LtrLedgerEntryType.AUCTION_HOLD_RELEASE,
                    signedAmountLtr = outcome.leaderMaxBidLtr!!,
                    auctionId = id,
                    now = now,
                )
            } else if (outcome.leaderMemberId != null) {
                writeLedger(
                    beneficiaryMemberId = outcome.leaderMemberId,
                    type = LtrLedgerEntryType.AUCTION_HOLD_RELEASE,
                    signedAmountLtr = outcome.leaderMaxBidLtr!!,
                    auctionId = id,
                    now = now,
                )
            }
            writeLedger(
                beneficiaryMemberId = current.memberId,
                type = LtrLedgerEntryType.AUCTION_SALE_OUT,
                signedAmountLtr = buyNowPrice.negate(),
                auctionId = id,
                now = now,
            )
            writeLedger(
                beneficiaryMemberId = seller,
                type = LtrLedgerEntryType.AUCTION_SALE_IN,
                signedAmountLtr = buyNowPrice,
                auctionId = id,
                now = now,
            )
            AuctionTable.update({ AuctionTable.id eq id }) {
                it[status] = AuctionStatus.SETTLED
                it[winnerMemberId] = current.memberId
                it[finalPriceLtr] = buyNowPrice
                it[settledAt] = now
            }
            val fresh = AuctionTable.selectAll().where { AuctionTable.id eq id }.single()
            rowToDto(row = fresh, now = now, callerId = current.memberId)
        }
    }

    override suspend fun getAuction(id: String): AuctionDto {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        val auctionId = id.toAuctionUuid()
        val now = nowLocalDateTime()
        return transaction {
            val row = lockAndCloseIfDue(auctionId = auctionId, now = now)
            rowToDto(row = row, now = now, callerId = current.memberId)
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
            val condition: Op<Boolean>? = statusFilter?.let { listFilterCondition(filter = it, callerId = current.memberId) }
            val query = if (condition != null) AuctionTable.selectAll().where { condition } else AuctionTable.selectAll()
            val rows = query.orderBy(AuctionTable.createdAt, SortOrder.DESC).limit(MAX_LIST_RESULTS).toList()
            val covers =
                AttachedImageStore.coverRefsFor(
                    type = AttachedImageOwnerType.AUCTION_LISTING,
                    ownerIds = rows.map { it[AuctionTable.id] },
                )
            rows.map { row -> rowToDto(row = row, now = now, callerId = current.memberId, covers = covers) }
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
                if (auctionRow.isFiat()) {
                    val fiatOutcome =
                        computeFiatAuctionOutcome(
                            startingMinor = auctionRow[AuctionTable.startingPriceMinor]!!,
                            minIncrementMinor = AuctionCurrencies.MIN_INCREMENT_MINOR,
                            bids = currentFiatBidViews(theAuctionId),
                        )
                    return@map AuctionBidDto(
                        id = bidRow[AuctionBidTable.id].toString(),
                        auctionId = theAuctionId.toString(),
                        auctionTitle = auctionRow[AuctionTable.title],
                        maxBidLtr = null,
                        createdAt = bidRow[AuctionBidTable.createdAt],
                        isCurrentLeader = fiatOutcome.leaderMemberId == current.memberId,
                        auctionStatus =
                            auctionRow[AuctionTable.status].let { st ->
                                val winnerId = auctionRow[AuctionTable.winnerMemberId]
                                val isParty = auctionRow[AuctionTable.sellerMemberId] == current.memberId || winnerId == current.memberId
                                if (isParty) st else neutralForStranger(st)
                            },
                        maxBidMinor = bidRow[AuctionBidTable.maxBidMinor],
                        currency = auctionRow[AuctionTable.currency],
                    )
                }
                val outcome =
                    computeAuctionOutcome(
                        startingBidLtr = auctionRow[AuctionTable.startingBidLtr]!!,
                        minIncrementLtr = MIN_INCREMENT_LTR,
                        bids = currentBidViews(theAuctionId),
                    )
                AuctionBidDto(
                    id = bidRow[AuctionBidTable.id].toString(),
                    auctionId = theAuctionId.toString(),
                    auctionTitle = auctionRow[AuctionTable.title],
                    maxBidLtr = bidRow[AuctionBidTable.maxBidLtr],
                    createdAt = bidRow[AuctionBidTable.createdAt],
                    isCurrentLeader = outcome.leaderMemberId == current.memberId,
                    auctionStatus = auctionRow[AuctionTable.status],
                    currency = AuctionCurrencies.LTR,
                )
            }
        }
    }

    override suspend fun listMyAuctions(): List<AuctionDto> {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        val now = nowLocalDateTime()
        return transaction {
            val rows =
                AuctionTable
                    .selectAll()
                    .where { AuctionTable.sellerMemberId eq current.memberId }
                    .orderBy(AuctionTable.createdAt, SortOrder.DESC)
                    .limit(MAX_LIST_RESULTS)
                    .toList()
            val covers =
                AttachedImageStore.coverRefsFor(
                    type = AttachedImageOwnerType.AUCTION_LISTING,
                    ownerIds = rows.map { it[AuctionTable.id] },
                )
            rows.map { row -> rowToDto(row = row, now = now, callerId = current.memberId, covers = covers) }
        }
    }

    override suspend fun settleAuction(id: String): AuctionDto {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        val auctionId = id.toAuctionUuid()
        val now = nowLocalDateTime()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
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
                settleAuctionLocked(row = row, now = now)
            }
            val fresh = AuctionTable.selectAll().where { AuctionTable.id eq auctionId }.single()
            rowToDto(row = fresh, now = now, callerId = current.memberId)
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
        if (!AuctionComplianceDisclaimer.matches(version = input.disclaimerVersion, sha256 = input.disclaimerSha256)) {
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
        val normalized = maxValueLtr?.let { validateAndNormalizeAmount(amount = it, fieldName = "auctionMaxValueLtr") }
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

    // ── V1.9.320 Auktionen in Euro mit Direktzahlung (Fiat W1) ─────────────────────────────────────────
    // No method below writes the LTR ledger or locks a member account for debit (see class KDoc "Fiat path").

    override suspend fun getAuctionFiatAvailability(): AuctionFiatAvailabilityDto {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        return transaction { fiatAvailability(current.memberId) }
    }

    override suspend fun acknowledgeFiatSellerDisclaimer(input: AuctionComplianceAcknowledgmentInput): AuctionFiatAvailabilityDto {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        if (!AuctionFiatSellerDisclaimer.matches(version = input.disclaimerVersion, sha256 = input.disclaimerSha256)) {
            throw AuctionFiatDisclaimerRequiredException()
        }
        val now = nowLocalDateTime()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            if (!fiatEnabledNow()) throw AuctionFiatDisabledException()
            if (!hasSellerAck(current.memberId)) {
                AuctionFiatAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[kind] = FIAT_ACK_SELLER
                    it[acknowledgedByMemberId] = current.memberId
                    it[acknowledgedAt] = now
                    it[disclaimerVersion] = AuctionFiatSellerDisclaimer.VERSION
                    it[disclaimerSha256] = AuctionFiatSellerDisclaimer.SHA256
                }
            }
            fiatAvailability(current.memberId)
        }
    }

    override suspend fun createFiatListing(input: CreateFiatAuctionListingInput): AuctionDto {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        if (input.title.isBlank()) throw ConflictException("title must not be blank")
        if (input.durationHours < MIN_DURATION_HOURS || input.durationHours > MAX_DURATION_HOURS) {
            throw ConflictException("durationHours must be between $MIN_DURATION_HOURS and $MAX_DURATION_HOURS")
        }
        requireSupportedFiatCurrency(input.currency)
        validateFiatPrices(startingMinor = input.startingPriceMinor, buyNowMinor = input.buyNowPriceMinor)
        val details = AuctionPaymentDetailsValidator.validate(input.paymentDetails)
        if (!paymentStore.available) throw AuctionFiatEncryptionUnavailableException()
        val auctionId = Uuid.random()
        // Seal BEFORE the transaction: pure cryptography with a random IV, harmless on a transaction retry.
        val sealed = paymentStore.seal(auctionId = auctionId, details = details)
        val now = nowLocalDateTime()
        val computedEndsAt = now.toInstant(ServerClock.zone).plus(input.durationHours.hours).toLocalDateTime(ServerClock.zone)
        return transaction {
            // forUpdate serialises the seller's parallel creations, so the open-count limit cannot be overrun by a race.
            requireActiveMembership(memberId = current.memberId, forUpdate = true)
            if (!fiatEnabledNow()) throw AuctionFiatDisabledException()
            requireSellerAck(
                memberId = current.memberId,
                version = input.sellerDisclaimerVersion,
                sha256 = input.sellerDisclaimerSha256,
            )
            requireOpenFiatCapacity(current.memberId)
            AuctionTable.insert {
                it[id] = auctionId
                it[title] = input.title
                it[description] = input.description
                it[currency] = input.currency
                it[startingBidLtr] = null
                it[buyNowPriceLtr] = null
                it[startingPriceMinor] = input.startingPriceMinor
                it[buyNowPriceMinor] = input.buyNowPriceMinor
                it[status] = AuctionStatus.OPEN
                it[sellerMemberId] = current.memberId
                it[winnerMemberId] = null
                it[finalPriceLtr] = null
                it[finalPriceMinor] = null
                it[listingFeeLtr] = ZERO_2DP
                it[sellerDisclaimerVersion] = AuctionFiatSellerDisclaimer.VERSION
                it[createdAt] = now
                it[AuctionTable.endsAt] = computedEndsAt
                it[settledAt] = null
            }
            paymentStore.upsert(auctionId = auctionId, sealed = sealed, details = details, now = now)
            auditFiat(actor = current, auctionId = auctionId, event = "AUCTION_FIAT_CREATED", action = AuditAction.CREATE, now = now)
            val row = AuctionTable.selectAll().where { AuctionTable.id eq auctionId }.single()
            rowToDto(row = row, now = now, callerId = current.memberId)
        }
    }

    override suspend fun placeFiatBid(
        auctionId: String,
        maxBidMinor: Long,
    ): AuctionBidResultDto {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        val id = auctionId.toAuctionUuid()
        if (maxBidMinor < AuctionCurrencies.MIN_MINOR || maxBidMinor > AuctionCurrencies.MAX_MINOR) throw AuctionAmountOutOfRangeException()
        val now = nowLocalDateTime()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val row = lockAndCloseIfDue(auctionId = id, now = now)
            if (!row.isFiat()) throw AuctionCurrencyMismatchException()
            if (row[AuctionTable.status] != AuctionStatus.OPEN) throw ConflictException("Auction $id is not open for bidding")
            if (row[AuctionTable.sellerMemberId] == current.memberId) throw ConflictException("Seller cannot bid on their own auction")
            val startingMinor = row[AuctionTable.startingPriceMinor]!!
            if (maxBidMinor < startingMinor) throw ConflictException("The bid is below the starting price")
            val myExisting = currentFiatBidViews(id).firstOrNull { it.bidderMemberId == current.memberId }
            if (myExisting != null && maxBidMinor <= myExisting.maxBidMinor) {
                throw ConflictException("The bid must exceed your current maximum -- you may only raise, never lower")
            }
            upsertFiatBid(targetAuctionId = id, callerMemberId = current.memberId, newMaxBidMinor = maxBidMinor, now = now)
            val outcomeAfter =
                computeFiatAuctionOutcome(
                    startingMinor = startingMinor,
                    minIncrementMinor = AuctionCurrencies.MIN_INCREMENT_MINOR,
                    bids = currentFiatBidViews(id),
                )
            AuctionBidResultDto(
                auctionId = id.toString(),
                accepted = true,
                youAreLeader = outcomeAfter.leaderMemberId == current.memberId,
                currentPriceLtr = null,
                yourMaxBidLtr = null,
                endsAt = row[AuctionTable.endsAt],
                currentPriceMinor = outcomeAfter.currentPriceMinor,
                yourMaxBidMinor = maxBidMinor,
                currency = row[AuctionTable.currency],
            )
        }
    }

    override suspend fun updateAuctionPaymentDetails(
        auctionId: String,
        input: AuctionPaymentDetailsInput,
    ): AuctionDto {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        val id = auctionId.toAuctionUuid()
        val details = AuctionPaymentDetailsValidator.validate(input)
        if (!paymentStore.available) throw AuctionFiatEncryptionUnavailableException()
        val sealed = paymentStore.seal(auctionId = id, details = details)
        val now = nowLocalDateTime()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val row = lockAndCloseIfDue(auctionId = id, now = now)
            if (!row.isFiat()) throw AuctionCurrencyMismatchException()
            if (row[AuctionTable.sellerMemberId] != current.memberId) throw ForbiddenException()
            if (row[AuctionTable.status] != AuctionStatus.OPEN) {
                throw ConflictException("The payment details can no longer be changed -- the auction has been awarded or closed")
            }
            paymentStore.upsert(auctionId = id, sealed = sealed, details = details, now = now)
            auditFiat(
                actor = current,
                auctionId = id,
                event = "AUCTION_FIAT_PAYMENT_DETAILS_UPDATED",
                action = AuditAction.UPDATE,
                now = now,
            )
            rowToDto(row = AuctionTable.selectAll().where { AuctionTable.id eq id }.single(), now = now, callerId = current.memberId)
        }
    }

    override suspend fun updateAuctionTerms(
        auctionId: String,
        input: UpdateAuctionTermsInput,
    ): AuctionDto {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        val id = auctionId.toAuctionUuid()
        val targetFiat = AuctionCurrencies.isFiat(input.currency)
        // Pure validation of the target shape, before any lock.
        var fiatDetails: SellerPaymentDetails? = null
        var sealed: String? = null
        var ltrStart: BigDecimal? = null
        var ltrBuyNow: BigDecimal? = null
        if (targetFiat) {
            requireSupportedFiatCurrency(input.currency)
            validateFiatPrices(
                startingMinor = input.startingPriceMinor ?: throw AuctionAmountOutOfRangeException(),
                buyNowMinor = input.buyNowPriceMinor,
            )
            input.paymentDetails?.let {
                fiatDetails = AuctionPaymentDetailsValidator.validate(it)
                if (!paymentStore.available) throw AuctionFiatEncryptionUnavailableException()
                sealed = paymentStore.seal(auctionId = id, details = fiatDetails!!)
            }
        } else {
            if (input.currency != AuctionCurrencies.LTR) throw AuctionCurrencyUnsupportedException()
            ltrStart =
                validateAndNormalizeAmount(
                    amount = input.startingBidLtr ?: throw ConflictException("startingBidLtr is required"),
                    fieldName = "startingBidLtr",
                )
            ltrBuyNow = input.buyNowPriceLtr?.let { validateAndNormalizeAmount(amount = it, fieldName = "buyNowPriceLtr") }
            if (ltrBuyNow != null && ltrBuyNow <= ltrStart) {
                throw ConflictException("buyNowPriceLtr must be strictly greater than startingBidLtr")
            }
        }
        val now = nowLocalDateTime()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val row = lockAndCloseIfDue(auctionId = id, now = now)
            if (row[AuctionTable.sellerMemberId] != current.memberId) throw ForbiddenException()
            if (row[AuctionTable.status] != AuctionStatus.OPEN) throw ConflictException("Only an open auction can be changed")
            // Once a bid exists the currency and the prices are frozen.
            if (AuctionBidTable.selectAll().where { AuctionBidTable.auctionId eq id }.any()) throw AuctionCurrencyLockedException()
            val wasFiat = row.isFiat()
            if (targetFiat) {
                if (!wasFiat) {
                    if (!fiatEnabledNow()) throw AuctionFiatDisabledException()
                    // The same spam barrier as createFiatListing: lock the member row, then count (this auction is still LTR and not counted).
                    requireActiveMembership(memberId = current.memberId, forUpdate = true)
                    requireOpenFiatCapacity(current.memberId)
                    requireSellerAck(
                        memberId = current.memberId,
                        version = input.sellerDisclaimerVersion.orEmpty(),
                        sha256 = input.sellerDisclaimerSha256.orEmpty(),
                    )
                    val details = fiatDetails ?: throw AuctionPaymentDetailsInvalidException()
                    val sealedBlob = sealed ?: throw AuctionFiatEncryptionUnavailableException()
                    AuctionTable.update({ AuctionTable.id eq id }) {
                        it[currency] = input.currency
                        it[startingBidLtr] = null
                        it[buyNowPriceLtr] = null
                        it[startingPriceMinor] = input.startingPriceMinor
                        it[buyNowPriceMinor] = input.buyNowPriceMinor
                        it[sellerDisclaimerVersion] = AuctionFiatSellerDisclaimer.VERSION
                    }
                    paymentStore.upsert(auctionId = id, sealed = sealedBlob, details = details, now = now)
                } else {
                    AuctionTable.update({ AuctionTable.id eq id }) {
                        it[currency] = input.currency
                        it[startingPriceMinor] = input.startingPriceMinor
                        it[buyNowPriceMinor] = input.buyNowPriceMinor
                    }
                    val sealedBlob = sealed
                    val details = fiatDetails
                    if (sealedBlob != null &&
                        details != null
                    ) {
                        paymentStore.upsert(auctionId = id, sealed = sealedBlob, details = details, now = now)
                    }
                }
            } else {
                val maxValue = currentAuctionMaxValueLtr()
                if (maxValue != null && (ltrStart!! > maxValue || (ltrBuyNow != null && ltrBuyNow > maxValue))) {
                    throw ConflictException("The price exceeds the configured auctionMaxValueLtr $maxValue")
                }
                if (wasFiat) {
                    // Fiat -> LTR: the listing fee of an LTR auction is charged now (a Fiat listing paid none).
                    // The fee is charged ONCE per auction: an earlier LTR phase (LTR -> EUR -> LTR) already paid it and the ledger row stays (no refund).
                    val feeAlreadyCharged =
                        LtrLedgerEntryTable
                            .selectAll()
                            .where {
                                (LtrLedgerEntryTable.referenceId eq id) and
                                    (LtrLedgerEntryTable.referenceType eq LtrLedgerReferenceType.AUCTION) and
                                    (LtrLedgerEntryTable.entryType eq LtrLedgerEntryType.AUCTION_LISTING_FEE)
                            }.any()
                    if (!feeAlreadyCharged) {
                        ltrBalanceProvider.lockForDebit(current.memberId)
                        val freeBalance = ltrBalanceProvider.freeBalance(current.memberId)
                        if (LISTING_FEE_LTR > freeBalance) {
                            throw ConflictException("Listing fee $LISTING_FEE_LTR LTR exceeds your free LTR balance $freeBalance")
                        }
                    }
                    AuctionTable.update({ AuctionTable.id eq id }) {
                        it[currency] = AuctionCurrencies.LTR
                        it[startingBidLtr] = ltrStart
                        it[buyNowPriceLtr] = ltrBuyNow
                        it[startingPriceMinor] = null
                        it[buyNowPriceMinor] = null
                        it[sellerDisclaimerVersion] = null
                        it[listingFeeLtr] = LISTING_FEE_LTR
                    }
                    paymentStore.delete(id)
                    if (!feeAlreadyCharged) {
                        writeLedger(
                            beneficiaryMemberId = current.memberId,
                            type = LtrLedgerEntryType.AUCTION_LISTING_FEE,
                            signedAmountLtr = LISTING_FEE_LTR.negate(),
                            auctionId = id,
                            now = now,
                        )
                    }
                } else {
                    AuctionTable.update({ AuctionTable.id eq id }) {
                        it[startingBidLtr] = ltrStart
                        it[buyNowPriceLtr] = ltrBuyNow
                    }
                }
            }
            if (wasFiat || targetFiat) {
                auditFiat(actor = current, auctionId = id, event = "AUCTION_FIAT_TERMS_CHANGED", action = AuditAction.UPDATE, now = now)
            }
            rowToDto(row = AuctionTable.selectAll().where { AuctionTable.id eq id }.single(), now = now, callerId = current.memberId)
        }
    }

    override suspend fun getAuctionPaymentDetails(auctionId: String): AuctionPaymentDetailsDto {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        // ONE constant answer for "does not exist", "not awarded", "LTR auction" and "not your auction": nothing here tells them apart.
        val id = runCatching { Uuid.parse(auctionId) }.getOrElse { throw NotFoundException(PAYMENT_DETAILS_NOT_FOUND) }
        val now = nowLocalDateTime()
        return transaction {
            val row = lockAndCloseIfDue(auctionId = id, now = now, notFoundMessage = PAYMENT_DETAILS_NOT_FOUND)
            val winner = row[AuctionTable.winnerMemberId]
            val isParty = current.memberId == row[AuctionTable.sellerMemberId] || (winner != null && current.memberId == winner)
            val awarded = row[AuctionTable.status] in AWARDED_FIAT_STATUSES
            if (!isParty || !row.isFiat() || !awarded) throw NotFoundException(PAYMENT_DETAILS_NOT_FOUND)
            if (AuctionPaymentDetailsRetention.isDueNow(auctionId = id, now = now)) paymentStore.delete(id)
            val details =
                try {
                    paymentStore.open(id)
                } catch (e: PaymentDetailsUnavailableException) {
                    throw ConflictException("The payment details are currently not available")
                } ?: throw NotFoundException(PAYMENT_DETAILS_NOT_FOUND)
            AuctionPaymentDetailsDto(
                accountHolder = details.accountHolder,
                ibanFormatted = details.iban?.chunked(4)?.joinToString(" "),
                paymentLink = details.paymentLink,
                paymentLinkHost = details.paymentLink?.let { AuctionPaymentDetailsValidator.hostOf(it) },
                amountMinor = row[AuctionTable.finalPriceMinor]!!,
                currency = row[AuctionTable.currency],
                reference = "Lapis-Auktion ${id.toString().take(8)}",
            )
        }
    }

    override suspend fun confirmPaymentReceived(auctionId: String): AuctionDto = confirmFiat(auctionId = auctionId, payment = true)

    override suspend fun confirmDeliveryReceived(auctionId: String): AuctionDto = confirmFiat(auctionId = auctionId, payment = false)

    /** [payment] `true` = the SELLER confirms the money arrived; `false` = the WINNER confirms the goods arrived. */
    private suspend fun confirmFiat(
        auctionId: String,
        payment: Boolean,
    ): AuctionDto {
        val current = resolveCurrentMember(call)
        requireAuctionEnabled()
        val id = auctionId.toAuctionUuid()
        val now = nowLocalDateTime()
        return transaction {
            requireActiveMembership(memberId = current.memberId)
            val row = lockAndCloseIfDue(auctionId = id, now = now)
            if (!row.isFiat()) throw AuctionCurrencyMismatchException()
            val seller = row[AuctionTable.sellerMemberId]
            val winner = row[AuctionTable.winnerMemberId]
            val isSeller = current.memberId == seller
            val isWinner = winner != null && current.memberId == winner
            // A stranger learns nothing about the auction's progress: NOT_FOUND. The wrong party of a known auction gets FORBIDDEN.
            if (!isSeller && !isWinner) throw NotFoundException(PAYMENT_DETAILS_NOT_FOUND)
            if (payment != isSeller) throw ForbiddenException()
            val status = row[AuctionTable.status]
            if (status == AuctionStatus.COMPLETED) throw AuctionAlreadyConfirmedException()
            if (status != AuctionStatus.AWAITING_PAYMENT && status != AuctionStatus.PAID) {
                throw ConflictException("The auction has not been awarded")
            }
            val paymentAt = row[AuctionTable.paymentConfirmedAt]
            val deliveryAt = row[AuctionTable.deliveryConfirmedAt]
            if ((payment && paymentAt != null) || (!payment && deliveryAt != null)) throw AuctionAlreadyConfirmedException()
            val bothAfter = if (payment) deliveryAt != null else paymentAt != null
            val newStatus =
                when {
                    bothAfter -> AuctionStatus.COMPLETED
                    payment -> AuctionStatus.PAID
                    else -> status
                }
            AuctionTable.update({ AuctionTable.id eq id }) {
                if (payment) it[paymentConfirmedAt] = now else it[deliveryConfirmedAt] = now
                it[AuctionTable.status] = newStatus
            }
            auditFiat(
                actor = current,
                auctionId = id,
                event = if (payment) "AUCTION_PAYMENT_CONFIRMED" else "AUCTION_DELIVERY_CONFIRMED",
                action = AuditAction.UPDATE,
                now = now,
            )
            rowToDto(row = AuctionTable.selectAll().where { AuctionTable.id eq id }.single(), now = now, callerId = current.memberId)
        }
    }

    override suspend fun getAuctionFiatOperatorDisclaimer(): AuctionComplianceDisclaimerDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        return AuctionComplianceDisclaimerDto(
            version = AuctionFiatOperatorDisclaimer.VERSION,
            text = AuctionFiatOperatorDisclaimer.TEXT,
            sha256 = AuctionFiatOperatorDisclaimer.SHA256,
        )
    }

    override suspend fun setAuctionFiatEnabled(
        enabled: Boolean,
        input: AuctionComplianceAcknowledgmentInput?,
    ): AuctionSettingsDto {
        val current = resolveCurrentMember(call)
        current.requireRole(AccountRole.ADMIN)
        if (enabled &&
            (input == null || !AuctionFiatOperatorDisclaimer.matches(version = input.disclaimerVersion, sha256 = input.disclaimerSha256))
        ) {
            throw ConflictException(
                "disclaimerVersion/disclaimerSha256 do not match the current AuctionFiatOperatorDisclaimer -- " +
                    "call getAuctionFiatOperatorDisclaimer again and submit its CURRENT version/sha256 unmodified",
            )
        }
        val now = nowLocalDateTime()
        return transaction {
            if (enabled) {
                AuctionFiatAcknowledgmentTable.insert {
                    it[id] = Uuid.random()
                    it[kind] = FIAT_ACK_OPERATOR
                    it[acknowledgedByMemberId] = current.memberId
                    it[acknowledgedAt] = now
                    it[disclaimerVersion] = AuctionFiatOperatorDisclaimer.VERSION
                    it[disclaimerSha256] = AuctionFiatOperatorDisclaimer.SHA256
                }
            }
            OrganizationSettingsTable.update({ OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }) {
                it[auctionFiatEnabled] = enabled
            }
            AuditLogRecorder.record(
                actorMemberId = current.memberId,
                actorRole = current.role,
                entityType = AuditEntityType.ORGANIZATION_SETTINGS,
                entityId = ORGANIZATION_SETTINGS_ID,
                action = AuditAction.UPDATE,
                before = null,
                after =
                    Json.encodeToString(
                        AuctionFiatSwitchAuditSnapshot.serializer(),
                        AuctionFiatSwitchAuditSnapshot(
                            auctionFiatEnabled = enabled,
                            disclaimerVersion = if (enabled) AuctionFiatOperatorDisclaimer.VERSION else null,
                        ),
                    ),
                occurredAt = now,
            )
            loadAuctionSettingsDto()
        }
    }

    // ── Fiat helpers ──────────────────────────────────────────────────────

    private fun fiatEnabledNow(): Boolean =
        OrganizationSettingsTable
            .selectAll()
            .where { OrganizationSettingsTable.id eq ORGANIZATION_SETTINGS_ID }
            .singleOrNull()
            ?.get(OrganizationSettingsTable.auctionFiatEnabled)
            ?: false

    private fun hasSellerAck(memberId: Uuid): Boolean =
        AuctionFiatAcknowledgmentTable
            .selectAll()
            .where {
                (AuctionFiatAcknowledgmentTable.acknowledgedByMemberId eq memberId) and
                    (AuctionFiatAcknowledgmentTable.kind eq FIAT_ACK_SELLER) and
                    (AuctionFiatAcknowledgmentTable.disclaimerVersion eq AuctionFiatSellerDisclaimer.VERSION)
            }.any()

    /** The echoed version/hash must be the CURRENT seller disclaimer AND the member must have acknowledged exactly it. */
    private fun requireSellerAck(
        memberId: Uuid,
        version: String,
        sha256: String,
    ) {
        if (!AuctionFiatSellerDisclaimer.matches(version = version, sha256 = sha256) || !hasSellerAck(memberId)) {
            throw AuctionFiatDisclaimerRequiredException()
        }
    }

    private fun fiatAvailability(memberId: Uuid): AuctionFiatAvailabilityDto {
        val ack =
            AuctionFiatAcknowledgmentTable
                .selectAll()
                .where {
                    (AuctionFiatAcknowledgmentTable.acknowledgedByMemberId eq memberId) and
                        (AuctionFiatAcknowledgmentTable.kind eq FIAT_ACK_SELLER) and
                        (AuctionFiatAcknowledgmentTable.disclaimerVersion eq AuctionFiatSellerDisclaimer.VERSION)
                }.orderBy(AuctionFiatAcknowledgmentTable.acknowledgedAt, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
        return AuctionFiatAvailabilityDto(
            fiatEnabled = fiatEnabledNow(),
            sellerDisclaimerVersion = AuctionFiatSellerDisclaimer.VERSION,
            sellerDisclaimerText = AuctionFiatSellerDisclaimer.TEXT,
            sellerDisclaimerSha256 = AuctionFiatSellerDisclaimer.SHA256,
            sellerAcknowledgedCurrentVersion = ack != null,
            sellerAcknowledgedAt = ack?.get(AuctionFiatAcknowledgmentTable.acknowledgedAt),
        )
    }

    private fun requireSupportedFiatCurrency(code: String) {
        if (code !in AuctionCurrencies.SUPPORTED) throw AuctionCurrencyUnsupportedException()
    }

    private fun validateFiatPrices(
        startingMinor: Long,
        buyNowMinor: Long?,
    ) {
        val range = AuctionCurrencies.MIN_MINOR..AuctionCurrencies.MAX_MINOR
        if (startingMinor !in range) throw AuctionAmountOutOfRangeException()
        if (buyNowMinor != null) {
            if (buyNowMinor !in range) throw AuctionAmountOutOfRangeException()
            if (buyNowMinor <=
                startingMinor
            ) {
                throw ConflictException("The Sofortkauf price must be strictly greater than the starting price")
            }
        }
    }

    /** Appends one `AUCTION` audit entry (event name, currency, status, time -- NEVER an amount or payment data). MUST be the LAST locking statement. */
    private fun auditFiat(
        actor: CurrentMember?,
        auctionId: Uuid,
        event: String,
        action: AuditAction,
        now: LocalDateTime,
    ) {
        val row = AuctionTable.selectAll().where { AuctionTable.id eq auctionId }.single()
        AuditLogRecorder.record(
            actorMemberId = actor?.memberId,
            actorRole = actor?.role,
            entityType = AuditEntityType.AUCTION,
            entityId = auctionId,
            action = action,
            before = null,
            after =
                Json.encodeToString(
                    AuctionFiatAuditSnapshot.serializer(),
                    AuctionFiatAuditSnapshot(
                        event = event,
                        currency = row[AuctionTable.currency],
                        status = row[AuctionTable.status],
                        occurredAt = now,
                    ),
                ),
            occurredAt = now,
        )
    }

    /** Settles a locked Fiat auction by time: no bid -> CLOSED_NO_SALE (payment details deleted at once), else the leader is awarded. NO ledger. */
    private fun settleFiatAuctionLocked(
        row: ResultRow,
        now: LocalDateTime,
    ) {
        val auctionId = row[AuctionTable.id]
        val outcome =
            computeFiatAuctionOutcome(
                startingMinor = row[AuctionTable.startingPriceMinor]!!,
                minIncrementMinor = AuctionCurrencies.MIN_INCREMENT_MINOR,
                bids = currentFiatBidViews(auctionId),
            )
        if (outcome.leaderMemberId == null) {
            AuctionTable.update({ AuctionTable.id eq auctionId }) {
                it[status] = AuctionStatus.CLOSED_NO_SALE
                it[settledAt] = now
            }
            paymentStore.delete(auctionId)
            auditFiat(actor = null, auctionId = auctionId, event = "AUCTION_FIAT_CLOSED_NO_SALE", action = AuditAction.UPDATE, now = now)
            return
        }
        AuctionTable.update({ AuctionTable.id eq auctionId }) {
            it[status] = AuctionStatus.AWAITING_PAYMENT
            it[winnerMemberId] = outcome.leaderMemberId
            it[finalPriceMinor] = outcome.currentPriceMinor
            it[settledAt] = now
        }
        auditFiat(actor = null, auctionId = auctionId, event = "AUCTION_FIAT_SETTLED", action = AuditAction.UPDATE, now = now)
    }

    /** Sofortkauf of a locked, OPEN Fiat auction (the seller check is done by the caller). NO ledger, NO member lock. */
    private fun buyNowFiat(
        row: ResultRow,
        current: CurrentMember,
        now: LocalDateTime,
    ): AuctionDto {
        val id = row[AuctionTable.id]
        val buyNowPrice = row[AuctionTable.buyNowPriceMinor] ?: throw ConflictException("Auction $id has no Sofortkauf price")
        val outcome =
            computeFiatAuctionOutcome(
                startingMinor = row[AuctionTable.startingPriceMinor]!!,
                minIncrementMinor = AuctionCurrencies.MIN_INCREMENT_MINOR,
                bids = currentFiatBidViews(id),
            )
        if (outcome.currentPriceMinor != null && outcome.currentPriceMinor >= buyNowPrice) {
            throw ConflictException("Sofortkauf is no longer available -- the current price already reached the Sofortkauf price")
        }
        AuctionTable.update({ AuctionTable.id eq id }) {
            it[status] = AuctionStatus.AWAITING_PAYMENT
            it[winnerMemberId] = current.memberId
            it[finalPriceMinor] = buyNowPrice
            it[settledAt] = now
        }
        auditFiat(actor = current, auctionId = id, event = "AUCTION_FIAT_SETTLED", action = AuditAction.UPDATE, now = now)
        return rowToDto(row = AuctionTable.selectAll().where { AuctionTable.id eq id }.single(), now = now, callerId = current.memberId)
    }

    private fun upsertFiatBid(
        targetAuctionId: Uuid,
        callerMemberId: Uuid,
        newMaxBidMinor: Long,
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
                it[AuctionBidTable.maxBidMinor] = newMaxBidMinor
                it[AuctionBidTable.createdAt] = now
            }
        } else {
            AuctionBidTable.insert {
                it[id] = Uuid.random()
                it[AuctionBidTable.auctionId] = targetAuctionId
                it[AuctionBidTable.bidderMemberId] = callerMemberId
                it[AuctionBidTable.maxBidLtr] = null
                it[AuctionBidTable.maxBidMinor] = newMaxBidMinor
                it[AuctionBidTable.createdAt] = now
            }
        }
    }

    /**
     * The status filter must not leak the private payment progress: AWAITING_PAYMENT/PAID/COMPLETED match only the caller's own auctions (seller or
     * winner); SETTLED also matches the awarded euro auctions of others, which a stranger sees as SETTLED (see [neutralForStranger]).
     */
    private fun listFilterCondition(
        filter: AuctionStatus,
        callerId: Uuid,
    ): Op<Boolean> {
        val isParty = (AuctionTable.sellerMemberId eq callerId) or (AuctionTable.winnerMemberId eq callerId)
        return when (filter) {
            in AWARDED_FIAT_STATUSES -> (AuctionTable.status eq filter) and isParty
            AuctionStatus.SETTLED ->
                (AuctionTable.status eq AuctionStatus.SETTLED) or
                    (AuctionTable.status.inList(AWARDED_FIAT_STATUSES) and not(isParty))
            else -> AuctionTable.status eq filter
        }
    }

    private fun neutralForStranger(status: AuctionStatus): AuctionStatus =
        if (status in AWARDED_FIAT_STATUSES) {
            AuctionStatus.SETTLED
        } else {
            status
        }

    /** Spam barrier of the fee-free euro auctions: at most [AuctionCurrencies.MAX_OPEN_PER_SELLER] open at the same time. Call with the member row locked. */
    private fun requireOpenFiatCapacity(sellerId: Uuid) {
        val openCount =
            AuctionTable
                .selectAll()
                .where {
                    (AuctionTable.sellerMemberId eq sellerId) and
                        (AuctionTable.currency neq AuctionCurrencies.LTR) and
                        (AuctionTable.status eq AuctionStatus.OPEN)
                }.count()
        if (openCount >= AuctionCurrencies.MAX_OPEN_PER_SELLER) {
            throw ConflictException(
                "Too many open auctions in Euro -- at most ${AuctionCurrencies.MAX_OPEN_PER_SELLER} at the same time",
            )
        }
    }

    /** [rowToDto] for a Fiat auction. The payment details are never part of it; the confirmation stamps only for the seller and the winner. */
    private fun fiatRowToDto(
        row: ResultRow,
        now: LocalDateTime,
        callerId: Uuid,
        cover: GalleryCoverRef?,
    ): AuctionDto {
        val theAuctionId = row[AuctionTable.id]
        val status = row[AuctionTable.status]
        val outcome =
            computeFiatAuctionOutcome(
                startingMinor = row[AuctionTable.startingPriceMinor]!!,
                minIncrementMinor = AuctionCurrencies.MIN_INCREMENT_MINOR,
                bids = currentFiatBidViews(theAuctionId),
            )
        val awarded = status in AWARDED_FIAT_STATUSES
        val effectiveStatus =
            if (status == AuctionStatus.OPEN && now >= row[AuctionTable.endsAt]) {
                if (outcome.leaderMemberId != null) AuctionStatus.AWAITING_PAYMENT else AuctionStatus.CLOSED_NO_SALE
            } else {
                status
            }
        val sellerId = row[AuctionTable.sellerMemberId]
        val winnerId = row[AuctionTable.winnerMemberId]
        val isParty = callerId == sellerId || (winnerId != null && callerId == winnerId)
        // The payment progress is private: a stranger sees every awarded euro auction as plain SETTLED, never AWAITING_PAYMENT/PAID/COMPLETED.
        val shownStatus = if (isParty) status else neutralForStranger(status)
        val shownEffectiveStatus = if (isParty) effectiveStatus else neutralForStranger(effectiveStatus)
        return AuctionDto(
            id = theAuctionId.toString(),
            title = row[AuctionTable.title],
            description = row[AuctionTable.description],
            sellerMemberId = sellerId.toString(),
            sellerDisplayName = memberDisplayName(sellerId),
            startingBidLtr = null,
            buyNowPriceLtr = null,
            status = shownStatus,
            effectiveStatus = shownEffectiveStatus,
            currentPriceLtr = null,
            currentLeaderDisplayName =
                if (awarded) winnerId?.let { memberDisplayName(it) } else outcome.leaderMemberId?.let { memberDisplayName(it) },
            leaderIsMe = if (awarded) winnerId == callerId else outcome.leaderMemberId == callerId,
            bidCount = outcome.bidCount,
            winnerMemberId = winnerId?.toString(),
            winnerDisplayName = winnerId?.let { memberDisplayName(it) },
            finalPriceLtr = null,
            listingFeeLtr = row[AuctionTable.listingFeeLtr],
            createdAt = row[AuctionTable.createdAt],
            endsAt = row[AuctionTable.endsAt],
            settledAt = row[AuctionTable.settledAt],
            coverImageUrl = cover?.let { GalleryUrls.imageUrl(imageId = it.imageId, storageKey = it.storageKey) },
            currency = row[AuctionTable.currency],
            startingPriceMinor = row[AuctionTable.startingPriceMinor],
            buyNowPriceMinor = row[AuctionTable.buyNowPriceMinor],
            currentPriceMinor = if (awarded) row[AuctionTable.finalPriceMinor] else outcome.currentPriceMinor,
            finalPriceMinor = row[AuctionTable.finalPriceMinor],
            paymentConfirmedAt = if (isParty) row[AuctionTable.paymentConfirmedAt] else null,
            deliveryConfirmedAt = if (isParty) row[AuctionTable.deliveryConfirmedAt] else null,
            callerIsSeller = callerId == sellerId,
            callerIsWinner = winnerId != null && callerId == winnerId,
            hasPaymentLink = isParty && awarded && paymentStore.hasLink(theAuctionId),
        )
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
        val lastFiatAck =
            AuctionFiatAcknowledgmentTable
                .selectAll()
                .where { AuctionFiatAcknowledgmentTable.kind eq FIAT_ACK_OPERATOR }
                .orderBy(AuctionFiatAcknowledgmentTable.acknowledgedAt, SortOrder.DESC)
                .limit(1)
                .singleOrNull()
        return AuctionSettingsDto(
            auctionEnabled = settingsRow[OrganizationSettingsTable.auctionEnabled],
            auctionMaxValueLtr = settingsRow[OrganizationSettingsTable.auctionMaxValueLtr],
            lastAcknowledgedByDisplayName =
                lastAck?.let { memberDisplayName(it[AuctionComplianceAcknowledgmentTable.acknowledgedByMemberId]) },
            lastAcknowledgedAt = lastAck?.get(AuctionComplianceAcknowledgmentTable.acknowledgedAt),
            lastDisclaimerVersion = lastAck?.get(AuctionComplianceAcknowledgmentTable.disclaimerVersion),
            auctionFiatEnabled = settingsRow[OrganizationSettingsTable.auctionFiatEnabled],
            fiatLastAcknowledgedByDisplayName =
                lastFiatAck?.let { memberDisplayName(it[AuctionFiatAcknowledgmentTable.acknowledgedByMemberId]) },
            fiatLastAcknowledgedAt = lastFiatAck?.get(AuctionFiatAcknowledgmentTable.acknowledgedAt),
            fiatLastDisclaimerVersion = lastFiatAck?.get(AuctionFiatAcknowledgmentTable.disclaimerVersion),
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
        notFoundMessage: String? = null,
    ): ResultRow {
        val row =
            AuctionTable
                .selectAll()
                .where { AuctionTable.id eq auctionId }
                .forUpdate()
                .singleOrNull()
                ?: throw NotFoundException(notFoundMessage ?: "Auction $auctionId not found")
        if (row[AuctionTable.status] != AuctionStatus.OPEN || now < row[AuctionTable.endsAt]) return row
        settleAuctionLocked(row = row, now = now)
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
        if (row.isFiat()) {
            settleFiatAuctionLocked(row = row, now = now)
            return
        }
        val auctionId = row[AuctionTable.id]
        val seller = row[AuctionTable.sellerMemberId]
        val outcome =
            computeAuctionOutcome(
                startingBidLtr = row[AuctionTable.startingBidLtr]!!,
                minIncrementLtr = MIN_INCREMENT_LTR,
                bids = currentBidViews(auctionId),
            )
        if (outcome.leaderMemberId == null) {
            AuctionTable.update({ AuctionTable.id eq auctionId }) {
                it[status] = AuctionStatus.CLOSED_NO_SALE
                it[settledAt] = now
            }
            return
        }
        val winner = outcome.leaderMemberId
        val finalPrice = outcome.currentPriceLtr!!
        lockBothAccounts(a = winner, b = seller)
        writeLedger(
            beneficiaryMemberId = winner,
            type = LtrLedgerEntryType.AUCTION_HOLD_RELEASE,
            signedAmountLtr = outcome.leaderMaxBidLtr!!,
            auctionId = auctionId,
            now = now,
        )
        writeLedger(
            beneficiaryMemberId = winner,
            type = LtrLedgerEntryType.AUCTION_SALE_OUT,
            signedAmountLtr = finalPrice.negate(),
            auctionId = auctionId,
            now = now,
        )
        writeLedger(
            beneficiaryMemberId = seller,
            type = LtrLedgerEntryType.AUCTION_SALE_IN,
            signedAmountLtr = finalPrice,
            auctionId = auctionId,
            now = now,
        )
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
            .mapNotNull { row ->
                val maxBid = row[AuctionBidTable.maxBidLtr] ?: return@mapNotNull null
                AuctionBidView(
                    bidId = row[AuctionBidTable.id],
                    bidderMemberId = row[AuctionBidTable.bidderMemberId],
                    maxBidLtr = maxBid,
                    createdAt = row[AuctionBidTable.createdAt],
                )
            }

    private fun currentFiatBidViews(auctionId: Uuid): List<FiatBidView> =
        AuctionBidTable
            .selectAll()
            .where { AuctionBidTable.auctionId eq auctionId }
            .mapNotNull { row ->
                val maxBid = row[AuctionBidTable.maxBidMinor] ?: return@mapNotNull null
                FiatBidView(
                    bidId = row[AuctionBidTable.id],
                    bidderMemberId = row[AuctionBidTable.bidderMemberId],
                    maxBidMinor = maxBid,
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
        // V1.9.320 last line of defence: a Fiat auction NEVER writes the LTR ledger (see class KDoc "Fiat path").
        val auctionCurrency =
            AuctionTable
                .selectAll()
                .where { AuctionTable.id eq auctionId }
                .single()[AuctionTable.currency]
        check(auctionCurrency == AuctionCurrencies.LTR) { "ledger write for non-LTR auction" }
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
        covers: Map<Uuid, GalleryCoverRef>? = null,
    ): AuctionDto {
        val theAuctionId = row[AuctionTable.id]
        // V1.9.222: a list passes the covers of ALL its rows (one query); a single row looks its own up.
        val cover =
            (
                covers ?: AttachedImageStore.coverRefsFor(
                    type = AttachedImageOwnerType.AUCTION_LISTING,
                    ownerIds = listOf(theAuctionId),
                )
            )[theAuctionId]
        if (row.isFiat()) return fiatRowToDto(row = row, now = now, callerId = callerId, cover = cover)
        val status = row[AuctionTable.status]
        val outcome =
            computeAuctionOutcome(
                startingBidLtr = row[AuctionTable.startingBidLtr]!!,
                minIncrementLtr = MIN_INCREMENT_LTR,
                bids = currentBidViews(theAuctionId),
            )
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
            coverImageUrl = cover?.let { GalleryUrls.imageUrl(imageId = it.imageId, storageKey = it.storageKey) },
        )
    }

    private fun nowLocalDateTime(): LocalDateTime = DbClock.nowLocalDateTime()

    private fun String.toAuctionUuid(): Uuid = runCatching { Uuid.parse(this) }.getOrElse { throw NotFoundException("Invalid id: $this") }
}
