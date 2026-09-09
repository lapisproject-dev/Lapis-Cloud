package network.lapis.cloud.server.routes

import kotlinx.html.FlowContent
import kotlinx.html.HEAD
import kotlinx.html.a
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.footer
import kotlinx.html.header
import kotlinx.html.img
import kotlinx.html.link
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.summary
import network.lapis.cloud.server.branding.ResolvedBranding

/**
 * Welle "Einheitlicher Kopfbereich + Sprachumschalter" -- the shared visual chrome (skip-link,
 * branded header, primary nav, language switcher, login CTA) rendered as the FIRST
 * children of `<body class="has-chrome">` on all five unauthenticated, account-less public HTML
 * route families this codebase has ([SocialPublicHtml] `/s`, [PublicTransparencyHtml]
 * `/transparenz`, [PublicLandingHtml] `/`, and, since V1.4.7 "Rechtstexte", [LegalHtml]
 * `/impressum` + `/datenschutz`). Never used by [EmbedHtml]/[EmbedDonationHtml]/
 * [EventPublicHtml] -- those keep their own, narrower `<body>` shape unchanged (see
 * `SocialPublicHtmlTest`'s Embed byte-identity regression test).
 *
 * Same five non-negotiable rendering-safety properties [SocialPublicHtml]'s own class KDoc
 * establishes apply here verbatim (escape-only `kotlinx.html` API, no request-time-dependent
 * output, identical output for crawler and human, etc.) -- this file adds NO new bypass of any of
 * them: [branding].title is the one operator-controlled string here, and it already flows through
 * `kotlinx.html`'s ordinary text-node/attribute APIs everywhere it appears (nav wordmark,
 * `alt="..."` on the optional logo).
 */
internal enum class PublicLanguage(
    val code: String,
    val nativeName: String,
) {
    DE("de", "Deutsch"),
    EN("en", "English"),
    FR("fr", "Français"),
    ES("es", "Español"),
    IT("it", "Italiano"),
    NL("nl", "Nederlands"),
    PL("pl", "Polski"),
    RU("ru", "Русский"),
    ;

    companion object {
        /**
         * Must stay reihenfolge- und mengengleich zu `network.lapis.cloud.client.App.kt`'s eigener,
         * privater `SUPPORTED_LANGUAGES`-Liste -- siehe `PublicChromeTest` "language codes match the
         * SPA's own switcher" für den Cross-Modul-Abgleich (ein echter Import ist wegen der JVM/JS-
         * Modulgrenze nicht möglich).
         */
        val DEFAULT: PublicLanguage = DE

        /** Nie werfend -- unbekannter/fehlender Code liefert `null`, niemals eine Exception. */
        fun parse(raw: String?): PublicLanguage? = entries.firstOrNull { it.code == raw }
    }
}

/**
 * Every user-facing chrome/page string across the three public route families, in every supported
 * [PublicLanguage]. Deliberately a `data class` (not a `Map<String, String>`) -- a missing
 * translation for a NEW field is a compile error in [PublicChrome.STRINGS], not a silent runtime
 * blank/fallback.
 *
 * Deliberately EXCLUDES `consentNote` ("Ihr Name erscheint nur, wenn mindestens fünf Mitglieder
 * zugestimmt haben.") -- that ranking-consent disclaimer is a legal text tied to a specific,
 * German-language consent flow ([network.lapis.cloud.server.rpc.PublicRankingConsentDisclaimer])
 * and stays German (`lang="de"`) regardless of the chrome's language, see
 * [PublicTransparencyHtml.renderRankingSection]. Also excludes every string on the report form
 * (`GET`/`POST /s/{id}/report`) -- that DSA Art. 16 legal workflow stays entirely German for the
 * same reason, see [SocialPublicHtml.reportFormPage].
 */
