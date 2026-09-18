package network.lapis.cloud.server.security

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * "committee-role-titles" wave -- [canManageCommittee]/[canRecordForMeeting] gain three new
 * [CommitteeRole] placements ([CommitteeRole.GENERAL_SECRETARY]/[CommitteeRole.MANAGING_DIRECTOR]/
 * [CommitteeRole.PRESS_SPOKESPERSON]) without introducing any new authorization semantics -- both
 * predicates are still exactly the same `isPrivileged || hasCommitteeRole(...)` shape, only the
 * role set widened. This is the first dedicated test file for [GovernanceAuthorization] -- no prior
 * coverage existed for [canManageCommittee]/[canRecordForMeeting] directly (only indirectly via
 * higher-level service tests), so the pre-existing five roles are covered here too, as a
 * regression baseline.
 */
class GovernanceAuthorizationTest :
    FunSpec({
        val createdCommitteeIds = mutableListOf<Uuid>()
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdCommitteeIds.isNotEmpty()) {
                    CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId inList createdCommitteeIds }
                    CommitteeTable.deleteWhere { CommitteeTable.id inList createdCommitteeIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createTestMember(email: String): Uuid {
            val memberId = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[id] = memberId
                    it[displayName] = "GovernanceAuthorizationTest Testmitglied"
                    it[MemberTable.email] = email
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[AccountTable.memberId] = memberId
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += memberId
            return memberId
        }

        fun createTestCommittee(): Uuid {
            val committeeId = Uuid.random()
            transaction {
                CommitteeTable.insert {
                    it[id] = committeeId
                    it[name] = "GovernanceAuthorizationTest Committee $committeeId"
                    it[type] = CommitteeType.WORKING_GROUP
                    it[description] = "Test"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2020, 1, 1, 0, 0)
                }
            }
            createdCommitteeIds += committeeId
            return committeeId
        }

        fun seatMember(
            committeeId: Uuid,
            memberId: Uuid,
            role: CommitteeRole,
        ) {
            transaction {
                CommitteeMembershipTable.insert {
                    it[id] = Uuid.random()
                    it[CommitteeMembershipTable.committeeId] = committeeId
                    it[CommitteeMembershipTable.memberId] = memberId
                    it[CommitteeMembershipTable.role] = role
                    it[since] = LocalDate(2020, 1, 1)
                    it[until] = null
                }
            }
        }

        fun currentMemberFor(memberId: Uuid) = CurrentMember(memberId = memberId, role = AccountRole.MEMBER, status = MemberStatus.ACTIVE)

        // ── canManageCommittee ────────────────────────────────────────────────────────────────

        test("canManageCommittee: GENERAL_SECRETARY is granted (new leadership-tier placement)") {
            val committeeId = createTestCommittee()
            val memberId = createTestMember("gensec-manage-${Uuid.random()}@example.org")
            seatMember(committeeId, memberId, CommitteeRole.GENERAL_SECRETARY)
            currentMemberFor(memberId).canManageCommittee(committeeId) shouldBe true
        }

        test("canManageCommittee: MANAGING_DIRECTOR is NOT granted") {
            val committeeId = createTestCommittee()
            val memberId = createTestMember("md-manage-${Uuid.random()}@example.org")
            seatMember(committeeId, memberId, CommitteeRole.MANAGING_DIRECTOR)
            currentMemberFor(memberId).canManageCommittee(committeeId) shouldBe false
        }

        test("canManageCommittee: PRESS_SPOKESPERSON is NOT granted") {
            val committeeId = createTestCommittee()
            val memberId = createTestMember("press-manage-${Uuid.random()}@example.org")
            seatMember(committeeId, memberId, CommitteeRole.PRESS_SPOKESPERSON)
            currentMemberFor(memberId).canManageCommittee(committeeId) shouldBe false
        }

        test("canManageCommittee: pre-existing CHAIR/DEPUTY_CHAIR still granted, SECRETARY/MEMBER/ASSESSOR still not") {
            val committeeId = createTestCommittee()
            val chair = createTestMember("chair-${Uuid.random()}@example.org")
            val deputy = createTestMember("deputy-${Uuid.random()}@example.org")
            val secretary = createTestMember("secretary-manage-${Uuid.random()}@example.org")
            val member = createTestMember("member-manage-${Uuid.random()}@example.org")
            val assessor = createTestMember("assessor-manage-${Uuid.random()}@example.org")
            seatMember(committeeId, chair, CommitteeRole.CHAIR)
            seatMember(committeeId, deputy, CommitteeRole.DEPUTY_CHAIR)
            seatMember(committeeId, secretary, CommitteeRole.SECRETARY)
            seatMember(committeeId, member, CommitteeRole.MEMBER)
            seatMember(committeeId, assessor, CommitteeRole.ASSESSOR)

            currentMemberFor(chair).canManageCommittee(committeeId) shouldBe true
            currentMemberFor(deputy).canManageCommittee(committeeId) shouldBe true
            currentMemberFor(secretary).canManageCommittee(committeeId) shouldBe false
            currentMemberFor(member).canManageCommittee(committeeId) shouldBe false
            currentMemberFor(assessor).canManageCommittee(committeeId) shouldBe false
        }

        // ── canRecordForMeeting ───────────────────────────────────────────────────────────────

        test("canRecordForMeeting: GENERAL_SECRETARY and MANAGING_DIRECTOR are granted, PRESS_SPOKESPERSON is not") {
            val committeeId = createTestCommittee()
            val gensec = createTestMember("gensec-record-${Uuid.random()}@example.org")
            val md = createTestMember("md-record-${Uuid.random()}@example.org")
            val press = createTestMember("press-record-${Uuid.random()}@example.org")
            seatMember(committeeId, gensec, CommitteeRole.GENERAL_SECRETARY)
            seatMember(committeeId, md, CommitteeRole.MANAGING_DIRECTOR)
            seatMember(committeeId, press, CommitteeRole.PRESS_SPOKESPERSON)

            currentMemberFor(gensec).canRecordForMeeting(committeeId) shouldBe true
            currentMemberFor(md).canRecordForMeeting(committeeId) shouldBe true
            currentMemberFor(press).canRecordForMeeting(committeeId) shouldBe false
        }

        test("canRecordForMeeting: pre-existing CHAIR/DEPUTY_CHAIR/SECRETARY still granted, MEMBER/ASSESSOR still not") {
            val committeeId = createTestCommittee()
            val chair = createTestMember("chair-record-${Uuid.random()}@example.org")
            val secretary = createTestMember("secretary-record-${Uuid.random()}@example.org")
            val member = createTestMember("member-record-${Uuid.random()}@example.org")
            seatMember(committeeId, chair, CommitteeRole.CHAIR)
            seatMember(committeeId, secretary, CommitteeRole.SECRETARY)
            seatMember(committeeId, member, CommitteeRole.MEMBER)

            currentMemberFor(chair).canRecordForMeeting(committeeId) shouldBe true
            currentMemberFor(secretary).canRecordForMeeting(committeeId) shouldBe true
            currentMemberFor(member).canRecordForMeeting(committeeId) shouldBe false
        }

        // ── isActiveCommitteeMember / canSubmitMotion stay role-set-agnostic (CommitteeRole.entries) ──

        test("isActiveCommitteeMember: any of the three new roles counts as active membership") {
            val committeeId = createTestCommittee()
            val press = createTestMember("press-active-${Uuid.random()}@example.org")
            seatMember(committeeId, press, CommitteeRole.PRESS_SPOKESPERSON)
            currentMemberFor(press).isActiveCommitteeMember(committeeId) shouldBe true
        }
    })
