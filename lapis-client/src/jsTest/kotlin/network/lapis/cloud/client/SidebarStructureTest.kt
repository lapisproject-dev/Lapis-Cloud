package network.lapis.cloud.client

import io.kvision.html.Button
import io.kvision.html.Link
import io.kvision.navbar.Nav
import io.kvision.panel.SimplePanel
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regressionstest für den V1.4.8-Sidebar-Layout-Bug: `buildSidebar()` fügte seine acht
 * Top-Level-Einträge (2 flache Links + 6 Gruppen) bis V1.4.7 DIREKT in `body` (die Offcanvas)
 * ein. Bootstrap setzt `.offcanvas-lg .offcanvas-body { display: flex }` ab 992px (Navbar-
 * Muster, bootstrap.css 5.3.8 Z. 6515, kein `flex-direction` gesetzt -> row) -- alle Kinder
 * lagen dadurch in EINER horizontalen Zeile, und weil `.lapis-sidebar` selbst
 * `overflow-y: auto` setzt (was `overflow-x` per Spezifikation ebenfalls auf `auto` zwingt),
 * wurde alles ab dem zweiten Link innerhalb der 264px-breiten Spalte unsichtbar weggescrollt --
 * live gemeldet von der pzb.parteidervernunft.de-Produktivinstanz (ADMIN-Account sah nur
 * "Dashboard"/"Videokonferenz" nebeneinander, keine der sechs Rollen-Gruppen).
 *
 * Der Fix wickelt ALLE acht Einträge in einen eigenen `nav.lapis-sidebar-nav`-Wrapper
 * (`display:flex; flex-direction:column`) -- dieser Test prüft, dass `body` genau EIN
 * direktes Kind hat, dieses die Klasse `lapis-sidebar-nav` trägt, und DARIN die erwarteten
 * Einträge in der richtigen Reihenfolge auftauchen.
 *
 * Bewusst NICHT auf eine harte Gesamt-Kinderzahl geprüft: `sidebarGroup()` (siehe dortiges
 * KDoc) fügt pro Gruppe ZWEI Geschwister ein (Header-`Button` + Body-`Nav`), nicht eins --
 * "acht Einträge" ist eine bewusst korrigierte Vereinfachung. Stattdessen wird nach Widget-Typ
 * gefiltert: genau 2 `Link`s (Dashboard/Videokonferenz, in dieser Reihenfolge, an den ersten
 * beiden Positionen) und genau 6 `Button`s mit der Klasse `lapis-sidebar-group-header` (die
 * sechs Gruppenköpfe, in Rollen-Deklarationsreihenfolge) -- das ist genau das, was ein
 * Regressionstest gegen DIESEN Bug tatsächlich beweisen muss: alle acht Einträge existieren im
 * Widget-Baum UND liegen innerhalb des einen vertikalen Wrappers, nicht direkt in `body`.
 *
 * DOM-frei: `SimplePanel()` wird hier standalone (ohne `root()`/`Offcanvas`/Mounting)
 * konstruiert und direkt als `body`-Parameter an [buildSidebar] übergeben -- kein
 * Rendering-Harness nötig, [SimplePanel.getChildren] arbeitet rein auf der In-Memory-Widget-Liste
 * (verifiziert gegen den gepinnten kvision-9.6.0-Quellcheckout, `SimplePanel.kt`).
 * [io.kvision.core.Widget.hasCssClass] dagegen hat eine eigene Stolperfalle -- siehe der
 * Kommentar direkt an der `nav`-Assertion unten -- korrigiert die pessimistischere
 * Annahme in [SidebarViewportTest]s KDoc ("no rendering harness exists in this module"), die für
 * eine tatsächliche DOM-Renderprüfung weiterhin zutrifft, aber nicht für diese reine
 * In-Memory-Baumstruktur.
 *
 * [AppState] ist ein Test-übergreifendes Singleton (Karma+ChromeHeadless teilt eine
 * Browser-Instanz über alle Testklassen) -- `@BeforeTest`/`@AfterTest` setzen/löschen
 * `AppState.session` nach demselben Muster wie [AppStateTest].
 */
class SidebarStructureTest {
    private val adminSession =
        SessionInfoDto(
            memberId = "admin-1",
            displayName = "Admin",
            role = AccountRole.ADMIN,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            status = MemberStatus.ACTIVE,
        )

    @BeforeTest
    fun setUp() {
        AppState.setSession(adminSession)
    }

    @AfterTest
    fun tearDown() {
        AppState.onSessionChange = {}
        AppState.setSession(null)
    }