internal data class PublicUiStrings(
    // -- Chrome (shared across all three route families) --
    val navHome: String,
    val navTransparency: String,
    val navSocial: String,
    val login: String,
    val register: String,
    val languageLabel: String,
    val skipToContent: String,
    /** Footer line, e.g. "Powered by Lapis Cloud". */
    val operatedBy: String,
    /**
     * V1.4.7 "Rechtstexte" -- footer link LABEL for `/impressum`. The FULL TEXT behind this link is
     * German-only regardless of [PublicLanguage] (see [LegalHtml]); only this label is translated,
     * with `hreflang="de"` on the `<a>` itself making the discrepancy explicit rather than silent.
     */
    val legalImprint: String,
    /** V1.4.7 "Rechtstexte" -- footer link LABEL for `/datenschutz`, see [legalImprint] KDoc. */
    val legalPrivacy: String,
    /**
     * V1.4.7 "Rechtstexte" -- ONE sentence at the top of `/impressum`/`/datenschutz`, in the CHROME
     * language, telling the reader the full text below is German-only. Deliberately no more than one
     * sentence (Design-Team-Sitzung V1.4.7, Jobs).
     */
    val legalGermanOnlyNote: String,
    // -- "/" (PublicLandingHtml) --
    val tagline: String,
    val statMembers: String,
    val statLtr: String,
    val statPosts: String,
    val linkTransparency: String,
    // -- "/s" (SocialPublicHtml) --
    val socialH1: String,
    val noPosts: String,
    val prevPage: String,
    val nextPage: String,
    val backToTimeline: String,
    val replies: String,
    val moreRepliesHidden: String,
    // -- "/transparenz" (PublicTransparencyHtml) --
    val transparencyH1: String,
    val jumpStats: String,
    val jumpBoard: String,
    val jumpPosts: String,
    val jumpLtr: String,
    val jumpDonors: String,
    val boardEmpty: String,
    val topPosts: String,
    val topLtrHolders: String,
    /** Format string with ONE `%d` placeholder for the donation year, e.g. "Top donors %d". */
    val topDonorsFormat: String,
    // -- shared between "/" and "/transparenz" --
    val allPosts: String,
    // -- committee roles (network.lapis.cloud.shared.domain.CommitteeRole), used on "/transparenz" --
    val committeeRoleChair: String,
    val committeeRoleDeputyChair: String,
    val committeeRoleSecretary: String,
    val committeeRoleAssessor: String,
    val committeeRoleMember: String,
)

