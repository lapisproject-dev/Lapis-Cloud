package network.lapis.cloud.server.auction.federation

import network.lapis.cloud.server.crypto.SecretBox
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid

/**
 * The pseudonymous id of a hint (`teaserId`): the keyed, domain-separated HMAC-SHA256 of the auction id, 64 lower-case hex characters. Nobody outside
 * this server can derive it from the auction id or reverse it. Global per auction (the same for every partner). The key comes from
 * `LAPIS_SECRET_ENCRYPTION_KEY` (never the AES key itself); rotating it changes every id, which a partner sees as "all withdrawn, all new". The purpose
 * differs from the carpool one, so an id of one exchange is never an id of the other. Never logged, `toString` is redacted.
 */
internal class AuctionTeaserIds(
    private val box: SecretBox,
) {
    fun of(auctionId: Uuid): String = box.hmacSha256Hex(purpose = PURPOSE, data = auctionId.toString())

    override fun toString(): String = "AuctionTeaserIds(<redacted>)"

    companion object {
        const val PURPOSE = "lapis-auction-teaser-id/v1"
    }
}

/**
 * The ONE holder of the id deriver for the paths without constructor injection (the RPC services, the withdrawal helpers). Set once by
 * `Application.kt` with the SAME instance the feed route uses (pinned by `AuctionFederationTripwireTest`).
 */
internal object AuctionFederationKey {
    @Volatile
    private var ids: AuctionTeaserIds? = null

    /** Withdrawals that found no key: the consent was cleared but no tombstone could be written. */
    val skippedNoKey = AtomicLong(0)

    fun configure(ids: AuctionTeaserIds?) {
        this.ids = ids
    }

    fun current(): AuctionTeaserIds? = ids

    internal fun resetForTest() {
        ids = null
        skippedNoKey.set(0)
    }
}
