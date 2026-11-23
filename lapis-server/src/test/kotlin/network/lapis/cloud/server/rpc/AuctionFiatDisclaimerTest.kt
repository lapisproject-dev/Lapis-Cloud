package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.security.MessageDigest

/** V1.9.320 -- the two Fiat notices behave like [AuctionComplianceDisclaimer]: stable hash, constant-time match, no gendering, no tampering. */
class AuctionFiatDisclaimerTest :
    FunSpec({
        val gendering = Regex("""\*innen|:innen|_innen|/innen|in/ein|Innen\b""")

        data class Subject(
            val name: String,
            val version: String,
            val text: String,
            val sha: String,
            val matches: (String, String) -> Boolean,
        )

        val subjects =
            listOf(
                Subject(
                    "operator",
                    AuctionFiatOperatorDisclaimer.VERSION,
                    AuctionFiatOperatorDisclaimer.TEXT,
                    AuctionFiatOperatorDisclaimer.SHA256,
                    AuctionFiatOperatorDisclaimer::matches,
                ),
                Subject(
                    "seller",
                    AuctionFiatSellerDisclaimer.VERSION,
                    AuctionFiatSellerDisclaimer.TEXT,
                    AuctionFiatSellerDisclaimer.SHA256,
                    AuctionFiatSellerDisclaimer::matches,
                ),
            )

        subjects.forEach { s ->
            test("${s.name}: the hash is SHA-256 of the UTF-8 bytes of 'VERSION newline TEXT' and the matching input matches") {
                val expected =
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest("${s.version}\n${s.text}".toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                s.sha shouldBe expected
                s.matches(s.version, s.sha) shouldBe true
                s.matches(s.version, s.sha.uppercase()) shouldBe true
            }

            test("${s.name}: a wrong version, a wrong hash, a short hash, a non-hex hash and a blank hash do not match") {
                s.matches("1999-01-01.v0", s.sha) shouldBe false
                s.matches(s.version, "0".repeat(64)) shouldBe false
                s.matches(s.version, s.sha.take(62)) shouldBe false
                s.matches(s.version, "zz" + s.sha.drop(2)) shouldBe false
                s.matches(s.version, s.sha.dropLast(1)) shouldBe false
                s.matches(s.version, "") shouldBe false
            }

            test("${s.name}: no gender asterisk, colon, underscore or slash form, and real umlauts instead of ae/oe/ue transliteration") {
                gendering.containsMatchIn(s.text) shouldBe false
                s.text.contains("Gebühr") || s.text.contains("für") shouldBe true
            }

            test("${s.name}: the text states that Lapis takes no money and is no legal advice") {
                s.text.contains("kein Geld") shouldBe true
                s.text.contains("KEINE Rechtsberatung") shouldBe true
            }
        }

        test("the two notices have different hashes (a seller acknowledgment is never an operator acknowledgment)") {
            (AuctionFiatOperatorDisclaimer.SHA256 == AuctionFiatSellerDisclaimer.SHA256) shouldBe false
            AuctionFiatSellerDisclaimer.matches(
                version = AuctionFiatSellerDisclaimer.VERSION,
                sha256 = AuctionFiatOperatorDisclaimer.SHA256,
            ) shouldBe
                false
        }
    })
