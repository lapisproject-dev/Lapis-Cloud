package network.lapis.cloud.server.agreementtexts

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.articles.ArticleMarkdown
import network.lapis.cloud.server.rpc.TravelContactHintMarkdown
import network.lapis.cloud.shared.domain.AgreementTextFormat
import network.lapis.cloud.shared.domain.AgreementTextRules
import network.lapis.cloud.shared.domain.TravelExpenseAmountRules

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

private fun quoted(depth: Int): String = ">".repeat(depth) + " x"

private fun listed(levels: Int): String = (0 until levels).joinToString("\n") { i -> " ".repeat(i * 2) + "- item $i" }

/** Parses [source] WITHOUT the parser limits (small inputs only) -- the reference to see whether the limits changed the result. */
private val UNLIMITED_PARSER: org.commonmark.parser.Parser =
    org.commonmark.parser.Parser
        .builder()
        .extensions(emptyList())
        .maxOpenBlockParsers(Int.MAX_VALUE)
        .maxInlineNesting(Int.MAX_VALUE)
        .build()

private fun unlimitedHtml(source: String): String =
    ArticleMarkdown.renderDocument(document = UNLIMITED_PARSER.parse(source), softBreakAsLineBreak = true)

/**
 * V1.9.337 -- commonmark 0.33.0 limits block/inline nesting itself (excess becomes plain text instead of a StackOverflowError). The accepting
 * paths therefore refuse deep nesting through the own [ArticleMarkdown.MAX_NESTING_DEPTH] check on the parsed tree; these specs pin that
 * contract per construct, at the boundary, and prove that a clamped text is never accepted silently.
 */
