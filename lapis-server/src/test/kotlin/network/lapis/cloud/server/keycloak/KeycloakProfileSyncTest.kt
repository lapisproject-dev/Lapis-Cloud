package network.lapis.cloud.server.keycloak

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MemberEmailChangeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.member.EmailChangeFixture
import network.lapis.cloud.server.member.EmailChangeStore
import network.lapis.cloud.server.member.KeycloakProvisioningNotifier
import network.lapis.cloud.server.member.configuredSmtp
import network.lapis.cloud.server.security.PasswordResetTokenStore
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.EmailChangeKind
import network.lapis.cloud.shared.domain.EmailChangeStatus
import network.lapis.cloud.shared.domain.KeycloakIdpAuditEvent
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/** Welle V1.9.73 -- the opt-in Keycloak profile sync against a real database (H2 and PostgreSQL). */
abstract class KeycloakProfileSyncScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fixture = EmailChangeFixture()

        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterSpec {
            fixture.cleanUp()
            db.deactivate()
        }

        fun sync(
            mailer: RecordingProvisioningMailer = RecordingProvisioningMailer(),
            smtp: SmtpConfigState = configuredSmtp(),
        ) = KeycloakProfileSync(notifier = KeycloakProvisioningNotifier(mailer = mailer, smtpConfigState = smtp))

        fun KeycloakProfileSync.login(
            memberId: Uuid,
            email: String,
            name: String? = null,
            verified: Boolean = true,
        ) = syncOnLogin(
            memberId = memberId,
            subject = "sub-$memberId",
            rawEmail = email,
            emailVerified = verified,
            claims = idClaims(email = email, name = name, groups = null),
        )

        fun displayNameOf(id: Uuid) =
            transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single()[MemberTable.displayName] }

        fun changes(id: Uuid) = fixture.rowsOf(id)

        test("the display name is taken over (any role), with a flag-only audit entry") {
            val id = fixture.member(displayName = "Alter Name", role = AccountRole.TREASURER)
            val mail = fixture.emailOf(id)
            val result = sync().login(id, email = mail, name = "Neuer Name")
            result.nameChanged shouldBe true
            result.emailChanged shouldBe false
            displayNameOf(id) shouldBe "Neuer Name"
            val audit = memberAuditJson(id)
            audit.size shouldBe 1
            audit.single() shouldContain "NAME_SYNCED"
            audit.single() shouldContain "\"displayNameChanged\":true"
            audit.single() shouldNotContain "Neuer Name"
            audit.single() shouldNotContain "Alter Name"
        }

        test("nothing differs: no change, no audit entry, no mail; a missing name changes nothing") {
            val id = fixture.member(displayName = "Gleich")
            val mailer = RecordingProvisioningMailer()
            val result = sync(mailer).login(id, email = fixture.emailOf(id).uppercase(), name = "Gleich")
            result shouldBe KeycloakProfileSync.Result(nameChanged = false, emailChanged = false, skipped = null)
            sync().login(id, email = fixture.emailOf(id), name = null).nameChanged shouldBe false
            memberAuditJson(id) shouldBe emptyList()
            mailer.synced shouldBe emptyList()
        }

        test("a verified, free address is applied through the change lifecycle; sessions and reset tokens end; the OLD address is warned") {
            val id = fixture.member(role = AccountRole.MEMBER)
            val oldMail = fixture.emailOf(id)
            val newMail = uniqueEmail("neu")
            SessionStore.createSession(id)
            val resetToken = PasswordResetTokenStore.createToken(id)
            val mailer = RecordingProvisioningMailer()

            val result = sync(mailer).login(id, email = newMail.uppercase(), name = null)

            result.emailChanged shouldBe true
            fixture.emailOf(id) shouldBe newMail
            (fixture.verifiedAtOf(id) != null) shouldBe true
            val row = changes(id).single()
            row[MemberEmailChangeTable.kind] shouldBe EmailChangeKind.IDP_SYNC.name
            row[MemberEmailChangeTable.status] shouldBe EmailChangeStatus.APPLIED.name
            row[MemberEmailChangeTable.pendingEmail] shouldBe newMail
            row[MemberEmailChangeTable.requestedBy] shouldBe null
            row[MemberEmailChangeTable.openMemberId] shouldBe null
            fixture.liveSessions(id) shouldBe 0
            PasswordResetTokenStore.peekMemberId(resetToken) shouldBe null
            mailer.synced.size shouldBe 1
            mailer.synced.single().email shouldBe oldMail
            mailer.synced.single().maskedNew shouldNotContain newMail
            mailer.synced.single().maskedNew shouldContain "@example.org"

            val audit = memberAuditJson(id).single()
            audit shouldContain "EMAIL_SYNCED"
            audit shouldContain row[MemberEmailChangeTable.id].toString()
            audit shouldContain "\"emailChanged\":true"
            audit shouldContain "IDP_SYNC"
            // the unerasable log carries the change id, never an address
            audit shouldNotContain newMail
            audit shouldNotContain oldMail
        }

        test("an unverified address is NOT taken over: audit hint only, login unaffected") {
            val id = fixture.member()
            val oldMail = fixture.emailOf(id)
            val result = sync().login(id, email = uniqueEmail(), verified = false)
            result.emailChanged shouldBe false
            result.skipped shouldBe KeycloakIdpAuditEvent.EMAIL_SYNC_SKIPPED_UNVERIFIED
            fixture.emailOf(id) shouldBe oldMail
            changes(id) shouldBe emptyList()
            memberAuditJson(id).single() shouldContain "EMAIL_SYNC_SKIPPED_UNVERIFIED"
        }

        test("BOARD, TREASURER and ADMIN keep the local address; the name is still taken over") {
            listOf(AccountRole.BOARD, AccountRole.TREASURER, AccountRole.ADMIN).forEach { role ->
                val id = fixture.member(role = role, displayName = "Vorher")
                val oldMail = fixture.emailOf(id)
                val newMail = uniqueEmail("prot")
                val mailer = RecordingProvisioningMailer()
                val result = sync(mailer).login(id, email = newMail, name = "Nachher")
                result.skipped shouldBe KeycloakIdpAuditEvent.EMAIL_SYNC_SKIPPED_PROTECTED_ROLE
                result.nameChanged shouldBe true
                fixture.emailOf(id) shouldBe oldMail
                displayNameOf(id) shouldBe "Nachher"
                changes(id) shouldBe emptyList()
                mailer.synced shouldBe emptyList()
                val audit = memberAuditJson(id)
                audit.size shouldBe 2
                audit.joinToString() shouldContain "EMAIL_SYNC_SKIPPED_PROTECTED_ROLE"
                audit.joinToString() shouldNotContain newMail
            }
        }

        test("an address that belongs to another member is not taken over, and leaves no change row") {
            val other = fixture.member()
            val id = fixture.member()
            val oldMail = fixture.emailOf(id)
            val result = sync().login(id, email = fixture.emailOf(other).uppercase())
            result.skipped shouldBe KeycloakIdpAuditEvent.EMAIL_SYNC_SKIPPED_COLLISION
            fixture.emailOf(id) shouldBe oldMail
            changes(id) shouldBe emptyList()
            memberAuditJson(id).single() shouldContain "EMAIL_SYNC_SKIPPED_COLLISION"
        }

        test("an open address change (V1.9.56 / V1.9.57) is never overtaken and stays PENDING") {
            val id = fixture.member()
            val oldMail = fixture.emailOf(id)
            val now = fixture.now()
            val changeId =
                transaction {
                    EmailChangeStore.lockMember(id)
                    EmailChangeStore.insertPending(
                        memberId = id,
                        pendingEmail = uniqueEmail("offen"),
                        kind = EmailChangeKind.PROPOSAL_NO_ACCOUNT,
                        requestedBy = null,
                        reason = null,
                        confirmHash = "c-${Uuid.random()}",
                        revokeHash = "r-${Uuid.random()}",
                        now = now,
                        expiresAt = fixture.nowPlus(7.days),
                        effectiveAt = null,
                    )
                }
            val result = sync().login(id, email = uniqueEmail("kc"))
            result.skipped shouldBe KeycloakIdpAuditEvent.EMAIL_SYNC_SKIPPED_OPEN_CHANGE
            fixture.emailOf(id) shouldBe oldMail
            fixture.statusOf(changeId.toString()) shouldBe EmailChangeStatus.PENDING.name
            changes(id).size shouldBe 1
        }

        test("a syntactically invalid address is ignored silently") {
            val id = fixture.member()
            val oldMail = fixture.emailOf(id)
            val result = sync().login(id, email = "kein-at-zeichen")
            result shouldBe KeycloakProfileSync.Result(nameChanged = false, emailChanged = false, skipped = null)
            fixture.emailOf(id) shouldBe oldMail
            memberAuditJson(id) shouldBe emptyList()
        }

        test("role, status and tier are never touched") {
            val id = fixture.member(role = AccountRole.MEMBER)
            sync().login(id, email = uniqueEmail("rs"), name = "Egal Wer")
            transaction { AccountTable.selectAll().where { AccountTable.memberId eq id }.single()[AccountTable.role] } shouldBe
                AccountRole.MEMBER
            transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single()[MemberTable.status] } shouldBe MemberStatus.ACTIVE
        }

        test("a failing mailer or a missing SMTP configuration does not undo the sync") {
            val a = fixture.member()
            val newA = uniqueEmail("m1")
            sync(RecordingProvisioningMailer(failing = true)).login(a, email = newA).emailChanged shouldBe true
            fixture.emailOf(a) shouldBe newA
            val b = fixture.member()
            val newB = uniqueEmail("m2")
            val silent = RecordingProvisioningMailer()
            sync(silent, SmtpConfigState.NotConfigured).login(b, email = newB).emailChanged shouldBe true
            fixture.emailOf(b) shouldBe newB
            silent.synced shouldBe emptyList()
        }

        test("two parallel logins produce ONE sync") {
            repeat(3) {
                val id = fixture.member()
                val newMail = uniqueEmail("par")
                val mailer = RecordingProvisioningMailer()
                val s = sync(mailer)
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val start = CountDownLatch(1)
                    val futures =
                        (0 until 2).map {
                            pool.submit<Result<KeycloakProfileSync.Result>> {
                                start.await(20, TimeUnit.SECONDS)
                                runCatching { s.login(id, email = newMail) }
                            }
                        }
                    start.countDown()
                    val results = futures.map { it.get(60, TimeUnit.SECONDS) }
                    results.forEach { it.exceptionOrNull() shouldBe null }
                    results.count { it.getOrThrow().emailChanged } shouldBe 1
                } finally {
                    pool.shutdownNow()
                }
                fixture.emailOf(id) shouldBe newMail
                changes(id).size shouldBe 1
                mailer.synced.size shouldBe 1
            }
        }

        if (db.isPostgres) {
            test(
                "a concurrent claim of the address between check and write ends as CONFLICT; the transaction stays usable and the login unaffected",
            ) {
                val id = fixture.member()
                val oldMail = fixture.emailOf(id)
                val contested = uniqueEmail("contested")
                val claimed = CountDownLatch(1)
                val competitorId = Uuid.random()
                fixture.createdMemberIds += competitorId
                val pool = Executors.newFixedThreadPool(1)
                try {
                    val competitor =
                        pool.submit {
                            transaction {
                                MemberTable.insert {
                                    it[MemberTable.id] = competitorId
                                    it[displayName] = "Konkurrent"
                                    it[email] = contested
                                    it[status] = MemberStatus.FRIEND
                                    it[joinedAt] = LocalDate(2026, 1, 1)
                                }
                                claimed.countDown()
                                Thread.sleep(1500)
                            }
                        }
                    claimed.await(20, TimeUnit.SECONDS) shouldBe true
                    // The checks cannot see the uncommitted row; the UPDATE waits on the unique index and fails with 23505 afterwards.
                    val result = sync().login(id, email = contested)
                    competitor.get(30, TimeUnit.SECONDS)
                    result.emailChanged shouldBe false
                    result.skipped shouldBe KeycloakIdpAuditEvent.EMAIL_SYNC_SKIPPED_COLLISION
                } finally {
                    pool.shutdownNow()
                }
                fixture.emailOf(id) shouldBe oldMail
                changes(id).single()[MemberEmailChangeTable.status] shouldBe EmailChangeStatus.CONFLICT.name
                // the audit entry was written AFTER the savepoint rollback, in the same transaction: it was still usable
                memberAuditJson(id).single() shouldContain "EMAIL_SYNC_SKIPPED_COLLISION"
            }
        }
    })

class KeycloakProfileSyncTest : KeycloakProfileSyncScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class KeycloakProfileSyncPostgresTest : KeycloakProfileSyncScenarios(TestDatabase.Postgres())
