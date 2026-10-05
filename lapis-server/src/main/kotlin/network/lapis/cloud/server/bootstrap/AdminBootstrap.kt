package network.lapis.cloud.server.bootstrap

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.OrganizationSettingsTable
import network.lapis.cloud.server.mail.JakartaMailTransport
import network.lapis.cloud.server.mail.MailBranding
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTemplates
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.server.mail.PeerExecutedEvent
import network.lapis.cloud.server.mail.SmtpConfig
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.member.MemberRoleStatusMutations
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.PasswordPolicy
import network.lapis.cloud.server.security.PasswordResetTokenStore
import network.lapis.cloud.server.security.PeerGuard
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.security.forMemberUpdate
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AdminPasswordAction
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberChangeSnapshot
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MemberStatusTransitions
import network.lapis.cloud.shared.domain.PeerAction
import network.lapis.cloud.shared.domain.PeerActionAuditFacts
import network.lapis.cloud.shared.domain.PeerAuditEvent
import network.lapis.cloud.shared.rpc.LastAdminException
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
 * **Emergency actions (Welle V1.9.57 "Admin-Peer-Schutz")**: `LAPIS_BOOTSTRAP_ACTION` selects what the console does --
 * `reset-password` (the default, everything above; it now also ends every session and every outstanding reset token of the account and
 * writes an audit entry without an actor), `set-role` (`LAPIS_BOOTSTRAP_TARGET_EMAIL` + `LAPIS_BOOTSTRAP_ROLE`) and `set-status`
 * (`LAPIS_BOOTSTRAP_TARGET_EMAIL` + `LAPIS_BOOTSTRAP_STATUS=ACTIVE`, re-activation only). They exist because no signed-in path may take
 * an action against another administrator alone: this is the way out when the four-eyes rule cannot be met (one or two administrators)
 * or an administrator lost every means of signing in. The last-admin protection holds here too -- there is no console path to zero
 * login-capable administrators. Inside the container (the server image holds `java` and the jars under `/app/server/lib`) the
 * entry point is `network.lapis.cloud.server.bootstrap.AdminBootstrapKt` -- the exact `docker compose run` command is in
 * `deploy/example/README.adoc` and `docs/architecture/admin-peer-protection.adoc`.
 * **Not verified against a real container yet** -- a check item of the next staging deploy.
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

        var changedMemberId: Uuid? = null
        val result =
            transaction {
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
                // Welle V1.9.57 -- the operator's reset is audited like every other password change (no actor: the operator console
                // has no signed-in member; `operatorConsole` says so) and never carries the password or its hash.
                recordConsoleAudit(
                    memberRow = row,
                    role = row[AccountTable.role],
                    facts =
                        PeerActionAuditFacts(
                            event = PeerAuditEvent.EXECUTED,
                            action = PeerAction.TEMP_PASSWORD,
                            targetRole = row[AccountTable.role],
                            operatorConsole = true,
                        ),
                    adminPasswordAction = AdminPasswordAction.TEMPORARY_PASSWORD_SET,
                )
                changedMemberId = memberId
                BootstrapResult.Success(email = normalizedEmail, displayName = row[MemberTable.displayName])
            }
        // Welle V1.9.57 -- AFTER the commit, exactly like the signed-in paths: a reset means "this account may be compromised", so
        // every session and every outstanding reset token of the account ends. (Before this wave the console reset left both alive.)
        changedMemberId?.let { id ->
            SessionStore.revokeAllForMember(memberId = id)
            PasswordResetTokenStore.invalidateAllForMember(memberId = id)
        }
        return result
    }

    /** One MEMBER/UPDATE audit entry of the operator console (actor null). Call it LAST in the transaction. */
    private fun recordConsoleAudit(
        memberRow: org.jetbrains.exposed.v1.core.ResultRow,
        role: AccountRole?,
        facts: PeerActionAuditFacts,
        adminPasswordAction: AdminPasswordAction? = null,
        roleOverride: AccountRole? = role,
    ) {
        val before =
            MemberChangeSnapshot(
                displayNameChanged = false,
                emailChanged = false,
                status = memberRow[MemberTable.status],
                role = roleOverride,
            )
        val after = before.copy(peerAction = facts, adminPasswordAction = adminPasswordAction)
        AuditLogRecorder.record(
            actorMemberId = null,
            actorRole = null,
            entityType = AuditEntityType.MEMBER,
            entityId = memberRow[MemberTable.id],
            action = AuditAction.UPDATE,
            before = Json.encodeToString(MemberChangeSnapshot.serializer(), before),
            after = Json.encodeToString(MemberChangeSnapshot.serializer(), after),
        )
    }

    /** Result of [setRole] / [setStatus]. */
    sealed interface ConsoleChangeResult {
        data class Success(
            val email: String,
            val displayName: String,
            val mailTo: String,
            val event: PeerExecutedEvent,
        ) : ConsoleChangeResult

        data class NoChange(
            val email: String,
        ) : ConsoleChangeResult

        data class AccountNotFound(
            val email: String,
        ) : ConsoleChangeResult

        /** The change would leave no login-capable ADMIN -- there is deliberately NO console path to zero administrators. */
        data object LastAdmin : ConsoleChangeResult

        data class InvalidInput(
            val reason: String,
        ) : ConsoleChangeResult
    }

    /**
     * Emergency path (Welle V1.9.57): sets the ROLE of the member with [email]. Same union lock as the signed-in paths
     * ({target account} U {every ADMIN account}, id-ordered), the same last-admin protection (**hard**: never a console path to zero
     * login-capable administrators), `account.role_changed_at` stamped, an audit entry without an actor (`operatorConsole`). The notice to
     * the target goes out after the commit by the caller ([notifyFromConsole]). No network endpoint, no secret in the arguments.
     */
    fun setRole(
        email: String,
        newRole: AccountRole,
        database: Database? = null,
    ): ConsoleChangeResult {
        val normalizedEmail = email.trim().lowercase()
        return transaction(database) {
            val memberRow =
                MemberTable
                    .selectAll()
                    .where { MemberTable.email.lowerCase() eq normalizedEmail }
                    .forMemberUpdate()
                    .singleOrNull() ?: return@transaction ConsoleChangeResult.AccountNotFound(normalizedEmail)
            val targetId = memberRow[MemberTable.id]
            if (memberRow[MemberTable.anonymizedAt] !=
                null
            ) {
                return@transaction ConsoleChangeResult.InvalidInput("the member was anonymized")
            }
            val facts = PeerGuard.lockFactsAfterMemberLock(targetId = targetId, memberRow = memberRow, requesterId = null)
            val currentRole = facts.targetRole ?: return@transaction ConsoleChangeResult.AccountNotFound(normalizedEmail)
            if (currentRole == newRole) return@transaction ConsoleChangeResult.NoChange(normalizedEmail)
            try {
                MemberRoleStatusMutations.applyRoleChangeLocked(
                    actor = null,
                    targetId = targetId,
                    newRole = newRole,
                    currentRole = currentRole,
                    memberRow = memberRow,
                    lockedAccountRows = facts.lockedAccountRows,
                    now = DbClock.nowLocalDateTime(),
                    peerFacts =
                        PeerActionAuditFacts(
                            event = PeerAuditEvent.EXECUTED,
                            action = if (newRole == AccountRole.ADMIN) PeerAction.PROMOTE_TO_ADMIN else PeerAction.DEMOTE,
                            targetRole = currentRole,
                            operatorConsole = true,
                        ),
                )
            } catch (e: LastAdminException) {
                return@transaction ConsoleChangeResult.LastAdmin
            }
            ConsoleChangeResult.Success(
                email = normalizedEmail,
                displayName = memberRow[MemberTable.displayName],
                mailTo = memberRow[MemberTable.email],
                event = PeerExecutedEvent.ROLE_CHANGED,
            )
        }
    }

    /**
     * Emergency path (Welle V1.9.57): sets the STATUS of the member with [email] -- **to [MemberStatus.ACTIVE] only**. The emergency this
     * exists for is an administrator who got blocked and cannot unblock themselves; blocking is not an emergency (any administrator can
     * do it, ADMIN targets through the four-eyes approval) and ending a membership needs a signed-in person to attribute the committee,
     * mandate and officer cleanup to. Same union lock, same transition table, same audit as the signed-in path
     * ([MemberRoleStatusMutations.applyStatusChangeLocked]); the regional-chapter rule is deliberately not enforced (the operator decides).
     */
    fun setStatus(
        email: String,
        newStatus: MemberStatus,
        database: Database? = null,
    ): ConsoleChangeResult {
        if (newStatus != MemberStatus.ACTIVE) {
            return ConsoleChangeResult.InvalidInput("the console only re-activates (ACTIVE); other statuses need a signed-in administrator")
        }
        val normalizedEmail = email.trim().lowercase()
        return transaction(database) {
            val memberRow =
                MemberTable
                    .selectAll()
                    .where { MemberTable.email.lowerCase() eq normalizedEmail }
                    .forMemberUpdate()
                    .singleOrNull() ?: return@transaction ConsoleChangeResult.AccountNotFound(normalizedEmail)
            val targetId = memberRow[MemberTable.id]
            if (memberRow[MemberTable.anonymizedAt] !=
                null
            ) {
                return@transaction ConsoleChangeResult.InvalidInput("the member was anonymized")
            }
            val from = memberRow[MemberTable.status]
            if (from == newStatus) return@transaction ConsoleChangeResult.NoChange(normalizedEmail)
            if (newStatus !in MemberStatusTransitions.allowedTargets(from)) {
                return@transaction ConsoleChangeResult.InvalidInput("the transition from $from to $newStatus is not allowed")
            }
            val facts = PeerGuard.lockFactsAfterMemberLock(targetId = targetId, memberRow = memberRow, requesterId = null)
            MemberRoleStatusMutations.applyStatusChangeLocked(
                actor = null,
                targetId = targetId,
                newStatus = newStatus,
                trimmedReason = null,
                dateOfDeath = null,
                row = memberRow,
                existingRole = facts.targetRole,
                lockedAccountRows = facts.lockedAccountRows,
                now = DbClock.nowLocalDateTime(),
                regionalChapterEnforced = false,
                peerFacts =
                    PeerActionAuditFacts(
                        event = PeerAuditEvent.EXECUTED,
                        action = PeerAction.NON_BLOCKING_STATUS,
                        targetRole = facts.targetRole,
                        operatorConsole = true,
                    ),
            )
            ConsoleChangeResult.Success(
                email = normalizedEmail,
                displayName = memberRow[MemberTable.displayName],
                mailTo = memberRow[MemberTable.email],
                event = PeerExecutedEvent.STATUS_CHANGED,
            )
        }
    }

    /**
     * Best-effort notice to the target after a console change, only when SMTP is configured (the console process has no mail queue:
     * the message goes straight through the transport, synchronously, so it is out before the JVM exits). Returns a one-line outcome
     * for the console. Never throws, never logs an address or a token.
     */
    internal fun notifyFromConsole(
        change: ConsoleChangeResult.Success,
        smtpConfigState: SmtpConfigState = SmtpConfig.load(),
        transport: MailTransport? = null,
    ): String {
        if (smtpConfigState !is SmtpConfigState.Configured &&
            transport == null
        ) {
            return "no SMTP configured: the target was NOT notified by mail"
        }
        val branding =
            when (smtpConfigState) {
                is SmtpConfigState.Configured ->
                    MailBranding(fromDisplayName = smtpConfigState.config.fromDisplayName, replyTo = smtpConfigState.config.replyTo)
                else -> MailBranding.notConfigured()
            }
        val mail =
            MailTemplates.peerExecutedForTarget(
                event = change.event,
                actorName = "Betreiberkonsole / operator console",
                occurredAt = DbClock.nowLocalDateTime(),
                branding = branding,
            )
        val effective =
            transport ?: (smtpConfigState as? SmtpConfigState.Configured)?.let { JakartaMailTransport(config = it.config) }
                ?: return "no SMTP configured: the target was NOT notified by mail"
        val outcome =
            runCatching {
                runBlocking {
                    effective.send(
                        to = change.mailTo,
                        subject = mail.subject,
                        plainTextBody = mail.plainText,
                        htmlBody = mail.html,
                    )
                }
            }.getOrNull()
        return when (outcome) {
            is MailSendOutcome.Sent -> "the target was notified by mail"
            else -> "the notice mail to the target could NOT be sent"
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
                it[joinedAt] = OrganizationTimeZone.today()
                it[membershipTierId] = null
            }
            AccountTable.insert {
                it[id] = Uuid.random()
                it[AccountTable.memberId] = memberId
                it[role] = AccountRole.ADMIN
                it[roleChangedAt] = DbClock.nowLocalDateTime()
                it[passwordHash] = PasswordHasher.hash(rawPassword)
            }
            BootstrapFirstAdminResult.Success(email = normalizedEmail, displayName = trimmedDisplayName)
        }
    }
}

