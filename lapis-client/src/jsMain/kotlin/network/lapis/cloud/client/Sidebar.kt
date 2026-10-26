package network.lapis.cloud.client

import io.kvision.core.Widget
import io.kvision.html.ButtonStyle
import io.kvision.html.Icon
import io.kvision.html.Link
import io.kvision.html.Span
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
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatus
import network.lapis.cloud.shared.rpc.IArticleService
import network.lapis.cloud.shared.rpc.IContributionReliefService
import network.lapis.cloud.shared.rpc.ITravelExpenseService
import network.lapis.cloud.shared.rpc.IVolunteerAllowanceService
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
    const val STORAGE_KEY = "lapis-cloud-sidebar-groups.v2"

    /** Pre-V1.9.183 key: also held groups that were only opened automatically by the route. Read once, migrated, removed. */
    const val LEGACY_STORAGE_KEY = "lapis-cloud-sidebar-groups"

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

    /**
     * Reads [STORAGE_KEY] from `localStorage`; if absent, migrates [LEGACY_STORAGE_KEY] once (sanitised by
     * [parse], written to v2, legacy key removed). Never throws: private mode, blocked site data or a
     * throwing `getItem`/`setItem` yield an empty set and the page works without persistence.
     */
    fun load(): Set<SidebarGroupId> =
        runCatching {
            val current = localStorage.getItem(STORAGE_KEY)
            if (current != null) return@runCatching parse(current)
            val legacy = localStorage.getItem(LEGACY_STORAGE_KEY) ?: return@runCatching emptySet()
            val migrated = parse(legacy)
            runCatching {
                localStorage.setItem(STORAGE_KEY, serialize(migrated))
                localStorage.removeItem(LEGACY_STORAGE_KEY)
            }
            migrated
        }.getOrDefault(emptySet())

    /** Persists [ids] to `localStorage` under [STORAGE_KEY]; a silent no-op when storage is unavailable. */
    fun save(ids: Set<SidebarGroupId>) {
        runCatching { localStorage.setItem(STORAGE_KEY, serialize(ids)) }
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
 * Routes that belong to a group (opening it on a deep link, highlighting) but have no sidebar link of their own: sub-flows reached from a
 * list screen. Not part of [NavRegistry], so the dashboard shows no tile for them.
 */
private val GROUP_DEEP_LINK_ONLY_ROUTES: Map<SidebarGroupId, List<String>> =
    mapOf(SidebarGroupId.ADMINISTRATION to listOf(Routes.EVENT_IMPORT, Routes.ARTICLE_IMPORT))

/**
 * Route -> owning sidebar group. Derived from [NavRegistry] (the list [buildSidebar] builds from), so "which routes trigger forcing a
 * group open" ([sidebarGroupForRoute]) can never drift from "which routes actually render inside that group" the way two
 * independently-maintained lists could. Since V1.9.220 this includes Bankkonten and Ehrenamtspauschalen (their groups now open on a deep link).
 */
internal val GROUP_ROUTES: Map<SidebarGroupId, List<String>> by lazy {
    // Lazy on purpose: NavRegistry's initializer calls the label functions of THIS file, which would otherwise start this file's top-level
    // initialization while NavRegistry is still being created (an initialization cycle that leaves NavRegistry undefined in Kotlin/JS).
    NavRegistry.entries
        .mapNotNull { entry -> entry.group?.let { it to entry.route } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (group, routes) -> (routes + GROUP_DEEP_LINK_ONLY_ROUTES[group].orEmpty()).distinct() }
}

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
    SidebarHelpMenu.disposeCurrent()
    // V1.9.203: nobody is signed in -- nothing to search, and no timer or open palette of the old session survives.
    MenuSearchField.disposeCurrent()
    CommandPalette.close()
    MenuIndex.reset()
    // V1.9.224: nobody is signed in -- no timer, no listener and no figures of the old session survive.
    NavCountsPoller.stop()
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
    SidebarHelpMenu.disposeCurrent()
    // V1.9.203: the menu index is rebuilt together with the links (see [MenuIndex]); the old field's timer and an open palette go first.
    MenuSearchField.disposeCurrent()
    CommandPalette.close()
    MenuIndex.reset()
    body.removeAll()
    // Review-Fund 2026-09-08 (Finding 2, HOCH): counterpart to `clearSidebar`'s own
    // `addCssClass("lapis-sidebar-empty")` -- this build IS populating the sidebar, so drop the
    // marker again (a no-op if it was never set, e.g. the very first build of a session). See
    // theme.css's `:not(.lapis-sidebar-empty)` comment and `clearSidebar`'s own KDoc.
    body.removeCssClass("lapis-sidebar-empty")
    NavHighlight.reset()
    // V1.9.145: the listeners of the old member-finder link go with the old links.
    MemberFinderNav.resetListeners()

    // V1.4.8 Sidebar-Layout-Fix (Design-Team Atkinson/Kay, Jobs' Review): EIN eigener, vertikal
    // stapelnder Container statt Bootstraps privatem `.offcanvas-body` direkt zu befüllen. Bootstrap
    // setzt `.offcanvas-lg .offcanvas-body { display: flex }` ab 992px (Navbar-Muster, bootstrap.css
    // 5.3.8 Z. 6515) -- OHNE eigenes `flex-direction` (Default `row`). Weil `.lapis-sidebar` damals selbst
    // `overflow-y: auto` setzte (per CSS-Spezifikation wird `overflow-x` dann ebenfalls `auto`;
    // seit V1.9.68 / R59 hat die Seitenleiste ab 992px gar keinen eigenen Scroller mehr), liefen die bislang direkt in `body` eingefügten
    // Top-Level-Einträge (2 flache Links + 6 Gruppen, siehe [GROUP_ROUTES]) in EINER horizontalen
    // Zeile -- alles ab dem zweiten Link wurde innerhalb der 264px-breiten Spalte unsichtbar
    // weggescrollt (Live-Fund PROD_HOST: ADMIN-Account sah nur "Dashboard"/
    // "Videokonferenz" nebeneinander, keine der sechs Rollen-Gruppen). Wir besitzen unser eigenes
    // Layout vollständig (`.lapis-sidebar-nav`, theme.css), statt gegen Bootstraps `.offcanvas-body`-
    // Interna mit einer dritten `!important`-Schicht anzuschreiben. Siehe `SidebarStructureTest` für
    // die Regressionsabsicherung.
    val nav = Nav(className = "flex-column lapis-sidebar-nav")
    body.add(nav)
    // V1.9.203: the menu search is the first child of the nav, before "Dashboard" (it is neither a Link nor a Nav, so the link/group scans of
    // the sidebar tests are unaffected).
    mountMenuSearchField(nav, onNavigate)

    // Two sets on purpose: `persisted` changes ONLY through header clicks and is the only thing written
    // to storage; `openGroups` (persisted + the active route's group) is the display state at build time.
    val persisted = SidebarGroupStorage.load().toMutableSet()
    val openGroups = persisted + listOfNotNull(sidebarGroupForRoute(activeRoute))

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

    // V1.9.203: the label of the group whose links are being built (null for the flat entries) -- `sidebarLink` files each link in the menu index with it.
    var currentGroupLabel: String? = null

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
        // V1.9.203: the menu index gets exactly the links that are built here (so the search can only offer what the menu shows). The title is
        // the plain label as given NOW -- the counters of the approval links change `link.label` later, the index keeps the base title.
        MenuIndex.register(
            route = route,
            title = plainMenuText(label),
            icon = icon,
            groupLabel = currentGroupLabel,
            isVisible = { link.visible },
        )
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
        val bodyId = "lapis-sidebar-group-${id.name.lowercase()}"
        header.setAttribute("aria-expanded", isOpen.toString())
        header.setAttribute("aria-controls", bodyId)
        // Trailing child (Button is a SimplePanel: label first, children after) -- never part of Button.text.
        val chevron = Icon(ActionIcon.CHEVRON.css)
        chevron.addCssClass("lapis-sidebar-group-chevron")
        chevron.setAttribute("aria-hidden", "true")
        header.add(chevron)
        val groupBody = Nav(className = "flex-column lapis-sidebar-group-body")
        groupBody.setAttribute("id", bodyId)
        add(groupBody)

        // Only a header click persists; opening through routing (persist = false) must not be stored.
        fun setOpen(
            nowOpen: Boolean,
            persist: Boolean,
        ) {
            if (nowOpen) groupBody.show() else groupBody.hide()
            header.setAttribute("aria-expanded", nowOpen.toString())
            if (persist) {
                if (nowOpen) persisted += id else persisted -= id
                SidebarGroupStorage.save(persisted)
            }
        }
        // Registered BEFORE `content(header)` runs below -- `sidebarLink` calls inside `content`
        // look this map up immediately (via the `toggle` parameter they're passed), so the entry
        // must already exist by the time the first one of them fires.
        groupOpeners[header] = { if (!groupBody.visible) setOpen(nowOpen = true, persist = false) }

        currentGroupLabel = plainMenuText(label)
        groupBody.content(header)
        currentGroupLabel = null
        if (!isOpen) groupBody.hide()
        header.onClick {
            setOpen(nowOpen = !groupBody.visible, persist = true)
        }
    }

    // V1.9.220: the destinations come from [NavRegistry] (one list for the sidebar and the dashboard tiles). The member-finder row is built
    // whenever the person MAY see it (offered = true here) and `enhance` then follows the real, asynchronously loaded answer.
    val buildContext = NavContext(session = session, memberFinderOffered = true)
    NavRegistry.flat(buildContext).forEach { entry ->
        val link = nav.sidebarLink(entry.route, entry.label, entry.icon)
        enhance(entry, link, session)
    }
    NavRegistry.visibleGroups(buildContext).forEach { groupId ->
        val meta = NavRegistry.groupMeta.getValue(groupId)
        nav.sidebarGroup(groupId, meta.label, meta.icon) { toggle ->
            NavRegistry.visibleInGroup(groupId, buildContext).forEach { entry ->
                val link = sidebarLink(entry.route, entry.label, entry.icon, toggle)
                enhance(entry, link, session)
            }
        }
    }

    // V1.9.224: the live counters of "Videokonferenz" and "Begegnungsräume" -- one poller for the whole page, started once per person.
    NavCountsPoller.start(ownerId = session.memberId)

    // V1.9.187: the "Hilfe und Feedback" foot of the sidebar -- the SECOND direct child of `body` (after the nav, never inside it, so the
    // link set and NavHighlight stay untouched). It scrolls with the page like the rest (R59) and its popover opens upward.
    mountSidebarHelpMenu(body)

    // Reapplies whatever route NavHighlight currently tracks (set by Routing.kt's `show()` on the
    // last navigation) to this freshly-built link set -- same "rebuild, then reapply" contract
    // `NavHighlight.apply()`'s own KDoc documents for `refreshNavbar`.
    NavHighlight.apply()
}