    @Test
    fun adminSession_wrapsEverythingInOneVerticalContainer() {
        val body = SimplePanel()
        buildSidebar(body, adminSession, activeRoute = null) {}

        val topLevel = body.getChildren()
        assertEquals(1, topLevel.size, "body sollte genau EIN direktes Kind haben (den Wrapper)")
        val nav = topLevel.single() as Nav
        // KVision-Stolperfalle (verifiziert gegen den gepinnten kvision-9.6.0-Quellcheckout,
        // `Widget.kt`): `hasCssClass` liest NUR das lazy, imperativ befuellte `classes`-Set, NIE
        // den Konstruktor-`className` direkt -- `classes` bleibt `null`, bis irgendwann
        // `addCssClass`/`removeCssClass` aufgerufen wird (das seedet `classes` dann EINMALIG aus
        // `className`). `render()`/`buildClassSet()` faellt zur RENDER-Zeit korrekt auf
        // `className` zurueck, solange `classes == null` ist -- die echte DOM-Ausgabe ist davon
        // also nicht betroffen (live gegen `jsBrowserDevelopmentRun` verifiziert, V1.4.8 plan
        // Abschnitt 1: `document.querySelector('.lapis-sidebar-nav')` traf zu). Fuer `nav` selbst
        // ruft aber nichts in `buildSidebar` je `addCssClass`/`removeCssClass` auf (anders als bei
        // den Gruppen-Headern, die `NavHighlight.apply()` ueber ihr `toggle`-Widget indirekt
        // treffen) -- ein harmloser No-op-Probe-Zyklus erzwingt dieselbe lazy Migration, bevor wir
        // `hasCssClass` befragen. `nav` ist hier nie gemountet (`getRoot()` liefert `null` bis zur
        // Wurzel), `refresh()`s `getRoot()?.reRender()` ist also ein sicherer No-op.
        nav.addCssClass("sidebar-structure-test-probe")
        nav.removeCssClass("sidebar-structure-test-probe")
        assertTrue(nav.hasCssClass("lapis-sidebar-nav"))

        val navChildren = nav.getChildren()
        val links = navChildren.filterIsInstance<Link>()
        val groupHeaders = navChildren.filterIsInstance<Button>().filter { it.hasCssClass("lapis-sidebar-group-header") }

        assertEquals(2, links.size)
        assertEquals("#${Routes.DASHBOARD}", links[0].url)
        assertEquals("#${Routes.CONFERENCE}", links[1].url)

        assertEquals(6, groupHeaders.size)
        // `tr(key)` (siehe `Sidebar.kt`'s `sidebarGroup`-Aufrufe) liefert laut kvision-9.6.0
        // `I18nManager.tr()` NICHT den übersetzten Text, sondern `key` mit einem
        // "###KvI18nS###"-Präfix markiert für DYNAMISCHE Übersetzung -- die eigentliche
        // Auflösung passiert erst beim Rendern (widerlegt live an diesem Testlauf die
        // pessimistischere Annahme in `SidebarStructureTest`s eigenem Umsetzungsplan-Abschnitt,
        // der von einer sofort aufgelösten Rückgabe ausging). `Button.text` liefert also den
        // rohen markierten String zurück, solange nie gerendert wurde -- Präfix hier abstreifen,
        // statt einen zweiten, unrenderten Übersetzungspfad nachzubauen.
        assertEquals(
            listOf("Mitgliedschaft", "Selbstverwaltung", "Wirtschaft", "Finanzen", "Verwaltung", "System"),
            groupHeaders.map { it.text.removePrefix("###KvI18nS###") },
        )

        // Die beiden Links müssen VOR allen Gruppenköpfen liegen (Positions-Reihenfolge im
        // realen Baum, nicht nur "beide Typen existieren irgendwo").
        val firstGroupHeaderIndex = navChildren.indexOfFirst { it === groupHeaders.first() }
        val lastLinkIndex = navChildren.indexOfFirst { it === links.last() }
        assertTrue(lastLinkIndex < firstGroupHeaderIndex)
    }

    @Test
    fun financeGroup_showsContributionReliefEntry_onlyForBoardAndAdmin_notTreasurer() {
        // Review-Fund 2026-09-12 (Welle V1.4.10.1 "Beitragsvergünstigungen: Bedienoberfläche"):
        // regression coverage for the FINANCE group's own narrower sub-gate -- BOARD/ADMIN see the
        // new `/contribution-relief` entry, TREASURER does not, even though TREASURER sees every
        // OTHER entry in the same FINANCE group (mirrors
        // `IContributionReliefService.listReliefRequests`'s own role check, see `Sidebar.kt`'s
        // comment directly above the `sidebarLink(Routes.CONTRIBUTION_RELIEF, ...)` call).
        fun hasContributionReliefLink(session: SessionInfoDto): Boolean {
            AppState.setSession(session)
            val body = SimplePanel()
            buildSidebar(body, session, activeRoute = null) {}
            val nav = body.getChildren().single() as Nav
            return nav
                .getChildren()
                .filterIsInstance<Nav>()
                .flatMap { it.getChildren() }
                .filterIsInstance<Link>()
                .any { it.url == "#${Routes.CONTRIBUTION_RELIEF}" }
        }

        assertFalse(
            hasContributionReliefLink(adminSession.copy(role = AccountRole.TREASURER)),
            "TREASURER must NOT see the Beitragsvergünstigungen entry",
        )
        assertTrue(
            hasContributionReliefLink(adminSession.copy(role = AccountRole.BOARD)),
            "BOARD must see the Beitragsvergünstigungen entry",
        )
        assertTrue(
            hasContributionReliefLink(adminSession.copy(role = AccountRole.ADMIN)),
            "ADMIN must see the Beitragsvergünstigungen entry",
        )
    }

    @Test
    fun anonymousClearSidebar_stillLeavesBodyEmpty() {
        // Regressions-Abgrenzung: clearSidebar() bekommt KEINEN Wrapper (kein Inhalt), damit
        // .lapis-sidebar-empty weiterhin korrekt "wirklich nichts da" bedeutet.
        val body = SimplePanel()
        buildSidebar(body, adminSession, activeRoute = null) {}
        clearSidebar(body)
        assertTrue(body.getChildren().isEmpty())
        assertTrue(body.hasCssClass("lapis-sidebar-empty"))
    }
}
