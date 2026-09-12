package network.lapis.cloud.client

import io.kvision.core.Widget
import io.kvision.html.ButtonStyle
import io.kvision.html.Link
import io.kvision.html.button
import io.kvision.html.link
import io.kvision.i18n.gettext
import io.kvision.i18n.tr
import io.kvision.navbar.Nav
import io.kvision.panel.SimplePanel
import kotlinx.browser.localStorage
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ContributionReliefStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IContributionReliefService
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * Vertical-Sidebar-Umbau (2026-09-08) -- stable, translation-independent IDs of the six sidebar
 * groups. Persistence (see [SidebarGroupStorage]) is keyed on [storageKey], NEVER on the
 * translated group label -- a `localStorage` entry must survive a language switch unchanged
 * (Design-Team-Auflage, siehe Plan Abschnitt 8 Stolperfalle 8).
 */
enum class SidebarGroupId(
    val storageKey: String,
) {
    MEMBERSHIP("membership"),
    SELF_GOVERNANCE("self-governance"),
    ECONOMY("economy"),
    FINANCE("finance"),
    ADMINISTRATION("administration"),
    SYSTEM("system"),
}

/**
 * Pure (DOM-free) serialization of the open/closed state of the sidebar groups for `localStorage`
 * -- same posture as `App.kt`'s `LANGUAGE_STORAGE_KEY` handling (`initialLanguage`/`setLanguage`).
 * [parse]/[serialize] are DOM-free and unit-tested directly (see `SidebarGroupStorageTest`);
 * [load]/[save] are the only impure (jsMain-only, `localStorage`-touching) entry points.
 */
object SidebarGroupStorage {
    const val STORAGE_KEY = "lapis-cloud-sidebar-groups"

    /**
     * Comma-separated [SidebarGroupId.storageKey] tokens -> the matching set. Unknown tokens are
     * silently ignored (forward/backward compatible with a future group rename/removal); `null`,
     * blank, or an all-unknown-tokens input all yield an empty set (= every group starts closed).
     */
    fun parse(raw: String?): Set<SidebarGroupId> {
        if (raw.isNullOrBlank()) return emptySet()
        val tokens = raw.split(",").map { it.trim() }.filterTo(mutableSetOf()) { it.isNotEmpty() }
        return SidebarGroupId.entries.filterTo(mutableSetOf()) { it.storageKey in tokens }
    }

    /**
     * Enum-declaration-order output (NOT the input [ids] set's own iteration order) -- keeps
     * `serialize(parse(x))` deterministic regardless of how [ids] was built, see
     * `SidebarGroupStorageTest`'s round-trip coverage.
     */
    fun serialize(ids: Set<SidebarGroupId>): String = SidebarGroupId.entries.filter { it in ids }.joinToString(",") { it.storageKey }

    /** Reads [STORAGE_KEY] from `localStorage`. */
    fun load(): Set<SidebarGroupId> = parse(localStorage[STORAGE_KEY])

    /** Persists [ids] to `localStorage` under [STORAGE_KEY]. */
    fun save(ids: Set<SidebarGroupId>) {
        localStorage[STORAGE_KEY] = serialize(ids)
    }
}

/**
 * Bootstrap's own `lg` breakpoint in pixels -- the same value [io.kvision.offcanvas.OffResponsiveType.RESPONSIVELG]
 * (`App.kt`'s `shell.offcanvas(...)` call) encodes as the `offcanvas-lg` CSS class, and the same
 * value theme.css's own `@media (min-width: 992px)` `!important` override hardcodes (see that
 * rule's own comment). Named here, once, so [shouldMountSidebarEagerly] and that CSS rule can
 * never silently drift apart.
 */
const val SIDEBAR_DESKTOP_BREAKPOINT_PX = 992

