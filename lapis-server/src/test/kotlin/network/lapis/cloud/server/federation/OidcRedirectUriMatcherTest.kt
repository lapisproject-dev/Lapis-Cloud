package network.lapis.cloud.server.federation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pure unit coverage for [OidcRedirectUriMatcher] -- the component that carves the ONE exception
 * (Welle V1.8.1) into this server's otherwise-strict HTTPS-only/exact-match `redirect_uri` rule.
 * Before this test, none of its invariants were pinned anywhere -- see the finding this test
 * closes.
 */
class OidcRedirectUriMatcherTest :
    FunSpec({
        test("exact match always succeeds, loopback flexibility on or off") {
            OidcRedirectUriMatcher.matches(
                registered = "https://client.example/cb",
                presented = "https://client.example/cb",
                allowLoopbackPortFlexibility = false,
            ) shouldBe true
            OidcRedirectUriMatcher.matches(
                registered = "http://127.0.0.1:9999/cb",
                presented = "http://127.0.0.1:9999/cb",
                allowLoopbackPortFlexibility = true,
            ) shouldBe true
        }

        test("a confidential client (allowLoopbackPortFlexibility=false) NEVER gets the port relaxation, even for a loopback URI") {
            OidcRedirectUriMatcher.matches(
                registered = "http://127.0.0.1:8080/cb",
                presented = "http://127.0.0.1:9999/cb",
                allowLoopbackPortFlexibility = false,
            ) shouldBe false
        }

        test("a public client's loopback redirect_uri matches across different ports") {
            OidcRedirectUriMatcher.matches(
                registered = "http://127.0.0.1:8080/cb",
                presented = "http://127.0.0.1:54321/cb",
                allowLoopbackPortFlexibility = true,
            ) shouldBe true
        }

        test("\"localhost\" is NEVER treated as loopback, even for a public client -- a DNS name is not a loopback literal") {
            OidcRedirectUriMatcher.matches(
                registered = "http://localhost:8080/cb",
                presented = "http://localhost:9999/cb",
                allowLoopbackPortFlexibility = true,
            ) shouldBe false
        }

        test("scheme, host, path, query and fragment must still match exactly under loopback flexibility -- only the port is relaxed") {
            val base = "http://127.0.0.1:8080"
            OidcRedirectUriMatcher.matches(
                registered = "$base/cb",
                presented = "http://127.0.0.1:9999/other-path",
                allowLoopbackPortFlexibility = true,
            ) shouldBe false
            OidcRedirectUriMatcher.matches(
                registered = "$base/cb?x=1",
                presented = "http://127.0.0.1:9999/cb?x=2",
                allowLoopbackPortFlexibility = true,
            ) shouldBe false
            OidcRedirectUriMatcher.matches(
                registered = "$base/cb#a",
                presented = "http://127.0.0.1:9999/cb#b",
                allowLoopbackPortFlexibility = true,
            ) shouldBe false
            OidcRedirectUriMatcher.matches(
                registered = "$base/cb",
                presented = "http://127.0.0.1:9999/cb",
                allowLoopbackPortFlexibility = true,
            ) shouldBe true
        }

        test("an HTTPS loopback URI never gets the port relaxation -- isLoopbackRedirectUri requires the http scheme") {
            OidcRedirectUriMatcher.matches(
                registered = "https://127.0.0.1:8080/cb",
                presented = "https://127.0.0.1:9999/cb",
                allowLoopbackPortFlexibility = true,
            ) shouldBe false
        }

        test("a malformed URI is rejected, not thrown") {
            OidcRedirectUriMatcher.matches(
                registered = "http://127.0.0.1:8080/cb",
                presented = "not a uri at all ::: %%%",
                allowLoopbackPortFlexibility = true,
            ) shouldBe false
        }

        test("isLoopbackRedirectUri: 127.0.0.1 and bracketed [::1] are loopback, localhost and a public IP are not") {
            OidcRedirectUriMatcher.isLoopbackRedirectUri("http://127.0.0.1:8080/cb") shouldBe true
            OidcRedirectUriMatcher.isLoopbackRedirectUri("http://[::1]:8080/cb") shouldBe true
            OidcRedirectUriMatcher.isLoopbackRedirectUri("http://localhost:8080/cb") shouldBe false
            OidcRedirectUriMatcher.isLoopbackRedirectUri("http://93.184.216.34:8080/cb") shouldBe false
            OidcRedirectUriMatcher.isLoopbackRedirectUri("https://127.0.0.1:8080/cb") shouldBe false
        }
    })