/**
 * The live parts of a sidebar row that the registry does not describe: the open-request counters and the member-finder visibility. Keyed by
 * the route (and, for the carpool, by flat vs. grouped).
 */
private fun enhance(
    entry: NavEntry,
    link: Link,
    session: SessionInfoDto,
) {
    // V1.9.224: "(aktiv/gesamt)" as a child of the link, never part of its label (the menu search indexes the label; the counters of the
    // approval links below overwrite the label). Hidden until the first figures arrive.
    entry.liveSuffix?.let { suffix ->
        val span = Span(className = "nav-live-suffix")
        span.setAttribute("aria-hidden", "true")
        span.visible = false
        link.add(span)
        NavCountsPoller.bind(suffix = suffix, span = span, link = link, label = entry.label)
    }
    when (entry.route) {
        Routes.CARPOOL -> updateCarpoolSidebarCount(link = link, isMember = entry.group != null)
        // V1.9.145 -- shown only while the server offers it (instance switch on); `fa-user-group` is free (`fa-address-book` is the contacts).
        Routes.MEMBER_FINDER -> {
            link.visible = NavVisibility.showsMemberFinder(session.status, MemberFinderNav.offered)
            MemberFinderNav.onChange { offered -> link.visible = NavVisibility.showsMemberFinder(session.status, offered) }
            MemberFinderNav.ensureLoaded(session)
        }
        // Welle V1.4.36 -- BOARD/ADMIN see the size of the approval queue in the label (every member sees the entry itself).
        Routes.ARTICLES ->
            if (AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)) {
                AppScope.launch {
                    val openCount = runCatching { rpcService<IArticleService>().listSubmittedArticles().size }.getOrNull()
                    if (openCount != null && openCount > 0) link.label = articlesSidebarLabel(openCount)
                }
            }
        // Counters: `runCatching`, NOT `guarded{}` -- a failure while building the sidebar must never raise a toast. The label is overwritten
        // afterwards by a coroutine (the build is not `suspend`); the link first renders without a number like every other entry.
        Routes.CONTRIBUTION_RELIEF ->
            AppScope.launch {
                val openCount =
                    runCatching {
                        rpcService<IContributionReliefService>().listReliefRequests(status = ContributionReliefStatus.REQUESTED).size
                    }.getOrNull()
                if (openCount != null && openCount > 0) link.label = reliefSidebarLabel(openCount)
            }
        Routes.TRAVEL_EXPENSE_APPROVALS ->
            AppScope.launch {
                val openCount =
                    runCatching {
                        rpcService<ITravelExpenseService>()
                            .listReports(
                                status = TravelExpenseReportStatus.REQUESTED,
                            ).size
                    }.getOrNull()
                if (openCount != null && openCount > 0) link.label = travelExpenseSidebarLabel(openCount)
            }
        Routes.VOLUNTEER_ALLOWANCE_APPROVALS ->
            AppScope.launch {
                val openCount =
                    runCatching {
                        rpcService<IVolunteerAllowanceService>().listPayments(status = VolunteerAllowancePaymentStatus.REQUESTED).size
                    }.getOrNull()
                if (openCount != null && openCount > 0) link.label = volunteerAllowanceSidebarLabel(openCount)
            }
    }
}

