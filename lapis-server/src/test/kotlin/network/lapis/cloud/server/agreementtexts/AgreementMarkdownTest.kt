package network.lapis.cloud.server.agreementtexts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.articles.ArticleMarkdown
import network.lapis.cloud.shared.domain.AgreementTextFormat
import network.lapis.cloud.shared.domain.AgreementTextRules

private val TAG = Regex("""<(/?)([a-zA-Z][a-zA-Z0-9]*)([^>]*)>""")
private val ALLOWED_TAGS = setOf("p", "h2", "h3", "h4", "ul", "ol", "li", "strong", "em", "a", "br", "code", "pre", "blockquote", "hr")
private val HREF = Regex("""\bhref\s*=\s*"([^"]*)"""")
private val EVENT_ATTRIBUTE = Regex("""\bon[a-zA-Z]+\s*=""", RegexOption.IGNORE_CASE)

/** Everything a rendered text could do to a page, as findings: a tag outside the allowed set, an event attribute, a `href` that is not http(s)/mailto. */
private fun dangers(html: String): List<String> =
    buildList {
        TAG.findAll(html).forEach { match ->
            val name = match.groupValues[2].lowercase()
            val attributes = match.groupValues[3]
            if (name !in ALLOWED_TAGS) add("tag <$name>")
            if (EVENT_ATTRIBUTE.containsMatchIn(attributes)) add("event attribute in <$name$attributes>")
            HREF.findAll(attributes).forEach { href ->
                val value = href.groupValues[1]
                if (!(value.startsWith("http://") || value.startsWith("https://") || value.startsWith("mailto:"))) add("href=\"$value\"")
            }
            if (Regex("""\b(src|style|srcset|formaction|action)\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(attributes)) {
                add("forbidden attribute in <$name$attributes>")
            }
        }
    }

/**
 * V1.9.196 -- [AgreementMarkdown] is the only place an agreement text becomes HTML. The XSS matrix: whatever an admin types, the output has
 * only allowed tags, no event attribute and no `href` that is not http(s)/mailto -- and the supported subset renders.
 */
class AgreementMarkdownTest :
    FunSpec({
        val hostile =
            mapOf(
                "script tag" to "<script>alert(1)</script>",
                "img onerror" to "<img src=x onerror=alert(1)>",
                "javascript link" to "[klick](javascript:alert(1))",
                "mixed case javascript link" to "[klick](JaVaScRiPt:alert(1))",
                "tab inside scheme" to "[klick](java&#x09;script:alert(1))",
                "numeric entity scheme" to "[klick](&#106;avascript:alert(1))",
                "data html" to "[klick](data:text/html,<script>alert(1)</script>)",
                "vbscript" to "[klick](vbscript:msgbox(1))",
                "nested link" to "[[x](javascript:alert(1))](https://example.org)",
                "autolink javascript" to "<javascript:alert(1)>",
                "reference link" to "[a][r]\n\n[r]: javascript:alert(1)",
                "escaped entity text" to "&lt;script&gt;alert(1)&lt;/script&gt;",
                "html block with handler" to "<div onclick=\"alert(1)\">x</div>",
                "raw anchor" to "<a href=\"javascript:alert(1)\">x</a>",
                "image javascript" to "![x](javascript:alert(1))",
                "image data" to "![x](data:image/svg+xml,<svg onload=alert(1)>)",
                "relative link" to "[x](/etc/passwd)",
                "protocol relative" to "[x](//evil.example/x)",
                "style attribute" to "<p style=\"background:url(javascript:alert(1))\">x</p>",
                "iframe" to "<iframe src=\"https://evil.example\"></iframe>",
                "svg onload" to "<svg onload=alert(1)>",
                "uppercase scheme" to "[x](HTTPS://example.org)",
            )

        hostile.forEach { (name, source) ->
            test("hostile input is rendered inert: $name") {
                val html = AgreementMarkdown.render(body = source)
                dangers(html).shouldBeEmpty()
                html.lowercase() shouldNotContain "<script"
            }
        }

        test("a raw tag is shown as text (escaped), never passed through, and an entity is not decoded twice") {
            AgreementMarkdown.render(body = "<script>alert(1)</script>") shouldContain "&lt;script&gt;"
            val twice = AgreementMarkdown.render(body = "&lt;script&gt;")
            twice shouldContain "&lt;script&gt;"
            twice shouldNotContain "<script"
        }

        test("a dead link keeps its visible text but loses its href") {
            val html = AgreementMarkdown.render(body = "[klick mich](javascript:alert(1))")
            html shouldContain "klick mich"
            html shouldNotContain "href"
        }

        test("the supported subset renders: headings h2-h4, lists, emphasis, links with rel, paragraphs") {
            val html =
                AgreementMarkdown.render(
                    body =
                        "# Eins\n\n## Zwei\n\n### Drei\n\n#### Vier\n\nEin **fetter** und *kursiver* Absatz.\n\n" +
                            "- a\n- b\n\n1. x\n2. y\n\n" +
                            "[Seite](https://example.org/a) und [Post](mailto:vorstand@example.org)",
                )
            html shouldContain "<h2>Eins</h2>"
            html shouldContain "<h3>Zwei</h3>"
            html shouldContain "<h4>Drei</h4>"
            html shouldContain "<h4>Vier</h4>"
            html shouldContain "<strong>fetter</strong>"
            html shouldContain "<em>kursiver</em>"
            html shouldContain "<ul>"
            html shouldContain "<ol>"
            html shouldContain "<li>a</li>"
            html shouldContain "href=\"https://example.org/a\""
            html shouldContain "rel=\"nofollow noopener ugc\""
            html shouldContain "href=\"mailto:vorstand@example.org\""
            dangers(html).shouldBeEmpty()
        }

        test("single line breaks are kept as <br /> (addresses, signature blocks), a blank line still starts a new paragraph") {
            val html = AgreementMarkdown.render(body = "Zeile eins\nZeile zwei\n\nNeuer Absatz")
            html shouldContain "Zeile eins<br />"
            html shouldContain "Zeile zwei"
            html shouldContain "<p>Neuer Absatz</p>"
        }

        test("the rendering is cut at the write limit, so the rendered text can never be longer than the hashed text") {
            val over = "a".repeat(AgreementTextRules.MAX_CHARS + 5_000)
            val html = AgreementMarkdown.render(body = over)
            html.count { it == 'a' } shouldBe AgreementTextRules.MAX_CHARS
        }

        test("the article renderer is unchanged by the new parameters: defaults render bit-identically") {
            val source = "# Titel\n\nZeile eins\nZeile zwei\n\n- a\n- b\n\n[x](https://example.org)"
            ArticleMarkdown.render(markdown = source) shouldBe
                ArticleMarkdown.render(markdown = source, embedHost = null, maxChars = 100_000, softBreakAsLineBreak = false)
            ArticleMarkdown.render(markdown = "a\nb") shouldBe "<p>a\nb</p>\n"
        }

        test("toPlainText removes the Markdown characters (headline and key points of a notice)") {
            AgreementMarkdown.toPlainText("**Wichtig:** Lesen Sie [das hier](https://example.org).") shouldBe
                "Wichtig: Lesen Sie \"das hier\" (https://example.org)."
            AgreementMarkdown.toPlainText("Nur *Text*") shouldBe "Nur Text"
        }

        test("htmlFor renders only a MARKDOWN text; a plain text has no HTML at all") {
            AgreementMarkdown.htmlFor(format = AgreementTextFormat.PLAIN, body = "**x**") shouldBe null
            AgreementMarkdown.htmlFor(format = AgreementTextFormat.MARKDOWN, body = "**x**") shouldContain "<strong>x</strong>"
        }

        test("a deeply nested input renders in bounded time (no quadratic blow-up at the size limit)") {
            val nestedBrackets = "[".repeat(20_000) + "x" + "](https://example.org)".repeat(1)
            val nestedLists = (0 until 3_000).joinToString("\n") { i -> " ".repeat(minOf(i, 40) * 2) + "- item $i" }
            for (source in listOf(nestedBrackets, nestedLists, ">".repeat(20_000), "*".repeat(30_000), "<".repeat(30_000))) {
                val started = System.nanoTime()
                // either it renders, or it is refused as too deeply nested -- never an unhandled StackOverflowError, never a hang
                AgreementMarkdown.renderOrNull(body = source.take(AgreementTextRules.MAX_CHARS))
                val millis = (System.nanoTime() - started) / 1_000_000
                (millis < 5_000) shouldBe true
            }
        }

        test("an absurdly nested text is refused by renderOrNull instead of crashing the request") {
            AgreementMarkdown.renderOrNull(body = ">".repeat(40_000).take(AgreementTextRules.MAX_CHARS)) shouldBe null
        }
    })
