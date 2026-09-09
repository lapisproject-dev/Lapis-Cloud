package network.lapis.cloud.server.routes

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.legal.LegalConfig

/**
 * Welle V1.4.7 "Rechtstexte" -- regression tests for [PublicChrome.renderPublicFooter]'s rollout
 * across all 13 call sites (11 pre-existing + the two new [LegalHtml] pages). See § 0/V5, § 0/V6 in
 * the implementation plan for the two special-cased call sites this file guards against regressing.
 */
class PublicFooterTest :
    FunSpec({
        val baseUrl = "https://cloud.lapisproject.dev"
        val branding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null)

        fun post(id: String = "11111111-1111-1111-1111-111111111111") =
            PublicPostView(
                id = id,
                depth = 0,
                authorDisplayName = "Alice",
                contentLines = listOf("Hallo Welt"),
                totalWeightLtr = "3.14",
                ownWeightLtr = "3.14",
                publishedAtIso = "2026-08-18T12:00:00",
                publishedAtHuman = "18.08.2026",
            )

        fun completeLegalConfig(): LegalConfig =
            LegalConfig.load { key ->
                mapOf(
                    LegalConfig.ENV_OPERATOR_NAME to "Beispielverein e. V.",
                    LegalConfig.ENV_STREET to "Musterweg 1",
                    LegalConfig.ENV_POSTAL_CODE to "12345",
                    LegalConfig.ENV_CITY to "Musterstadt",
                    LegalConfig.ENV_COUNTRY to "Deutschland",
                    LegalConfig.ENV_CONTACT_EMAIL to "info@example.org",
                    LegalConfig.ENV_REPRESENTATIVE to "Max Muster",
                )[key]
            }

        val renderedPages: Map<String, String> =
            mapOf(
                "PublicLandingHtml.page" to
                    PublicLandingHtml.page(
                        view = PublicLandingView(stats = null, topPosts = emptyList()),
                        baseUrl = baseUrl,
                        branding = branding,
                    ),
                "PublicTransparencyHtml.page" to
                    PublicTransparencyHtml.page(
                        view =
                            PublicTransparencyView(
                                stats = PublicTransparencyStats(activeMemberCount = 1L, mintedLtrTotal = "0.00", publicPostCount = 0L),
                                board = emptyList(),
                                topPosts = emptyList(),
                                ltr = null,
                                donations = null,
                                donationYear = 2026,
                            ),
                        baseUrl = baseUrl,
                        branding = branding,
                    ),
                "SocialPublicHtml.timelinePage" to
                    SocialPublicHtml.timelinePage(
                        view = PublicTimelineView(posts = emptyList(), page = 1, hasNext = false),
                        baseUrl = baseUrl,
                        branding = branding,
                    ),
                "SocialPublicHtml.postPage" to
                    SocialPublicHtml.postPage(
                        view = PublicThreadView(root = post(), descendants = emptyList(), truncated = false),
                        baseUrl = baseUrl,
                        branding = branding,
                    ),
                "SocialPublicHtml.notFoundPage" to SocialPublicHtml.notFoundPage(baseUrl = baseUrl, branding = branding),
                "SocialPublicHtml.tooManyRequestsPage" to SocialPublicHtml.tooManyRequestsPage(baseUrl = baseUrl, branding = branding),
                "SocialPublicHtml.malformedRequestPage" to SocialPublicHtml.malformedRequestPage(baseUrl = baseUrl, branding = branding),
                "SocialPublicHtml.serverErrorPage" to SocialPublicHtml.serverErrorPage(baseUrl = baseUrl, branding = branding),
                "SocialPublicHtml.legallyRemovedPage" to
                    SocialPublicHtml.legallyRemovedPage(
                        view =
                            PublicRemovalNoticeView(
                                postId = "11111111-1111-1111-1111-111111111111",
                                reasonLines = listOf("Grund"),
                                removedAtIso = "2026-08-18T12:00:00",
                                removedAtHuman = "18.08.2026",
                            ),
                        baseUrl = baseUrl,
                        branding = branding,
                    ),
                "SocialPublicHtml.reportFormPage" to
                    SocialPublicHtml.reportFormPage(
                        postId = "11111111-1111-1111-1111-111111111111",
                        baseUrl = baseUrl,
                        branding = branding,
                    ),
                "SocialPublicHtml.reportSubmittedPage" to SocialPublicHtml.reportSubmittedPage(baseUrl = baseUrl, branding = branding),
                "LegalHtml.imprintPage" to
                    LegalHtml.imprintPage(legal = completeLegalConfig(), baseUrl = baseUrl, branding = branding, lang = PublicLanguage.DE),
                "LegalHtml.privacyPage" to
                    LegalHtml.privacyPage(legal = completeLegalConfig(), baseUrl = baseUrl, branding = branding, lang = PublicLanguage.DE),
            )

        test("F1: every one of the 13 render call sites emits both the Impressum and Datenschutz footer links") {
            renderedPages.forEach { (name, html) ->
                withClue(name) {
                    html shouldContain "href=\"$baseUrl/impressum\""
                    html shouldContain "href=\"$baseUrl/datenschutz\""
                }
            }
        }

        test("F2: legallyRemovedPage still carries the backToTimeline link (regression, plan § 0/V6)") {
            val html = renderedPages.getValue("SocialPublicHtml.legallyRemovedPage")
            html shouldContain "Zur Timeline"
        }

        test("F3: serverErrorPage's first footer line stays exactly \"{title} · Betrieben mit Lapis Cloud\" (regression, plan § 0/V5)") {
            val html = renderedPages.getValue("SocialPublicHtml.serverErrorPage")
            html shouldContain "${BrandConfig.DEFAULT_TITLE} · Betrieben mit Lapis Cloud"
        }

        test("F4: EmbedHtml pages carry no /impressum link (out of scope -- own legal notice is the embedding page's job)") {
            val loginPage =
                EmbedHtml.loginPage(
                    baseUrl = baseUrl,
                    brandTitle = BrandConfig.DEFAULT_TITLE,
                    requesterOriginHost = "example.org",
                    targetOrigin = "https://example.org",
                    state = "state-token",
                )
            loginPage shouldNotContain "/impressum"
        }

        test("F4b: EventPublicHtml pages DO carry site-relative Impressum/Datenschutz links (plan § 11/OF-1)") {
            val html = EventPublicHtml.notFoundPage(brandTitle = BrandConfig.DEFAULT_TITLE)
            html shouldContain "href=\"/impressum\""
            html shouldContain "href=\"/datenschutz\""
        }

        test("F5: both footer <a> tags carry hreflang=\"de\"") {
            val html = renderedPages.getValue("SocialPublicHtml.timelinePage")
            html shouldContain "<a href=\"$baseUrl/impressum\" hreflang=\"de\">"
            html shouldContain "<a href=\"$baseUrl/datenschutz\" hreflang=\"de\">"
        }
    })
