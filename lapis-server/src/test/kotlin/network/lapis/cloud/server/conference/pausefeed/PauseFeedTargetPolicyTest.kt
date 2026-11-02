package network.lapis.cloud.server.conference.pausefeed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.net.InetAddress
import java.net.UnknownHostException

private fun resolver(vararg addresses: String) = HostResolver { _ -> addresses.map { InetAddress.getByName(it) } }

private fun policy(
    vararg addresses: String,
    allowRtmps: Boolean = true,
) = PauseFeedTargetPolicy(resolver = resolver(*addresses), allowRtmps = allowRtmps)

/** V1.9.255 -- the server opens the pause feed itself, so a target must be public and its URL must not carry tee syntax. */
class PauseFeedTargetPolicyTest :
    FunSpec({
        test("a public rtmp and rtmps target passes") {
            policy("203.0.113.10").check("rtmp://a.example.org/live2/key-1234") shouldBe
                PauseFeedTargetCheck.Ok("rtmp://a.example.org/live2/key-1234")
            policy("2001:4860:4860::8888").check("rtmps://a.example.org:443/live2/key-1234") shouldBe
                PauseFeedTargetCheck.Ok("rtmps://a.example.org:443/live2/key-1234")
        }

        test("every character of tee syntax, whitespace and control characters is refused") {
            val ok = policy("203.0.113.10")
            listOf("|", "[", "]", "\\", " ", "\n", "\t", "\u0000").forEach { bad ->
                ok.check("rtmp://a.example.org/live/key${bad}x") shouldBe PauseFeedTargetCheck.Rejected(RejectReason.FORBIDDEN_CHARACTER)
            }
        }

        test("only rtmp and rtmps are accepted") {
            val ok = policy("203.0.113.10")
            listOf(
                "http://a.example.org/x",
                "https://a.example.org/x",
                "file:///etc/passwd",
                "tcp://a.example.org:1935",
                "a.example.org/live",
            ).forEach {
                ok.check(it) shouldBe PauseFeedTargetCheck.Rejected(RejectReason.SCHEME)
            }
        }

        test("rtmps is refused when no CA bundle is available (no unverified TLS)") {
            policy("203.0.113.10", allowRtmps = false).check("rtmps://a.example.org/live/k") shouldBe
                PauseFeedTargetCheck.Rejected(RejectReason.TLS_UNVERIFIABLE)
            policy("203.0.113.10", allowRtmps = false).check("rtmp://a.example.org/live/k") shouldBe
                PauseFeedTargetCheck.Ok("rtmp://a.example.org/live/k")
        }

        test("loopback, private, link-local, carrier-grade NAT, ULA and mixed answers are all refused") {
            listOf(
                "127.0.0.1",
                "10.1.2.3",
                "172.16.0.5",
                "172.31.255.1",
                "192.168.1.1",
                "169.254.169.254",
                "100.64.0.1",
                "100.127.255.254",
                "0.0.0.0",
                "224.0.0.1",
                "::1",
                "fd00::1",
                "fe80::1",
                "::",
                "::ffff:10.0.0.1",
            ).forEach { address ->
                policy(address).check("rtmp://a.example.org/live/key") shouldBe PauseFeedTargetCheck.Rejected(RejectReason.PRIVATE_ADDRESS)
            }
            // a name with one public and one private address is refused as a whole
            policy("203.0.113.10", "10.0.0.5").check("rtmp://a.example.org/live/key") shouldBe
                PauseFeedTargetCheck.Rejected(RejectReason.PRIVATE_ADDRESS)
        }

        test("neighbours of the blocked ranges are public") {
            listOf(
                "172.15.255.255",
                "172.32.0.1",
                "100.63.255.255",
                "100.128.0.1",
                "11.0.0.1",
                "198.17.0.1",
                "198.20.0.1",
            ).forEach { address ->
                policy(address).check("rtmp://a.example.org/live/key") shouldBe PauseFeedTargetCheck.Ok("rtmp://a.example.org/live/key")
            }
        }

        test("octal, hex, short and integer IPv4 forms that glibc reads differently from Java are refused") {
            val ok = policy("203.0.113.10")
            listOf(
                "0177.0.0.1",
                "172.032.0.2",
                "012.1.2.3",
                "2130706433",
                "127.1",
                "0x7f.0.0.1",
                "0x7f000001",
                "1.2.3.4.5",
                "1.2.3.",
            ).forEach {
                // java.net.URI may already refuse the odd forms (host == null -> UNRESOLVABLE); either way no feed.
                (ok.check("rtmp://$it:1935/a/k") is PauseFeedTargetCheck.Rejected) shouldBe true
            }
            listOf("0177.0.0.1", "172.032.0.2", "012.1.2.3", "2130706433", "127.1").forEach {
                PauseFeedTargetPolicy.isAmbiguousNumericHost(it) shouldBe true
            }
            PauseFeedTargetPolicy.isAmbiguousNumericHost("203.0.113.10") shouldBe false
            PauseFeedTargetPolicy.isAmbiguousNumericHost("cafe.de") shouldBe false
            ok.check("rtmp://203.0.113.10/a/k") shouldBe PauseFeedTargetCheck.Ok("rtmp://203.0.113.10/a/k")
            ok.check("rtmp://cafe.de/a/k") shouldBe PauseFeedTargetCheck.Ok("rtmp://cafe.de/a/k")
        }

        test("an unresolvable or empty answer is refused") {
            PauseFeedTargetPolicy(resolver = { throw UnknownHostException("x") }, allowRtmps = true)
                .check("rtmp://nope.example.org/live/key") shouldBe PauseFeedTargetCheck.Rejected(RejectReason.UNRESOLVABLE)
            PauseFeedTargetPolicy(resolver = { emptyList() }, allowRtmps = true)
                .check("rtmp://nope.example.org/live/key") shouldBe PauseFeedTargetCheck.Rejected(RejectReason.UNRESOLVABLE)
        }
    })
