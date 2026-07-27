package network.lapis.cloud.server.security

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.ApplicationCall
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.domain.MemberStatus
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
 * [isGuest] (V0.8.2 OIDC-Gastzugang-Federation) is `true` iff this [CurrentMember] was resolved via
 * a [network.lapis.cloud.server.db.generated.MemberTable] row with
 * `status == `[network.lapis.cloud.shared.domain.MemberStatus.GAST]` -- see
 * [network.lapis.cloud.server.federation.OidcGuestMemberStore] KDoc for the full "guest = real
 * Member row" design decision. Existing `status`-checking gates
 * ([network.lapis.cloud.server.rpc.requireActiveMembership] and friends) already exclude
 * [network.lapis.cloud.shared.domain.MemberStatus.GAST] structurally and do not need this field --
 * it exists as a POSITIVE, greppable signal for any future call site that wants to special-case a
 * guest explicitly, rather than relying on "GAST fails requireActiveMembership" tribal knowledge.
 * [canAccessDocumentAtLevel] is exactly such a call site: `PUBLIC_MEMBERS`-level document access
 * requires `isGuest == false` (closed gap, see that function's KDoc -- V0.8.2 originally shipped
 * this role-only and flagged it as a known, deliberately-left-open gap; a later wave closed it).
 * `BOARD_ONLY`/`ADMIN_ONLY` never needed an explicit [isGuest] check to begin with: a guest's
 * `role` is always [network.lapis.cloud.shared.domain.AccountRole.MEMBER] (never `BOARD`/`ADMIN` --
 * no write path in this codebase elevates a guest's `Account.role` after creation), so
 * [isPrivileged] and `role == ADMIN` already excluded a guest transitively.
 */
data class CurrentMember(
    val memberId: Uuid,
    val role: AccountRole,
    val isGuest: Boolean = false,
)

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
            isGuest = row[MemberTable.status] == MemberStatus.GAST,
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
 * `PUBLIC_MEMBERS` means "visible to members of THIS organization" and therefore additionally
 * requires [CurrentMember.isGuest] to be `false` -- a federated OIDC guest (V0.8.2) always
 * resolves with `role = MEMBER` (see [network.lapis.cloud.server.federation.OidcGuestMemberStore])
 * but is not actually a member of the visited organization, so a bare role check alone would
 * wrongly grant PUBLIC_MEMBERS-tier internal-document access (statutes, meeting minutes, board
 * correspondence -- a fundamentally different content domain than the Timeline access the
 * Gastzugang concept actually describes for guests). `BOARD_ONLY`/`ADMIN_ONLY` need no separate
 * guest check: a guest's `role` can never be `BOARD`/`ADMIN` (nothing in this codebase elevates a
 * guest's `Account.role` after creation), so [isPrivileged] and `role == ADMIN` already exclude a
 * guest transitively.
 */
fun CurrentMember.canAccessDocumentAtLevel(level: DocumentAccessLevel): Boolean =
    when (level) {
        DocumentAccessLevel.PUBLIC_MEMBERS -> !isGuest
        DocumentAccessLevel.BOARD_ONLY -> isPrivileged
        DocumentAccessLevel.ADMIN_ONLY -> role == AccountRole.ADMIN
    }
