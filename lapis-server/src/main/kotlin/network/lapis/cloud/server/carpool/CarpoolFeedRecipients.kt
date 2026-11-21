package network.lapis.cloud.server.carpool

import network.lapis.cloud.server.partner.PartnerBlocklistPattern
import network.lapis.cloud.server.partner.PartnerRow

/**
 * V1.9.317 (wave 5): the ONE predicate "this direct partner may pull our teaser feed right now" (instance switch aside, which the caller checks).
 * Used by the feed gate and by the reach information of the consent box, so the list the owner reads cannot drift from what really happens
 * (pinned by `CarpoolFederationTripwireTest`). [blockPatterns] is read once by the caller.
 */
internal fun PartnerRow.mayPullCarpoolFeed(blockPatterns: List<String>): Boolean =
    active &&
        carpoolSharing &&
        signingPublicKeyPem != null &&
        signingKeyId != null &&
        // V1.9.319 (wave 7): a changed signing key pauses the exchange until an administrator accepts it (pinned by CarpoolFederationTripwireTest).
        signingKeyChangedAt == null &&
        !PartnerBlocklistPattern.isBlocked(host = host, patterns = blockPatterns)