internal object PublicChrome {
    /**
     * One [PublicUiStrings] instance per [PublicLanguage], all 8 mandatory -- a missing entry is a
     * compile error (a `mapOf` literal that omits a key still type-checks, so completeness is
     * additionally verified at runtime by `PublicChromeTest` "every PublicLanguage has a STRINGS
     * entry, no field blank").
     *
     * Translation provenance (Umsetzungsplan § 3): the CHROME-level strings (nav labels,
     * login/register, committee roles, "Powered by ...") were cross-checked against
     * `lapis-client/src/jsMain/resources/modules/i18n/messages-*.po` where an unambiguous match
     * exists. Several German words are FALSE FRIENDS between the two contexts, though --
     * `"Beiträge"` means "membership dues" in the SPA's own catalog (`CrmContactsScreen` etc., see
     * French "Cotisations"/Spanish "Cuotas") but means "social network posts" here -- so every
     * post-related string below was translated fresh for THIS meaning, never copied from the SPA
     * catalog's "Beiträge" entries. The remaining, `/transparenz`- and `/s`-specific strings have no
     * SPA precedent at all (that surface never existed in the authenticated app) and are best-effort
     * translations, not yet reviewed by a native speaker of each language -- see plan § 9 "offene
     * Fragen".
     */
    val STRINGS: Map<PublicLanguage, PublicUiStrings> =
        mapOf(
            PublicLanguage.DE to
                PublicUiStrings(
                    navHome = "Startseite",
                    navTransparency = "Transparenz",
                    navSocial = "Soziales Netzwerk",
                    login = "Anmelden",
                    register = "Mitglied werden",
                    languageLabel = "Sprache",
                    skipToContent = "Zum Inhalt springen",
                    operatedBy = "Betrieben mit Lapis Cloud",
                    legalImprint = "Impressum",
                    legalPrivacy = "Datenschutz",
                    legalGermanOnlyNote = "Dieser Rechtstext liegt ausschließlich auf Deutsch vor.",
                    tagline = "Mitgliederverwaltung -- föderiert, transparent, in Ihrer Hand.",
                    statMembers = "Mitglieder",
                    statLtr = "Insgesamt ausgegebene LTR",
                    statPosts = "Öffentliche Beiträge",
                    linkTransparency = "Vorstand, Ranglisten und Finanzkennzahlen",
                    socialH1 = "Soziales Netzwerk",
                    noPosts = "Noch keine öffentlichen Beiträge.",
                    prevPage = "Vorherige Seite",
                    nextPage = "Nächste Seite",
                    backToTimeline = "Zur Timeline",
                    replies = "Antworten",
                    moreRepliesHidden = "Weitere Antworten werden hier nicht angezeigt.",
                    transparencyH1 = "Transparenz",
                    jumpStats = "Kennzahlen",
                    jumpBoard = "Vorstand",
                    jumpPosts = "Beiträge",
                    jumpLtr = "LTR-Halter",
                    jumpDonors = "Spender",
                    boardEmpty = "Derzeit kein besetzter Vorstand.",
                    topPosts = "Top-Beiträge",
                    topLtrHolders = "Top-LTR-Halter",
                    topDonorsFormat = "Top-Spender %d",
                    allPosts = "Alle Beiträge",
                    committeeRoleChair = "Vorsitz",
                    committeeRoleDeputyChair = "Stellv. Vorsitz",
                    committeeRoleSecretary = "Schriftführung",
                    committeeRoleAssessor = "Beisitz",
                    committeeRoleMember = "Mitglied",
                ),
            PublicLanguage.EN to
                PublicUiStrings(
                    navHome = "Home",
                    navTransparency = "Transparency",
                    navSocial = "Social network",
                    login = "Log in",
                    register = "Become a member",
                    languageLabel = "Language",
                    skipToContent = "Skip to content",
                    operatedBy = "Powered by Lapis Cloud",
                    legalImprint = "Legal notice",
                    legalPrivacy = "Privacy",
                    legalGermanOnlyNote = "This legal text is available in German only.",
                    tagline = "Membership management -- federated, transparent, in your hands.",
                    statMembers = "Members",
                    statLtr = "Total LTR issued",
                    statPosts = "Public posts",
                    linkTransparency = "Board, rankings, and financial key figures",
                    socialH1 = "Social network",
                    noPosts = "No public posts yet.",
                    prevPage = "Previous page",
                    nextPage = "Next page",
                    backToTimeline = "Back to timeline",
                    replies = "Replies",
                    moreRepliesHidden = "Further replies are not shown here.",
                    transparencyH1 = "Transparency",
                    jumpStats = "Key figures",
                    jumpBoard = "Board",
                    jumpPosts = "Posts",
                    jumpLtr = "LTR holders",
                    jumpDonors = "Donors",
                    boardEmpty = "No board currently in office.",
                    topPosts = "Top posts",
                    topLtrHolders = "Top LTR holders",
                    topDonorsFormat = "Top donors %d",
                    allPosts = "All posts",
                    committeeRoleChair = "Chair",
                    committeeRoleDeputyChair = "Deputy Chair",
                    committeeRoleSecretary = "Secretary",
                    committeeRoleAssessor = "Assessor",
                    committeeRoleMember = "Member",
                ),
            PublicLanguage.FR to
                PublicUiStrings(
                    navHome = "Accueil",
                    navTransparency = "Transparence",
                    navSocial = "Réseau social",
                    login = "Se connecter",
                    register = "Devenir membre",
                    languageLabel = "Langue",
                    skipToContent = "Aller au contenu",
                    operatedBy = "Propulsé par Lapis Cloud",
                    legalImprint = "Mentions légales",
                    legalPrivacy = "Confidentialité",
                    legalGermanOnlyNote = "Ce texte juridique n'est disponible qu'en allemand.",
                    tagline = "Gestion des membres -- fédérée, transparente, entre vos mains.",
                    statMembers = "Membres",
                    statLtr = "Total des LTR émis",
                    statPosts = "Publications publiques",
                    linkTransparency = "Conseil d'administration, classements et indicateurs financiers",
                    socialH1 = "Réseau social",
                    noPosts = "Aucune publication publique pour le moment.",
                    prevPage = "Page précédente",
                    nextPage = "Page suivante",
                    backToTimeline = "Retour à la chronologie",
                    replies = "Réponses",
                    moreRepliesHidden = "D'autres réponses ne sont pas affichées ici.",
                    transparencyH1 = "Transparence",
                    jumpStats = "Indicateurs",
                    jumpBoard = "Conseil d'administration",
                    jumpPosts = "Publications",
                    jumpLtr = "Détenteurs de LTR",
                    jumpDonors = "Donateurs",
                    boardEmpty = "Aucun conseil d'administration en fonction actuellement.",
                    topPosts = "Publications les mieux notées",
                    topLtrHolders = "Principaux détenteurs de LTR",
                    topDonorsFormat = "Principaux donateurs %d",
                    allPosts = "Toutes les publications",
                    committeeRoleChair = "Présidence",
                    committeeRoleDeputyChair = "Présidence adjointe",
                    committeeRoleSecretary = "Secrétariat",
                    committeeRoleAssessor = "Assesseur",
                    committeeRoleMember = "Membre",
                ),
            PublicLanguage.ES to
                PublicUiStrings(
                    navHome = "Inicio",
                    navTransparency = "Transparencia",
                    navSocial = "Red social",
                    login = "Iniciar sesión",
                    register = "Hacerse miembro",
                    languageLabel = "Idioma",
                    skipToContent = "Saltar al contenido",
                    operatedBy = "Desarrollado con Lapis Cloud",
                    legalImprint = "Aviso legal",
                    legalPrivacy = "Privacidad",
                    legalGermanOnlyNote = "Este texto legal solo está disponible en alemán.",
                    tagline = "Gestión de miembros -- federada, transparente, en sus manos.",
                    statMembers = "Miembros",
                    statLtr = "Total de LTR emitidos",
                    statPosts = "Publicaciones públicas",
                    linkTransparency = "Junta directiva, clasificaciones e indicadores financieros",
                    socialH1 = "Red social",
                    noPosts = "Todavía no hay publicaciones públicas.",
                    prevPage = "Página anterior",
                    nextPage = "Página siguiente",
                    backToTimeline = "Volver a la cronología",
                    replies = "Respuestas",
                    moreRepliesHidden = "Aquí no se muestran más respuestas.",
                    transparencyH1 = "Transparencia",
                    jumpStats = "Indicadores",
                    jumpBoard = "Junta directiva",
                    jumpPosts = "Publicaciones",
                    jumpLtr = "Titulares de LTR",
                    jumpDonors = "Donantes",
                    boardEmpty = "Actualmente no hay una junta directiva en funciones.",
                    topPosts = "Publicaciones destacadas",
                    topLtrHolders = "Principales titulares de LTR",
                    topDonorsFormat = "Principales donantes %d",
                    allPosts = "Todas las publicaciones",
                    committeeRoleChair = "Presidencia",
                    committeeRoleDeputyChair = "Vicepresidencia",
                    committeeRoleSecretary = "Secretaría",
                    committeeRoleAssessor = "Vocalía",
                    committeeRoleMember = "Miembro",
                ),
            PublicLanguage.IT to
                PublicUiStrings(
                    navHome = "Home",
                    navTransparency = "Trasparenza",
                    navSocial = "Rete sociale",
                    login = "Accedi",
                    register = "Diventa membro",
                    languageLabel = "Lingua",
                    skipToContent = "Vai al contenuto",
                    operatedBy = "Realizzato con Lapis Cloud",
                    legalImprint = "Note legali",
                    legalPrivacy = "Privacy",
                    legalGermanOnlyNote = "Questo testo legale è disponibile solo in tedesco.",
                    tagline = "Gestione dei soci -- federata, trasparente, nelle vostre mani.",
                    statMembers = "Membri",
                    statLtr = "Totale LTR emessi",
                    statPosts = "Post pubblici",
                    linkTransparency = "Consiglio direttivo, classifiche e indicatori finanziari",
                    socialH1 = "Rete sociale",
                    noPosts = "Ancora nessun post pubblico.",
                    prevPage = "Pagina precedente",
                    nextPage = "Pagina successiva",
                    backToTimeline = "Torna alla timeline",
                    replies = "Risposte",
                    moreRepliesHidden = "Ulteriori risposte non vengono mostrate qui.",
                    transparencyH1 = "Trasparenza",
                    jumpStats = "Indicatori",
                    jumpBoard = "Consiglio direttivo",
                    jumpPosts = "Post",
                    jumpLtr = "Detentori di LTR",
                    jumpDonors = "Donatori",
                    boardEmpty = "Al momento nessun consiglio direttivo in carica.",
                    topPosts = "Post principali",
                    topLtrHolders = "Principali detentori di LTR",
                    topDonorsFormat = "Principali donatori %d",
                    allPosts = "Tutti i post",
                    committeeRoleChair = "Presidenza",
                    committeeRoleDeputyChair = "Vicepresidenza",
                    committeeRoleSecretary = "Segreteria",
                    committeeRoleAssessor = "Assessorato",
                    committeeRoleMember = "Membro",
                ),
            PublicLanguage.NL to
                PublicUiStrings(
                    navHome = "Startpagina",
                    navTransparency = "Transparantie",
                    navSocial = "Sociaal netwerk",
                    login = "Aanmelden",
                    register = "Lid worden",
                    languageLabel = "Taal",
                    skipToContent = "Naar de inhoud",
                    operatedBy = "Mogelijk gemaakt door Lapis Cloud",
                    legalImprint = "Colofon",
                    legalPrivacy = "Privacybeleid",
                    legalGermanOnlyNote = "Deze juridische tekst is alleen in het Duits beschikbaar.",
                    tagline = "Ledenbeheer -- gefedereerd, transparant, in uw handen.",
                    statMembers = "Leden",
                    statLtr = "Totaal uitgegeven LTR",
                    statPosts = "Openbare berichten",
                    linkTransparency = "Bestuur, ranglijsten en financiële kengetallen",
                    socialH1 = "Sociaal netwerk",
                    noPosts = "Nog geen openbare berichten.",
                    prevPage = "Vorige pagina",
                    nextPage = "Volgende pagina",
                    backToTimeline = "Terug naar de tijdlijn",
                    replies = "Antwoorden",
                    moreRepliesHidden = "Verdere antwoorden worden hier niet weergegeven.",
                    transparencyH1 = "Transparantie",
                    jumpStats = "Kengetallen",
                    jumpBoard = "Bestuur",
                    jumpPosts = "Berichten",
                    jumpLtr = "LTR-houders",
                    jumpDonors = "Donateurs",
                    boardEmpty = "Momenteel geen bezet bestuur.",
                    topPosts = "Topberichten",
                    topLtrHolders = "Grootste LTR-houders",
                    topDonorsFormat = "Grootste donateurs %d",
                    allPosts = "Alle berichten",
                    committeeRoleChair = "Voorzitterschap",
                    committeeRoleDeputyChair = "Vicevoorzitterschap",
                    committeeRoleSecretary = "Secretariaat",
                    committeeRoleAssessor = "Bijzitter",
                    committeeRoleMember = "Lid",
                ),
            PublicLanguage.PL to
                PublicUiStrings(
                    navHome = "Strona główna",
                    navTransparency = "Przejrzystość",
                    navSocial = "Sieć społecznościowa",
                    login = "Zaloguj się",
                    register = "Zostań członkiem",
                    languageLabel = "Język",
                    skipToContent = "Przejdź do treści",
                    operatedBy = "Obsługiwane przez Lapis Cloud",
                    legalImprint = "Nota prawna",
                    legalPrivacy = "Prywatność",
                    legalGermanOnlyNote = "Ten tekst prawny jest dostępny wyłącznie w języku niemieckim.",
                    tagline = "Zarządzanie członkostwem -- sfederowane, przejrzyste, w Twoich rękach.",
                    statMembers = "Członkowie",
                    statLtr = "Łącznie wyemitowane LTR",
                    statPosts = "Publiczne wpisy",
                    linkTransparency = "Zarząd, rankingi i wskaźniki finansowe",
                    socialH1 = "Sieć społecznościowa",
                    noPosts = "Brak publicznych wpisów.",
                    prevPage = "Poprzednia strona",
                    nextPage = "Następna strona",
                    backToTimeline = "Powrót do osi czasu",
                    replies = "Odpowiedzi",
                    moreRepliesHidden = "Kolejne odpowiedzi nie są tu wyświetlane.",
                    transparencyH1 = "Przejrzystość",
                    jumpStats = "Wskaźniki",
                    jumpBoard = "Zarząd",
                    jumpPosts = "Wpisy",
                    jumpLtr = "Posiadacze LTR",
                    jumpDonors = "Darczyńcy",
                    boardEmpty = "Obecnie brak urzędującego zarządu.",
                    topPosts = "Najlepsze wpisy",
                    topLtrHolders = "Najwięksi posiadacze LTR",
                    topDonorsFormat = "Najwięksi darczyńcy %d",
                    allPosts = "Wszystkie wpisy",
                    committeeRoleChair = "Przewodnictwo",
                    committeeRoleDeputyChair = "Zastępca przewodniczącego",
                    committeeRoleSecretary = "Sekretariat",
                    committeeRoleAssessor = "Członek zwyczajny",
                    committeeRoleMember = "Członek",
                ),
            PublicLanguage.RU to
                PublicUiStrings(
                    navHome = "Главная",
                    navTransparency = "Прозрачность",
                    navSocial = "Социальная сеть",
                    login = "Войти",
                    register = "Стать участником",
                    languageLabel = "Язык",
                    skipToContent = "Перейти к содержимому",
                    operatedBy = "Работает на базе Lapis Cloud",
                    legalImprint = "Правовая информация",
                    legalPrivacy = "Конфиденциальность",
                    legalGermanOnlyNote = "Этот правовой текст доступен только на немецком языке.",
                    tagline = "Управление членством -- федеративно, прозрачно, в ваших руках.",
                    statMembers = "Участники",
                    statLtr = "Всего выпущено LTR",
                    statPosts = "Публичные записи",
                    linkTransparency = "Правление, рейтинги и финансовые показатели",
                    socialH1 = "Социальная сеть",
                    noPosts = "Пока нет публичных записей.",
                    prevPage = "Предыдущая страница",
                    nextPage = "Следующая страница",
                    backToTimeline = "К ленте записей",
                    replies = "Ответы",
                    moreRepliesHidden = "Дальнейшие ответы здесь не показаны.",
                    transparencyH1 = "Прозрачность",
                    jumpStats = "Показатели",
                    jumpBoard = "Правление",
                    jumpPosts = "Записи",
                    jumpLtr = "Держатели LTR",
                    jumpDonors = "Жертвователи",
                    boardEmpty = "В настоящее время правление не сформировано.",
                    topPosts = "Лучшие записи",
                    topLtrHolders = "Крупнейшие держатели LTR",
                    topDonorsFormat = "Крупнейшие жертвователи %d",
                    allPosts = "Все записи",
                    committeeRoleChair = "Председательство",
                    committeeRoleDeputyChair = "Заместитель председателя",
                    committeeRoleSecretary = "Секретариат",
                    committeeRoleAssessor = "Заседатель",
                    committeeRoleMember = "Участник",
                ),
        )

