package network.lapis.cloud.server.auction.federation.pull

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import network.lapis.cloud.server.auction.federation.AuctionOriginSignatures
import network.lapis.cloud.server.auction.federation.feed.AUCTION_TEASER_FEED_KIND
import network.lapis.cloud.server.auction.federation.feed.AuctionTeaserFeedItem
import network.lapis.cloud.server.partner.PartnerBlocklistPattern
import network.lapis.cloud.server.partner.PartnerFeedParser
import network.lapis.cloud.server.partner.PartnerHost
import network.lapis.cloud.shared.domain.AuctionFederationLimits
import network.lapis.cloud.shared.domain.AuctionReceivedRejectReason
import network.lapis.cloud.shared.domain.AuctionTeaser
import network.lapis.cloud.shared.domain.CarpoolPullErrorCode
import network.lapis.cloud.shared.domain.isCanonicalFederationHost
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.EnumMap
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

/** One hint of a received feed that passed the format checks of the reader (no key and no signature check yet; the store does those). */
internal class ParsedAuctionItem(
    val teaser: AuctionTeaser,
    val originHost: String,
    val wireHops: Int,
    val maxHops: Int,
    val via: List<String>,
    val originSignature: String,
)

/** What the reader makes of a feed: the valid items, the ids of the withdrawals, the number of rejected entries and whether the lists are complete. */
internal class ParsedAuctionFeed(
    val complete: Boolean,
    val items: List<ParsedAuctionItem>,
    val withdrawnIds: List<String>,
    val rejected: Int,
    val rejectedByReason: Map<AuctionReceivedRejectReason, Int> = emptyMap(),
)

internal sealed interface AuctionFeedParse {
    class Ok(
        val feed: ParsedAuctionFeed,
    ) : AuctionFeedParse

    class Invalid(
        val code: CarpoolPullErrorCode,
    ) : AuctionFeedParse
}

/**
 * V1.9.322 (step c3) -- reads the auction teaser feed of a FOREIGN server. Pure, no database, no network. Nothing in it is trusted: strict UTF-8, the
 * nesting depth is checked BEFORE the JSON parser sees the text, the lists are bounded, EVERY item is checked on its own (one bad item never voids the
 * rest) and every field must be in its canonical form. The key sets are checked BEFORE the decode: a field this server does not know (a seller name, an
 * IBAN, a bid count ...) rejects the item as UNKNOWN_FIELD and is never decoded, let alone stored. Nothing here is ever interpreted as markup.
 *
 * The signature of the origin and the key are NOT checked here (the store does that inside its transaction, against the pinned key).
 */
internal object AuctionTeaserFeedReader {
    const val MAX_LIST = 1000
    private val ID = Regex("^[0-9a-f]{64}$")
    private val BASE64 = Regex("^[A-Za-z0-9+/]+={0,2}$")

    /** The keys the root object may have. */
    internal val ROOT_KEYS = setOf("schemaVersion", "kind", "complete", "items", "withdrawn")

    /** The keys an item may have. */
    internal val ITEM_KEYS = setOf("originHost", "hops", "maxHops", "teaser", "via", "originSignature")

    /**
     * The ten fields of the wire teaser, hard-coded on purpose. A test pins this set against the serializer descriptor of [AuctionTeaser], so adding a
     * wire field is a deliberate act that must touch both.
     */
    internal val TEASER_KEYS =
        setOf(
            "teaserId",
            "revision",
            "title",
            "currency",
            "priceKind",
            "priceMinor",
            "buyNowMinor",
            "offersPossible",
            "endsAt",
            "priceAsOf",
        )

