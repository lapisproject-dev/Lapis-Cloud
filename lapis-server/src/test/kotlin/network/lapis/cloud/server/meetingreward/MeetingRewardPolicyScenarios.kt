package network.lapis.cloud.server.meetingreward

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.MeetingRewardCommitteeTable
import network.lapis.cloud.server.tender.TenderFixtures
import network.lapis.cloud.server.tender.tenderTestApplication
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.MeetingRewardBlockedReason
import network.lapis.cloud.shared.domain.MeetingRewardPolicyAuditSnapshot
import network.lapis.cloud.shared.domain.MeetingRewardPolicyChangeKind
import network.lapis.cloud.shared.domain.MeetingRewardPolicyChangeStatus
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * V1.9.316 -- how the rules of the LTR recognition change: a resolution of the general assembly plus a second, tenured administrator for every CHANGE;
 * one administrator may switch off. Runs on H2 and on a real PostgreSQL.
 */
abstract class MeetingRewardPolicyScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = TenderFixtures()

        beforeSpec {
            db.activate()
            TimeTestSupport.setOrganizationZone("Europe/Berlin")
        }
        installLaneGuards(db = db, checkDeadlocks = true)
        afterTest { fx.resetRewards() }
        afterSpec {
            TimeTestSupport.resetOrganizationZone()
            db.deactivate()
        }

        class Setup(
            val adminA: Uuid,
            val adminB: Uuid,
            val committee: Uuid,
            val resolution: Uuid,
        )

        var auditBaseline = 0L

        fun setup(): Setup {
            auditBaseline = transaction { AuditLogEntryTable.selectAll().maxOfOrNull { it[AuditLogEntryTable.sequenceNumber] } ?: 0L }
            val a = fx.member(label = "adminA", role = AccountRole.ADMIN)
            val b = fx.member(label = "adminB", role = AccountRole.ADMIN)
            val ga = fx.generalAssembly()
            val committee = fx.committee()
            return Setup(a, b, committee, fx.resolutionIn(committee = ga, recordedBy = a))
        }

        fun policyAudit(): List<MeetingRewardPolicyAuditSnapshot> =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where {
                        (AuditLogEntryTable.entityType eq AuditEntityType.MEETING_REWARD_POLICY) and
                            (AuditLogEntryTable.sequenceNumber greater auditBaseline)
                    }.orderBy(AuditLogEntryTable.sequenceNumber, SortOrder.ASC)
                    .map { Json.decodeFromString(MeetingRewardPolicyAuditSnapshot.serializer(), it[AuditLogEntryTable.afterSnapshot]!!) }
            }

        test("a proposal needs an ADOPTED resolution of the general assembly") {
            tenderTestApplication {
                val s = setup()
                val other = fx.resolutionIn(committee = s.committee, recordedBy = s.adminA) // adopted, but not of the general assembly
                val rejected = fx.resolutionIn(committee = fx.generalAssembly(), recordedBy = s.adminA, status = ResolutionStatus.REJECTED)
                listOf(other, rejected, Uuid.random()).forEach { bad ->
                    shouldThrow<ConflictException> {
                        reward(member = s.adminA) {
                            proposeMeetingRewardPolicyChange(
                                proposal(resolution = bad, values = valuesDto(committees = listOf(s.committee))),
                            )
                        }
                    }.message shouldBe RESOLUTION_INVALID
                }
                shouldThrow<ConflictException> {
                    reward(member = s.adminA) {
                        proposeMeetingRewardPolicyChange(
                            network.lapis.cloud.shared.domain
                                .MeetingRewardPolicyProposalInput(values = valuesDto(), resolutionId = "not-a-uuid"),
                        )
                    }
                }
                reward(member = s.adminA) { getMeetingRewardPolicy() }.openProposal shouldBe null
            }
        }

        test("amounts with a third decimal place, negative amounts and unknown committees are refused") {
            tenderTestApplication {
                val s = setup()
                shouldThrow<ConflictException> {
                    reward(
                        member = s.adminA,
                    ) { proposeMeetingRewardPolicyChange(proposal(resolution = s.resolution, values = valuesDto(base = "1.005"))) }
                }.message shouldBe AMOUNT_SCALE_INVALID
                shouldThrow<ConflictException> {
                    reward(
                        member = s.adminA,
                    ) { proposeMeetingRewardPolicyChange(proposal(resolution = s.resolution, values = valuesDto(chair = "-1.00"))) }
                }.message shouldBe AMOUNT_OUT_OF_RANGE
                shouldThrow<ConflictException> {
                    reward(member = s.adminA) {
                        proposeMeetingRewardPolicyChange(
                            proposal(resolution = s.resolution, values = valuesDto(committees = listOf(Uuid.random()))),
                        )
                    }
                }.message shouldBe COMMITTEE_INVALID
                shouldThrow<ConflictException> {
                    reward(member = s.adminA) {
                        proposeMeetingRewardPolicyChange(
                            proposal(resolution = s.resolution, values = valuesDto(), reference = "x".repeat(MAX_REFERENCE_LENGTH + 1)),
                        )
                    }
                }.message shouldBe REFERENCE_TOO_LONG
            }
        }

        test("a proposal is stored with the old and the new figures and audited; a second proposal is refused while one is open") {
            tenderTestApplication {
                val s = setup()
                val view =
                    reward(member = s.adminA) {
                        proposeMeetingRewardPolicyChange(
                            proposal(resolution = s.resolution, values = valuesDto(committees = listOf(s.committee)), reference = "TOP 7"),
                        )
                    }
                val open = view.openProposal!!
                open.status shouldBe MeetingRewardPolicyChangeStatus.PROPOSED
                open.kind shouldBe MeetingRewardPolicyChangeKind.CHANGE
                open.resolution?.resolutionId shouldBe s.resolution.toString()
                open.resolutionReference shouldBe "TOP 7"
                open.old.enabled shouldBe false
                open.new.enabled shouldBe true
                view.current.enabled shouldBe false // nothing is in force yet
                val audit = policyAudit().single()
                audit.event shouldBe "PROPOSED"
                audit.resolutionId shouldBe s.resolution.toString()
                audit.new.attendanceAmount shouldBe "10.00"
                audit.newCommitteeIds shouldContainExactly listOf(s.committee.toString())
                shouldThrow<ConflictException> {
                    reward(
                        member = s.adminB,
                    ) { proposeMeetingRewardPolicyChange(proposal(resolution = s.resolution, values = valuesDto())) }
                }.message shouldBe POLICY_PROPOSAL_OPEN
            }
        }

        test("the proposer cannot confirm their own proposal (four eyes), the view says why") {
            tenderTestApplication {
                val s = setup()
                val open =
                    reward(
                        member = s.adminA,
                    ) {
                        proposeMeetingRewardPolicyChange(
                            proposal(resolution = s.resolution, values = valuesDto(committees = listOf(s.committee))),
                        )
                    }.openProposal!!
                shouldThrow<ConflictException> { reward(member = s.adminA) { confirmMeetingRewardPolicyChange(open.id) } }.message shouldBe
                    FOUR_EYES_REQUIRED
                val view = reward(member = s.adminA) { getMeetingRewardPolicy() }
                view.canConfirmOpen shouldBe false
                view.confirmBlockedReason shouldBe MeetingRewardBlockedReason.FOUR_EYES
                reward(member = s.adminB) { getMeetingRewardPolicy() }.canConfirmOpen shouldBe true
            }
        }

        test("an administrator who became one only just now cannot confirm (no puppet administrator)") {
            tenderTestApplication {
                val s = setup()
                val puppet = fx.member(label = "puppet", role = AccountRole.ADMIN)
                makeFreshAdmin(puppet)
                val open =
                    reward(
                        member = s.adminA,
                    ) {
                        proposeMeetingRewardPolicyChange(
                            proposal(resolution = s.resolution, values = valuesDto(committees = listOf(s.committee))),
                        )
                    }.openProposal!!
                shouldThrow<ConflictException> { reward(member = puppet) { confirmMeetingRewardPolicyChange(open.id) } }.message shouldBe
                    APPROVER_NOT_TENURED
                reward(member = puppet) { getMeetingRewardPolicy() }.confirmBlockedReason shouldBe
                    MeetingRewardBlockedReason.APPROVER_NOT_TENURED
                reward(member = s.adminA) { getMeetingRewardPolicy() }.current.enabled shouldBe false
            }
        }

        test("the second administrator confirms: the figures and the committees are in force, audited with old and new values") {
            tenderTestApplication {
                val s = setup()
                val open =
                    reward(member = s.adminA) {
                        proposeMeetingRewardPolicyChange(
                            proposal(resolution = s.resolution, values = valuesDto(committees = listOf(s.committee)), reference = "TOP 3"),
                        )
                    }.openProposal!!
                val view = reward(member = s.adminB) { confirmMeetingRewardPolicyChange(open.id) }
                view.current.enabled shouldBe true
                view.current.attendanceAmount.compareTo(BigDecimal("10.00")) shouldBe 0
                view.current.bonusChair.compareTo(BigDecimal("5.00")) shouldBe 0
                view.current.committeeIds shouldContainExactly listOf(s.committee.toString())
                view.openProposal shouldBe null
                view.resolutionReference shouldBe "TOP 3"
                view.history.single().status shouldBe MeetingRewardPolicyChangeStatus.CONFIRMED
                transaction { MeetingRewardCommitteeTable.selectAll().count() } shouldBe 1
                val confirmed = policyAudit().last()
                confirmed.event shouldBe "CONFIRMED"
                confirmed.old.enabled shouldBe false
                confirmed.new.enabled shouldBe true
                confirmed.resolutionId shouldBe s.resolution.toString()
                confirmed.resolutionReference shouldBe "TOP 3"
                // a confirmed proposal cannot be confirmed or withdrawn again
                shouldThrow<ConflictException> { reward(member = s.adminB) { confirmMeetingRewardPolicyChange(open.id) } }
                shouldThrow<ConflictException> { reward(member = s.adminA) { withdrawMeetingRewardPolicyChange(open.id) } }
            }
        }

        test(
            "switching on is impossible for a single administrator, switching off is possible and audited, a second switch-off writes nothing",
        ) {
            tenderTestApplication {
                val s = setup()
                reward(member = s.adminA) { disableMeetingReward() }.current.enabled shouldBe false // already off: nothing written
                policyAudit().size shouldBe 0
                seedPolicy(proposer = s.adminA, approver = s.adminB, resolution = s.resolution, committees = setOf(s.committee))
                val off = reward(member = s.adminA) { disableMeetingReward() }
                off.current.enabled shouldBe false
                off.history.first().kind shouldBe MeetingRewardPolicyChangeKind.DISABLE
                val audit = policyAudit().single()
                audit.event shouldBe "DISABLED"
                audit.kind shouldBe MeetingRewardPolicyChangeKind.DISABLE
                audit.old.enabled shouldBe true
                audit.new.enabled shouldBe false
                reward(member = s.adminB) { disableMeetingReward() }
                policyAudit().size shouldBe 1
            }
        }

        test("a proposal made before a switch-off is stale and cannot be confirmed afterwards") {
            tenderTestApplication {
                val s = setup()
                seedPolicy(proposer = s.adminA, approver = s.adminB, resolution = s.resolution, committees = setOf(s.committee))
                val open =
                    reward(
                        member = s.adminA,
                    ) {
                        proposeMeetingRewardPolicyChange(
                            proposal(resolution = s.resolution, values = valuesDto(committees = listOf(s.committee), base = "12.00")),
                        )
                    }.openProposal!!
                reward(member = s.adminB) { disableMeetingReward() }
                shouldThrow<ConflictException> { reward(member = s.adminB) { confirmMeetingRewardPolicyChange(open.id) } }.message shouldBe
                    POLICY_PROPOSAL_STALE
                reward(member = s.adminB) { getMeetingRewardPolicy() }.current.enabled shouldBe false
            }
        }

        test("a proposal can be withdrawn by any administrator, then a new one can be made") {
            tenderTestApplication {
                val s = setup()
                val open =
                    reward(
                        member = s.adminA,
                    ) {
                        proposeMeetingRewardPolicyChange(
                            proposal(resolution = s.resolution, values = valuesDto(committees = listOf(s.committee))),
                        )
                    }.openProposal!!
                val view = reward(member = s.adminB) { withdrawMeetingRewardPolicyChange(open.id) }
                view.openProposal shouldBe null
                view.history.single().status shouldBe MeetingRewardPolicyChangeStatus.WITHDRAWN
                policyAudit().last().event shouldBe "WITHDRAWN"
                reward(
                    member = s.adminB,
                ) {
                    proposeMeetingRewardPolicyChange(
                        proposal(resolution = s.resolution, values = valuesDto(committees = listOf(s.committee))),
                    )
                }.openProposal shouldNotBe
                    null
            }
        }

        test("every call is administrator-only (a member, a board member and a treasurer are refused); the own lines need no role") {
            tenderTestApplication {
                val s = setup()
                val member = fx.member(label = "member")
                val board = fx.member(label = "board", role = AccountRole.BOARD)
                val treasurer = fx.member(label = "treasurer", role = AccountRole.TREASURER)
                val change = Uuid.random().toString()
                val meeting = Uuid.random().toString()
                listOf(member, board, treasurer).forEach { who ->
                    shouldThrow<ForbiddenException> { reward(member = who) { getMeetingRewardPolicy() } }
                    shouldThrow<ForbiddenException> { reward(member = who) { listMeetingRewardResolutionOptions() } }
                    shouldThrow<ForbiddenException> {
                        reward(
                            member = who,
                        ) { proposeMeetingRewardPolicyChange(proposal(resolution = s.resolution, values = valuesDto())) }
                    }
                    shouldThrow<ForbiddenException> { reward(member = who) { confirmMeetingRewardPolicyChange(change) } }
                    shouldThrow<ForbiddenException> { reward(member = who) { withdrawMeetingRewardPolicyChange(change) } }
                    shouldThrow<ForbiddenException> { reward(member = who) { disableMeetingReward() } }
                    shouldThrow<ForbiddenException> { reward(member = who) { previewMeetingRewardSettlement(meeting) } }
                    shouldThrow<ForbiddenException> {
                        reward(
                            member = who,
                        ) { prepareMeetingRewardSettlement(meetingId = meeting, previewHash = "x") }
                    }
                    shouldThrow<ForbiddenException> {
                        reward(
                            member = who,
                        ) { releaseMeetingRewardSettlement(meetingId = meeting, previewHash = "x") }
                    }
                    shouldThrow<ForbiddenException> { reward(member = who) { discardMeetingRewardSettlement(meeting) } }
                    reward(member = who) { listMyMeetingRewardLines() } shouldBe emptyList()
                }
            }
        }

        test("the resolution options list only adopted resolutions of the general assembly") {
            tenderTestApplication {
                val s = setup()
                fx.resolutionIn(committee = s.committee, recordedBy = s.adminA)
                fx.resolutionIn(committee = fx.generalAssembly(), recordedBy = s.adminA, status = ResolutionStatus.POSTPONED)
                val options = reward(member = s.adminA) { listMeetingRewardResolutionOptions() }.map { it.resolutionId }
                (s.resolution.toString() in options) shouldBe true
                options.size shouldBe options.toSet().size
                transaction {
                    options.all { id ->
                        val r =
                            network.lapis.cloud.server.db.generated.ResolutionTable
                                .selectAll()
                                .where { network.lapis.cloud.server.db.generated.ResolutionTable.id eq Uuid.parse(id) }
                                .single()
                        r[network.lapis.cloud.server.db.generated.ResolutionTable.status] == ResolutionStatus.ADOPTED
                    }
                } shouldBe true
            }
        }
    })

class MeetingRewardPolicyTest : MeetingRewardPolicyScenarios(TestDatabase.H2)

@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class MeetingRewardPolicyPostgresTest : MeetingRewardPolicyScenarios(TestDatabase.Postgres())
