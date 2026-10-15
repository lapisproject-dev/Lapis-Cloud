package network.lapis.cloud.server.articles

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.File

/**
 * [ArticleMarkdown] is the ONLY place `article.body` Markdown ever becomes HTML -- these are the
 * negative/security tests the implementation plan §9 requires (javascript: links, raw
 * script/onerror HTML, image-as-alt-text) plus the positive heading-shift/link-attribute checks.
 */
class ArticleMarkdownTest :
    FunSpec({
        test("javascript: link has its href stripped, the link text remains") {
            val html = ArticleMarkdown.render(markdown = "[click me](javascript:alert(1))")
            html shouldNotContain "href"
            html shouldContain "click me"
        }

        test("raw <script> in the body is escaped as text, never executable") {
            val html = ArticleMarkdown.render(markdown = "Hallo <script>alert(1)</script> Welt")
            html shouldNotContain "<script>"
            html shouldContain "&lt;script&gt;"
        }

        test("raw <img onerror=...> is escaped as text, never an executable element") {
            val html = ArticleMarkdown.render(markdown = "<img src=x onerror=alert(1)>")
            html shouldNotContain "<img"
            html shouldContain "&lt;img"
        }

        test("Markdown image renders as alt text only, never an <img> tag") {
            val html = ArticleMarkdown.render(markdown = "![Alt Text](https://evil.example/x.png)")
            html shouldNotContain "<img"
            html shouldContain "Alt Text"
            html shouldNotContain "evil.example"
        }

        test("heading levels are shifted down by one and capped at h4") {
            ArticleMarkdown.render(markdown = "# Eins") shouldBe "<h2>Eins</h2>\n"
            ArticleMarkdown.render(markdown = "## Zwei") shouldBe "<h3>Zwei</h3>\n"
            ArticleMarkdown.render(markdown = "### Drei") shouldBe "<h4>Drei</h4>\n"
            ArticleMarkdown.render(markdown = "#### Vier") shouldBe "<h4>Vier</h4>\n"
            ArticleMarkdown.render(markdown = "###### Sechs") shouldBe "<h4>Sechs</h4>\n"
        }

        test("legitimate https link gets rel=nofollow noopener ugc and target=_blank") {
            val html = ArticleMarkdown.render(markdown = "[Lapis](https://lapisproject.dev)")
            html shouldContain "target=\"_blank\""
            html shouldContain "rel=\"nofollow noopener ugc\""
            html shouldContain "href=\"https://lapisproject.dev\""
        }

        test("mailto link keeps rel but never gets target=_blank") {
            val html = ArticleMarkdown.render(markdown = "[Mail](mailto:info@example.org)")
            html shouldContain "rel=\"nofollow noopener ugc\""
            html shouldNotContain "target"
        }

        test("body at the 100000-character cap does not crash and is cut off, not truncated mid-tag") {
            val huge = "a".repeat(200_000)
            val html = ArticleMarkdown.render(markdown = huge)
            (html.length <= ArticlePolicy.BODY_MAX + 20) shouldBe true
        }

        test("golden: render(md) without an embed host is byte-identical to the checked-in STANDARD fixture") {
            val html = ArticleMarkdown.render(markdown = GOLDEN_MARKDOWN)
            val golden = File("src/test/resources/golden/article-markdown-standard.html")
            if (System.getenv("UPDATE_ARTICLE_MD_GOLDEN") == "1") golden.writeText(html, Charsets.UTF_8)
            html shouldBe golden.readText(Charsets.UTF_8)
        }

        val embed = EmbedLinkHost.fromBaseUrl("https://example.org")

        test("embed: an absolute link to the own host gets target=_top and keeps rel") {
            val html = ArticleMarkdown.render(markdown = "[x](https://example.org/impressum)", embedHost = embed)
            html shouldContain "target=\"_top\""
            html shouldContain "rel=\"nofollow noopener ugc\""
            html shouldNotContain "_blank"
        }

        test("embed: a foreign host stays target=_blank with noopener") {
            val html = ArticleMarkdown.render(markdown = "[x](https://other.example/a)", embedHost = embed)
            html shouldContain "target=\"_blank\""
            html shouldContain "noopener"
        }

        test("embed: host, scheme and the default port are compared case-insensitively and by effective port") {
            // The link provider only treats lower-case `http(s)://` as a link at all (an upper-case scheme is a dead link, unchanged
            // since V1.4.36), so the case-insensitive host comparison is asserted on the matcher itself.
            (embed?.matches("HTTPS://Example.ORG/x") == true) shouldBe true
            ArticleMarkdown.render(markdown = "[x](https://example.org:443/x)", embedHost = embed) shouldContain "target=\"_top\""
        }

        test("embed: another port, another scheme or another subdomain is external") {
            ArticleMarkdown.render(markdown = "[x](https://example.org:8443/x)", embedHost = embed) shouldContain "target=\"_blank\""
            ArticleMarkdown.render(markdown = "[x](http://example.org/x)", embedHost = embed) shouldContain "target=\"_blank\""
            ArticleMarkdown.render(markdown = "[x](https://sub.example.org/x)", embedHost = embed) shouldContain "target=\"_blank\""
        }

        test("embed: userinfo tricks do not pass as the own host") {
            val html = ArticleMarkdown.render(markdown = "[x](https://example.org@evil.test/)", embedHost = embed)
            html shouldContain "target=\"_blank\""
            html shouldNotContain "_top"
        }

        test("embed: an unparseable URL is external, never _top") {
            val html = ArticleMarkdown.render(markdown = "[x](https://exa mple.org/x)", embedHost = embed)
            html shouldNotContain "_top"
        }

        test("embed: javascript:, data: and mailto: behave as without an embed host") {
            ArticleMarkdown.render(markdown = "[x](javascript:alert(1))", embedHost = embed) shouldNotContain "href"
            ArticleMarkdown.render(markdown = "[x](data:text/html,x)", embedHost = embed) shouldNotContain "href"
            ArticleMarkdown.render(markdown = "[x](mailto:a@example.org)", embedHost = embed) shouldNotContain "target"
        }

        test("every target=_blank carries noopener, with and without an embed host") {
            for (host in listOf(null, embed)) {
                val html = ArticleMarkdown.render(markdown = GOLDEN_MARKDOWN, embedHost = host)
                val blanks = Regex("<a [^>]*target=\"_blank\"[^>]*>").findAll(html).toList()
                (blanks.isNotEmpty()) shouldBe true
                blanks.forEach { it.value shouldContain "noopener" }
            }
        }

        test("EmbedLinkHost.fromBaseUrl: effective ports, lowercase host, null for junk and non-http schemes") {
            EmbedLinkHost.fromBaseUrl("https://Example.org") shouldBe EmbedLinkHost(scheme = "https", host = "example.org", port = 443)
            EmbedLinkHost.fromBaseUrl("http://example.org") shouldBe EmbedLinkHost(scheme = "http", host = "example.org", port = 80)
            EmbedLinkHost.fromBaseUrl("http://localhost:8080") shouldBe EmbedLinkHost(scheme = "http", host = "localhost", port = 8080)
            EmbedLinkHost.fromBaseUrl("ftp://example.org") shouldBe null
            EmbedLinkHost.fromBaseUrl("not a url") shouldBe null
        }
    })

/** Every link kind the renderer distinguishes; the fixture is generated BEFORE the V1.9.140 embed change and must never drift. */
private const val GOLDEN_MARKDOWN =
    "# Titel\n\n" +
        "[extern](https://example.com/a) [eigene](https://example.org/x) [http](http://example.org/y) " +
        "[Mail](mailto:info@example.org) [js](javascript:alert(1)) [data](data:text/html,x) " +
        "[relativ](/impressum) [anker](#oben) [tel](tel:+49123)\n\n" +
        "![Bild](https://evil.example/x.png)\n"
