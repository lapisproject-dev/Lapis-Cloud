package network.lapis.cloud.client

import io.kvision.i18n.tr
import io.kvision.utils.obj
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.fetch.Headers
import org.w3c.fetch.INCLUDE
import org.w3c.fetch.RequestCredentials
import org.w3c.fetch.RequestInit
import org.w3c.fetch.Response
import kotlin.js.JSON

private external interface LoginRequestBody {
    var email: String
    var password: String
}

private external interface PasswordResetRequestBody {
    var email: String
}

private external interface PasswordResetConfirmBody {
    var token: String
    var newPassword: String
}

private external interface FriendEmailVerifyBody {
    var token: String
}

private external interface EmailChangeConfirmBody {
    var token: String
    var password: String?
}

private external interface EmailChangeRevokeBody {
    var token: String
}

private external interface PrivilegedActionVetoBody {
    var token: String
}

/** Welle V1.9.57 -- what the objection endpoint answered; deliberately closed and free of server text. */
enum class PrivilegedActionVetoOutcome { SENT, RATE_LIMITED, FAILED }

/**
 * Welle V1.9.56 -- what the two address-change link endpoints answered. Deliberately a small closed set: the server's
 * own text is never shown (its body is one fixed code word, see `EmailChangeRoutes`), and a network failure is [FAILED],
 * distinct from a server-side [INVALID].
 */
enum class EmailChangeLinkOutcome { OK, CONFIRMED_PENDING, INVALID, WRONG_PASSWORD, UNAVAILABLE, RATE_LIMITED, FAILED }

/**
 * Mirrors `network.lapis.cloud.server.routes.AuthRoutes.kt` 1:1 -- login/logout/password-reset are
 * dedicated HTTP routes, not Kilua RPC (see `IAuthService` KDoc for why: these must be reachable
 * BEFORE any session exists). Hand-written `fetch()` calls with `credentials = include` set
 * explicitly on every one of them, since (unlike Kilua RPC's own `CallAgent`, which always sets
 * this) nothing else does that for a plain `window.fetch()` call. No kotlinx-serialization needed
 * for these small, fixed request shapes -- built via KVision's `obj {}` JS-object DSL plus
 * `JSON.stringify`, matching the "large/differently-shaped payload gets a dedicated HTTP route, not
 * RPC" reasoning the server-side KDoc already establishes.
 *
 * Every function here returns `null` on success, or a human-readable error message (the server's
 * own response body text, which is already account-enumeration-hardened -- see [AuthRoutes]'s own
 * "generic message" design) on failure. Login deliberately never differentiates a wrong email from
 * a wrong password on the client either -- it just surfaces whatever generic text the server sent.
 */
object AuthHttp {
    suspend fun login(
        email: String,
        password: String,
    ): String? {
        val body =
            obj<LoginRequestBody> {
                this.email = email
                this.password = password
            }
        val response = postJson("/api/auth/login", JSON.stringify(body))
        return if (response.ok) null else response.text().await().ifBlank { tr("Anmeldung fehlgeschlagen.") }
    }

    /**
     * Idempotent by server design (see `AuthRoutes.registerAuthRoutes` KDoc) -- the outcome is
     * deliberately ignored; the caller always proceeds to a logged-out client state regardless of
     * whether the request itself succeeded (e.g. offline), since the session cookie is cleared
     * client-side either way once [AppState.setSession] is called with `null`.
     */
    suspend fun logout() {
        runCatching {
            window
                .fetch(
                    "/api/auth/logout",
                    RequestInit(method = "POST", credentials = RequestCredentials.INCLUDE),
                ).await()
        }
    }

    suspend fun requestPasswordReset(email: String): String? {
        val body = obj<PasswordResetRequestBody> { this.email = email }
        val response = postJson("/api/auth/password-reset/request", JSON.stringify(body))
        return if (response.ok) null else response.text().await().ifBlank { tr("Anfrage fehlgeschlagen.") }
    }