/**
 * Review-Fund 2026-09-08 (Finding 2, KRITISCH) -- pure, DOM-free half of the fix, unit-tested
 * directly (see `SidebarViewportTest`) the same way [SidebarGroupStorage.parse]/[serialize] and
 * [sidebarGroupForRoute] already are (no DOM/rendering harness exists in this module, see those
 * tests' own KDoc for the precedent).
 *
 * `Offcanvas` starts KVision-invisible (its own `init { hide() }`), and
 * `SimplePanel.childrenVNodes()` (verified against pinned kvision 9.6.0 `SimplePanel.kt`)
 * completely EXCLUDES an invisible child from the rendered DOM -- not merely CSS-hidden, genuinely
 * never inserted. theme.css's own `@media (min-width: 992px)` `!important` override therefore has
 * nothing to act on until something actually mounts the sidebar first -- `App.kt`'s `refreshShell`
 * calls `sidebar.show()` whenever this returns `true`, on EVERY refresh (not just once at boot),
 * because `clearSidebar`'s own `sidebar.hide()` (anonymous session) would otherwise silently
 * re-unmount it on a desktop viewport too. Below the breakpoint this is never consulted for
 * mounting/unmounting -- the mobile toggle button owns that entirely via `Offcanvas.toggle()`'s
 * own normal open/close cycle, exactly as the library intends.
 */
fun shouldMountSidebarEagerly(viewportWidthPx: Int): Boolean = viewportWidthPx >= SIDEBAR_DESKTOP_BREAKPOINT_PX

/**
 * Route -> owning sidebar group. Single source of truth [buildSidebar] itself reads from (via
 * [sidebarGroupHeader] call sites below) -- kept here, once, so "which routes trigger forcing a
 * group open" ([sidebarGroupForRoute]) can never drift from "which routes actually render inside
 * that group" the way two independently-maintained lists could.
 */
private val GROUP_ROUTES: Map<SidebarGroupId, List<String>> =
    mapOf(
        SidebarGroupId.MEMBERSHIP to
            listOf(Routes.CONTRIBUTIONS, Routes.DOCUMENTS, Routes.COMMUNICATION, Routes.DONATE, Routes.DSGVO_RIGHTS),
        SidebarGroupId.SELF_GOVERNANCE to listOf(Routes.COMMITTEES, Routes.MEETINGS, Routes.MOTIONS),
        SidebarGroupId.ECONOMY to
            listOf(Routes.LTR_LEDGER, Routes.CROWDFUNDING, Routes.AUCTION, Routes.POLITICIANS, Routes.SOCIAL_NETWORK),
        SidebarGroupId.FINANCE to
            listOf(
                Routes.LEDGER,
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
                Routes.BANK_IMPORT,
                Routes.CONTRIBUTION_RELIEF,
            ),
        SidebarGroupId.ADMINISTRATION to
            listOf(
                Routes.MEMBERS,
                Routes.DSGVO_COMPLIANCE,
                Routes.BOARD_MEMBERSHIP,
                Routes.SOCIAL_MODERATION,
                Routes.API_KEYS,
                Routes.CRM,
                Routes.EVENT_CHECKIN,
                Routes.MEMBER_ANNIVERSARIES,
                Routes.MEMBER_HONORS,
                Routes.MEMBER_FAMILIES,
            ),
        SidebarGroupId.SYSTEM to
            listOf(
                Routes.BACKUP,
                Routes.CONFERENCE_STREAM_DESTINATIONS,
                Routes.SEPA_SETTINGS,
                Routes.PAYMENT_GATEWAY_SETTINGS,
                Routes.DUNNING_SETTINGS,
                Routes.EMBED_INTEGRATION,
            ),
    )

/**
 * Which sidebar group [route] belongs to, `null` for Dashboard/Videokonferenz (flat top-level
 * links) or any route not in [GROUP_ROUTES]. Reuses [NavRouteMatch.isActive]'s own slash-suffixed
 * prefix rule (not a second, independently-written `startsWith` check) so a route that would
 * highlight a sidebar link as active is *exactly* the same route that forces that link's enclosing
 * group open -- see `SidebarGroupStorageTest` for the `/social-network/post/:id` boundary case.
 */
fun sidebarGroupForRoute(route: String?): SidebarGroupId? {
    if (route == null) return null
    // Welle V1.4.5.1.1: `App.kt#currentHashRoute()` liefert den ROHEN Hash inkl. Query
    // ("/bank-import?import=abc"). `NavRouteMatch.isActive` matcht darauf nicht -- ohne diesen
    // Schnitt klappt die Gruppe beim Deep-Link-Seitenaufruf nicht auf. Betraf latent bereits
    // /member-finances?member=, /honors?member=, /families?family=, /payment-return?session=.
    val path = route.substringBefore('?')
    return GROUP_ROUTES.entries.firstOrNull { (_, routes) -> routes.any { NavRouteMatch.isActive(path, it) } }?.key
}

