package network.lapis.cloud.server.routes

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.util.date.GMTDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.PasswordResetMailer
import network.lapis.cloud.server.security.LoginRateLimiter
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.PasswordPolicy
import network.lapis.cloud.server.security.PasswordResetTokenStore
import network.lapis.cloud.server.security.SESSION_COOKIE_NAME
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.WeakPasswordException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

private val logger = KotlinLogging.logger {}

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class LoginResponse(
    val memberId: String,
    val displayName: String,
    val role: AccountRole,
)

@Serializable
data class PasswordResetRequestRequest(
    val email: String,
)

@Serializable
data class PasswordResetConfirmRequest(
    val token: String,
    val newPassword: String,
)

/**
 * Public login/logout HTTP endpoints (V0.7.1 Authentifizierung) — deliberately outside the Kilua
 * RPC layer, mounted BEFORE any authentication is required, the exact opposite of every RPC
 * service (which resolves the caller via `resolveCurrentMember` as its first step). See
 * [network.lapis.cloud.shared.rpc.IAuthService] KDoc for the full rationale.
 *
 * **Account-enumeration hardening**: an unknown email, a known email with the wrong password, AND
 * a known email whose `account.password_hash` is still `NULL` (a seeded-but-never-logged-in
 * account) all produce the IDENTICAL `401 Unauthorized` response — same status code, same body
 * text (`"Invalid credentials"`), computed via [PasswordHasher.verify]'s own timing-uniform
 * `storedHash == null` handling (see that function's KDoc). A caller can never learn from this
 * endpoint's response whether a given email is registered at all.
 *
 * **Session-fixation**: every successful login always calls [SessionStore.createSession] for a
 * BRAND-NEW token — an existing `lapis_session` cookie value the client happened to send along is
 * never read or reused as the new session's identity; the response simply overwrites it.
 *
 * **Cookie transport**: `HttpOnly` (no JS access, XSS-hardening) + `Secure` (gated by
 * [cookieSecure] — `true` by default, disable ONLY for local plaintext-HTTP dev) + `SameSite=Strict`
 * (CSRF-hardening; Ktor's [Cookie] has no dedicated field for this attribute, so it travels via
 * [Cookie.extensions] — see `io.ktor.http.Cookie` source). `SameSite=Strict` is this wave's
 * INTERIM CSRF control — a real double-submit CSRF token is deferred to the V0.7.3 UI wave (it
 * needs client-side changes this backend-only wave does not make); modern browsers already refuse
 * to attach a `SameSite=Strict` cookie to a cross-site request, which covers the classic CSRF
 * attack shape (forged cross-origin form/fetch) even without a token.
 *
 * **V0.7.2 login gate**: [MemberStatus.AUSGETRETEN]/[MemberStatus.ABGELEHNT] accounts are rejected
 * with the SAME generic `401 Invalid credentials` response as a wrong password -- no separate
 * status code or message, so a caller can never learn from this endpoint whether an email belongs
 * to a departed/rejected identity versus simply not existing. [MemberStatus.ANTRAG] (an applicant
 * checking on their still-pending status) and [MemberStatus.GAST] (currently unreachable in
 * practice -- no Gast identity/login path exists yet, but not excluded here on principle) may
 * still log in.
 *
 * **V0.7.2 password reset**: `/api/auth/password-reset/request`/`/api/auth/password-reset/confirm`
 * live here, not in `network.lapis.cloud.shared.rpc.IRegistrationService`, for the exact same
 * "reachable before any session exists" reason login/logout do (see
 * [network.lapis.cloud.shared.rpc.IAuthService] KDoc). See
 * [network.lapis.cloud.server.security.PasswordResetTokenStore] KDoc for the token-mechanics
 * contract and [network.lapis.cloud.server.mail.PasswordResetMailer] KDoc for why delivery is an
 * honest, disclosed non-op. `/request` always returns the IDENTICAL response whether or not
 * [PasswordResetRequestRequest.email] is registered (same account-enumeration posture as
 * `/api/auth/login`).
 */
