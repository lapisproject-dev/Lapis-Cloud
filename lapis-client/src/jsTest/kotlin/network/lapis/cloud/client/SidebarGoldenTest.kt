package network.lapis.cloud.client

import io.kvision.html.Link
import io.kvision.navbar.Nav
import io.kvision.panel.SimplePanel
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RegionalChapterRefDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * V1.9.220 -- the reference of the sidebar, written BEFORE the navigation registry took over the construction of the sidebar. For every
 * kind of person it pins the ordered list of visible links (`route|label|icon`) and the group headers (`# label`). If a byte differs,
 * the registry changed what a person sees. Labels are the resolved text without counters (the counters arrive asynchronously).
 */
class SidebarGoldenTest {
    private fun session(
        role: AccountRole,
        status: MemberStatus = MemberStatus.ACTIVE,
        chapter: Boolean = false,
    ) = SessionInfoDto(
        memberId = "m-1",
        displayName = "Person",
        role = role,
        expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        status = status,
        aiAssistantEnabled = true,
        mcpEnabled = true,
        chapterScope = if (chapter) RegionalChapterRefDto(id = "c-1", name = "Chapter") else null,
    )

    @AfterTest
    fun tearDown() {
        AppState.onSessionChange = {}
        AppState.setSession(null)
        MemberFinderNav.setOfferedForTest(null)
        MenuIndex.reset()
        NavHighlight.reset()
    }

    /** Ordered description of the sidebar built for [session]. */
    private fun dump(
        session: SessionInfoDto,
        finderOffered: Boolean?,
    ): List<String> {
        AppState.setSession(session)
        MemberFinderNav.setOfferedForTest(finderOffered)
        val body = SimplePanel()
        buildSidebar(body, session, activeRoute = null) {}
        val nav = body.getChildren().first() as Nav
        val lines = mutableListOf<String>()
        nav.getChildren().forEach { child ->
            when {
                child is Link -> if (child.visible) lines += describe(child, "")
                child is Nav ->
                    child
                        .getChildren()
                        .filterIsInstance<Link>()
                        .filter { it.visible }
                        .forEach { lines += describe(it, "  ") }
                child is io.kvision.html.Button -> lines += "# ${plainMenuText(child.text.orEmpty())}"
            }
        }
        return lines
    }

    private fun describe(
        link: Link,
        indent: String,
    ): String = "$indent${link.url.orEmpty().removePrefix("#")}|${plainMenuText(link.label)}|${link.icon.orEmpty()}"

    private fun check(
        name: String,
        session: SessionInfoDto,
        finderOffered: Boolean? = null,
    ) {
        val actual = dump(session, finderOffered)
        assertEquals(GOLDEN.getValue(name), actual.joinToString("\n"), "sidebar of $name")
    }

    @Test fun member() = check("member", session(AccountRole.MEMBER))

    @Test fun memberWithChapterAndFinder() = check("memberChapterFinder", session(AccountRole.MEMBER, chapter = true), finderOffered = true)

    @Test fun memberFinderOffFalse() = check("memberFinderFalse", session(AccountRole.MEMBER), finderOffered = false)

    @Test fun treasurer() = check("treasurer", session(AccountRole.TREASURER))

    @Test fun board() = check("board", session(AccountRole.BOARD))

    @Test fun admin() = check("admin", session(AccountRole.ADMIN))

    @Test fun friend() = check("friend", session(AccountRole.MEMBER, MemberStatus.FRIEND))

    @Test fun guest() = check("guest", session(AccountRole.MEMBER, MemberStatus.GUEST))

