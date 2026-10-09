package network.lapis.cloud.server.federation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private fun flex(
    r: String,
    p: String,
    allow: Boolean = true,
): Boolean = OidcRedirectUriMatcher.matches(registered = r, presented = p, allowLoopbackPortFlexibility = allow)

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

        test("localhost is loopback for a public client -- port relaxed, host/path/query exact") {
            OidcRedirectUriMatcher.matches(
                registered = "http://localhost:53682/callback",
                presented = "http://localhost:61111/callback",
                allowLoopbackPortFlexibility = true,
            ) shouldBe true
            OidcRedirectUriMatcher.matches(
                registered = "http://localhost:1/callback",
                presented = "http://localhost:2/other",
                allowLoopbackPortFlexibility = true,
            ) shouldBe false
        }

        test("confidential client: localhost (and loopback literals) are never port-flexible") {
            flex(r = "http://localhost:8080/cb", p = "http://localhost:9999/cb", allow = false) shouldBe false
            flex(r = "http://127.0.0.1:8080/cb", p = "http://127.0.0.1:9999/cb", allow = false) shouldBe false
            flex(r = "http://localhost:8080/cb", p = "http://localhost:8080/cb", allow = false) shouldBe true
        }

        test("localhost is never equivalent to 127.0.0.1 or [::1] (host must match exactly)") {
            listOf(
                "http://localhost:1/cb" to "http://127.0.0.1:2/cb",
                "http://127.0.0.1:1/cb" to "http://localhost:2/cb",
                "http://localhost:1/cb" to "http://[::1]:2/cb",
                "http://[::1]:1/cb" to "http://localhost:2/cb",
            ).forEach { (r, p) ->
                flex(r = r, p = p, allow = true) shouldBe false
            }
        }

        test("loopback flexibility: differing query, encoded path or userinfo on the presented URI are rejected") {
            flex(r = "http://localhost:1/cb?a=1", p = "http://localhost:2/cb?a=2", allow = true) shouldBe false
            flex(r = "http://localhost:1/a/b", p = "http://localhost:2/a%2Fb", allow = true) shouldBe false
            flex(r = "http://localhost:1/cb", p = "http://user@localhost:2/cb", allow = true) shouldBe false
        }

        test("LOOPBACK_HOSTS is exactly the three accepted hosts") {
            OidcRedirectUriMatcher.LOOPBACK_HOSTS shouldBe setOf("127.0.0.1", "[::1]", "localhost")
        }

        test("parseLoopback accepts the legitimate forms") {
            listOf(
                "http://localhost:53682/callback",
                "http://localhost/callback",
                "http://127.0.0.1:1/cb",
                "http://[::1]:65535/cb",
            ).forEach { OidcRedirectUriMatcher.parseLoopback(it) shouldNotBe null }
            OidcRedirectUriMatcher.parseLoopback("http://localhost:53682/callback")!!.port shouldBe 53682
        }

        test("parseLoopback rejects every tamper form") {
            listOf(
                "http://LOCALHOST:1/cb",
                "http://localhost.:1234/cb",
                "http://evil.localhost:1/cb",
                "http://localhost.evil.com/cb",
                "http://localhost@evil.com/cb",
                "http://user@localhost:1/cb",
                "http://localhost:1/cb#x",
                "http://localhost:1/cb#",
                "https://localhost:1/cb",
                "javascript:alert(1)",
                "data:text/html,x",
                "http://localhost:/cb",
                "http://localhost:0/cb",
                "http://localhost:65536/cb",
                "http://localhost:0080/cb",
                "http://localhost:1/c b",
                "http://localhost\\@evil/cb",
                "http://local_host:1/cb",
                "HTTP://localhost:1/cb",
                "http://localhost:1/" + "a".repeat(2100),
            ).forEach { OidcRedirectUriMatcher.parseLoopback(it) shouldBe null }
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
                presented = "http://127.0.0.1:9999/cb#a",
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

        test("isLoopbackRedirectUri: 127.0.0.1, [::1] and localhost are loopback, a public IP is not") {
            OidcRedirectUriMatcher.isLoopbackRedirectUri("http://127.0.0.1:8080/cb") shouldBe true
            OidcRedirectUriMatcher.isLoopbackRedirectUri("http://[::1]:8080/cb") shouldBe true
            OidcRedirectUriMatcher.isLoopbackRedirectUri("http://localhost:8080/cb") shouldBe true
            OidcRedirectUriMatcher.isLoopbackRedirectUri("http://93.184.216.34:8080/cb") shouldBe false
            OidcRedirectUriMatcher.isLoopbackRedirectUri("https://127.0.0.1:8080/cb") shouldBe false
        }
    })
