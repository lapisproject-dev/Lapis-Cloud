package network.lapis.cloud.client

import io.kvision.i18n.tr

/**
 * V1.9.187 -- the registry of the page intros: route -> [PageIntroDef].
 *
 * Rules for every text (a reviewer checks each against the screen's code):
 *  - German source text, at most two sentences and about 220 characters (a source scan pins the length);
 *  - it says only what the view verifiably does, who may do what and what the person can expect -- no legal and no financial promise;
 *  - a role split is named only when the screen really shows it.
 *
 * This is a function, not a top-level `val`: the `tr(...)` markers must be created at call time (a language switch re-translates the
 * text), and the literal inside `tr(` is what the catalog scan sees. Never pass a variable to `tr()` here.
 *
 * A view that is not in the registry has no intro. Part 1 (V1.9.187) covered the personal and self-governance views; Part 2 (V1.9.188)
 * adds the economy, finance, administration and system views, so every sidebar destination has one (`SidebarIntroCoverageDomTest` keeps
 * it that way). Only views named in the source scan's `MORE_ALLOWED` may pass a [PageIntroDef.more] part (today: the audit log).
 * Rules added in Part 2: a term gets its one-half-sentence explanation where it first appears in the text; the words "rechtssicher",
 * "gesetzeskonform", "garantiert", "automatisch erfüllt" and "steuerfrei" never appear in an intro, and `text` never names "GoBD",
 * "revisionssicher" or a paragraph sign (that belongs in `more`, if anywhere). Descriptive sentences belong in the intro; a notice that
 * restricts what the result may be used for stays in the screen body, where it cannot be hidden.
 */
