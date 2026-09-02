package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** Welle V1.4.1a "Öffentliche Website-Integration" -- [EmbedHtml] determinism and no-echo posture. */
class EmbedHtmlTest :
    FunSpec({
        test("loginPage: two renders with identical input are byte-identical") {
            val first =
                EmbedHtml.loginPage(
                    baseUrl = "https://cloud.example.org",
                    brandTitle = "Lapis Cloud",
                    requesterOriginHost = "partei.example",
                    targetOrigin = "https://partei.example",
                    state = "a".repeat(32),
                )
            val second =
                EmbedHtml.loginPage(
                    baseUrl = "https://cloud.example.org",
                    brandTitle = "Lapis Cloud",
                    requesterOriginHost = "partei.example",
                    targetOrigin = "https://partei.example",
                    state = "a".repeat(32),
                )
            first shouldBe second
        }

        test("loginPage: requesterOriginHost appears only as text, not inside an href/attribute value that could navigate") {
            val body =
                EmbedHtml.loginPage(
                    baseUrl = "https://cloud.example.org",
                    brandTitle = "Lapis Cloud",
                    requesterOriginHost = "partei.example",
                    targetOrigin = "https://partei.example",
                    state = "a".repeat(32),
                )
            body shouldContain "partei.example"
            body shouldNotContain "href=\"https://partei.example\""
        }

        test("rejectedPage never echoes anything -- it is a fixed, generic body regardless of inputs") {
            val body = EmbedHtml.rejectedPage(baseUrl = "https://cloud.example.org", brandTitle = "Lapis Cloud")
            body shouldContain "nicht freigeschaltet"
            body shouldNotContain "evil"
        }

        test("badRequestPage is a fixed, generic body") {
            val body = EmbedHtml.badRequestPage(baseUrl = "https://cloud.example.org", brandTitle = "Lapis Cloud")
            body shouldContain "Ungültige Anfrage"
        }

        test(
            "loginPage never knows the caller's sign-in status at render time (Review-Fund V1.4.1a: the " +
                "signedIn/displayName parameters this function used to accept were structurally always " +
                "false/null in production -- SameSite=Strict means the session cookie never reaches this " +
                "cross-site top-level navigation -- and were removed together with the dead branches they fed). " +
                "The form is therefore always rendered; the sign-in decision moves to /embed/v1/login.js's " +
                "runtime session probe instead.",
        ) {
            val body =
                EmbedHtml.loginPage(
                    baseUrl = "https://cloud.example.org",
                    brandTitle = "Lapis Cloud",
                    requesterOriginHost = "partei.example",
                    targetOrigin = "https://partei.example",
                    state = "b".repeat(32),
                )
            body shouldContain "lapis-login-form"
            body shouldNotContain "data-signed-in"
            body shouldNotContain "data-display-name"
        }
    })
