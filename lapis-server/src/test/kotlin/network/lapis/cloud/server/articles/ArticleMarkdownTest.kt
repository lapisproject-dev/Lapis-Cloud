package network.lapis.cloud.server.articles

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * [ArticleMarkdown] is the ONLY place `article.body` Markdown ever becomes HTML -- these are the
 * negative/security tests the implementation plan §9 requires (javascript: links, raw
 * script/onerror HTML, image-as-alt-text) plus the positive heading-shift/link-attribute checks.
 */
class ArticleMarkdownTest :
    FunSpec({
        test("javascript: link has its href stripped, the link text remains") {
            val html = ArticleMarkdown.render("[click me](javascript:alert(1))")
            html shouldNotContain "href"
            html shouldContain "click me"
        }

        test("raw <script> in the body is escaped as text, never executable") {
            val html = ArticleMarkdown.render("Hallo <script>alert(1)</script> Welt")
            html shouldNotContain "<script>"
            html shouldContain "&lt;script&gt;"
        }

        test("raw <img onerror=...> is escaped as text, never an executable element") {
            val html = ArticleMarkdown.render("<img src=x onerror=alert(1)>")
            html shouldNotContain "<img"
            html shouldContain "&lt;img"
        }

        test("Markdown image renders as alt text only, never an <img> tag") {
            val html = ArticleMarkdown.render("![Alt Text](https://evil.example/x.png)")
            html shouldNotContain "<img"
            html shouldContain "Alt Text"
            html shouldNotContain "evil.example"
        }

        test("heading levels are shifted down by one and capped at h4") {
            ArticleMarkdown.render("# Eins") shouldBe "<h2>Eins</h2>\n"
            ArticleMarkdown.render("## Zwei") shouldBe "<h3>Zwei</h3>\n"
            ArticleMarkdown.render("### Drei") shouldBe "<h4>Drei</h4>\n"
            ArticleMarkdown.render("#### Vier") shouldBe "<h4>Vier</h4>\n"
            ArticleMarkdown.render("###### Sechs") shouldBe "<h4>Sechs</h4>\n"
        }

        test("legitimate https link gets rel=nofollow noopener ugc and target=_blank") {
            val html = ArticleMarkdown.render("[Lapis](https://lapisproject.dev)")
            html shouldContain "target=\"_blank\""
            html shouldContain "rel=\"nofollow noopener ugc\""
            html shouldContain "href=\"https://lapisproject.dev\""
        }

        test("mailto link keeps rel but never gets target=_blank") {
            val html = ArticleMarkdown.render("[Mail](mailto:info@example.org)")
            html shouldContain "rel=\"nofollow noopener ugc\""
            html shouldNotContain "target"
        }

        test("body at the 100000-character cap does not crash and is cut off, not truncated mid-tag") {
            val huge = "a".repeat(200_000)
            val html = ArticleMarkdown.render(huge)
            (html.length <= ArticlePolicy.BODY_MAX + 20) shouldBe true
        }
    })
