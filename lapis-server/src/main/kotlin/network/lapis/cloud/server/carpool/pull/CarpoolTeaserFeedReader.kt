package network.lapis.cloud.server.carpool.pull

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import network.lapis.cloud.server.carpool.CarpoolOriginSignatures
import network.lapis.cloud.server.carpool.ParsedRelayItem
import network.lapis.cloud.server.carpool.ParsedTeaserFeed
import network.lapis.cloud.server.carpool.feed.CARPOOL_TEASER_FEED_KIND
import network.lapis.cloud.server.carpool.feed.CarpoolTeaserFeedItem
import network.lapis.cloud.server.partner.PartnerBlocklistPattern
import network.lapis.cloud.server.partner.PartnerFeedParser
import network.lapis.cloud.server.partner.PartnerHost
import network.lapis.cloud.shared.domain.CarpoolFederationLimits
import network.lapis.cloud.shared.domain.CarpoolPullErrorCode
import network.lapis.cloud.shared.domain.CarpoolReceivedRejectReason
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.EnumMap

private val logger = KotlinLogging.logger {}

internal sealed interface TeaserFeedParse {
    class Ok(
        val feed: ParsedTeaserFeed,
    ) : TeaserFeedParse

    class Invalid(
        val code: CarpoolPullErrorCode,
    ) : TeaserFeedParse
}

/**
 * V1.9.314 W4 -- reads the carpool teaser feed of a FOREIGN server. Pure, no database, no network. Nothing in it is trusted: strict UTF-8, the nesting
 * depth is checked BEFORE the JSON parser sees the text, the lists are bounded, EVERY item is decoded and checked on its own (one bad item never voids the
 * rest), and every field must be in its canonical form. Nothing here is ever interpreted as markup; fields are plain data.
 *
 * The signature of the origin and the key are NOT checked here (the store does that inside its transaction, against the pinned key).
 */
internal object CarpoolTeaserFeedReader {
    const val MAX_LIST = 1000
    private val ID = Regex("^[0-9a-f]{64}$")
    private val BASE64 = Regex("^[A-Za-z0-9+/]+={0,2}$")
    private val JSON =
        Json {
            ignoreUnknownKeys = true
            isLenient = false
        }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    fun parse(
        bytes: ByteArray,
        sourceHost: String,
        ownHost: String,
        today: LocalDate,
        blockPatterns: List<String>,
    ): TeaserFeedParse {
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
            runCatching { JSON.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return invalid(CarpoolPullErrorCode.MALFORMED)
        val kind = (root["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        val version = (root["schemaVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        if (kind != CARPOOL_TEASER_FEED_KIND || version != 1) return invalid(CarpoolPullErrorCode.SCHEMA)
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
        var unsigned = 0
        val byReason = EnumMap<CarpoolReceivedRejectReason, Int>(CarpoolReceivedRejectReason::class.java)
        val seen = HashSet<Pair<String, String>>()
        val items = ArrayList<ParsedRelayItem>()
        val maxDate = today.plus(CarpoolFederationLimits.MAX_DAYS_AHEAD, DateTimeUnit.DAY)
        for (el in itemsRaw) {
            val item = runCatching { JSON.decodeFromJsonElement(CarpoolTeaserFeedItem.serializer(), el) }.getOrNull()
            if (item == null) {
                rejected++
                byReason.merge(CarpoolReceivedRejectReason.FORMAT, 1, Int::plus)
                continue
            }
            val reason =
                rejectReason(
                    item = item,
                    sourceHost = sourceHost,
                    ownHost = ownHost,
                    today = today,
                    maxDate = maxDate,
                    blockPatterns = blockPatterns,
                )
            if (reason != null) {
                rejected++
                if (reason == CarpoolReceivedRejectReason.SIGNATURE) unsigned++
                byReason.merge(reason, 1, Int::plus)
                continue
            }
            val teaser = item.teaser
            val signature = requireNotNull(item.originSignature)
            if (!seen.add(item.originHost to teaser.teaserId)) continue
            items +=
                ParsedRelayItem(
                    teaser = teaser,
                    originHost = item.originHost,
                    wireHops = item.hops,
                    maxHops = item.maxHops,
                    via = item.via,
                    originSignature = signature,
                )
        }
        val withdrawn =
            withdrawnRaw
                .mapNotNull { ((it as? JsonObject)?.get("teaserId") as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
                .filter { ID.matches(it) }
                .distinct()
        if (rejected > 0) logger.info { "CarpoolTeaserFeedReader: host=$sourceHost rejected=$rejected kept=${items.size}" }
        return TeaserFeedParse.Ok(
            ParsedTeaserFeed(
                complete = complete,
                items = items,
                withdrawnIds = withdrawn,
                rejected = rejected,
                rejectedUnsigned = unsigned,
                rejectedByReason = byReason,
            ),
        )
    }

    /**
     * The first failing check of an item, in a FIXED order: format, then signature present, then blocked, then hop limit, then expired. `null` = fine.
     * The order decides which cause is counted when an item has several faults, so it is pinned by a test.
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun rejectReason(
        item: CarpoolTeaserFeedItem,
        sourceHost: String,
        ownHost: String,
        today: LocalDate,
        maxDate: LocalDate,
        blockPatterns: List<String>,
    ): CarpoolReceivedRejectReason? {
        val signature = item.originSignature
        val teaser = item.teaser
        val format =
            ID.matches(teaser.teaserId) &&
                isCanonicalHost(item.originHost) &&
                item.via.size == item.hops &&
                item.via.all { isCanonicalHost(it) && it != item.originHost && it != ownHost } &&
                item.via.toSet().size == item.via.size &&
                (
                    signature.isNullOrBlank() ||
                        (signature.length <= CarpoolOriginSignatures.MAX_SIGNATURE_LENGTH && BASE64.matches(signature))
                ) &&
                teaser.departureDate <= maxDate &&
                (item.hops !in 0..2 || (if (item.hops == 0) item.originHost == sourceHost else item.via.lastOrNull() == sourceHost))
        if (!format) return CarpoolReceivedRejectReason.FORMAT
        if (signature.isNullOrBlank()) return CarpoolReceivedRejectReason.SIGNATURE
        if (item.originHost == ownHost ||
            PartnerBlocklistPattern.isBlocked(host = item.originHost, patterns = blockPatterns) ||
            item.via.any { PartnerBlocklistPattern.isBlocked(host = it, patterns = blockPatterns) }
        ) {
            return CarpoolReceivedRejectReason.BLOCKED
        }
        if (item.hops !in 0..2 || item.maxHops !in 1..CarpoolFederationLimits.MAX_HOPS || item.hops + 1 > item.maxHops) {
            return CarpoolReceivedRejectReason.HOP_LIMIT
        }
        if (teaser.departureDate < today) return CarpoolReceivedRejectReason.EXPIRED
        return null
    }

    private fun isCanonicalHost(host: String): Boolean = PartnerHost.normalizeHostOnly(host) == host

    private fun invalid(code: CarpoolPullErrorCode): TeaserFeedParse = TeaserFeedParse.Invalid(code)
}
