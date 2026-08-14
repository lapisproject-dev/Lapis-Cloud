package network.lapis.cloud.client

import io.kvision.Application
import io.kvision.BootstrapCssModule
import io.kvision.BootstrapModule
import io.kvision.CoreModule
import io.kvision.FontAwesomeModule
import io.kvision.dropdown.ddLink
import io.kvision.dropdown.dropDown
import io.kvision.html.Link
import io.kvision.html.span
import io.kvision.navbar.Nav
import io.kvision.navbar.Navbar
import io.kvision.navbar.nav
import io.kvision.navbar.navLink
import io.kvision.navbar.navLinkDisabled
import io.kvision.navbar.navbar
import io.kvision.panel.root
import io.kvision.panel.vPanel
import io.kvision.remote.registerRemoteTypes
import io.kvision.startApplication
import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.rpc.IAuthService

/** Application-wide coroutine scope tied to the browser's event loop. */
val AppScope: CoroutineScope = CoroutineScope(window.asCoroutineDispatcher())

/**
 * The Lapis family's faceted-stone brand mark, copied verbatim (same `<polygon>`/`<polyline>`
 * point coordinates) from `cloud.lapisproject.dev`'s `Logo.astro` -- see that component's KDoc
 * for the "shared wordmark, per-project suffix" convention this app's plain "Lapis Cloud" label
 * already follows without a `suffix` prop. `stroke="currentColor"` picks up `.lapis-brand-mark`'s
 * `color` from theme.css rather than hardcoding a color here, so a future dark-mode pass only
 * needs to touch the CSS, not this markup.
 */
private const val LAPIS_GEM_MARK_SVG = """<svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    <polygon points="12,1 21,8 17,23 7,23 3,8" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round" />
    <polyline points="12,1 12,23" stroke="currentColor" stroke-width="1" opacity="0.55" />
    <polyline points="3,8 21,8" stroke="currentColor" stroke-width="1" opacity="0.55" />
    <polyline points="7,23 12,8 17,23" stroke="currentColor" stroke-width="1" opacity="0.55" />
</svg>"""

/**
 * V0.7.3 Basis-Mehrseiten-UI: replaces the V0.1.5 single-dashboard "acting as" member-switcher
 * demo with a real, multi-screen SPA covering the core domains needed for a first deployment. Each
 * screen lives in its own file (`LoginScreen.kt`, `RegistrationScreen.kt`, `DashboardScreen.kt`,
 * `MemberAdministrationScreen.kt`, `ContributionsScreen.kt`, `DocumentsScreen.kt`,
 * `CommunicationScreen.kt`) -- mirrors the flat, one-file-per-concern convention
 * `lapis-server/.../rpc/` already uses for its services. `Routing.kt` wires hash-based navigation
 * between them; `AppState.kt`/`AuthHttp.kt` hold the real session-cookie auth this file's own
 * previous KDoc always pointed towards.
 */
class App : Application() {
    override fun start() {
        root("lapis-client") {
            val navbar = navbar(label = "Lapis Cloud", link = "#${Routes.DASHBOARD}", className = "lapis-navbar")
            // UI/UX-Design-Team-Review 2026-08-14 (Forstall): the brand mark reuses the exact
            // gem-glyph polygon geometry from cloud.lapisproject.dev's Logo.astro, not a
            // reinterpretation, so the deployed app and the marketing site read as one product.
            // `labelFirst = false` puts this child (added below) before the "Lapis Cloud" text --
            // see `Link.render()`: labelFirst controls whether the label+icon/image render before
            // or after `childrenVNodes()`, and `Link` is itself a `Container`, so `brandLink.span`
            // is a normal child add, not a special API.
            navbar.brandLink.labelFirst = false
            navbar.brandLink.addCssClass("lapis-brand-text")
            navbar.brandLink.span(content = LAPIS_GEM_MARK_SVG, rich = true, className = "lapis-brand-mark")
            refreshNavbar(navbar)
            val pageContainer = vPanel()

            initNotifications()
            AppState.onSessionChange = { refreshNavbar(navbar) }

            AppScope.launch {
                // Boot-time session probe -- deliberately NOT routed through `guarded()`: an
                // anonymous first-time visitor failing this call is the ordinary, expected case,
                // not a "your session just expired" event, so no error toast here (unlike every
                // other call site in this app, which DOES want that toast).
                val session =
                    try {
                        rpcService<IAuthService>().getSessionInfo()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        null
                    }
                AppState.setSession(session)
                // Routing is initialized only AFTER the probe resolves, so the very first hash
                // resolution already sees the correct auth state -- see `initRouting` KDoc.
                initRouting(pageContainer)
            }
        }
    }
}

