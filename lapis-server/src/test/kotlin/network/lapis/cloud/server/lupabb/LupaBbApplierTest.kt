package network.lapis.cloud.server.lupabb

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.LupaBbSyncRunTable
import network.lapis.cloud.server.db.generated.MailOutboxTable
import network.lapis.cloud.server.db.generated.MemberLupaBbProfileTable
import network.lapis.cloud.server.db.generated.MemberStatusHistoryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.LupaBbApplyResultDto
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbRowReasonCode
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/** V1.9.226 -- what the apply step writes, what it must never write, and that one broken record does not stop the run. */
class LupaBbApplierTest :
    FunSpec({
        val fx = LupaBbFixtures()
        beforeSpec { DatabaseConfig.connect() }
        beforeTest { LupaBbSuppression.configure(TEST_BOX) }
        afterTest { fx.cleanUp() }

        fun unique() = "Testfeld${Uuid.random().toString().take(8)}"

        fun applyTo(
            plan: LupaBbPlan,
            admin: Uuid,
        ): LupaBbApplyResultDto =
            LupaBbApplier(chapterEnforced = false).apply(entry = entryOf(plan = plan, admin = admin), actor = fx.current(id = admin))

        fun mailCount() = transaction { MailOutboxTable.selectAll().count() }

        fun auditOf(vararg ids: Uuid) =
            transaction { AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId inList ids.toList() }.toList() }

        test("a new record becomes a member without an account and without any mail, with its history and audit") {
            val admin = fx.admin()
            val family = unique()
            val mailsBefore = mailCount()
            val result =
                applyTo(planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_040, family = family)), actor = admin), admin)
            result.created shouldBe 1
            val row = fx.byPersonNumber(9_000_000_040)!!
            val id = row[MemberTable.id]
            row[MemberTable.email] shouldBe "${TEST_EMAIL_PREFIX}9000000040@example.org"
            row[MemberTable.displayName] shouldBe "Erika $family"
            row[MemberTable.status] shouldBe MemberStatus.ACTIVE
            row[MemberTable.street] shouldBe "Musterweg 1"
            row[MemberTable.countryCode] shouldBe "DE"
            transaction { AccountTable.selectAll().where { AccountTable.memberId eq id }.count() } shouldBe 0
            mailCount() shouldBe mailsBefore
            transaction { MemberLupaBbProfileTable.selectAll().where { MemberLupaBbProfileTable.memberId eq id }.single() }[
                MemberLupaBbProfileTable.familyName,
            ] shouldBe family
            val history = transaction { MemberStatusHistoryTable.selectAll().where { MemberStatusHistoryTable.memberId eq id }.single() }
            history[MemberStatusHistoryTable.sourceKind] shouldBe "LUPABB_SYNC"
            history[MemberStatusHistoryTable.status] shouldBe "ACTIVE"
            auditOf(id).single().let {
                it[AuditLogEntryTable.entityType] shouldBe AuditEntityType.MEMBER
                it[AuditLogEntryTable.action] shouldBe AuditAction.CREATE
            }
        }

        test("an update writes only what differs and leaves the fields kept in Lapis, the account and the e-mail alone") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(displayName = "Erika $family", personNumber = 9_000_000_041, email = "${TEST_EMAIL_PREFIX}keep@example.org")
            transaction {
                MemberTable.update({ MemberTable.id eq id }) { it[contributionExemptFrom] = LocalDate(2026, 5, 5) }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_041, family = family, email = "${TEST_EMAIL_PREFIX}new@example.org"),
                        ),
                    actor = admin,
                )
            val result = applyTo(plan, admin)
            result.updated shouldBe 1
            val row = fx.memberRow(id)
            row[MemberTable.street] shouldBe "Musterweg 1"
            row[MemberTable.email] shouldBe "${TEST_EMAIL_PREFIX}keep@example.org"
            row[MemberTable.contributionExemptFrom] shouldBe LocalDate(2026, 5, 5)
            transaction { AccountTable.selectAll().where { AccountTable.memberId eq id }.single() }[AccountTable.role] shouldBe
                AccountRole.MEMBER
            val touched =
                transaction { MemberLupaBbProfileTable.selectAll().where { MemberLupaBbProfileTable.memberId eq id }.single() }[
                    MemberLupaBbProfileTable.lastTouchedFields,
                ]
            touched!! shouldContain "STREET"
            touched shouldNotContain "EMAIL"
        }

        test("a status change goes through the central path, is recorded as LUPABB_SYNC, ends the sessions and sends no notice") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(displayName = "Erika $family", personNumber = 9_000_000_042, status = MemberStatus.ACTIVE)
            SessionStore.createSession(id)
            SessionStore.countActiveForMember(id) shouldBe 1
            val mailsBefore = mailCount()
            val result =
                applyTo(
                    planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_042, statusId = 6, family = family)), actor = admin),
                    admin,
                )
            result.updated shouldBe 1
            fx.memberRow(id)[MemberTable.status] shouldBe MemberStatus.WITHDRAWN
            SessionStore.countActiveForMember(id) shouldBe 0
            mailCount() shouldBe mailsBefore
            val history = transaction { MemberStatusHistoryTable.selectAll().where { MemberStatusHistoryTable.memberId eq id }.toList() }
            history.map { it[MemberStatusHistoryTable.sourceKind] } shouldContain "LUPABB_SYNC"
            history.first { it[MemberStatusHistoryTable.sourceKind] == "LUPABB_SYNC" }[MemberStatusHistoryTable.status] shouldBe "WITHDRAWN"
        }

        test("deceased is taken over without a date of death") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(displayName = "Erika $family", personNumber = 9_000_000_043)
            applyTo(
                planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_043, statusId = 5, family = family)), actor = admin),
                admin,
            )
            fx.memberRow(id)[MemberTable.status] shouldBe MemberStatus.DECEASED
            fx.memberRow(id)[MemberTable.dateOfDeath] shouldBe null
        }

        test("a record that changed since the preview is an error of its own; the rest of the run goes on") {
            val admin = fx.admin()
            val family = unique()
            val first = fx.member(displayName = "Erika $family", personNumber = 9_000_000_044)
            val second = fx.member(displayName = "Erika $family", personNumber = 9_000_000_045)
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_044, family = family),
                            wireMember(personNumber = 9_000_000_045, family = family),
                        ),
                    actor = admin,
                )
            transaction { MemberTable.update({ MemberTable.id eq first }) { it[street] = "Heimlich geaendert" } }
            val result = applyTo(plan, admin)
            result.updated shouldBe 1
            result.errors shouldBe 1
            result.failures.single().code shouldBe LupaBbRowReasonCode.CHANGED_SINCE_PREVIEW
            fx.memberRow(first)[MemberTable.street] shouldBe "Heimlich geaendert"
            fx.memberRow(second)[MemberTable.street] shouldBe "Musterweg 1"
        }

        test("a role granted after the preview stops a status change (the fingerprint covers the role)") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(displayName = "Erika $family", personNumber = 9_000_000_046)
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_046, statusId = 6, family = family)), actor = admin)
            transaction {
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.BOARD
                }
            }
            val result = applyTo(plan, admin)
            result.failures.single().code shouldBe LupaBbRowReasonCode.CHANGED_SINCE_PREVIEW
            fx.memberRow(id)[MemberTable.status] shouldBe MemberStatus.ACTIVE
        }

        test("a management role: the profile is written, status and core fields are not; afterwards only the manual part is left (error)") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(role = AccountRole.BOARD, displayName = "Erika $family", personNumber = 9_000_000_047)
            val data = datasetOf(wireMember(personNumber = 9_000_000_047, statusId = 6, family = family))
            val result = applyTo(planOf(dataset = data, actor = admin), admin)
            result.errors shouldBe 0
            result.updated shouldBe 1
            fx.memberRow(id)[MemberTable.status] shouldBe MemberStatus.ACTIVE
            fx.memberRow(id)[MemberTable.street] shouldBe null
            transaction { MemberLupaBbProfileTable.selectAll().where { MemberLupaBbProfileTable.memberId eq id }.single() }[
                MemberLupaBbProfileTable.familyName,
            ] shouldBe family
            val replan = planOf(dataset = data, actor = admin)
            replan.row(9_000_000_047).category shouldBe LupaBbCategory.ERROR
            replan.row(9_000_000_047).reasons.map { it.code } shouldContain LupaBbRowReasonCode.ADMIN_ROLE_MANUAL
        }

        test("a management member that is not linked yet gets its person number, nothing else of the core data") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(role = AccountRole.TREASURER, displayName = "Erika $family", email = "${TEST_EMAIL_PREFIX}treas@example.org")
            val result =
                applyTo(
                    planOf(
                        dataset =
                            datasetOf(
                                wireMember(personNumber = 9_000_000_062, family = family, email = "${TEST_EMAIL_PREFIX}treas@example.org"),
                            ),
                        actor = admin,
                    ),
                    admin,
                )
            result.updated shouldBe 1
            fx.memberRow(id)[MemberTable.lupabbPersonNumber] shouldBe 9_000_000_062
            fx.memberRow(id)[MemberTable.street] shouldBe null
        }

        test("a new member with the LupaBB status Foerderer gets friend_since like every other supporter") {
            val admin = fx.admin()
            val result =
                applyTo(
                    planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_063, statusId = 3, family = unique())), actor = admin),
                    admin,
                )
            result.created shouldBe 1
            val row = fx.byPersonNumber(9_000_000_063)!!
            row[MemberTable.status] shouldBe MemberStatus.FRIEND
            row[MemberTable.friendSince] shouldBe row[MemberTable.joinedAt]
        }

        test("a second run with the same data writes nothing: everything unchanged, no further member audit entries") {
            val admin = fx.admin()
            val family = unique()
            val existing = fx.member(displayName = "Erika $family", personNumber = 9_000_000_048)
            val data =
                datasetOf(
                    wireMember(personNumber = 9_000_000_048, family = family),
                    wireMember(personNumber = 9_000_000_049, family = unique()),
                )
            val first = applyTo(planOf(dataset = data, actor = admin), admin)
            first.created shouldBe 1
            first.updated shouldBe 1
            val created = fx.byPersonNumber(9_000_000_049)!![MemberTable.id]
            val auditBefore = auditOf(existing, created).size
            val replan = planOf(dataset = data, actor = admin)
            replan.row(9_000_000_048).category shouldBe LupaBbCategory.UNCHANGED
            replan.row(9_000_000_049).category shouldBe LupaBbCategory.UNCHANGED
            val second = applyTo(replan, admin)
            second.created shouldBe 0
            second.updated shouldBe 0
            second.unchanged shouldBe replan.rows.size
            auditOf(existing, created).size shouldBe auditBefore
        }

        test("a member missing in LupaBB is only marked, never deleted or deactivated; reappearing clears the mark") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(role = AccountRole.MEMBER, displayName = "Erika $family", personNumber = 9_000_000_050)
            val gone =
                applyTo(planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_051, family = unique())), actor = admin), admin)
            gone.missing shouldBe 1
            val row = fx.memberRow(id)
            row[MemberTable.status] shouldBe MemberStatus.ACTIVE
            (row[MemberTable.lupabbMissingSince] != null) shouldBe true
            transaction { AccountTable.selectAll().where { AccountTable.memberId eq id }.count() } shouldBe 1
            val replan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_051, family = unique())), actor = admin)
            replan.rows.none { it.category == LupaBbCategory.MISSING && it.personNumber == 9_000_000_050 } shouldBe true
            val back = applyTo(planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_050, family = family)), actor = admin), admin)
            back.updated shouldBe 1
            fx.memberRow(id)[MemberTable.lupabbMissingSince] shouldBe null
        }

        test("an e-mail address or person number that became taken after the preview ends in a fixed code") {
            val admin = fx.admin()
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_052, family = unique(), email = "${TEST_EMAIL_PREFIX}taken@example.org"),
                            wireMember(personNumber = 9_000_000_053, family = unique()),
                            wireMember(personNumber = 9_000_000_054, family = unique()),
                        ),
                    actor = admin,
                )
            fx.member(email = "${TEST_EMAIL_PREFIX}taken@example.org", displayName = "Wer anders")
            fx.member(personNumber = 9_000_000_053, displayName = "Wer anders 2")
            val result = applyTo(plan, admin)
            result.created shouldBe 1
            result.failures.associate { it.personNumber to it.code } shouldBe
                mapOf(
                    9_000_000_052L to LupaBbRowReasonCode.EMAIL_TAKEN,
                    9_000_000_053L to LupaBbRowReasonCode.PERSON_NUMBER_TAKEN,
                )
            (fx.byPersonNumber(9_000_000_054) != null) shouldBe true
        }

        test("the run is logged with counters only; no audit entry carries a name, an address, a street or a person number") {
            val admin = fx.admin()
            val family = unique()
            val existing = fx.member(displayName = "Erika $family", personNumber = 9_000_000_055)
            val result =
                applyTo(
                    planOf(
                        dataset =
                            datasetOf(
                                wireMember(personNumber = 9_000_000_055, family = family),
                                wireMember(personNumber = 9_000_000_056, family = family),
                            ),
                        actor = admin,
                    ),
                    admin,
                )
            val created = fx.byPersonNumber(9_000_000_056)!![MemberTable.id]
            val runId = Uuid.parse(result.runId)
            transaction {
                LupaBbSyncRunTable.selectAll().where { LupaBbSyncRunTable.id eq runId }.single()
            }[LupaBbSyncRunTable.createdCount] shouldBe
                1
            val runAudit = auditOf(runId).single()
            runAudit[AuditLogEntryTable.entityType] shouldBe AuditEntityType.LUPABB_SYNC_RUN
            for (entry in auditOf(existing, created, runId)) {
                val text = (entry[AuditLogEntryTable.beforeSnapshot].orEmpty() + entry[AuditLogEntryTable.afterSnapshot].orEmpty())
                for (secret in listOf(family, "Musterweg", "example.org", "9000000055", "9000000056", "Braunschweig")) {
                    text shouldNotContain
                        secret
                }
            }
        }
    })
