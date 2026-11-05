package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.ktor.http.parseQueryString

/** V1.9.286 -- the authorize URL (byte-identical without `forceLogin`) and the strict `?konto=andere` detection. */
class KeycloakAuthorizeUrlTest :
    FunSpec({
        fun url(
            forceLogin: Boolean,
            state: String = "st",
            clientId: String = "client",
        ) = keycloakAuthorizeUrl(
            authorizationEndpoint = "https://idp.example.org/auth",
            clientId = clientId,
            redirectUri = "https://lapis.example.org/auth/keycloak/callback",
            scopes = "openid email profile",
            state = state,
            nonce = "no",
            codeChallenge = "cc",
            forceLogin = forceLogin,
        )

        val golden =
            "https://idp.example.org/auth?response_type=code&client_id=client" +
                "&redirect_uri=https%3A%2F%2Flapis.example.org%2Fauth%2Fkeycloak%2Fcallback" +
                "&scope=openid+email+profile&state=st&nonce=no&code_challenge=cc&code_challenge_method=S256"

        test("without forceLogin: the exact eight parameters in today's order, no prompt") {
            url(forceLogin = false) shouldBe golden
        }

        test("with forceLogin: the same string plus exactly &prompt=login at the end") {
            url(forceLogin = true) shouldBe "$golden&prompt=login"
            url(forceLogin = true) shouldEndWith "&prompt=login"
        }

        test("hostile values are encoded and never create an extra parameter") {
            val hostile = "a&prompt=none=b#c d\r\nX: 1"
            val u = url(forceLogin = false, state = hostile, clientId = hostile)
            val query = u.substringAfter('?')
            query.contains(' ') shouldBe false
            query.contains('#') shouldBe false
            query.contains('\r') shouldBe false
            query.contains('\n') shouldBe false
            val names = query.split('&').map { it.substringBefore('=') }
            names shouldBe
                listOf("response_type", "client_id", "redirect_uri", "scope", "state", "nonce", "code_challenge", "code_challenge_method")
        }

        fun q(s: String) = isOtherAccountRequest(parseQueryString(s))

        test("isOtherAccountRequest: true only for exactly konto=andere") {
            q("konto=andere") shouldBe true
            q("konto=and%65re") shouldBe true
        }

        test("isOtherAccountRequest: every other shape is a normal start") {
            listOf(
                "",
                "konto=Andere",
                "KONTO=andere",
                "konto=",
                "konto",
                "konto=andere&konto=andere",
                "konto=andere&returnTo=%2Fx",
                "konto=andere&x=1",
                "konto=andere%0d%0aX:1",
                "x=1",
            ).forEach { q(it) shouldBe false }
        }
    })