private fun refreshNavbar(navbar: Navbar) {
    navbar.removeAll()
    val session = AppState.session
    if (session == null) {
        navbar.hide()
        return
    }
    navbar.show()

    // UI/UX-Design-Team-Review 2026-08-14 (Norman/Raskin): the previous 20-entry flat `navLink`
    // list overflowed the viewport width and hid entries with no visual indication anything was
    // cut off. Regrouped by mental model into dropdowns instead of narrowing/wrapping the same
    // flat list -- "Dashboard"/"Videokonferenz" stay top-level as the two highest-frequency
    // destinations, everything else moves into a themed dropdown. Role-gating is UNCHANGED from
    // the prior flat list: every route/role pair below is identical to before, only the grouping
    // changed -- the three `if (AppState.hasRole(...))` blocks map exactly onto the three
    // role-gated dropdowns (Finanzen/Verwaltung/System) because the original flat list already
    // grouped its role-gated entries contiguously by tier, see each dropdown's own comment for
    // the KDoc cross-references the flat-list version carried per link.
    val leftNav: Nav = navbar.nav()
    leftNav.navLink("Dashboard", url = "#${Routes.DASHBOARD}", icon = "fas fa-house")
    leftNav.navLink("Videokonferenz", url = "#${Routes.CONFERENCE}", icon = "fas fa-video")

    // requireAuth-tier, unconditional for every authenticated member -- see `Routes.CONTRIBUTIONS`/
    // `DOCUMENTS`/`COMMUNICATION`/`DSGVO_RIGHTS` KDoc for the per-route verification. "Meine Daten"
    // self-adapts its content per role (ADMIN-only "Anträge verwalten" queue lives inside the
    // screen) rather than forking the nav label, unchanged from the prior flat-list comment.
    leftNav.dropDown("Mitgliedschaft", icon = "fas fa-id-card", forNavbar = true) {
        ddLink("Beiträge", url = "#${Routes.CONTRIBUTIONS}", icon = "fas fa-coins")
        ddLink("Dokumente", url = "#${Routes.DOCUMENTS}", icon = "fas fa-file-lines")
        ddLink("Kommunikation", url = "#${Routes.COMMUNICATION}", icon = "fas fa-envelope")
        ddLink("Meine Daten", url = "#${Routes.DSGVO_RIGHTS}", icon = "fas fa-shield-halved")
    }
    // requireAuth-tier -- see `Routes.COMMITTEES`/`MEETINGS`/`MOTIONS` KDoc.
    leftNav.dropDown("Selbstverwaltung", icon = "fas fa-people-group", forNavbar = true) {
        ddLink("Gremien", url = "#${Routes.COMMITTEES}", icon = "fas fa-people-group")
        ddLink("Sitzungen", url = "#${Routes.MEETINGS}", icon = "fas fa-calendar-days")
        ddLink("Anträge", url = "#${Routes.MOTIONS}", icon = "fas fa-file-signature")
    }
    // requireAuth-tier -- see `Routes.LTR_LEDGER`/`CROWDFUNDING`/`AUCTION`/`POLITICIANS` KDoc for
    // the per-route verification; narrower role-gated sub-sections inside these screens (e.g.
    // Auktion's ADMIN-only Verwaltung, Politiker's BOARD/ADMIN Verwaltung) are unchanged, still
    // gated INSIDE the screen, not via separate nav entries.
    leftNav.dropDown("Wirtschaft", icon = "fas fa-coins", forNavbar = true) {
        ddLink("LTR-Konto", url = "#${Routes.LTR_LEDGER}", icon = "fas fa-wallet")
        ddLink("Crowdfunding", url = "#${Routes.CROWDFUNDING}", icon = "fas fa-hand-holding-heart")
        ddLink("Auktion", url = "#${Routes.AUCTION}", icon = "fas fa-gavel")
        ddLink("Politiker", url = "#${Routes.POLITICIANS}", icon = "fas fa-landmark")
    }
    // Accounting UI wave, design decision D15 -- gated on TREASURER/BOARD/ADMIN (the same three
    // roles the LEDGER route itself requires), so a plain MEMBER never even sees this dropdown
    // render. See `Routes.LEDGER`/`FINANCIAL_REPORTS`/`COMPLIANCE_REPORTS`/`COST_CENTERS`/
    // `DONORS`/`AUDIT_LOG`/`POSTAL_MAIL`/`PRICE_ORACLE` KDoc for the per-route verification.
    if (AppState.hasRole(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)) {
        leftNav.dropDown("Finanzen", icon = "fas fa-chart-line", forNavbar = true) {
            ddLink("Kontenplan & Journal", url = "#${Routes.LEDGER}", icon = "fas fa-book")
            ddLink("Finanzberichte", url = "#${Routes.FINANCIAL_REPORTS}", icon = "fas fa-chart-pie")
            ddLink(
                "Gemeinnützigkeits-Berichte",
                url = "#${Routes.COMPLIANCE_REPORTS}",
                icon = "fas fa-scale-balanced",
            )
            ddLink("Kostenstellen", url = "#${Routes.COST_CENTERS}", icon = "fas fa-tags")
            ddLink("Spender", url = "#${Routes.DONORS}", icon = "fas fa-heart")
            ddLink("Prüfprotokoll", url = "#${Routes.AUDIT_LOG}", icon = "fas fa-magnifying-glass")
            ddLink("Postversand", url = "#${Routes.POSTAL_MAIL}", icon = "fas fa-envelope-open-text")
            ddLink("Price-Oracle", url = "#${Routes.PRICE_ORACLE}", icon = "fas fa-chart-simple")
        }
    }
    // BOARD/ADMIN-tier -- see `Routes.MEMBERS`/`DSGVO_COMPLIANCE`/`BOARD_MEMBERSHIP` KDoc.
    if (AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)) {
        leftNav.dropDown("Verwaltung", icon = "fas fa-user-gear", forNavbar = true) {
            ddLink("Mitgliederverwaltung", url = "#${Routes.MEMBERS}", icon = "fas fa-users-gear")
            ddLink("DSGVO-Compliance", url = "#${Routes.DSGVO_COMPLIANCE}", icon = "fas fa-shield-halved")
            ddLink(
                "Vorstand & Transparenzregister",
                url = "#${Routes.BOARD_MEMBERSHIP}",
                icon = "fas fa-landmark-flag",
            )
        }
    }
    // ADMIN-only-tier -- see `Routes.BACKUP`/`CONFERENCE_STREAM_DESTINATIONS` KDoc.
    if (AppState.hasRole(AccountRole.ADMIN)) {
        leftNav.dropDown("System", icon = "fas fa-server", forNavbar = true) {
            ddLink("Backup & Wiederherstellung", url = "#${Routes.BACKUP}", icon = "fas fa-database")
            ddLink(
                "Stream-Ziele",
                url = "#${Routes.CONFERENCE_STREAM_DESTINATIONS}",
                icon = "fas fa-satellite-dish",
            )
        }
    }

    val rightNav: Nav = navbar.nav(rightAlign = true)
    // V0.8.4 Guest Badge: a federated OIDC guest session gets a visual indicator in place of the
    // ordinary "(role)" display -- a non-guest session's display below is completely unchanged.
    // homeserverUrl != null is a defensive guard (see GuestBadge.kt guestBadge KDoc): it should
    // always be set for a real guest (OidcGuestProfileTable is 1:1 with a GAST member), but the
    // DTO models it as nullable, so we don't force-unwrap without a check -- falling back to the
    // ordinary display is safer than crashing or showing a badge with no home-server text.
    if (session.isGuest && session.homeserverUrl != null) {
        rightNav.span(className = "nav-item nav-link disabled d-flex align-items-center gap-2") {
            guestBadge(session.homeserverUrl!!)
            span("${session.displayName} (Gast)")
        }
    } else {
        rightNav.navLinkDisabled("${session.displayName} (${session.role})", icon = "fas fa-user")
    }
    val logoutLink = rightNav.navLink("Abmelden", url = "javascript:void(0)", icon = "fas fa-right-from-bracket")
    logoutLink.onClick {
        AppScope.launch {
            AuthHttp.logout()
            AppState.setSession(null)
            navigateTo(Routes.LOGIN)
        }
    }
}

