package network.lapis.cloud.server.auction.federation.pull

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.server.auction.federation.feed.AuctionTeaserFeedItem
import network.lapis.cloud.shared.domain.AuctionReceivedRejectReason
import network.lapis.cloud.shared.domain.AuctionTeaser
import network.lapis.cloud.shared.domain.CarpoolPullErrorCode
import kotlin.time.Duration.Companion.hours

/** V1.9.322 c3 -- the reader of a foreign auction feed: pure, every item on its own, strict, bounded. */
class AuctionTeaserFeedReaderTest :
    FunSpec({
        val a = AuctionOriginServer(host = "a.beispiel.de")
        val o = AuctionOriginServer(host = "o.beispiel.de")

        fun parse(
            text: String,
            source: String = "a.beispiel.de",
            ownHopLimit: Int = 2,
            blocked: List<String> = emptyList(),
        ) = AuctionTeaserFeedReader.parse(
            bytes = text.toByteArray(),
            sourceHost = source,
            ownHost = AUCTION_PULL_OWN_HOST,
            ownHopLimit = ownHopLimit,
            now = AUCTION_PULL_NOW_INSTANT,
            blockPatterns = blocked,
        )

        fun invalid(r: AuctionFeedParse) = r.shouldBeInstanceOf<AuctionFeedParse.Invalid>().code

        fun ok(r: AuctionFeedParse) = r.shouldBeInstanceOf<AuctionFeedParse.Ok>().feed

        fun feedOf(
            vararg items: AuctionTeaserFeedItem,
            ownHopLimit: Int = 2,
            blocked: List<String> = emptyList(),
        ) = parseAuctionOk(
            bytes = auctionFeedBytes(items = items.toList()),
            sourceHost = "a.beispiel.de",
            ownHopLimit = ownHopLimit,
            blocked = blocked,
        )

        val firstId = hexId("1")
        val goodItemJson =
            listOf(
                """{"originHost":"a.beispiel.de","hops":0,"maxHops":2,""",
                """"teaser":{"teaserId":"$firstId","revision":5,"title":"Fahrrad","currency":"EUR","priceKind":"CURRENT",""",
                """"priceMinor":2500,"offersPossible":false,"endsAt":"2026-11-26T10:00:00Z","priceAsOf":"2026-11-24T10:00:00Z"},""",
                """"via":[],"originSignature":"QUJD"}""",
            ).joinToString("")

        fun feedJson(
            items: String,
            extra: String = "",
        ) = """{"schemaVersion":1,"kind":"lapis-auction-teaser-feed","complete":true,"items":[$items],"withdrawn":[]$extra}"""

        test("the teaser key set is pinned to the serializer descriptor") {
            val d = AuctionTeaser.serializer().descriptor
            (0 until d.elementsCount).map { d.getElementName(it) }.toSet() shouldBe AuctionTeaserFeedReader.TEASER_KEYS
            AuctionTeaserFeedReader.TEASER_KEYS shouldHaveSize 10
            val i = AuctionTeaserFeedItem.serializer().descriptor
            (0 until i.elementsCount).map { i.getElementName(it) }.toSet() shouldBe AuctionTeaserFeedReader.ITEM_KEYS
        }

        test("a valid hop 0 item of the feed host and a valid relayed item are accepted with their wire fields") {
            val feed =
                parseAuctionOk(
                    bytes =
                        auctionFeedBytes(
                            items = listOf(a.item(seed = "1"), o.item(seed = "2", hops = 1, via = listOf("a.beispiel.de"))),
                        ),
                    sourceHost = "a.beispiel.de",
                )
            feed.items shouldHaveSize 2
            feed.rejected shouldBe 0
            feed.items.map { it.wireHops } shouldBe listOf(0, 1)
        }

        test("a field this server does not know is UNKNOWN_FIELD and never decoded: seller, IBAN, bid count (teaser) and a URL (item)") {
            listOf("sellerDisplayName", "iban", "bidCount").forEach { field ->
                val json = goodItemJson.replace("\"offersPossible\":false", "\"offersPossible\":false,\"$field\":\"x\"")
                val feed = ok(parse(feedJson(json)))
                feed.items shouldHaveSize 0
                feed.rejectedByReason[AuctionReceivedRejectReason.UNKNOWN_FIELD] shouldBe 1
            }
            val withUrl = goodItemJson.replace("\"via\":[]", "\"via\":[],\"url\":\"https://evil.example\"")
            ok(parse(feedJson(withUrl))).rejectedByReason[AuctionReceivedRejectReason.UNKNOWN_FIELD] shouldBe 1
        }

        test("the frame: unknown root key, wrong kind, a string version -> SCHEMA; bad UTF-8, deep nesting, 1001 items -> MALFORMED") {
            invalid(parse(feedJson("", ",\"extra\":1"))) shouldBe CarpoolPullErrorCode.SCHEMA
            invalid(parse(feedJson("").replace("lapis-auction-teaser-feed", "lapis-carpool-teaser-feed"))) shouldBe
                CarpoolPullErrorCode.SCHEMA
            invalid(parse(feedJson("").replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""))) shouldBe CarpoolPullErrorCode.SCHEMA
            AuctionTeaserFeedReader
                .parse(
                    bytes = byteArrayOf(0x7b, 0xC3.toByte(), 0x28, 0x7d),
                    sourceHost = "a.beispiel.de",
                    ownHost = AUCTION_PULL_OWN_HOST,
                    ownHopLimit = 2,
                    now = AUCTION_PULL_NOW_INSTANT,
                    blockPatterns = emptyList(),
                ).let { invalid(it) } shouldBe CarpoolPullErrorCode.MALFORMED
            invalid(parse("[".repeat(200) + "]".repeat(200))) shouldBe CarpoolPullErrorCode.MALFORMED
            invalid(parse(feedJson(List(1001) { "{}" }.joinToString(",")))) shouldBe CarpoolPullErrorCode.MALFORMED
        }

        test("one bad item does not void the others") {
            val good = a.item(seed = "9")
            val mixed =
                parseAuctionOk(
                    bytes = auctionFeedBytes(items = listOf(good, a.item(seed = "8", signature = ""))),
                    sourceHost = "a.beispiel.de",
                )
            mixed.items shouldHaveSize 1
            ok(parse(feedJson("""{"x":1},5"""))).rejected shouldBe 2
        }

        test("hop 0 must come from its own origin; a relayed item must carry the feed host as the last relay") {
            feedOf(o.item(seed = "1")).items shouldHaveSize 0
            feedOf(o.item(seed = "1", hops = 1, via = listOf("x.beispiel.de"))).items shouldHaveSize 0
            feedOf(o.item(seed = "1", hops = 1, via = listOf("a.beispiel.de"))).items shouldHaveSize 1
        }

        test("the hop arithmetic: wire hops 0..1, max hops 1..2, hops + 1 within max hops, and within OUR OWN limit") {
            feedOf(a.item(seed = "1", maxHops = 1)).items shouldHaveSize 1
            feedOf(a.item(seed = "1", maxHops = 3, signature = "AAAA")).items shouldHaveSize 0
            feedOf(a.item(seed = "1", maxHops = 0, signature = "AAAA")).items shouldHaveSize 0
            // hops 1 with max hops 1: would travel one hop too far
            feedOf(o.item(seed = "2", maxHops = 1, hops = 1, via = listOf("a.beispiel.de"))).items shouldHaveSize 0
            // hops 2 is beyond the limit of this exchange
            val two = feedOf(o.item(seed = "2", maxHops = 2, hops = 2, via = listOf("x.beispiel.de", "a.beispiel.de")))
            two.items shouldHaveSize 0
            two.rejectedByReason[AuctionReceivedRejectReason.HOP_LIMIT] shouldBe 1
            // with OUR limit 1 only the hints of direct origins stay; with 2 the relayed one is fine
            val relayed = o.item(seed = "3", hops = 1, via = listOf("a.beispiel.de"))
            feedOf(relayed, ownHopLimit = 1).items shouldHaveSize 0
            feedOf(relayed, ownHopLimit = 1).rejectedByReason[AuctionReceivedRejectReason.HOP_LIMIT] shouldBe 1
            feedOf(relayed, ownHopLimit = 2).items shouldHaveSize 1
            feedOf(a.item(seed = "4"), ownHopLimit = 1).items shouldHaveSize 1
        }

        test("loops: an item that names this server as origin or relay, or repeats a relay, is dropped") {
            val own = AuctionOriginServer(host = AUCTION_PULL_OWN_HOST)
            feedOf(own.item(seed = "1", hops = 1, via = listOf("a.beispiel.de"))).items shouldHaveSize 0
            feedOf(o.item(seed = "1", hops = 1, via = listOf(AUCTION_PULL_OWN_HOST))).items shouldHaveSize 0
            feedOf(o.item(seed = "1", hops = 2, via = listOf("a.beispiel.de", "a.beispiel.de"))).items shouldHaveSize 0
        }

        test("an item without the origin signature is SIGNATURE") {
            val unsigned = a.item(seed = "1", signature = "")
            val feed = parseAuctionOk(bytes = auctionFeedBytes(items = listOf(unsigned, a.item(seed = "2"))), sourceHost = "a.beispiel.de")
            feed.items shouldHaveSize 1
            feed.rejectedByReason[AuctionReceivedRejectReason.SIGNATURE] shouldBe 1
        }

        test("blocked: the origin or a relay on the block list") {
            feedOf(a.item(seed = "1"), blocked = listOf("a.beispiel.de")).items shouldHaveSize 0
            feedOf(a.item(seed = "1"), blocked = listOf("a.beispiel.de")).rejectedByReason[AuctionReceivedRejectReason.BLOCKED] shouldBe 1
            feedOf(o.item(seed = "1", hops = 1, via = listOf("a.beispiel.de")), blocked = listOf("a.beispiel.de")).items shouldHaveSize 0
        }

        test("content rules reach the reader: an LTR hint is CURRENCY, an item that ended is EXPIRED, a far end is END") {
            val ltr = a.teaser(seed = "1", currency = "LTR")
            feedOf(a.item(seed = "1", teaser = ltr, signature = "AAAA")).rejectedByReason[AuctionReceivedRejectReason.CURRENCY] shouldBe 1
            val ended =
                a.teaser(seed = "2").copy(
                    endsAt = AUCTION_PULL_NOW_INSTANT - 1.hours,
                    priceAsOf =
                        AUCTION_PULL_NOW_INSTANT - 2.hours,
                )
            feedOf(a.item(seed = "2", teaser = ended)).rejectedByReason[AuctionReceivedRejectReason.EXPIRED] shouldBe 1
            val far = a.teaser(seed = "3", endsIn = 24 * 91)
            feedOf(a.item(seed = "3", teaser = far)).rejectedByReason[AuctionReceivedRejectReason.END] shouldBe 1
        }

        test("a duplicate (origin, id) is taken once; withdrawals keep only ids with a readable day") {
            val feed = feedOf(a.item(seed = "1"), a.item(seed = "1"))
            feed.items shouldHaveSize 1
            val json =
                """{"schemaVersion":1,"kind":"lapis-auction-teaser-feed","complete":false,"items":[],"withdrawn":[""" +
                    """{"teaserId":"${hexId("w1")}","withdrawnAt":"2026-11-23"},{"teaserId":"${hexId("w2")}","withdrawnAt":"nonsense"},""" +
                    """{"teaserId":"short","withdrawnAt":"2026-11-23"},{"teaserId":"${hexId("w1")}","withdrawnAt":"2026-11-22"}]}"""
            ok(parse(json)).withdrawnIds shouldBe listOf(hexId("w1"))
        }
    })
