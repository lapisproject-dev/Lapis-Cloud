package network.lapis.cloud.server.mail.newsletter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Welle V1.9.7 "SuperMailer" Teil A -- exercises [MailingPlainText] through
 * [MailingHtmlSanitizer.sanitize] (the only real caller), since [MailingPlainText.derive] takes an
 * already-sanitized [org.jsoup.nodes.Document], not raw HTML.
 */
class MailingPlainTextTest :
    FunSpec({
        test("paragraphs become blank-line-separated blocks") {
            val text = MailingHtmlSanitizer.sanitize("<p>Erster Absatz.</p><p>Zweiter Absatz.</p>").plainText
            text shouldBe "Erster Absatz.\n\nZweiter Absatz."
        }

        test("an unordered list becomes dash-prefixed lines") {
            val text = MailingHtmlSanitizer.sanitize("<ul><li>Eins</li><li>Zwei</li></ul>").plainText
            text shouldBe "- Eins\n- Zwei"
        }

        test("an ordered list becomes numbered lines") {
            val text = MailingHtmlSanitizer.sanitize("<ol><li>Eins</li><li>Zwei</li></ol>").plainText
            text shouldBe "1. Eins\n2. Zwei"
        }

        test("a link with different text and target becomes 'text (href)'") {
            val text = MailingHtmlSanitizer.sanitize("<p>Siehe <a href=\"https://example.org\">unsere Seite</a>.</p>").plainText
            text shouldContain "unsere Seite (https://example.org)"
        }

        test("a link whose text equals its target is not duplicated") {
            val text = MailingHtmlSanitizer.sanitize("<p><a href=\"https://example.org\">https://example.org</a></p>").plainText
            text shouldBe "https://example.org"
        }

        test("br becomes a newline within one paragraph") {
            val text = MailingHtmlSanitizer.sanitize("<p>Zeile eins<br>Zeile zwei</p>").plainText
            text shouldBe "Zeile eins\nZeile zwei"
        }

        test("h2 is rendered uppercase, in its own block") {
            val text = MailingHtmlSanitizer.sanitize("<h2>Überschrift</h2><p>Text.</p>").plainText
            text shouldContain "ÜBERSCHRIFT"
            text shouldNotContain "Überschrift\n"
        }

        test("never contains a tracking URL shape -- plain text always comes from the unrewritten document") {
            val text = MailingHtmlSanitizer.sanitize("<p><a href=\"https://example.org/page\">link</a></p>").plainText
            text shouldNotContain "/api/mailing/"
        }
    })
