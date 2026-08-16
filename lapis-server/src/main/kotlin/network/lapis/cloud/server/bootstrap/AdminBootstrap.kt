package network.lapis.cloud.server.bootstrap

import io.github.oshai.kotlinlogging.KotlinLogging
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.PasswordPolicy
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.WeakPasswordException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

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
 * **Two modes, selected by whether `LAPIS_BOOTSTRAP_ADMIN_DISPLAY_NAME` is set:**
 * - **Existing row** (no display name given): [setInitialAdminPassword] only ever sets
 *   `account.password_hash` on a member/account row that some other process already created
 *   (registration, `createMemberDirect`, or historically a manual `INSERT`).
 * - **Genuinely fresh deployment** (display name given): [bootstrapFirstAdmin] creates the
 *   member+account row itself AND grants `ADMIN`, but ONLY when [MemberTable] is completely empty
 *   -- see that function's own KDoc for why this is deliberately narrower than "no admin exists
 *   yet". This closes the chicken-and-egg gap the original version of this tool left open: a fresh
 *   Postgres database had no way to get its very first member/account row at all without a manual
 *   `INSERT`, since [network.lapis.cloud.server.rpc.RegistrationService.createMemberDirect] (the
 *   normal way to mint a privileged account) itself requires an already-authenticated ADMIN/BOARD
 *   caller.
 *
 * Run either mode via the Gradle `bootstrapAdmin` task (see `build.gradle.kts`) or directly:
 * ```
 * # Fresh deployment, no rows exist yet -- creates the row AND grants ADMIN:
 * LAPIS_BOOTSTRAP_ADMIN_EMAIL=admin@example.org \
 * LAPIS_BOOTSTRAP_ADMIN_DISPLAY_NAME='Erika Musterfrau' \
 * LAPIS_BOOTSTRAP_ADMIN_PASSWORD='a strong, unique password' \
 *   java -cp <runtime classpath> network.lapis.cloud.server.bootstrap.AdminBootstrapKt
 *
 * # Existing row, e.g. one restored from a backup with no password set:
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
            PasswordPolicy.validate(newPassword = rawPassword, email = normalizedEmail)
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

    sealed interface BootstrapFirstAdminResult {
        data class Success(
            val email: String,
            val displayName: String,
        ) : BootstrapFirstAdminResult

        /**
         * [MemberTable] already has at least one row. Deliberately checked against the WHOLE table,
         * not "no ADMIN exists yet" -- this tool's only job is the very-first-admin-of-a-genuinely-
         * fresh-deployment case, so it must never be usable to inject a new ADMIN account into a
         * deployment that already has real member data (an admin-lockout-recovery tool is a
         * meaningfully different, more dangerous feature that needs its own dedicated design --
         * e.g. a re-usable emergency token -- not a side effect of this one).
         */
        data object NotEmpty : BootstrapFirstAdminResult

        data class WeakPassword(
            val reason: String,
        ) : BootstrapFirstAdminResult

        data class InvalidInput(
            val reason: String,
        ) : BootstrapFirstAdminResult
    }

    /**
     * Creates the very first member+account row in a genuinely fresh deployment and grants it
     * `ADMIN` -- closes the chicken-and-egg gap [setInitialAdminPassword] cannot: that function only
     * ever sets a password on a row that already exists, and every other way to mint an account in
     * this codebase ([network.lapis.cloud.server.rpc.RegistrationService.registerApplication] /
     * `createMemberDirect`) either lands as a pending `APPLICATION` application with no board yet able to
     * approve it, or itself requires an already-authenticated ADMIN/BOARD caller.
     *
     * **Refuses unless [MemberTable] is completely empty** -- see [BootstrapFirstAdminResult.NotEmpty]
     * KDoc for why this is the correct, narrower gate (not "no ADMIN exists yet"). For an *existing*
     * deployment that has simply lost its only ADMIN account, the correct fix is a dedicated recovery
     * mechanism, not this tool.
     *
     * [database] defaults to the ambient [transaction] database (`null` -- Exposed's own convention
     * for "whatever `Database.connect`/`DatabaseConfig.connect` last established as current"); tests
     * pass an isolated instance explicitly (see `TestDatabaseFactory` in the `backup` test package)
     * so the empty-table check is deterministic instead of depending on what other Spec classes
     * sharing the same test JVM happen to have left behind.
     *
     * **Concurrency**: the empty-check and both inserts happen in one transaction, but a plain,
     * unlocked `SELECT` there alone would still let two concurrent invocations both observe an
     * empty table before either commits (classic check-then-act TOCTOU) -- unlikely in practice
     * (this is a one-time, operator-run CLI, not a hot path), but a deploy script that retries after
     * a perceived timeout is a realistic enough way to trigger it, and the resulting "two ADMIN rows"
     * outcome would directly contradict [BootstrapFirstAdminResult.NotEmpty]'s own documented
     * invariant. Fixed the same way [network.lapis.cloud.server.audit.AuditLogRecorder]/
     * [network.lapis.cloud.server.security.PasswordResetTokenStore] serialize their own
     * genesis-singleton-row operations: `SELECT ... FOR UPDATE` on [OrganizationSettingsTable]'s
     * Flyway-seeded singleton row (guaranteed to exist from the very first migration, in every
     * environment, well before any [MemberTable] row does) BEFORE the empty-check, so a second
     * concurrent call blocks until the first commits, then correctly re-reads a non-empty table.
     *
     * **Logging/PII**: same house rule as [setInitialAdminPassword] -- never logs [rawPassword] or
     * the resulting hash.
     */
    fun bootstrapFirstAdmin(
        displayName: String,
        email: String,
        rawPassword: String,
        database: Database? = null,
    ): BootstrapFirstAdminResult {
        val trimmedDisplayName = displayName.trim()
        if (trimmedDisplayName.isBlank()) {
            return BootstrapFirstAdminResult.InvalidInput("displayName must not be blank")
        }
        val normalizedEmail = email.trim().lowercase()
        try {
            PasswordPolicy.validate(newPassword = rawPassword, email = normalizedEmail)
        } catch (e: WeakPasswordException) {
            return BootstrapFirstAdminResult.WeakPassword(e.message)
        }

        return transaction(database) {
            // Serializes concurrent bootstrapFirstAdmin calls against each other -- see class KDoc
            // "Concurrency". Locks, not reads, the row's contents; the seeded name is irrelevant here.
            OrganizationSettingsTable.selectAll().forUpdate().single()

            val alreadyHasMembers = MemberTable.selectAll().limit(1).any()
            if (alreadyHasMembers) {
                return@transaction BootstrapFirstAdminResult.NotEmpty
            }

            val memberId = Uuid.random()
            MemberTable.insert {
                it[id] = memberId
                it[MemberTable.displayName] = trimmedDisplayName
                it[MemberTable.email] = normalizedEmail
                it[status] = MemberStatus.ACTIVE
                it[joinedAt] = DbClock.nowLocalDateTime().date
                it[membershipTierId] = null
            }
            AccountTable.insert {
                it[id] = Uuid.random()
                it[AccountTable.memberId] = memberId
                it[role] = AccountRole.ADMIN
                it[passwordHash] = PasswordHasher.hash(rawPassword)
            }
            BootstrapFirstAdminResult.Success(email = normalizedEmail, displayName = trimmedDisplayName)
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
    val displayName = System.getenv("LAPIS_BOOTSTRAP_ADMIN_DISPLAY_NAME")
    val force = System.getenv("LAPIS_BOOTSTRAP_FORCE")?.equals("true", ignoreCase = true) == true

    DatabaseConfig.connect()

    // LAPIS_BOOTSTRAP_ADMIN_DISPLAY_NAME is the mode selector -- see class KDoc "Two modes".
    if (displayName != null) {
        when (val result = AdminBootstrap.bootstrapFirstAdmin(displayName = displayName, email = email, rawPassword = password)) {
            is AdminBootstrap.BootstrapFirstAdminResult.Success -> {
                logger.info { "First ADMIN created: '${result.email}' (${result.displayName})." }
            }
            is AdminBootstrap.BootstrapFirstAdminResult.NotEmpty -> {
                logger.error {
                    "Refusing: the member table is not empty. This tool only bootstraps a genuinely fresh " +
                        "deployment's very first admin -- for an existing deployment, sign in as an existing " +
                        "ADMIN/BOARD account and use the member administration screen instead."
                }
                kotlin.system.exitProcess(1)
            }
            is AdminBootstrap.BootstrapFirstAdminResult.WeakPassword -> {
                logger.error { "Rejected: ${result.reason}" }
                kotlin.system.exitProcess(1)
            }
            is AdminBootstrap.BootstrapFirstAdminResult.InvalidInput -> {
                logger.error { "Rejected: ${result.reason}" }
                kotlin.system.exitProcess(1)
            }
        }
        return
    }

    when (val result = AdminBootstrap.setInitialAdminPassword(email = email, rawPassword = password, force = force)) {
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
