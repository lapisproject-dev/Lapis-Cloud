package network.lapis.cloud.server.carpool.pull

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.CarpoolPullErrorCode
import network.lapis.cloud.shared.domain.CarpoolReceivedRejectReason

/** V1.9.314 W4 -- the reader of a foreign teaser feed: pure, every item on its own, strict, bounded. */
class CarpoolTeaserFeedReaderTest :
    FunSpec({
        val a = OriginServer(host = "a.beispiel.de")
        val o = OriginServer(host = "o.beispiel.de")

        fun parse(
            text: String,
            source: String = "a.beispiel.de",
            blocked: List<String> = emptyList(),
        ) = CarpoolTeaserFeedReader.parse(
            bytes = text.toByteArray(),
            sourceHost = source,
            ownHost = PULL_OWN_HOST,
            today = PULL_TODAY,
            blockPatterns = blocked,
        )

        fun invalid(r: TeaserFeedParse) = r.shouldBeInstanceOf<TeaserFeedParse.Invalid>().code

        fun ok(r: TeaserFeedParse) = r.shouldBeInstanceOf<TeaserFeedParse.Ok>().feed

        fun feedOf(vararg items: network.lapis.cloud.server.carpool.feed.CarpoolTeaserFeedItem) =
            parseOk(bytes = feedBytes(items = items.toList()), sourceHost = "a.beispiel.de")

        test("a valid hop 0 item of the feed host and a valid relayed item are accepted with their wire fields") {
            val feed =
                parseOk(
                    bytes = feedBytes(items = listOf(a.item(seed = "1"), o.item(seed = "2", hops = 1, via = listOf("a.beispiel.de")))),
                    sourceHost = "a.beispiel.de",
                )
            feed.items shouldHaveSize 2
            feed.rejected shouldBe 0
            feed.items.map { it.wireHops } shouldBe listOf(0, 1)
        }

        test("hop 0 must come from its own origin; a relayed item must carry the feed host as the last relay") {
            feedOf(o.item(seed = "1")).items shouldHaveSize 0
            feedOf(o.item(seed = "1", hops = 1, via = listOf("x.beispiel.de"))).items shouldHaveSize 0
            feedOf(o.item(seed = "1", hops = 1, via = listOf("a.beispiel.de"))).items shouldHaveSize 1
        }

        test("the hop arithmetic: wire hops 0..2, max hops 1..3, hops + 1 within max hops, via as long as hops") {
            feedOf(a.item(seed = "1", maxHops = 1)).items shouldHaveSize 1
            feedOf(a.item(seed = "1", maxHops = 4, signature = "AAAA")).items shouldHaveSize 0
            feedOf(a.item(seed = "1", maxHops = 0, signature = "AAAA")).items shouldHaveSize 0
            feedOf(o.item(seed = "2", maxHops = 1, hops = 1, via = listOf("a.beispiel.de"))).items shouldHaveSize 0
            feedOf(
                o.item(seed = "2", maxHops = 3, hops = 3, via = listOf("x.beispiel.de", "y.beispiel.de", "a.beispiel.de")),
            ).items shouldHaveSize
                0
            feedOf(o.item(seed = "2", maxHops = 3, hops = 2, via = listOf("a.beispiel.de"))).items shouldHaveSize 0
        }

        test("loops: an item that names this server as origin or relay, or repeats a relay, is dropped") {
            val own = OriginServer(host = PULL_OWN_HOST)
            feedOf(own.item(seed = "1", hops = 1, via = listOf("a.beispiel.de"))).items shouldHaveSize 0
            feedOf(o.item(seed = "1", maxHops = 3, hops = 2, via = listOf(PULL_OWN_HOST, "a.beispiel.de"))).items shouldHaveSize 0
            feedOf(o.item(seed = "1", maxHops = 3, hops = 2, via = listOf("a.beispiel.de", "a.beispiel.de"))).items shouldHaveSize 0
            feedOf(o.item(seed = "1", maxHops = 3, hops = 2, via = listOf("o.beispiel.de", "a.beispiel.de"))).items shouldHaveSize 0
        }

        test("an item without the origin signature is dropped and counted as unsigned (a wave 2/3 origin)") {
            val unsigned = a.item(seed = "1").copy(originSignature = null)
            val feed = parseOk(bytes = feedBytes(items = listOf(unsigned, a.item(seed = "2"))), sourceHost = "a.beispiel.de")
            feed.items shouldHaveSize 1
            feed.rejectedUnsigned shouldBe 1
            feed.rejected shouldBe 1
        }

        test("one bad item never voids the rest: bad id, bad area, bad date, bad host, bad signature alphabet") {
            val good = a.item(seed = "ok")
            val badArea = """{"originHost":"a.beispiel.de","hops":0,"maxHops":2,"teaser":{"teaserId":"${"c".repeat(
                64,
            )}","departureDate":"2026-11-24","fromPostcodeArea":"<b","toPostcodeArea":"10"},"originSignature":"AAAA"}"""
            val badId = a.item(seed = "x").let { it.copy(teaser = it.teaser.copy(teaserId = "ZZ" + "c".repeat(62))) }
            val past = a.item(seed = "p", date = LocalDate(2026, 11, 1))
            val far = a.item(seed = "f", date = LocalDate(2027, 6, 1))
            val badHost = a.item(seed = "h").copy(originHost = "A.Beispiel.de")
            val badSig = a.item(seed = "s", signature = "!!!!")
            val text =
                """{"schemaVersion":1,"kind":"lapis-carpool-teaser-feed","complete":true,"items":[$badArea,${
                    listOf(badId, past, far, badHost, badSig, good).joinToString(",") {
                        network.lapis.cloud.server.carpool.feed.CARPOOL_TEASER_FEED_JSON
                            .encodeToString(
                                network.lapis.cloud.server.carpool.feed.CarpoolTeaserFeedItem
                                    .serializer(),
                                it,
                            )
                    }
                }],"withdrawn":[]}"""
            val feed = ok(parse(text))
            feed.items.map { it.teaser.teaserId } shouldBe listOf(good.teaser.teaserId)
            feed.rejected shouldBe 6
        }

        test("a blocked origin or relay is dropped") {
            feedOf(a.item(seed = "1")).items shouldHaveSize 1
            parseOk(
                bytes = feedBytes(items = listOf(a.item(seed = "1"))),
                sourceHost = "a.beispiel.de",
                blocked = listOf("*.beispiel.de"),
            ).items shouldHaveSize
                0
        }

        test("the envelope: wrong kind or version is SCHEMA, non-JSON, wrong shape and lists over 1000 are MALFORMED") {
            invalid(parse("""{"schemaVersion":2,"kind":"lapis-carpool-teaser-feed","complete":true,"items":[]}""")) shouldBe
                CarpoolPullErrorCode.SCHEMA
            invalid(parse("""{"schemaVersion":1,"kind":"other","complete":true,"items":[]}""")) shouldBe CarpoolPullErrorCode.SCHEMA
            invalid(parse("not json")) shouldBe CarpoolPullErrorCode.MALFORMED
            invalid(parse("[]")) shouldBe CarpoolPullErrorCode.MALFORMED
            invalid(parse("""{"schemaVersion":1,"kind":"lapis-carpool-teaser-feed","complete":true,"items":"x"}""")) shouldBe
                CarpoolPullErrorCode.MALFORMED
            invalid(parse("""{"schemaVersion":1,"kind":"lapis-carpool-teaser-feed","items":[]}""")) shouldBe CarpoolPullErrorCode.MALFORMED
            invalid(parse("""{"schemaVersion":1,"kind":"lapis-carpool-teaser-feed","complete":true,"items":[],"withdrawn":5}""")) shouldBe
                CarpoolPullErrorCode.MALFORMED
            val many = (1..1001).joinToString(",") { "{}" }
            invalid(parse("""{"schemaVersion":1,"kind":"lapis-carpool-teaser-feed","complete":true,"items":[$many]}""")) shouldBe
                CarpoolPullErrorCode.MALFORMED
            invalid(
                parse("""{"schemaVersion":1,"kind":"lapis-carpool-teaser-feed","complete":true,"items":[],"withdrawn":[$many]}"""),
            ) shouldBe
                CarpoolPullErrorCode.MALFORMED
        }

        test("a deeply nested body and invalid UTF-8 are MALFORMED before the JSON parser sees them") {
            invalid(parse("[".repeat(5000) + "]".repeat(5000))) shouldBe CarpoolPullErrorCode.MALFORMED
            invalid(
                CarpoolTeaserFeedReader.parse(
                    bytes = byteArrayOf(0x7b, 0xC3.toByte(), 0x28, 0x7d),
                    sourceHost = "a.beispiel.de",
                    ownHost = PULL_OWN_HOST,
                    today = PULL_TODAY,
                    blockPatterns = emptyList(),
                ),
            ) shouldBe
                CarpoolPullErrorCode.MALFORMED
        }

        test("withdrawals: only 64 hex ids survive, duplicates once; a W3 origin's feed (no signatures) still delivers its withdrawals") {
            val id = hexId("w")
            val feed =
                parseOk(
                    bytes = feedBytes(items = emptyList(), withdrawn = listOf(id, id, "short", "G".repeat(64))),
                    sourceHost = "a.beispiel.de",
                )
            feed.withdrawnIds shouldBe listOf(id)
            val w3 =
                """{"schemaVersion":1,"kind":"lapis-carpool-teaser-feed","complete":true,"items":[{"originHost":"a.beispiel.de","hops":0,"maxHops":2,"teaser":{"teaserId":"${"d".repeat(
                    64,
                )}","departureDate":"2026-11-24","fromPostcodeArea":"38","toPostcodeArea":"10"}}],"withdrawn":[{"teaserId":"$id","withdrawnAt":"2026-11-19"}]}"""
            val parsed = ok(parse(w3))
            parsed.items shouldHaveSize 0
            parsed.rejectedUnsigned shouldBe 1
            parsed.withdrawnIds shouldBe listOf(id)
        }

        test("a duplicate (origin, id) inside one answer counts once") {
            val one = a.item(seed = "dup")
            parseOk(bytes = feedBytes(items = listOf(one, one)), sourceHost = "a.beispiel.de").items shouldHaveSize 1
        }

        test("markup and control characters in any field never pass: nothing is kept as text") {
            val evil = a.item(seed = "e").let { it.copy(originHost = "a.beispiel.de<script>") }
            feedOf(evil).items shouldHaveSize 0
        }

        test("V1.9.318: every rejected item is counted under ONE cause, in the fixed order of the checks; the causes add up to the total") {
            val own = OriginServer(host = PULL_OWN_HOST)
            val yesterday = LocalDate(2026, 11, 18)
            val feed =
                feedOf(
                    a.item(seed = "ok"),
                    // FORMAT: a signature that is no base64, and a path of the wrong length
                    a.item(seed = "f1", signature = "not base64!!"),
                    o.item(seed = "f2", hops = 1, via = emptyList()),
                    // SIGNATURE: missing
                    a.item(seed = "s1", signature = " "),
                    // BLOCKED: this server as origin
                    own.item(seed = "b1", hops = 1, via = listOf("a.beispiel.de")),
                    // HOP_LIMIT: above the maximum, and no room left
                    a.item(seed = "h1", maxHops = 4, signature = "AAAA"),
                    o.item(seed = "h2", maxHops = 1, hops = 1, via = listOf("a.beispiel.de")),
                    // EXPIRED
                    a.item(seed = "e1", date = yesterday),
                    // several faults: the first check wins (FORMAT before SIGNATURE before BLOCKED before HOP_LIMIT before EXPIRED)
                    a.item(seed = "m1", date = yesterday, maxHops = 4, signature = "not base64!!"),
                    a.item(seed = "m2", date = yesterday, signature = " "),
                    own.item(seed = "m3", date = yesterday, hops = 1, via = listOf("a.beispiel.de")),
                    a.item(seed = "m4", date = yesterday, maxHops = 4, signature = "AAAA"),
                )
            feed.items shouldHaveSize 1
            feed.rejected shouldBe 11
            feed.rejectedByReason[CarpoolReceivedRejectReason.FORMAT] shouldBe 3
            feed.rejectedByReason[CarpoolReceivedRejectReason.SIGNATURE] shouldBe 2
            feed.rejectedByReason[CarpoolReceivedRejectReason.BLOCKED] shouldBe 2
            feed.rejectedByReason[CarpoolReceivedRejectReason.HOP_LIMIT] shouldBe 3
            feed.rejectedByReason[CarpoolReceivedRejectReason.EXPIRED] shouldBe 1
            feed.rejectedByReason.values.sum() shouldBe feed.rejected
            feed.rejectedUnsigned shouldBe 2
        }

        test("V1.9.318: a blocked origin or a blocked relay is BLOCKED, an item that cannot be decoded is FORMAT") {
            val blocked =
                parseOk(
                    bytes =
                        feedBytes(
                            items =
                                listOf(
                                    o.item(seed = "1", hops = 1, via = listOf("a.beispiel.de")),
                                    a.item(seed = "2"),
                                ),
                        ),
                    sourceHost = "a.beispiel.de",
                    blocked = listOf("o.beispiel.de", "a.beispiel.de"),
                )
            blocked.rejectedByReason[CarpoolReceivedRejectReason.BLOCKED] shouldBe 2
            val text =
                """{"schemaVersion":1,"kind":"lapis-carpool-teaser-feed","complete":true,"items":[{"nonsense":1}],"withdrawn":[]}"""
            val undecodable = ok(parse(text))
            undecodable.rejected shouldBe 1
            undecodable.rejectedByReason[CarpoolReceivedRejectReason.FORMAT] shouldBe 1
        }
    })