/**
 * V1.9.203: the readable text of a sidebar label. `tr(key)` returns the key with a marker that KVision resolves at render time; the menu
 * index needs the real text now, so the marker is removed and the key is translated with `gettext`. Text without the marker is returned as is.
 */
internal fun plainMenuText(label: String): String =
    if (label.startsWith(KVISION_TR_MARKER)) gettext(label.removePrefix(KVISION_TR_MARKER)) else label

private const val KVISION_TR_MARKER = "###KvI18nS###"

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

/**
 * Welle V1.4.11 -- gleiche Grammatik wie [reliefSidebarLabel]: `null`/`0` rendert das reine Label
 * (kein Badge), `>= 200` zeigt "200+" statt der exakten Zahl (spiegelt
 * `TravelExpenseService.MAX_LIST_RESULTS`). Pure -- see `SidebarLabelsTest`.
 */
internal fun travelExpenseSidebarLabel(openCount: Int?): String =
    when {
        openCount == null || openCount == 0 -> tr("Reisekosten-Freigaben")
        openCount >= 200 -> gettext("Reisekosten-Freigaben (%1)", "200+")
        else -> gettext("Reisekosten-Freigaben (%1)", openCount)
    }

/** Welle V1.4.12 -- gleiche Grammatik wie [travelExpenseSidebarLabel]. */
internal fun volunteerAllowanceSidebarLabel(openCount: Int?): String =
    when {
        openCount == null || openCount == 0 -> tr("Ehrenamtspauschalen-Freigaben")
        openCount >= 200 -> gettext("Ehrenamtspauschalen-Freigaben (%1)", "200+")
        else -> gettext("Ehrenamtspauschalen-Freigaben (%1)", openCount)
    }