class AgreementMarkdownNestingTest :
    FunSpec({
        test("the nesting limit is clearly below both parser limits") {
            (ArticleMarkdown.MAX_NESTING_DEPTH < ArticleMarkdown.PARSER_MAX_OPEN_BLOCK_PARSERS - 2) shouldBe true
            (ArticleMarkdown.MAX_NESTING_DEPTH < ArticleMarkdown.PARSER_MAX_INLINE_NESTING - 2) shouldBe true
        }

        test("nestingDepth counts the longest chain of ancestors, document = 0") {
            fun depth(source: String) = ArticleMarkdown.nestingDepth(ArticleMarkdown.parse(markdown = source, maxChars = 100_000))
            depth("") shouldBe 0
            depth("x") shouldBe 2 // paragraph > text
            depth("> x") shouldBe 3 // quote > paragraph > text
            depth(quoted(10)) shouldBe 12
            depth("a\n\n> b\n\nc") shouldBe 3
        }

        test("nestingDepth is iterative: a tree far deeper than the stack allows is measured without an error") {
            var node: org.commonmark.node.Node = org.commonmark.node.Document()
            val root = node
            repeat(200_000) {
                val child = org.commonmark.node.BlockQuote()
                node.appendChild(child)
                node = child
            }
            ArticleMarkdown.nestingDepth(root) shouldBe 200_000
        }

        test("block quotes: at the limit the text renders, one level more is refused") {
            // quote depth n is AST depth n + 2
            val atLimit = ArticleMarkdown.MAX_NESTING_DEPTH - 2
            AgreementMarkdown.renderOrNull(body = quoted(atLimit)).shouldNotBeNull()
            AgreementMarkdown.renderOrNull(body = quoted(atLimit + 1)) shouldBe null
            AgreementMarkdown.renderOrNull(body = quoted(20)).shouldNotBeNull()
            AgreementMarkdown.renderOrNull(body = quoted(70)) shouldBe null
            AgreementMarkdown.renderOrNull(body = quoted(5_000)) shouldBe null
        }

        test("lists by indentation: 10 levels render, 60 and more are refused") {
            AgreementMarkdown.renderOrNull(body = listed(10)).shouldNotBeNull()
            AgreementMarkdown.renderOrNull(body = listed(60)) shouldBe null
            AgreementMarkdown.renderOrNull(body = listed(400)) shouldBe null
        }

        test("mixed list in quote in list") {
            val shallow = "- a\n  > b\n  >\n  > - c\n  >   > d"
            AgreementMarkdown.renderOrNull(body = shallow).shouldNotBeNull()
            var deep = "x"
            repeat(25) { deep = "- " + ("> $deep").lines().joinToString("\n  ") }
            AgreementMarkdown.renderOrNull(body = deep.take(AgreementTextRules.MAX_CHARS)) shouldBe null
        }

        test("emphasis: 10 levels render, 300 are refused or at least never crash") {
            val shallow = "*".repeat(10) + "x" + "*".repeat(10)
            AgreementMarkdown.renderOrNull(body = shallow).shouldNotBeNull()
            val alternating = (0 until 10).joinToString("") { if (it % 2 == 0) "_" else "*" }
            AgreementMarkdown.renderOrNull(body = alternating + "x" + alternating.reversed()).shouldNotBeNull()
            val deep = "*a ".repeat(300) + " b*".repeat(300)
            AgreementMarkdown.renderOrNull(body = deep) shouldBe null
        }

        test("nested images: 10 levels render with their alt text, 500 are refused") {
            val shallow = "![".repeat(10) + "x" + "](u)".repeat(10)
            AgreementMarkdown.renderOrNull(body = shallow).shouldNotBeNull() shouldContain "x"
            AgreementMarkdown.renderOrNull(body = "![".repeat(500) + "x" + "](u)".repeat(500)) shouldBe null
        }

        test("huge bracket runs are bounded and never crash") {
            for (source in listOf("[".repeat(20_000), "[a](".repeat(5_000), "[a](".repeat(5_000) + ")".repeat(5_000))) {
                val started = System.nanoTime()
                AgreementMarkdown.renderOrNull(body = source.take(AgreementTextRules.MAX_CHARS))
                ((System.nanoTime() - started) / 1_000_000 < 5_000) shouldBe true
            }
        }

        test("a text on which a parser limit takes effect is never accepted silently clamped") {
            val constructs: Map<String, (Int) -> String> =
                mapOf(
                    "quote" to { n -> quoted(n) },
                    "list" to { n -> listed(n) },
                    "emphasis" to { n -> "*a ".repeat(n) + " b*".repeat(n) },
                    "emphasis-run" to { n -> "*".repeat(n) + "x" + "*".repeat(n) },
                    "image" to { n -> "![".repeat(n) + "x" + "](u)".repeat(n) },
                )
            for ((name, make) in constructs) {
                for (n in listOf(10, 50, 90, 98, 100, 101, 150, 400)) {
                    val source = make(n)
                    val limited = AgreementMarkdown.render(body = source)
                    val accepted = AgreementMarkdown.renderOrNull(body = source)
                    if (limited != unlimitedHtml(source)) {
                        withClue("$name n=$n: the parser limit changed the result, so the text must be refused") { accepted shouldBe null }
                    }
                }
            }
        }

        test("the read path still shows a stored text nested deeper than the acceptance limit") {
            val stored = quoted(70)
            AgreementMarkdown.renderOrNull(body = stored) shouldBe null
            AgreementMarkdown.renderForDisplayOrNull(body = stored).shouldNotBeNull() shouldContain "<blockquote>"
        }

        test("hasBlockNodes and toPlainText survive absurd nesting") {
            val deep = ">".repeat(40_000).take(AgreementTextRules.MAX_CHARS)
            AgreementMarkdown.hasBlockNodes(deep) shouldBe true
            AgreementMarkdown.toPlainText(deep).shouldNotBeNull()
        }

        test("the run time stays bounded for pathological inputs at every size limit") {
            val inputs =
                listOf(
                    "![".repeat(60_000) + "x" + "](u)".repeat(30_000),
                    "<".repeat(100_000),
                    "<http://a".repeat(20_000),
                    (1..400).joinToString("") { "`".repeat(it) + "a" }.repeat(3),
                    "*a".repeat(50_000),
                    ">".repeat(100_000),
                )
            val limits = listOf(AgreementTextRules.MAX_CHARS, 10_000, TravelExpenseAmountRules.MAX_CONTACT_HINT_LENGTH)
            for (source in inputs) {
                for (limit in limits) {
                    val started = System.nanoTime()
                    AgreementMarkdown.renderOrNull(body = source.take(limit), maxChars = limit)
                    ((System.nanoTime() - started) / 1_000_000 < 5_000) shouldBe true
                }
                val started = System.nanoTime()
                ArticleMarkdown.render(markdown = source)
                ((System.nanoTime() - started) / 1_000_000 < 5_000) shouldBe true
            }
        }

        test("HTTP:// with an upper-case scheme keeps no href (stricter than needed, pinned)") {
            val html = AgreementMarkdown.render(body = "[x](HTTP://example.org) [y](Https://example.org)")
            html shouldNotContain "href"
            html shouldContain "x"
        }
    })

