package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.dsgvo.FoundationPersonalData
import network.lapis.cloud.server.member.MemberVotingRights
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.VotingRightAuditEvent
import network.lapis.cloud.shared.domain.VotingRightSource
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val NOW = LocalDateTime(2026, 11, 9, 10, 0)

/**
 * Welle V1.9.298 -- the electorate is membership AND voting right, on H2 and PostgreSQL alike: the three kinds of committee, the live floor of
 * every ballot (`requireVotingMembership`), the single writer `MemberVotingRights`, the SQL shape of the columns and the DSGVO erasure.
 */
abstract class VotingRightElectorateScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = CommitteeSeatFixture()
        val rc = RuleCommitteeFixture(fx)
        val admin = lazy { fx.member(role = AccountRole.ADMIN, displayName = "Stimmrecht Administration") }

        beforeSpec { db.activate() }
        beforeTest { db.assertActive() }
        afterSpec {
            rc.cleanUp()
            fx.cleanUp()
            db.deactivate()
        }

        fun actor() = CurrentMember(memberId = admin.value, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE)

        fun row(id: Uuid) = transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single() }

        fun revoke(id: Uuid) =
            transaction {
                MemberVotingRights.setManualLocked(actor = actor(), targetRow = row(id), value = false, now = NOW)
            }

        // ---- the three kinds of committee --------------------------------------------------------------------------

        test("manual committee: a member without voting right stays a member but is not in the electorate") {
            val committee = fx.committee()
            val voter = fx.member()
            val silent = fx.member()
            fx.seat(committee = committee, member = voter)
            fx.seat(committee = committee, member = silent)
            revoke(silent) shouldBe VotingRightAuditEvent.REVOKED
            val date = rc.today
            transaction {
                val committeeRow = fx.committeeRow(committee)
                eligibleMemberIds(committeeRow = committeeRow, scheduledDate = date) shouldBe setOf(voter, silent)
                electorateMemberIds(committeeRow = committeeRow, scheduledDate = date) shouldBe setOf(voter)
                // the point query agrees with the set query for everybody (parity)
                listOf(voter, silent).forEach { m ->
                    isCommitteeVoter(committeeRow = committeeRow, scheduledDate = date, memberId = m) shouldBe
                        (m in electorateMemberIds(committeeRow = committeeRow, scheduledDate = date))
                    isCommitteeEligible(committeeRow = committeeRow, scheduledDate = date, memberId = m) shouldBe true
                }
            }
        }

        test("rule committee: the electorate excludes the member without voting right; the live fallback of the quorum follows it") {
            val base = fx.committee()
            val a = fx.member()
            val b = fx.member()
            fx.seat(committee = base, member = a)
            fx.seat(committee = base, member = b)
            revoke(b)
            val ruleBased = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            val meeting = rc.meeting(committee = ruleBased)
            transaction {
                val committeeRow = fx.committeeRow(ruleBased)
                electorateMemberIds(committeeRow = committeeRow, scheduledDate = rc.today) shouldBe setOf(a)
                countEligible(committeeRow = committeeRow) shouldBe 2
                countElectorate(committeeRow = committeeRow) shouldBe 1
                isCommitteeVoter(committeeRow = committeeRow, scheduledDate = rc.today, memberId = b) shouldBe false
                val live = frozenOrLiveElectorate(meetingId = meeting, committeeRow = committeeRow, scheduledDate = rc.today)
                live.frozen shouldBe false
                live.memberIds shouldBe setOf(a)
            }
        }

        test("general assembly: ACTIVE and voting right; a withdrawn member is out whatever the voting right says") {
            val ga = fx.committee(type = CommitteeType.GENERAL_ASSEMBLY)
            val voter = fx.member()
            val silent = fx.member()
            val withdrawn = fx.member(status = MemberStatus.WITHDRAWN)
            revoke(silent)
            transaction {
                val committeeRow = fx.committeeRow(ga)
                val electorate = electorateMemberIds(committeeRow = committeeRow, scheduledDate = rc.today)
                electorate shouldContain voter
                electorate shouldNotContain silent
                electorate shouldNotContain withdrawn
                eligibleMemberIds(committeeRow = committeeRow, scheduledDate = rc.today) shouldContain silent
                countElectorate(committeeRow = committeeRow) shouldBe
                    countEligible(committeeRow = committeeRow) -
                    MemberTable
                        .selectAll()
                        .where { (MemberTable.status eq MemberStatus.ACTIVE) and (MemberTable.votingRight eq false) }
                        .count()
                        .toInt()
            }
        }

        // ---- the live floor ----------------------------------------------------------------------------------------

        test("requireVotingMembership: ACTIVE and voting right, nothing else") {
            val voter = fx.member()
            val silent = fx.member()
            val friend = fx.member(status = MemberStatus.FRIEND)
            val donor = fx.member(status = MemberStatus.DONOR)
            revoke(silent)
            transaction { requireVotingMembership(memberId = voter) shouldBe MemberStatus.ACTIVE }
            listOf(silent, friend, donor, Uuid.random()).forEach { who ->
                shouldThrow<ForbiddenException> { transaction { requireVotingMembership(memberId = who) } }
            }
        }

        // ---- the single writer -------------------------------------------------------------------------------------

        test("MemberVotingRights: manual change, no-op, release, and the sync never overwrites a manual value") {
            val m = fx.member()
            // default: sync may write, source becomes LUPABB
            transaction {
                MemberVotingRights.applyFromSyncLocked(targetRow = row(m), value = false, now = NOW) shouldBe
                    VotingRightAuditEvent.REVOKED_BY_SYNC
            }
            row(m)[MemberTable.votingRightSource] shouldBe VotingRightSource.LUPABB
            row(m)[MemberTable.votingRight] shouldBe false
            // an administrator sets it by hand
            transaction { MemberVotingRights.setManualLocked(actor = actor(), targetRow = row(m), value = true, now = NOW) } shouldBe
                VotingRightAuditEvent.GRANTED
            row(m)[MemberTable.votingRightSource] shouldBe VotingRightSource.MANUAL
            row(m)[MemberTable.votingRightChangedAt] shouldBe NOW
            // same value again: nothing
            transaction { MemberVotingRights.setManualLocked(actor = actor(), targetRow = row(m), value = true, now = NOW) } shouldBe null
            // the sync does not overwrite a MANUAL value
            transaction { MemberVotingRights.applyFromSyncLocked(targetRow = row(m), value = false, now = NOW) } shouldBe null
            row(m)[MemberTable.votingRight] shouldBe true
            // released: the value stays, the sync may take over
            transaction { MemberVotingRights.releaseToSyncLocked(actor = actor(), targetRow = row(m), now = NOW) } shouldBe
                VotingRightAuditEvent.RELEASED_TO_SYNC
            row(m)[MemberTable.votingRightSource] shouldBe VotingRightSource.LUPABB
            row(m)[MemberTable.votingRight] shouldBe true
            transaction { MemberVotingRights.releaseToSyncLocked(actor = actor(), targetRow = row(m), now = NOW) } shouldBe null
            transaction {
                MemberVotingRights.applyFromSyncLocked(targetRow = row(m), value = false, now = NOW) shouldBe
                    VotingRightAuditEvent.REVOKED_BY_SYNC
            }
        }

        test("MemberVotingRights: the sync never writes a deceased or an anonymised member, and nobody changes their own row") {
            val deceased = fx.member(status = MemberStatus.DECEASED)
            transaction { MemberVotingRights.applyFromSyncLocked(targetRow = row(deceased), value = false, now = NOW) } shouldBe null
            row(deceased)[MemberTable.votingRight] shouldBe true
            val anonymised = fx.member()
            transaction { MemberTable.update({ MemberTable.id eq anonymised }) { it[anonymizedAt] = NOW } }
            transaction { MemberVotingRights.applyFromSyncLocked(targetRow = row(anonymised), value = false, now = NOW) } shouldBe null
            shouldThrow<IllegalArgumentException> {
                transaction { MemberVotingRights.setManualLocked(actor = actor(), targetRow = row(admin.value), value = false, now = NOW) }
            }
        }

        // ---- SQL shape and erasure ---------------------------------------------------------------------------------

        test("the CHECKs: source DEFAULT only with voting right TRUE and no change time; an unknown source is refused") {
            val m = fx.member()
            shouldThrow<ExposedSQLException> {
                transaction { MemberTable.update({ MemberTable.id eq m }) { it[votingRight] = false } }
            }
            shouldThrow<ExposedSQLException> {
                transaction { MemberTable.update({ MemberTable.id eq m }) { it[votingRightChangedAt] = NOW } }
            }
            shouldThrow<ExposedSQLException> {
                transaction {
                    exec("UPDATE member SET voting_right_source = 'WHATEVER' WHERE id = '$m'")
                }
            }
        }

        test("DSGVO: the export carries the three values, the erasure resets them to the defaults") {
            val m = fx.member()
            revoke(m)
            transaction {
                val export = FoundationPersonalData.exportMember(m)
                export["votingRight"].toString() shouldBe "false"
                export["votingRightSource"].toString() shouldBe "\"MANUAL\""
                FoundationPersonalData.eraseMember(memberId = m, mode = ErasureMode.ANONYMIZE)
            }
            row(m)[MemberTable.votingRight] shouldBe true
            row(m)[MemberTable.votingRightSource] shouldBe VotingRightSource.DEFAULT
            row(m)[MemberTable.votingRightChangedAt] shouldBe null
        }
    })
