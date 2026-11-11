package network.lapis.cloud.server.lupabb

import io.kotest.assertions.throwables.shouldThrow
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
import network.lapis.cloud.server.security.PeerDeniedSignal
import network.lapis.cloud.server.security.SessionStore
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.LupaBbApplyResultDto
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbField
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

        test("a new member with the LupaBB status Foerderer is a DONOR without an account and without friend_since") {
            val admin = fx.admin()
            val result =
                applyTo(
                    planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_063, statusId = 3, family = unique())), actor = admin),
                    admin,
                )
            result.created shouldBe 1
            val row = fx.byPersonNumber(9_000_000_063)!!
            row[MemberTable.status] shouldBe MemberStatus.DONOR
            row[MemberTable.friendSince] shouldBe null
            transaction { AccountTable.selectAll().where { AccountTable.memberId eq row[MemberTable.id] }.count() } shouldBe 0
        }

        test("a legacy FRIEND reported as Foerderer becomes DONOR with a LUPABB_SYNC history entry; friend_since stays") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(status = MemberStatus.FRIEND, displayName = "Erika $family", personNumber = 9_000_000_340)
            val friendSinceBefore = fx.memberRow(id)[MemberTable.friendSince]
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_340, statusId = 3, family = family)), actor = admin)
            plan.row(9_000_000_340).category shouldBe LupaBbCategory.CHANGED
            fx.memberRow(id)[MemberTable.status] shouldBe MemberStatus.FRIEND // the preview writes nothing
            val result = applyTo(plan, admin)
            result.updated shouldBe 1
            result.errors shouldBe 0
            fx.memberRow(id)[MemberTable.status] shouldBe MemberStatus.DONOR
            fx.memberRow(id)[MemberTable.friendSince] shouldBe friendSinceBefore
            val history = transaction { MemberStatusHistoryTable.selectAll().where { MemberStatusHistoryTable.memberId eq id }.toList() }
            history.first { it[MemberStatusHistoryTable.sourceKind] == "LUPABB_SYNC" }[MemberStatusHistoryTable.status] shouldBe "DONOR"
        }

        test("a role granted to a FRIEND after the preview stops the move to DONOR") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(status = MemberStatus.FRIEND, displayName = "Erika $family", personNumber = 9_000_000_341)
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_341, statusId = 3, family = family)), actor = admin)
            transaction {
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.BOARD
                }
            }
            val result = applyTo(plan, admin)
            result.failures.single().code shouldBe LupaBbRowReasonCode.CHANGED_SINCE_PREVIEW
            fx.memberRow(id)[MemberTable.status] shouldBe MemberStatus.FRIEND
        }

        test("the applier refuses FRIEND to DONOR for a member without the record's person number, even with a forged plan") {
            val admin = fx.admin()
            val family = unique()
            val email = "${TEST_EMAIL_PREFIX}selfreg-apply@example.org"
            val id = fx.member(status = MemberStatus.FRIEND, displayName = "Erika $family", email = email)
            val genuine =
                planOf(
                    dataset = datasetOf(wireMember(personNumber = 9_000_000_073, statusId = 3, family = family, email = email)),
                    actor = admin,
                )
            val row = genuine.row(9_000_000_073)
            row.category shouldBe LupaBbCategory.ERROR
            val forged =
                row.copy(
                    category = LupaBbCategory.CHANGED,
                    diffs =
                        listOf(
                            FieldDiff(
                                field = LupaBbField.STATUS,
                                before = MemberStatus.FRIEND.name,
                                after = MemberStatus.DONOR.name,
                            ),
                        ),
                )
            val plan = LupaBbPlan(rows = listOf(forged), notices = genuine.notices, dataSha256 = genuine.dataSha256)
            val result = applyTo(plan, admin)
            result.updated shouldBe 0
            result.failures.single().code shouldBe LupaBbRowReasonCode.STATUS_TRANSITION_NOT_ALLOWED
            fx.memberRow(id)[MemberTable.status] shouldBe MemberStatus.FRIEND
        }

        test("the applier itself refuses a FRIEND to ACTIVE move that bypassed the planner") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(status = MemberStatus.FRIEND, displayName = "Erika $family", personNumber = 9_000_000_342)
            val genuine =
                planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_342, statusId = 3, family = family)), actor = admin)
            val row = genuine.row(9_000_000_342)
            val forged =
                row.copy(
                    record = row.record!!.copy(status = MemberStatus.ACTIVE),
                    diffs = row.diffs.map { if (it.field == LupaBbField.STATUS) it.copy(after = MemberStatus.ACTIVE.name) else it },
                )
            val plan = LupaBbPlan(rows = listOf(forged), notices = genuine.notices, dataSha256 = genuine.dataSha256)
            val result = applyTo(plan, admin)
            result.failures.single().code shouldBe LupaBbRowReasonCode.STATUS_TRANSITION_NOT_ALLOWED
            fx.memberRow(id)[MemberTable.status] shouldBe MemberStatus.FRIEND
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

        // ---- "LupaBB-Werte uebernehmen": the manual take-over of a held-back row ----

        fun takeOver(
            plan: LupaBbPlan,
            personNumber: Long,
            admin: Uuid,
            enforced: Boolean = false,
        ) = LupaBbApplier(chapterEnforced = enforced).applyProtected(row = plan.row(personNumber), actor = fx.current(id = admin))

        fun snapshotOf(id: Uuid): List<Any?> {
            val row = fx.memberRow(id)
            return listOf(row[MemberTable.street], row[MemberTable.city], row[MemberTable.postalCode], row[MemberTable.status])
        }

        test("a manual take-over writes address and status of a BOARD member, never the role, and audits keys only") {
            val admin = fx.admin()
            val family = unique()
            val board =
                fx.member(
                    role = AccountRole.BOARD,
                    displayName = "Erika $family",
                    personNumber = 9_000_000_400,
                    street = "Alte Strasse",
                )
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_400, family = family, statusId = 6)), actor = admin)
            val row = plan.row(9_000_000_400)
            row.protectedDiffs.map { it.field } shouldContain LupaBbField.STREET
            row.protectedDiffs.map { it.field } shouldContain LupaBbField.STATUS
            row.protectedBlocker shouldBe null
            val outcome = takeOver(plan, 9_000_000_400, admin)
            outcome.failure shouldBe null
            outcome.revokeSessions shouldBe true
            fx.memberRow(board)[MemberTable.street] shouldBe "Musterweg 1"
            fx.memberRow(board)[MemberTable.status] shouldBe MemberStatus.WITHDRAWN
            transaction { AccountTable.selectAll().where { AccountTable.memberId eq board }.single() }[AccountTable.role] shouldBe
                AccountRole.BOARD
            val history = transaction { MemberStatusHistoryTable.selectAll().where { MemberStatusHistoryTable.memberId eq board }.toList() }
            history.first { it[MemberStatusHistoryTable.sourceKind] == "LUPABB_SYNC" }[MemberStatusHistoryTable.status] shouldBe "WITHDRAWN"
            val texts = auditOf(board).map { it[AuditLogEntryTable.afterSnapshot].orEmpty() }
            texts.any { it.contains("lupabbManualApply") } shouldBe true
            for (text in texts) {
                for (secret in listOf(family, "Musterweg", "Alte Strasse", "9000000400", "Braunschweig")) text shouldNotContain secret
            }
        }

        test("a manual take-over refuses when the member changed after the preview") {
            val admin = fx.admin()
            val family = unique()
            val board = fx.member(role = AccountRole.BOARD, displayName = "Erika $family", personNumber = 9_000_000_401, street = "Alt")
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_401, family = family)), actor = admin)
            transaction { MemberTable.update({ MemberTable.id eq board }) { it[street] = "Handbearbeitet" } }
            takeOver(plan, 9_000_000_401, admin).failure shouldBe LupaBbRowReasonCode.CHANGED_SINCE_PREVIEW
            fx.memberRow(board)[MemberTable.street] shouldBe "Handbearbeitet"
        }

        test("the own record is never taken over, not even with a forged plan") {
            val family = unique()
            val self = fx.member(role = AccountRole.ADMIN, displayName = "Erika $family", personNumber = 9_000_000_402, street = "Alt")
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_402, family = family)), actor = self)
            plan.row(9_000_000_402).isOwnRecord shouldBe true
            plan.row(9_000_000_402).protectedBlocker shouldBe LupaBbRowReasonCode.OWN_RECORD
            takeOver(plan, 9_000_000_402, self).failure shouldBe LupaBbRowReasonCode.OWN_RECORD
            fx.memberRow(self)[MemberTable.street] shouldBe "Alt"
        }

        test("a status that is not allowed writes NOTHING of the row, not even the address") {
            val admin = fx.admin()
            val family = unique()
            val id =
                fx.member(
                    role = AccountRole.BOARD,
                    status = MemberStatus.FRIEND,
                    displayName = "Erika $family",
                    personNumber = 9_000_000_403,
                    street = "Alt",
                )
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_403, family = family, statusId = 1)), actor = admin)
            plan.row(9_000_000_403).protectedBlocker shouldBe LupaBbRowReasonCode.STATUS_TRANSITION_NOT_ALLOWED
            val before = snapshotOf(id)
            takeOver(plan, 9_000_000_403, admin).failure shouldBe LupaBbRowReasonCode.STATUS_TRANSITION_NOT_ALLOWED
            snapshotOf(id) shouldBe before
        }

        test("an ADMIN target never gets a login-blocking status without a second administrator: nothing is written") {
            val admin = fx.admin()
            val family = unique()
            val target = fx.member(role = AccountRole.ADMIN, displayName = "Erika $family", personNumber = 9_000_000_404, street = "Alt")
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_404, family = family, statusId = 6)), actor = admin)
            plan.row(9_000_000_404).protectedBlocker shouldBe LupaBbRowReasonCode.PEER_APPROVAL_REQUIRED
            val before = snapshotOf(target)
            shouldThrow<PeerDeniedSignal> { takeOver(plan, 9_000_000_404, admin) }
            snapshotOf(target) shouldBe before
        }

        test("an ADMIN target gets address changes directly and is told (notifyTargetAdmin)") {
            val admin = fx.admin()
            val family = unique()
            val target = fx.member(role = AccountRole.ADMIN, displayName = "Erika $family", personNumber = 9_000_000_405, street = "Alt")
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_405, family = family)), actor = admin)
            val outcome = takeOver(plan, 9_000_000_405, admin)
            outcome.failure shouldBe null
            outcome.notifyTargetAdmin shouldBe true
            fx.memberRow(target)[MemberTable.street] shouldBe "Musterweg 1"
        }

        test("a donor cannot hold a management role: ROLE_BLOCKS_STATUS, nothing is written") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(role = AccountRole.TREASURER, displayName = "Erika $family", personNumber = 9_000_000_406, street = "Alt")
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_406, family = family, statusId = 4)), actor = admin)
            plan.row(9_000_000_406).protectedBlocker shouldBe LupaBbRowReasonCode.ROLE_BLOCKS_STATUS
            val before = snapshotOf(id)
            takeOver(plan, 9_000_000_406, admin).failure shouldBe LupaBbRowReasonCode.ROLE_BLOCKS_STATUS
            snapshotOf(id) shouldBe before
        }

        test("activating without a regional chapter is CHAPTER_REQUIRED when the chapter duty is on") {
            val admin = fx.admin()
            val family = unique()
            fx.chapter("Kapitel${unique()}")
            val id =
                fx.member(
                    role = AccountRole.BOARD,
                    status = MemberStatus.WITHDRAWN,
                    displayName = "Erika $family",
                    personNumber = 9_000_000_407,
                    street = "Alt",
                )
            val plan =
                planOf(
                    dataset = datasetOf(wireMember(personNumber = 9_000_000_407, family = family, statusId = 1)),
                    actor = admin,
                    enforced = true,
                )
            plan.row(9_000_000_407).protectedBlocker shouldBe LupaBbRowReasonCode.CHAPTER_REQUIRED
            val before = snapshotOf(id)
            takeOver(plan, 9_000_000_407, admin, enforced = true).failure shouldBe LupaBbRowReasonCode.CHAPTER_REQUIRED
            snapshotOf(id) shouldBe before
        }

        test("an anonymised member is not taken over") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(role = AccountRole.BOARD, displayName = "Erika $family", personNumber = 9_000_000_408, street = "Alt")
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_408, family = family)), actor = admin)
            transaction {
                MemberTable.update({ MemberTable.id eq id }) {
                    it[anonymizedAt] =
                        network.lapis.cloud.server.time.ServerClock
                            .now()
                }
            }
            takeOver(plan, 9_000_000_408, admin).failure shouldBe LupaBbRowReasonCode.ANONYMIZED
        }

        test("the bulk run still holds a management member back (ADMIN_ROLE_MANUAL) and does not write the address") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(role = AccountRole.BOARD, displayName = "Erika $family", personNumber = 9_000_000_409, street = "Alt")
            val plan = planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_409, family = family)), actor = admin)
            applyTo(plan, admin).errors shouldBe 0
            fx.memberRow(id)[MemberTable.street] shouldBe "Alt"
        }

        test("the sync takes over the end of a friendship: FRIEND to WITHDRAWN and FRIEND to DECEASED, with the person number only") {
            val admin = fx.admin()
            val family = unique()
            val gone = fx.member(status = MemberStatus.FRIEND, displayName = "Erika $family", personNumber = 9_000_000_410)
            val dead = fx.member(status = MemberStatus.FRIEND, displayName = "Erika $family", personNumber = 9_000_000_411)
            val result =
                applyTo(
                    planOf(
                        dataset =
                            datasetOf(
                                wireMember(personNumber = 9_000_000_410, family = family, statusId = 6),
                                wireMember(personNumber = 9_000_000_411, family = family, statusId = 5),
                            ),
                        actor = admin,
                    ),
                    admin,
                )
            result.updated shouldBe 2
            fx.memberRow(gone)[MemberTable.status] shouldBe MemberStatus.WITHDRAWN
            fx.memberRow(dead)[MemberTable.status] shouldBe MemberStatus.DECEASED
        }

        test("the title is written to member.title (not the side table) and a hand-set title is only replaced by the confirmed apply") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(displayName = "Erika $family", personNumber = 9_000_000_064, title = "Prof.")
            val plan =
                planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_064, family = family, title = " Dr. ")), actor = admin)
            // planning is read only: the hand-set title is untouched until the apply
            fx.memberRow(id)[MemberTable.title] shouldBe "Prof."
            applyTo(plan, admin).updated shouldBe 1
            fx.memberRow(id)[MemberTable.title] shouldBe "Dr."
        }

        test("a new record with a title creates the member with member.title set") {
            val admin = fx.admin()
            val family = unique()
            applyTo(
                planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_065, family = family, title = "Dipl.-Ing.")), actor = admin),
                admin,
            ).created shouldBe 1
            fx.byPersonNumber(9_000_000_065)!![MemberTable.title] shouldBe "Dipl.-Ing."
        }
    })
