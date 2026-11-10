package network.lapis.cloud.server.lupabb

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.LupaBbCategory
import network.lapis.cloud.shared.domain.LupaBbField
import network.lapis.cloud.shared.domain.LupaBbRowReasonCode
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.VotingRightSource
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private const val MITGLIED = 1L
private const val NEUMITGLIED = 2L
private const val FOERDERER = 3L
private const val SPENDER = 4L
private const val VERSTORBEN = 5L

/**
 * V1.9.298 -- "Neumitglied" is a member WITHOUT voting right, "Mitglied" one with: planning (difference, manual value, protected roles, deceased,
 * anonymised), applying (insert, update, never over a MANUAL value, fingerprint) and the guarantees that did not change (no account, no change
 * of the mapping of "Foerderer"/"Spender").
 */
class LupaBbVotingRightTest :
    FunSpec({
        val fx = LupaBbFixtures()
        beforeSpec { DatabaseConfig.connect() }
        beforeTest { LupaBbSuppression.configure(TEST_BOX) }
        afterTest { fx.cleanUp() }

        fun unique() = "Stimmrecht${Uuid.random().toString().take(8)}"

        fun setVotingRight(
            id: Uuid,
            value: Boolean,
            source: VotingRightSource,
        ) = transaction {
            MemberTable.update({ MemberTable.id eq id }) {
                it[votingRight] = value
                it[votingRightSource] = source
                it[votingRightChangedAt] = if (source == VotingRightSource.DEFAULT) null else LocalDateTime(2026, 11, 1, 10, 0)
            }
        }

        fun apply(
            plan: LupaBbPlan,
            admin: Uuid,
        ) = LupaBbApplier(chapterEnforced = false).apply(entry = entryOf(plan = plan, admin = admin), actor = fx.current(id = admin))

        test("a new record 'Neumitglied' becomes an ACTIVE member without voting right (source LUPABB), still without an account") {
            val admin = fx.admin()
            val family = unique()
            val plan =
                planOf(
                    dataset = datasetOf(wireMember(personNumber = 9_000_000_300, statusId = NEUMITGLIED, family = family)),
                    actor = admin,
                )
            plan.row(9_000_000_300).category shouldBe LupaBbCategory.NEW
            plan.row(9_000_000_300).diffs.map { it.field } shouldContain LupaBbField.VOTING_RIGHT
            apply(plan, admin).created shouldBe 1
            val row = fx.byPersonNumber(9_000_000_300)!!
            row[MemberTable.status] shouldBe MemberStatus.ACTIVE
            row[MemberTable.votingRight] shouldBe false
            row[MemberTable.votingRightSource] shouldBe VotingRightSource.LUPABB
            (row[MemberTable.votingRightChangedAt] != null) shouldBe true
            transaction { AccountTable.selectAll().where { AccountTable.memberId eq row[MemberTable.id] }.count() } shouldBe 0
        }

        test("a new record 'Mitglied' keeps the defaults (voting right, source DEFAULT, no change time)") {
            val admin = fx.admin()
            val plan =
                planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_301, statusId = MITGLIED, family = unique())), actor = admin)
            plan.row(9_000_000_301).diffs.map { it.field } shouldNotContain LupaBbField.VOTING_RIGHT
            apply(plan, admin).created shouldBe 1
            val row = fx.byPersonNumber(9_000_000_301)!!
            row[MemberTable.votingRight] shouldBe true
            row[MemberTable.votingRightSource] shouldBe VotingRightSource.DEFAULT
            row[MemberTable.votingRightChangedAt] shouldBe null
        }

        test("an existing member with the default value: 'Neumitglied' is a difference and is applied, audited with the fixed key only") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(displayName = "Erika $family", personNumber = 9_000_000_302)
            val plan =
                planOf(
                    dataset = datasetOf(wireMember(personNumber = 9_000_000_302, statusId = NEUMITGLIED, family = family)),
                    actor = admin,
                )
            plan.row(9_000_000_302).category shouldBe LupaBbCategory.CHANGED
            plan.row(9_000_000_302).diffs.single { it.field == LupaBbField.VOTING_RIGHT }.let {
                it.before shouldBe "true"
                it.after shouldBe "false"
            }
            apply(plan, admin).updated shouldBe 1
            val row = fx.memberRow(id)
            row[MemberTable.votingRight] shouldBe false
            row[MemberTable.votingRightSource] shouldBe VotingRightSource.LUPABB
            val audit = transaction { AuditLogEntryTable.selectAll().where { AuditLogEntryTable.entityId inList listOf(id) }.toList() }
            val after = audit.joinToString { it[AuditLogEntryTable.afterSnapshot].orEmpty() }
            (after.contains("member.voting_right.revoked_by_sync")) shouldBe true
            (after.contains("VOTING_RIGHT")) shouldBe true
        }

        test("'Mitglied' gives the voting right back to a LUPABB-sourced value") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(displayName = "Erika $family", personNumber = 9_000_000_303)
            setVotingRight(id, value = false, source = VotingRightSource.LUPABB)
            val plan =
                planOf(dataset = datasetOf(wireMember(personNumber = 9_000_000_303, statusId = MITGLIED, family = family)), actor = admin)
            apply(plan, admin).updated shouldBe 1
            fx.memberRow(id)[MemberTable.votingRight] shouldBe true
        }

        test("a MANUAL value is never a field difference, only an information line, and is never overwritten") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(displayName = "Erika $family", personNumber = 9_000_000_304)
            setVotingRight(id, value = true, source = VotingRightSource.MANUAL)
            val plan =
                planOf(
                    dataset = datasetOf(wireMember(personNumber = 9_000_000_304, statusId = NEUMITGLIED, family = family)),
                    actor = admin,
                )
            val row = plan.row(9_000_000_304)
            row.diffs.map { it.field } shouldNotContain LupaBbField.VOTING_RIGHT
            row.category shouldNotBe LupaBbCategory.ERROR // an information line, never an error or "action needed"
            row.reasons.single { it.code == LupaBbRowReasonCode.VOTING_RIGHT_MANUAL }.param shouldBe "true>false"
            apply(plan, admin)
            fx.memberRow(id)[MemberTable.votingRight] shouldBe true
            fx.memberRow(id)[MemberTable.votingRightSource] shouldBe VotingRightSource.MANUAL
        }

        test(
            "a member with a management role is never written: the difference lands in 'by hand' (ADMIN_ROLE_MANUAL), nothing is applied",
        ) {
            val admin = fx.admin()
            val family = unique()
            val board = fx.member(displayName = "Erika $family", personNumber = 9_000_000_305, role = AccountRole.BOARD)
            val plan =
                planOf(
                    dataset = datasetOf(wireMember(personNumber = 9_000_000_305, statusId = NEUMITGLIED, family = family)),
                    actor = admin,
                )
            val row = plan.row(9_000_000_305)
            row.reasons.map { it.code } shouldContain LupaBbRowReasonCode.ADMIN_ROLE_MANUAL
            apply(plan, admin)
            fx.memberRow(board)[MemberTable.votingRight] shouldBe true
        }

        test("a deceased member is not touched (open point Q3 default: the sync never writes the voting right of a deceased member)") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(displayName = "Erika $family", personNumber = 9_000_000_306, status = MemberStatus.DECEASED)
            val plan =
                planOf(
                    dataset = datasetOf(wireMember(personNumber = 9_000_000_306, statusId = NEUMITGLIED, family = family)),
                    actor = admin,
                )
            plan.row(9_000_000_306).diffs.map { it.field } shouldNotContain LupaBbField.VOTING_RIGHT
            apply(plan, admin)
            fx.memberRow(id)[MemberTable.votingRight] shouldBe true
        }

        test("an anonymised member is skipped entirely") {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(displayName = "Erika $family", personNumber = 9_000_000_307, anonymized = true)
            val plan =
                planOf(
                    dataset = datasetOf(wireMember(personNumber = 9_000_000_307, statusId = NEUMITGLIED, family = family)),
                    actor = admin,
                )
            plan.row(9_000_000_307).category shouldBe LupaBbCategory.SKIPPED
            apply(plan, admin)
            fx.memberRow(id)[MemberTable.votingRight] shouldBe true
        }

        test(
            "a change by hand between the preview and the apply makes the record CHANGED_SINCE_PREVIEW (the fingerprint covers voting right and source)",
        ) {
            val admin = fx.admin()
            val family = unique()
            val id = fx.member(displayName = "Erika $family", personNumber = 9_000_000_308)
            val plan =
                planOf(
                    dataset = datasetOf(wireMember(personNumber = 9_000_000_308, statusId = NEUMITGLIED, family = family)),
                    actor = admin,
                )
            setVotingRight(id, value = true, source = VotingRightSource.MANUAL)
            val result = apply(plan, admin)
            result.errors shouldBe 1
            result.failures.single().code shouldBe LupaBbRowReasonCode.CHANGED_SINCE_PREVIEW
            fx.memberRow(id)[MemberTable.votingRightSource] shouldBe VotingRightSource.MANUAL
        }

        test("'Foerderer' and 'Spender' records neither get nor lose a voting right (the sync leaves it alone) and create no account") {
            val admin = fx.admin()
            val plan =
                planOf(
                    dataset =
                        datasetOf(
                            wireMember(personNumber = 9_000_000_309, statusId = FOERDERER, family = unique()),
                            wireMember(personNumber = 9_000_000_310, statusId = SPENDER, family = unique()),
                        ),
                    actor = admin,
                )
            apply(plan, admin).created shouldBe 2
            listOf(9_000_000_309L, 9_000_000_310L).forEach { number ->
                val row = fx.byPersonNumber(number)!!
                row[MemberTable.votingRight] shouldBe true
                row[MemberTable.votingRightSource] shouldBe VotingRightSource.DEFAULT
                transaction { AccountTable.selectAll().where { AccountTable.memberId eq row[MemberTable.id] }.count() } shouldBe 0
            }
            fx.byPersonNumber(9_000_000_309)!![MemberTable.status] shouldBe MemberStatus.FRIEND
            fx.byPersonNumber(9_000_000_310)!![MemberTable.status] shouldBe MemberStatus.DONOR
        }
    })
