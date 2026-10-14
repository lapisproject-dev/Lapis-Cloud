package network.lapis.cloud.server.articles

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class ArticleLeadTest :
    FunSpec({
        fun show(
            excerpt: String,
            body: String,
        ) = ArticleLead.shouldShow(excerpt = excerpt, renderedBodyHtml = ArticleMarkdown.render(body))

        test("empty or punctuation-only excerpt is never shown") {
            show(excerpt = "", body = "Text") shouldBe false
            show(excerpt = " ... !? ", body = "Text") shouldBe false
        }

        test("an excerpt that is not at the start of the body is shown") {
            show(excerpt = "Ein Auszug", body = "Ganz anderer Text.") shouldBe true
        }

        test("an excerpt identical to a bold first line is suppressed") {
            show(excerpt = "Es droht ein harter Aufprall.", body = "**Es droht ein harter Aufprall.**\n\nText") shouldBe false
        }

        test("a truncated excerpt with an ellipsis is suppressed") {
            val body = "Es droht ein harter Aufprall auf dem Boden der Tatsachen."
            show(excerpt = "Es droht ein harter Aufprall...", body = body) shouldBe false
            show(excerpt = "Es droht ein harter Aufprall…", body = body) shouldBe false
        }

        test("a prefix in the middle of a word counts as a prefix (truncated excerpts)") {
            show(excerpt = "Es droht ein harter Auf", body = "Es droht ein harter Aufprall.") shouldBe false
        }

        test("special characters survive the HTML round trip") {
            val text = "Tom & Jerry sagen \"Hallo\" <b> und it's gut"
            show(excerpt = text, body = text) shouldBe false
        }

        test("case and umlaut normalization (NFC vs NFD)") {
            show(excerpt = "ÄRGER IM PARADIES", body = "Ärger im Paradies") shouldBe false
            val nfd = "Ärger im Paradies"
            show(excerpt = nfd, body = "Ärger im Paradies") shouldBe false
        }

        test("a body that starts with a heading before the sentence shows the lead (intended)") {
            show(excerpt = "Der Auszug steht hier.", body = "# Titel\n\nDer Auszug steht hier.") shouldBe true
        }

        test("an excerpt longer than the body is shown") {
            show(excerpt = "Ein sehr langer Auszug, der laenger ist als der Text.", body = "Kurz.") shouldBe true
        }

        test("unknown entities do not leave letter artefacts") {
            ArticleLead.textOf("a&hellip;b") shouldNotContain "hellip"
            ArticleLead.norm(ArticleLead.textOf("a&hellip;b")) shouldBe "a b"
        }
    })
