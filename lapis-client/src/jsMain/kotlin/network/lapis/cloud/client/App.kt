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
    if (AppState.hasRole(AccountRole.BOARD, AccountRole.ADMIN)) {
        leftNav.navLink("Mitgliederverwaltung", url = "#${Routes.MEMBERS}")
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
