package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.VoteEligibleVoterTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.server.dsgvo.GovernancePersonalData
import network.lapis.cloud.server.routes.requireDerivedAudienceCommittee
import network.lapis.cloud.server.routes.resolveAllActiveInvitationPart
import network.lapis.cloud.server.routes.resolveAllActivePostalRecipientIds
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.security.canManageCommittee
import network.lapis.cloud.server.security.canRecordForMeeting
import network.lapis.cloud.server.security.canSubmitMotion
import network.lapis.cloud.server.security.isActiveCommitteeMember
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.VoteStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Welle V1.9.112 -- what else a rule-based committee touches: the invitation to "all members of the committee" (PDF part and postal ids are
 * exactly the eligible set, anonymised members never get a letter), the access checks (membership is derived and live; leadership stays
 * role-based, so only BOARD/ADMIN lead the meetings of a rule committee) and the Art. 15/17 treatment of the frozen electorate.
 */
class RuleCommitteeAudienceTest :
    FunSpec({
        val fx = CommitteeSeatFixture()
        val rc = RuleCommitteeFixture(fx)

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }
        afterSpec {
            rc.cleanUp()
            fx.cleanUp()
        }

        fun group(count: Int): Pair<Uuid, List<Uuid>> {
            val manual = fx.committee()
            val people = (1..count).map { fx.member(displayName = "Einladung Person") }
            people.forEach { fx.seat(committee = manual, member = it) }
            return manual to people
        }

        fun currentOf(
            member: Uuid,
            role: AccountRole = AccountRole.MEMBER,
        ) = CurrentMember(memberId = member, role = role, status = MemberStatus.ACTIVE)

        // ── invitation ─────────────────────────────────────────────────────────────────────────────────────

        test("the invitation to a rule committee reaches exactly its eligible members; a manual committee is refused") {
            val (base, people) = group(3)
            val ruleBased = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            val part = resolveAllActiveInvitationPart(committeeId = ruleBased, part = 0)
            part.recipients.map { it.id } shouldContainExactlyInAnyOrder people.map { it.toString() }
            part.partCount shouldBe 1
            part.totalRecipients shouldBe people.size
            resolveAllActivePostalRecipientIds(committeeId = ruleBased, maxRecipients = 10) shouldContainExactlyInAnyOrder
                people.map { it.toString() }
            // the cap applies to the derived set as it does to the assembly
            shouldThrow<BadRequestException> { resolveAllActivePostalRecipientIds(committeeId = ruleBased, maxRecipients = 2) }
            shouldThrow<BadRequestException> { resolveAllActiveInvitationPart(committeeId = ruleBased, part = 5) }
            shouldThrow<BadRequestException> { resolveAllActiveInvitationPart(committeeId = base, part = 0) }
            shouldThrow<NotFoundException> { resolveAllActiveInvitationPart(committeeId = Uuid.random(), part = 0) }
            transaction { requireDerivedAudienceCommittee(ruleBased) }[CommitteeTable.id] shouldBe ruleBased
        }

        test("an anonymised or withdrawn member never receives a letter, and the part is cut in a stable order") {
            val (base, people) = group(4)
            val ruleBased = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            transaction {
                MemberTable.update({ MemberTable.id eq people[0] }) { it[anonymizedAt] = LocalDateTime(2026, 2, 1, 0, 0) }
                MemberTable.update({ MemberTable.id eq people[1] }) { it[status] = MemberStatus.WITHDRAWN }
            }
            val part = resolveAllActiveInvitationPart(committeeId = ruleBased, part = 0)
            part.recipients.map { it.id } shouldContainExactlyInAnyOrder listOf(people[2], people[3]).map { it.toString() }
            part.recipients.map { it.id } shouldBe part.recipients.map { it.id }.sorted()
        }

        // ── access ─────────────────────────────────────────────────────────────────────────────────────────

        test(
            "membership of a rule committee is derived and live: it lets a member submit a motion and bind a room; it grants no leadership",
        ) {
            val (base, people) = group(2)
            val ruleBased = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            val outsider = fx.member()
            val insider = currentOf(people[0])
            val stranger = currentOf(outsider)
            insider.canSubmitMotion(ruleBased) shouldBe true
            insider.isActiveCommitteeMember(ruleBased) shouldBe true
            stranger.canSubmitMotion(ruleBased) shouldBe false
            stranger.isActiveCommitteeMember(ruleBased) shouldBe false
            // leadership and recording stay role based: a rule committee has no offices, so only BOARD/ADMIN lead its meetings
            insider.canManageCommittee(ruleBased) shouldBe false
            insider.canRecordForMeeting(ruleBased) shouldBe false
            currentOf(outsider, AccountRole.BOARD).canRecordForMeeting(ruleBased) shouldBe true
            // live: the member leaves the rule's circle
            transaction { MemberTable.update({ MemberTable.id eq people[0] }) { it[status] = MemberStatus.WITHDRAWN } }
            insider.canSubmitMotion(ruleBased) shouldBe false
            insider.isActiveCommitteeMember(ruleBased) shouldBe false
        }

        test("a broken stored rule grants nothing (false, never an exception out of an access check)") {
            val (base, people) = group(1)
            val ruleBased = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            transaction {
                CommitteeTable.update({ CommitteeTable.id eq ruleBased }) {
                    it[ruleJson] =
                        """{"v":1,"op":"ALL","conditions":[{"type":"BOGUS"}]}"""
                }
            }
            currentOf(people[0]).canSubmitMotion(ruleBased) shouldBe false
            currentOf(people[0]).isActiveCommitteeMember(ruleBased) shouldBe false
        }

        test("a manual seat still works exactly as before for submitting a motion and for the room binding") {
            val manual = fx.committee()
            val seated = fx.member()
            fx.seat(committee = manual, member = seated, role = CommitteeRole.MEMBER)
            currentOf(seated).canSubmitMotion(manual) shouldBe true
            currentOf(seated).isActiveCommitteeMember(manual) shouldBe true
            currentOf(fx.member()).canSubmitMotion(manual) shouldBe false
        }

        // ── data protection ────────────────────────────────────────────────────────────────────────────────

        test("the frozen electorate is part of the Art. 15 export (ids only) and is retained with a reason at Art. 17") {
            val (base, people) = group(1)
            val meeting = rc.meeting(committee = rc.ruleCommittee(ruleJson = rc.seatedIn(base)))
            val motion = rc.motion(committee = fx.committee(), meeting = meeting, submitter = people[0])
            val vote = rc.voteRow(meeting = meeting, motion = motion, opener = people[0], status = VoteStatus.OPEN)
            transaction {
                VoteTable.update({ VoteTable.id eq vote }) { it[electorateFrozenAt] = DbClock.nowLocalDateTime() }
                VoteEligibleVoterTable.insert {
                    it[id] = Uuid.random()
                    it[voteId] = vote
                    it[memberId] = people[0]
                }
            }
            val export = transaction { GovernancePersonalData.exportMember(people[0]) }.toString()
            (export.contains("voteEligibilities")) shouldBe true
            (export.contains(vote.toString())) shouldBe true
            val outcome =
                transaction { GovernancePersonalData.eraseMember(memberId = people[0], mode = ErasureMode.ANONYMIZE) }.single {
                    it.table ==
                        "vote_eligible_voter"
                }
            outcome.rowsRetained shouldBe 1
        }
    })