    fun stringsFor(lang: PublicLanguage): PublicUiStrings = STRINGS.getValue(lang)

    /** Which of the three route families' nav entries (if any) is the "you are here" page. */
    internal enum class NavTarget { HOME, TRANSPARENCY, SOCIAL }

    /**
     * `"$baseUrl$currentPath"`, with a `?lang=`/`&lang=` suffix appended UNLESS [lang] is
     * [PublicLanguage.DEFAULT] -- German is the unparameterized, canonical default, exactly the same
     * "no `?page=1`" convention `SocialPublicRoutes.timelineCanonicalUrl` already establishes for
     * pagination. [currentPath] must already be query-string-free of any `lang` parameter (every
     * caller constructs it that way, see each route file's own "Ablauf").
     */
    fun languageUrl(
        baseUrl: String,
        currentPath: String,
        lang: PublicLanguage,
    ): String {
        if (lang == PublicLanguage.DEFAULT) return "$baseUrl$currentPath"
        val separator = if (currentPath.contains('?')) "&" else "?"
        return "$baseUrl$currentPath${separator}lang=${lang.code}"
    }

    /**
     * Renders the skip-link + `<header class="chrome">` -- the caller emits these as the FIRST
     * children of `<body class="has-chrome">`, before its own `<main id="main">`. [currentPath] is
     * used ONLY to build the language-switcher links ([languageUrl]) -- never echoed back into any
     * `href`/`src` untransformed (it is always one of a small, caller-constructed set of fixed
     * shapes, never raw request input, see each route file's own "Ablauf" KDoc).
     *
     * Nutzer-Feedback 2026-09-09: nur EIN CTA hier -- "Anmelden". [PublicUiStrings.register]
     * ("Mitglied werden") lebte hier zusätzlich zum bereits eigenen, primären Hero-CTA auf `/`
     * ([PublicLandingHtml.renderHero]) -- auf `/transparenz` und `/s` gab es dafür GAR keinen Hero,
     * die Chrome-Kopie war dort der einzige Beleg. Ergebnis: doppelt auf der Startseite, aber nicht
     * konsequent überall. Jetzt einheitlich: "Mitglied werden" bleibt ausschließlich der primäre
     * Hero-CTA von `/`, der Kopfbereich zeigt nur noch den seitenübergreifend sinnvollen "Anmelden"-
     * Link. [PublicUiStrings.register] selbst bleibt im Datensatz (weiterhin vom Hero verwendet).
     */
    fun FlowContent.renderChrome(
        lang: PublicLanguage,
        active: NavTarget?,
        baseUrl: String,
        branding: ResolvedBranding,
        currentPath: String,
    ) {
        val strings = stringsFor(lang)
        a(href = "#main", classes = "skip-link") { +strings.skipToContent }
        header(classes = "chrome") {
            attributes["lang"] = lang.code
            attributes["role"] = "banner"
            div(classes = "chrome-inner") {
                a(href = baseUrl, classes = "chrome-brand") {
                    if (branding.logoAvailable) {
                        img(src = "/api/branding/logo", alt = branding.title, classes = "chrome-logo")
                    } else {
                        span(classes = "chrome-wordmark") { +branding.title }
                    }
                }
                nav(classes = "chrome-nav") {
                    attributes["aria-label"] = strings.navHome
                    a(href = baseUrl) {
                        if (active == NavTarget.HOME) attributes["aria-current"] = "page"
                        +strings.navHome
                    }
                    a(href = "$baseUrl/transparenz") {
                        if (active == NavTarget.TRANSPARENCY) attributes["aria-current"] = "page"
                        +strings.navTransparency
                    }
                    a(href = "$baseUrl/s") {
                        if (active == NavTarget.SOCIAL) attributes["aria-current"] = "page"
                        +strings.navSocial
                    }
                }
                details(classes = "chrome-lang") {
                    summary { +"${lang.nativeName} ▾" }
                    div {
                        attributes["role"] = "menu"
                        attributes["aria-label"] = strings.languageLabel
                        PublicLanguage.entries.forEach { entry ->
                            a(href = languageUrl(baseUrl = baseUrl, currentPath = currentPath, lang = entry)) {
                                if (entry == lang) attributes["aria-current"] = "true"
                                attributes["hreflang"] = entry.code
                                +entry.nativeName
                            }
                        }
                    }
                }
                a(href = "$baseUrl/app#/login", classes = "chrome-cta") { +strings.login }
            }
        }
    }

