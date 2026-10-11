package network.lapis.cloud.server.articles

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.articles.ArticleImportPolicy.EntryCheck
import network.lapis.cloud.shared.rpc.BadRequestException

/** Welle V1.9.105 -- pure validation of the article import payload (no database). */
class ArticleImportPolicyTest :
    FunSpec({
        val wallNow = LocalDateTime(2026, 10, 11, 12, 0)

        fun entry(
            slug: String = "parteitag-bericht",
            title: String = "Bericht vom Parteitag",
            excerpt: String = "Kurzer Teaser.",
            body: String = "Erster Absatz.  \nZweite Zeile.",
            publishedAt: String? = "2024-05-05",
            byline: String? = "PdV",
            extra: String = "",
        ): String {
            val parts = mutableListOf<String>()
            parts += """"slug":"$slug""""
            parts += """"title":"$title""""
            parts += """"excerpt":"$excerpt""""
            parts += """"body":"${body.replace("\n", "\\n")}""""
            if (publishedAt != null) parts += """"publishedAt":"$publishedAt""""
            if (byline != null) parts += """"byline":"$byline""""
            if (extra.isNotBlank()) parts += extra
            return "{${parts.joinToString(",")}}"
        }

        fun parse(
            vararg entries: String,
            labels: List<String> = listOf("PdV"),
        ) = ArticleImportPolicy.parse(json = "[${entries.joinToString(",")}]", wallNow = wallNow, organizationLabels = labels)

        fun invalid(check: EntryCheck): EntryCheck.Invalid = check.shouldBeInstanceOf<EntryCheck.Invalid>()

        test("a well-formed entry is valid; a date-only publishedAt means 00:00 organization time; Markdown hard line breaks survive") {
            val check = parse(entry()).single().shouldBeInstanceOf<EntryCheck.Valid>()
            check.entry.slug shouldBe "parteitag-bericht"
            check.entry.publishedAtWall shouldBe LocalDateTime(2024, 5, 5, 0, 0)
            check.entry.byline shouldBe "PdV"
            check.entry.body shouldBe "Erster Absatz.  \nZweite Zeile."
        }

        test("publishedAt with a time is accepted as typed") {
            val check = parse(entry(publishedAt = "2024-05-05T19:30")).single().shouldBeInstanceOf<EntryCheck.Valid>()
            check.entry.publishedAtWall shouldBe LocalDateTime(2024, 5, 5, 19, 30)
        }

        test(
            "publishedAt: future, offset, Z, seconds, wrong shape and impossible dates are errors; the moment 'now' and the past are fine",
        ) {
            for (bad in listOf(
                "2026-10-11T12:01",
                "2099-01-01",
                "2024-05-05T10:00+02:00",
                "2024-05-05T10:00Z",
                "2024-05-05T10:00:00",
                "2024-5-5",
                "2024-02-30",
                "2024-13-01",
                "05.05.2024",
                "",
            )) {
                invalid(parse(entry(publishedAt = bad)).single()).reasons.isNotEmpty() shouldBe true
            }
            parse(entry(publishedAt = "2026-10-11T12:00")).single().shouldBeInstanceOf<EntryCheck.Valid>()
            parse(entry(publishedAt = "2026-10-11")).single().shouldBeInstanceOf<EntryCheck.Valid>()
            invalid(parse(entry(publishedAt = null)).single()).reasons.any { it.contains("publishedAt") } shouldBe true
        }

        test("limits: excerpt 1..300, title 5..140, body up to 100000, byline up to 120") {
            parse(entry(excerpt = "x".repeat(300))).single().shouldBeInstanceOf<EntryCheck.Valid>()
            invalid(parse(entry(excerpt = "x".repeat(301))).single())
            invalid(parse(entry(excerpt = "")).single())
            invalid(parse(entry(title = "Kurz")).single())
            invalid(parse(entry(title = "x".repeat(141))).single())
            parse(entry(body = "x".repeat(100_000))).single().shouldBeInstanceOf<EntryCheck.Valid>()
            invalid(parse(entry(body = "x".repeat(100_001))).single())
            parse(entry(byline = "x".repeat(120))).single().shouldBeInstanceOf<EntryCheck.Valid>()
            invalid(parse(entry(byline = "x".repeat(121))).single())
        }

        test("an overlong excerpt is rejected, never silently shortened") {
            val result = invalid(parse(entry(excerpt = "x".repeat(440))).single())
            result.reasons.any { it.contains("Teaser") } shouldBe true
        }

        test("unknown keys are errors -- coverImage, image, status, authorId, imported (mass assignment, no image fetch)") {
            for (key in listOf("coverImage", "image", "status", "authorId", "imported", "slugs")) {
                val result = invalid(parse(entry(extra = """"$key":"x"""")).single())
                result.reasons.any { it.contains("Unbekanntes Feld") } shouldBe true
            }
        }

        test("an unusual key name is not echoed back") {
            val result = invalid(parse(entry(extra = """"<script>":"x"""")).single())
            result.reasons.joinToString() shouldNotContain "<script>"
        }

        test("slugs: hyphen and underscore separators are fine, everything else is not") {
            parse(entry(slug = "dankeschoen_eu-wahl")).single().shouldBeInstanceOf<EntryCheck.Valid>()
            for (bad in listOf("Gross", "mit leerzeichen", "-anfang", "ende-", "doppel--strich", "a__b", "umlaut-ä", "x".repeat(121))) {
                invalid(parse(entry(slug = bad)).single())
            }
        }

        test("a slug that occurs twice in the file is an error on the second occurrence") {
            val checks = parse(entry(slug = "doppelt"), entry(slug = "doppelt"))
            checks[0].shouldBeInstanceOf<EntryCheck.Valid>()
            invalid(checks[1]).reasons.any { it.contains("mehrfach") } shouldBe true
        }

        test("wrong types and non-objects are reported per entry") {
            invalid(ArticleImportPolicy.parse(json = """[{"slug":1}]""", wallNow = wallNow).single())
            invalid(ArticleImportPolicy.parse(json = """["text"]""", wallNow = wallNow).single())
            invalid(ArticleImportPolicy.parse(json = """[{"slug":["a"]}]""", wallNow = wallNow).single())
        }

        test("top level: not JSON, not an array, more than 100 entries, more than 2 MiB, nesting deeper than 3") {
            shouldThrow<BadRequestException> { ArticleImportPolicy.parse(json = "{", wallNow = wallNow) }
            shouldThrow<BadRequestException> { ArticleImportPolicy.parse(json = """{"a":1}""", wallNow = wallNow) }
            shouldThrow<BadRequestException> {
                ArticleImportPolicy.parse(json = "[" + List(101) { "{}" }.joinToString(",") + "]", wallNow = wallNow)
            }
            ArticleImportPolicy.parse(json = "[" + List(100) { "{}" }.joinToString(",") + "]", wallNow = wallNow).size shouldBe 100
            shouldThrow<BadRequestException> {
                ArticleImportPolicy.parse(json = "[\"" + "x".repeat(ArticleImportPolicy.MAX_PAYLOAD_BYTES) + "\"]", wallNow = wallNow)
            }
            shouldThrow<BadRequestException> { ArticleImportPolicy.parse(json = """[[[[1]]]]""", wallNow = wallNow) }
            shouldThrow<BadRequestException> { ArticleImportPolicy.parse(json = "[".repeat(100_000), wallNow = wallNow) }
        }

        test("hint 'not an organization name' compares exactly (case-insensitive) with the given list, never by heuristic") {
            val notOrg = "Autorenzeile ist kein Organisationsname – Einwilligung klären."
            (parse(entry(byline = "PdV")).single() as EntryCheck.Valid).hints shouldNotContain notOrg
            (parse(entry(byline = "pdv")).single() as EntryCheck.Valid).hints shouldNotContain notOrg
            (parse(entry(byline = "Dirk Hesse")).single() as EntryCheck.Valid).hints shouldContain notOrg
            // "Partei der Vernunft" is only an organization if the board says so
            (parse(entry(byline = "Partei der Vernunft")).single() as EntryCheck.Valid).hints shouldContain notOrg
            (parse(entry(byline = "Partei der Vernunft"), labels = listOf("PdV", "Partei der Vernunft")).single() as EntryCheck.Valid)
                .hints shouldNotContain notOrg
            // no byline: nothing to clarify
            (parse(entry(byline = null)).single() as EntryCheck.Valid).hints shouldNotContain notOrg
        }

        test("hints: no cover image, HTML-like text") {
            val check = parse(entry(body = "Text mit <b>HTML</b>")).single() as EntryCheck.Valid
            check.hints shouldContain "Ohne Titelbild – nach dem Import einzeln hochladen."
            check.hints shouldContain "Enthält HTML – wird als Text angezeigt."
            (parse(entry()).single() as EntryCheck.Valid).hints shouldNotContain "Enthält HTML – wird als Text angezeigt."
        }

        test("organization labels: at most 5, each 1..120 characters, normalized") {
            ArticleImportPolicy.normalizeOrganizationLabels(listOf("  PdV ", "Partei‮ der Vernunft")) shouldBe
                listOf("PdV", "Partei der Vernunft")
            shouldThrow<BadRequestException> { ArticleImportPolicy.normalizeOrganizationLabels(List(6) { "x$it" }) }
            shouldThrow<BadRequestException> { ArticleImportPolicy.normalizeOrganizationLabels(listOf("")) }
            shouldThrow<BadRequestException> { ArticleImportPolicy.normalizeOrganizationLabels(listOf("x".repeat(121))) }
            ArticleImportPolicy.normalizeOrganizationLabels(emptyList()) shouldBe emptyList()
        }

        test("the SHA-256 is over the exact UTF-8 text and differs for any edit") {
            val a = ArticleImportPolicy.sha256Hex("[{\"a\":\"ä\"}]")
            a.length shouldBe 64
            ArticleImportPolicy.sha256Hex("[{\"a\":\"ä\"}] ") shouldBe ArticleImportPolicy.sha256Hex("[{\"a\":\"ä\"}] ")
            (a != ArticleImportPolicy.sha256Hex("[{\"a\":\"ä\"}] ")) shouldBe true
        }
    })

/** Welle V1.9.105 -- the Markdown-preserving text normalization of the import (NOT `EventText.normalizeMultiline`). */
class ArticleImportTextTest :
    FunSpec({
        test("a Markdown hard line break (two spaces before the newline) and tabs survive") {
            ArticleImportText.normalizeMarkdownBody("Zeile  \nZeile\tTab") shouldBe "Zeile  \nZeile\tTab"
        }

        test("CRLF, CR and U+2028 become LF; control and bidi characters are removed; NFC") {
            ArticleImportText.normalizeMarkdownBody("a\r\nb\rc d\u0000‮") shouldBe "a\nb\nc\nd"
            ArticleImportText.normalizeMarkdownBody("Müller") shouldBe "Müller"
        }

        test("only leading and trailing empty lines are trimmed; inner blank lines stay as they are") {
            ArticleImportText.normalizeMarkdownBody("\n\n  Absatz eins\n\n\n\nAbsatz zwei  \n\n") shouldBe "Absatz eins\n\n\n\nAbsatz zwei"
        }

        test("a blank body stays blank") {
            ArticleImportText.normalizeMarkdownBody(" \n \t\n") shouldBe ""
        }

        test("ZWJ and soft hyphen are legitimate text and are kept") {
            ArticleImportText.normalizeMarkdownBody("a‍b­c") shouldBe "a‍b­c"
        }
    })
