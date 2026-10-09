package network.lapis.cloud.server.events

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.events.EventImportPolicy.EntryCheck
import network.lapis.cloud.shared.rpc.BadRequestException

/** Welle V1.9.82 -- pure validation of the import payload (no database). */
class EventImportPolicyTest :
    FunSpec({
        val wallNow = LocalDateTime(2026, 10, 9, 12, 0)

        fun entry(
            slug: String = "stammtisch-berlin",
            extra: String = "",
            startsAt: String = "2025-03-01T19:00",
            endsAt: String? = "2025-03-01T22:00",
            description: String = "Ein Abend.",
            location: String? = "Berlin",
        ): String {
            val parts =
                mutableListOf(
                    """"slug":"$slug"""",
                    """"title":"Stammtisch"""",
                    """"description":"$description"""",
                    """"startsAt":"$startsAt"""",
                )
            if (endsAt != null) parts += """"endsAt":"$endsAt""""
            if (location != null) parts += """"locationText":"$location""""
            if (extra.isNotBlank()) parts += extra
            return "{${parts.joinToString(",")}}"
        }

        fun parse(vararg entries: String) = EventImportPolicy.parse(json = "[${entries.joinToString(",")}]", wallNow = wallNow)

        fun invalid(check: EntryCheck): EntryCheck.Invalid = check.shouldBeInstanceOf<EntryCheck.Invalid>()

        test("a well-formed entry is valid, times stay exactly as typed") {
            val check = parse(entry()).single().shouldBeInstanceOf<EntryCheck.Valid>()
            check.entry.slug shouldBe "stammtisch-berlin"
            check.entry.startsAt shouldBe LocalDateTime(2025, 3, 1, 19, 0)
            check.entry.endsAt shouldBe LocalDateTime(2025, 3, 1, 22, 0)
            check.entry.onlineUrlPublic shouldBe false
            check.hints shouldBe emptyList()
        }

        test("daylight saving gap and overlap times are accepted and kept exactly as typed (class B, nothing is converted)") {
            val gap =
                parse(
                    entry(startsAt = "2025-03-30T02:30", endsAt = "2025-03-30T03:30"),
                ).single().shouldBeInstanceOf<EntryCheck.Valid>()
            gap.entry.startsAt shouldBe LocalDateTime(2025, 3, 30, 2, 30)
            val overlap =
                parse(
                    entry(startsAt = "2025-10-26T02:30", endsAt = "2025-10-26T03:30"),
                ).single().shouldBeInstanceOf<EntryCheck.Valid>()
            overlap.entry.startsAt shouldBe LocalDateTime(2025, 10, 26, 2, 30)
        }

        test("a missing end equals the start and raises a hint") {
            val check = parse(entry(endsAt = null)).single().shouldBeInstanceOf<EntryCheck.Valid>()
            check.entry.endsAt shouldBe check.entry.startsAt
            check.hints shouldContain "Ende fehlt, gleich Beginn gesetzt."
        }

        test("top level: not JSON, not an array, too many entries, too large") {
            shouldThrow<BadRequestException> { EventImportPolicy.parse(json = "{", wallNow = wallNow) }
            shouldThrow<BadRequestException> { EventImportPolicy.parse(json = """{"a":1}""", wallNow = wallNow) }
            shouldThrow<BadRequestException> {
                EventImportPolicy.parse(
                    json = "[" + List(201) { "{}" }.joinToString(",") + "]",
                    wallNow = wallNow,
                )
            }
            EventImportPolicy.parse(json = "[" + List(200) { "{}" }.joinToString(",") + "]", wallNow = wallNow).size shouldBe 200
            val big = "[\"" + "a".repeat(EventImportPolicy.MAX_PAYLOAD_BYTES) + "\"]"
            shouldThrow<BadRequestException> { EventImportPolicy.parse(json = big, wallNow = wallNow) }
        }

        test("deeply nested input is refused before parsing, normal nesting and brackets inside strings are fine") {
            shouldThrow<BadRequestException> { EventImportPolicy.parse(json = "[".repeat(100_000), wallNow = wallNow) }
            shouldThrow<BadRequestException> { EventImportPolicy.parse(json = """[{"a":[[1]]}]""", wallNow = wallNow) }
            EventImportPolicy.exceedsNestingDepth("""[{"a":"[[[[[[ {{{{ \" ]]]]"}]""") shouldBe false
            invalid(EventImportPolicy.parse(json = """[{"slug":"a","title":["x"]}]""", wallNow = wallNow).single())
        }

        test("an element that is not an object is an invalid entry, not a crash") {
            invalid(EventImportPolicy.parse(json = "[1]", wallNow = wallNow).single()).reasons.size shouldBe 1
        }

        test("unknown and privileged keys are rejected (mass assignment)") {
            for (key in listOf("status", "visibility", "feeAmount", "createdBy", "imported", "capacity", "id")) {
                val check = invalid(parse(entry(extra = """"$key":"x"""")).single())
                check.reasons.any { it.contains(key) } shouldBe true
            }
        }

        test("an unknown key with unsafe characters is not echoed back") {
            val check = invalid(parse(entry(extra = """"<script>alert(1)</script>":1""")).single())
            check.reasons.joinToString() shouldNotContain "script"
        }

        test("wrong types are rejected") {
            invalid(parse(entry(extra = """"summary":5""")).single())
            invalid(parse(entry(extra = """"onlineUrlPublic":"yes"""")).single())
            invalid(parse("""{"slug":1,"title":"t","description":"d","startsAt":"2025-03-01T19:00","locationText":"x"}""").single())
        }

        test("required fields: slug, title, description, startsAt and a place or an online link") {
            invalid(parse("""{"title":"t","description":"d","startsAt":"2025-03-01T19:00","locationText":"x"}""").single())
            invalid(parse("""{"slug":"a","description":"d","startsAt":"2025-03-01T19:00","locationText":"x"}""").single())
            invalid(parse("""{"slug":"a","title":"t","startsAt":"2025-03-01T19:00","locationText":"x"}""").single())
            invalid(parse("""{"slug":"a","title":"t","description":"d","locationText":"x"}""").single())
            invalid(parse(entry(location = null)).single()).reasons.any { it.contains("Ort oder Online-Link") } shouldBe true
            parse(entry(location = null, extra = """"onlineUrl":"https://example.org/z"""")).single().shouldBeInstanceOf<EntryCheck.Valid>()
        }

        test("an empty description or title after normalization is rejected") {
            invalid(parse(entry(description = " \\n ")).single())
        }

        test("slug: pattern and length") {
            for (bad in listOf("Gross", "a_b", "a b", "-a", "a-", "a--b", "ä")) {
                invalid(parse(entry(slug = bad)).single())
            }
            parse(entry(slug = "a".repeat(120))).single().shouldBeInstanceOf<EntryCheck.Valid>()
            invalid(parse(entry(slug = "a".repeat(121))).single())
        }

        test("time format: offset, Z, seconds and space separator are rejected") {
            for (bad in listOf(
                "2025-03-01T19:00Z",
                "2025-03-01T19:00+01:00",
                "2025-03-01T19:00:00",
                "2025-03-01 19:00",
                "01.03.2025 19:00",
            )) {
                invalid(parse(entry(startsAt = bad, endsAt = null)).single())
            }
        }

        test("a date that does not exist is rejected") {
            invalid(parse(entry(startsAt = "2025-02-30T10:00", endsAt = null)).single())
            invalid(parse(entry(startsAt = "2025-13-01T10:00", endsAt = null)).single())
        }

        test("start after end is rejected") {
            invalid(parse(entry(startsAt = "2025-03-01T22:00", endsAt = "2025-03-01T19:00")).single())
        }

        test("an event that is not over yet is rejected, one ending exactly now is accepted") {
            val future = invalid(parse(entry(startsAt = "2026-10-10T10:00", endsAt = "2026-10-10T12:00")).single())
            future.reasons.any { it.contains("vergangene") } shouldBe true
            invalid(parse(entry(startsAt = "2026-10-09T11:00", endsAt = "2026-10-09T12:01")).single())
            parse(entry(startsAt = "2026-10-09T11:00", endsAt = "2026-10-09T12:00")).single().shouldBeInstanceOf<EntryCheck.Valid>()
            // missing end -> end == start -> a start in the future is rejected as well
            invalid(parse(entry(startsAt = "2026-10-10T10:00", endsAt = null)).single())
        }

        test("online link: https only, and public only with a valid https link") {
            invalid(parse(entry(extra = """"onlineUrl":"http://example.org"""")).single())
            invalid(parse(entry(extra = """"onlineUrl":"javascript:alert(1)"""")).single())
            invalid(parse(entry(extra = """"onlineUrlPublic":true""")).single())
            invalid(parse(entry(extra = """"onlineUrl":"http://example.org","onlineUrlPublic":true""")).single())
            val ok = parse(entry(extra = """"onlineUrl":"https://example.org/z","onlineUrlPublic":true""")).single()
            ok.shouldBeInstanceOf<EntryCheck.Valid>().entry.onlineUrlPublic shouldBe true
        }

        test("the same slug twice in one payload: the second occurrence is an error") {
            val checks = parse(entry(slug = "dup"), entry(slug = "dup"))
            checks[0].shouldBeInstanceOf<EntryCheck.Valid>()
            invalid(checks[1])
        }

        test("control characters are stripped and HTML-like text only raises a hint") {
            val check =
                parse(entry(description = "a\\u0000b \\u202E <b>fett</b>")).single().shouldBeInstanceOf<EntryCheck.Valid>()
            check.entry.description shouldBe "ab  <b>fett</b>"
            check.hints shouldContain "Enthält HTML-ähnliche Zeichen, wird als Text angezeigt."
        }

        test("length limits apply to the stored form") {
            invalid(parse(entry(extra = """"summary":"${"a".repeat(301)}"""")).single())
            parse(entry(extra = """"summary":"${"a".repeat(300)}"""")).single().shouldBeInstanceOf<EntryCheck.Valid>()
            invalid(parse(entry(extra = """"coverImageAlt":"${"a".repeat(501)}"""")).single())
            invalid(parse(entry(description = "a".repeat(8001))).single())
        }

        test("error texts never contain payload content") {
            val secret = "GEHEIMER-TITEL-XYZ"
            val check =
                invalid(
                    parse("""{"slug":"BAD SLUG","title":"$secret","description":"d","startsAt":"x","locationText":"y"}""").single(),
                )
            check.reasons.joinToString() shouldNotContain secret
            check.reasons.joinToString() shouldNotContain "BAD SLUG"
        }

        test("sha256Hex is stable, hex and sensitive to a single character") {
            val a = EventImportPolicy.sha256Hex("[]")
            a shouldBe EventImportPolicy.sha256Hex("[]")
            a.length shouldBe 64
            (a == EventImportPolicy.sha256Hex("[] ")) shouldBe false
            EventImportPolicy.sha256Hex("") shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        }
    })
