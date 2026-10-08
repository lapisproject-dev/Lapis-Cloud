package network.lapis.cloud.server.keycloak

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.KeycloakAccountLinkTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.SmtpConfigState
import network.lapis.cloud.server.member.EmailChangeFixture
import network.lapis.cloud.server.member.KeycloakProvisioningNotifier
import network.lapis.cloud.server.member.configuredSmtp
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

private typealias Rejected = KeycloakMemberProvisioner.Outcome.Rejected
private typealias RejectReason = KeycloakMemberProvisioner.RejectReason

/**
 * Welle V1.9.73 -- the just-in-time provisioning against a real database, as scenarios so the same assertions run on H2 and on
 * PostgreSQL. Clock-dependent scenarios pin the server clock to otherwise unused years so they neither see nor leave rate-limit
 * rows of other specs.
 */
abstract class KeycloakMemberProvisionerScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fixture = EmailChangeFixture()
        val createdIds = mutableListOf<Uuid>()
        val usedYears = mutableSetOf<Int>()

        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterSpec {
            deleteMembersCompletely(createdIds)
            fixture.cleanUp()
            usedYears.forEach { deleteMemberNumberSequence(it) }
            TimeTestSupport.resetOrganizationZone()
            db.deactivate()
        }

        fun provisioner(
            config: KeycloakConfig = jitConfig(),
            mailer: RecordingProvisioningMailer = RecordingProvisioningMailer(),
            smtp: SmtpConfigState = configuredSmtp(),
        ) = KeycloakMemberProvisioner(config = config, notifier = KeycloakProvisioningNotifier(mailer = mailer, smtpConfigState = smtp))

        fun KeycloakMemberProvisioner.go(
            email: String = uniqueEmail(),
            subject: String = "sub-${Uuid.random()}",
            verified: Boolean = true,
            claims: com.nimbusds.jwt.JWTClaimsSet = idClaims(email = email),
        ): KeycloakMemberProvisioner.Outcome {
            val outcome = provision(issuer = JIT_ISSUER, subject = subject, rawEmail = email, emailVerified = verified, claims = claims)
            if (outcome is KeycloakMemberProvisioner.Outcome.Provisioned) createdIds += outcome.memberId
            return outcome
        }

        fun withClock(
            instant: String,
            block: () -> Unit,
        ) {
            usedYears += instant.take(4).toInt()
            TimeTestSupport.withServerClock(instant = instant) { block() }
        }

        test("happy path: an ACTIVE member, a MEMBER account without password, a link, a number, history and an audit entry without PII") {
            TimeTestSupport.setOrganizationZone("Europe/Berlin")
            // 23:30 UTC on 15 March is already 16 March in Berlin (winter time) -> joined_at is the ORGANIZATION date.
            withClock("2032-03-15T23:30:00Z") {
                val email = "  Erika.Muster-" + Uuid.random().toString().take(8) + "@Example.ORG "
                val normalized = email.trim().lowercase()
                val outcome =
                    provisioner().go(
                        email = email,
                        subject = "sub-happy",
                        claims = idClaims(email = email, name = "Erika\nMuster"),
                    )
                val provisioned = outcome.shouldBeInstanceOf<KeycloakMemberProvisioner.Outcome.Provisioned>()
                val id = provisioned.memberId

                val member = transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single() }
                member[MemberTable.status] shouldBe MemberStatus.ACTIVE
                member[MemberTable.email] shouldBe normalized
                member[MemberTable.displayName] shouldBe "Erika Muster"
                member[MemberTable.joinedAt].toString() shouldBe "2032-03-16"
                member[MemberTable.membershipTierId] shouldBe null
                member[MemberTable.regionalChapterId] shouldBe null
                (member[MemberTable.emailVerifiedAt] != null) shouldBe true
                member[MemberTable.memberNumber] shouldBe "M-2032-00001"

                val account = transaction { AccountTable.selectAll().where { AccountTable.memberId eq id }.single() }
                account[AccountTable.role] shouldBe AccountRole.MEMBER
                account[AccountTable.passwordHash] shouldBe null
                account[AccountTable.oidcIssuer] shouldBe null
                account[AccountTable.oidcSubject] shouldBe null
                (account[AccountTable.roleChangedAt] != null) shouldBe true

                val link = transaction { KeycloakAccountLinkTable.selectAll().where { KeycloakAccountLinkTable.memberId eq id }.single() }
                link[KeycloakAccountLinkTable.keycloakIssuer] shouldBe JIT_ISSUER
                link[KeycloakAccountLinkTable.keycloakSubject] shouldBe "sub-happy"
                link[KeycloakAccountLinkTable.linkedBy] shouldBe null

                val history =
                    transaction { MemberStatusHistoryTable.selectAll().where { MemberStatusHistoryTable.memberId eq id }.toList() }
                history.size shouldBe 1
                history.single()[MemberStatusHistoryTable.status] shouldBe "ACTIVE"
                history.single()[MemberStatusHistoryTable.previousStatus] shouldBe null
                history.single()[MemberStatusHistoryTable.sourceKind] shouldBe "KEYCLOAK_JIT"
                (history.single()[MemberStatusHistoryTable.recordedAt] != null) shouldBe true

                val audit = memberAuditJson(id)
                audit.size shouldBe 1
                audit.single() shouldContain "PROVISIONED"
                audit.single() shouldContain "\"role\":\"MEMBER\""
                // The hash-chained, unerasable log learns THAT it happened, never WHO.
                audit.single() shouldNotContain normalized
                audit.single() shouldNotContain "Muster"
                audit.single() shouldNotContain "sub-happy"
            }
        }

        test("every refusal creates nothing: option off, group missing / wrong / absent type, unverified, bad address, no name") {
            val p = provisioner()

            fun refused(
                reason: RejectReason,
                outcome: KeycloakMemberProvisioner.Outcome,
                email: String,
            ) {
                outcome shouldBe Rejected(reason)
                memberCountByEmail(email.trim().lowercase()) shouldBe 0
            }

            var email = uniqueEmail()
            refused(
                RejectReason.DISABLED,
                provisioner(config = jitConfig(autoProvision = false)).go(email = email),
                email,
            )
            email = uniqueEmail()
            refused(RejectReason.GROUP_MISSING, p.go(email = email, claims = idClaims(email = email, groups = null)), email)
            email = uniqueEmail()
            refused(
                RejectReason.GROUP_NO_MATCH,
                p.go(email = email, claims = idClaims(email = email, groups = listOf("other", "/a/apolda"))),
                email,
            )
            email = uniqueEmail()
            refused(RejectReason.GROUP_WRONG_TYPE, p.go(email = email, claims = idClaims(email = email, groups = 7)), email)
            email = uniqueEmail()
            refused(RejectReason.EMAIL_NOT_VERIFIED, p.go(email = email, verified = false), email)
            // email_verified is required for a NEW account even when the operator relaxed it for linking
            email = uniqueEmail()
            refused(
                RejectReason.EMAIL_NOT_VERIFIED,
                provisioner(config = jitConfig(requireVerifiedEmail = false)).go(email = email, verified = false),
                email,
            )
            refused(RejectReason.INVALID_EMAIL, p.go(email = "not-an-address"), "not-an-address")
            refused(RejectReason.INVALID_EMAIL, p.go(email = "a@b.de\r\nBcc: x@y.de"), "a@b.de\r\nBcc: x@y.de")
            val tooLong = "a".repeat(320) + "@example.org"
            refused(RejectReason.INVALID_EMAIL, p.go(email = tooLong), tooLong)
            email = uniqueEmail()
            refused(RejectReason.NAME_MISSING, p.go(email = email, claims = idClaims(email = email, name = null)), email)
            email = uniqueEmail()
            refused(RejectReason.NAME_MISSING, p.go(email = email, claims = idClaims(email = email, name = " \u0007 ")), email)
        }

        test("a custom claim name and a slash-prefixed group are honoured") {
            val email = uniqueEmail()
            val p = provisioner(config = jitConfig(group = "/apolda", claim = "kc_groups"))
            p
                .go(email = email, claims = idClaims(email = email, groups = listOf("apolda"), groupsClaim = "kc_groups"))
                .shouldBeInstanceOf<KeycloakMemberProvisioner.Outcome.Provisioned>()
            val other = uniqueEmail()
            p.go(email = other, claims = idClaims(email = other, groups = listOf("apolda"), groupsClaim = "groups")) shouldBe
                Rejected(RejectReason.GROUP_MISSING)
        }

        test("an existing member with the same address (other case) wins: ExistingFound, no second member") {
            val existing = fixture.member(email = "existing-" + Uuid.random().toString().take(8) + "@example.org")
            val address = fixture.emailOf(existing)
            val before = transaction { MemberTable.selectAll().count() }
            provisioner().go(email = address.uppercase()) shouldBe KeycloakMemberProvisioner.Outcome.ExistingFound
            transaction { MemberTable.selectAll().count() } shouldBe before
        }

        test("an existing link for the same (issuer, subject) wins even with a different address") {
            val first = uniqueEmail()
            val p = provisioner()
            p.go(email = first, subject = "same-subject").shouldBeInstanceOf<KeycloakMemberProvisioner.Outcome.Provisioned>()
            val before = transaction { MemberTable.selectAll().count() }
            p.go(email = uniqueEmail(), subject = "same-subject") shouldBe KeycloakMemberProvisioner.Outcome.ExistingFound
            transaction { MemberTable.selectAll().count() } shouldBe before
        }

        test("the role is a constant: a token that claims ADMIN in every shape still yields a MEMBER account") {
            val email = uniqueEmail()
            val claims =
                idClaims(
                    email = email,
                    groups = listOf("admin", "apolda"),
                    extra =
                        mapOf(
                            "role" to "ADMIN",
                            "roles" to listOf("ADMIN", "BOARD"),
                            "realm_access" to mapOf("roles" to listOf("admin")),
                            "resource_access" to mapOf("lapis" to mapOf("roles" to listOf("ADMIN"))),
                        ),
                )
            val id =
                provisioner()
                    .go(
                        email = email,
                        claims = claims,
                    ).shouldBeInstanceOf<KeycloakMemberProvisioner.Outcome.Provisioned>()
                    .memberId
            transaction { AccountTable.selectAll().where { AccountTable.memberId eq id }.single()[AccountTable.role] } shouldBe
                AccountRole.MEMBER
        }

        test("the hourly rate: N creations pass, the next is RATE_LIMITED, rows older than an hour do not count") {
            val p = provisioner(config = jitConfig(ratePerHour = 3))
            withClock("2033-05-01T10:00:00Z") {
                repeat(3) { p.go().shouldBeInstanceOf<KeycloakMemberProvisioner.Outcome.Provisioned>() }
                val blocked = uniqueEmail()
                p.go(email = blocked) shouldBe Rejected(RejectReason.RATE_LIMITED)
                memberCountByEmail(blocked) shouldBe 0
            }
            withClock("2033-05-01T10:59:00Z") { p.go() shouldBe Rejected(RejectReason.RATE_LIMITED) }
            withClock("2033-05-01T11:01:00Z") {
                // the first batch is now 61 minutes old
                p.go().shouldBeInstanceOf<KeycloakMemberProvisioner.Outcome.Provisioned>()
            }
        }

        test("the notice goes to every ADMIN (not BOARD, not blocked, not anonymized), after the commit, and names no address") {
            val admin = fixture.member(role = AccountRole.ADMIN)
            val board = fixture.member(role = AccountRole.BOARD)
            val blockedAdmin = fixture.member(role = AccountRole.ADMIN, status = MemberStatus.WITHDRAWN)
            val anonymizedAdmin = fixture.member(role = AccountRole.ADMIN)
            transaction { MemberTable.update({ MemberTable.id eq anonymizedAdmin }) { it[anonymizedAt] = fixture.now() } }
            val mailer = RecordingProvisioningMailer()
            val email = uniqueEmail()
            provisioner(mailer = mailer)
                .go(email = email, claims = idClaims(email = email, name = "Neu Mitglied"))
                .shouldBeInstanceOf<KeycloakMemberProvisioner.Outcome.Provisioned>()
            val recipients = mailer.provisioned.map { it.email }
            recipients shouldContain fixture.emailOf(admin)
            recipients shouldNotContain fixture.emailOf(board)
            recipients shouldNotContain fixture.emailOf(blockedAdmin)
            recipients shouldNotContain fixture.emailOf(anonymizedAdmin)
            mailer.provisioned.all { it.name == "Neu Mitglied" } shouldBe true
        }

        test("a failing mailer or a missing SMTP configuration never blocks the creation") {
            val failing = RecordingProvisioningMailer(failing = true)
            fixture.member(role = AccountRole.ADMIN)
            provisioner(mailer = failing).go().shouldBeInstanceOf<KeycloakMemberProvisioner.Outcome.Provisioned>()
            val silent = RecordingProvisioningMailer()
            provisioner(mailer = silent, smtp = SmtpConfigState.NotConfigured)
                .go()
                .shouldBeInstanceOf<KeycloakMemberProvisioner.Outcome.Provisioned>()
            silent.provisioned shouldBe emptyList()
        }

        test(
            "race: two threads with the SAME identity and address create exactly one member, one account, one link, one history row, one audit entry",
        ) {
            repeat(4) {
                val email = uniqueEmail("race")
                val subject = "race-sub-${Uuid.random()}"
                val p = provisioner()
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val start = CountDownLatch(1)
                    val futures =
                        (0 until 2).map {
                            pool.submit<Result<KeycloakMemberProvisioner.Outcome>> {
                                start.await(20, TimeUnit.SECONDS)
                                runCatching {
                                    p.provision(
                                        issuer = JIT_ISSUER,
                                        subject = subject,
                                        rawEmail = email,
                                        emailVerified = true,
                                        claims = idClaims(email = email),
                                    )
                                }
                            }
                        }
                    start.countDown()
                    val results = futures.map { it.get(60, TimeUnit.SECONDS) }
                    results.forEach { it.exceptionOrNull() shouldBe null }
                    val outcomes = results.map { it.getOrThrow() }
                    val created = outcomes.filterIsInstance<KeycloakMemberProvisioner.Outcome.Provisioned>()
                    created.size shouldBe 1
                    createdIds += created.single().memberId
                    outcomes.count { it == KeycloakMemberProvisioner.Outcome.ExistingFound } shouldBe 1
                } finally {
                    pool.shutdownNow()
                }
                memberCountByEmail(email) shouldBe 1
                val id = requireNotNull(memberIdByEmail(email))
                transaction { AccountTable.selectAll().where { AccountTable.memberId eq id }.count() } shouldBe 1
                transaction { KeycloakAccountLinkTable.selectAll().where { KeycloakAccountLinkTable.memberId eq id }.count() } shouldBe 1
                transaction { MemberStatusHistoryTable.selectAll().where { MemberStatusHistoryTable.memberId eq id }.count() } shouldBe 1
                memberAuditJson(id).size shouldBe 1
            }
        }

        test("race: two DIFFERENT people at once get two members with consecutive numbers, no gap and no duplicate") {
            withClock("2034-06-01T09:00:00Z") {
                val p = provisioner()
                val pool = Executors.newFixedThreadPool(2)
                val results =
                    try {
                        val start = CountDownLatch(1)
                        val futures =
                            (0 until 2).map {
                                pool.submit<Result<KeycloakMemberProvisioner.Outcome>> {
                                    start.await(20, TimeUnit.SECONDS)
                                    val email = uniqueEmail("two")
                                    runCatching {
                                        p.provision(
                                            issuer = JIT_ISSUER,
                                            subject = "two-${Uuid.random()}",
                                            rawEmail = email,
                                            emailVerified = true,
                                            claims = idClaims(email = email),
                                        )
                                    }
                                }
                            }
                        start.countDown()
                        futures.map { it.get(60, TimeUnit.SECONDS) }
                    } finally {
                        pool.shutdownNow()
                    }
                results.forEach { it.exceptionOrNull() shouldBe null }
                val ids = results.map { it.getOrThrow().shouldBeInstanceOf<KeycloakMemberProvisioner.Outcome.Provisioned>().memberId }
                createdIds += ids
                val numbers =
                    transaction {
                        ids.map { id -> MemberTable.selectAll().where { MemberTable.id eq id }.single()[MemberTable.memberNumber] }
                    }
                numbers.toSet() shouldBe setOf("M-2034-00001", "M-2034-00002")
            }
        }

        if (db.isPostgres) {
            test("SQLSTATE 23505 from a concurrent writer that bypasses the lock maps to ExistingFound, never to an error") {
                val email = uniqueEmail("bypass")
                val competitorInserted = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(1)
                val competitorId = Uuid.random()
                createdIds += competitorId
                try {
                    // A writer that does NOT take the organization-settings lock inserts the same address and holds its transaction open.
                    val competitor =
                        pool.submit {
                            transaction {
                                MemberTable.insert {
                                    it[id] = competitorId
                                    it[displayName] = "Konkurrent"
                                    it[MemberTable.email] = email
                                    it[status] = MemberStatus.FRIEND
                                    it[joinedAt] = LocalDate(2026, 1, 1)
                                }
                                competitorInserted.countDown()
                                Thread.sleep(1500)
                            }
                        }
                    competitorInserted.await(20, TimeUnit.SECONDS) shouldBe true
                    // The pre-check cannot see the uncommitted row; the INSERT blocks on the unique index and fails with 23505 after the commit.
                    provisioner().go(email = email) shouldBe KeycloakMemberProvisioner.Outcome.ExistingFound
                    competitor.get(30, TimeUnit.SECONDS)
                } finally {
                    pool.shutdownNow()
                }
                memberCountByEmail(email) shouldBe 1
                transaction {
                    MemberStatusHistoryTable.selectAll().where { MemberStatusHistoryTable.memberId eq competitorId }.count()
                } shouldBe
                    0
            }
        }
    })

class KeycloakMemberProvisionerTest : KeycloakMemberProvisionerScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class KeycloakMemberProvisionerPostgresTest : KeycloakMemberProvisionerScenarios(TestDatabase.Postgres())
