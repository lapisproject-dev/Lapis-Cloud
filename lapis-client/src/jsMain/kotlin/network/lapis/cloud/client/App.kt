package network.lapis.cloud.client

import io.kvision.Application
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
            val navbar = navbar(label = "Lapis Cloud", link = "#${Routes.DASHBOARD}")
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

    val leftNav: Nav = navbar.nav()
    leftNav.navLink("Dashboard", url = "#${Routes.DASHBOARD}")
    leftNav.navLink("Beiträge", url = "#${Routes.CONTRIBUTIONS}")
    leftNav.navLink("Dokumente", url = "#${Routes.DOCUMENTS}")
    leftNav.navLink("Kommunikation", url = "#${Routes.COMMUNICATION}")
    // Compliance UI wave, screen 4 of 5: unconditional, alongside "Beiträge"/"Dokumente" -- the
    // ADMIN-only "Anträge verwalten" queue lives INSIDE this same screen (see `Routes.DSGVO_RIGHTS`
    // KDoc), so the nav label itself does not need to fork per role -- don't fork a nav label on a
    // role check when the destination screen already self-adapts (design decision, nav grouping).
    leftNav.navLink("Meine Daten", url = "#${Routes.DSGVO_RIGHTS}")
    leftNav.navLink("Gremien", url = "#${Routes.COMMITTEES}")
    leftNav.navLink("Sitzungen", url = "#${Routes.MEETINGS}")
    leftNav.navLink("Anträge", url = "#${Routes.MOTIONS}")
    // LTR-Wirtschaft UI wave: unconditional, alongside the other `requireAuth`-tier links above --
    // every authenticated member has their own LTR balance/ledger and can send a peer transfer,
    // see `Routes.LTR_LEDGER` KDoc for the `requireAuth`-at-route verification.
    leftNav.navLink("LTR-Konto", url = "#${Routes.LTR_LEDGER}")
    // LTR-Wirtschaft UI wave, screen 2 of 5: same unconditional `requireAuth`-tier placement as
    // "LTR-Konto" above -- every authenticated member can submit a project, react, and read the
    // distribution history; see `Routes.CROWDFUNDING` KDoc for the role-gating verification.
    leftNav.navLink("Crowdfunding", url = "#${Routes.CROWDFUNDING}")
    // LTR-Wirtschaft UI wave, screen 3 of 5: same unconditional `requireAuth`-tier placement as
    // "LTR-Konto"/"Crowdfunding" above -- every authenticated member can browse auctions, bid, and
    // list their own items; the narrower ADMIN-only Verwaltung sub-section (enable/disable, value
    // cap) is gated INSIDE the screen itself, not via a separate nav entry -- see `Routes.AUCTION`
    // KDoc for the role-gating verification.
    leftNav.navLink("Auktion", url = "#${Routes.AUCTION}")
    // LTR-Wirtschaft UI wave, screen 4 of 5: same unconditional `requireAuth`-tier placement as
    // "LTR-Konto"/"Crowdfunding"/"Auktion" above -- every authenticated member (and GAST) can browse
    // politician profiles and rate them; the narrower BOARD/ADMIN-only Verwaltung sub-section
    // (grant/revoke/mandate text/snapshot) and the ADMIN-only `politicianRankingEnabled` toggle are
    // gated INSIDE the screen itself, not via separate nav entries -- see `Routes.POLITICIANS` KDoc
    // for the role-gating verification.
    leftNav.navLink("Politiker", url = "#${Routes.POLITICIANS}")
    // V1.0 Videokonferenzen (Kleinsitzung), Wave 1: same unconditional `requireAuth`-tier placement
    // as the rest of this group -- every authenticated AKTIV member can start/join/browse rooms; the
    // narrower moderator-or-BOARD/ADMIN-only "Für alle beenden" action is gated INSIDE the screen
    // itself, not via a separate nav entry -- see `Routes.CONFERENCE` KDoc for the role-gating
    // verification.
    leftNav.navLink("Videokonferenz", url = "#${Routes.CONFERENCE}")
    // Accounting UI wave, design decision D15: inserted immediately after "Anträge" and before
    // "Mitgliederverwaltung" -- gated on TREASURER/BOARD/ADMIN (the same three roles the LEDGER
    // route itself requires), so a plain MEMBER never even sees this link render.
    if (AppState.hasRole(AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN)) {
        leftNav.navLink("Kontenplan & Journal", url = "#${Routes.LEDGER}")
        leftNav.navLink("Finanzberichte", url = "#${Routes.FINANCIAL_REPORTS}")
        leftNav.navLink("Gemeinnützigkeits-Berichte", url = "#${Routes.COMPLIANCE_REPORTS}")
        leftNav.navLink("Kostenstellen", url = "#${Routes.COST_CENTERS}")
        leftNav.navLink("Spender", url = "#${Routes.DONORS}")
        // Compliance UI wave: same TREASURER/BOARD/ADMIN tier as the rest of this group -- see
        // `Routes.AUDIT_LOG` KDoc for the `AUDIT_READ_ROLES` verification.
        leftNav.navLink("Prüfprotokoll", url = "#${Routes.AUDIT_LOG}")
        // Mail-merge/Postal-Dispatch UI wave: same TREASURER/BOARD/ADMIN tier -- see
        // `Routes.POSTAL_MAIL` KDoc for the `FINANCIAL_DISPATCH_ROLES` verification.
        leftNav.navLink("Postversand", url = "#${Routes.POSTAL_MAIL}")
        // LTR-Wirtschaft UI wave, screen 5 of 5: same TREASURER/BOARD/ADMIN tier as the rest of
        // this group -- every `IPriceOracleService` method requires at least this tier, see
        // `Routes.PRICE_ORACLE` KDoc for the `PRICE_ORACLE_TREASURY_ROLES` verification. The
        // narrower ADMIN-only config-edit tier is gated INSIDE the screen itself as `canManage`,
        // not via a separate nav entry.
        leftNav.navLink("Price-Oracle", url = "#${Routes.PRICE_ORACLE}")
    }
    if (AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)) {
        leftNav.navLink("Mitgliederverwaltung", url = "#${Routes.MEMBERS}")
        // Compliance UI wave, screen 3 of 5: same BOARD/ADMIN tier as "Mitgliederverwaltung" --
        // see `Routes.DSGVO_COMPLIANCE` KDoc for the `COMPLIANCE_READ_ROLES` verification.
        leftNav.navLink("DSGVO-Compliance", url = "#${Routes.DSGVO_COMPLIANCE}")
        // Compliance UI wave, screen 5 of 5: same BOARD/ADMIN tier -- see `Routes.BOARD_MEMBERSHIP`
        // KDoc for the `BOARD_ADMIN_ROLES` verification (uniform across all six RPC methods).
        leftNav.navLink("Vorstand & Transparenzregister", url = "#${Routes.BOARD_MEMBERSHIP}")
    }
    // Compliance UI wave, screen 2 of 5: the first ADMIN-only nav entry in this client -- see
    // `Routes.BACKUP` KDoc for the `requireRole` verification (every `IBackupService` method and
    // both raw HTTP routes require ADMIN, uniformly, narrower than every other group above).
    if (AppState.hasRole(AccountRole.ADMIN)) {
        leftNav.navLink("Backup & Wiederherstellung", url = "#${Routes.BACKUP}")
        // V1.0 Videokonferenzen, Wave 3 "Externes Streaming": same ADMIN-only tier as "Backup &
        // Wiederherstellung" -- see `Routes.CONFERENCE_STREAM_DESTINATIONS` KDoc for the
        // `requireRole(ADMIN)` verification (uniform across all five `IConferenceStreamingService`
        // destination-CRUD methods). Deliberately NOT alongside "Videokonferenz" above -- that link
        // stays in the unconditional `requireAuth`-tier group since every member can reach the
        // conference lobby, but only an ADMIN may configure external streaming credentials.
        leftNav.navLink("Stream-Ziele", url = "#${Routes.CONFERENCE_STREAM_DESTINATIONS}")
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
        rightNav.navLinkDisabled("${session.displayName} (${session.role})")
    }
    val logoutLink = rightNav.navLink("Abmelden", url = "javascript:void(0)")
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
    registerRemoteTypes()
    startApplication(::App)
}
