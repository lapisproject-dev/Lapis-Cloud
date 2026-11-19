package network.lapis.cloud.server.meetingreward

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.dsgvo.MeetingRewardPersonalData
import network.lapis.cloud.server.dsgvo.PersonalDataRegistry
import network.lapis.cloud.server.tender.TenderFixtures
import network.lapis.cloud.server.tender.tenderTestApplication
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AttendanceStatus
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.ErasureMode
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** V1.9.316 -- the data-subject export lists the member's own lines; erasure retains everything as booking evidence (GoBD) and says so. */
class MeetingRewardPersonalDataTest :
    FunSpec({
        val fx = TenderFixtures()

        beforeSpec { fx.connect() }
        afterTest { fx.resetRewards() }
        afterSpec { TimeTestSupport.resetOrganizationZone() }

        test("the contributor is registered and covers all five tables") {
            (MeetingRewardPersonalData in PersonalDataRegistry.contributors) shouldBe true
            MeetingRewardPersonalData.coveredTables.map { it.tableName }.toSet() shouldBe
                setOf(
                    "meeting_reward_policy",
                    "meeting_reward_policy_change",
                    "meeting_reward_committee",
                    "meeting_reward_settlement",
                    "meeting_reward_line",
                )
        }

        test("export lists the own lines and the administrator steps; erasure retains the evidence") {
            tenderTestApplication {
                val a = fx.member(label = "adminA", role = AccountRole.ADMIN)
                val b = fx.member(label = "adminB", role = AccountRole.ADMIN)
                val committee = fx.committee()
                val resolution = fx.resolutionIn(committee = fx.generalAssembly(), recordedBy = a)
                val meeting = fx.heldMeeting(committee = committee)
                val chair = fx.member(label = "chair")
                seatWithRole(committee = committee, member = chair, role = CommitteeRole.CHAIR)
                setAttendance(meeting = meeting, member = chair, status = AttendanceStatus.PRESENT)
                seedPolicy(proposer = a, approver = b, resolution = resolution, committees = setOf(committee))
                confirmProtocol(meeting = meeting, by = a)
                val preview = reward(member = a) { previewMeetingRewardSettlement(meeting.toString()) }
                reward(member = a) { prepareMeetingRewardSettlement(meetingId = meeting.toString(), previewHash = preview.previewHash!!) }
                reward(member = b) { releaseMeetingRewardSettlement(meetingId = meeting.toString(), previewHash = preview.previewHash!!) }

                val export = transaction { MeetingRewardPersonalData.exportMember(chair) }
                val line = export["lines"]!!.jsonArray.single().jsonObject
                line["amount"]!!.jsonPrimitive.content shouldBe "15.00"
                line["paid"]!!.jsonPrimitive.content shouldBe "true"
                line["functionRole"]!!.jsonPrimitive.content shouldBe "CHAIR"
                export["settlements"]!!.jsonArray.size shouldBe 0

                val adminExport = transaction { MeetingRewardPersonalData.exportMember(b) }
                adminExport["settlements"]!!
                    .jsonArray
                    .single()
                    .jsonObject["releasedByMe"]!!
                    .jsonPrimitive.content shouldBe "true"
                adminExport["policyChanges"]!!
                    .jsonArray
                    .single()
                    .jsonObject["decidedByMe"]!!
                    .jsonPrimitive.content shouldBe "true"

                val outcomes = transaction { MeetingRewardPersonalData.eraseMember(memberId = chair, mode = ErasureMode.ANONYMIZE) }
                outcomes.single { it.table == "meeting_reward_line" }.rowsRetained shouldBe 1
                outcomes.all { it.rowsDeleted == 0 && it.rowsAnonymized == 0 } shouldBe true
                // nothing was deleted
                transaction { MeetingRewardPersonalData.exportMember(chair) }["lines"]!!.jsonArray.size shouldBe 1
            }
        }
    })
