package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.PartgReviewerRules
import kotlin.uuid.Uuid

/** V1.9.310 (R6b) -- the pure secrets of the auditor's access: code shape, hash binding, constant-time compare, input normalisation. */
class PartgReviewerTokensTest :
    FunSpec({
        test("a code is always six digits, also with leading zeros, and the draw is not constant") {
            val codes = (1..3000).map { PartgReviewerTokens.newCode() }
            codes.all { it.length == 6 && it.all { c -> c in '0'..'9' } } shouldBe true
            codes.any { it.startsWith("0") } shouldBe true
            (codes.toSet().size > 2500) shouldBe true
            // every leading digit shows up (a crude check against a biased draw)
            codes.map { it.first() }.toSet().size shouldBe 10
        }

        test("the hash of a code is bound to the invitation: the same code on another invitation hashes differently") {
            val a = Uuid.random()
            val b = Uuid.random()
            PartgReviewerTokens.codeHash(invitationId = a, code = "482913") shouldBe
                PartgReviewerTokens.codeHash(invitationId = a, code = "482913")
            (
                PartgReviewerTokens.codeHash(invitationId = a, code = "482913") ==
                    PartgReviewerTokens.codeHash(invitationId = b, code = "482913")
            ) shouldBe
                false
            (
                PartgReviewerTokens.codeHash(invitationId = a, code = "482913") ==
                    PartgReviewerTokens.codeHash(invitationId = a, code = "482914")
            ) shouldBe
                false
            PartgReviewerTokens.codeHash(invitationId = a, code = "482913").length shouldBe 64
        }

        test("matches compares hashes, and a missing stored hash never matches") {
            val id = Uuid.random()
            val h = PartgReviewerTokens.codeHash(invitationId = id, code = "000001")
            PartgReviewerTokens.matches(storedHex = h, candidateHex = h) shouldBe true
            PartgReviewerTokens.matches(
                storedHex = h,
                candidateHex = PartgReviewerTokens.codeHash(invitationId = id, code = "000002"),
            ) shouldBe
                false
            PartgReviewerTokens.matches(storedHex = null, candidateHex = h) shouldBe false
        }

        test("a link token is 256 bit, url safe, and its hash is not the token") {
            val t = PartgReviewerTokens.newLinkToken()
            PartgReviewerTokens.plausibleLinkToken(t) shouldBe true
            (t.length >= 43) shouldBe true
            (PartgReviewerTokens.hash(t) == t) shouldBe false
            (PartgReviewerTokens.newLinkToken() == t) shouldBe false
        }

        test("plausibleLinkToken refuses a foreign alphabet, a too long and a too short text") {
            PartgReviewerTokens.plausibleLinkToken(null) shouldBe false
            PartgReviewerTokens.plausibleLinkToken("") shouldBe false
            PartgReviewerTokens.plausibleLinkToken("a".repeat(15)) shouldBe false
            PartgReviewerTokens.plausibleLinkToken("a".repeat(65)) shouldBe false
            PartgReviewerTokens.plausibleLinkToken("a".repeat(30) + "+/=") shouldBe false
            PartgReviewerTokens.plausibleLinkToken("a".repeat(30) + "\n") shouldBe false
            PartgReviewerTokens.plausibleLinkToken("a".repeat(30) + "é") shouldBe false
        }

        test("the code input is normalised: blanks go, anything but six digits is refused") {
            PartgReviewerRules.normalizeCode("482 913") shouldBe "482913"
            PartgReviewerRules.normalizeCode("482913") shouldBe "482913"
            PartgReviewerRules.normalizeCode("48291") shouldBe null
            PartgReviewerRules.normalizeCode("4829130") shouldBe null
            PartgReviewerRules.normalizeCode("48a913") shouldBe null
            PartgReviewerRules.normalizeCode(null) shouldBe null
            PartgReviewerRules.formatCode("482913") shouldBe "482 913"
        }

        test("the address is trimmed and lower cased; line breaks, control characters, blanks and a missing at-sign are refused") {
            PartgReviewerRules.normalizeEmail("  Pruefer@Example.ORG ") shouldBe "pruefer@example.org"
            PartgReviewerRules.normalizeEmail("a@b") shouldBe null
            PartgReviewerRules.normalizeEmail("a b@example.org") shouldBe null
            PartgReviewerRules.normalizeEmail("a@example.org\nBcc: x@example.org") shouldBe null
            PartgReviewerRules.normalizeEmail("a\u0000@example.org") shouldBe null
            PartgReviewerRules.normalizeEmail("<a>@example.org") shouldBe null
            PartgReviewerRules.normalizeEmail("a".repeat(250) + "@example.org") shouldBe null
            PartgReviewerRules.normalizeEmail("") shouldBe null
            PartgReviewerRules.normalizeEmail(null) shouldBe null
        }
    })
