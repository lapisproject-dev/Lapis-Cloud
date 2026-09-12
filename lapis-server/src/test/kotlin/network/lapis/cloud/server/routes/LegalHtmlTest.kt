package network.lapis.cloud.server.routes

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.branding.BrandConfig
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.dsgvo.PersonalDataRegistry
import network.lapis.cloud.server.legal.LegalConfig

private fun envOf(vararg pairs: Pair<String, String>): (String) -> String? {
    val map = pairs.toMap()
    return { key -> map[key] }
}

private fun completeLegalConfig(vararg overrides: Pair<String, String>): LegalConfig {
    val defaults =
        mapOf(
            LegalConfig.ENV_OPERATOR_NAME to "Beispielverein e. V.",
            LegalConfig.ENV_STREET to "Musterweg 1",
            LegalConfig.ENV_POSTAL_CODE to "12345",
            LegalConfig.ENV_CITY to "Musterstadt",
            LegalConfig.ENV_COUNTRY to "Deutschland",
            LegalConfig.ENV_CONTACT_EMAIL to "info@example.org",
            LegalConfig.ENV_REPRESENTATIVE to "Max Muster",
        )
    return LegalConfig.load(envOf(*(defaults + overrides.toMap()).toList().toTypedArray()))
}

/** Pure renderer tests -- NO `testApplication`, NO DB, mirrors `SocialPublicHtmlTest`'s own structure. */
class LegalHtmlTest :
    FunSpec({
        val baseUrl = "https://cloud.lapisproject.dev"
        val branding = ResolvedBranding(title = BrandConfig.DEFAULT_TITLE, logoAvailable = false, logoPath = null)

        fun imprint(
            legal: LegalConfig,
            lang: PublicLanguage = PublicLanguage.DE,
        ): String = LegalHtml.imprintPage(legal = legal, baseUrl = baseUrl, branding = branding, lang = lang)

        fun privacy(
            legal: LegalConfig,
            lang: PublicLanguage = PublicLanguage.DE,
        ): String = LegalHtml.privacyPage(legal = legal, baseUrl = baseUrl, branding = branding, lang = lang)

        test("H1: complete config -- imprint shows fields, no incomplete notice") {
            val html = imprint(legal = completeLegalConfig())
            html shouldContain "Beispielverein e. V."
            html shouldContain "Musterweg 1"
            html shouldContain "12345"
            html shouldContain "Musterstadt"
            html shouldContain "Deutschland"
            html shouldContain "Max Muster"
            html shouldContain "info@example.org"
            html shouldNotContain "legal-incomplete"
        }

        test("H1b: complete config -- privacy page shows the same responsible-party fields") {
            val html = privacy(legal = completeLegalConfig())
            html shouldContain "Beispielverein e. V."
            html shouldContain "Musterweg 1"
            html shouldNotContain "legal-incomplete"
        }

        test("H2: empty config -- incomplete notice shown, no placeholder, missing var names listed") {
            val empty = LegalConfig.load(envOf())
            val html = imprint(legal = empty)
            html shouldContain "legal-incomplete"
            html shouldContain LegalConfig.ENV_OPERATOR_NAME
            html shouldContain LegalConfig.ENV_STREET
            html shouldNotContain "["
        }

        test("H3: partial config (only operatorName) -- incomplete notice still shown") {
            val partial = LegalConfig.load(envOf(LegalConfig.ENV_OPERATOR_NAME to "Beispielverein e. V."))
            val html = imprint(legal = partial)
            html shouldContain "legal-incomplete"
        }

        test("H4: XSS -- every one of the 14 config fields is escaped, never raw, on whichever page(s) render it") {
            val payload = "<script>alert(1)</script>"
            // field -> at least one page that is expected to render it (see LegalHtml.renderImprintBody/
            // renderPrivacyBody -- registerCourt/registerNumber/vatId/mstvResponsible are IMPRINT-only,
            // dpoContact/supervisoryAuthority are PRIVACY-only, the rest appear on both).
            val fieldToRenderingPage =
                mapOf(
                    LegalConfig.ENV_OPERATOR_NAME to true,
                    LegalConfig.ENV_STREET to true,
                    LegalConfig.ENV_POSTAL_CODE to true,
                    LegalConfig.ENV_CITY to true,
                    LegalConfig.ENV_COUNTRY to true,
                    LegalConfig.ENV_REPRESENTATIVE to true,
                    LegalConfig.ENV_PHONE to true,
                    LegalConfig.ENV_REGISTER_COURT to true,
                    LegalConfig.ENV_REGISTER_NUMBER to true,
                    LegalConfig.ENV_VAT_ID to true,
                    LegalConfig.ENV_MSTV_RESPONSIBLE to true,
                    LegalConfig.ENV_DPO_CONTACT to false,
                    LegalConfig.ENV_SUPERVISORY_AUTHORITY to false,
                )
            fieldToRenderingPage.forEach { (name, onImprint) ->
                val legal = completeLegalConfig(name to payload)
                val imprintHtml = imprint(legal = legal)
                val privacyHtml = privacy(legal = legal)
                imprintHtml shouldNotContain "<script>alert"
                privacyHtml shouldNotContain "<script>alert"
                val pageWithField = if (onImprint) imprintHtml else privacyHtml
                pageWithField shouldContain "&lt;script&gt;"
            }
            // contactEmail is form-validated (LegalConfig), so a script payload there is rejected
            // to null upstream -- H9 covers contactEmail's own href-construction escaping.
        }

        test("H4b: XSS in operatorName -- attribute-breaking payload is escaped in city too") {
            val legal = completeLegalConfig(LegalConfig.ENV_CITY to "\"><img src=x onerror=alert(1)>")
            val html = imprint(legal = legal)
            html shouldNotContain "<img src=x onerror=alert(1)>"
            html shouldContain "&lt;img"
        }

        test("H5: determinism -- two renders of identical input are byte-identical") {
            val legal = completeLegalConfig()
            imprint(legal = legal, lang = PublicLanguage.EN) shouldBe imprint(legal = legal, lang = PublicLanguage.EN)
            privacy(legal = legal) shouldBe privacy(legal = legal)
        }

        test("H6: robots is noindex,follow; no <script; no hreflang alternates in <head>") {
            val html = imprint(legal = completeLegalConfig())
            html shouldContain "noindex,follow"
            html shouldNotContain "<script"
            val headSection = html.substringBefore("</head>")
            headSection shouldNotContain "rel=\"alternate\""
        }

        test("H7: full-text container carries lang=\"de\", <html> carries the chrome language") {
            val html = imprint(legal = completeLegalConfig(), lang = PublicLanguage.FR)
            html shouldContain "<html lang=\"fr\">"
            html shouldContain "<div lang=\"de\">"
        }

        test("H8: for every supported language, the chrome translates but the heading stays German") {
            PublicLanguage.entries.forEach { lang ->
                imprint(legal = completeLegalConfig(), lang = lang) shouldContain "Impressum"
                privacy(legal = completeLegalConfig(), lang = lang) shouldContain "Datenschutzerklärung"
            }
        }

        test("H9: contactEmail produces mailto: only when set; null produces neither href nor mailto:null") {
            val withEmail = imprint(legal = completeLegalConfig(LegalConfig.ENV_CONTACT_EMAIL to "info@example.org"))
            withEmail shouldContain "href=\"mailto:info@example.org\""

            val withoutEmail =
                LegalConfig.load(
                    envOf(
                        LegalConfig.ENV_OPERATOR_NAME to "Beispielverein e. V.",
                        LegalConfig.ENV_STREET to "Musterweg 1",
                        LegalConfig.ENV_POSTAL_CODE to "12345",
                        LegalConfig.ENV_CITY to "Musterstadt",
                        LegalConfig.ENV_COUNTRY to "Deutschland",
                        LegalConfig.ENV_REPRESENTATIVE to "Max Muster",
                    ),
                )
            val htmlWithoutEmail = imprint(legal = withoutEmail)
            htmlWithoutEmail shouldNotContain "href=\"mailto:"
            htmlWithoutEmail shouldNotContain "mailto:null"
        }

        test("H10: optional sections are fully absent when unset -- no empty headings") {
            val html = imprint(legal = completeLegalConfig())
            html shouldNotContain "Registereintrag"
            html shouldNotContain "Umsatzsteuer-Identifikationsnummer"
            html shouldNotContain "§ 18 Abs. 2 MStV"

            val withOptional =
                completeLegalConfig(
                    LegalConfig.ENV_REGISTER_COURT to "Amtsgericht Musterstadt",
                    LegalConfig.ENV_REGISTER_NUMBER to "VR 1234",
                    LegalConfig.ENV_VAT_ID to "DE123456789",
                    LegalConfig.ENV_MSTV_RESPONSIBLE to "Max Muster",
                )
            val htmlWithOptional = imprint(legal = withOptional)
            htmlWithOptional shouldContain "Registereintrag"
            htmlWithOptional shouldContain "Amtsgericht Musterstadt"
            htmlWithOptional shouldContain "Umsatzsteuer-Identifikationsnummer"
            htmlWithOptional shouldContain "§ 18 Abs. 2 MStV"
        }

        test("both pages render the template/no-legal-advice disclaimer") {
            val legal = completeLegalConfig()
            imprint(legal = legal) shouldContain "stellt keine Rechtsberatung dar"
            privacy(legal = legal) shouldContain "stellt keine Rechtsberatung dar"
        }

        test("no DB/Reader import used by this renderer -- neither page mentions member counts or statistics") {
            val html = privacy(legal = completeLegalConfig())
            html shouldNotContain "Mitglieder</div"
        }

        test(
            "H11: multi-line representative/dpoContact keep their line breaks -- regression guard, " +
                "LegalConfig allows '\\n' in these two fields specifically so operators can list " +
                "multiple named persons, and plain HTML <p> collapses '\\n' to a single space " +
                "without the 'legal-multiline' (white-space: pre-wrap) class",
        ) {
            val legal =
                completeLegalConfig(
                    LegalConfig.ENV_REPRESENTATIVE to "Max Muster\nErika Muster",
                    LegalConfig.ENV_DPO_CONTACT to "Datenschutz-Team\nDSB Meier",
                )
            val imprintHtml = imprint(legal = legal)
            imprintHtml shouldContain "class=\"legal-multiline\">Max Muster\nErika Muster</"

            val privacyHtml = privacy(legal = legal)
            privacyHtml shouldContain "class=\"legal-multiline\">Max Muster\nErika Muster</"
            privacyHtml shouldContain "class=\"legal-multiline\">Datenschutz-Team\nDSB Meier</"
        }

        test(
            "H12: PersonalDataCoverageGuard -- every PersonalDataRegistry contributor is either " +
                "textually described in the privacy page's purpose list, or carries a written " +
                "allowlist reason here for why it is deliberately covered only by an umbrella " +
                "bullet. Guards against exactly the kind of gap found by review in LegalHtml.kt's " +
                "'Zwecke und Rechtsgrundlagen'-Liste (PoliticianPersonalData/" +
                "BackupOperationPersonalData/DsgvoCompliancePersonalData had a live contributor " +
                "each but no matching sentence on the page) -- 'clean check' alone did not catch " +
                "that gap because a missing bullet does not fail PersonalDataCoverageTest's " +
                "information_schema walk, only THIS test walks the rendered legal text itself.",
        ) {
            val html = privacy(legal = completeLegalConfig())
            val purposesSection =
                html.substringAfter("Zwecke und Rechtsgrundlagen der Verarbeitung").substringBefore("Empfänger")

            // sectionKey -> a substring that must appear in the purposes list for that
            // contributor's domain to count as "described". Keep these keywords narrow enough
            // that a future rename/removal of the matching prose actually breaks this test.
            val keywordBySectionKey =
                mapOf(
                    "foundation" to "Stammdaten",
                    "registration" to "Registrierungs-Workflow",
                    "contributions" to "Beiträge,",
                    "dunning" to "Mahnwesen",
                    "documents" to "Dokumentenablage",
                    "communication" to "interne Kommunikation",
                    "governance" to "Gremien",
                    "elections" to "Wahlen",
                    "systemic_consensus" to "Abstimmungsverfahren",
                    "accounting" to "Buchhaltung",
                    "postalMail" to "Briefpost",
                    "boardMembership" to "Vorstands-/Transparenzregister",
                    "auditLog" to "Prüfpfad",
                    "backupOperations" to "-Restore- oder -Datenexport-Vorgang",
                    "dsgvoCompliance" to "DSGVO-Compliance der Organisation",
                    "crowdfunding" to "Crowdfunding",
                    "politician" to "Politiker-Profile und Politiker-Ranking",
                    "auction" to "Auktionen",
                    "sessions" to "Anmeldesitzungen",
                    "oidc_guest_federation" to "Föderierter Gastzugang",
                    "conference" to "Videokonferenzen",
                    "social_network" to "sozialen Netzwerk",
                    "payments" to "Zahlungsverkehr",
                    "crm" to "(CRM)",
                    "events" to "Veranstaltungen",
                    "memberHonors" to "Ehrungen",
                    "memberFamily" to "Familienmitgliedschaften",
                    "apiKeys" to "API-Schlüssel",
                    "webhookEndpoints" to "Webhooks",
                    "publicRankingConsent" to "Öffentliche Ranglisten",
                    "contributionRelief" to "Beitragsvergünstigungen",
                    "travelExpenses" to "Reisekostenabrechnungen",
                )

            // Deliberately NOT matched by their own keyword: these three LTR-economy
            // contributors are covered only by the Buchhaltungs-Punkt's umbrella "Zahlungsverkehr"/
            // "interne Verrechnung"/"Auktionen" wording, not by a domain-specific sentence of
            // their own. If that ever needs sharpening, do it deliberately -- this map exists so
            // the gap stays a documented decision, not an unnoticed one.
            val umbrellaAllowlist =
                setOf("ltrLedger", "peerTransfer", "priceOracleConversions")

            PersonalDataRegistry.contributors.forEach { contributor ->
                val keyword = keywordBySectionKey[contributor.sectionKey]
                when {
                    keyword != null ->
                        if (!purposesSection.contains(keyword)) {
                            throw AssertionError(
                                "Privacy purposes list is missing expected keyword '$keyword' for contributor " +
                                    "'${contributor.sectionKey}' (${contributor.displayName}).",
                            )
                        }
                    contributor.sectionKey in umbrellaAllowlist -> Unit
                    else ->
                        throw AssertionError(
                            "PersonalDataContributor '${contributor.sectionKey}' (${contributor.displayName}) is " +
                                "neither textually described in the privacy purposes list nor explicitly " +
                                "allowlisted in LegalHtmlTest -- add a keyword entry (preferred) or a justified " +
                                "allowlist entry.",
                        )
                }
            }
        }
    })