    /**
     * V1.4.7 "Rechtstexte" -- the shared footer of ALL `body.has-chrome` pages. Up to this wave,
     * `footer { p { +"${branding.title} · ${strings.operatedBy}" } }` stood WORD FOR WORD in ten
     * render methods across three files ([PublicLandingHtml], [PublicTransparencyHtml],
     * [SocialPublicHtml] eight times) -- adding two legal links would have meant the same edit ten
     * times, and forgetting the eleventh (Kay, Design-Team-Sitzung V1.4.7; Jobs: "Vorbedingung,
     * nicht Nachbereitung"). Head and foot are ONE component, hence living next to [renderChrome]
     * (Raskin).
     *
     * Deliberately NOT used by [EmbedHtml]/[EmbedDonationHtml]/[EventPublicHtml] -- those never set
     * `has-chrome` and have their own `<body>` shapes (see [renderChrome] KDoc).
     *
     * The Impressum/Datenschutz FULL TEXTS are German-only (see [LegalHtml]); only these LINK
     * LABELS are translated. `hreflang="de"` on the link is the standard mechanism that makes this
     * discrepancy explicit rather than silent (Tesler vs. Kare, Jobs decided for Tesler).
     *
     * Deliberately NO `?lang=` parameter on either link -- the target pages have only one language
     * version of the full text, so the footer link always points at the canonical, unparameterized
     * URL even when the surrounding chrome is currently in another language.
     *
     * [extra], when given, renders BEFORE the brand/legal line -- its one caller is
     * [SocialPublicHtml.legallyRemovedPage], whose footer additionally carries the "back to
     * timeline" link.
     */
    fun FlowContent.renderPublicFooter(
        lang: PublicLanguage,
        baseUrl: String,
        branding: ResolvedBranding,
        extra: (FlowContent.() -> Unit)? = null,
    ) {
        val strings = stringsFor(lang)
        footer {
            extra?.invoke(this)
            p { +"${branding.title} · ${strings.operatedBy}" }
            p(classes = "footer-legal") {
                a(href = "$baseUrl/impressum") {
                    attributes["hreflang"] = "de"
                    +strings.legalImprint
                }
                span {
                    attributes["aria-hidden"] = "true"
                    +" · "
                }
                a(href = "$baseUrl/datenschutz") {
                    attributes["hreflang"] = "de"
                    +strings.legalPrivacy
                }
            }
        }
    }

    /**
     * `<link rel="alternate" hreflang="...">` for every [PublicLanguage] plus one `x-default` entry
     * pointing at [PublicLanguage.DEFAULT] -- only ever called for `index,follow` pages (`/`, `/s`,
     * `/s/{id}`), never `/transparenz` (stays `noindex,follow`, see [PublicTransparencyHtml]'s own
     * KDoc on why it must not be crawled at all).
     */
    fun HEAD.renderHreflangAlternates(
        baseUrl: String,
        currentPath: String,
    ) {
        PublicLanguage.entries.forEach { entry ->
            link(rel = "alternate", href = languageUrl(baseUrl = baseUrl, currentPath = currentPath, lang = entry)) {
                attributes["hreflang"] = entry.code
            }
        }
        link(rel = "alternate", href = languageUrl(baseUrl = baseUrl, currentPath = currentPath, lang = PublicLanguage.DEFAULT)) {
            attributes["hreflang"] = "x-default"
        }
    }
}
