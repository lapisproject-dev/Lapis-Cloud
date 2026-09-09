package network.lapis.cloud.server.routes

import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.html
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.strong
import kotlinx.html.title
import kotlinx.html.ul
import network.lapis.cloud.server.branding.ResolvedBranding
import network.lapis.cloud.server.legal.LegalConfig

/**
 * V1.4.7 "Rechtstexte" -- renders `/impressum` and `/datenschutz`, [network.lapis.cloud.server
 * .routes.registerLegalRoutes]'s two literal HTML routes. Same five non-negotiable rendering-safety
 * properties every other public-HTML file in this package establishes ([SocialPublicHtml] class
 * KDoc): escape-only `kotlinx.html` API, no request-time-dependent output, identical output for
 * crawler and human. All fourteen [LegalConfig] fields are OPERATOR-controlled, never request
 * input, but still reach the page exclusively through `kotlinx.html`'s ordinary escaping text/
 * attribute APIs -- the same defense-in-depth discipline [LegalConfig]'s own KDoc documents for its
 * control-character/length checks.
 *
 * **The full legal text is German-only, regardless of [PublicLanguage]** -- only the surrounding
 * chrome (nav, footer link labels) is translated, see [PublicUiStrings.legalGermanOnlyNote] and
 * [PublicChrome.renderPublicFooter] KDoc for why. The German-only content sits inside a
 * `<div lang="de">` while the outer `<html>` carries the chrome's own [PublicLanguage.code].
 *
 * **Absolute rule for every sentence below (Design-Team-Review V1.4.7, Jobs): nothing is claimed
 * that is not backed by an actual, checkable place in this codebase.** No processing this
 * application does not actually perform is described, and no processing it DOES perform is
 * omitted. Whoever changes what data this application processes must update this file in the SAME
 * change, not later.
 *
 * This is a TEMPLATE, not legal advice -- see the closing disclaimer both pages render, worded
 * analogously to the established precedent in
 * `network.lapis.cloud.server.rpc.PublicRankingConsentDisclaimer`.
 */
internal object LegalHtml {
    fun imprintPage(
        legal: LegalConfig,
        baseUrl: String,
        branding: ResolvedBranding,
        lang: PublicLanguage,
    ): String =
        skeleton(baseUrl = baseUrl, branding = branding, lang = lang, currentPath = "/impressum", pageTitle = "Impressum") {
            renderImprintBody(legal = legal)
        }

    fun privacyPage(
        legal: LegalConfig,
        baseUrl: String,
        branding: ResolvedBranding,
        lang: PublicLanguage,
    ): String =
        skeleton(
            baseUrl = baseUrl,
            branding = branding,
            lang = lang,
            currentPath = "/datenschutz",
            pageTitle = "Datenschutzerklärung",
        ) { renderPrivacyBody(legal = legal) }

    private fun skeleton(
        baseUrl: String,
        branding: ResolvedBranding,
        lang: PublicLanguage,
        currentPath: String,
        pageTitle: String,
        content: FlowContent.() -> Unit,
    ): String {
        val strings = PublicChrome.stringsFor(lang)
        return createHTML(prettyPrint = false).html {
            attributes["lang"] = lang.code
            renderLegalHead(baseUrl = baseUrl, branding = branding, lang = lang, currentPath = currentPath, pageTitle = pageTitle)
            body(classes = "has-chrome") {
                with(PublicChrome) {
                    renderChrome(lang = lang, active = null, baseUrl = baseUrl, branding = branding, currentPath = currentPath)
                }
                main {
                    attributes["id"] = "main"
                    p(classes = "lang-note") { +strings.legalGermanOnlyNote }
                    div {
                        attributes["lang"] = "de"
                        content()
                    }
                }
                with(PublicChrome) { renderPublicFooter(lang = lang, baseUrl = baseUrl, branding = branding) }
            }
        }
    }

    private fun HTML.renderLegalHead(
        baseUrl: String,
        branding: ResolvedBranding,
        lang: PublicLanguage,
        currentPath: String,
        pageTitle: String,
    ) {
        val canonicalUrl = PublicChrome.languageUrl(baseUrl = baseUrl, currentPath = currentPath, lang = lang)
        head {
            meta(charset = "utf-8")
            meta(name = "viewport", content = "width=device-width, initial-scale=1")
            title { +"$pageTitle – ${branding.title}" }
            meta(name = "description", content = "$pageTitle von ${branding.title}.")
            // noindex,follow (wie /transparenz): eine Rechtstextseite muss AUFFINDBAR sein von der
            // Seite, auf der der Nutzer steht, nicht in einem Suchindex. Kein hreflang-Alternates-
            // Satz -- der Volltext existiert nur auf Deutsch (Klassen-KDoc).
            meta(name = "robots", content = "noindex,follow")
            link(rel = "canonical", href = canonicalUrl)
            link(rel = "stylesheet", href = "/s/assets/style.css")
        }
    }