    suspend fun confirmPasswordReset(
        token: String,
        newPassword: String,
    ): String? {
        val body =
            obj<PasswordResetConfirmBody> {
                this.token = token
                this.newPassword = newPassword
            }
        val response = postJson("/api/auth/password-reset/confirm", JSON.stringify(body))
        return if (response.ok) null else response.text().await().ifBlank { tr("Zurücksetzen fehlgeschlagen.") }
    }

    /**
     * V1.2.3 -- backs [renderVerifyEmailScreen]'s `#/verify-email?token=...` deep link (Option B of
     * the SMTP-Versand plan). Mirrors `POST /api/auth/friend/verify-email` 1:1, same shape as
     * [confirmPasswordReset] above.
     */
    suspend fun confirmFriendEmailVerification(token: String): String? {
        val body = obj<FriendEmailVerifyBody> { this.token = token }
        val response = postJson("/api/auth/friend/verify-email", JSON.stringify(body))
        return if (response.ok) null else response.text().await().ifBlank { tr("Bestätigung fehlgeschlagen.") }
    }

    /**
     * Welle V1.9.56 -- `POST /api/auth/email-change/confirm`. [password] is sent only for a proposal the owner accepts;
     * the ownership-proof links carry none. Only the status code and the fixed body code word are evaluated.
     */
    suspend fun confirmEmailChange(
        token: String,
        password: String?,
    ): EmailChangeLinkOutcome {
        val body =
            obj<EmailChangeConfirmBody> {
                this.token = token
                if (password != null) this.password = password
            }
        return emailChangeOutcome("/api/auth/email-change/confirm", JSON.stringify(body))
    }

    /** Welle V1.9.56 -- `POST /api/auth/email-change/revoke`, the reject link in the mail to the OLD address. */
    suspend fun revokeEmailChange(token: String): EmailChangeLinkOutcome {
        val body = obj<EmailChangeRevokeBody> { this.token = token }
        return emailChangeOutcome("/api/auth/email-change/revoke", JSON.stringify(body))
    }

    private suspend fun emailChangeOutcome(
        url: String,
        jsonBody: String,
    ): EmailChangeLinkOutcome =
        try {
            val response = postJson(url, jsonBody)
            when {
                response.status.toInt() == 202 -> EmailChangeLinkOutcome.CONFIRMED_PENDING
                response.ok -> EmailChangeLinkOutcome.OK
                response.status.toInt() == 429 -> EmailChangeLinkOutcome.RATE_LIMITED
                else ->
                    when (response.text().await().trim()) {
                        "invalid" -> EmailChangeLinkOutcome.INVALID
                        "wrong-password" -> EmailChangeLinkOutcome.WRONG_PASSWORD
                        "unavailable" -> EmailChangeLinkOutcome.UNAVAILABLE
                        else -> EmailChangeLinkOutcome.FAILED
                    }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            EmailChangeLinkOutcome.FAILED
        }

    /**
     * Welle V1.9.57 -- `POST /api/auth/privileged-action/veto`, the target's objection link of a four-eyes request. The server answers
     * `204` for EVERY token (valid, wrong, used, expired: no enumeration), so the only outcomes the client can tell apart are "sent",
     * "too many requests" and "failed"; the screen's text says only that the objection was transmitted.
     */
    suspend fun vetoPrivilegedAction(token: String): PrivilegedActionVetoOutcome {
        val body = obj<PrivilegedActionVetoBody> { this.token = token }
        return try {
            val response = postJson("/api/auth/privileged-action/veto", JSON.stringify(body))
            when {
                response.status.toInt() == 429 -> PrivilegedActionVetoOutcome.RATE_LIMITED
                response.ok -> PrivilegedActionVetoOutcome.SENT
                else -> PrivilegedActionVetoOutcome.FAILED
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            PrivilegedActionVetoOutcome.FAILED
        }
    }

    private suspend fun postJson(
        url: String,
        jsonBody: String,
    ): Response {
        val headers = Headers()
        headers.set("Content-Type", "application/json")
        return window
            .fetch(
                url,
                RequestInit(
                    method = "POST",
                    headers = headers,
                    body = jsonBody,
                    credentials = RequestCredentials.INCLUDE,
                ),
            ).await()
    }
}