internal object PageIntros {
    fun forRoute(route: String): PageIntroDef? =
        when (route) {
            Routes.DASHBOARD ->
                PageIntroDef(
                    "dashboard",
                    tr(
                        "Das Dashboard zeigt Ihren Status und Ihre Rolle und führt über Kacheln in die Bereiche, " +
                            "die für Sie freigeschaltet sind. " +
                            "Hier ändern Sie auch Ihr Passwort.",
                    ),
                )
            Routes.CONFERENCE ->
                PageIntroDef(
                    "conference",
                    tr(
                        "Hier treten Sie laufenden Videobesprechungen bei oder starten eine neue. " +
                            "Welche Funktionen Ihnen offenstehen, etwa Aufzeichnung oder Breakout-Räume, hängt von Ihrer Rolle ab.",
                    ),
                )
            Routes.ENCOUNTER ->
                PageIntroDef(
                    "encounter",
                    tr(
                        "Begegnungsräume sind dauerhafte Räume, in denen sich Personen zum Gespräch oder zu einer Veranstaltung treffen. " +
                            "Die Liste zeigt, ob ein Raum geöffnet ist und wie viele Personen anwesend sind.",
                    ),
                )
            Routes.CARPOOL ->
                PageIntroDef(
                    "carpool",
                    tr(
                        "Hier finden Sie angebotene Fahrten und können eine Mitfahrt anfragen. " +
                            "Mitglieder können außerdem selbst Fahrten anbieten oder eine Fahrt suchen.",
                    ),
                )
            Routes.CONTRIBUTIONS ->
                PageIntroDef(
                    "contributions",
                    tr(
                        "Hier sehen Sie Ihre Beiträge mit Status und können eine Beitragsvergünstigung oder Stundung beantragen. " +
                            "Lastschrift und Online-Zahlung erscheinen nur, wenn Ihre Organisation sie eingeschaltet hat.",
                    ),
                )
            Routes.DOCUMENTS ->
                PageIntroDef(
                    "documents",
                    tr(
                        "Hier finden Sie die Dokumente Ihrer Organisation in Ordnern. " +
                            "Welche Ordner und Dokumente Sie sehen, richtet sich nach der Sichtbarkeit, die dafür festgelegt ist.",
                    ),
                )
            Routes.STATUTE_QA ->
                PageIntroDef(
                    "statute-qa",
                    tr(
                        "Sie stellen Fragen zur Satzung; die Antwort entsteht per KI aus den dafür freigegebenen Dokumenten. " +
                            "Dazu stimmen Sie einmal zu, die Zustimmung können Sie widerrufen.",
                    ),
                )
            Routes.COMMUNICATION ->
                PageIntroDef(
                    "communication",
                    tr(
                        "Hier verwalten Sie Ihre Mailinglisten, schreiben Direktnachrichten und lesen Ihr Postfach. " +
                            "Vorstand und Administration legen zusätzlich Mailinglisten an und verschicken Nachrichten an sie.",
                    ),
                )
            Routes.MY_CHAPTER ->
                PageIntroDef(
                    "my-chapter",
                    tr(
                        "Hier sehen Sie die aktiven Mitglieder Ihres Landesverbands und können nach Name oder E-Mail suchen. " +
                            "Der Zugang gilt nur, solange Ihr Amt im Landesverband aktiv ist.",
                    ),
                )
            Routes.MEMBER_FINDER ->
                PageIntroDef(
                    "member-finder",
                    tr(
                        "Der Mitglieder-Finder ist freiwillig und erst nach Ihrer Einwilligung nutzbar. " +
                            "Eine Verbindung zu einem anderen Mitglied entsteht nur, wenn dieses der Anfrage zustimmt.",
                    ),
                )
            Routes.ARTICLES ->
                PageIntroDef(
                    "articles",
                    tr(
                        "Hier schreiben Sie Artikel und reichen sie zur Freigabe ein. " +
                            "Vorstand und Administration geben Artikel frei, lehnen sie ab oder nehmen veröffentlichte wieder von der Webseite.",
                    ),
                )
            Routes.DONATE ->
                PageIntroDef(
                    "donate",
                    tr(
                        "Hier starten Sie eine Online-Spende mit Betrag und Verwendungszweck; " +
                            "bezahlt wird anschließend beim Zahlungsdienstleister. " +
                            "Ob Online-Spenden möglich sind, legt Ihre Organisation fest.",
                    ),
                )
            Routes.DSGVO_RIGHTS ->
                PageIntroDef(
                    "dsgvo-rights",
                    tr(
                        "Hier können Sie Ihre gespeicherten Daten einsehen und exportieren sowie die Löschung beantragen. " +
                            "Eine Löschung wird nur beantragt; über den Antrag entscheidet die Administration.",
                    ),
                )
            Routes.TRAVEL_EXPENSES ->
                PageIntroDef(
                    "travel-expenses",
                    tr(
                        "Hier erfassen Sie Reisekostenanträge als Entwurf, laden Belege hoch und reichen den Antrag zur Freigabe ein. " +
                            "Ihre Anträge sehen Sie unter Meine Anträge.",
                    ),
                    helpTopic = "travel-expenses",
                )
            Routes.VOLUNTEER_ALLOWANCES ->
                PageIntroDef(
                    "volunteer-allowances",
                    tr(
                        "Hier beantragen Sie Zahlungen für Ehrenamts- und Übungsleiterpauschalen und reichen sie ein. " +
                            "Ihre bisherigen Zahlungen sehen Sie unter Meine Zahlungen.",
                    ),
                )
            Routes.MY_VOLUNTEER_SHIFTS ->
                PageIntroDef(
                    "my-volunteer-shifts",
                    tr(
                        "Hier sehen Sie die offenen Helferschichten geplanter Veranstaltungen und melden sich an. " +
                            "Eine Zusage können Sie wieder zurückziehen.",
                    ),
                )
            Routes.MY_EVENTS ->
                PageIntroDef(
                    "my-events",
                    tr(
                        "Hier sehen Sie die geplanten Veranstaltungen Ihrer Organisation und können sich anmelden oder wieder abmelden. " +
                            "Bei Online-Veranstaltungen ist die Teilnahme ebenfalls hier möglich.",
                    ),
                )
            Routes.COMMITTEES ->
                PageIntroDef(
                    "committees",
                    tr(
                        "Hier sehen Sie die Gremien mit ihren Mitgliedern und Ämtern. " +
                            "Gremien anlegen und Mitglieder hinzufügen oder entfernen können Vorstand und Administration.",
                    ),
                )
            Routes.MEETINGS ->
                PageIntroDef(
                    "meetings",
                    tr(
                        "Hier sehen Sie die Sitzungen der Gremien mit Tagesordnung, Anwesenheit und Beschlüssen. " +
                            "Erfassen können das Vorstand, Administration und die Leitung des jeweiligen Gremiums.",
                    ),
                )
            Routes.TENDERS ->
                PageIntroDef(
                    "tenders",
                    tr(
                        "Hier schreibt der Vorstand Aufträge aus, die ein Beschluss vorsieht. " +
                            "Mitglieder und Freunde geben bis zur Frist ein Angebot ab.",
                    ),
                )
            Routes.MOTIONS ->
                PageIntroDef(
                    "motions",
                    tr(
                        "Hier reichen Sie Anträge an Gremien ein und verfolgen ihren Stand bis zum Beschluss. " +
                            "Prüfen, terminieren und entscheiden können Vorstand, Administration und die Leitung des Gremiums.",
                    ),
                )
            Routes.ELECTIONS ->
                PageIntroDef(
                    "elections",
                    tr(
                        "Hier sehen Sie die Wahlen Ihrer Organisation und geben Ihre Stimme ab, wenn Sie wahlberechtigt sind. " +
                            "Eröffnet wird eine Wahl nicht hier, sondern aus einem terminierten Antrag.",
                    ),
                )
            Routes.CONSENSUS ->
                PageIntroDef(
                    "consensus",
                    tr(
                        "Beim Konsensieren bewerten Sie jede Option mit einem Widerstand von 0 bis 10; " +
                            "die Option mit dem geringsten Widerstand gewinnt. " +
                            "Eröffnet wird es aus einem terminierten Antrag.",
                    ),
                )
            Routes.POLLS ->
                PageIntroDef(
                    "polls",
                    tr(
                        "Umfragen sind unverbindliche Stimmungsbilder unter den Mitgliedern. " +
                            "Ihre Antwort ist anonym, das Ergebnis sehen Sie erst nach dem Ende der Umfrage.",
                    ),
                )
            Routes.LTR_LEDGER ->
                PageIntroDef(
                    "ltr-ledger",
                    tr(
                        "Hier sehen Sie Ihren LTR-Kontostand und Ihre Buchungen und können LTR an andere Mitglieder senden. " +
                            "Senden kann nur, wer aktives Mitglied ist.",
                    ),
                )
            Routes.CROWDFUNDING ->
                PageIntroDef(
                    "crowdfunding",
                    tr(
                        "Hier reichen Sie Projekte mit einem LTR-Einsatz ein und bewerten freigegebene Projekte mit Like oder" +
                            " Dislike. Der Einsatz wird nicht zurückgebucht.",
                    ),
                )
            Routes.AUCTION ->
                PageIntroDef(
                    "auction",
                    tr(
                        "Hier bieten Sie auf Angebote anderer Mitglieder oder stellen selbst eines ein; geboten wird in LTR. " +
                            "Ob die Auktion eingeschaltet ist, legt die Administration fest.",
                    ),
                )
            Routes.POLITICIANS ->
                PageIntroDef(
                    "politicians",
                    tr(
                        "Hier sehen Sie die Politiker-Profile als Rangliste und bewerten sie mit Like oder Dislike. Ob diese " +
                            "Funktion eingeschaltet ist, legt die Administration fest.",
                    ),
                )
            Routes.SOCIAL_NETWORK ->
                PageIntroDef(
                    "social-network",
                    tr(
                        "Die Timeline zeigt die Beiträge der Mitglieder. Sie können eigene Beiträge veröffentlichen und auf " +
                            "andere antworten.",
                    ),
                )
            Routes.AI_DRAFTS ->
                PageIntroDef(
                    "ai-drafts",
                    tr(
                        "Hier prüfen Sie Beitrags-Entwürfe, die ein verbundener KI-Agent für Sie abgelegt hat. Sie " +
                            "bearbeiten, veröffentlichen oder verwerfen sie; Verworfenes lässt sich zeitweise wiederherstellen.",
                    ),
                )
            Routes.LEDGER ->
                PageIntroDef(
                    "ledger",
                    tr(
                        "Hier richten Sie die Finanzen ein, sehen den Kontenplan und das Journal mit allen Buchungen. Einrichten " +
                            "und buchen können Schatzmeisterei und Administration; die Zuordnung der Konten ändert nur die Administration.",
                    ),
                )
            Routes.OPEN_ITEMS ->
                PageIntroDef(
                    "open-items",
                    tr(
                        "Hier sehen Sie offene Posten, also noch nicht bezahlte Forderungen, getrennt nach Kreditoren und " +
                            "Debitoren. Erfassen können sie Schatzmeisterei und Administration.",
                    ),
                )
            Routes.FINANCIAL_REPORTS ->
                PageIntroDef(
                    "financial-reports",
                    tr(
                        "Hier sehen Sie GuV, Bilanz und Jahresabschluss und exportieren Daten für DATEV oder andere " +
                            "Buchhaltungsprogramme.",
                    ),
                )
            Routes.COMPLIANCE_REPORTS ->
                PageIntroDef(
                    "compliance-reports",
                    tr(
                        "Hier sehen Sie die Vier-Sphären-Rechnung, die Mittelverwendung und die USt-Voranmeldung als " +
                            "Vorschau.",
                    ),
                )
            Routes.COST_CENTERS ->
                PageIntroDef(
                    "cost-centers",
                    tr(
                        "Hier verwalten Sie Kostenstellen, also Bereiche, denen Ausgaben zugeordnet werden. Anlegen können " +
                            "sie Schatzmeisterei und Administration.",
                    ),
                )
            Routes.DONORS ->
                PageIntroDef(
                    "donors",
                    tr(
                        "Hier verwalten Sie Spender, die keine Mitglieder sind. Anlegen können sie Schatzmeisterei und " +
                            "Administration.",
                    ),
                )
            Routes.AUDIT_LOG ->
                PageIntroDef(
                    "audit-log",
                    tr(
                        "Hier sehen Sie das Protokoll aller Buchungen, Beschlüsse, Vorstandsmitgliedschaften und " +
                            "Spendenprüfungen. Einträge lassen sich hier nur lesen, nicht ändern oder löschen.",
                    ),
                    more =
                        tr(
                            "GoBD-revisionssicheres Protokoll aller Buchungen, Beschlüsse, Vorstandsmitgliedschaften und " +
                                "Spendenprüfungen -- unveränderlich und ausschließlich lesbar. Es gibt auf dieser Seite keine " +
                                "Funktion zum Bearbeiten oder Löschen von Einträgen.",
                        ),
                )
            Routes.POSTAL_MAIL ->
                PageIntroDef(
                    "postal-mail",
                    tr(
                        "Hier sehen Sie, welche Briefe bereits verschickt wurden. Neue Versendungen starten Sie bei " +
                            "Beitragsrechnung, Spendenbescheinigung oder Sitzungseinladung.",
                    ),
                )
            Routes.PRICE_ORACLE ->
                PageIntroDef(
                    "price-oracle",
                    tr(
                        "Hier sehen Sie das Price-Oracle, also die Quelle für Umrechnungskurse. Einstellungen ändern kann nur" +
                            " die Administration.",
                    ),
                )
            Routes.SEPA_MANDATES ->
                PageIntroDef(
                    "sepa-mandates",
                    tr(
                        "Hier verwalten Sie SEPA-Mandate, also die Erlaubnis, Beiträge vom Konto einzuziehen. Im Auftrag " +
                            "eines Mitglieds erfassen sie Schatzmeisterei und Administration.",
                    ),
                )
            Routes.SEPA_BATCHES ->
                PageIntroDef(
                    "sepa-batches",
                    tr(
                        "Hier bereiten Sie SEPA-Lastschriften vor, also den Einzug vom Bankkonto, erzeugen die Datei für die " +
                            "Bank und erfassen Rückläufer.",
                    ),
                )
            Routes.DUNNING_CASES ->
                PageIntroDef(
                    "dunning-cases",
                    tr(
                        "Hier sehen Sie die Mahnvorgänge zu offenen Beiträgen. Mahnungen ausstellen und Vorgänge zurücksetzen" +
                            " können Schatzmeisterei und Administration.",
                    ),
                )
            Routes.PAYMENT_TRANSACTIONS ->
                PageIntroDef(
                    "payment-transactions",
                    tr(
                        "Hier sehen Sie eingegangene Zahlungen und ob sie schon gebucht sind.",
                    ),
                )
            Routes.BANK_ACCOUNTS ->
                PageIntroDef(
                    "bank-accounts",
                    tr(
                        "Hier verwalten Sie die Bankkonten der Organisation.",
                    ),
                )
            Routes.BANK_IMPORT ->
                PageIntroDef(
                    "bank-import",
                    tr(
                        "Hier laden Sie Kontoauszüge hoch und ordnen deren Zeilen zu. Hochladen und zuordnen können " +
                            "Schatzmeisterei und Administration.",
                    ),
                )
            Routes.MEMBERSHIP_TIERS ->
                PageIntroDef(
                    "membership-tiers",
                    tr(
                        "Hier legen Sie Mitgliedschaftsstufen mit Beitrag, Intervall und Zahlungsziel fest. Eine Stufe wird " +
                            "nicht gelöscht, sondern geschlossen.",
                    ),
                )
            Routes.CONTRIBUTION_RELIEF ->
                PageIntroDef(
                    "contribution-relief",
                    tr(
                        "Hier entscheiden Sie über Anträge auf Beitragsvergünstigung oder Stundung. Den eigenen Antrag " +
                            "entscheiden Sie nicht selbst.",
                    ),
                )
            Routes.TRAVEL_EXPENSE_APPROVALS ->
                PageIntroDef(
                    "travel-expense-approvals",
                    tr(
                        "Hier entscheiden Sie über eingereichte Reisekostenanträge und erzeugen eine SEPA-Überweisungsdatei " +
                            "für gebuchte Anträge. Die Sätze pflegt die Administration.",
                    ),
                )
            Routes.VOLUNTEER_ALLOWANCE_APPROVALS ->
                PageIntroDef(
                    "volunteer-allowance-approvals",
                    tr(
                        "Hier geben Sie Anträge auf Ehrenamts- und Übungsleiterpauschalen frei oder lehnen sie ab.",
                    ),
                )
            Routes.MEMBERS ->
                PageIntroDef(
                    "members",
                    tr(
                        "Hier verwalten Sie die Mitglieder mit Status, Rolle und Stammdaten. Neue Mitglieder legen Vorstand " +
                            "und Administration an.",
                    ),
                )
            Routes.REGIONAL_CHAPTERS ->
                PageIntroDef(
                    "regional-chapters",
                    tr(
                        "Hier verwalten Sie die Landesverbände. Der Vorstand pflegt Wappen und öffentliche Beschreibung, " +
                            "alles Übrige die Administration.",
                    ),
                )
            Routes.DSGVO_COMPLIANCE ->
                PageIntroDef(
                    "dsgvo-compliance",
                    tr(
                        "Hier führen Sie die Datenschutz-Dokumentation Ihrer Organisation und erfassen Datenpannen.",
                    ),
                )
            Routes.BOARD_MEMBERSHIP ->
                PageIntroDef(
                    "board-membership",
                    tr(
                        "Hier sehen Sie, wer im Vorstand ist. Wechsel erzeugen eine Erinnerung für das Transparenzregister, " +
                            "das amtliche Register der wirtschaftlich Berechtigten.",
                    ),
                )
            Routes.SOCIAL_MODERATION ->
                PageIntroDef(
                    "social-moderation",
                    tr(
                        "Hier bearbeiten Sie Meldungen zu Beiträgen. Löschanträge entscheidet die Administration.",
                    ),
                )
            Routes.API_KEYS ->
                PageIntroDef(
                    "api-keys",
                    tr(
                        "Hier verwalten Sie API-Schlüssel, also Zugang für andere Programme zur lesenden Schnittstelle. Pro " +
                            "Schlüssel kann ein Webhook eingerichtet werden.",
                    ),
                )
            Routes.CRM ->
                PageIntroDef(
                    "crm",
                    tr(
                        "Hier verwalten Sie Kontakte und Interessenten, getrennt von Mitgliedern und Spendern.",
                    ),
                )
            Routes.EVENT_MANAGEMENT ->
                PageIntroDef(
                    "event-management",
                    tr(
                        "Hier planen Sie Veranstaltungen und verwalten Anmeldungen.",
                    ),
                )
            Routes.EVENT_CHECKIN ->
                PageIntroDef(
                    "event-checkin",
                    tr(
                        "Hier wählen Sie eine Veranstaltung, um Tickets am Einlass zu prüfen.",
                    ),
                )
            Routes.EVENT_ROOMS ->
                PageIntroDef(
                    "event-rooms",
                    tr(
                        "Hier verwalten Sie Räume für Veranstaltungen.",
                    ),
                )
            Routes.CATERING ->
                PageIntroDef(
                    "catering",
                    tr(
                        "Hier verwalten Sie Catering-Bestellungen je Veranstaltung.",
                    ),
                )
            Routes.EVENT_VOLUNTEERS ->
                PageIntroDef(
                    "event-volunteers",
                    tr(
                        "Hier planen Sie Helferschichten je Veranstaltung.",
                    ),
                )
            Routes.MEMBER_ANNIVERSARIES ->
                PageIntroDef(
                    "member-anniversaries",
                    tr(
                        "Bevorstehende Geburtstage und Mitgliedschafts-/Förderer-Jubiläen -- eine gemeinsame, chronologische " +
                            "Liste für den Vorstand.",
                    ),
                )
            Routes.MEMBER_HONORS ->
                PageIntroDef(
                    "member-honors",
                    tr(
                        "Ehrenmitgliedschaften, Verdienst- und Treueauszeichnungen -- eine Übersicht für den Vorstand.",
                    ),
                )
            Routes.MEMBER_FAMILIES ->
                PageIntroDef(
                    "member-families",
                    tr(
                        "Haushalts-/Familienverbünde für die Beitragsabrechnung -- ein Zahler, beliebig viele beitragsfreie " +
                            "Angehörige.",
                    ),
                )
            Routes.MEMBER_MAP ->
                PageIntroDef(
                    "member-map",
                    tr(
                        "Hier sehen Sie, wo Mitglieder wohnen, nach Postleitzahl.",
                    ),
                )
            Routes.MEMBER_STATISTICS ->
                PageIntroDef(
                    "member-statistics",
                    tr(
                        "Hier sehen Sie die Entwicklung der Mitgliederzahl und exportieren sie als CSV.",
                    ),
                )
            Routes.BACKUP ->
                PageIntroDef(
                    "backup",
                    tr(
                        "Hier exportieren Sie alle Organisationsdaten und stellen sie wieder her. Das ist der Administration " +
                            "vorbehalten.",
                    ),
                )
            Routes.AGREEMENT_TEXTS ->
                PageIntroDef(
                    "agreement-texts",
                    tr(
                        "Hier pflegen Sie die Texte, die bei der Registrierung angezeigt und bestätigt werden. Eine " +
                            "veröffentlichte Fassung kann nicht mehr geändert werden; jede Änderung ist eine neue Fassung.",
                    ),
                )
            Routes.CONFERENCE_STREAM_DESTINATIONS ->
                PageIntroDef(
                    "stream-destinations",
                    tr(
                        "Hier verwalten Sie die externen Stream-Ziele (RTMP), zu denen Moderierende eine Besprechung live " +
                            "übertragen können. Den Stream-Schlüssel sehen Sie nach dem Speichern nicht mehr.",
                    ),
                )
            Routes.SEPA_SETTINGS ->
                PageIntroDef(
                    "sepa-settings",
                    tr(
                        "Hier schalten Sie SEPA-Lastschriften ein oder aus und pflegen die Gläubigerdaten der Organisation.",
                    ),
                )
            Routes.TRAVEL_EXPENSE_SETTINGS ->
                PageIntroDef(
                    "travel-expense-settings",
                    tr(
                        "Hier stellen Sie Sätze und Regeln für Reisekosten ein und pflegen die Unterlagen für Mitglieder.",
                    ),
                )
            Routes.PAYMENT_GATEWAY_SETTINGS ->
                PageIntroDef(
                    "payment-gateway-settings",
                    tr(
                        "Hier stellen Sie die Online-Zahlung ein. Die Zuordnung zu Konten finden Sie unter Kontenplan & Journal, " +
                            "im Reiter Einrichtung.",
                    ),
                )
            Routes.DUNNING_SETTINGS ->
                PageIntroDef(
                    "dunning-settings",
                    tr(
                        "Hier stellen Sie das Mahnwesen und die Mahnstufen ein, also wie oft und wie deutlich erinnert wird.",
                    ),
                )
            Routes.RECEIVABLE_DUNNING_SETTINGS ->
                PageIntroDef(
                    "receivable-dunning-settings",
                    tr(
                        "Konfiguration des Mahnwesens für offene Forderungen (Debitoren) -- getrennt vom Mahnwesen für " +
                            "Mitgliedsbeiträge.",
                    ),
                )
            Routes.EMBED_INTEGRATION ->
                PageIntroDef(
                    "embed-integration",
                    tr(
                        "Widgets zum Einbetten auf der eigenen Vereins-/Parteiwebsite (Mitglieder-Login, Mitglied werden).",
                    ),
                )
            Routes.MAIL_DELIVERY ->
                PageIntroDef(
                    "mail-delivery",
                    tr(
                        "Zustand des E-Mail-Versands: Stundenbudget, wartende System-E-Mails und Fehlschläge der letzten 7 " +
                            "Tage. Es werden nur Zahlen angezeigt, nie Adressen oder Inhalte.",
                    ),
                )
            else -> null
        }

