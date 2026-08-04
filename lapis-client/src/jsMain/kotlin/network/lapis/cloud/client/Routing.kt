package network.lapis.cloud.client

import io.kvision.panel.SimplePanel
import io.kvision.routing.Routing
import network.lapis.cloud.shared.domain.AccountRole

/**
 * Every top-level hash route this wave's SPA offers. Hash-based (`#/dashboard`, ...) so the Ktor
 * server never needs a SPA-fallback/catch-all route for deep links -- every request the server
 * ever sees is `/`, a static asset, an `/api/...` route, or an RPC POST path; the fragment never
 * leaves the browser. See `network.lapis.cloud.server.Application`'s `staticFiles("/", ...)`
 * registration for the same-origin serving this depends on.
 */
object Routes {
    const val LOGIN = "/login"
    const val REGISTER = "/register"
    const val DASHBOARD = "/dashboard"
    const val MEMBERS = "/members"
    const val CONTRIBUTIONS = "/contributions"
    const val DOCUMENTS = "/documents"
    const val COMMUNICATION = "/communication"

    // Governance UI wave: reads are open to any authenticated member (see `IGovernanceService`
    // KDoc -- no RPC method in this interface requires a role to READ), so all three routes use
    // `requireAuth`, not `requireRole`, exactly like CONTRIBUTIONS/DOCUMENTS/COMMUNICATION.
    // Privileged write actions (create committee, add member, ...) are gated inside each screen
    // instead, mirroring `DocumentsScreen`'s `canManage` posture -- see the approved plan §2/§4.
    const val COMMITTEES = "/committees"
    const val MEETINGS = "/meetings"
    const val MOTIONS = "/motions"
}

private var appRouting: Routing? = null

/** Programmatic navigation (nav tiles, post-login/-logout redirects, guards) -- see [initRouting]. */
fun navigateTo(route: String) {
    appRouting?.navigate(route)
}

/**
 * Registers every route in [Routes], wires the auth/role guards described in the V0.7.3 plan
 * ("Routing/navigation structure"), and resolves whatever hash is already in the URL. Must be
 * called exactly once, from `App.start()`, AFTER the initial `getSessionInfo()` boot-time probe
 * has populated (or failed to populate) [AppState.session] -- otherwise the very first resolve
 * would see a stale, always-unauthenticated state and bounce a genuinely logged-in visitor
 * (refreshing the page) to `/login` before their session had a chance to load.
 *
 * [pageContainer] is cleared and re-populated by every route handler -- each screen owns its own
 * `renderXScreen(container)` top-level function in its own file (`LoginScreen.kt`,
 * `DashboardScreen.kt`, ...).
 */
fun initRouting(pageContainer: SimplePanel) {
    val routing = Routing.init(useHash = true)
    appRouting = routing

    /**
     * Round-2-review fix (2026-07-23): wraps every screen render in a `try`/`catch`. Before this
     * fix, an exception thrown anywhere inside a `renderXScreen(container)` call unwound silently
     * -- swallowed by Navigo's own promise-based route-resolution queue (`src/Q.ts`), which never
     * surfaces a rejected/thrown handler to the browser console -- leaving the user looking at a
     * half-rendered or entirely frozen screen with zero diagnostic trail. This is exactly how the
     * `addCssClass("btn btn-outline-primary text-start")`-shaped bug (multi-class string passed to
     * a function that only ever adds a single literal token -- see `CssClasses.kt`) went unnoticed:
     * `DashboardScreen`'s body silently stopped rendering after the first broken `navTile()` call,
     * and -- because the same exception aborted `callHandler` before Navigo's post-handler
     * `updatePageLinks()` re-scan could run -- the top navbar's links never got hooked into
     * Navigo's click-hijacking either, so they looked broken too even though their own markup was
     * fine. A real render exception now at least reaches the console and a user-facing toast
     * instead of vanishing.
     */
    fun show(render: (SimplePanel) -> Unit) {
        pageContainer.removeAll()
        try {
            render(pageContainer)
        } catch (e: Throwable) {
            kotlin.js.console.error("Screen render failed: ${e.message}", e)
            notifyError("Diese Seite konnte nicht geladen werden -- bitte laden Sie die Seite neu.")
        }
    }

    routing.kvOn(Routes.LOGIN) {
        if (AppState.isAuthenticated) {
            routing.navigate(Routes.DASHBOARD)
        } else {
            show(::renderLoginScreen)
        }
    }
    routing.kvOn(Routes.REGISTER) {
        if (AppState.isAuthenticated) {
            routing.navigate(Routes.DASHBOARD)
        } else {
            show(::renderRegistrationScreen)
        }
    }
    routing.kvOn(Routes.DASHBOARD) {
        requireAuth(routing) { show(::renderDashboardScreen) }
    }
    routing.kvOn(Routes.MEMBERS) {
        requireRole(routing, AccountRole.BOARD, AccountRole.ADMIN) { show(::renderMemberAdministrationScreen) }
    }
    routing.kvOn(Routes.CONTRIBUTIONS) {
        requireAuth(routing) { show(::renderContributionsScreen) }
    }
    routing.kvOn(Routes.DOCUMENTS) {
        requireAuth(routing) { show(::renderDocumentsScreen) }
    }
    routing.kvOn(Routes.COMMUNICATION) {
        requireAuth(routing) { show(::renderCommunicationScreen) }
    }
    routing.kvOn(Routes.COMMITTEES) {
        requireAuth(routing) { show(::renderCommitteesScreen) }
    }
    routing.kvOn(Routes.MEETINGS) {
        requireAuth(routing) { show(::renderMeetingsScreen) }
    }
    routing.kvOn(Routes.MOTIONS) {
        requireAuth(routing) { show(::renderMotionsScreen) }
    }
    routing.kvOn("/") {
        routing.navigate(if (AppState.isAuthenticated) Routes.DASHBOARD else Routes.LOGIN)
    }
    routing.notFound(handler = {
        routing.navigate(if (AppState.isAuthenticated) Routes.DASHBOARD else Routes.LOGIN)
    })

    routing.kvResolve()
}

/** Pure guard predicate -- see [ValidationTest] for coverage (no DOM/router dependency). */
fun isRouteAllowed(
    authenticated: Boolean,
    callerRole: AccountRole?,
    requiredRoles: Set<AccountRole>,
): Boolean {
    if (!authenticated) return false
    if (requiredRoles.isEmpty()) return true
    return callerRole in requiredRoles
}

private inline fun requireAuth(
    routing: Routing,
    body: () -> Unit,
) {
    if (!AppState.isAuthenticated) {
        routing.navigate(Routes.LOGIN)
    } else {
        body()
    }
}

private inline fun requireRole(
    routing: Routing,
    vararg roles: AccountRole,
    body: () -> Unit,
) {
    if (!AppState.isAuthenticated) {
        routing.navigate(Routes.LOGIN)
    } else if (!AppState.hasRole(*roles)) {
        notifyError("Kein Zugriff -- diese Seite ist nur für Vorstand/Admin sichtbar.")
        routing.navigate(Routes.DASHBOARD)
    } else {
        body()
    }
}