fun main() {
    val action =
        System
            .getenv("LAPIS_BOOTSTRAP_ACTION")
            ?.trim()
            ?.lowercase()
            ?.ifEmpty { null } ?: "reset-password"
    when (action) {
        "reset-password" -> mainResetPassword()
        "set-role" -> mainSetRole()
        "set-status" -> mainSetStatus()
        else -> {
            logger.error { "Unknown LAPIS_BOOTSTRAP_ACTION '$action' -- expected reset-password (default), set-role or set-status." }
            kotlin.system.exitProcess(1)
        }
    }
}

private fun reportConsoleChange(result: AdminBootstrap.ConsoleChangeResult) {
    when (result) {
        is AdminBootstrap.ConsoleChangeResult.Success -> {
            logger.info { "Done for '${result.email}' (${result.displayName}); ${AdminBootstrap.notifyFromConsole(change = result)}." }
        }
        is AdminBootstrap.ConsoleChangeResult.NoChange -> logger.info { "Nothing to do: '${result.email}' already has this value." }
        is AdminBootstrap.ConsoleChangeResult.AccountNotFound -> {
            logger.error { "No member/account found for '${result.email}'." }
            kotlin.system.exitProcess(1)
        }
        is AdminBootstrap.ConsoleChangeResult.LastAdmin -> {
            logger.error {
                "Refusing: the change would leave no login-capable ADMIN. There is deliberately no console path to zero administrators."
            }
            kotlin.system.exitProcess(1)
        }
        is AdminBootstrap.ConsoleChangeResult.InvalidInput -> {
            logger.error { "Rejected: ${result.reason}" }
            kotlin.system.exitProcess(1)
        }
    }
}

