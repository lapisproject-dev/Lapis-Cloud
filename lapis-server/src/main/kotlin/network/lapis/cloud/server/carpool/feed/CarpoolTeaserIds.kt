package network.lapis.cloud.server.carpool.feed

import network.lapis.cloud.server.crypto.SecretBox
import kotlin.uuid.Uuid

/**
 * The pseudonymous id of a hint (`teaserId`): the keyed, domain-separated HMAC-SHA256 of the trip id, 64 lower-case hex characters. Nobody outside
 * this server can derive it from the trip id or reverse it. Global per trip (the same for every partner, so a later wave can deduplicate and
 * withdraw across several paths). The key is derived from `LAPIS_SECRET_ENCRYPTION_KEY` (never the AES key itself); rotating that key changes
 * every id, which a partner sees as "all withdrawn, all new". Never logged, `toString` is redacted.
 */
internal class CarpoolTeaserIds(
    private val box: SecretBox,
) {
    fun of(postingId: Uuid): String = box.hmacSha256Hex(purpose = PURPOSE, data = postingId.toString())

    override fun toString(): String = "CarpoolTeaserIds(<redacted>)"

    companion object {
        const val PURPOSE = "carpool-teaser-id/v1"
    }
}
