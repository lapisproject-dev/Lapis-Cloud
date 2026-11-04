package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import network.lapis.cloud.server.branding.LogoVariant
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.branding.ResolvedLogoFile
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.legal.LegalConfig
import network.lapis.cloud.server.theme.ResolvedTheme
import network.lapis.cloud.server.theme.ThemeCssRenderer
import kotlin.time.Duration.Companion.minutes

/**
 * V1.9.265 -- the CSP trap: with ONLY logo variants configured (no legacy logo) the public pages must still allow `img-src 'self'`,
 * otherwise the browser blocks every variant logo. Uses the DB-free legal pages as a representative call site of
 * `applyPublicPageHeaders(imgSrcSelf = branding.servesAnyLogo)`.
 */
class BrandLogoVariantsPublicPageTest :
    FunSpec({
        fun legal(): LegalConfig {
            val env =
                mapOf(
                    LegalConfig.ENV_OPERATOR_NAME to "Beispielverein e. V.",
                    LegalConfig.ENV_STREET to "Musterweg 1",
                    LegalConfig.ENV_POSTAL_CODE to "12345",
                    LegalConfig.ENV_CITY to "Musterstadt",
                    LegalConfig.ENV_COUNTRY to "Deutschland",
                    LegalConfig.ENV_CONTACT_EMAIL to "info@example.org",
                    LegalConfig.ENV_REPRESENTATIVE to "Max Muster",
                )
            return LegalConfig.load { key -> env[key] }
        }

        suspend fun page(
            branding: ResolvedBranding,
            block: suspend (csp: String, body: String) -> Unit,
        ) {
            testApplication {
                application {
                    install(XForwardedHeaders) { useLastProxy() }
                    install(AutoHeadResponse)
                    routing {
                        registerLegalRoutes(
                            readRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 1.minutes),
                            branding = branding,
                            legal = legal(),
                            navAvailability = PublicNavAvailabilityProvider(),
                            memberFinderEnabled = { false },
                        )
                    }
                }
                val response = client.get("/impressum")
                block(response.headers["Content-Security-Policy"] ?: error("no CSP"), response.bodyAsText())
            }
        }

        fun onlyVariants(vararg variants: LogoVariant) =
            ResolvedBranding(
                title = "Mein Verein",
                logoAvailable = false,
                logoPath = null,
                variants = variants.associateWith { ResolvedLogoFile(path = "/x/${it.slug}.png", extension = "png") },
            )

        test("only variants configured -> img-src allows 'self' and the page shows the variant logo") {
            page(onlyVariants(LogoVariant.WORDMARK_LIGHT)) { csp, body ->
                csp shouldContain "img-src 'self' data:"
                body shouldContain "/api/branding/logo/wordmark-light"
            }
        }

        test("light and dark wordmark -> two images switched by the colour scheme") {
            page(onlyVariants(LogoVariant.WORDMARK_LIGHT, LogoVariant.WORDMARK_DARK)) { _, body ->
                body shouldContain "chrome-logo chrome-logo--light"
                body shouldContain "chrome-logo chrome-logo--dark"
            }
        }

        test("the stylesheet switches the two chrome logos by the nav lightness tokens") {
            SocialPublicHtml.STYLESHEET shouldContain ".chrome-logo--light { display: var(--lapis-nav-logo-light-display, block); }"
            SocialPublicHtml.STYLESHEET shouldContain ".chrome-logo--dark { display: var(--lapis-nav-logo-dark-display, none); }"
            (SocialPublicHtml.STYLESHEET.contains(".chrome-logo--dark { display: none; }")) shouldBe false
            (SocialPublicHtml.STYLESHEET.contains(".chrome-logo--dark { display: inline; }")) shouldBe false
        }

        test("the default theme (dark nav) serves the dark variant token in the light root block; a light nav keeps the light one") {
            val darkNavCss = ResolvedTheme.DEFAULT.css
            val rootBlock = darkNavCss.lines().single { it.startsWith("html:root{") }
            rootBlock shouldContain "--lapis-nav-logo-dark-display:block"
            rootBlock shouldContain "--lapis-nav-logo-light-display:none"
            ThemeCssRenderer.navLogoDeclarations(dark = false) shouldBe
                "--lapis-nav-logo-light-display:block;--lapis-nav-logo-dark-display:none"
        }

        test("no logo at all -> img-src stays data: only (unchanged)") {
            page(ResolvedBranding(title = "T", logoAvailable = false, logoPath = null)) { csp, body ->
                csp shouldContain "img-src data:"
                (csp.contains("img-src 'self'")) shouldBe false
                body shouldContain "chrome-wordmark"
            }
        }
    })