    // Its OWN instance: strict about unknown keys. The shared feed Json (encode side) is never touched.
    private val STRICT =
        Json {
            ignoreUnknownKeys = false
            isLenient = false
            explicitNulls = false
        }
    private val PLAIN = Json { isLenient = false }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    fun parse(
        bytes: ByteArray,
        sourceHost: String,
        ownHost: String,
        ownHopLimit: Int,
        now: Instant,
        blockPatterns: List<String>,
    ): AuctionFeedParse {
        val text =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (e: CharacterCodingException) {
                return invalid(CarpoolPullErrorCode.MALFORMED)
            }
        if (PartnerFeedParser.exceedsDepth(text)) return invalid(CarpoolPullErrorCode.MALFORMED)
        val root =
            runCatching { PLAIN.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return invalid(CarpoolPullErrorCode.MALFORMED)
        if (!ROOT_KEYS.containsAll(root.keys)) return invalid(CarpoolPullErrorCode.SCHEMA)
        val kind = (root["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val version = (root["schemaVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (kind != AUCTION_TEASER_FEED_KIND || version != 1) return invalid(CarpoolPullErrorCode.SCHEMA)
        val complete =
            (root["complete"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return invalid(CarpoolPullErrorCode.MALFORMED)
        val itemsRaw = root["items"] as? JsonArray ?: return invalid(CarpoolPullErrorCode.MALFORMED)
        val withdrawnRaw =
            when (val w = root["withdrawn"]) {
                null -> JsonArray(emptyList())
                is JsonArray -> w
                else -> return invalid(CarpoolPullErrorCode.MALFORMED)
            }
        if (itemsRaw.size > MAX_LIST || withdrawnRaw.size > MAX_LIST) return invalid(CarpoolPullErrorCode.MALFORMED)

        var rejected = 0
        val byReason = EnumMap<AuctionReceivedRejectReason, Int>(AuctionReceivedRejectReason::class.java)

        fun reject(reason: AuctionReceivedRejectReason) {
            rejected++
            byReason.merge(reason, 1, Int::plus)
        }
        val seen = HashSet<Pair<String, String>>()
        val items = ArrayList<ParsedAuctionItem>()
        for (el in itemsRaw) {
            val obj = el as? JsonObject
            if (obj == null) {
                reject(AuctionReceivedRejectReason.FORMAT)
                continue
            }
            val teaserObj = obj["teaser"] as? JsonObject
            if (!ITEM_KEYS.containsAll(obj.keys) || (teaserObj != null && !TEASER_KEYS.containsAll(teaserObj.keys))) {
                reject(AuctionReceivedRejectReason.UNKNOWN_FIELD)
                continue
            }
            val item = runCatching { STRICT.decodeFromJsonElement(AuctionTeaserFeedItem.serializer(), el) }.getOrNull()
            if (item == null) {
                reject(AuctionReceivedRejectReason.FORMAT)
                continue
            }
            val reason =
                rejectReason(
                    item = item,
                    sourceHost = sourceHost,
                    ownHost = ownHost,
                    ownHopLimit = ownHopLimit,
                    now = now,
                    blockPatterns = blockPatterns,
                )
            if (reason != null) {
                reject(reason)
                continue
            }
            if (!seen.add(item.originHost to item.teaser.teaserId)) continue
            items +=
                ParsedAuctionItem(
                    teaser = item.teaser,
                    originHost = item.originHost,
                    wireHops = item.hops,
                    maxHops = item.maxHops,
                    via = item.via,
                    originSignature = item.originSignature,
                )
        }
        val withdrawn =
            withdrawnRaw
                .mapNotNull { entry ->
                    val o = entry as? JsonObject ?: return@mapNotNull null
                    val id = (o["teaserId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return@mapNotNull null
                    val day = (o["withdrawnAt"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    // An entry without a readable day is ignored (the same rule as the day of a carpool withdrawal).
                    if (day == null || runCatching { LocalDate.parse(day) }.isFailure) null else id
                }.filter { ID.matches(it) }
                .distinct()
        if (rejected > 0) logger.info { "AuctionTeaserFeedReader: host=$sourceHost rejected=$rejected kept=${items.size}" }
        return AuctionFeedParse.Ok(
            ParsedAuctionFeed(
                complete = complete,
                items = items,
                withdrawnIds = withdrawn,
                rejected = rejected,
                rejectedByReason = byReason,
            ),
        )
    }

    /**
     * The first failing check of an item, in a FIXED order: format, signature present, blocked, hop limit, content rules. `null` = fine. The order
     * decides which cause is counted when an item has several faults, so it is pinned by a test.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun rejectReason(
        item: AuctionTeaserFeedItem,
        sourceHost: String,
        ownHost: String,
        ownHopLimit: Int,
        now: Instant,
        blockPatterns: List<String>,
    ): AuctionReceivedRejectReason? {
        val signature = item.originSignature
        val format =
            ID.matches(item.teaser.teaserId) &&
                isCanonicalHost(item.originHost) &&
                item.via.size == item.hops &&
                item.via.all { isCanonicalHost(it) && it != item.originHost && it != ownHost } &&
                item.via.toSet().size == item.via.size &&
                (signature.isBlank() || (signature.length <= AuctionOriginSignatures.MAX_SIGNATURE_LENGTH && BASE64.matches(signature))) &&
                // The path must end at the host we pulled from. (An out-of-range hop count skips this and is counted as HOP_LIMIT below.)
                (item.hops !in 0..1 || (if (item.hops == 0) item.originHost == sourceHost else item.via.lastOrNull() == sourceHost))
        if (!format) return AuctionReceivedRejectReason.FORMAT
        if (signature.isBlank()) return AuctionReceivedRejectReason.SIGNATURE
        if (item.originHost == ownHost ||
            PartnerBlocklistPattern.isBlocked(host = item.originHost, patterns = blockPatterns) ||
            item.via.any { PartnerBlocklistPattern.isBlocked(host = it, patterns = blockPatterns) }
        ) {
            return AuctionReceivedRejectReason.BLOCKED
        }
        if (item.hops !in 0..1 ||
            item.maxHops !in 1..AuctionFederationLimits.MAX_HOPS ||
            item.hops + 1 > item.maxHops ||
            // Unlike the carpool reader, the auction reader applies OUR OWN hop limit already on receipt: with limit 1 only hints of direct origins stay.
            item.hops + 1 > ownHopLimit
        ) {
            return AuctionReceivedRejectReason.HOP_LIMIT
        }
        return AuctionTeaserValidator.check(teaser = item.teaser, now = now)
    }

    private fun isCanonicalHost(host: String): Boolean = isCanonicalFederationHost(host) && PartnerHost.normalizeHostOnly(host) == host

    private fun invalid(code: CarpoolPullErrorCode): AuctionFeedParse = AuctionFeedParse.Invalid(code)
}
