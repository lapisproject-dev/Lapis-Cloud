package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import network.lapis.cloud.shared.domain.PartnerFetchErrorCode
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal val PARSER_NOW: Instant = Instant.parse("2026-11-05T10:00:00Z")
internal const val PARSER_HOST = "partner.beispiel.de"
internal val PARSER_REMOTE_ID: Uuid = Uuid.parse("11111111-1111-1111-1111-111111111111")

/** One feed event as JSON; every field can be overridden by a raw JSON fragment. */
internal fun feedEventJson(
    id: String = "e1",
    title: String = "Treffen",
    startsAt: String = "2026-11-12T19:00:00+01:00",
    endsAt: String = "2026-11-12T21:00:00+01:00",
    url: String = "https://$PARSER_HOST/veranstaltung/treffen",
    kind: String = "event",
    extra: String = "",
): String =
    """{"kind":"$kind","id":"$id","title":${jsonString(title)},"startsAt":"$startsAt","endsAt":"$endsAt","timeZone":"Europe/Berlin",""" +
        """"online":false,"place":"Saal","summary":"Kurz","url":${jsonString(url)}$extra}"""

internal fun jsonString(raw: String): String =
    kotlinx.serialization.json
        .JsonPrimitive(raw)
        .toString()

internal fun feedJson(
    events: List<String> = listOf(feedEventJson()),
    serverId: Uuid = PARSER_REMOTE_ID,
    schemaVersion: Int = 1,
    partners: String = "[]",
    partnersHidden: Boolean = false,
    name: String = "Partner e.V.",
): ByteArray =
    (
        """{"schemaVersion":$schemaVersion,"kind":"lapis-partner-feed","serverId":"$serverId","name":${jsonString(name)},""" +
            """"generatedAt":"2026-11-05T10:00:00Z","events":[${events.joinToString(
                ",",
            )}],"partners":$partners,"partnersHidden":$partnersHidden,""" +
            """"future":{"x":1}}"""
    ).toByteArray()

