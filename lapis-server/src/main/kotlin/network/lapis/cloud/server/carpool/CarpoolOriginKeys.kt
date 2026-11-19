package network.lapis.cloud.server.carpool

import network.lapis.cloud.server.partner.PartnerDiscoveryStore
import network.lapis.cloud.server.partner.PartnerServerStore

/**
 * V1.9.314 W4 -- which public key verifies the origin signature of a hint, WITHOUT any request: (1) the pinned key of a direct partner with exactly
 * this host (active, not blocked, key id on that host), else (2) the key pinned from the partner recursion for a discovered server with exactly this
 * host. `null` = unknown: the hint is dropped. Runs inside the caller's transaction.
 */
internal object CarpoolOriginKeys {
    fun resolve(originHost: String): String? =
        PartnerServerStore.pinnedSigningKeyFor(originHost) ?: PartnerDiscoveryStore.pinnedSigningKeyFor(originHost)
}
