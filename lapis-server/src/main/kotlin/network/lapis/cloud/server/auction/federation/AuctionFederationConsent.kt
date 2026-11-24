package network.lapis.cloud.server.auction.federation

import network.lapis.cloud.server.db.generated.AuctionTable
import org.jetbrains.exposed.v1.core.ResultRow
import kotlin.uuid.Uuid

/**
 * V1.9.322 (step c2): THE one place that says who may share an auction -- the seller, as an active member who confirmed the Euro notice. A later
 * restriction (for example to full members) changes only this function. Whether the AUCTION may be shared (Euro, open, enough time, a presentable
 * title, instance on) is `mayConsentToAuctionFederation` in lapis-shared.
 *
 * [memberIsActive] and [sellerNoticeConfirmed] are read by the caller in the same transaction (the membership check, the notice acknowledgment).
 */
internal fun mayShareAuction(
    memberId: Uuid,
    auctionRow: ResultRow,
    memberIsActive: Boolean,
    sellerNoticeConfirmed: Boolean,
): Boolean = auctionRow[AuctionTable.sellerMemberId] == memberId && memberIsActive && sellerNoticeConfirmed
