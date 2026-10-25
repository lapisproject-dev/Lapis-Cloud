package network.lapis.cloud.server.share

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.html.body
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import network.lapis.cloud.server.routes.PublicChrome
import network.lapis.cloud.server.routes.PublicLanguage
import org.jsoup.Jsoup

class ShareBarHtmlTest :
    FunSpec({
        val url = "https://example.org/aktuelles/x"

        fun model(
            title: String = "Titel",
            networks: List<ShareNetwork> = ShareNetwork.entries.toList(),
        ) = ShareBarModel(
            url = url,
            title = title,
            text = title,
            networks = networks,
            needsScript = ShareNetwork.COPY in networks || ShareNetwork.NATIVE in networks,
        )

        fun render(
            model: ShareBarModel,
            lang: PublicLanguage = PublicLanguage.DEFAULT,
        ): String =
            createHTML(prettyPrint = false).html {
                head { renderShareScript(model) }
                body { renderShareBar(model = model, strings = PublicChrome.stringsFor(lang)) }
            }

        test("items follow the fixed order and the native item is hidden") {
            val doc = Jsoup.parse(render(model()))
            val items = doc.select("ul.share-list > li")
            items.size shouldBe 9
            items[0].hasClass("share-item--native") shouldBe true
            items[0].hasAttr("hidden") shouldBe true
            items.drop(1).none { it.hasAttr("hidden") } shouldBe true
            doc.select("li span.share-ico").map { it.classNames().first { c -> c.startsWith("share-ico--") } } shouldBe
                listOf("native", "copy", "whatsapp", "telegram", "facebook", "x", "linkedin", "reddit", "email").map { "share-ico--$it" }
        }

        test("labels per language: aria-label equals title, brand names go through the format string") {
            val de = Jsoup.parse(render(model(), PublicLanguage.DEFAULT))
            de.select("a[href^=https://www.facebook.com]").single().attr("aria-label") shouldBe "Auf Facebook teilen"
            de.select("[data-share-copy]").single().attr("title") shouldBe "Link kopieren"
            val en = Jsoup.parse(render(model(), PublicLanguage.EN))
            en.select("a[href^=https://www.facebook.com]").single().attr("aria-label") shouldBe "Share on Facebook"
            en.select("h2#share-h").text() shouldBe "Share"
            en.select("a, button").forEach { it.attr("aria-label") shouldBe it.attr("title") }
        }

        test("network links open safely, email and copy have no target") {
            val doc = Jsoup.parse(render(model()))
            for (a in doc.select("a[href^=https://]:not([data-share-copy])")) {
                a.attr("target") shouldBe "_blank"
                a.attr("rel") shouldBe "noopener noreferrer"
            }
            doc.select("a[href^=mailto:]").single().hasAttr("target") shouldBe false
            doc.select("[data-share-copy]").single().hasAttr("target") shouldBe false
        }

        test("copy link href equals data-share-url") {
            val doc = Jsoup.parse(render(model()))
            val copy = doc.select("[data-share-copy]").single()
            copy.attr("href") shouldBe url
            doc.select("section.share").single().attr("data-share-url") shouldBe url
        }

        test("a hostile title is only ever escaped attribute text") {
            val html = render(model(title = "\"><script>alert(1)</script>"))
            html shouldNotContain "<script>alert"
            val doc = Jsoup.parse(html)
            doc.select("section.share").single().attr("data-share-title") shouldBe "\"><script>alert(1)</script>"
        }

        test("no inline style, decorative icons hidden, polite live region") {
            val html = render(model())
            html shouldNotContain "style="
            val doc = Jsoup.parse(html)
            doc.select("span.share-ico").all { it.attr("aria-hidden") == "true" } shouldBe true
            doc.select("p.share-status").single().attr("role") shouldBe "status"
            doc.select("p.share-status").single().attr("aria-live") shouldBe "polite"
        }

        test("script tag only when needed") {
            Jsoup.parse(render(model())).select("script[src=/s/assets/share.js][defer]").size shouldBe 1
            Jsoup.parse(render(model(networks = listOf(ShareNetwork.FACEBOOK)))).select("script").size shouldBe 0
        }
    })
