package network.lapis.cloud.server.auction.federation

import network.lapis.cloud.server.partner.PartnerBlocklistPattern
import network.lapis.cloud.server.partner.PartnerRow

/**
 * V1.9.322 (step c2): the ONE predicate "this direct partner may pull our auction feed right now" (the instance and module switches aside, which the
 * caller checks). Used by the feed gate and by the reach information of the consent box, so the list the seller reads cannot drift from what really
 * happens (pinned by `AuctionFederationTripwireTest`). [blockPatterns] is read once by the caller.
 */
internal fun PartnerRow.mayPullAuctionFeed(blockPatterns: List<String>): Boolean =
    active &&
        auctionSharing &&
        signingPublicKeyPem != null &&
        signingKeyId != null &&
        // A changed signing key pauses the exchange until an administrator accepts it (the same hold as the carpool exchange).
        signingKeyChangedAt == null &&
        !PartnerBlocklistPattern.isBlocked(host = host, patterns = blockPatterns)
