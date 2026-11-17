package network.lapis.cloud.server.keycloak

import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.KeycloakAccountLinkTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.member.EmailChangeFixture
import network.lapis.cloud.server.member.ExternalMemberIdConfig
import network.lapis.cloud.server.member.ExternalMemberIdStore
import network.lapis.cloud.server.member.ExternalMemberIdStore.ClaimOutcome
import network.lapis.cloud.server.member.KeycloakProvisioningNotifier
import network.lapis.cloud.server.member.configuredSmtp
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * V156 -- the claim path of the external member id against a real database (H2 and PostgreSQL): the store itself (absent / invalid / set /
 * unchanged / mismatch / duplicate / anonymised), the just-in-time creation with a claim (also with a duplicate: the member is still created
 * and the transaction stays usable, which on PostgreSQL needs the savepoint) and the login sync of an existing member. Nothing here may touch
 * `oidc_subject`, and no id value may appear in the audit entries.
 */
abstract class ExternalMemberIdStoreScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fixture = EmailChangeFixture()
        val createdIds = mutableListOf<Uuid>()
        val usedYears = mutableSetOf<Int>()
        val now = LocalDateTime(2033, 5, 1, 10, 0)

        beforeSpec { db.activate() }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterSpec {
            deleteMembersCompletely(createdIds)
            fixture.cleanUp()
            usedYears.forEach { deleteMemberNumberSequence(it) }
            TimeTestSupport.resetOrganizationZone()
            db.deactivate()
        }

        fun newMember(): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Ext $id"
                    it[email] = "ext-$id@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2024, 1, 1)
                }
            }
            createdIds += id
            return id
        }

        fun row(id: Uuid) = transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single() }

        fun claim(
            id: Uuid,
            value: Any?,
            source: String = "Wabeo",
        ): ClaimOutcome =
            transaction {
                ExternalMemberIdStore.applyClaim(
                    memberId = id,
                    rawClaimValue = value,
                    source = source,
                    subjectFingerprint = "fp",
                    now = now,
                )
            }

        fun provisioner(config: ExternalMemberIdConfig) =
            KeycloakMemberProvisioner(
                config = jitConfig(),
                notifier = KeycloakProvisioningNotifier(mailer = RecordingProvisioningMailer(), smtpConfigState = configuredSmtp()),
                externalIdConfig = config,
            )

        fun KeycloakMemberProvisioner.go(
            claims: com.nimbusds.jwt.JWTClaimsSet,
            email: String,
            subject: String = "sub-${Uuid.random()}",
        ): KeycloakMemberProvisioner.Outcome {
            usedYears += 2034
            val outcome = provision(issuer = JIT_ISSUER, subject = subject, rawEmail = email, emailVerified = true, claims = claims)
            if (outcome is KeycloakMemberProvisioner.Outcome.Provisioned) createdIds += outcome.memberId
            return outcome
        }

        test("a missing, non-string or empty claim changes nothing; an invalid one is refused") {
            val m = newMember()
            claim(m, null) shouldBe ClaimOutcome.Absent
            claim(m, 12345) shouldBe ClaimOutcome.Absent
            claim(m, listOf("A1")) shouldBe ClaimOutcome.Absent
            claim(m, "   ") shouldBe ClaimOutcome.Absent
            claim(m, "has space") shouldBe ClaimOutcome.Invalid
            claim(m, "x".repeat(65)) shouldBe ClaimOutcome.Invalid
            claim(m, "-leading") shouldBe ClaimOutcome.Invalid
            row(m)[MemberTable.externalMemberId] shouldBe null
            row(m)[MemberTable.externalMemberIdSource] shouldBe null
        }

        test(
            "a valid claim is stored with the source, trimmed and case kept; the same value again is unchanged and writes no second audit entry",
        ) {
            val m = newMember()
            claim(m, "  Wb-Ab12 ") shouldBe ClaimOutcome.Set
            row(m)[MemberTable.externalMemberId] shouldBe "Wb-Ab12"
            row(m)[MemberTable.externalMemberIdSource] shouldBe "Wabeo"
            claim(m, "Wb-Ab12") shouldBe ClaimOutcome.Unchanged
            val audit = memberAuditJson(m)
            audit.size shouldBe 1
            audit.single() shouldContain "member.external_id.set_by_claim"
            audit.single() shouldNotContain "Wb-Ab12"
        }

        test("a different claim never overwrites: mismatch, audited once per window, and never with the value") {
            val m = newMember()
            claim(m, "A-1") shouldBe ClaimOutcome.Set
            claim(m, "B-2") shouldBe ClaimOutcome.Mismatch
            claim(m, "B-2") shouldBe ClaimOutcome.Mismatch
            claim(m, "A-1", source = "Other") shouldBe ClaimOutcome.Mismatch
            row(m)[MemberTable.externalMemberId] shouldBe "A-1"
            row(m)[MemberTable.externalMemberIdSource] shouldBe "Wabeo"
            val audit = memberAuditJson(m)
            audit.count { it.contains("claim_mismatch") } shouldBe 1
            audit.forEach {
                it shouldNotContain "B-2"
                it shouldNotContain "A-1"
            }
        }

        test("a duplicate within the same source is refused and the transaction stays usable; the same id under another source is fine") {
            val a = newMember()
            val b = newMember()
            claim(a, "DUP-9") shouldBe ClaimOutcome.Set
            transaction {
                // inside ONE transaction: the failed insert must not poison the rest (PostgreSQL: savepoint)
                ExternalMemberIdStore.applyClaim(
                    memberId = b,
                    rawClaimValue = "DUP-9",
                    source = "Wabeo",
                    subjectFingerprint = "fp",
                    now = now,
                ) shouldBe
                    ClaimOutcome.Duplicate
                ExternalMemberIdStore.applyClaim(
                    memberId = b,
                    rawClaimValue = "DUP-9",
                    source = "Other",
                    subjectFingerprint = "fp",
                    now = now,
                ) shouldBe
                    ClaimOutcome.Set
            }
            row(b)[MemberTable.externalMemberIdSource] shouldBe "Other"
            memberAuditJson(b).any { it.contains("claim_duplicate") } shouldBe true
        }

        test("an anonymised or unknown member gets nothing") {
            val m = newMember()
            transaction { MemberTable.update({ MemberTable.id eq m }) { it[anonymizedAt] = LocalDateTime(2026, 1, 1, 0, 0) } }
            claim(m, "A-1") shouldBe ClaimOutcome.MemberAnonymized
            claim(Uuid.random(), "A-1") shouldBe ClaimOutcome.MemberMissing
        }

        test("JIT: the claim is applied in the same transaction, oidc_subject stays untouched, the link is created") {
            val email = uniqueEmail()
            val outcome =
                provisioner(ExternalMemberIdConfig.forTest(source = "Wabeo", claim = "wabeo_id"))
                    .go(claims = idClaims(email = email, extra = mapOf("wabeo_id" to "W-100")), email = email, subject = "sub-ext-1")
            val id = outcome.shouldBeInstanceOf<KeycloakMemberProvisioner.Outcome.Provisioned>().memberId
            row(id)[MemberTable.externalMemberId] shouldBe "W-100"
            row(id)[MemberTable.externalMemberIdSource] shouldBe "Wabeo"
            transaction { KeycloakAccountLinkTable.selectAll().where { KeycloakAccountLinkTable.memberId eq id }.single() }[
                KeycloakAccountLinkTable.keycloakSubject,
            ] shouldBe "sub-ext-1"
            memberAuditJson(id).forEach { it shouldNotContain "W-100" }
        }

        test("JIT with a duplicate id still creates the member, without an id") {
            val holder = newMember()
            claim(holder, "W-200") shouldBe ClaimOutcome.Set
            val email = uniqueEmail()
            val outcome =
                provisioner(ExternalMemberIdConfig.forTest(source = "Wabeo", claim = "wabeo_id"))
                    .go(claims = idClaims(email = email, extra = mapOf("wabeo_id" to "W-200")), email = email)
            val id = outcome.shouldBeInstanceOf<KeycloakMemberProvisioner.Outcome.Provisioned>().memberId
            row(id)[MemberTable.externalMemberId] shouldBe null
            row(id)[MemberTable.status] shouldBe MemberStatus.ACTIVE
        }

        test("JIT without a configured claim leaves the id empty") {
            val email = uniqueEmail()
            val outcome =
                provisioner(ExternalMemberIdConfig.forTest(source = "Wabeo", claim = null))
                    .go(claims = idClaims(email = email, extra = mapOf("wabeo_id" to "W-300")), email = email)
            val id = outcome.shouldBeInstanceOf<KeycloakMemberProvisioner.Outcome.Provisioned>().memberId
            row(id)[MemberTable.externalMemberId] shouldBe null
        }

        test(
            "login sync of an existing member: stores the id, is inactive without a claim configuration, and never throws for a bad value",
        ) {
            val m = newMember()
            val claims = idClaims(email = "x@example.org", extra = mapOf("wabeo_id" to "S-1"))
            KeycloakExternalIdSync(ExternalMemberIdConfig.forTest(source = "Wabeo", claim = null)).let {
                it.active shouldBe false
                it.syncOnLogin(memberId = m, subject = "s", claims = claims) shouldBe null
            }
            row(m)[MemberTable.externalMemberId] shouldBe null
            val sync = KeycloakExternalIdSync(ExternalMemberIdConfig.forTest(source = "Wabeo", claim = "wabeo_id"))
            sync.active shouldBe true
            sync.syncOnLogin(memberId = m, subject = "s", claims = claims) shouldBe ClaimOutcome.Set
            row(m)[MemberTable.externalMemberId] shouldBe "S-1"
            sync.syncOnLogin(
                memberId = m,
                subject = "s",
                claims = idClaims(email = "x@example.org", extra = mapOf("wabeo_id" to "bad value")),
            ) shouldBe
                ClaimOutcome.Invalid
            row(m)[MemberTable.externalMemberId] shouldBe "S-1"
        }
    })

class ExternalMemberIdStoreTest : ExternalMemberIdStoreScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ExternalMemberIdStorePostgresTest : ExternalMemberIdStoreScenarios(TestDatabase.Postgres())