/** The XSS matrix: every payload through every pipeline that turns Markdown into HTML. */
class AgreementMarkdownXssMatrixTest :
    FunSpec({
        val payloads =
            listOf(
                "<script>alert(1)</script>",
                "text <script>alert(1)</script> text",
                "[x](javascript:alert(1))",
                "[x](JaVaScRiPt:alert(1))",
                "[x](&#106;avascript:alert(1))",
                "[x](java&#x09;script:alert(1))",
                "[x](data:text/html,<script>alert(1)</script>)",
                "[x](vbscript:msgbox(1))",
                "<javascript:alert(1)>",
                "[x]: javascript:alert(1)\n\n[x]",
                "![a](javascript:x)",
                "<img src=x onerror=alert(1)>",
                "a <img src=x onerror=alert(1)> b",
                "[x](https://a\"onmouseover=\"b)",
                "[x](https://a 'title\"onx=y')",
                "[x](https://example.org \"Ti\\\"tle onmouseover=x\")",
                "[[x](javascript:alert(1))](https://example.org)",
                "<a href=\"javascript:alert(1)\">x</a>",
                "<a href=\"https://example.org\" onclick=\"x\">x</a>",
                "<svg onload=alert(1)>",
                "<iframe src=\"https://evil.example\"></iframe>",
                ">".repeat(58) + " [x](javascript:alert(1)) <script>x</script>",
            )
        // the TEXT "javascript:" may stay visible inside a dead <a> (no href); what matters is that no href carries it (checked by dangers + href regex)
        val forbidden = listOf("<script", "<img", "<iframe", "<svg")

        fun check(
            name: String,
            html: String?,
        ) {
            withClue(name) {
                html.shouldNotBeNull()
                for (needle in forbidden) html.lowercase() shouldNotContain needle
                // a quote inside an attribute VALUE is escaped (&quot;), so text like onmouseover= inside a quoted value is inert: the event
                // attribute check looks at the attribute names only (quoted values removed); everything else (tags, hrefs) on the real output
                dangers(html).filterNot { it.startsWith("event attribute") }.shouldBeEmpty()
                dangers(html.replace(Regex("""="[^"]*""""), "=\"x\"")).filter { it.startsWith("event attribute") }.shouldBeEmpty()
                Regex("""\bhref\s*=\s*"([^"]*)"""").findAll(html).forEach { m ->
                    val v = m.groupValues[1]
                    (v.startsWith("http://") || v.startsWith("https://") || v.startsWith("mailto:")) shouldBe true
                }
            }
        }

        test("article renderer") {
            for (p in payloads) check("article: $p", ArticleMarkdown.render(markdown = p))
        }

        test("agreement text / tender description (renderOrNull)") {
            for (p in payloads) check("agreement: $p", AgreementMarkdown.renderOrNull(body = p))
        }

        test("travel contact hint") {
            for (p in payloads) {
                check("hint render: $p", TravelContactHintMarkdown.render(p))
                check("hint renderOrNull: $p", TravelContactHintMarkdown.renderOrNull(p))
            }
        }
    })