fun main() {
    // Critical fix (found+fixed during V0.7.3 review round 1): every `navLink(...)`/`link(...)`
    // call in this app (Routing.kt's own KDoc notwithstanding) passes only `url = "#/x"`, never
    // `dataNavigo = true` -- and `io.kvision.html.Link.useDataNavigoForLinks` defaults to `false`.
    // Without one of those two, `Link.buildAttributeSet` never emits the `data-navigo` attribute,
    // so kvision-routing-navigo-ng's own click-hijacking (`linksSelector`) never recognizes these
    // anchors as SPA-routed links: a real click just performs the browser's native, un-intercepted
    // hash-fragment update -- `location.hash` changes, but no `Routing.kvOn(...)` handler ever
    // fires, so the visible screen never changes. Verified end-to-end in a real browser against
    // both the production and development webpack bundles: every nav-link/tile click (Beiträge,
    // Dokumente, Kommunikation, Mitgliederverwaltung, the Dashboard "Bereiche" tiles) silently did
    // nothing -- only the explicit, programmatic `routing.navigate(...)` call sites (post-login,
    // post-logout, the boot-time `/` resolve, `guarded()`'s session-expiry redirect) worked, because
    // those bypass link-hijacking entirely. Setting this flag globally, once, before any `Link` is
    // ever constructed (i.e. here in `main()`, before `startApplication`) is the standard KVision
    // fix -- see `io.kvision.html.Link` companion object KDoc -- and is simpler and less error-prone
    // than threading `dataNavigo = true` through every individual `navLink`/`link`/`navTile` call
    // site across every screen file.
    Link.useDataNavigoForLinks = true
    // UI/UX-Design-Team-Review 2026-08-14: loads theme.css (papyrus/lapis-lazuli/gold palette,
    // ported from cloud.lapisproject.dev's tokens.css) into the webpack bundle. `js("require(...)")`
    // is the standard Kotlin/JS idiom for a raw stylesheet import under `cssSupport { enabled.set(true) }`
    // (see lapis-client/build.gradle.kts) -- css-loader/style-loader inject it as a `<style>` tag at
    // runtime, same mechanism BootstrapCssModule already relies on internally for Bootstrap's own CSS.
    js("require('./theme.css')")
    registerRemoteTypes()
    // Bug fix: startApplication() previously registered no CSS modules at all -- KVision only
    // requires Bootstrap's CSS (and its own base styles) when the corresponding module is passed
    // here explicitly; it is NOT pulled in automatically just because kvision-bootstrap is a
    // Gradle dependency. Without this, the app rendered as completely unstyled HTML (raw browser
    // default link/button styling, no navbar chrome, no icons) despite every Bootstrap CSS class
    // name being present in the DOM -- found live on the first real deployment (2026-08-14), where
    // it had gone unnoticed because prior verification only checked RPC/functional behavior, never
    // a visual screenshot of the production bundle.
    startApplication(::App, null, CoreModule, BootstrapModule, BootstrapCssModule, FontAwesomeModule)
}
