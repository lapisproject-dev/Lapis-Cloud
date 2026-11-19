package network.lapis.cloud.server.carpool.pull

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.carpool.CarpoolOriginSignatures
import network.lapis.cloud.server.carpool.CarpoolOwnSigningKey
import network.lapis.cloud.server.carpool.CarpoolPullApplyResult
import network.lapis.cloud.server.carpool.CarpoolReceivedStore
import network.lapis.cloud.server.carpool.feed.CARPOOL_TEASER_FEED_JSON
import network.lapis.cloud.server.carpool.feed.CarpoolTeaserFeedItem
import network.lapis.cloud.server.carpool.feed.CarpoolTeaserFeedV1
import network.lapis.cloud.server.carpool.feed.CarpoolTeaserWithdrawal
import network.lapis.cloud.server.federation.FederationKeyPair
import network.lapis.cloud.server.federation.FederationKeyPairGenerator
import network.lapis.cloud.server.partner.PartnerServerStore
import network.lapis.cloud.shared.domain.CarpoolTeaser
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import kotlin.uuid.Uuid

/** The "today" and "now" of the pull tests (fixed, so no test depends on the wall clock). */
internal val PULL_TODAY = LocalDate(2026, 11, 19)
internal val PULL_DEPARTURE = LocalDate(2026, 11, 24)
internal val PULL_NOW = LocalDateTime(2026, 11, 19, 10, 0, 0)
internal const val PULL_OWN_HOST = "b.beispiel.de"

internal fun hexId(seed: String): String =
    MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** A foreign server that signs hints as their origin. The key is the one a partner row pins (key id on the host itself). */
internal class OriginServer(
    val host: String,
    val keyPair: FederationKeyPair = FederationKeyPairGenerator.generate(),
) {
    val keyId = "https://$host/federation/actor#main-key"
    val signing = CarpoolOwnSigningKey(keyId = keyId, privateKeyPem = keyPair.privateKeyPem)

    fun teaser(
        seed: String,
        date: LocalDate = PULL_DEPARTURE,
        from: String = "38",
        to: String = "10",
    ) = CarpoolTeaser(teaserId = hexId(seed), departureDate = date, fromPostcodeArea = from, toPostcodeArea = to)

    /** An item as THIS origin signs it, with the wire fields of the relay that hands it on (`hops`, `via`). */
    fun item(
        seed: String,
        maxHops: Int = 2,
        hops: Int = 0,
        via: List<String> = emptyList(),
        date: LocalDate = PULL_DEPARTURE,
        from: String = "38",
        to: String = "10",
        signedMaxHops: Int = maxHops,
        signedBy: OriginServer = this,
        signature: String? = null,
    ): CarpoolTeaserFeedItem {
        val teaser = teaser(seed = seed, date = date, from = from, to = to)
        val sig =
            signature ?: CarpoolOriginSignatures.sign(
                teaser = teaser,
                originHost = host,
                maxHops = signedMaxHops,
                key = signedBy.signing,
            )
        return CarpoolTeaserFeedItem(
            originHost = host,
            hops = hops,
            maxHops = maxHops,
            teaser = teaser,
            via = via,
            originSignature = sig,
        )
    }
}

internal fun feedBytes(
    items: List<CarpoolTeaserFeedItem>,
    withdrawn: List<String> = emptyList(),
    complete: Boolean = true,
): ByteArray =
    CARPOOL_TEASER_FEED_JSON
        .encodeToString(
            CarpoolTeaserFeedV1.serializer(),
            CarpoolTeaserFeedV1(
                complete = complete,
                items = items,
                withdrawn = withdrawn.map { CarpoolTeaserWithdrawal(teaserId = it, withdrawnAt = PULL_TODAY) },
            ),
        ).toByteArray(Charsets.UTF_8)

internal fun parseOk(
    bytes: ByteArray,
    sourceHost: String,
    ownHost: String = PULL_OWN_HOST,
    blocked: List<String> = emptyList(),
) = (
    CarpoolTeaserFeedReader.parse(
        bytes = bytes,
        sourceHost = sourceHost,
        ownHost = ownHost,
        today = PULL_TODAY,
        blockPatterns = blocked,
    ) as TeaserFeedParse.Ok
).feed

/** Reads [bytes] as the answer of the partner [sourceId] and applies them, exactly as the poller does (reader, then ONE transaction). */
internal fun applyFeed(
    sourceId: Uuid,
    bytes: ByteArray,
    now: LocalDateTime = PULL_NOW,
    snapshot: network.lapis.cloud.server.partner.PartnerRow? = null,
): CarpoolPullApplyResult =
    transaction {
        val row = snapshot ?: PartnerServerStore.findPartner(sourceId)!!
        val current = PartnerServerStore.findPartner(sourceId)!!
        val parsed = parseOk(bytes = bytes, sourceHost = current.host, blocked = PartnerServerStore.blockPatterns())
        CarpoolReceivedStore.apply(source = row, feed = parsed, etag = "\"e\"", next = now, now = now)
    }