    /**
     * `role="note"`, nicht `role="alert"` (Zhuo, Design-Team-Review V1.4.7): der Block richtet sich
     * zuerst an den BETREIBER ("bitte vervollständigen Sie..."), erst danach an Besucherinnen und
     * Besucher -- kein Systemfehler-Alarmton für eine reine Konfigurationslücke.
     */
    private fun FlowContent.renderIncompleteNotice(legal: LegalConfig) {
        div(classes = "legal-incomplete") {
            attributes["role"] = "note"
            p { strong { +"Diese Angaben sind vom Betreiber dieser Installation noch zu vervollständigen." } }
            p {
                +"An den Betreiber: setzen Sie die folgenden Umgebungsvariablen und starten Sie den Dienst neu — "
                +legal.missingMandatory.joinToString(", ")
                +"."
            }
            p {
                +(
                    "An Besucherinnen und Besucher: die gesetzlich vorgeschriebenen Anbieterangaben sind hier " +
                        "noch nicht hinterlegt. Wenden Sie sich bitte direkt an den Betreiber dieser Installation."
                )
            }
        }
    }

    /**
     * Shared "Verantwortliche Stelle" section used by BOTH pages -- Impressum's § 5 TMG angaben and
     * Datenschutz's Art. 13 Abs. 1 lit. a/b DSGVO "Verantwortlicher" carry the exact same fields.
     * Renders [renderIncompleteNotice] IN PLACE of the address block when any mandatory field is
     * missing (LegalConfig KDoc "nie daneben, nie als leere Zeile") -- never both.
     */
    private fun FlowContent.renderResponsiblePartyFields(legal: LegalConfig) {
        if (!legal.isComplete) {
            renderIncompleteNotice(legal = legal)
            return
        }
        p { +requireNotNull(legal.operatorName) }
        p {
            +requireNotNull(legal.street)
            +", "
            +requireNotNull(legal.postalCode)
            +" "
            +requireNotNull(legal.city)
            +", "
            +requireNotNull(legal.country)
        }
        p {
            +"Vertreten durch: "
            span(classes = "legal-multiline") { +requireNotNull(legal.representative) }
        }
    }

    private fun FlowContent.renderContactSection(legal: LegalConfig) {
        h2 { +"Kontakt" }
        p {
            +"E-Mail: "
            val email = legal.contactEmail
            if (email != null) {
                a(href = "mailto:$email") { +email }
            } else {
                +"(noch nicht vom Betreiber hinterlegt)"
            }
        }
        legal.phone?.let { phone ->
            p {
                +"Telefon: "
                +phone
            }
        }
    }

    private fun FlowContent.renderImprintBody(legal: LegalConfig) {
        h1 { +"Impressum" }
        h2 { +"Angaben gemäß § 5 TMG" }
        renderResponsiblePartyFields(legal = legal)
        renderContactSection(legal = legal)
        if (legal.registerCourt != null || legal.registerNumber != null) {
            h2 { +"Registereintrag" }
            legal.registerCourt?.let { court ->
                p {
                    +"Registergericht: "
                    +court
                }
            }
            legal.registerNumber?.let { number ->
                p {
                    +"Registernummer: "
                    +number
                }
            }
        }
        legal.vatId?.let { vatId ->
            h2 { +"Umsatzsteuer-Identifikationsnummer" }
            p { +vatId }
        }
        legal.mstvResponsible?.let { responsible ->
            h2 { +"Verantwortlich für den Inhalt nach § 18 Abs. 2 MStV" }
            p { +responsible }
        }
        h2 { +"Technische Plattform" }
        p {
            +"Diese Installation wird mit "
            a(href = "https://cloud.lapisproject.dev") {
                attributes["rel"] = "noopener noreferrer"
                +"Lapis Cloud"
            }
            +" betrieben. Die Anbieterin der Software Lapis Cloud ist nicht Diensteanbieterin im Sinne des § 5 TMG für diese Installation."
        }
        renderTemplateDisclaimer()
    }

