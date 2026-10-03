package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ElectionBallotSelectionTable
import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ElectionOptionTable
import network.lapis.cloud.server.db.generated.ElectionParticipationTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.46 -- [ElectionPersonalData] never exports the content of a ballot: a member who voted in a secret and in an open election gets
 * exactly the open ballot (id, election, time) and no selection, option label or receipt code of either.
 */
class ElectionPersonalDataTest :
    FunSpec({
        val memberIds = mutableListOf<Uuid>()
        val committeeId = Uuid.random()
        val meetingId = Uuid.random()
        val motionIds = mutableListOf<Uuid>()
        val electionIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                val ballotIds =
                    ElectionBallotTable
                        .selectAll()
                        .where { ElectionBallotTable.electionId inList electionIds }
                        .map { it[ElectionBallotTable.id] }
                ElectionBallotSelectionTable.deleteWhere { ElectionBallotSelectionTable.ballotId inList ballotIds }
                ElectionBallotTable.deleteWhere { ElectionBallotTable.electionId inList electionIds }
                ElectionParticipationTable.deleteWhere { ElectionParticipationTable.electionId inList electionIds }
                ElectionOptionTable.deleteWhere { ElectionOptionTable.electionId inList electionIds }
                ElectionTable.deleteWhere { ElectionTable.id inList electionIds }
                MotionTable.deleteWhere { MotionTable.id inList motionIds }
                MeetingTable.deleteWhere { MeetingTable.id eq meetingId }
                CommitteeTable.deleteWhere { CommitteeTable.id eq committeeId }
                AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
                MemberTable.deleteWhere { MemberTable.id inList memberIds }
            }
        }

        test("a member's export holds only the open ballot's id, election and time -- no selection, label or receipt of either election") {
            val member = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[id] = member
                    it[displayName] = "Wahl-Export"
                    it[email] = "election-export-$member@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[id] = Uuid.random()
                    it[memberId] = member
                    it[role] = AccountRole.MEMBER
                }
                CommitteeTable.insert {
                    it[id] = committeeId
                    it[name] = "Wahl-Export-Gremium"
                    it[type] = CommitteeType.EXECUTIVE_BOARD
                    it[description] = "Export"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
                MeetingTable.insert {
                    it[id] = meetingId
                    it[MeetingTable.committeeId] = committeeId
                    it[title] = "Wahl-Export-Sitzung"
                    it[scheduledAt] = LocalDateTime(2026, 3, 1, 18, 0)
                    it[location] = "Vereinsheim"
                    it[format] = MeetingFormat.IN_PERSON
                    it[status] = MeetingStatus.PLANNED
                    it[calledBy] = null
                    it[calledAt] = null
                    it[chairMemberId] = null
                    it[minuteTakerMemberId] = null
                    it[protocolDocumentId] = null
                    it[createdAt] = LocalDateTime(2026, 3, 1, 18, 0)
                }
            }
            memberIds += member

            fun election(secret: Boolean): Pair<Uuid, String> {
                val motionId = Uuid.random()
                val electionId = Uuid.random()
                val optionId = Uuid.random()
                val ballotId = Uuid.random()
                val label = if (secret) "GEHEIM-LABEL-QQQ" else "OFFEN-LABEL-QQQ"
                val receipt = if (secret) "RCPT-GEHEIM-QQQ" else "RCPT-OFFEN-QQQ"
                val at = DbClock.nowLocalDateTime()
                transaction {
                    MotionTable.insert {
                        it[id] = motionId
                        it[targetCommitteeId] = committeeId
                        it[title] = "Export-Antrag"
                        it[rationale] = "R"
                        it[text] = "T"
                        it[submitterMemberId] = member
                        it[status] = MotionStatus.SCHEDULED
                        it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                        it[reviewedBy] = member
                        it[reviewedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                        it[reviewNote] = null
                        it[MotionTable.meetingId] = meetingId
                        it[agendaItemId] = null
                        it[resolutionId] = null
                        it[withdrawnAt] = null
                    }
                    ElectionTable.insert {
                        it[id] = electionId
                        it[title] = "Export-Wahl"
                        it[electionType] = ElectionType.YES_NO
                        it[ElectionTable.secret] = secret
                        it[seatCount] = 1
                        it[targetCommitteeId] = null
                        it[targetRole] = null
                        it[requiredMajorityPercent] = 50
                        it[status] = ElectionStatus.TALLIED
                        it[openedBy] = member
                        it[openedAt] = at
                        it[votingOpenedAt] = at
                        it[votingClosedAt] = at
                        it[tallyThreshold] = 2
                        it[tallyRunAt] = null
                        it[ElectionTable.motionId] = motionId
                        it[ElectionTable.meetingId] = meetingId
                        it[activeMotionId] = motionId
                    }
                    ElectionOptionTable.insert {
                        it[id] = optionId
                        it[ElectionOptionTable.label] = label
                        it[position] = 0
                        it[candidacyId] = null
                        it[ElectionOptionTable.electionId] = electionId
                    }
                    ElectionBallotTable.insert {
                        it[id] = ballotId
                        it[receiptCode] = receipt
                        it[castAt] = at
                        it[ElectionBallotTable.electionId] = electionId
                        it[memberId] = if (secret) null else member
                    }
                    ElectionBallotSelectionTable.insert {
                        it[id] = Uuid.random()
                        it[ElectionBallotSelectionTable.ballotId] = ballotId
                        it[ElectionBallotSelectionTable.optionId] = optionId
                    }
                    ElectionParticipationTable.insert {
                        it[id] = Uuid.random()
                        it[votedAt] = at
                        it[ElectionParticipationTable.electionId] = electionId
                        it[memberId] = member
                    }
                }
                motionIds += motionId
                electionIds += electionId
                return electionId to ballotId.toString()
            }

            val (secretElection, _) = election(secret = true)
            val (openElection, openBallot) = election(secret = false)

            val export = transaction { ElectionPersonalData.exportMember(member) }
            val ballots = export.jsonObject.getValue("ballotsNonSecret").jsonArray
            ballots.size shouldBe 1
            val only = ballots.single().jsonObject
            only.getValue("id").jsonPrimitive.content shouldBe openBallot
            only.getValue("electionId").jsonPrimitive.content shouldBe openElection.toString()
            only.keys shouldBe setOf("id", "electionId", "castAt")
            val text = export.toString()
            ballots.toString() shouldNotContain secretElection.toString()
            text shouldNotContain "LABEL-QQQ"
            text shouldNotContain "RCPT-"
            text.lowercase() shouldNotContain "selected"
            text.lowercase() shouldNotContain "receipt"
        }
    })
