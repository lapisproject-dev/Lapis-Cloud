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
        /** Welle V1.9.11 -- see [PublicChrome.renderChrome] KDoc. No default -- see that KDoc "Breaking Change, bewusst". */
        nav: PublicNavAvailability,
    ): String =
        skeleton(
            baseUrl = baseUrl,
            branding = branding,
            lang = lang,
            currentPath = "/impressum",
            pageTitle = "Impressum",
            nav = nav,
        ) {
            renderImprintBody(legal = legal)
        }

    /**
     * [aiAssistantEnabled] (V1.6.1) / [mcpEnabled] (V1.8.1): each optional-layer paragraph is
     * rendered **only** when that layer is operational on this installation -- otherwise the page
     * would describe a data flow that does not exist here (see the absolute rule in the class
     * KDoc). Both default to `false`.
     *
     * [memberFinderEnabled] (V1.9.147): renders the member-finder list item, its own section and the review notice -- only while the finder is ACTIVE on
     * this installation (switch on AND current notice acknowledged). Unlike the two flags above it changes at runtime; the caller picks the variant.
     */
    fun privacyPage(
        legal: LegalConfig,
        baseUrl: String,
        branding: ResolvedBranding,
        lang: PublicLanguage,
        /** Welle V1.9.11 -- see [PublicChrome.renderChrome] KDoc. No default -- see that KDoc "Breaking Change, bewusst". */
        nav: PublicNavAvailability,
        aiAssistantEnabled: Boolean = false,
        keycloakEnabled: Boolean = false,
        mcpEnabled: Boolean = false,
        memberFinderEnabled: Boolean = false,
    ): String =
        skeleton(
            baseUrl = baseUrl,
            branding = branding,
            lang = lang,
            currentPath = "/datenschutz",
            pageTitle = "Datenschutzerklärung",
            nav = nav,
        ) {
            renderPrivacyBody(
                legal = legal,
                aiAssistantEnabled = aiAssistantEnabled,
                keycloakEnabled = keycloakEnabled,
                mcpEnabled = mcpEnabled,
                memberFinderEnabled = memberFinderEnabled,
            )
        }

    private fun skeleton(
        baseUrl: String,
        branding: ResolvedBranding,
        lang: PublicLanguage,
        currentPath: String,
        pageTitle: String,
        nav: PublicNavAvailability,
        content: FlowContent.() -> Unit,
    ): String {
        val strings = PublicChrome.stringsFor(lang)
        return createHTML(prettyPrint = false).html {
            attributes["lang"] = lang.code
            renderLegalHead(baseUrl = baseUrl, branding = branding, lang = lang, currentPath = currentPath, pageTitle = pageTitle)
            body(classes = "has-chrome") {
                with(PublicChrome) {
                    renderChrome(
                        lang = lang,
                        active = null,
                        baseUrl = baseUrl,
                        branding = branding,
                        currentPath = currentPath,
                        nav = nav,
                    )
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

    private fun FlowContent.renderPrivacyBody(
        legal: LegalConfig,
        aiAssistantEnabled: Boolean,
        keycloakEnabled: Boolean,
        mcpEnabled: Boolean,
        memberFinderEnabled: Boolean,
    ) {
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
                        "Mahnwesen, Familienmitgliedschaften, Ehrungen, digitaler Mitgliedsausweis — Art. 6 " +
                        "Abs. 1 lit. b DSGVO (Vertrag bzw. vorvertragliche Maßnahme, hier: die Mitgliedschaft)."
                )
            }
            li {
                +(
                    "Land und Staatsangehörigkeiten: Das Land der Anschrift und bis zu zehn Staatsangehörigkeiten " +
                        "werden aus einer Liste gewählt und als Länderkürzel gespeichert (für Briefpost, die " +
                        "Vorstands-Karte und die Pflichtangaben nach dem Geldwäschegesetz) — Art. 6 Abs. 1 lit. b " +
                        "DSGVO bzw. Art. 6 Abs. 1 lit. c DSGVO in Verbindung mit § 20 GwG; bei einer Löschung " +
                        "werden sie gelöscht."
                )
            }
            li { +"Freund-/Fördererkonten — Art. 6 Abs. 1 lit. b DSGVO." }
            li {
                +(
                    "Gliederungsverwaltung: Zuordnung zu einem Landesverband sowie die Erteilung " +
                        "und der Entzug von Landesvorstand-Zugriff — Art. 6 Abs. 1 lit. b DSGVO " +
                        "(Organisation der Mitgliedschaft). Ein Landesvorstand sieht dabei Name, " +
                        "E-Mail-Adresse und Beitrittsdatum der aktiven Mitglieder des eigenen " +
                        "Landesverbands."
                )
            }
            li {
                +(
                    "Schutz der Administratorkonten (Vier-Augen-Freigabe): Anträge eines Administrators gegen " +
                        "ein anderes Administratorkonto (temporäres Passwort, Rollenentzug, Sperrung), die " +
                        "Freigabe oder Ablehnung durch einen zweiten Administrator, der Einspruch des betroffenen " +
                        "Kontos sowie die zugehörige Begründung — Art. 6 Abs. 1 lit. f DSGVO (berechtigtes " +
                        "Interesse an der Sicherheit der Verwaltungskonten und der darin verarbeiteten Daten); " +
                        "Anträge, in denen Sie das betroffene Konto sind, werden bei einer Löschung gelöscht."
                )
            }
            li {
                +(
                    "Mitgliederstatistik: Zum Nachweis der Entwicklung der Mitgliederzahlen wird jeder Wechsel " +
                        "des Mitgliedsstatus (neuer Status und Zeitpunkt, ohne Namen oder Anschrift) " +
                        "festgehalten und nur als Zahl je Zeitraum für Vorstand und Administratoren " +
                        "ausgewertet — Art. 6 Abs. 1 lit. f DSGVO (berechtigtes Interesse an einer " +
                        "wahrheitsgemäßen Darstellung der Mitgliederentwicklung); bei einer Löschung bleibt " +
                        "dieser Verlauf anonym erhalten, weil die Mitgliederzeile nur anonymisiert wird."
                )
            }
            li {
                +(
                    "Begegnungsräume (z. B. Gottesdienste): Wer eine Veranstaltung gerade besucht, wird nur " +
                        "für die Dauer der Teilnahme geführt und beim Verlassen oder Ende der Veranstaltung " +
                        "gelöscht; es gibt keine Teilnahmeliste und keine Aufzeichnung der Teilnehmenden. Hat der " +
                        "Betreiber die Aufzeichnung der Kanzel für einen Raum erlaubt, wird ausschließlich die " +
                        "Person mit dem Amt der Kanzel aufgezeichnet; der Raum weist vor dem Eintritt und " +
                        "während der Aufnahme ausdrücklich darauf hin, und die Aufnahme kann jederzeit gelöscht " +
                        "werden. Gäste willigen vorher " +
                        "ausdrücklich ein (Art. 9 Abs. 2 lit. a DSGVO, da die Teilnahme Rückschlüsse auf " +
                        "religiöse Überzeugungen zulassen kann); als Nachweis werden nur Version des Textes, " +
                        "Prüfsumme und Datum gespeichert (Art. 7 Abs. 1 DSGVO). Die Ämter (Kanzel, Ordner) " +
                        "einer Veranstaltung — Art. 6 Abs. 1 lit. b DSGVO."
                )
            }
            li {
                +(
                    "Aufzeichnung in Veranstaltungsräumen (nur wenn der Betreiber sie erlaubt und der Vorstand sie für eine " +
                        "Veranstaltung freigegeben hat): Bild, Ton und Anzeigename aller Personen im Online-Raum — auch von " +
                        "Gästen ohne Konto — können aufgezeichnet und live übertragen werden; der Raum weist während der " +
                        "Aufnahme oder Übertragung ausdrücklich darauf hin. Vor dem Eintritt bestätigt jede Person den " +
                        "Einwilligungstext ausdrücklich (Art. 6 Abs. 1 lit. a DSGVO, bei besonderen Kategorien personenbezogener " +
                        "Daten Art. 9 Abs. 2 lit. a DSGVO); als Nachweis werden nur die Veranstaltung, die Version und die " +
                        "Prüfsumme des Textes und das Datum gespeichert, ohne Namen und ohne Uhrzeit (Art. 7 Abs. 1 DSGVO). " +
                        "Die Aufnahme ist nur für Vorstand und Administratoren sichtbar und kann gelöscht werden."
                )
            }
            li {
                +(
                    "Versand von System-E-Mails (z. B. Passwort zurücksetzen, Bestätigungen, Hinweise): Ist die " +
                        "Zustellung nicht sofort möglich, wird die E-Mail verschlüsselt zwischengespeichert, bis sie " +
                        "zugestellt ist; sicherheitsrelevante E-Mails verfallen nach 30 Minuten ungesendet. Nach " +
                        "Abschluss bleiben nur Zweck, Status und Zeitpunkte ohne Adresse und Inhalt erhalten " +
                        "(7 bzw. 30 Tage) — Art. 6 Abs. 1 lit. b bzw. lit. f DSGVO."
                )
            }
            li {
                +(
                    "Buchhaltung, Beitrags-/Spendenkonto, Prüfpfad, Zahlungsverkehr, interne Verrechnung, " +
                        "Umsatzsteuer-Voranmeldung (Nachweishilfe) und Auktionen — Art. 6 Abs. 1 lit. c DSGVO " +
                        "(handels-/steuerrechtliche Aufbewahrungspflichten, GoBD/HGB/AO; bei politischen " +
                        "Parteien zusätzlich § 25 PartG)."
                )
            }
            li {
                +(
                    "FinTS/HBCI-Live-Kontoabruf für die eigenen Bankkonten der Organisation (sofern aktiv " +
                        "genutzt) — Art. 6 Abs. 1 lit. c DSGVO (dieselbe handels-/steuerrechtliche " +
                        "Aufbewahrungspflicht wie Buchhaltung/Prüfpfad oben); wer die Aktivierung/erneute " +
                        "Anmeldung vorgenommen hat, wird protokolliert (Art. 5 Abs. 2 DSGVO, " +
                        "Rechenschaftspflicht)."
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
                    "Umfragen (nicht bindende Stimmungsbilder) — Art. 6 Abs. 1 lit. f DSGVO. Gespeichert wird, dass " +
                        "ein Mitglied teilgenommen hat, getrennt davon und ohne Bezug zum Mitglied die gegebene " +
                        "Antwort samt dem LTR-Saldo zum Antwortzeitpunkt als Gewicht; Ergebnisse werden nur " +
                        "zusammengefasst und erst ab einer Mindestbeteiligung angezeigt."
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
                    "Speicherung eigener Hintergrundbilder für Videokonferenzen (sofern hochgeladen) — Art. 6 " +
                        "Abs. 1 lit. b DSGVO. Diese Bilder sind ausschließlich für das hochladende Mitglied " +
                        "sichtbar, werden nicht in das Organisations-Backup einbezogen und bei Löschung " +
                        "unmittelbar entfernt."
                )
            }
            li {
                +(
                    "Mitgliedsfoto (freiwillig, sofern hochgeladen) — Art. 6 Abs. 1 lit. a DSGVO. Das Foto ist nach dem " +
                        "Hochladen privat und nur für das Mitglied selbst sichtbar. Nur wenn das Mitglied ausdrücklich " +
                        "einwilligt, wird es auf öffentlichen Webseiten dieser Organisation angezeigt; die Einwilligung ist " +
                        "jederzeit mit Wirkung für die Zukunft widerrufbar, das Foto ist dann sofort nicht mehr abrufbar. " +
                        "Das Foto wird nicht in das Organisations-Backup einbezogen und bei Löschung unmittelbar entfernt."
                )
            }
            li {
                +(
                    "Öffentliche Kurzvorstellung (freiwillig, nur für Vorstandsmitglieder und ernannte Politiker) — " +
                        "Art. 6 Abs. 1 lit. a DSGVO. Der selbst verfasste Text ist nach dem Speichern privat. Nur wenn das " +
                        "Mitglied ausdrücklich einwilligt, wird er auf den öffentlichen Seiten Vorstand und Politiker sowie " +
                        "im Einbettungs-Feed der Website angezeigt; die Einwilligung ist jederzeit mit Wirkung für die " +
                        "Zukunft widerrufbar, der Text ist dann mit der nächsten Abfrage nicht mehr abrufbar. Der Text " +
                        "wird nicht in das Organisations-Backup einbezogen und bei Löschung unmittelbar entfernt."
                )
            }
            li {
                +(
                    "Veröffentlichung als Politiker (Name und Amt, auf Wunsch mit Foto und Kurzvorstellung) auf der " +
                        "öffentlichen Seite Politiker und im Einbettungs-Feed — Art. 6 Abs. 1 lit. a DSGVO. Die " +
                        "Einwilligung ist jederzeit mit Wirkung für die Zukunft widerrufbar und endet mit dem Politiker-Status."
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
            if (keycloakEnabled) {
                li {
                    +(
                        "Anmeldung über Keycloak (externe Benutzerverwaltung), sofern vom Betreiber aktiv " +
                            "genutzt: die Kontenzuordnung (Verknüpfung zwischen Ihrer Keycloak-Kennung und " +
                            "Ihrem Mitgliedskonto) wird gespeichert, das eigentliche Passwort verwaltet " +
                            "ausschließlich das externe Keycloak — Art. 6 Abs. 1 lit. b DSGVO."
                    )
                }
            }
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
            if (aiAssistantEnabled) {
                li {
                    +(
                        "KI-gestützte Satzungsauskunft (nur nach Ihrer ausdrücklichen Zustimmung im Bereich " +
                            "\u201EFragen zur Satzung\u201C): Ihre Frage wird zunächst von erkennbaren " +
                            "Kennungen (E-Mail-Adressen, IBAN, Telefonnummern, lange Ziffernfolgen) bereinigt und " +
                            "zusammen mit passenden Auszügen aus den für alle Mitglieder freigegebenen Dokumenten " +
                            "an den vom Betreiber konfigurierten externen KI-Anbieter übermittelt " +
                            "(Auftragsverarbeitung nach Art. 28 DSGVO; Name und Sitz des Anbieters erfahren Sie " +
                            "vom Betreiber). Namen und andere Freitext-Angaben werden nicht erkannt \u2014 bitte " +
                            "schreiben Sie keine personenbezogenen Daten in Ihre Frage. Protokolliert werden nur " +
                            "Zeitpunkt, Prüfsummen (Hashes) und Zähler, niemals der Klartext von Frage oder " +
                            "Antwort \u2014 Art. 6 Abs. 1 lit. a DSGVO (Einwilligung, jederzeit im selben Bereich " +
                            "widerrufbar)."
                    )
                }
            }
            if (mcpEnabled) {
                li {
                    +(
                        "MCP-Zugang für KI-Agenten (nur nach Ihrer ausdrücklichen Zustimmung im Bereich " +
                            "„KI-Agenten Zugriff“): ein von Ihnen selbst autorisierter KI-Agent kann " +
                            "lesend auf Ihren eigenen Beitragsstand, Ihren eigenen LTR-Kontostand, Satzungstexte, " +
                            "bevorstehende Veranstaltungen sowie Ihre eigenen abgegebenen Stimmzettel zugreifen — " +
                            "niemals auf Daten anderer Mitglieder. Protokolliert werden nur Zeitpunkt, " +
                            "aufgerufenes Werkzeug und Ergebnis-Status, niemals Frage- oder Antwortinhalte — " +
                            "Art. 6 Abs. 1 lit. a DSGVO (Einwilligung, jederzeit im selben Bereich widerrufbar; " +
                            "ein Widerruf beendet zugleich jede bestehende Verbindung)."
                    )
                }
            }
            if (memberFinderEnabled) {
                li {
                    +(
                        "Mitglieder-Finder (freiwillig, nur nach Ihrer ausdrücklichen Einwilligung): Vermittlung des " +
                            "Kontakts zwischen Mitgliedern derselben Region, um eine Ortsgruppe zu gründen und/oder " +
                            "Stammtische zu organisieren — Art. 6 Abs. 1 lit. a DSGVO i. V. m. Art. 9 Abs. 2 lit. a " +
                            "DSGVO (die Teilnahme lässt Rückschlüsse auf die Mitgliedschaft und gegebenenfalls auf " +
                            "die politische Überzeugung zu). Einzelheiten im Abschnitt \u201EMitglieder-Finder " +
                            "(optional)\u201C weiter unten."
                    )
                }
            }
            li {
                +(
                    "Dokumentenablage, interne Kommunikation, Mitfahrerzentrale, Crowdfunding, API-Schlüssel und " +
                        "Webhooks, jeweils nur soweit vom Betreiber genutzt — Art. 6 Abs. 1 lit. b bzw. lit. f DSGVO."
                )
            }
            li {
                +(
                    "Von Mitgliedern verfasste und vom Vorstand freigegebene Artikel (redaktioneller " +
                        "Nachrichten-Workflow): Titel, Auszug und Inhalt Ihres Entwurfs sind bis zur " +
                        "Freigabe nur Ihnen selbst sowie dem Vorstand sichtbar; nach der Veröffentlichung " +
                        "wird der Artikelinhalt öffentlich — Art. 6 Abs. 1 lit. b DSGVO. Ihr Name wird als " +
                        "Autor nur angezeigt, wenn Sie dem beim Verfassen des Artikels ausdrücklich zustimmen " +
                        "(Art. 6 Abs. 1 lit. a DSGVO); ohne Ihre Einwilligung wird kein Autorenname angezeigt. " +
                        "Sie können die Einwilligung jederzeit mit Wirkung für die Zukunft widerrufen " +
                        "(Art. 7 Abs. 3 DSGVO); danach entfernen wir die Namensnennung sofort aus Lapis. " +
                        "Wird der Artikel zusätzlich auf einer statischen Webseite veröffentlicht, zeigt diese " +
                        "die Änderung erst nach ihrer nächsten Aktualisierung. Eine Organisationsangabe " +
                        "(z. B. der Name der Partei) als Autorenzeile setzt der Vorstand; sie ist kein " +
                        "Personenname."
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
            li {
                +(
                    "Veröffentlichung der Registrierungstexte (Beitrittsvertrag, Nutzungsbedingungen für Freund-Konten), " +
                        "einschließlich wer eine Fassung veröffentlicht hat — Art. 6 Abs. 1 lit. c DSGVO i. V. m. " +
                        "Art. 5 Abs. 2 DSGVO (Rechenschaftspflicht); der Text selbst enthält keine personenbezogenen Daten."
                )
            }
            li {
                +(
                    "Beitragsvergünstigungen (Stundung, Befreiung, Sozialermäßigung), einschließlich des vom " +
                        "Mitglied freiwillig angegebenen Antragsgrundes, der auch Angaben zur Gesundheit " +
                        "enthalten kann — Art. 6 Abs. 1 lit. b DSGVO (Mitgliedschaftsvertrag) i. V. m. " +
                        "Art. 9 Abs. 2 lit. a DSGVO (ausdrückliche Einwilligung durch die freiwillige Angabe), " +
                        "soweit besondere Kategorien personenbezogener Daten betroffen sind."
                )
            }
            li {
                +(
                    "Reisekostenabrechnungen für Vorstand und Funktionsträger (Zweck, Reisezeitraum, " +
                        "Fahrt-/Tagespauschale-/Belegkosten-Zeilen, Entscheidung und ggf. daraus resultierende " +
                        "Buchung), einschließlich der hochgeladenen Belege — Art. 6 Abs. 1 lit. b DSGVO " +
                        "(Mitgliedschaftsvertrag/Vereinsorganisation) i. V. m. § 147 AO (Aufbewahrungspflicht " +
                        "für Buchungsbelege)."
                )
            }
            li {
                +(
                    "Ehrenamts- und Übungsleiterpauschalen (§ 3 Nr. 26 / 26a EStG): Kategorie, Betrag, " +
                        "Tätigkeitsbeschreibung, Zahlungsdatum, Entscheidung und ggf. daraus resultierende " +
                        "Buchung, sowie die Selbstauskunft der empfangenden Person zum jährlichen " +
                        "Freibetragsverbrauch — Art. 6 Abs. 1 lit. b DSGVO (Mitgliedschaftsvertrag/" +
                        "Vereinsorganisation) i. V. m. § 147 AO (Aufbewahrungspflicht für Buchungsbelege)."
                )
            }
            li {
                +(
                    "Kreditoren-/Debitorenbuchhaltung (offene Posten): Gegenparteiname, Referenz, Betrag, " +
                        "Fälligkeit, Zahlungs-/Verrechnungshistorie und ggf. Mahnstufen-Eskalation — Art. 6 " +
                        "Abs. 1 lit. b DSGVO (Vereinsorganisation) i. V. m. § 147 AO (Aufbewahrungspflicht " +
                        "für Buchungsbelege)."
                )
            }
        }

        if (memberFinderEnabled) renderMemberFinderSection()

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
     * V1.9.147 -- the member-finder section. Every sentence is checked against the V1.9.144 code and `docs/architecture/member-finder.adoc`; nothing is
     * rounded up (e.g. an exit does NOT delete everything: received, unanswered requests are detached and removed later).
     */
    private fun FlowContent.renderMemberFinderSection() {
        h2 { +"Mitglieder-Finder (optional)" }
        p {
            +(
                "Der Mitglieder-Finder ist ein freiwilliges Angebot dieser Installation. Er vermittelt den Kontakt " +
                    "zwischen Mitgliedern derselben Region, um eine Ortsgruppe zu gründen und/oder Stammtische zu " +
                    "organisieren (Interesse: Gruppengründung, Stammtisch oder beides). Weitere Zwecke gibt es nicht."
            )
        }
        ul {
            li {
                +(
                    "Rechtsgrundlage ist Ihre ausdrückliche Einwilligung (Art. 6 Abs. 1 lit. a, Art. 9 Abs. 2 lit. a " +
                        "DSGVO), weil Ihre Teilnahme Rückschlüsse auf Ihre Mitgliedschaft und gegebenenfalls auf Ihre " +
                        "politische Überzeugung zulässt. Teilnehmen können Sie nur auf eigenen Wunsch und nur, wenn " +
                        "Sie bestätigen, volljährig zu sein; ist ein Geburtsdatum bekannt und liegt es unter 18 Jahren " +
                        "zurück, ist die Teilnahme nicht möglich."
                )
            }
            li {
                +(
                    "Die Region wird nur grob aus den ersten zwei Ziffern der Postleitzahl Ihrer hinterlegten " +
                        "(deutschen) Anschrift abgeleitet. Sie wird nicht bei Ihnen gespeichert, sondern bei jeder " +
                        "Abfrage neu berechnet."
                )
            }
            li {
                +(
                    "Eine Region wird erst ab fünf Teilnehmenden angezeigt, und es werden nie genaue Zahlen genannt, " +
                        "sondern nur Größenbereiche (5–9, 10–19, 20 und mehr). Diese Bereiche werden höchstens einmal " +
                        "je Organisationstag neu berechnet."
                )
            }
            li {
                +(
                    "Es wird nur vermittelt: Pro Anfrage wird genau eine zufällig gewählte Person derselben Region " +
                        "angesprochen. Ihr Name wird erst nach beidseitiger Zustimmung sichtbar. Die angefragte Person " +
                        "erfährt nie, wer die Anfrage gesendet hat, und die Rückmeldung an den Absender ist für jede " +
                        "Anfrage gleich (zunächst immer „ausstehend“). Der weitere Kontakt läuft über die " +
                        "vorhandenen Direktnachrichten."
                )
            }
            li {
                +(
                    "Widerruf: Sie können Ihre Einwilligung jederzeit mit Wirkung für die Zukunft widerrufen, auch " +
                        "wenn der Finder inzwischen abgeschaltet wurde. Der Widerruf löscht Ihre eigenen Anfragen, " +
                        "Verbindungen und Sperren; eine erneute Teilnahme ist frühestens nach 24 Stunden möglich."
                )
            }
            li {
                +(
                    "Wird der Finder von dieser Installation abgeschaltet, werden alle Anfragen, Verbindungen, " +
                        "Sperren und Größenbereiche gelöscht, und alle Einwilligungen werden beendet."
                )
            }
            li {
                +(
                    "Verlassen Sie die Organisation oder verlieren Sie den Mitgliedsstatus, endet Ihre Einwilligung. " +
                        "Ihre eigenen gesendeten Anfragen, Verbindungen und Sperren werden gelöscht; bei Anfragen, die " +
                        "an Sie gerichtet waren und nicht angenommen wurden, wird der Bezug zu Ihrer Person gelöst, und " +
                        "sie werden spätestens 7 Tage nach ihrem Ablauf bzw. Abschluss entfernt."
                )
            }
            li {
                +(
                    "Der Nachweis Ihrer Einwilligung und eines Widerrufs (Zeitpunkt, Textfassung, Prüfsumme) bleibt " +
                        "bis zur Löschung Ihres Kontos gespeichert."
                )
            }
            li {
                +(
                    "Weder Protokolle noch die Prüfspur enthalten Namen, Regionen, Notizen oder Zahlen. Die Prüfspur " +
                        "hält nur fest, dass der Betreiber den Schalter geändert hat."
                )
            }
        }
        renderMemberFinderReviewNotice()
    }

    private fun FlowContent.renderMemberFinderReviewNotice() {
        p(classes = "section-note") {
            +(
                "Dieser Abschnitt ist eine Vorlage und juristisch noch nicht geprüft. Ob der Mitglieder-Finder in " +
                    "Ihrem Land zulässig ist und welche Informationspflichten gelten, muss der Betreiber für sein " +
                    "Land selbst prüfen."
            )
        }
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