    private companion object {
        val GOLDEN: Map<String, String> =
            mapOf(
                "member" to
                    """
                    /dashboard|Dashboard|fas fa-house
                    /conference|Videokonferenz|fas fa-video
                    /begegnung|Begegnungsräume|fas fa-church
                    # Mitgliedschaft
                      /contributions|Beiträge|fas fa-coins
                      /documents|Dokumente|fas fa-file-lines
                      /statute-qa|Fragen zur Satzung|fas fa-magnifying-glass
                      /messages|Nachrichten|fas fa-envelope
                      /mailing-lists|Mailinglisten|fas fa-envelopes-bulk
                      /carpool|Mitfahrzentrale|fas fa-car-side
                      /articles|Artikel|fas fa-newspaper
                      /donate|Spenden|fas fa-hand-holding-heart
                      /dsgvo-rights|Meine Daten|fas fa-shield-halved
                      /travel-expenses|Reisekosten|fas fa-car
                      /volunteer-allowances|Ehrenamtspauschalen|fas fa-hands-helping
                      /my-volunteer-shifts|Helferschichten|fas fa-calendar-check
                      /my-events|Veranstaltungen|fas fa-ticket
                    # Selbstverwaltung
                      /committees|Gremien|fas fa-people-group
                      /meetings|Sitzungen|fas fa-calendar-days
                      /motions|Anträge|fas fa-file-signature
                      /elections|Wahlen|fas fa-check-to-slot
                      /consensus|Konsensieren|fas fa-scale-balanced
                      /polls|Umfragen|fas fa-square-poll-horizontal
                    # Wirtschaft
                      /ltr-ledger|LTR-Konto|fas fa-wallet
                      /crowdfunding|Crowdfunding|fas fa-hand-holding-heart
                      /auction|Auktion|fas fa-gavel
                      /politicians|Politiker|fas fa-landmark
                      /tenders|Ausschreibungen|fas fa-handshake
                      /social-network|Soziales Netzwerk|fas fa-comments
                      /ai-drafts|KI-Entwürfe|fas fa-wand-magic-sparkles
                    """.trimIndent(),
                "memberChapterFinder" to
                    """
                    /dashboard|Dashboard|fas fa-house
                    /conference|Videokonferenz|fas fa-video
                    /begegnung|Begegnungsräume|fas fa-church
                    # Mitgliedschaft
                      /contributions|Beiträge|fas fa-coins
                      /documents|Dokumente|fas fa-file-lines
                      /statute-qa|Fragen zur Satzung|fas fa-magnifying-glass
                      /messages|Nachrichten|fas fa-envelope
                      /mailing-lists|Mailinglisten|fas fa-envelopes-bulk
                      /my-chapter|Mein Landesverband|fas fa-users-rectangle
                      /carpool|Mitfahrzentrale|fas fa-car-side
                      /member-finder|Mitglieder-Finder|fas fa-user-group
                      /articles|Artikel|fas fa-newspaper
                      /donate|Spenden|fas fa-hand-holding-heart
                      /dsgvo-rights|Meine Daten|fas fa-shield-halved
                      /travel-expenses|Reisekosten|fas fa-car
                      /volunteer-allowances|Ehrenamtspauschalen|fas fa-hands-helping
                      /my-volunteer-shifts|Helferschichten|fas fa-calendar-check
                      /my-events|Veranstaltungen|fas fa-ticket
                    # Selbstverwaltung
                      /committees|Gremien|fas fa-people-group
                      /meetings|Sitzungen|fas fa-calendar-days
                      /motions|Anträge|fas fa-file-signature
                      /elections|Wahlen|fas fa-check-to-slot
                      /consensus|Konsensieren|fas fa-scale-balanced
                      /polls|Umfragen|fas fa-square-poll-horizontal
                    # Wirtschaft
                      /ltr-ledger|LTR-Konto|fas fa-wallet
                      /crowdfunding|Crowdfunding|fas fa-hand-holding-heart
                      /auction|Auktion|fas fa-gavel
                      /politicians|Politiker|fas fa-landmark
                      /tenders|Ausschreibungen|fas fa-handshake
                      /social-network|Soziales Netzwerk|fas fa-comments
                      /ai-drafts|KI-Entwürfe|fas fa-wand-magic-sparkles
                    """.trimIndent(),
                "memberFinderFalse" to
                    """
                    /dashboard|Dashboard|fas fa-house
                    /conference|Videokonferenz|fas fa-video
                    /begegnung|Begegnungsräume|fas fa-church
                    # Mitgliedschaft
                      /contributions|Beiträge|fas fa-coins
                      /documents|Dokumente|fas fa-file-lines
                      /statute-qa|Fragen zur Satzung|fas fa-magnifying-glass
                      /messages|Nachrichten|fas fa-envelope
                      /mailing-lists|Mailinglisten|fas fa-envelopes-bulk
                      /carpool|Mitfahrzentrale|fas fa-car-side
                      /articles|Artikel|fas fa-newspaper
                      /donate|Spenden|fas fa-hand-holding-heart
                      /dsgvo-rights|Meine Daten|fas fa-shield-halved
                      /travel-expenses|Reisekosten|fas fa-car
                      /volunteer-allowances|Ehrenamtspauschalen|fas fa-hands-helping
                      /my-volunteer-shifts|Helferschichten|fas fa-calendar-check
                      /my-events|Veranstaltungen|fas fa-ticket
                    # Selbstverwaltung
                      /committees|Gremien|fas fa-people-group
                      /meetings|Sitzungen|fas fa-calendar-days
                      /motions|Anträge|fas fa-file-signature
                      /elections|Wahlen|fas fa-check-to-slot
                      /consensus|Konsensieren|fas fa-scale-balanced
                      /polls|Umfragen|fas fa-square-poll-horizontal
                    # Wirtschaft
                      /ltr-ledger|LTR-Konto|fas fa-wallet
                      /crowdfunding|Crowdfunding|fas fa-hand-holding-heart
                      /auction|Auktion|fas fa-gavel
                      /politicians|Politiker|fas fa-landmark
                      /tenders|Ausschreibungen|fas fa-handshake
                      /social-network|Soziales Netzwerk|fas fa-comments
                      /ai-drafts|KI-Entwürfe|fas fa-wand-magic-sparkles
                    """.trimIndent(),
                "treasurer" to
                    """
                    /dashboard|Dashboard|fas fa-house
                    /conference|Videokonferenz|fas fa-video
                    /begegnung|Begegnungsräume|fas fa-church
                    # Mitgliedschaft
                      /contributions|Beiträge|fas fa-coins
                      /documents|Dokumente|fas fa-file-lines
                      /statute-qa|Fragen zur Satzung|fas fa-magnifying-glass
                      /messages|Nachrichten|fas fa-envelope
                      /mailing-lists|Mailinglisten|fas fa-envelopes-bulk
                      /carpool|Mitfahrzentrale|fas fa-car-side
                      /articles|Artikel|fas fa-newspaper
                      /donate|Spenden|fas fa-hand-holding-heart
                      /dsgvo-rights|Meine Daten|fas fa-shield-halved
                      /travel-expenses|Reisekosten|fas fa-car
                      /volunteer-allowances|Ehrenamtspauschalen|fas fa-hands-helping
                      /my-volunteer-shifts|Helferschichten|fas fa-calendar-check
                      /my-events|Veranstaltungen|fas fa-ticket
                    # Selbstverwaltung
                      /committees|Gremien|fas fa-people-group
                      /meetings|Sitzungen|fas fa-calendar-days
                      /motions|Anträge|fas fa-file-signature
                      /elections|Wahlen|fas fa-check-to-slot
                      /consensus|Konsensieren|fas fa-scale-balanced
                      /polls|Umfragen|fas fa-square-poll-horizontal
                    # Wirtschaft
                      /ltr-ledger|LTR-Konto|fas fa-wallet
                      /crowdfunding|Crowdfunding|fas fa-hand-holding-heart
                      /auction|Auktion|fas fa-gavel
                      /politicians|Politiker|fas fa-landmark
                      /tenders|Ausschreibungen|fas fa-handshake
                      /social-network|Soziales Netzwerk|fas fa-comments
                      /ai-drafts|KI-Entwürfe|fas fa-wand-magic-sparkles
                    # Finanzen
                      /ledger|Kontenplan & Journal|fas fa-book
                      /open-items|Offene Posten|fas fa-right-left
                      /financial-reports|Finanzberichte|fas fa-chart-pie
                      /compliance-reports|Gemeinnützigkeits-Berichte|fas fa-scale-balanced
                      /cost-centers|Kostenstellen|fas fa-tags
                      /donors|Spender|fas fa-heart
                      /audit-log|Prüfprotokoll|fas fa-magnifying-glass
                      /postal-mail|Postversand|fas fa-envelope-open-text
                      /price-oracle|Price-Oracle|fas fa-chart-simple
                      /sepa-mandates|SEPA-Mandate|fas fa-file-contract
                      /sepa-batches|SEPA-Lastschrift|fas fa-money-check-dollar
                      /dunning|Mahnwesen|fas fa-file-invoice-dollar
                      /payment-transactions|Zahlungseingänge|fas fa-credit-card
                      /bank-accounts|Bankkonten|fas fa-piggy-bank
                      /bank-import|Kontoauszüge|fas fa-building-columns
                      /membership-tiers|Mitgliedschaftsstufen|fas fa-layer-group
                    # Verwaltung
                      /members|Mitgliederverwaltung|fas fa-users-gear
                    """.trimIndent(),
                "board" to
                    """
                    /dashboard|Dashboard|fas fa-house
                    /conference|Videokonferenz|fas fa-video
                    /begegnung|Begegnungsräume|fas fa-church
                    # Mitgliedschaft
                      /contributions|Beiträge|fas fa-coins
                      /documents|Dokumente|fas fa-file-lines
                      /statute-qa|Fragen zur Satzung|fas fa-magnifying-glass
                      /messages|Nachrichten|fas fa-envelope
                      /mailing-lists|Mailinglisten|fas fa-envelopes-bulk
                      /carpool|Mitfahrzentrale|fas fa-car-side
                      /articles|Artikel|fas fa-newspaper
                      /donate|Spenden|fas fa-hand-holding-heart
                      /dsgvo-rights|Meine Daten|fas fa-shield-halved
                      /travel-expenses|Reisekosten|fas fa-car
                      /volunteer-allowances|Ehrenamtspauschalen|fas fa-hands-helping
                      /my-volunteer-shifts|Helferschichten|fas fa-calendar-check
                      /my-events|Veranstaltungen|fas fa-ticket
                    # Selbstverwaltung
                      /committees|Gremien|fas fa-people-group
                      /meetings|Sitzungen|fas fa-calendar-days
                      /motions|Anträge|fas fa-file-signature
                      /elections|Wahlen|fas fa-check-to-slot
                      /consensus|Konsensieren|fas fa-scale-balanced
                      /polls|Umfragen|fas fa-square-poll-horizontal
                    # Wirtschaft
                      /ltr-ledger|LTR-Konto|fas fa-wallet
                      /crowdfunding|Crowdfunding|fas fa-hand-holding-heart
                      /auction|Auktion|fas fa-gavel
                      /politicians|Politiker|fas fa-landmark
                      /tenders|Ausschreibungen|fas fa-handshake
                      /social-network|Soziales Netzwerk|fas fa-comments
                      /ai-drafts|KI-Entwürfe|fas fa-wand-magic-sparkles
                    # Finanzen
                      /ledger|Kontenplan & Journal|fas fa-book
                      /open-items|Offene Posten|fas fa-right-left
                      /financial-reports|Finanzberichte|fas fa-chart-pie
                      /compliance-reports|Gemeinnützigkeits-Berichte|fas fa-scale-balanced
                      /cost-centers|Kostenstellen|fas fa-tags
                      /donors|Spender|fas fa-heart
                      /audit-log|Prüfprotokoll|fas fa-magnifying-glass
                      /postal-mail|Postversand|fas fa-envelope-open-text
                      /price-oracle|Price-Oracle|fas fa-chart-simple
                      /sepa-mandates|SEPA-Mandate|fas fa-file-contract
                      /sepa-batches|SEPA-Lastschrift|fas fa-money-check-dollar
                      /dunning|Mahnwesen|fas fa-file-invoice-dollar
                      /payment-transactions|Zahlungseingänge|fas fa-credit-card
                      /bank-accounts|Bankkonten|fas fa-piggy-bank
                      /bank-import|Kontoauszüge|fas fa-building-columns
                      /contribution-relief|Beitragsvergünstigungen|fas fa-percent
                      /travel-expense-approvals|Reisekosten-Freigaben|fas fa-route
                      /volunteer-allowance-approvals|Ehrenamtspauschalen-Freigaben|fas fa-people-carry-box
                    # Verwaltung
                      /members|Mitgliederverwaltung|fas fa-users-gear
                      /regional-chapters|Gliederungsverwaltung|fas fa-sitemap
                      /dsgvo-compliance|DSGVO-Compliance|fas fa-shield-halved
                      /board-membership|Vorstand & Transparenzregister|fas fa-landmark-flag
                      /social-moderation|Moderation|fas fa-flag
                      /api-keys|API-Schlüssel|fas fa-key
                      /crm|Kontakte & Interessenten|fas fa-address-book
                      /events|Veranstaltungen|fas fa-calendar-days
                      /event-checkin|Veranstaltungs-Check-in|fas fa-qrcode
                      /event-rooms|Raumverwaltung|fas fa-door-open
                      /catering|Catering|fas fa-utensils
                      /event-volunteers|Helfer-Schichten|fas fa-clipboard-check
                      /anniversaries|Geburtstage & Jubiläen|fas fa-cake-candles
                      /honors|Ehrungen & Auszeichnungen|fas fa-medal
                      /families|Familienmitgliedschaften|fas fa-people-roof
                      /member-map|Mitgliederkarte|fas fa-map-location-dot
                      /member-statistics|Mitgliederentwicklung|fas fa-chart-column
                    """.trimIndent(),
                "admin" to
                    """
                    /dashboard|Dashboard|fas fa-house
                    /conference|Videokonferenz|fas fa-video
                    /begegnung|Begegnungsräume|fas fa-church
                    # Mitgliedschaft
                      /contributions|Beiträge|fas fa-coins
                      /documents|Dokumente|fas fa-file-lines
                      /statute-qa|Fragen zur Satzung|fas fa-magnifying-glass
                      /messages|Nachrichten|fas fa-envelope
                      /mailing-lists|Mailinglisten|fas fa-envelopes-bulk
                      /carpool|Mitfahrzentrale|fas fa-car-side
                      /articles|Artikel|fas fa-newspaper
                      /donate|Spenden|fas fa-hand-holding-heart
                      /dsgvo-rights|Meine Daten|fas fa-shield-halved
                      /travel-expenses|Reisekosten|fas fa-car
                      /volunteer-allowances|Ehrenamtspauschalen|fas fa-hands-helping
                      /my-volunteer-shifts|Helferschichten|fas fa-calendar-check
                      /my-events|Veranstaltungen|fas fa-ticket
                    # Selbstverwaltung
                      /committees|Gremien|fas fa-people-group
                      /meetings|Sitzungen|fas fa-calendar-days
                      /motions|Anträge|fas fa-file-signature
                      /elections|Wahlen|fas fa-check-to-slot
                      /consensus|Konsensieren|fas fa-scale-balanced
                      /polls|Umfragen|fas fa-square-poll-horizontal
                    # Wirtschaft
                      /ltr-ledger|LTR-Konto|fas fa-wallet
                      /crowdfunding|Crowdfunding|fas fa-hand-holding-heart
                      /auction|Auktion|fas fa-gavel
                      /politicians|Politiker|fas fa-landmark
                      /tenders|Ausschreibungen|fas fa-handshake
                      /social-network|Soziales Netzwerk|fas fa-comments
                      /ai-drafts|KI-Entwürfe|fas fa-wand-magic-sparkles
                    # Finanzen
                      /ledger|Kontenplan & Journal|fas fa-book
                      /open-items|Offene Posten|fas fa-right-left
                      /financial-reports|Finanzberichte|fas fa-chart-pie
                      /compliance-reports|Gemeinnützigkeits-Berichte|fas fa-scale-balanced
                      /cost-centers|Kostenstellen|fas fa-tags
                      /donors|Spender|fas fa-heart
                      /audit-log|Prüfprotokoll|fas fa-magnifying-glass
                      /postal-mail|Postversand|fas fa-envelope-open-text
                      /price-oracle|Price-Oracle|fas fa-chart-simple
                      /sepa-mandates|SEPA-Mandate|fas fa-file-contract
                      /sepa-batches|SEPA-Lastschrift|fas fa-money-check-dollar
                      /dunning|Mahnwesen|fas fa-file-invoice-dollar
                      /payment-transactions|Zahlungseingänge|fas fa-credit-card
                      /bank-accounts|Bankkonten|fas fa-piggy-bank
                      /bank-import|Kontoauszüge|fas fa-building-columns
                      /membership-tiers|Mitgliedschaftsstufen|fas fa-layer-group
                      /contribution-relief|Beitragsvergünstigungen|fas fa-percent
                      /travel-expense-approvals|Reisekosten-Freigaben|fas fa-route
                      /volunteer-allowance-approvals|Ehrenamtspauschalen-Freigaben|fas fa-people-carry-box
                    # Verwaltung
                      /members|Mitgliederverwaltung|fas fa-users-gear
                      /regional-chapters|Gliederungsverwaltung|fas fa-sitemap
                      /dsgvo-compliance|DSGVO-Compliance|fas fa-shield-halved
                      /board-membership|Vorstand & Transparenzregister|fas fa-landmark-flag
                      /social-moderation|Moderation|fas fa-flag
                      /api-keys|API-Schlüssel|fas fa-key
                      /crm|Kontakte & Interessenten|fas fa-address-book
                      /events|Veranstaltungen|fas fa-calendar-days
                      /event-checkin|Veranstaltungs-Check-in|fas fa-qrcode
                      /event-rooms|Raumverwaltung|fas fa-door-open
                      /catering|Catering|fas fa-utensils
                      /event-volunteers|Helfer-Schichten|fas fa-clipboard-check
                      /anniversaries|Geburtstage & Jubiläen|fas fa-cake-candles
                      /honors|Ehrungen & Auszeichnungen|fas fa-medal
                      /families|Familienmitgliedschaften|fas fa-people-roof
                      /member-map|Mitgliederkarte|fas fa-map-location-dot
                      /member-statistics|Mitgliederentwicklung|fas fa-chart-column
                    # System
                      /organization|Organisation|fas fa-building
                      /backup|Backup & Wiederherstellung|fas fa-database
                      /agreement-texts|Vertragstexte|fas fa-scroll
                      /conference-stream-destinations|Stream-Ziele|fas fa-satellite-dish
                      /sepa-settings|SEPA-Konfiguration|fas fa-building-columns
                      /travel-expense-settings|Reisekosten-Einstellungen|fas fa-sliders
                      /payment-gateway-settings|Zahlungs-Konfiguration|fas fa-hand-holding-dollar
                      /dunning-settings|Mahnwesen-Konfiguration|fas fa-scale-unbalanced
                      /receivable-dunning-settings|Forderungs-Mahnstufen|fas fa-bell
                      /website-integration|Website-Integration|fas fa-code
                      /mail-delivery|E-Mail-Versand|fas fa-envelopes-bulk
                    """.trimIndent(),
                "friend" to
                    """
                    /dashboard|Dashboard|fas fa-house
                    /conference|Videokonferenz|fas fa-video
                    /begegnung|Begegnungsräume|fas fa-church
                    /carpool|Mitfahrzentrale|fas fa-car-side
                    /dsgvo-rights|Meine Daten|fas fa-shield-halved
                    # Wirtschaft
                      /ltr-ledger|LTR-Konto|fas fa-wallet
                      /tenders|Ausschreibungen|fas fa-handshake
                      /social-network|Soziales Netzwerk|fas fa-comments
                      /ai-drafts|KI-Entwürfe|fas fa-wand-magic-sparkles
                    """.trimIndent(),
                "guest" to
                    """
                    /dashboard|Dashboard|fas fa-house
                    /conference|Videokonferenz|fas fa-video
                    /begegnung|Begegnungsräume|fas fa-church
                    /carpool|Mitfahrzentrale|fas fa-car-side
                    /dsgvo-rights|Meine Daten|fas fa-shield-halved
                    """.trimIndent(),
            )
    }
}
