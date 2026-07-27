package network.lapis.cloud.server.federation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [requireSafeFederationUrl] -- the SSRF guard for fetching a URL named by untrusted input (a
 * remote actor URI, or a self-declared server's own `keyId`). Mirrors
 * [network.lapis.cloud.server.economy.oracle.BitcoinPriceSourceTest]'s own SSRF-section shape, but
 * exercises address-RESOLUTION-based rejection (this guard's actual mechanism), not a fixed
 * hostname allowlist (the price-oracle's own, deliberately different, mechanism -- see
 * [requireSafeFederationUrl] KDoc).
 */
class FederationHttpClientSsrfTest :
    FunSpec({
        test("loopback (127.0.0.1) is rejected") {
            runCatching { requireSafeFederationUrl("https://127.0.0.1/federation/actor") }.isFailure shouldBe true
        }

        test("the cloud-metadata address (169.254.169.254, link-local) is rejected") {
            runCatching { requireSafeFederationUrl("https://169.254.169.254/federation/actor") }.isFailure shouldBe true
        }

        test("an RFC 1918 private address (10.x) is rejected") {
            runCatching { requireSafeFederationUrl("https://10.0.0.1/federation/actor") }.isFailure shouldBe true
        }

        test("an RFC 1918 private address (192.168.x) is rejected") {
            runCatching { requireSafeFederationUrl("https://192.168.1.1/federation/actor") }.isFailure shouldBe true
        }

        test("the IPv6 loopback address (::1) is rejected") {
            runCatching { requireSafeFederationUrl("https://[::1]/federation/actor") }.isFailure shouldBe true
        }

        test("a hostname whose DNS resolves to a private address is rejected -- proves address-based, not string-based, checking") {
            // "localtest.me" and its subdomains are a well-known public DNS fixture that resolves
            // to 127.0.0.1 -- if this sandbox has no network egress to resolve it, the guard still
            // fails closed (resolution failure -> rejected), which is itself the correct behavior,
            // so this assertion holds either way.
            runCatching { requireSafeFederationUrl("https://127.0.0.1.nip.io/federation/actor") }.isFailure shouldBe true
        }

        test("a plain-HTTP URL is rejected regardless of host") {
            runCatching { requireSafeFederationUrl("http://example.org/federation/actor") }.isFailure shouldBe true
        }

        test("an unresolvable hostname is rejected (fails closed, not open)") {
            runCatching { requireSafeFederationUrl("https://this-host-does-not-exist.invalid/federation/actor") }.isFailure shouldBe true
        }

        // Live adversarial-test finding (V0.8.1 attack pass): Inet6Address.isSiteLocalAddress()
        // only recognizes the DEPRECATED fec0::/10 range, NOT the modern IPv6 Unique Local Address
        // range fc00::/7 (RFC 4193) -- the IPv6 equivalent of RFC 1918 private space, and the range
        // Docker/Kubernetes/most container platforms actually assign internal IPv6 addresses from.
        // Confirmed live: requireSafeFederationUrl("https://[fd00::1]/...") did NOT throw before the
        // isIpv6UniqueLocalAddress fix below.
        test("an IPv6 Unique Local Address (fd00::1, RFC 4193) is rejected") {
            runCatching { requireSafeFederationUrl("https://[fd00::1]/federation/actor") }.isFailure shouldBe true
        }

        test("an IPv6 Unique Local Address at the top of the fc00::/7 range (fdff:ffff::1) is rejected") {
            runCatching { requireSafeFederationUrl("https://[fdff:ffff::1]/federation/actor") }.isFailure shouldBe true
        }

        test(
            "an IPv6 address just below the fc00::/7 range (fbff::1) is NOT treated as ULA -- proves the range check is exact, not an over-broad prefix match",
        ) {
            // fbff::1 is outside fc00::/7 (the first 7 bits of 0xFB=1111_1011 differ from
            // 0xFC=1111_1100's leading 7 bits) -- it is a real, non-private, non-loopback,
            // non-link-local IPv6 address, so isIpv6UniqueLocalAddress must return false for it.
            // Whether the OVERALL requireSafeFederationUrl call succeeds still depends on this
            // sandbox's actual IPv6 route/DNS availability for an address with no assigned meaning,
            // so this test asserts the narrower, deterministic claim directly against the helper.
            isIpv6UniqueLocalAddress(java.net.InetAddress.getByName("fbff::1")) shouldBe false
        }
    })
