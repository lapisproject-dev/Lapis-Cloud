package network.lapis.cloud.server.bootstrap

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.PasswordPolicy
import network.lapis.cloud.shared.rpc.WeakPasswordException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

private val logger = KotlinLogging.logger {}

/**
 * One-time, operator-run CLI to give an existing member account a real password against a REAL
 * (Postgres) deployment (V0.7.1 Authentifizierung) -- solves the bootstrap problem this wave's
 * planning identified: there is no member-onboarding workflow yet (V0.7.2), so a fresh production
 * database has member/account rows only if something inserted them directly, and none of them can
 * have a password set via the normal, session-gated [network.lapis.cloud.server.rpc.AuthService.changePassword]
 * RPC method, because that itself requires an already-valid session -- a chicken-and-egg problem
 * for the very first login of a fresh deployment.
 *
 * **Deliberately NOT a network-reachable endpoint.** There is no "first login sets a password" HTTP
 * route anywhere in this codebase, and there must never be one -- that shape is an unauthenticated,
 * self-service admin-creation backdoor reachable by anyone who can reach the login page first. This
 * class is a plain `main()` invoked from a shell with direct access to the deployment's environment
 * (`LAPIS_DB_URL` etc.) -- the same trust boundary as running a one-off `psql` command against the
 * production database, not a new attack surface.
 *
 * **Requires the member/account row to already exist** (email lookup against [MemberTable] joined
 * to [AccountTable]) -- creating that row is still a manual `INSERT` (or a future V0.7.2
 * registration flow) until this codebase has a real onboarding workflow; this tool only ever sets
 * `account.password_hash` on a row that is already there. Run it via the Gradle `bootstrapAdmin`
 * task (see `build.gradle.kts`) or directly:
 * ```
 * LAPIS_BOOTSTRAP_ADMIN_EMAIL=admin@example.org \
 * LAPIS_BOOTSTRAP_ADMIN_PASSWORD='a strong, unique password' \
 *   java -cp <runtime classpath> network.lapis.cloud.server.bootstrap.AdminBootstrapKt
 * ```
 * The password is read from an environment variable, never a CLI argument (which would leak into
 * shell history / `ps` output) and never logged (see [setInitialAdminPassword] "Logging/PII").
 */
object AdminBootstrap {
    sealed interface BootstrapResult {
        data class Success(
            val email: String,
            val displayName: String,
        ) : BootstrapResult

        data class AccountNotFound(
            val email: String,
        ) : BootstrapResult

        data class AlreadyHasPassword(
            val email: String,
        ) : BootstrapResult

        data class WeakPassword(
            val reason: String,
        ) : BootstrapResult
    }

    /**
     * Sets `account.password_hash` for the member with [email] (case-insensitive lookup, mirroring
     * `registerAuthRoutes`' own login lookup) to a fresh bcrypt hash of [rawPassword]. Refuses to
     * overwrite an account that already has a password set unless [force] is `true` -- an
     * already-initialized account is not this tool's business to silently reset (use
     * [network.lapis.cloud.server.rpc.AuthService.changePassword] for a normal password change, or
     * pass `force = true` deliberately for a genuine operator-initiated reset).
     *
     * **Logging/PII**: never logs [rawPassword] or the resulting hash, only the outcome and the
     * (non-secret) email/display name -- same standing house rule every other security-relevant
     * class in this package follows.
     */
    fun setInitialAdminPassword(
        email: String,
        rawPassword: String,
        force: Boolean = false,
    ): BootstrapResult {
        val normalizedEmail = email.trim().lowercase()
        try {
            PasswordPolicy.validate(rawPassword, normalizedEmail)
        } catch (e: WeakPasswordException) {
            return BootstrapResult.WeakPassword(e.message)
        }

        return transaction {
            val row =
                (MemberTable innerJoin AccountTable)
                    .selectAll()
                    .where { MemberTable.email.lowerCase() eq normalizedEmail }
                    .singleOrNull()
                    ?: return@transaction BootstrapResult.AccountNotFound(normalizedEmail)

            val alreadyHasPassword = row[AccountTable.passwordHash] != null
            if (alreadyHasPassword && !force) {
                return@transaction BootstrapResult.AlreadyHasPassword(normalizedEmail)
            }

            val memberId = row[MemberTable.id]
            val newHash = PasswordHasher.hash(rawPassword)
            AccountTable.update({ AccountTable.memberId eq memberId }) {
                it[passwordHash] = newHash
            }
            BootstrapResult.Success(email = normalizedEmail, displayName = row[MemberTable.displayName])
        }
    }
}

fun main() {
    val email =
        System.getenv("LAPIS_BOOTSTRAP_ADMIN_EMAIL")
            ?: error("LAPIS_BOOTSTRAP_ADMIN_EMAIL must be set")
    val password =
        System.getenv("LAPIS_BOOTSTRAP_ADMIN_PASSWORD")
            ?: error("LAPIS_BOOTSTRAP_ADMIN_PASSWORD must be set")
    val force = System.getenv("LAPIS_BOOTSTRAP_FORCE")?.equals("true", ignoreCase = true) == true

    DatabaseConfig.connect()
    when (val result = AdminBootstrap.setInitialAdminPassword(email, password, force)) {
        is AdminBootstrap.BootstrapResult.Success -> {
            logger.info { "Password set for '${result.email}' (${result.displayName})." }
        }
        is AdminBootstrap.BootstrapResult.AccountNotFound -> {
            logger.error { "No member/account found for '${result.email}' -- create the row first, then re-run." }
            kotlin.system.exitProcess(1)
        }
        is AdminBootstrap.BootstrapResult.AlreadyHasPassword -> {
            logger.error {
                "'${result.email}' already has a password set -- re-run with LAPIS_BOOTSTRAP_FORCE=true to overwrite it deliberately."
            }
            kotlin.system.exitProcess(1)
        }
        is AdminBootstrap.BootstrapResult.WeakPassword -> {
            logger.error { "Rejected: ${result.reason}" }
            kotlin.system.exitProcess(1)
        }
    }
}
