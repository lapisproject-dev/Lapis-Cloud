package network.lapis.cloud.server.bootstrap

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.mail.MailSendOutcome
import network.lapis.cloud.server.mail.MailTransport
import network.lapis.cloud.server.mail.PeerExecutedEvent
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.member.PEER_PASSWORD
import network.lapis.cloud.server.member.PeerFixture
import network.lapis.cloud.server.security.PasswordHasher
import network.lapis.cloud.server.security.PasswordResetTokenStore
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.uuid.Uuid

private const val NEW_STRONG_PASSWORD = "an-even-stronger-console-password-9"

/**
 * Welle V1.9.57 -- the operator console as the way out of the four-eyes rule: `reset-password` ends sessions and reset tokens and is
 * audited, `set-role` / `set-status` run through the same union lock and last-admin protection as the signed-in paths (hard: never
 * zero login-capable administrators), and nothing here has an actor. The unchanged old call (no `LAPIS_BOOTSTRAP_ACTION`) is covered
 * by [AdminBootstrapTest].
 */
class AdminBootstrapEmergencyTest :
    FunSpec({
        val fx = PeerFixture()

        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }
        beforeTest { fx.isolateAdmins() }
        afterTest { fx.restoreAdmins() }

        fun emailOf(id: Uuid): String =
            transaction {
                network.lapis.cloud.server.db.generated.MemberTable
                    .selectAll()
                    .where { network.lapis.cloud.server.db.generated.MemberTable.id eq id }
                    .single()[network.lapis.cloud.server.db.generated.MemberTable.email]
            }

        fun consoleAudits(id: Uuid): List<String> =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityId eq id) and
                            (AuditLogEntryTable.entityType eq AuditEntityType.MEMBER) and
                            AuditLogEntryTable.actorMemberId.isNull()
                    }.map { it[AuditLogEntryTable.afterSnapshot] ?: "" }
            }

        test(
            "reset-password: the new password works, every session and reset token of the account ends, the audit entry has no actor and no secret",
        ) {
            val target = fx.admin()
            fx.admin()
            val session = SessionStore.createSession(target)
            val token = PasswordResetTokenStore.createToken(target)
            val result = AdminBootstrap.setInitialAdminPassword(email = emailOf(target), rawPassword = NEW_STRONG_PASSWORD, force = true)
            (result is AdminBootstrap.BootstrapResult.Success) shouldBe true
            PasswordHasher.verify(rawPassword = NEW_STRONG_PASSWORD, storedHash = fx.passwordHashOf(target)) shouldBe true
            PasswordHasher.verify(rawPassword = PEER_PASSWORD, storedHash = fx.passwordHashOf(target)) shouldBe false
            SessionStore.resolve(session.rawToken) shouldBe null
            PasswordResetTokenStore.peekMemberId(token) shouldBe null
            val audits = consoleAudits(target)
            audits shouldHaveSize 1
            audits.single() shouldContain "\"operatorConsole\":true"
            audits.single() shouldContain "\"event\":\"EXECUTED\""
            audits.single() shouldNotContain NEW_STRONG_PASSWORD
            // role_changed_at is untouched by a password reset
            fx.roleChangedAtOf(target) shouldBe null
        }

        test("reset-password without force still refuses an account that has a password and changes nothing (old behaviour)") {
            val target = fx.admin()
            val before = fx.passwordHashOf(target)
            val session = SessionStore.createSession(target)
            val result = AdminBootstrap.setInitialAdminPassword(email = emailOf(target), rawPassword = NEW_STRONG_PASSWORD, force = false)
            (result is AdminBootstrap.BootstrapResult.AlreadyHasPassword) shouldBe true
            fx.passwordHashOf(target) shouldBe before
            (SessionStore.resolve(session.rawToken) != null) shouldBe true
            consoleAudits(target).size shouldBe 0
        }

        test("set-role: demotes an ADMIN while another login-capable ADMIN remains, stamps role_changed_at, audits without an actor") {
            val keep = fx.admin()
            val target = fx.admin()
            val result = AdminBootstrap.setRole(email = emailOf(target), newRole = AccountRole.MEMBER)
            (result is AdminBootstrap.ConsoleChangeResult.Success) shouldBe true
            fx.roleOf(target) shouldBe AccountRole.MEMBER
            (fx.roleChangedAtOf(target) != null) shouldBe true
            fx.roleOf(keep) shouldBe AccountRole.ADMIN
            val audit = consoleAudits(target).single()
            audit shouldContain "\"operatorConsole\":true"
            audit shouldContain "\"action\":\"DEMOTE\""
        }

        test("set-role: the last login-capable ADMIN cannot be demoted by the console either -- no path to zero administrators") {
            val only = fx.admin()
            val blocked = fx.admin(status = MemberStatus.WITHDRAWN)
            AdminBootstrap.setRole(email = emailOf(only), newRole = AccountRole.MEMBER) shouldBe
                AdminBootstrap.ConsoleChangeResult.LastAdmin
            fx.roleOf(only) shouldBe AccountRole.ADMIN
            // the blocked ADMIN does not count as "remaining"
            (blocked != only) shouldBe true
            consoleAudits(only).size shouldBe 0
        }

        test("set-role: promoting works (and is audited as PROMOTE_TO_ADMIN), no change and unknown e-mail are reported, never thrown") {
            val plain = fx.member(role = AccountRole.BOARD)
            val promoted = AdminBootstrap.setRole(email = emailOf(plain), newRole = AccountRole.ADMIN)
            (promoted is AdminBootstrap.ConsoleChangeResult.Success) shouldBe true
            fx.roleOf(plain) shouldBe AccountRole.ADMIN
            consoleAudits(plain).single() shouldContain "\"action\":\"PROMOTE_TO_ADMIN\""
            (
                AdminBootstrap.setRole(
                    email = emailOf(plain),
                    newRole = AccountRole.ADMIN,
                ) is AdminBootstrap.ConsoleChangeResult.NoChange
            ) shouldBe
                true
            (
                AdminBootstrap.setRole(email = "nobody-${Uuid.random()}@example.org", newRole = AccountRole.ADMIN) is
                    AdminBootstrap.ConsoleChangeResult.AccountNotFound
            ) shouldBe true
            val accountless = fx.member(role = null)
            (
                AdminBootstrap.setRole(
                    email = emailOf(accountless),
                    newRole = AccountRole.ADMIN,
                ) is AdminBootstrap.ConsoleChangeResult.AccountNotFound
            ) shouldBe
                true
        }

        test(
            "set-status: re-activates a blocked administrator; every other target status is refused (the console is not a blocking tool)",
        ) {
            fx.admin()
            val blocked = fx.admin(status = MemberStatus.DONOR)
            val result = AdminBootstrap.setStatus(email = emailOf(blocked), newStatus = MemberStatus.ACTIVE)
            (result is AdminBootstrap.ConsoleChangeResult.Success) shouldBe true
            fx.statusOf(blocked) shouldBe MemberStatus.ACTIVE
            consoleAudits(blocked).single() shouldContain "\"operatorConsole\":true"
            listOf(MemberStatus.WITHDRAWN, MemberStatus.REJECTED, MemberStatus.DECEASED, MemberStatus.DONOR, MemberStatus.FRIEND).forEach {
                (
                    AdminBootstrap.setStatus(
                        email = emailOf(blocked),
                        newStatus = it,
                    ) is AdminBootstrap.ConsoleChangeResult.InvalidInput
                ) shouldBe
                    true
            }
            (
                AdminBootstrap.setStatus(
                    email = emailOf(blocked),
                    newStatus = MemberStatus.ACTIVE,
                ) is AdminBootstrap.ConsoleChangeResult.NoChange
            ) shouldBe
                true
            fx.statusOf(blocked) shouldBe MemberStatus.ACTIVE
        }

        test("the notice mail goes straight through the transport; without SMTP the console says the target was NOT notified") {
            val target = fx.admin()
            fx.admin()
            val change =
                AdminBootstrap.setRole(
                    email = emailOf(target),
                    newRole = AccountRole.BOARD,
                ) as AdminBootstrap.ConsoleChangeResult.Success
            change.event shouldBe PeerExecutedEvent.ROLE_CHANGED
            val sent = CopyOnWriteArrayList<String>()
            val transport =
                object : MailTransport {
                    override suspend fun send(
                        to: String,
                        subject: String,
                        plainTextBody: String,
                        htmlBody: String,
                    ): MailSendOutcome {
                        sent += "$to|$subject|$plainTextBody"
                        return MailSendOutcome.Sent
                    }
                }
            val line =
                AdminBootstrap.notifyFromConsole(
                    change = change,
                    smtpConfigState = SmtpConfigState.NotConfigured,
                    transport = transport,
                )
            line shouldContain "notified by mail"
            sent shouldHaveSize 1
            sent.single() shouldContain emailOf(target)
            sent.single() shouldContain "Betreiberkonsole"
            AdminBootstrap.notifyFromConsole(change = change, smtpConfigState = SmtpConfigState.NotConfigured) shouldContain "NOT notified"
        }
    })
