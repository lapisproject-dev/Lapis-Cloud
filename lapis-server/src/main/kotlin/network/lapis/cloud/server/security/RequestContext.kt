package network.lapis.cloud.server.security

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusSets
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * V0.7.1 Authentifizierung: [resolveCurrentMember] is the ONE designed switch point this codebase
 * has been building toward since the `X-Member-Id` stand-in was first introduced (V0.1.2-V0.1.4,
 * see CLAUDE.md "Vorab-Befund") — every RPC service implementation resolves the caller exactly
 * once per call via this function and uses the resulting [CurrentMember.role] for authorization
 * checks, so swapping the resolution mechanism only ever touches this one file, never each
 * service constructor's `call` parameter or each individual authorization check.
 *
 * **Production path**: a real, server-issued session token — read from the `lapis_session` cookie
 * (see [network.lapis.cloud.server.routes.registerAuthRoutes]) or, as a fallback, an
 * `Authorization: Bearer <token>` header — resolved via [SessionStore.resolve]. Session-token
 * lookup ALWAYS runs first, before the test-only fallback below, so a stray/forged `X-Member-Id`
 * header can never override a real, already-authenticated session.
 *
 * **Test-only fallback**: if (and only if) no session token resolves, AND
 * [AuthTestMode.trustedHeaderAuthEnabled] holds (both independent locks — JVM system property AND
 * H2-in-memory — see that object's KDoc), the legacy `X-Member-Id` trusted-header lookup runs —
 * byte-for-byte the same member⋈account lookup this function has always performed, so every one
 * of this codebase's ~700 existing tests (905 `header("X-Member-Id", ...)` call sites) keeps
 * working unmodified. [AuthTestMode.trustedHeaderAuthEnabled] is structurally `false` in any real
 * (Postgres) deployment — see that object's KDoc for the full defense-in-depth reasoning.
 *
 * [status] (V0.11.0) carries the caller's real [network.lapis.cloud.shared.domain.MemberStatus] --
 * every gate re-reads [network.lapis.cloud.server.db.generated.MemberTable] per call anyway (no
 * caching), so this is a free byproduct of the join both construction sites already perform.
 * [isGuest] and [isNonMember] below are DERIVED from it, not independently stored -- see their own
 * KDoc for the historical vs. current authorization-decision distinction.
 */
data class CurrentMember(
    val memberId: Uuid,
    val role: AccountRole,
    val status: MemberStatus,
) {
    /**
     * Retained for the V0.8.4 Guest-Badge wire contract ([network.lapis.cloud.shared.domain
     * .SessionInfoDto.isGuest]) ONLY -- never for an authorization decision. Use
     * `status in MemberStatusSets.X` for those. See [isNonMember] for the authorization-relevant
     * positive-capability replacement this V0.11.0 wave introduced.
     */
    val isGuest: Boolean get() = status == MemberStatus.GUEST

    /**
     * Not a member of THIS organization ([network.lapis.cloud.shared.domain.MemberStatusSets
     * .NON_MEMBER] -- currently [MemberStatus.GUEST] and [MemberStatus.FRIEND]). Replaces every
     * former `isGuest`-as-authorization use: a denylist of exactly one status was the wrong shape
     * for a status set that was about to grow (V0.11.0 added [MemberStatus.FRIEND] specifically
     * to close that gap before it could silently widen access).
     */
    val isNonMember: Boolean get() = status in MemberStatusSets.NON_MEMBER
}

private const val MEMBER_ID_HEADER = "X-Member-Id"

/** Name of the session cookie set by [network.lapis.cloud.server.routes.registerAuthRoutes] on a successful login. */
const val SESSION_COOKIE_NAME = "lapis_session"

fun resolveCurrentMember(call: ApplicationCall): CurrentMember {
    val rawToken = extractSessionToken(call)
    if (rawToken != null) {
        SessionStore.resolve(rawToken)?.let { return it }
    }
    if (AuthTestMode.trustedHeaderAuthEnabled) {
        resolveFromTrustedHeader(call)?.let { return it }
    }
    throw UnauthenticatedException()
}

/** Exposed (not `private`) so [network.lapis.cloud.server.rpc.AuthService.changePassword] can pass the caller's OWN current raw token as the `exceptRawToken` to [SessionStore.revokeAllForMember] -- see that function's call site. */
internal fun extractSessionToken(call: ApplicationCall): String? {
    val cookieToken = call.request.cookies[SESSION_COOKIE_NAME]
    if (!cookieToken.isNullOrBlank()) return cookieToken
    val authHeader = call.request.headers["Authorization"] ?: return null
    val bearerToken = authHeader.removePrefix("Bearer ").trim()
    return bearerToken.ifBlank { null }
}

/** One-time (per JVM) WARN the first time the trusted-header fallback is actually used — a visible signal in test/dev logs that this codepath, not real session auth, resolved the caller. */
private val trustedHeaderWarningLogged = AtomicBoolean(false)

/**
 * The pre-V0.7.1 `X-Member-Id` trusted-header lookup, preserved byte-for-byte so every existing
 * test keeps passing unmodified — see [resolveCurrentMember] KDoc "Test-only fallback". Re-asserts
 * [DeploymentMode.isH2InMemory] itself (defense in depth on top of [AuthTestMode]'s own two
 * locks) — throws rather than silently granting access if this is somehow reached against a real
 * deployment.
 */
private fun resolveFromTrustedHeader(call: ApplicationCall): CurrentMember? {
    check(DeploymentMode.isH2InMemory()) {
        "resolveFromTrustedHeader must never run against a non-H2-in-memory database"
    }
    if (trustedHeaderWarningLogged.compareAndSet(false, true)) {
        logger.warn { "Trusted X-Member-Id header auth is active (test-mode-only fallback) -- this must never happen in a real deployment" }
    }
    val headerValue = call.request.headers[MEMBER_ID_HEADER] ?: return null
    val memberId = runCatching { Uuid.parse(headerValue) }.getOrElse { return null }
    return transaction {
        val row =
            (MemberTable innerJoin AccountTable)
                .selectAll()
                .where { MemberTable.id eq memberId }
                .singleOrNull()
                ?: return@transaction null
        CurrentMember(
            memberId = memberId,
            role = row[AccountTable.role],
            status = row[MemberTable.status],
        )
    }
}

fun CurrentMember.requireRole(vararg allowed: AccountRole) {
    if (role !in allowed) throw ForbiddenException()
}

val CurrentMember.isPrivileged: Boolean
    get() = role == AccountRole.ADMIN || role == AccountRole.BOARD

/**
 * Three distinct [DocumentAccessLevel] tiers, three distinct outcomes — ADMIN_ONLY must require
 * the ADMIN role specifically, not just "privileged" (BOARD or ADMIN), otherwise BOARD_ONLY and
 * ADMIN_ONLY collapse into the same check. Used identically by [DocumentAccessLevel]-filtered
 * reads (listDocuments/listVersions) and the HTTP download route so the two never drift apart.
 *
 * `PUBLIC_MEMBERS` means "visible to members of THIS organization" and therefore requires
 * `status` to be in [MemberStatusSets.ORGANIZATION_MEMBER] (currently just [MemberStatus.ACTIVE]).
 * **V0.11.0 rewrite**: this used to be the denylist `!isGuest`, which had two problems the FRIEND
 * wave forced into the open -- (1) a self-registered [MemberStatus.FRIEND] would otherwise pass it
 * (denylist of one status is the wrong shape for a status set that just grew), and (2) it silently
 * ALSO passed for [MemberStatus.APPLICATION] (an applicant who *can* log in -- `AuthRoutes` only
 * blocks [MemberStatus.WITHDRAWN]/[MemberStatus.REJECTED] -- was never meant to read internal
 * documents before being admitted). Both are closed by testing organization-membership positively
 * instead of guest-status negatively. `BOARD_ONLY`/`ADMIN_ONLY` need no separate non-member check:
 * neither a guest's nor a friend's `role` can ever be `BOARD`/`ADMIN` (nothing in this codebase
 * elevates a non-member's `Account.role` after creation), so [isPrivileged] and `role == ADMIN`
 * already exclude them transitively.
 */
fun CurrentMember.canAccessDocumentAtLevel(level: DocumentAccessLevel): Boolean =
    when (level) {
        DocumentAccessLevel.PUBLIC_MEMBERS -> status in MemberStatusSets.ORGANIZATION_MEMBER
        DocumentAccessLevel.BOARD_ONLY -> isPrivileged
        DocumentAccessLevel.ADMIN_ONLY -> role == AccountRole.ADMIN
    }
