package network.lapis.cloud.server.keycloak

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

private fun valid(
    url: String,
    allowPrivateHost: Boolean = false,
    allowPlaintext: Boolean = false,
): String? = KeycloakIssuerUrlGuard.validateOrNull(raw = url, allowPrivateHost = allowPrivateHost, allowPlaintext = allowPlaintext)

class KeycloakIssuerUrlGuardTest :
    FunSpec({
        test("https is accepted and normalized without a trailing slash") {
            valid(url = "https://keycloak.example.org") shouldBe "https://keycloak.example.org"
            valid(url = "https://keycloak.example.org/") shouldBe "https://keycloak.example.org"
            valid(url = "https://keycloak.example.org/realms/lapis/") shouldBe "https://keycloak.example.org/realms/lapis"
            valid(url = "https://keycloak.example.org:8443/realms/lapis") shouldBe "https://keycloak.example.org:8443/realms/lapis"
        }

        test("plain http without the opt-in is rejected, even for a public host") {
            valid(url = "http://keycloak.example.org") shouldBe null
        }

        test("plain http is accepted only for loopback and only with the explicit escape flag") {
            valid(url = "http://127.0.0.1:8080/realms/lapis") shouldBe null
            valid(url = "http://127.0.0.1:8080/realms/lapis", allowPlaintext = true) shouldBe "http://127.0.0.1:8080/realms/lapis"
            valid(url = "http://localhost:8080/realms/lapis", allowPlaintext = true) shouldBe "http://localhost:8080/realms/lapis"
            valid(url = "http://192.168.1.5:8080", allowPlaintext = true) shouldBe null
        }

        test("a private host over https is rejected without its own opt-in, accepted with it") {
            listOf(
                "https://10.0.0.5/realms/lapis",
                "https://172.16.1.1/realms/lapis",
                "https://192.168.1.10/realms/lapis",
                "https://169.254.1.1/realms/lapis",
                "https://100.64.0.1/realms/lapis",
            ).forEach { url ->
                valid(url = url) shouldBe null
                valid(url = url, allowPrivateHost = true).shouldNotBeNull()
            }
        }

        test("loopback over https is always accepted regardless of the private-host opt-in") {
            valid(url = "https://127.0.0.1/realms/lapis") shouldBe "https://127.0.0.1/realms/lapis"
            valid(url = "https://localhost/realms/lapis") shouldBe "https://localhost/realms/lapis"
        }

        test("public hosts just outside the private ranges are not blocked") {
            valid(url = "https://172.32.0.1/realms/lapis") shouldBe "https://172.32.0.1/realms/lapis"
            valid(url = "https://100.63.0.1/realms/lapis") shouldBe "https://100.63.0.1/realms/lapis"
        }

        test("malformed URLs, userinfo, query, fragment, control characters and oversize are rejected") {
            valid(url = "::::not a url::::") shouldBe null
            valid(url = "https://user:pass@keycloak.example.org") shouldBe null
            valid(url = "https://keycloak.example.org?x=1") shouldBe null
            valid(url = "https://keycloak.example.org#x") shouldBe null
            valid(url = "https://keycloak.example.org/a\nb") shouldBe null
            valid(url = "https://a.example/" + "x".repeat(250)) shouldBe null
            valid(url = "") shouldBe null
            valid(url = "ftp://keycloak.example.org") shouldBe null
        }

        test("requireAllowedRequestUrl accepts URLs under the pinned issuer, including the issuer URL itself") {
            KeycloakIssuerUrlGuard.requireAllowedRequestUrl(
                urlString = "https://keycloak.example.org/realms/lapis/.well-known/openid-configuration",
                pinnedIssuerUrl = "https://keycloak.example.org/realms/lapis",
            )
            KeycloakIssuerUrlGuard.requireAllowedRequestUrl(
                urlString = "https://keycloak.example.org/realms/lapis",
                pinnedIssuerUrl = "https://keycloak.example.org/realms/lapis",
            )
        }

        test("requireAllowedRequestUrl rejects a host change, a scheme change and a path outside the base, without echoing the URL") {
            val pinned = "https://keycloak.example.org/realms/lapis"
            listOf(
                "https://evil.example/realms/lapis/x",
                "http://keycloak.example.org/realms/lapis/x",
                "https://keycloak.example.org.evil.example/realms/lapis/x",
                "https://keycloak.example.org:444/realms/lapis/x",
                "https://keycloak.example.org/realms/other/x",
            ).forEach { url ->
                val e =
                    shouldThrow<IllegalArgumentException> {
                        KeycloakIssuerUrlGuard.requireAllowedRequestUrl(urlString = url, pinnedIssuerUrl = pinned)
                    }
                e.message.orEmpty() shouldNotContain "evil"
            }
        }
    })
