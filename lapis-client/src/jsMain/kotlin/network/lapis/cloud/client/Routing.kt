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

    // Accounting UI wave: unlike Governance, every single `IAccountingService` method requires at
    // least TREASURER/BOARD/ADMIN server-side (see that interface's own class KDoc) -- there is no
    // plain-MEMBER-readable Accounting RPC at all. This route therefore gates at the route level
    // with `requireRole`, not `requireAuth` -- a deliberate deviation from the Governance UI wave's
    // routing posture, verified against every `requireRole` call site in `AccountingService.kt`.
    const val LEDGER = "/ledger"

    // Screen 2 of 5 -- GuV/Bilanz/Jahresabschluss, purely read-only (`getIncomeStatement`/
    // `getBalanceSheet`/`getAnnualFinancialStatement` are all `ACCOUNTING_READ_ROLES`, same
    // TREASURER/BOARD/ADMIN tier as [LEDGER]'s route guard below).
    const val FINANCIAL_REPORTS = "/financial-reports"

    // Screen 3 of 5 -- Vier-Sphären-Ergebnisrechnung + Mittelverwendungsrechnung, purely
    // read-only (`getFourSphereIncomeStatement`/`getUseOfFundsStatement` are both
    // `ACCOUNTING_READ_ROLES`, same TREASURER/BOARD/ADMIN tier as [LEDGER]/[FINANCIAL_REPORTS]).
    const val COMPLIANCE_REPORTS = "/compliance-reports"

    // Screen 4 of 5 -- Kostenstellen CRUD (`createCostCenter`/`deactivateCostCenter`, both
    // `TREASURY_ROLES`) + report (`listCostCenters`/`getCostCenterReport`, both
    // `ACCOUNTING_READ_ROLES`) -- same route-level TREASURER/BOARD/ADMIN guard as [LEDGER], with
    // the narrower TREASURER/ADMIN write-gating handled inside the screen itself.
    const val COST_CENTERS = "/cost-centers"

    // Screen 5 of 5 -- external donor CRM-lite CRUD (`createExternalDonor`/`deactivateExternalDonor`,
    // both `TREASURY_ROLES`) + §25 PartG duty report (`listExternalDonors`/`getExternalDonor`/
    // `getDonationDutyReport`, all `ACCOUNTING_READ_ROLES`) -- same route-level TREASURER/BOARD/ADMIN
    // guard as [LEDGER]/[COST_CENTERS], with the narrower TREASURER/ADMIN write-gating handled
    // inside the screen itself.
    const val DONORS = "/donors"

    // Compliance UI wave, screen 1 of 5 -- GoBD-revisionssicheres Prüfprotokoll, purely read-only
    // (`IAuditLogService` has no write method at all -- see that interface's own KDoc). Role gate
    // verified against `AuditLogService.kt`'s `AUDIT_READ_ROLES` constant: TREASURER/BOARD/ADMIN,
    // the same tier as [LEDGER]/[FINANCIAL_REPORTS]/[COMPLIANCE_REPORTS]/[COST_CENTERS]/[DONORS].
    const val AUDIT_LOG = "/audit-log"

    // Compliance UI wave, screen 2 of 5 -- full-organization export/restore + operations log.
    // Role gate verified against `BackupService.kt`/`BackupRoutes.kt`'s `requireRole` call sites:
    // ADMIN only, uniformly, for every one of `IBackupService`'s methods AND both raw HTTP routes
    // (`/api/backup/export`, `/api/backup/restore`) -- narrower than every other route in this
    // file (the first ADMIN-only route in this client).
    const val BACKUP = "/backup"

    // Compliance UI wave, screen 3 of 5 -- AVV-Register/TOMs/DSFA/Datenpannenmeldung. Role gate
    // verified against `DsgvoComplianceService.kt`'s `COMPLIANCE_READ_ROLES` constant: BOARD/ADMIN,
    // deliberately WITHOUT TREASURER/MEMBER (unlike [AUDIT_LOG]) -- see `IDsgvoComplianceService`
    // KDoc "organizational DSGVO-compliance records, not treasury records". The narrower
    // `AVV_TOM_WRITE_ROLES` (ADMIN only) is gated inside the screen itself, not at route level.
    const val DSGVO_COMPLIANCE = "/dsgvo-compliance"

    // Compliance UI wave, screen 4 of 5 -- member-facing DSGVO rights (Auskunft/Löschung, Art.
    // 15/17/20 DSGVO) plus an ADMIN-only decide/execute queue for erasure requests + DSGVO audit
    // trail. Role gate verified against `DsgvoService.kt`'s actual call sites: `exportManifest`/
    // `requestErasure` are `requireSelfOrAdmin` (any authenticated member acting on themselves --
    // the only case this screen's self-service tier calls), while `listErasureRequests`/
    // `decideErasure`/`executeErasure`/`listAuditLog` are `requireRole(ADMIN)`. Unlike every
    // `requireRole`-gated route above, this route uses plain `requireAuth` -- the narrower ADMIN
    // tier is gated INSIDE the screen (`AppState.hasRole(ADMIN)`), mirroring [CONTRIBUTIONS]'s own
    // `requireAuth`-at-route/`canManage`-in-screen shape, because every authenticated member (not
    // just ADMIN) needs to reach this route for their own Auskunft/Löschung rights.
    const val DSGVO_RIGHTS = "/dsgvo-rights"

    // Compliance UI wave, screen 5 of 5 -- board roster + §20 GwG Transparenzregister report/
    // reminders. Role gate verified against `BoardMembershipService.kt`'s `BOARD_ADMIN_ROLES`
    // constant: BOARD/ADMIN, uniformly, for every one of `IBoardMembershipService`'s six methods --
    // no narrower write tier exists on this interface, unlike [DSGVO_COMPLIANCE], so (mirroring
    // [AUDIT_LOG]'s "route guard is the only guard" posture, just for a screen with real write
    // actions) there is no in-screen `canManage` split either.
    const val BOARD_MEMBERSHIP = "/board-membership"
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
    routing.kvOn(Routes.LEDGER) {
        requireRole(routing, AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN) { show(::renderLedgerScreen) }
    }
    routing.kvOn(Routes.FINANCIAL_REPORTS) {
        requireRole(routing, AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN) { show(::renderFinancialReportsScreen) }
    }
    routing.kvOn(Routes.COMPLIANCE_REPORTS) {
        requireRole(routing, AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN) {
            show(::renderNonprofitComplianceReportsScreen)
        }
    }
    routing.kvOn(Routes.COST_CENTERS) {
        requireRole(routing, AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN) { show(::renderCostCentersScreen) }
    }
    routing.kvOn(Routes.DONORS) {
        requireRole(routing, AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN) { show(::renderDonorsScreen) }
    }
    routing.kvOn(Routes.AUDIT_LOG) {
        requireRole(routing, AccountRole.TREASURER, AccountRole.BOARD, AccountRole.ADMIN) { show(::renderAuditLogScreen) }
    }
    routing.kvOn(Routes.BACKUP) {
        requireRole(routing, AccountRole.ADMIN) { show(::renderBackupScreen) }
    }
    routing.kvOn(Routes.DSGVO_COMPLIANCE) {
        requireRole(routing, AccountRole.BOARD, AccountRole.ADMIN) { show(::renderDsgvoComplianceScreen) }
    }
    routing.kvOn(Routes.DSGVO_RIGHTS) {
        requireAuth(routing) { show(::renderDsgvoRightsScreen) }
    }
    routing.kvOn(Routes.BOARD_MEMBERSHIP) {
        requireRole(routing, AccountRole.BOARD, AccountRole.ADMIN) { show(::renderBoardMembershipScreen) }
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

/**
 * Accounting UI wave design review, "Additional decision not on the original list": the denial
 * copy used to hardcode "...nur für Vorstand/Admin sichtbar", written when [MEMBERS] was the only
 * `requireRole`-gated route (BOARD/ADMIN). Reusing it verbatim for the new Accounting routes
 * (TREASURER/BOARD/ADMIN) would show a MEMBER an inaccurate role list, so the message is
 * generalized to be role-neutral -- applies retroactively to `/members` too with no loss of
 * meaning.
 */
private inline fun requireRole(
    routing: Routing,
    vararg roles: AccountRole,
    body: () -> Unit,
) {
    if (!AppState.isAuthenticated) {
        routing.navigate(Routes.LOGIN)
    } else if (!AppState.hasRole(*roles)) {
        notifyError("Kein Zugriff -- für diesen Bereich fehlt Ihnen die Berechtigung.")
        routing.navigate(Routes.DASHBOARD)
    } else {
        body()
    }
}