/**
 * Clears the sidebar for an anonymous session (no [SessionInfoDto] to build from) -- also drops
 * every [NavHighlight] registration, mirroring [buildSidebar]'s own `reset()` call. Without this,
 * a registration left over from a just-ended session would still sit in [NavHighlight]'s registry
 * (pointing at now-removed link components) and receive a highlight update from the very next
 * anonymous-route navigation (`Routing.kt`'s `show()` calls `NavHighlight.setActiveRoute` on
 * EVERY route, LOGIN/REGISTER/... included).
 *
 * Review-Fund 2026-09-08 (Finding 2, HOCH): also marks [body] `.lapis-sidebar-empty` -- see
 * theme.css's own comment on the `:not(.lapis-sidebar-empty)` exemption this class exists for.
 * Without it, theme.css's `>=992px` `!important` override (needed to keep a POPULATED sidebar
 * visible on desktop, see that rule's own KDoc) kept this now-empty sidebar visible too: a bare,
 * bordered, ~264px-wide column next to e.g. the login form on any desktop viewport.
 */
fun clearSidebar(body: SimplePanel) {
    body.removeAll()
    body.addCssClass("lapis-sidebar-empty")
    NavHighlight.reset()
}

/**
 * Vertical-Sidebar-Umbau (2026-09-08) -- builds the sidebar's contents into [body] for [session].
 * Role-gating below is 1:1 copied from the pre-umbau `App.kt#refreshNavbar` dropdown structure
 * (same [NavVisibility] predicates, same [AppState.hasRole] checks, same route/label/icon triples,
 * same order) -- only the container shape changed (collapsible sidebar sections instead of
 * navbar dropdowns), see the plan's own group/route table for the verified correspondence.
 *
 * [activeRoute] additionally forces that route's own [sidebarGroupForRoute] group open for this
 * build (on top of whatever [SidebarGroupStorage.load] already had open) -- e.g. a fresh page load
 * on a deep link like `/audit-log` opens "Finanzen" immediately rather than requiring a manual
 * click. That forced-open state IS persisted on the next manual toggle (the whole current
 * open-set is written back as one unit -- no separate "force" vs. "user choice" bookkeeping, see
 * plan Abschnitt 11 point 2).
 *
 * [onNavigate] runs after every link click (both top-level and inside a group) -- the caller wires
 * this to close the offcanvas on mobile, see `App.kt`'s call site.
 */
