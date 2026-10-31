package network.lapis.cloud.server.partner

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import network.lapis.cloud.shared.domain.PartnerAddressRejectionCode

class PartnerHostTest :
    FunSpec({
        fun ok(raw: String) = (PartnerHost.normalize(raw = raw, ownHost = "own.example.com") as? PartnerAddressParse.Ok)?.host

        fun rejected(raw: String) = (PartnerHost.normalize(raw = raw, ownHost = "own.example.com") as? PartnerAddressParse.Rejected)?.reason

        test("a path, a query, an upper-case host and a trailing dot are tolerated and dropped") {
            ok("https://Partner.Beispiel.de./veranstaltungen?x=1") shouldBe "partner.beispiel.de"
            ok("partner.beispiel.de") shouldBe "partner.beispiel.de"
            ok("  partner.beispiel.de/path#frag ") shouldBe "partner.beispiel.de"
            ok("https://partner.beispiel.de:443/") shouldBe "partner.beispiel.de"
        }

        test("an IDN host becomes punycode") {
            ok("https://bücher.de") shouldBe "xn--bcher-kva.de"
        }

        test("http and other schemes are NOT_HTTPS") {
            rejected("http://partner.beispiel.de") shouldBe PartnerAddressRejectionCode.NOT_HTTPS
            rejected("ftp://partner.beispiel.de") shouldBe PartnerAddressRejectionCode.NOT_HTTPS
        }

        test("a port other than 443 and user info are refused") {
            rejected("https://partner.beispiel.de:8443") shouldBe PartnerAddressRejectionCode.PORT_NOT_ALLOWED
            rejected("https://user@partner.beispiel.de") shouldBe PartnerAddressRejectionCode.USERINFO
            rejected("https://user:pw@partner.beispiel.de") shouldBe PartnerAddressRejectionCode.USERINFO
        }

        test("every spelling of an IP address is an IP literal") {
            listOf("127.0.0.1", "2130706433", "0177.0.0.1", "0x7f.0.0.1", "127.1", "[::1]", "[::ffff:127.0.0.1]", "https://[fd00::1]/")
                .forEach { rejected(it) shouldBe PartnerAddressRejectionCode.IP_LITERAL }
        }

        test("local names and single-label names are refused") {
            rejected("localhost") shouldBe PartnerAddressRejectionCode.LOCAL_NAME
            rejected("x.local") shouldBe PartnerAddressRejectionCode.LOCAL_NAME
            rejected("x.internal") shouldBe PartnerAddressRejectionCode.LOCAL_NAME
            rejected("x.lan") shouldBe PartnerAddressRejectionCode.LOCAL_NAME
            rejected("x.home.arpa") shouldBe PartnerAddressRejectionCode.LOCAL_NAME
            rejected("foo") shouldBe PartnerAddressRejectionCode.NO_DOT
        }

        test("the own host is refused, an over-long host too") {
            rejected("https://own.example.com") shouldBe PartnerAddressRejectionCode.OWN_HOST
            rejected("a".repeat(60) + (".bbbbbbbbbb" + "b".repeat(50)).repeat(4) + ".de") shouldBe PartnerAddressRejectionCode.TOO_LONG
            rejected("a".repeat(2100)) shouldBe PartnerAddressRejectionCode.TOO_LONG
        }

        test("malformed input is MALFORMED") {
            rejected("") shouldBe PartnerAddressRejectionCode.MALFORMED
            rejected("a b.de") shouldBe PartnerAddressRejectionCode.MALFORMED
            rejected("a..de") shouldBe PartnerAddressRejectionCode.MALFORMED
            rejected("https://evil.com\\@good.com") shouldBe PartnerAddressRejectionCode.MALFORMED
            rejected("x_y.de") shouldBe PartnerAddressRejectionCode.MALFORMED
        }

        test("ownHostOf reads the host of the public base URL") {
            PartnerHost.ownHostOf("https://Cloud.Example.org/") shouldBe "cloud.example.org"
            PartnerHost.ownHostOf("not a url") shouldBe null
        }
    })
