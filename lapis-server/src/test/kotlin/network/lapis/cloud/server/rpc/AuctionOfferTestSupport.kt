package network.lapis.cloud.server.rpc

import network.lapis.cloud.shared.domain.AuctionOfferDto
import network.lapis.cloud.shared.domain.AuctionOfferNoticeKind
import network.lapis.cloud.shared.rpc.IAuctionService

/**
 * V1.9.323 -- `makeOffer`/`counterOffer` now need the echo of the notice text. These two overloads (an extension never wins against a member that
 * fits, but the member needs five arguments) fill in the CURRENT German notice, so the older scenarios keep reading like the business story.
 */
internal suspend fun IAuctionService.makeOffer(
    auctionId: String,
    amountMinor: Long,
): AuctionOfferDto {
    val kind = AuctionOfferNoticeKind.BUYER_OFFER
    return makeOffer(
        auctionId = auctionId,
        amountMinor = amountMinor,
        noticeVersion = AuctionOfferNotice.VERSION,
        noticeLang = "de",
        noticeSha256 = AuctionOfferNotice.sha256(kind = kind, lang = "de")!!,
    )
}

internal suspend fun IAuctionService.counterOffer(
    offerId: String,
    amountMinor: Long,
): AuctionOfferDto {
    val kind = AuctionOfferNoticeKind.SELLER_COUNTER
    return counterOffer(
        offerId = offerId,
        amountMinor = amountMinor,
        noticeVersion = AuctionOfferNotice.VERSION,
        noticeLang = "de",
        noticeSha256 = AuctionOfferNotice.sha256(kind = kind, lang = "de")!!,
    )
}