fun buildSidebar(
    body: SimplePanel,
    session: SessionInfoDto,
    activeRoute: String?,
    onNavigate: () -> Unit,
) {
    body.removeAll()
    // Review-Fund 2026-09-08 (Finding 2, HOCH): counterpart to `clearSidebar`'s own
    // `addCssClass("lapis-sidebar-empty")` -- this build IS populating the sidebar, so drop the
    // marker again (a no-op if it was never set, e.g. the very first build of a session). See
    // theme.css's `:not(.lapis-sidebar-empty)` comment and `clearSidebar`'s own KDoc.
    body.removeCssClass("lapis-sidebar-empty")
    NavHighlight.reset()

    // V1.4.8 Sidebar-Layout-Fix (Design-Team Atkinson/Kay, Jobs' Review): EIN eigener, vertikal
    // stapelnder Container statt Bootstraps privatem `.offcanvas-body` direkt zu befüllen. Bootstrap
    // setzt `.offcanvas-lg .offcanvas-body { display: flex }` ab 992px (Navbar-Muster, bootstrap.css
    // 5.3.8 Z. 6515) -- OHNE eigenes `flex-direction` (Default `row`). Weil `.lapis-sidebar` selbst
    // `overflow-y: auto` setzt (theme.css, das per CSS-Spezifikation `overflow-x` ebenfalls auf
    // `auto` zwingt statt `visible` zu belassen), liefen die bislang direkt in `body` eingefügten
    // Top-Level-Einträge (2 flache Links + 6 Gruppen, siehe [GROUP_ROUTES]) in EINER horizontalen
    // Zeile -- alles ab dem zweiten Link wurde innerhalb der 264px-breiten Spalte unsichtbar
    // weggescrollt (Live-Fund pzb.parteidervernunft.de: ADMIN-Account sah nur "Dashboard"/
    // "Videokonferenz" nebeneinander, keine der sechs Rollen-Gruppen). Wir besitzen unser eigenes
    // Layout vollständig (`.lapis-sidebar-nav`, theme.css), statt gegen Bootstraps `.offcanvas-body`-
    // Interna mit einer dritten `!important`-Schicht anzuschreiben. Siehe `SidebarStructureTest` für
    // die Regressionsabsicherung.
    val nav = Nav(className = "flex-column lapis-sidebar-nav")
    body.add(nav)

    val openGroups = SidebarGroupStorage.load().toMutableSet()
    sidebarGroupForRoute(activeRoute)?.let { openGroups += it }

    // Review-Fund 2026-09-08 (Finding 4, MINOR/UX): maps a group's header `toggle` widget to a
    // closure that force-opens that specific group -- populated by `sidebarGroup` below, looked up
    // by `sidebarLink` and handed to `NavHighlight.register` so `NavHighlight.apply()` can expand a
    // link's enclosing group whenever ROUTING (not a sidebar click) makes that link the active one,
    // e.g. a `DashboardScreen.kt` `navTile` click straight into a route inside a currently-collapsed
    // group -- previously only `sidebarGroupForRoute(activeRoute)` above did this, and only at
    // `buildSidebar`-rebuild time (session/language change), never for a plain in-session
    // navigation. Keyed by `Widget` identity (the header `Button`, unique per group per
    // `buildSidebar` call) rather than `SidebarGroupId`, so `sidebarLink`'s existing `toggle`
    // parameter is the only thing that needs to change -- no second, independently-threaded id to
    // keep in sync with it.
    val groupOpeners = mutableMapOf<Widget, () -> Unit>()

    // Welle V1.4.10.1 "Beitragsvergünstigungen: Bedienoberfläche" -- returns the [Link] widget
    // (previously `Unit`) so the FINANCE group's new relief entry can update its own label once the
    // open-request count has loaded (see [reliefSidebarLabel]'s call site below). Every existing
    // call site ignores the return value, so this is source-compatible.
    fun SimplePanel.sidebarLink(
        route: String,
        label: String,
        icon: String,
        toggle: Widget? = null,
    ): Link {
        val link = link(label, url = "#$route", icon = icon, className = "nav-link")
        link.onClick { onNavigate() }
        // Named arguments (CLAUDE.md "Kotlin-Code-Konvention" -- Named Parameters PFLICHT):
        // `NavHighlight.register` has more than one value parameter, and `lapis-client`'s own
        // `RequireNamedArguments` detekt gate is silently skipped for this JS-only module (see this
        // file's build.gradle.kts header comment) -- named by review discipline instead, since there
        // is no automated enforcement here.
        NavHighlight.register(route = route, link = link, toggle = toggle, openGroup = toggle?.let { groupOpeners[it] })
        return link
    }

    /**
     * A collapsible section: a hand-built header button (no KVision accordion widget exists to
     * reuse, see plan Abschnitt 2 "Toggle-Header-Implementierung") + a nested [Nav] list of
     * [sidebarLink]s. A real `<button>` (not a hand-rolled `role="button"` div) so Enter/Space
     * activation is native, free accessibility -- same posture as `ConferenceScreen.kt`'s
     * `rosterToggleButton`/`chatToggleButton` toggle buttons this mirrors (`.active` class +
     * `aria-expanded`, not `aria-pressed` -- this toggles a section's OWN visibility, not a
     * feature flag, so `aria-expanded` is the semantically correct ARIA property here).
     */
    fun SimplePanel.sidebarGroup(
        id: SidebarGroupId,
        label: String,
        icon: String,
        content: SimplePanel.(toggle: Widget) -> Unit,
    ) {
        val isOpen = id in openGroups
        val header =
            button(
                label,
                icon = icon,
                style = ButtonStyle.LINK,
                className = "lapis-sidebar-group-header w-100 text-start d-flex align-items-center gap-2",
            )
        header.setAttribute("aria-expanded", isOpen.toString())
        val groupBody = Nav(className = "flex-column lapis-sidebar-group-body")
        add(groupBody)

        fun setOpen(nowOpen: Boolean) {
            if (nowOpen) groupBody.show() else groupBody.hide()
            header.setAttribute("aria-expanded", nowOpen.toString())
            if (nowOpen) openGroups += id else openGroups -= id
            SidebarGroupStorage.save(openGroups)
        }
        // Registered BEFORE `content(header)` runs below -- `sidebarLink` calls inside `content`
        // look this map up immediately (via the `toggle` parameter they're passed), so the entry
        // must already exist by the time the first one of them fires.
        groupOpeners[header] = { if (!groupBody.visible) setOpen(true) }

        groupBody.content(header)
        if (!isOpen) groupBody.hide()
        header.onClick {
            setOpen(!groupBody.visible)
        }
    }

    // Zwei höchstfrequente Ziele bleiben flach, wie im vorherigen `leftNav` -- siehe
    // `App.kt`-Git-Historie, UI/UX-Design-Team-Review 2026-08-14 (Norman/Raskin).
    nav.sidebarLink(Routes.DASHBOARD, tr("Dashboard"), "fas fa-house")
    nav.sidebarLink(Routes.CONFERENCE, tr("Videokonferenz"), "fas fa-video")

    if (NavVisibility.showsMembershipSection(session.status)) {
        nav.sidebarGroup(SidebarGroupId.MEMBERSHIP, tr("Mitgliedschaft"), "fas fa-id-card") { toggle ->
            sidebarLink(Routes.CONTRIBUTIONS, tr("Beiträge"), "fas fa-coins", toggle)
            sidebarLink(Routes.DOCUMENTS, tr("Dokumente"), "fas fa-file-lines", toggle)
            sidebarLink(Routes.COMMUNICATION, tr("Kommunikation"), "fas fa-envelope", toggle)
            sidebarLink(Routes.DONATE, tr("Spenden"), "fas fa-hand-holding-heart", toggle)
            sidebarLink(Routes.DSGVO_RIGHTS, tr("Meine Daten"), "fas fa-shield-halved", toggle)
        }
    } else if (NavVisibility.showsDsgvoRights(session.status)) {
        // Welle V1.1.4: ein FRIEND hat kein volles "Mitgliedschaft"-Dropdown, braucht aber
        // trotzdem einen erreichbaren Betroffenenrechte-Einstieg -- siehe Routes.DSGVO_RIGHTS KDoc.
        nav.sidebarLink(Routes.DSGVO_RIGHTS, tr("Meine Daten"), "fas fa-shield-halved")
    }

    if (NavVisibility.showsSelfGovernance(session.status)) {
        nav.sidebarGroup(SidebarGroupId.SELF_GOVERNANCE, tr("Selbstverwaltung"), "fas fa-people-group") { toggle ->
            sidebarLink(Routes.COMMITTEES, tr("Gremien"), "fas fa-people-group", toggle)
            sidebarLink(Routes.MEETINGS, tr("Sitzungen"), "fas fa-calendar-days", toggle)
            sidebarLink(Routes.MOTIONS, tr("Anträge"), "fas fa-file-signature", toggle)
        }
    }

    if (NavVisibility.showsEconomySection(session.status)) {
        nav.sidebarGroup(SidebarGroupId.ECONOMY, tr("Wirtschaft"), "fas fa-coins") { toggle ->
            if (NavVisibility.showsLtrLedger(session.status)) {
                sidebarLink(Routes.LTR_LEDGER, tr("LTR-Konto"), "fas fa-wallet", toggle)
            }
            if (NavVisibility.showsMemberOnlyEconomy(session.status)) {
                sidebarLink(Routes.CROWDFUNDING, tr("Crowdfunding"), "fas fa-hand-holding-heart", toggle)
                sidebarLink(Routes.AUCTION, tr("Auktion"), "fas fa-gavel", toggle)
                sidebarLink(Routes.POLITICIANS, tr("Politiker"), "fas fa-landmark", toggle)
            }
            if (NavVisibility.showsSocialNetwork(session.status)) {
                sidebarLink(Routes.SOCIAL_NETWORK, tr("Soziales Netzwerk"), "fas fa-comments", toggle)
            }
        }
    }

    // Accounting UI wave, design decision D15 -- TREASURER/BOARD/ADMIN, same tier the LEDGER route
    // itself requires.
    if (AppState.hasRole(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)) {
        nav.sidebarGroup(SidebarGroupId.FINANCE, tr("Finanzen"), "fas fa-chart-line") { toggle ->
            sidebarLink(Routes.LEDGER, tr("Kontenplan & Journal"), "fas fa-book", toggle)
            sidebarLink(Routes.FINANCIAL_REPORTS, tr("Finanzberichte"), "fas fa-chart-pie", toggle)
            sidebarLink(Routes.COMPLIANCE_REPORTS, tr("Gemeinnützigkeits-Berichte"), "fas fa-scale-balanced", toggle)
            sidebarLink(Routes.COST_CENTERS, tr("Kostenstellen"), "fas fa-tags", toggle)
            sidebarLink(Routes.DONORS, tr("Spender"), "fas fa-heart", toggle)
            sidebarLink(Routes.AUDIT_LOG, tr("Prüfprotokoll"), "fas fa-magnifying-glass", toggle)
            sidebarLink(Routes.POSTAL_MAIL, tr("Postversand"), "fas fa-envelope-open-text", toggle)
            sidebarLink(Routes.PRICE_ORACLE, tr("Price-Oracle"), "fas fa-chart-simple", toggle)
            sidebarLink(Routes.SEPA_MANDATES, tr("SEPA-Mandate"), "fas fa-file-contract", toggle)
            sidebarLink(Routes.SEPA_BATCHES, tr("SEPA-Lastschrift"), "fas fa-money-check-dollar", toggle)
            sidebarLink(Routes.DUNNING_CASES, tr("Mahnwesen"), "fas fa-file-invoice-dollar", toggle)
            sidebarLink(Routes.PAYMENT_TRANSACTIONS, tr("Zahlungseingänge"), "fas fa-credit-card", toggle)
            sidebarLink(Routes.BANK_IMPORT, tr("Kontoauszüge"), "fas fa-building-columns", toggle)
            // Welle V1.4.10.1: enger gegatet als der Rest der FINANCE-Gruppe (BOARD/ADMIN, NICHT
            // TREASURER) -- verifiziert gegen `IContributionReliefService.listReliefRequests`s
            // eigenen Rollen-Check, siehe `Routes.CONTRIBUTION_RELIEF` KDoc. Gleiches
            // "engerer Unter-Gate innerhalb der äußeren Rollenprüfung"-Muster wie ADMINISTRATION
            // weiter unten.
            if (AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)) {
                // Icon-Kollisions-Korrektur (Plan Abschnitt 1): `fas fa-hand-holding-dollar` ist
                // bereits an PAYMENT_GATEWAY_SETTINGS vergeben (siehe SYSTEM-Gruppe unten) --
                // `fas fa-percent` ist frei (verifiziert per grep) und semantisch passend
                // (Ermäßigung/Beitragssatz-Änderung).
                val reliefLink = sidebarLink(Routes.CONTRIBUTION_RELIEF, reliefSidebarLabel(null), "fas fa-percent", toggle)
                // Sidebar-Zähler (Jobs/Forstall-Auflage): EIN `listReliefRequests(status =
                // REQUESTED)`-Aufruf, NUR in diesem BOARD/ADMIN-Zweig (nie für TREASURER, der 403
                // bekäme). `runCatching`, NICHT `guarded{}` -- ein Fehlschlag hier darf niemals einen
                // Toast beim reinen Sidebar-Aufbau auslösen (bewusste Abweichung vom `guarded{}`-
                // Default, siehe CLAUDE.md-Plan Abschnitt 2.5). Das Label wird NACHTRÄGLICH per
                // Coroutine überschrieben (`buildSidebar` selbst ist nicht `suspend`) -- der Link
                // rendert zunächst ohne Zahl, genau wie jeder andere Eintrag.
                AppScope.launch {
                    val openCount =
                        runCatching {
                            rpcService<IContributionReliefService>().listReliefRequests(status = ContributionReliefStatus.REQUESTED).size
                        }.getOrNull()
                    if (openCount != null && openCount > 0) {
                        reliefLink.label = reliefSidebarLabel(openCount)
                    }
                }
            }
        }
    }

    // TREASURER/BOARD/ADMIN-Tier fuer den Gruppen-Header selbst, aber NICHT fuer jeden einzelnen
    // Eintrag darin -- siehe `Routes.MEMBERS` KDoc (Welle V1.4.4.4: auch TREASURER).
    if (AppState.hasRole(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)) {
        nav.sidebarGroup(SidebarGroupId.ADMINISTRATION, tr("Verwaltung"), "fas fa-user-gear") { toggle ->
            sidebarLink(Routes.MEMBERS, tr("Mitgliederverwaltung"), "fas fa-users-gear", toggle)
            if (AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)) {
                sidebarLink(Routes.DSGVO_COMPLIANCE, tr("DSGVO-Compliance"), "fas fa-shield-halved", toggle)
                sidebarLink(
                    Routes.BOARD_MEMBERSHIP,
                    tr("Vorstand & Transparenzregister"),
                    "fas fa-landmark-flag",
                    toggle,
                )
                sidebarLink(Routes.SOCIAL_MODERATION, tr("Moderation"), "fas fa-flag", toggle)
                sidebarLink(Routes.API_KEYS, tr("API-Schlüssel"), "fas fa-key", toggle)
                sidebarLink(Routes.CRM, tr("Kontakte & Interessenten"), "fas fa-address-book", toggle)
                sidebarLink(Routes.EVENT_CHECKIN, tr("Veranstaltungs-Check-in"), "fas fa-qrcode", toggle)
                sidebarLink(Routes.MEMBER_ANNIVERSARIES, tr("Geburtstage & Jubiläen"), "fas fa-cake-candles", toggle)
                sidebarLink(Routes.MEMBER_HONORS, tr("Ehrungen & Auszeichnungen"), "fas fa-medal", toggle)
                sidebarLink(Routes.MEMBER_FAMILIES, tr("Familienmitgliedschaften"), "fas fa-people-roof", toggle)
            }
        }
    }

    if (AppState.hasRole(AccountRole.ADMIN)) {
        nav.sidebarGroup(SidebarGroupId.SYSTEM, tr("System"), "fas fa-server") { toggle ->
            sidebarLink(Routes.BACKUP, tr("Backup & Wiederherstellung"), "fas fa-database", toggle)
            sidebarLink(Routes.CONFERENCE_STREAM_DESTINATIONS, tr("Stream-Ziele"), "fas fa-satellite-dish", toggle)
            sidebarLink(Routes.SEPA_SETTINGS, tr("SEPA-Konfiguration"), "fas fa-building-columns", toggle)
            sidebarLink(
                Routes.PAYMENT_GATEWAY_SETTINGS,
                tr("Zahlungs-Konfiguration"),
                "fas fa-hand-holding-dollar",
                toggle,
            )
            sidebarLink(Routes.DUNNING_SETTINGS, tr("Mahnwesen-Konfiguration"), "fas fa-scale-unbalanced", toggle)
            sidebarLink(Routes.EMBED_INTEGRATION, tr("Website-Integration"), "fas fa-code", toggle)
        }
    }

    // Reapplies whatever route NavHighlight currently tracks (set by Routing.kt's `show()` on the
    // last navigation) to this freshly-built link set -- same "rebuild, then reapply" contract
    // `NavHighlight.apply()`'s own KDoc documents for `refreshNavbar`.
    NavHighlight.apply()
}

/**
 * Welle V1.4.10.1 -- `null`/`0` renders the plain label (no badge), `>= 200` shows "200+" rather
 * than the exact count (the sidebar counter mirrors `listReliefRequests`'s own
 * `MAX_LIST_RESULTS = 200` page-size cap -- an exact count above that would silently imply a total
 * this one capped call never actually saw). Pure -- see `SidebarLabelsTest`.
 */
internal fun reliefSidebarLabel(openCount: Int?): String =
    when {
        openCount == null || openCount == 0 -> tr("Beitragsvergünstigungen")
        openCount >= 200 -> gettext("Beitragsvergünstigungen (%1)", "200+")
        else -> gettext("Beitragsvergünstigungen (%1)", openCount)
    }
