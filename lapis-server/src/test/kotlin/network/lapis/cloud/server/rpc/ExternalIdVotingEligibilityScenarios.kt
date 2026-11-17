package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.VoteEligibleVoterTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.server.member.MemberVotingRights
import network.lapis.cloud.server.member.VotingRightPolicy
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.VotingRightSource
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

private val STRICT = VotingRightPolicy(requireExternalMemberId = true)
private val LEGACY = VotingRightPolicy.LEGACY

/**
 * V1.9.307 -- the instance switch `LAPIS_REQUIRE_EXTERNAL_ID_FOR_VOTING` on H2 and PostgreSQL: with it OFF nothing changes; with it ON a member
 * without an external member id is not in the electorate of any kind of committee, not in the live quorum base N, refused at the ballot floor and
 * flagged on the session. A frozen electorate is never recomputed; switching the option off restores the old behaviour at once because
 * `member.voting_right` itself is never rewritten.
 */
abstract class ExternalIdVotingEligibilityScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = CommitteeSeatFixture()
        val rc = RuleCommitteeFixture(fx)

        beforeSpec { db.activate() }
        beforeTest { db.assertActive() }
        afterSpec {
            rc.cleanUp()
            fx.cleanUp()
            db.deactivate()
        }

        fun row(id: Uuid) = transaction { MemberTable.selectAll().where { MemberTable.id eq id }.single() }

        fun giveId(
            member: Uuid,
            id: String = "X-${Uuid.random()}",
        ) {
            transaction {
                MemberTable.update({ MemberTable.id eq member }) {
                    it[externalMemberId] = id
                    it[externalMemberIdSource] = "Wabeo"
                }
            }
        }

        test("manual committee: OFF = old behaviour, ON = only members with an id vote; the membership set itself is untouched") {
            val committee = fx.committee()
            val withId = fx.member()
            val without = fx.member()
            fx.seat(committee = committee, member = withId)
            fx.seat(committee = committee, member = without)
            giveId(withId)
            transaction {
                val committeeRow = fx.committeeRow(committee)
                val date = rc.today
                electorateMemberIds(policy = LEGACY, committeeRow = committeeRow, scheduledDate = date) shouldBe setOf(withId, without)
                electorateMemberIds(policy = STRICT, committeeRow = committeeRow, scheduledDate = date) shouldBe setOf(withId)
                eligibleMemberIds(committeeRow = committeeRow, scheduledDate = date) shouldBe setOf(withId, without)
                listOf(withId, without).forEach { m ->
                    isCommitteeVoter(policy = STRICT, committeeRow = committeeRow, scheduledDate = date, memberId = m) shouldBe
                        (m in electorateMemberIds(policy = STRICT, committeeRow = committeeRow, scheduledDate = date))
                }
            }
        }

        test("rule committee and general assembly: the same rule; countElectorate follows it") {
            val base = fx.committee()
            val a = fx.member()
            val b = fx.member()
            fx.seat(committee = base, member = a)
            fx.seat(committee = base, member = b)
            giveId(a)
            val ruleBased = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            val ga = fx.committee(type = CommitteeType.GENERAL_ASSEMBLY)
            transaction {
                val ruleRow = fx.committeeRow(ruleBased)
                electorateMemberIds(policy = STRICT, committeeRow = ruleRow, scheduledDate = rc.today) shouldBe setOf(a)
                countElectorate(policy = STRICT, committeeRow = ruleRow) shouldBe 1
                countElectorate(policy = LEGACY, committeeRow = ruleRow) shouldBe 2
                val gaRow = fx.committeeRow(ga)
                electorateMemberIds(policy = STRICT, committeeRow = gaRow, scheduledDate = rc.today) shouldNotContain b
                electorateMemberIds(policy = LEGACY, committeeRow = gaRow, scheduledDate = rc.today) shouldContain b
                countElectorate(policy = STRICT, committeeRow = gaRow) shouldBe
                    MemberTable
                        .selectAll()
                        .where { MemberTable.status eq MemberStatus.ACTIVE }
                        .count { it[MemberTable.votingRight] && it[MemberTable.externalMemberId] != null }
            }
        }

        test("live quorum base N (computeQuorum) follows the policy; a frozen electorate does not") {
            val committee = fx.committee()
            val withId = fx.member()
            val without = fx.member()
            fx.seat(committee = committee, member = withId)
            fx.seat(committee = committee, member = without)
            giveId(withId)
            val ruleBased = rc.ruleCommittee(ruleJson = rc.seatedIn(committee))
            val meeting = rc.meeting(committee = ruleBased)
            transaction {
                computeQuorum(
                    policy = LEGACY,
                    meetingId = meeting,
                    committeeId = ruleBased,
                    scheduledDate = rc.today,
                ).eligibleMemberCount shouldBe
                    2
                computeQuorum(
                    policy = STRICT,
                    meetingId = meeting,
                    committeeId = ruleBased,
                    scheduledDate = rc.today,
                ).eligibleMemberCount shouldBe
                    1
            }
            // a vote frozen with BOTH members: its snapshot wins whatever the policy says
            val motion = rc.motion(committee = ruleBased, meeting = meeting, submitter = withId)
            val vote = rc.voteRow(meeting = meeting, motion = motion, opener = withId)
            transaction {
                VoteTable.update({ VoteTable.id eq vote }) { it[electorateFrozenAt] = LocalDateTime(2026, 11, 17, 10, 0) }
                listOf(withId, without).forEach { m ->
                    VoteEligibleVoterTable.insert {
                        it[id] = Uuid.random()
                        it[voteId] = vote
                        it[memberId] = m
                    }
                }
                val frozen =
                    frozenOrLiveElectorate(
                        policy = STRICT,
                        meetingId = meeting,
                        committeeRow = fx.committeeRow(ruleBased),
                        scheduledDate = rc.today,
                        voteId = vote,
                    )
                frozen.frozen shouldBe true
                frozen.memberIds shouldBe setOf(withId, without)
                computeQuorum(policy = STRICT, meetingId = meeting, committeeId = ruleBased, scheduledDate = rc.today, voteId = vote)
                    .eligibleMemberCount shouldBe 2
            }
        }

        test("the ballot floor: ON refuses a member without an id, even one in a frozen snapshot; OFF and an id let the member through") {
            val withId = fx.member()
            val without = fx.member()
            giveId(withId)
            transaction {
                requireVotingMembership(policy = STRICT, memberId = withId) shouldBe MemberStatus.ACTIVE
                requireVotingMembership(policy = LEGACY, memberId = without) shouldBe MemberStatus.ACTIVE
                MemberVotingRights.isVoterNow(memberId = without, policy = LEGACY) shouldBe true
                MemberVotingRights.isVoterNow(memberId = without, policy = STRICT) shouldBe false
                MemberVotingRights.isVoterNow(memberId = withId, policy = STRICT) shouldBe true
            }
            shouldThrow<ForbiddenException> { transaction { requireVotingMembership(policy = STRICT, memberId = without) } }
        }

        test(
            "the plain voting right stays authoritative: no id and no right is out under both policies; the flag is never rewritten by the switch",
        ) {
            val m = fx.member()
            giveId(m)
            transaction {
                MemberTable.update({ MemberTable.id eq m }) {
                    it[votingRight] = false
                    it[votingRightSource] = VotingRightSource.MANUAL
                    it[votingRightChangedAt] = LocalDateTime(2026, 11, 17, 9, 0)
                }
            }
            transaction {
                LEGACY.holds(row(m)) shouldBe false
                STRICT.holds(row(m)) shouldBe false
                STRICT.suspendedForMissingExternalId(row(m)) shouldBe false
            }
            val other = fx.member()
            transaction {
                row(other)[MemberTable.votingRight] shouldBe true
                STRICT.holds(row(other)) shouldBe false
                STRICT.suspendedForMissingExternalId(row(other)) shouldBe true
                LEGACY.suspendedForMissingExternalId(row(other)) shouldBe false
                // switching the option off restores the old behaviour at once: the stored right was never touched
                LEGACY.holds(row(other)) shouldBe true
            }
        }
    })

/** V1.9.307 -- the switch scenarios on H2. */
class ExternalIdVotingEligibilityH2Test : ExternalIdVotingEligibilityScenarios(TestDatabase.H2)

/** V1.9.307 -- the switch scenarios against a real PostgreSQL. */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class ExternalIdVotingEligibilityPostgresTest : ExternalIdVotingEligibilityScenarios(TestDatabase.Postgres())