    /** The routes that have an intro today (Part 1 and Part 2, every sidebar destination); the coverage test and the source scan read this list. */
    val routes: List<String> =
        listOf(
            Routes.DASHBOARD,
            Routes.CONFERENCE,
            Routes.ENCOUNTER,
            Routes.CARPOOL,
            Routes.CONTRIBUTIONS,
            Routes.DOCUMENTS,
            Routes.STATUTE_QA,
            Routes.COMMUNICATION,
            Routes.MY_CHAPTER,
            Routes.MEMBER_FINDER,
            Routes.ARTICLES,
            Routes.DONATE,
            Routes.DSGVO_RIGHTS,
            Routes.TRAVEL_EXPENSES,
            Routes.VOLUNTEER_ALLOWANCES,
            Routes.MY_VOLUNTEER_SHIFTS,
            Routes.MY_EVENTS,
            Routes.COMMITTEES,
            Routes.MEETINGS,
            Routes.MOTIONS,
            Routes.ELECTIONS,
            Routes.CONSENSUS,
            Routes.POLLS,
            Routes.LTR_LEDGER,
            Routes.CROWDFUNDING,
            Routes.AUCTION,
            Routes.POLITICIANS,
            Routes.TENDERS,
            Routes.SOCIAL_NETWORK,
            Routes.AI_DRAFTS,
            Routes.LEDGER,
            Routes.OPEN_ITEMS,
            Routes.FINANCIAL_REPORTS,
            Routes.COMPLIANCE_REPORTS,
            Routes.COST_CENTERS,
            Routes.DONORS,
            Routes.AUDIT_LOG,
            Routes.POSTAL_MAIL,
            Routes.PRICE_ORACLE,
            Routes.SEPA_MANDATES,
            Routes.SEPA_BATCHES,
            Routes.DUNNING_CASES,
            Routes.PAYMENT_TRANSACTIONS,
            Routes.BANK_ACCOUNTS,
            Routes.BANK_IMPORT,
            Routes.MEMBERSHIP_TIERS,
            Routes.CONTRIBUTION_RELIEF,
            Routes.TRAVEL_EXPENSE_APPROVALS,
            Routes.VOLUNTEER_ALLOWANCE_APPROVALS,
            Routes.MEMBERS,
            Routes.REGIONAL_CHAPTERS,
            Routes.DSGVO_COMPLIANCE,
            Routes.BOARD_MEMBERSHIP,
            Routes.SOCIAL_MODERATION,
            Routes.API_KEYS,
            Routes.CRM,
            Routes.EVENT_MANAGEMENT,
            Routes.EVENT_CHECKIN,
            Routes.EVENT_ROOMS,
            Routes.CATERING,
            Routes.EVENT_VOLUNTEERS,
            Routes.MEMBER_ANNIVERSARIES,
            Routes.MEMBER_HONORS,
            Routes.MEMBER_FAMILIES,
            Routes.MEMBER_MAP,
            Routes.MEMBER_STATISTICS,
            Routes.BACKUP,
            Routes.AGREEMENT_TEXTS,
            Routes.CONFERENCE_STREAM_DESTINATIONS,
            Routes.SEPA_SETTINGS,
            Routes.TRAVEL_EXPENSE_SETTINGS,
            Routes.PAYMENT_GATEWAY_SETTINGS,
            Routes.DUNNING_SETTINGS,
            Routes.RECEIVABLE_DUNNING_SETTINGS,
            Routes.EMBED_INTEGRATION,
            Routes.MAIL_DELIVERY,
        )
}