    private fun FlowContent.renderPrivacyBody(legal: LegalConfig) {
        h1 { +"Datenschutzerklärung" }
        h2 { +"Verantwortliche Stelle" }
        renderResponsiblePartyFields(legal = legal)
        renderContactSection(legal = legal)
        legal.dpoContact?.let { dpo ->
            h2 { +"Datenschutzbeauftragte(r)" }
            p(classes = "legal-multiline") { +dpo }
        }

        h2 { +"Zwecke und Rechtsgrundlagen der Verarbeitung" }
        p {
            +(
                "Diese Installation verarbeitet personenbezogene Daten ausschließlich für die folgenden, " +
                    "durch die Software vorgegebenen Zwecke:"
            )
        }
        ul {
            li {
                +(
                    "Mitgliederverwaltung: Stammdaten, Beitritts- und Registrierungs-Workflow, Beiträge, " +
                        "Mahnwesen, Familienmitgliedschaften, Ehrungen — Art. 6 Abs. 1 lit. b DSGVO (Vertrag " +
                        "bzw. vorvertragliche Maßnahme, hier: die Mitgliedschaft)."
                )
            }
            li { +"Freund-/Fördererkonten — Art. 6 Abs. 1 lit. b DSGVO." }
            li {
                +(
                    "Buchhaltung, Beitrags-/Spendenkonto, Prüfpfad, Zahlungsverkehr, interne Verrechnung und " +
                        "Auktionen — Art. 6 Abs. 1 lit. c DSGVO (handels-/steuerrechtliche Aufbewahrungspflichten, " +
                        "GoBD/HGB/AO; bei politischen Parteien zusätzlich § 25 PartG)."
                )
            }
            li {
                +(
                    "Gremien, Sitzungen, Wahlen, Abstimmungsverfahren, Vorstands-/Transparenzregister — " +
                        "Art. 6 Abs. 1 lit. b bzw. lit. c DSGVO."
                )
            }
            li {
                +(
                    "Öffentlich einsehbare Beiträge im integrierten sozialen Netzwerk (sofern aktiv genutzt) — " +
                        "Art. 6 Abs. 1 lit. b DSGVO; die Veröffentlichung erfolgt durch das Mitglied selbst."
                )
            }
            li {
                +(
                    "Öffentliche Ranglisten auf der Transparenz-Seite (sofern aktiv genutzt) — Art. 6 Abs. 1 " +
                        "lit. a DSGVO (gesonderte, widerrufbare Einwilligung je Rangliste). Ein Widerruf ist " +
                        "jederzeit möglich und wird spätestens nach kurzer Zwischenspeicherzeit wirksam."
                )
            }
            li {
                +(
                    "Videokonferenzen, einschließlich optionaler Aufzeichnung/Übertragung (sofern aktiv genutzt) " +
                        "— Art. 6 Abs. 1 lit. b DSGVO; die Teilnahme von Gästen erfolgt auf Grundlage einer " +
                        "gesonderten Einwilligung, Art. 6 Abs. 1 lit. a DSGVO."
                )
            }
            li {
                +(
                    "Versand von E-Mails (u. a. Passwort-Rücksetzung, E-Mail-Bestätigung, Benachrichtigungen) " +
                        "— Art. 6 Abs. 1 lit. b bzw. lit. f DSGVO."
                )
            }
            li { +"Versand von Briefpost, sofern vom Betreiber genutzt — Art. 6 Abs. 1 lit. b DSGVO." }
            li {
                +(
                    "Föderierter Gastzugang über andere Lapis-Cloud-Installationen (OIDC), sofern die " +
                        "Föderation aktiv genutzt wird — Art. 6 Abs. 1 lit. b DSGVO."
                )
            }
            li { +"Anmeldesitzungen (Session-Cookie) — Art. 6 Abs. 1 lit. b DSGVO i. V. m. § 25 Abs. 2 TDDDG." }
            li {
                +(
                    "Interessenten-/Sympathisantenverwaltung (CRM), sofern vom Betreiber genutzt — Art. 6 " +
                        "Abs. 1 lit. a bzw. lit. f DSGVO."
                )
            }
            li {
                +(
                    "Veranstaltungen einschließlich Gästeanmeldung, sofern vom Betreiber genutzt — Art. 6 " +
                        "Abs. 1 lit. b DSGVO."
                )
            }
            li {
                +(
                    "Dokumentenablage, interne Kommunikation, Crowdfunding, API-Schlüssel und Webhooks, jeweils " +
                        "nur soweit vom Betreiber genutzt — Art. 6 Abs. 1 lit. b bzw. lit. f DSGVO."
                )
            }
            li {
                +(
                    "Politiker-Profile und Politiker-Ranking, sofern aktiv genutzt — Vergabe/Entzug des " +
                        "Politiker-Status ist eine Organisationsentscheidung (Art. 6 Abs. 1 lit. b DSGVO); " +
                        "die von Mitgliedern abgegebenen Bewertungen (Like/Dislike) sowie die daraus " +
                        "berechneten monatlichen Gewichts-Snapshots beruhen auf dem berechtigten Interesse " +
                        "an einer nachvollziehbaren, mitgliederbasierten Gewichtungsberechnung " +
                        "(Art. 6 Abs. 1 lit. f DSGVO)."
                )
            }
            li {
                +(
                    "Protokollierung, wer einen vollständigen Organisations-Backup-, -Restore- oder " +
                        "-Datenexport-Vorgang ausgelöst hat — Art. 6 Abs. 1 lit. f DSGVO (berechtigtes " +
                        "Interesse an der Nachvollziehbarkeit dieser hochprivilegierten " +
                        "Administrations-Vorgänge)."
                )
            }
            li {
                +(
                    "Dokumentation der DSGVO-Compliance der Organisation (Auftragsverarbeitungsverträge, " +
                        "technische und organisatorische Maßnahmen, Datenschutz-Folgenabschätzungen, " +
                        "Datenpannen), einschließlich wer eine solche Aufzeichnung erstellt, bearbeitet " +
                        "oder gemeldet hat — Art. 6 Abs. 1 lit. c DSGVO i. V. m. Art. 5 Abs. 2, Art. 24 " +
                        "DSGVO (Rechenschaftspflicht)."
                )
            }
        }

        h2 { +"Empfänger" }
        p {
            +(
                "Je nach Konfiguration dieser Installation können folgende Kategorien von Empfängern " +
                    "personenbezogene Daten im Auftrag des Betreibers verarbeiten: ein Zahlungsdienstleister " +
                    "für Zahlungsabwicklung, ein Briefpostdienstleister für den Versand physischer Post, ein " +
                    "Infrastrukturanbieter für Videokonferenzen, ein SMTP-Relay für den E-Mail-Versand sowie " +
                    "— nur bei aktiv genutzter Föderation — andere Lapis-Cloud-Installationen. Welche " +
                    "konkreten Dienstleister das im Einzelnen sind, legt der Betreiber dieser Installation " +
                    "fest; bitte fragen Sie unter der oben genannten Kontaktadresse nach, falls dies hier " +
                    "nicht ergänzt wurde."
            )
        }

        h2 { +"Speicherdauer" }
        p {
            +(
                "Personenbezogene Daten werden gelöscht, sobald sie für die genannten Zwecke nicht mehr " +
                    "erforderlich sind. Handels- und steuerrechtlich aufbewahrungspflichtige Datensätze " +
                    "(insbesondere Buchhaltungsunterlagen) werden stattdessen für die Dauer der gesetzlichen " +
                    "Aufbewahrungsfrist anonymisiert bzw. mit einer dokumentierten Begründung zurückgehalten, " +
                    "statt gelöscht zu werden."
            )
        }

        h2 { +"Ihre Rechte" }
        p {
            +(
                "Sie haben nach Art. 15–21 DSGVO das Recht auf Auskunft, Berichtigung, Löschung, " +
                    "Einschränkung der Verarbeitung, Datenübertragbarkeit sowie Widerspruch. Eine erteilte " +
                    "Einwilligung können Sie jederzeit mit Wirkung für die Zukunft widerrufen (Art. 7 Abs. 3 " +
                    "DSGVO)."
            )
        }
        p {
            +"Mitglieder erreichen Auskunft und Löschung direkt in der Anwendung unter „Meine Daten“."
        }
        p {
            +"Sie haben zudem das Recht, sich bei einer Datenschutzaufsichtsbehörde zu beschweren (Art. 77 DSGVO)."
            legal.supervisoryAuthority?.let { authority -> +" Zuständig ist: $authority." }
        }

        h2 { +"Cookies und lokaler Speicher" }
        p {
            +(
                "Diese Anwendung setzt ein technisch notwendiges Session-Cookie für die Anmeldung. Zusätzlich " +
                    "speichert der Browser lokal (im Local Storage des Endgeräts, nicht auf dem Server) " +
                    "folgende technisch notwendige Einträge: die zuletzt gewählte Oberflächensprache, der " +
                    "Auf-/Zugeklappt-Zustand der Seitenleisten-Gruppen sowie — nur bei Nutzung des " +
                    "Videokonferenz-Moduls — die zuletzt gewählte Mikrofon-, Kamera- und Lautsprecher-" +
                    "Geräte-ID. Diese lokalen Speichereinträge verlassen das Endgerät nie und werden nicht an " +
                    "den Server übertragen. Es findet kein Tracking, keine Analyse und keine Einbindung von " +
                    "Drittanbieter-Skripten statt."
            )
        }
        renderTemplateDisclaimer()
    }

    /**
     * Wortlautanalog zum etablierten Präzedenzfall
     * `network.lapis.cloud.server.rpc.PublicRankingConsentDisclaimer` -- siehe Klassen-KDoc.
     */
    private fun FlowContent.renderTemplateDisclaimer() {
        p(classes = "section-note") {
            +(
                "Diese Seite ist eine Vorlage und stellt keine Rechtsberatung dar. Ein reales Deployment " +
                    "sollte diesen Text durch die eigene, rechtlich geprüfte Fassung ersetzen."
            )
        }
    }
}