class PartnerFeedParserTest :
    FunSpec({
        fun parse(
            bytes: ByteArray,
            own: Uuid = Uuid.parse("22222222-2222-2222-2222-222222222222"),
            known: Uuid? = null,
        ) = PartnerFeedParser.parse(bytes = bytes, partnerHost = PARSER_HOST, ownServerId = own, knownRemoteId = known, now = PARSER_NOW)

        fun ok(bytes: ByteArray) = parse(bytes).shouldBeInstanceOf<PartnerParseResult.Ok>()

        fun invalid(result: PartnerParseResult) = result.shouldBeInstanceOf<PartnerParseResult.Invalid>().code

        test("a valid feed is read; times become UTC, unknown fields are ignored") {
            val result = ok(feedJson())
            result.remoteServerId shouldBe PARSER_REMOTE_ID
            result.remoteName shouldBe "Partner e.V."
            result.events shouldHaveSize 1
            val event = result.events.single()
            event.startsAt.toString() shouldBe "2026-11-12T18:00"
            event.endsAt.toString() shouldBe "2026-11-12T20:00"
            event.timeZone shouldBe "Europe/Berlin"
            event.place shouldBe "Saal"
        }

        test("schemaVersion 2 or a wrong kind is invalid") {
            invalid(parse(feedJson(schemaVersion = 2))) shouldBe PartnerFetchErrorCode.INVALID_FEED
            invalid(parse(String(feedJson()).replace("lapis-partner-feed", "other").toByteArray())) shouldBe
                PartnerFetchErrorCode.INVALID_FEED
        }

        test("our own server id is OWN_SERVER_ID, a changed id is SERVER_ID_CHANGED") {
            invalid(parse(feedJson(), own = PARSER_REMOTE_ID)) shouldBe PartnerFetchErrorCode.OWN_SERVER_ID
            invalid(parse(feedJson(), known = Uuid.random())) shouldBe PartnerFetchErrorCode.SERVER_ID_CHANGED
            parse(feedJson(), known = PARSER_REMOTE_ID).shouldBeInstanceOf<PartnerParseResult.Ok>()
        }

        test("an event URL on a foreign host, over http, with user info or on another port drops the event, not the feed") {
            val events =
                listOf(
                    feedEventJson(id = "good"),
                    feedEventJson(id = "foreign", url = "https://evil.example.org/x"),
                    feedEventJson(id = "http", url = "http://$PARSER_HOST/x"),
                    feedEventJson(id = "userinfo", url = "https://u:p@$PARSER_HOST/x"),
                    feedEventJson(id = "port", url = "https://$PARSER_HOST:8443/x"),
                    feedEventJson(id = "lookalike", url = "https://$PARSER_HOST.evil.org/x"),
                )
            val result = ok(feedJson(events = events))
            result.events.map { it.id } shouldBe listOf("good")
            result.droppedEvents shouldBe 5
        }

        test("HTML and bidi characters in the title are cleaned, HTML stays literal") {
            val result = ok(feedJson(events = listOf(feedEventJson(title = "<b>Fett</b>‮ x"))))
            result.events.single().title shouldBe "<b>Fett</b> x"
        }

        test("600 events are cut to 200 and the rest is counted") {
            val events = (1..600).map { feedEventJson(id = "e$it", startsAt = "2026-11-12T19:00:00+01:00") }
            val result = ok(feedJson(events = events))
            result.events shouldHaveSize 200
            result.droppedEvents shouldBe 400
        }

        test("an absurdly deep document is refused before the JSON parser sees it") {
            val deep = "[".repeat(10_000).toByteArray()
            invalid(parse(deep)) shouldBe PartnerFetchErrorCode.INVALID_FEED
            PartnerFeedParser.exceedsDepth("[".repeat(PartnerFeedParser.MAX_JSON_DEPTH + 1)) shouldBe true
            PartnerFeedParser.exceedsDepth("[".repeat(PartnerFeedParser.MAX_JSON_DEPTH)) shouldBe false
            PartnerFeedParser.exceedsDepth("\"[[[[[[[[[[[[[[[[[[[[[[[[[\"") shouldBe false
        }

        test("invalid UTF-8 is invalid") {
            invalid(parse(byteArrayOf(0xC3.toByte(), 0x28))) shouldBe PartnerFetchErrorCode.INVALID_FEED
        }

        test("times: too far ahead, ends before starts, too long, in the past are dropped") {
            val far = (PARSER_NOW + 400.days).toString()
            val events =
                listOf(
                    feedEventJson(id = "ok"),
                    feedEventJson(id = "far", startsAt = far, endsAt = far),
                    feedEventJson(id = "reversed", startsAt = "2026-11-12T21:00:00+01:00", endsAt = "2026-11-12T19:00:00+01:00"),
                    feedEventJson(id = "long", startsAt = "2026-11-12T19:00:00+01:00", endsAt = "2026-12-30T19:00:00+01:00"),
                    feedEventJson(id = "past", startsAt = "2026-10-01T19:00:00+01:00", endsAt = "2026-10-01T20:00:00+01:00"),
                    feedEventJson(id = "garbage", startsAt = "tomorrow", endsAt = "later"),
                )
            ok(feedJson(events = events)).events.map { it.id } shouldBe listOf("ok")
        }

        test("duplicate ids, bad ids and unknown kinds") {
            val events =
                listOf(
                    feedEventJson(id = "a"),
                    feedEventJson(id = "a"),
                    feedEventJson(id = "bad id!"),
                    feedEventJson(id = "x", kind = "workshop"),
                )
            ok(feedJson(events = events)).events.map { it.id } shouldBe listOf("a")
        }

        test("the partner list is stored cleaned and compact, never followed; hidden means null") {
            val partners =
                """[{"kind":"partner","host":"Dritter.Beispiel.de","name":"Dritter"},""" +
                    """{"kind":"partner","host":"127.0.0.1","name":"Intern"},{"kind":"other","host":"x.de","name":"x"}]"""
            val json = ok(feedJson(partners = partners)).partnersJson!!
            json shouldContain "dritter.beispiel.de"
            json shouldNotContain "127.0.0.1"
            ok(feedJson(partners = partners, partnersHidden = true)).partnersJson shouldBe null
            ok(feedJson()).partnersJson shouldBe null
        }
    })