private fun mainSetRole() {
    val email =
        System.getenv("LAPIS_BOOTSTRAP_TARGET_EMAIL") ?: error("LAPIS_BOOTSTRAP_TARGET_EMAIL must be set")
    val role =
        runCatching {
            AccountRole.valueOf(
                System
                    .getenv("LAPIS_BOOTSTRAP_ROLE")
                    ?.trim()
                    ?.uppercase()
                    .orEmpty(),
            )
        }.getOrElse { error("LAPIS_BOOTSTRAP_ROLE must be one of ${AccountRole.entries.joinToString()}") }
    DatabaseConfig.connect()
    reportConsoleChange(AdminBootstrap.setRole(email = email, newRole = role))
}

private fun mainSetStatus() {
    val email =
        System.getenv("LAPIS_BOOTSTRAP_TARGET_EMAIL") ?: error("LAPIS_BOOTSTRAP_TARGET_EMAIL must be set")
    val status =
        runCatching {
            MemberStatus.valueOf(
                System
                    .getenv("LAPIS_BOOTSTRAP_STATUS")
                    ?.trim()
                    ?.uppercase()
                    .orEmpty(),
            )
        }.getOrElse {
            error(
                "LAPIS_BOOTSTRAP_STATUS must be one of ${MemberStatus.entries.joinToString()} (the console accepts ACTIVE only)",
            )
        }
    DatabaseConfig.connect()
    reportConsoleChange(AdminBootstrap.setStatus(email = email, newStatus = status))
}

private fun mainResetPassword() {
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
