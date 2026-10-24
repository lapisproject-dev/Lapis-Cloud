package network.lapis.cloud.server.events

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** V1.9.204 -- the check and clean-up of the registrant message (pure, no database). */
class EventRegistrationMessageTest :
    FunSpec({
        fun ok(raw: String?): String? =
            EventRegistrationMessage.normalize(raw).shouldBeInstanceOf<EventRegistrationMessage.Outcome.Ok>().value

        test("null, empty and blank become null") {
            ok(null) shouldBe null
            ok("") shouldBe null
            ok("  \n\t ") shouldBe null
        }

        test("the text is trimmed") {
            ok("  Hallo Welt \n") shouldBe "Hallo Welt"
        }

        test("exactly 500 characters pass, 501 are too long") {
            ok("a".repeat(500)) shouldBe "a".repeat(500)
            EventRegistrationMessage.normalize("a".repeat(501)) shouldBe EventRegistrationMessage.Outcome.TooLong
        }

        test("the length counts UTF-16 units: 250 emoji (two units each) pass, 251 do not") {
            val emoji = "😀"
            ok(emoji.repeat(250)) shouldBe emoji.repeat(250)
            EventRegistrationMessage.normalize(emoji.repeat(251)) shouldBe EventRegistrationMessage.Outcome.TooLong
        }

        test("line breaks are unified to LF") {
            ok("a\r\nb\rc\nd") shouldBe "a\nb\nc\nd"
        }

        test("control characters and bidi controls are removed, LF stays") {
            ok("a\u0000b\u0007c‮d⁦e‏f\ng") shouldBe "abcdef\ng"
        }

        test("the length is checked after the clean-up") {
            ok("a".repeat(500) + "‮\u0000") shouldBe "a".repeat(500)
        }

        test("HTML stays literal -- it is escaped where it is shown") {
            ok("<script>alert(1)</script> & \"x\"") shouldBe "<script>alert(1)</script> & \"x\""
        }

        test("German text with umlauts passes unchanged") {
            ok("Können wir bitte vegetarisch essen? Größe 42") shouldBe "Können wir bitte vegetarisch essen? Größe 42"
        }
    })
