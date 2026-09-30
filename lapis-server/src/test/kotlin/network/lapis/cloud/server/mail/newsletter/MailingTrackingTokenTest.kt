package network.lapis.cloud.server.mail.newsletter

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MailingTrackingTokenTest :
    FunSpec({
        val token = testTrackingToken()

        test("click token round-trips") {
            val issued = token.issue()
            val parsed = token.parse(token.clickToken(nonce = issued.nonce, linkIndex = 7))
            parsed shouldBe MailingTrackingToken.Parsed.Click(nonce = issued.nonce, linkIndex = 7)
        }

        test("open token round-trips") {
            val issued = token.issue()
            token.parse(token.openToken(issued.nonce)) shouldBe MailingTrackingToken.Parsed.Open(issued.nonce)
        }

        test("tokens stay within the length ceiling") {
            val issued = token.issue()
            (token.clickToken(nonce = issued.nonce, linkIndex = 199).length <= MailingTrackingToken.MAX_LENGTH) shouldBe true
            (token.openToken(issued.nonce).length <= MailingTrackingToken.MAX_LENGTH) shouldBe true
        }

        test("a manipulated MAC is rejected") {
            val issued = token.issue()
            val good = token.clickToken(nonce = issued.nonce, linkIndex = 3)
            val lastChar = good.last()
            val tampered = good.dropLast(1) + (if (lastChar == 'A') 'B' else 'A')
            token.parse(tampered) shouldBe null
        }

        test("re-pointing the link index with the old MAC is rejected") {
            val issued = token.issue()
            val parts = token.clickToken(nonce = issued.nonce, linkIndex = 0).split('.')
            token.parse("${parts[0]}.${parts[1]}.5.${parts[3]}") shouldBe null
        }

        test("a foreign nonce combined with another token's MAC is rejected") {
            val a = token.issue()
            val b = token.issue()
            val partsA = token.clickToken(nonce = a.nonce, linkIndex = 1).split('.')
            val partsB = token.clickToken(nonce = b.nonce, linkIndex = 1).split('.')
            token.parse("1.${partsB[1]}.1.${partsA[3]}") shouldBe null
        }

        test("a click token cannot be replayed as an open token and vice versa") {
            val issued = token.issue()
            val click = token.clickToken(nonce = issued.nonce, linkIndex = 2).split('.')
            val open = token.openToken(issued.nonce).split('.')
            token.parse("1.${issued.nonce}.o.${click[3]}") shouldBe null
            token.parse("1.${issued.nonce}.2.${open[3]}") shouldBe null
        }

        test("over-long, truncated, wrong-version and malformed-index tokens are rejected") {
            val issued = token.issue()
            val good = token.clickToken(nonce = issued.nonce, linkIndex = 4)
            token.parse(good + "A".repeat(MailingTrackingToken.MAX_LENGTH)) shouldBe null
            token.parse(good.dropLast(5)) shouldBe null
            token.parse("2" + good.drop(1)) shouldBe null
            val p = good.split('.')
            listOf("200", "-1", "01", "+1", "1000", "", "x").forEach { idx ->
                token.parse("${p[0]}.${p[1]}.$idx.${p[3]}") shouldBe null
            }
            token.parse("") shouldBe null
            token.parse("....") shouldBe null
            token.parse("1.!!.1.x") shouldBe null
        }

        test("non base64url characters are rejected") {
            val issued = token.issue()
            val p = token.clickToken(nonce = issued.nonce, linkIndex = 1).split('.')
            token.parse("1.${p[1].dropLast(1)}+.1.${p[3]}") shouldBe null
            token.parse("1.${p[1]}.1.${p[3].dropLast(1)}=") shouldBe null
        }

        test("issue() yields a unique nonce per call and a 64-char hex hash") {
            val seen = (1..200).map { token.issue() }
            seen.map { it.nonce }.toSet().size shouldBe 200
            seen.forEach {
                it.nonce.length shouldBe 22
                it.hashHex.length shouldBe 64
                it.hashHex shouldBe MailingTrackingToken.hashNonce(it.nonce)
                (it.hashHex.all { c -> c in "0123456789abcdef" }) shouldBe true
            }
        }

        test("a token minted with another key does not verify") {
            val other = MailingTrackingToken(ByteArray(32) { (it + 99).toByte() })
            val issued = token.issue()
            other.parse(token.clickToken(nonce = issued.nonce, linkIndex = 1)) shouldBe null
            other.parse(token.openToken(issued.nonce)) shouldBe null
        }

        test("parse never throws on hostile input") {
            listOf("1.a.b.c", "1..1.", "1." + "A".repeat(22) + ".1." + "A".repeat(22), "\u0000", "1.\n.1.x").forEach {
                token.parse(it) shouldBe null
            }
            token.parse("1.AAAAAAAAAAAAAAAAAAAAAA.1.AAAAAAAAAAAAAAAAAAAAAA") shouldBe null
        }
    })
