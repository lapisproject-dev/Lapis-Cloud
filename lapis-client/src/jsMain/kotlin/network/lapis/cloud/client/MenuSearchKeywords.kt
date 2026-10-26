package network.lapis.cloud.client

/**
 * V1.9.203 -- the extra search words of the menu search: what a person might type for a destination whose title they do not remember
 * ("Spesen" for "Reisekosten", "Backup" for "Sicherung ...", "Auskunft" for "Meine Daten").
 *
 * GERMAN DATA, NOT TRANSLATED: the words are not run through `tr()`. In another language the search finds an entry by its translated menu
 * title plus these words. Each route lists words that are NOT already part of its title or group. Words are folded like the query
 * ([MenuSearchNormalizer]), so umlauts and "ae"/"ue" spellings do not matter.
 *
 * The coverage test `MenuIndexCompletenessTest` keeps this table complete: every destination of the sidebar has an entry here or a
 * written reason in [ALLOWED_WITHOUT_KEYWORD]; a new sidebar link without either turns the build red.
 */
object MenuSearchKeywords {
    val BY_ROUTE: Map<String, List<String>> =
        mapOf(
            Routes.DASHBOARD to listOf("startseite", "übersicht", "home", "start"),
            Routes.CONFERENCE to listOf("video", "konferenz", "videoanruf", "besprechung", "online treffen", "anruf"),
            Routes.ENCOUNTER to listOf("begegnung", "raum", "treffen", "gespräch", "andacht"),
            Routes.CARPOOL to listOf("mitfahrt", "fahrgemeinschaft", "auto", "mitfahren", "anreise"),
            Routes.CONTRIBUTIONS to listOf("mitgliedsbeitrag", "beitrag", "zahlung", "rechnung", "gebühr", "zahlen"),
            Routes.DOCUMENTS to listOf("datei", "unterlagen", "satzung", "ablage", "pdf"),
            Routes.STATUTE_QA to listOf("satzung", "ki", "assistent", "frage", "regeln"),
            Routes.COMMUNICATION to listOf("nachricht", "mail", "email", "rundschreiben", "brief", "postfach", "newsletter"),
            Routes.ARTICLES to listOf("nachrichten", "news", "blog", "text", "veröffentlichung", "beitrag"),
            Routes.MY_CHAPTER to listOf("gliederung", "landesvorstand", "kreisverband", "region"),
            Routes.MEMBER_FINDER to listOf("mitglieder suchen", "kontakt", "verzeichnis", "finden", "adressbuch"),
            Routes.DONATE to listOf("spende", "unterstützen", "zuwendung", "paypal"),
            Routes.DSGVO_RIGHTS to listOf("dsgvo", "datenschutz", "auskunft", "löschung", "datenexport", "betroffenenrechte"),
            Routes.TRAVEL_EXPENSES to listOf("spesen", "fahrtkosten", "erstattung", "abrechnung", "kilometer"),
            Routes.MY_VOLUNTEER_SHIFTS to listOf("schicht", "helfen", "ehrenamt", "dienst", "einsatz"),
            Routes.MY_EVENTS to listOf("termin", "event", "anmeldung", "tagung", "kongress"),
            Routes.VOLUNTEER_ALLOWANCES to listOf("ehrenamt", "übungsleiter", "pauschale", "aufwandsentschädigung"),
            Routes.COMMITTEES to listOf("ausschuss", "vorstand", "kommission", "arbeitsgruppe"),
            Routes.MEETINGS to listOf("protokoll", "tagesordnung", "versammlung", "termin"),
            Routes.MOTIONS to listOf("antrag", "beschluss", "vorschlag", "abstimmen"),
            Routes.ELECTIONS to listOf("wahl", "abstimmung", "stimmzettel", "kandidat", "wählen"),
            Routes.CONSENSUS to listOf("konsens", "widerstand", "systemisch", "abstimmung", "entscheidung"),
            Routes.POLLS to listOf("umfrage", "abstimmung", "meinungsbild", "befragung"),
            Routes.LTR_LEDGER to listOf("konto", "guthaben", "saldo", "kontostand", "ltr", "wallet"),
            Routes.CROWDFUNDING to listOf("projekt finanzieren", "kampagne", "fundraising", "sammeln"),
            Routes.AUCTION to listOf("versteigerung", "gebot", "bieten", "ltr handeln"),
            Routes.POLITICIANS to listOf("mandatsträger", "abgeordnete", "kandidaten", "bewertung"),
            Routes.TENDERS to listOf("angebot", "vergabe", "auftrag", "gebot"),
            Routes.SOCIAL_NETWORK to listOf("post", "community", "forum", "austausch", "feed", "beitrag"),
            Routes.AI_DRAFTS to listOf("ki", "entwurf", "agent", "mcp", "assistent"),
            Routes.LEDGER to
                listOf(
                    "buchhaltung",
                    "buchung",
                    "konten",
                    "skr42",
                    "buchungssatz",
                    "finanzbuchhaltung",
                    "kontenrahmen",
                    "einrichtung",
                    "kontenzuordnung",
                    "erlöskonto",
                ),
            Routes.OPEN_ITEMS to listOf("forderung", "verbindlichkeit", "verrechnung", "ausgleich", "zahlungsabgleich"),
            Routes.FINANCIAL_REPORTS to listOf("bilanz", "jahresabschluss", "auswertung", "bericht", "einnahmen", "ausgaben"),
            Routes.COMPLIANCE_REPORTS to listOf("gemeinnützig", "steuer", "spendenbescheinigung", "finanzamt"),
            Routes.COST_CENTERS to listOf("kostenträger", "budget", "projektkosten"),
            Routes.DONORS to listOf("spende", "zuwendungsbestätigung", "bescheinigung", "förderer"),
            Routes.AUDIT_LOG to listOf("audit", "protokoll", "änderungen", "historie", "gobd", "log", "nachvollziehen"),
            Routes.POSTAL_MAIL to listOf("brief", "serienbrief", "post", "versand", "druck"),
            Routes.PRICE_ORACLE to listOf("kurs", "preis", "kursverlauf", "wechselkurs"),
            Routes.SEPA_MANDATES to listOf("lastschrift", "einzugsermächtigung", "mandat", "iban"),
            Routes.SEPA_BATCHES to listOf("einzug", "lastschriftlauf", "abbuchung", "sammellastschrift"),
            Routes.DUNNING_CASES to listOf("mahnung", "rechnung", "zahlungserinnerung", "verzug", "inkasso"),
            Routes.PAYMENT_TRANSACTIONS to listOf("zahlung", "eingang", "überweisung", "kreditkarte", "paypal"),
            Routes.BANK_ACCOUNTS to listOf("bank", "konto", "iban", "fints", "girokonto"),
            Routes.BANK_IMPORT to listOf("auszug", "kontoauszug", "import", "camt", "mt940", "csv", "bankdaten"),
            Routes.MEMBERSHIP_TIERS to listOf("stufe", "tarif", "beitragsstufe", "kategorie", "ermäßigt"),
            Routes.CONTRIBUTION_RELIEF to listOf("ermäßigung", "befreiung", "härtefall", "antrag", "reduzierter beitrag"),
            Routes.TRAVEL_EXPENSE_APPROVALS to listOf("genehmigung", "freigabe", "spesen", "prüfen"),
            Routes.VOLUNTEER_ALLOWANCE_APPROVALS to listOf("genehmigung", "freigabe", "pauschale", "ehrenamt"),
            Routes.MEMBERS to listOf("mitglied", "stammdaten", "adresse", "eintritt", "austritt", "aufnahme", "beitritt", "personen"),
            Routes.REGIONAL_CHAPTERS to listOf("landesverband", "kreisverband", "verband", "gliederung", "region", "bezirk"),
            Routes.DSGVO_COMPLIANCE to listOf("datenschutz", "auskunft", "löschung", "löschfristen", "verarbeitung", "dsgvo", "anfragen"),
            Routes.BOARD_MEMBERSHIP to listOf("vorstand", "transparenzregister", "amtszeit", "vertretung", "geschäftsführung"),
            Routes.SOCIAL_MODERATION to listOf("meldung", "melden", "beitrag sperren", "inhalte", "verstoß"),
            Routes.API_KEYS to listOf("api", "token", "schnittstelle", "zugang", "key", "integration"),
            Routes.CRM to listOf("interessent", "kontakt", "adressen", "crm", "kontaktverwaltung"),
            Routes.EVENT_MANAGEMENT to listOf("event", "termin", "tagung", "kongress", "anmeldungen", "planung"),
            Routes.EVENT_CHECKIN to listOf("einlass", "qr", "scanner", "anwesenheit", "teilnehmerliste"),
            Routes.EVENT_ROOMS to listOf("raum", "saal", "räume", "ort", "belegung"),
            Routes.CATERING to listOf("essen", "verpflegung", "getränke", "menü", "bewirtung"),
            Routes.EVENT_VOLUNTEERS to listOf("helfer", "schicht", "dienstplan", "einsatzplan", "ehrenamt"),
            Routes.MEMBER_ANNIVERSARIES to listOf("geburtstag", "jubiläum", "mitgliedsjahre", "glückwunsch"),
            Routes.MEMBER_HONORS to listOf("ehrung", "auszeichnung", "ehrenmitglied", "urkunde", "medaille"),
            Routes.MEMBER_FAMILIES to listOf("familie", "partner", "haushalt", "kinder"),
            Routes.MEMBER_MAP to listOf("karte", "landkarte", "standort", "verteilung"),
            Routes.MEMBER_STATISTICS to listOf("statistik", "entwicklung", "zahlen", "wachstum", "diagramm", "austritte"),
            Routes.BACKUP to listOf("sicherung", "backup", "restore", "wiederherstellen", "datensicherung"),
            Routes.AGREEMENT_TEXTS to listOf("agb", "beitrittsvertrag", "vereinbarung", "einwilligung", "nutzungsbedingungen", "texte"),
            Routes.ORGANIZATION to
                listOf(
                    "organisation",
                    "zeitzone",
                    "videokonferenz",
                    "overlay",
                    "rauschunterdrückung",
                    "aufzeichnung",
                    "stream",
                    "livestream",
                    "mitglieder-finder",
                    "ausschreibung",
                    "mitgliederzahl",
                ),
            Routes.CONFERENCE_STREAM_DESTINATIONS to listOf("stream", "youtube", "livestream", "rtmp", "übertragung"),
            Routes.SEPA_SETTINGS to listOf("gläubiger-id", "sepa", "bank", "lastschrift einstellungen"),
            Routes.TRAVEL_EXPENSE_SETTINGS to listOf("kilometerpauschale", "sätze", "einstellung", "spesensätze"),
            Routes.PAYMENT_GATEWAY_SETTINGS to listOf("zahlungsanbieter", "stripe", "paypal", "gateway", "kreditkarte"),
            Routes.DUNNING_SETTINGS to listOf("mahnstufen", "mahngebühr", "fristen", "mahnung einstellungen"),
            Routes.RECEIVABLE_DUNNING_SETTINGS to listOf("forderung", "mahnstufe", "verzugszinsen"),
            Routes.EMBED_INTEGRATION to listOf("einbetten", "widget", "homepage", "iframe", "embed", "webseite"),
            Routes.MAIL_DELIVERY to listOf("smtp", "mailserver", "zustellung", "bounce", "postausgang", "email einstellungen"),
        )

    /** Destinations without search words, each with its reason. */
    val ALLOWED_WITHOUT_KEYWORD: Map<String, String> =
        mapOf(
            Routes.EVENT_IMPORT to "no sidebar link: reachable only from the event management screen, so there is nothing to find",
            Routes.ARTICLE_IMPORT to "no sidebar link: reachable only from the articles screen, so there is nothing to find",
        )
}