fun Route.registerAuthRoutes(
    rateLimiter: LoginRateLimiter,
    cookieSecure: Boolean,
    passwordResetRateLimiter: LoginRateLimiter,
    passwordResetMailer: PasswordResetMailer,
) {
    post("/api/auth/login") {
        val request =
            runCatching { Json.decodeFromString(LoginRequest.serializer(), call.receiveText()) }.getOrNull()
        if (request == null || request.email.isBlank() || request.password.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, "email and password are required")
            return@post
        }

        val normalizedEmail = request.email.trim().lowercase()
        val emailKey = "email:$normalizedEmail"
        val ipKey = "ip:${call.request.local.remoteHost}"
        if (!rateLimiter.checkAllowed(emailKey) || !rateLimiter.checkAllowed(ipKey)) {
            call.respondText("Too many failed login attempts -- try again later", status = HttpStatusCode.TooManyRequests)
            return@post
        }

        val accountRow =
            transaction {
                (MemberTable innerJoin AccountTable)
                    .selectAll()
                    .where { MemberTable.email.lowerCase() eq normalizedEmail }
                    .singleOrNull()
            }

        // See class KDoc "Account-enumeration hardening" -- PasswordHasher.verify always runs a
        // real bcrypt comparison (against a fixed dummy hash if accountRow/passwordHash is null),
        // never short-circuits, so every rejection reason below takes the same code path.
        val passwordOk = PasswordHasher.verify(request.password, accountRow?.get(AccountTable.passwordHash))
        // V0.7.2 login gate -- see class KDoc "V0.7.2 login gate". Checked only AFTER a correct
        // password, so a wrong-password attempt against a departed/rejected account still takes
        // the exact same passwordOk==false path as any other wrong password (no extra branch that
        // could leak status via response timing).
        val statusBlocksLogin = accountRow?.get(MemberTable.status) in setOf(MemberStatus.AUSGETRETEN, MemberStatus.ABGELEHNT)
        if (accountRow == null || !passwordOk || statusBlocksLogin) {
            rateLimiter.recordFailure(emailKey)
            rateLimiter.recordFailure(ipKey)
            call.respondText("Invalid credentials", status = HttpStatusCode.Unauthorized)
            return@post
        }

        rateLimiter.reset(emailKey)
        rateLimiter.reset(ipKey)

        val memberId = accountRow[MemberTable.id]
        val issued = SessionStore.createSession(memberId)
        call.response.cookies.append(
            Cookie(
                name = SESSION_COOKIE_NAME,
                value = issued.rawToken,
                encoding = CookieEncoding.URI_ENCODING,
                maxAge = SessionStore.SESSION_TTL.inWholeSeconds.toInt(),
                path = "/",
                secure = cookieSecure,
                httpOnly = true,
                extensions = mapOf("SameSite" to "Strict"),
            ),
        )
        call.respond(
            LoginResponse(
                memberId = memberId.toString(),
                displayName = accountRow[MemberTable.displayName],
                role = accountRow[AccountTable.role],
            ),
        )
    }

    post("/api/auth/logout") {
        val rawToken = call.request.cookies[SESSION_COOKIE_NAME]
        if (rawToken != null) SessionStore.revoke(rawToken)
        // Idempotent by design -- an absent/unknown/already-revoked cookie still yields 204, same
        // as a successful logout. See SessionStore.revoke KDoc.
        call.response.cookies.append(
            Cookie(
                name = SESSION_COOKIE_NAME,
                value = "",
                path = "/",
                secure = cookieSecure,
                httpOnly = true,
                maxAge = 0,
                expires = GMTDate.START,
                extensions = mapOf("SameSite" to "Strict"),
            ),
        )
        call.respond(HttpStatusCode.NoContent)
    }

    post("/api/auth/password-reset/request") {
        val request =
            runCatching { Json.decodeFromString(PasswordResetRequestRequest.serializer(), call.receiveText()) }.getOrNull()
        if (request == null || request.email.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, "email is required")
            return@post
        }

        val normalizedEmail = request.email.trim().lowercase()
        val emailKey = "email:$normalizedEmail"
        val ipKey = "ip:${call.request.local.remoteHost}"
        if (!passwordResetRateLimiter.checkAllowed(emailKey) || !passwordResetRateLimiter.checkAllowed(ipKey)) {
            call.respondText("Too many requests -- try again later", status = HttpStatusCode.TooManyRequests)
            return@post
        }
        passwordResetRateLimiter.recordFailure(emailKey)
        passwordResetRateLimiter.recordFailure(ipKey)

        val accountRow =
            transaction {
                (MemberTable innerJoin AccountTable)
                    .selectAll()
                    .where { MemberTable.email.lowerCase() eq normalizedEmail }
                    .singleOrNull()
            }
        // See class KDoc "V0.7.2 password reset" -- IDENTICAL response whether or not the email
        // is registered. A token is only actually created/sent when accountRow != null.
        if (accountRow != null) {
            val rawToken = PasswordResetTokenStore.createToken(accountRow[MemberTable.id])
            passwordResetMailer.send(normalizedEmail, rawToken)
        }
        call.respond(HttpStatusCode.OK, "If this email is registered, a password-reset link has been sent.")
    }

    post("/api/auth/password-reset/confirm") {
        val request =
            runCatching { Json.decodeFromString(PasswordResetConfirmRequest.serializer(), call.receiveText()) }.getOrNull()
        if (request == null || request.token.isBlank() || request.newPassword.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, "token and newPassword are required")
            return@post
        }

        // Validate BEFORE actually consuming the token -- see PasswordResetTokenStore.peekMemberId
        // KDoc. A rejected newPassword must not burn an otherwise perfectly valid, unexpired
        // single-use token; the caller should be able to retry the SAME link with a stronger
        // password instead of being forced to request an entirely new reset email.
        val peekedMemberId = PasswordResetTokenStore.peekMemberId(request.token)
        if (peekedMemberId == null) {
            call.respondText("Invalid or expired token", status = HttpStatusCode.BadRequest)
            return@post
        }
        val email = transaction { MemberTable.selectAll().where { MemberTable.id eq peekedMemberId }.single()[MemberTable.email] }
        val validation = runCatching { PasswordPolicy.validate(request.newPassword, email) }
        if (validation.isFailure) {
            val message = (validation.exceptionOrNull() as? WeakPasswordException)?.message ?: "Weak password"
            call.respondText(message, status = HttpStatusCode.BadRequest)
            return@post
        }

        // Now actually claim the token -- see PasswordResetTokenStore.consumeToken KDoc "Single-use,
        // atomically". A concurrent consumer could in principle have claimed it between the peek
        // above and this call; consumeToken is still the sole atomic authority and returns null in
        // that case.
        val memberId = PasswordResetTokenStore.consumeToken(request.token)
        if (memberId == null) {
            call.respondText("Invalid or expired token", status = HttpStatusCode.BadRequest)
            return@post
        }
        val newHash = PasswordHasher.hash(request.newPassword)
        transaction {
            AccountTable.update({ AccountTable.memberId eq memberId }) {
                it[passwordHash] = newHash
            }
        }
        // Every session is revoked -- a password reset means the OLD credential (and any session
        // established under it) is no longer trusted, same reasoning IAuthService.changePassword
        // KDoc gives for its own OTHER-session revocation, except here there is no "caller's own
        // current session" to preserve: the caller of THIS endpoint is unauthenticated by design.
        SessionStore.revokeAllForMember(memberId)
        call.respond(HttpStatusCode.NoContent)
    }
}
