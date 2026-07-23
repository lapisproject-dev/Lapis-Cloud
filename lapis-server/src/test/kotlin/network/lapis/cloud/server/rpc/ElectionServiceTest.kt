package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.BoardMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ElectionBallotSelectionTable
import network.lapis.cloud.server.db.generated.ElectionBallotTable
import network.lapis.cloud.server.db.generated.ElectionBoardMemberTable
import network.lapis.cloud.server.db.generated.ElectionCandidacyTable
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionOptionTable
import network.lapis.cloud.server.db.generated.ElectionParticipationTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.ElectionTallyApprovalTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.TransparenzregisterReminderTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.BoardMembershipSnapshot
import network.lapis.cloud.shared.domain.CandidacyInput
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionAnswer
import network.lapis.cloud.shared.domain.ElectionBallotInput
import network.lapis.cloud.shared.domain.ElectionOpenInput
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.ElectionType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionSnapshot
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.uuid.Uuid

/**
 * Exercises [ElectionService] end to end -- same "throwaway routes calling the service class
 * directly" house style as [GovernanceServiceTest]. Uses its own freshly created Gremien/Meetingen/
 * Antraege/members throughout (never [DevSeedData]'s shared demo fixtures as Committee members), for
 * the same order-independence reasoning [GovernanceServiceTest] documents. [afterSpec] hard-deletes
 * every row this file created.
 *
 * Committee/Meeting/Motion test fixtures are seeded via direct table inserts (bypassing
 * [GovernanceService]'s own authorization/validation) -- this file's own routes/assertions only
 * need a Motion already in [MotionStatus.SCHEDULED] with a scheduled Meeting, not a full
 * `submitMotion -> reviewMotion -> scheduleMotion` walk (that walk is [GovernanceServiceTest]'s
 * concern, exercised there).
 */
class ElectionServiceTest :
    FunSpec({
        val createdCommitteeIds = mutableListOf<Uuid>()
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpElectionTestData(createdCommitteeIds, createdMemberIds) }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.AKTIV,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Election Testmitglied"
                    it[MemberTable.email] = email
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun createTestCommittee(
            name: String,
            type: CommitteeType = CommitteeType.EXECUTIVE_BOARD,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                CommitteeTable.insert {
                    it[CommitteeTable.id] = id
                    it[CommitteeTable.name] = name
                    it[CommitteeTable.type] = type
                    it[description] = "Testcommittee"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            createdCommitteeIds += id
            return id
        }

        fun addMember(
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

        fun createTestMeeting(
            committeeId: Uuid,
            scheduledAt: LocalDateTime,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MeetingTable.insert {
                    it[MeetingTable.id] = id
                    it[MeetingTable.committeeId] = committeeId
                    it[title] = "Testmeeting"
                    it[MeetingTable.scheduledAt] = scheduledAt
                    it[location] = "Vereinsheim"
                    it[format] = MeetingFormat.IN_PERSON
                    it[status] = MeetingStatus.PLANNED
                    it[calledBy] = null
                    it[calledAt] = null
                    it[chairMemberId] = null
                    it[minuteTakerMemberId] = null
                    it[protocolDocumentId] = null
                    it[createdAt] = scheduledAt
                }
            }
            return id
        }

        /** Directly seeds an already-[MotionStatus.SCHEDULED] Motion -- see class KDoc. */
        fun createTerminierterMotion(
            committeeId: Uuid,
            meetingId: Uuid,
            submitterId: Uuid,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MotionTable.insert {
                    it[MotionTable.id] = id
                    it[targetCommitteeId] = committeeId
                    it[title] = "Testmotion"
                    it[rationale] = "Rationale"
                    it[text] = "Motionstext"
                    it[submitterMemberId] = submitterId
                    it[status] = MotionStatus.SCHEDULED
                    it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewedBy] = submitterId
                    it[reviewedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                    it[reviewNote] = null
                    it[MotionTable.meetingId] = meetingId
                    it[agendaItemId] = null
                    it[resolutionId] = null
                    it[withdrawnAt] = null
                }
            }
            return id
        }

        test(
            "YES_NO full lifecycle: open -> appoint election board -> open voting -> non-secret ballots -> " +
                "close -> Vier-Augen release -> tally writes a DEMOCRATIC Resolution and resolves the Motion",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installElectionExceptionHandlers() }
                    routing { registerElectionTestRoutes() }
                }

                val committeeId = createTestCommittee("JaNein Executive Board")
                val chair = createTestMember("election-janein-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val voter1 = createTestMember("election-janein-v1@example.org")
                val voter2 = createTestMember("election-janein-v2@example.org")
                addMember(committeeId, voter1, CommitteeRole.MEMBER)
                addMember(committeeId, voter2, CommitteeRole.MEMBER)
                val electionBoardMembers = (1..3).map { createTestMember("election-janein-wv$it@example.org") }

                val meetingId = createTestMeeting(committeeId, LocalDateTime(2026, 3, 1, 18, 0))
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                val opened =
                    client
                        .post(
                            "/test/open-election/$motionId/YES_NO?secret=false",
                        ) { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                val electionId = opened.substringBefore(":")
                opened.substringAfter(":") shouldBe ElectionStatus.PREPARATION.name

                val appointed =
                    client
                        .post(
                            "/test/appoint-election-board/$electionId?memberIds=${electionBoardMembers.joinToString(",")}",
                        ) { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                appointed shouldBe "3"

                val openVoting =
                    client.post("/test/open-voting/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }.bodyAsText()
                openVoting shouldBe ElectionStatus.OPEN.name

                client.post(
                    "/test/cast-election-ballot/$electionId?answer=YES",
                ) { header("X-Member-Id", chair.toString()) }
                client.post(
                    "/test/cast-election-ballot/$electionId?answer=YES",
                ) { header("X-Member-Id", voter1.toString()) }
                client.post(
                    "/test/cast-election-ballot/$electionId?answer=NO",
                ) { header("X-Member-Id", voter2.toString()) }

                val closeVoting =
                    client.post("/test/close-voting/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }.bodyAsText()
                closeVoting shouldBe ElectionStatus.CLOSED.name

                // Vier-Augen: default tallyThreshold is 2 -- one approval must not be enough.
                client.post("/test/release-tally/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }
                val tooEarly = client.post("/test/tally/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }
                tooEarly.status shouldBe HttpStatusCode.Conflict

                client.post("/test/release-tally/$electionId") { header("X-Member-Id", electionBoardMembers[1].toString()) }
                val ergebnis =
                    client.post("/test/tally/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }.bodyAsText()
                // 2 YES vs 1 NO -> majority met, YES option wins, no tie.
                val parts = ergebnis.split(":")
                parts[1] shouldBe "false" // tie
                parts[2] shouldBe "true" // majorityMet

                val finalElection = client.get("/test/get-election/$electionId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                finalElection.substringBefore(":") shouldBe ElectionStatus.TALLIED.name

                val motionStatus =
                    transaction { MotionTable.selectAll().where { MotionTable.id eq motionId }.single()[MotionTable.status] }
                motionStatus shouldBe MotionStatus.RESOLVED

                val resolution =
                    transaction {
                        ResolutionTable.selectAll().where { ResolutionTable.electionId eq Uuid.parse(electionId) }.single()
                    }
                transaction { resolution[ResolutionTable.resolutionMode] } shouldBe ResolutionMode.DEMOCRATIC
                transaction { resolution[ResolutionTable.status] } shouldBe ResolutionStatus.ADOPTED
            }
        }

        test(
            "SINGLE_CHOICE personnel election: Candidacy submit/withdraw, releaseCandidateList freezes options, " +
                "secret ballots hide memberId and hand back a receiptCode, winner is seated into targetCommittee",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installElectionExceptionHandlers() }
                    routing { registerElectionTestRoutes() }
                }

                val hostCommittee = createTestCommittee("General Assembly Einzelelection", CommitteeType.GENERAL_ASSEMBLY)
                val targetCommittee = createTestCommittee("Executive Board Ziel")
                val chair = createTestMember("election-einzel-chair@example.org")
                addMember(hostCommittee, chair, CommitteeRole.CHAIR)

                val candidateA = createTestMember("election-einzel-candA@example.org")
                val candidateB = createTestMember("election-einzel-candB@example.org")
                val withdrawingCandidate = createTestMember("election-einzel-candC@example.org")
                val voter = createTestMember("election-einzel-voter@example.org")
                val electionBoardMembers = (1..3).map { createTestMember("election-einzel-wv$it@example.org") }

                val meetingId = createTestMeeting(hostCommittee, LocalDateTime(2026, 4, 1, 18, 0))
                val motionId = createTerminierterMotion(hostCommittee, meetingId, chair)

                val opened =
                    client
                        .post(
                            "/test/open-election/$motionId/SINGLE_CHOICE?targetCommitteeId=$targetCommittee&seatCount=1",
                        ) { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                val electionId = opened.substringBefore(":")

                client.post("/test/appoint-election-board/$electionId?memberIds=${electionBoardMembers.joinToString(",")}") {
                    header("X-Member-Id", chair.toString())
                }

                val candidacyA =
                    client.post("/test/submit-candidacy/$electionId") { header("X-Member-Id", candidateA.toString()) }.bodyAsText()
                client.post("/test/submit-candidacy/$electionId") { header("X-Member-Id", candidateB.toString()) }
                val candidacyC =
                    client
                        .post(
                            "/test/submit-candidacy/$electionId",
                        ) { header("X-Member-Id", withdrawingCandidate.toString()) }
                        .bodyAsText()

                val withdrawn =
                    client
                        .post(
                            "/test/withdraw-candidacy/$candidacyC",
                        ) { header("X-Member-Id", withdrawingCandidate.toString()) }
                        .bodyAsText()
                withdrawn shouldBe "true"

                val freigegeben =
                    client
                        .post("/test/release-kandidatenliste/$electionId") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                // Only 2 non-withdrawn candidacies (A, B) become options.
                freigegeben shouldBe "${ElectionStatus.CANDIDATE_LIST_RELEASED}:2"

                client.post("/test/open-voting/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }

                val optionAId =
                    transaction {
                        ElectionOptionTable
                            .selectAll()
                            .where {
                                (ElectionOptionTable.electionId eq Uuid.parse(electionId)) and
                                    (ElectionOptionTable.candidacyId eq Uuid.parse(candidacyA))
                            }.single()[ElectionOptionTable.id]
                    }

                val castResult =
                    client
                        .post(
                            "/test/cast-election-ballot/$electionId?selectedOptionIds=$optionAId",
                        ) { header("X-Member-Id", voter.toString()) }
                        .bodyAsText()
                val receiptCode = castResult.substringAfter(":")
                receiptCode.isBlank() shouldBe false

                // Ballot secrecy: the stored ballot row has no member_id for a secret Election.
                val storedMemberId =
                    transaction {
                        ElectionBallotTable.selectAll().where { ElectionBallotTable.electionId eq Uuid.parse(electionId) }.single()[
                            ElectionBallotTable.memberId,
                        ]
                    }
                storedMemberId shouldBe null

                // Before TALLIED, verifyReceipt confirms existence but never the option.
                val beforeTally =
                    client
                        .get(
                            "/test/verify-receipt/$electionId?receiptCode=$receiptCode",
                        ) { header("X-Member-Id", voter.toString()) }
                        .bodyAsText()
                beforeTally shouldBe "true:"

                client.post("/test/close-voting/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }
                client.post("/test/release-tally/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }
                client.post("/test/release-tally/$electionId") { header("X-Member-Id", electionBoardMembers[1].toString()) }
                client.post("/test/tally/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }

                // After TALLIED, the receipt reveals the chosen option's label (the candidate's display name).
                val afterTally =
                    client
                        .get(
                            "/test/verify-receipt/$electionId?receiptCode=$receiptCode",
                        ) { header("X-Member-Id", voter.toString()) }
                        .bodyAsText()
                afterTally shouldBe "true:Election Testmitglied"

                // Winner (candidateA, only ballot cast) is seated into targetCommittee.
                val seated =
                    transaction {
                        CommitteeMembershipTable
                            .selectAll()
                            .where {
                                (CommitteeMembershipTable.committeeId eq targetCommittee) and
                                    (CommitteeMembershipTable.memberId eq candidateA)
                            }.count()
                    }
                seated shouldBe 1L
            }
        }

        test(
            "V0.5.3 GoBD audit log: tally writes both a RESOLUTION CREATE (DEMOCRATIC) and a " +
                "BOARD_MEMBERSHIP CREATE audit entry when seating an EXECUTIVE_BOARD winner -- fixes a review " +
                "finding that only the administrative BoardMembershipService path was ever audited",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installElectionExceptionHandlers() }
                    routing { registerElectionTestRoutes() }
                }

                val hostCommittee = createTestCommittee("General Assembly AuditTally", CommitteeType.GENERAL_ASSEMBLY)
                val targetCommittee = createTestCommittee("Executive Board AuditTally Ziel")
                val chair = createTestMember("election-audit-chair@example.org")
                addMember(hostCommittee, chair, CommitteeRole.CHAIR)
                val candidate = createTestMember("election-audit-cand@example.org")
                val voter = createTestMember("election-audit-voter@example.org")
                val electionBoardMembers = (1..3).map { createTestMember("election-audit-wv$it@example.org") }

                val meetingId = createTestMeeting(hostCommittee, LocalDateTime(2026, 5, 1, 18, 0))
                val motionId = createTerminierterMotion(hostCommittee, meetingId, chair)

                val opened =
                    client
                        .post(
                            "/test/open-election/$motionId/SINGLE_CHOICE?targetCommitteeId=$targetCommittee&seatCount=1&secret=false",
                        ) { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                val electionId = opened.substringBefore(":")
                client.post("/test/appoint-election-board/$electionId?memberIds=${electionBoardMembers.joinToString(",")}") {
                    header("X-Member-Id", chair.toString())
                }
                client.post("/test/submit-candidacy/$electionId") { header("X-Member-Id", candidate.toString()) }
                client.post("/test/release-kandidatenliste/$electionId") { header("X-Member-Id", chair.toString()) }
                client.post("/test/open-voting/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }

                val optionId =
                    transaction {
                        ElectionOptionTable.selectAll().where { ElectionOptionTable.electionId eq Uuid.parse(electionId) }.single()[
                            ElectionOptionTable.id,
                        ]
                    }
                client.post("/test/cast-election-ballot/$electionId?selectedOptionIds=$optionId") {
                    header("X-Member-Id", voter.toString())
                }
                client.post("/test/close-voting/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }
                client.post("/test/release-tally/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }
                client.post("/test/release-tally/$electionId") { header("X-Member-Id", electionBoardMembers[1].toString()) }
                client.post("/test/tally/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }

                val resolution =
                    transaction { ResolutionTable.selectAll().where { ResolutionTable.electionId eq Uuid.parse(electionId) }.single() }
                val resolutionId = transaction { resolution[ResolutionTable.id] }

                val resolutionRows =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.RESOLUTION) and
                                    (AuditLogEntryTable.entityId eq resolutionId)
                            }.toList()
                    }
                resolutionRows.size shouldBe 1
                transaction { resolutionRows.single()[AuditLogEntryTable.action] } shouldBe AuditAction.CREATE
                val resolutionSnapshot =
                    transaction {
                        Json.decodeFromString(
                            ResolutionSnapshot.serializer(),
                            resolutionRows.single()[AuditLogEntryTable.afterSnapshot]!!,
                        )
                    }
                resolutionSnapshot.resolutionMode shouldBe ResolutionMode.DEMOCRATIC

                val boardMembershipId =
                    transaction {
                        BoardMembershipTable.selectAll().where { BoardMembershipTable.memberId eq candidate }.single()[
                            BoardMembershipTable.id,
                        ]
                    }
                val boardMembershipRows =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.BOARD_MEMBERSHIP) and
                                    (AuditLogEntryTable.entityId eq boardMembershipId)
                            }.toList()
                    }
                boardMembershipRows.size shouldBe 1
                transaction { boardMembershipRows.single()[AuditLogEntryTable.action] } shouldBe AuditAction.CREATE
                val boardMembershipSnapshot =
                    transaction {
                        Json.decodeFromString(
                            BoardMembershipSnapshot.serializer(),
                            boardMembershipRows.single()[AuditLogEntryTable.afterSnapshot]!!,
                        )
                    }
                boardMembershipSnapshot.memberId shouldBe candidate.toString()
            }
        }

        test(
            "one-member-one-vote: a second castElectionBallot attempt for the same member is rejected on both the secret and non-secret path",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installElectionExceptionHandlers() }
                    routing { registerElectionTestRoutes() }
                }

                val committeeId = createTestCommittee("DoubleVote Executive Board")
                val chair = createTestMember("election-double-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val voter = createTestMember("election-double-voter@example.org")
                addMember(committeeId, voter, CommitteeRole.MEMBER)
                val electionBoardMembers = (1..3).map { createTestMember("election-double-wv$it@example.org") }

                listOf(true, false).forEach { secret ->
                    val meetingId = createTestMeeting(committeeId, LocalDateTime(2026, 5, 1, 18, 0))
                    val motionId = createTerminierterMotion(committeeId, meetingId, chair)
                    val electionId =
                        client
                            .post("/test/open-election/$motionId/YES_NO?secret=$secret") { header("X-Member-Id", chair.toString()) }
                            .bodyAsText()
                            .substringBefore(":")
                    client.post("/test/appoint-election-board/$electionId?memberIds=${electionBoardMembers.joinToString(",")}") {
                        header("X-Member-Id", chair.toString())
                    }
                    client.post("/test/open-voting/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }

                    val first = client.post("/test/cast-election-ballot/$electionId?answer=YES") { header("X-Member-Id", voter.toString()) }
                    first.status shouldBe HttpStatusCode.OK
                    val second = client.post("/test/cast-election-ballot/$electionId?answer=NO") { header("X-Member-Id", voter.toString()) }
                    second.status shouldBe HttpStatusCode.Conflict
                }
            }
        }

        test("concurrency: N simultaneous castElectionBallot calls for the same member on a secret Election -- exactly one succeeds") {
            testApplication {
                application {
                    install(StatusPages) { installElectionExceptionHandlers() }
                    routing { registerElectionTestRoutes() }
                }

                val committeeId = createTestCommittee("Concurrency Executive Board")
                val chair = createTestMember("election-concurrency-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val voter = createTestMember("election-concurrency-voter@example.org")
                addMember(committeeId, voter, CommitteeRole.MEMBER)
                val electionBoardMembers = (1..3).map { createTestMember("election-concurrency-wv$it@example.org") }

                val meetingId = createTestMeeting(committeeId, LocalDateTime(2026, 6, 1, 18, 0))
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)
                val electionId =
                    client
                        .post("/test/open-election/$motionId/YES_NO") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                client.post("/test/appoint-election-board/$electionId?memberIds=${electionBoardMembers.joinToString(",")}") {
                    header("X-Member-Id", chair.toString())
                }
                client.post("/test/open-voting/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }

                val attempts = 8
                val results =
                    coroutineScope {
                        (1..attempts)
                            .map {
                                async {
                                    client
                                        .post(
                                            "/test/cast-election-ballot/$electionId?answer=YES",
                                        ) { header("X-Member-Id", voter.toString()) }
                                        .status
                                }
                            }.map { it.await() }
                    }
                results.count { it == HttpStatusCode.OK } shouldBe 1
                results.count { it == HttpStatusCode.Conflict } shouldBe attempts - 1

                val participationCount =
                    transaction {
                        ElectionParticipationTable
                            .selectAll()
                            .where {
                                (ElectionParticipationTable.electionId eq Uuid.parse(electionId)) and
                                    (ElectionParticipationTable.memberId eq voter)
                            }.count()
                    }
                participationCount shouldBe 1L
            }
        }

        test(
            "appointElectionBoard rejects a member currently active in a EXECUTIVE_BOARD-typed targetCommittee (election board/Executive Board separation)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installElectionExceptionHandlers() }
                    routing { registerElectionTestRoutes() }
                }

                val hostCommittee = createTestCommittee("MV Separation", CommitteeType.GENERAL_ASSEMBLY)
                val targetExecutiveBoard = createTestCommittee("Executive Board Separation")
                val chair = createTestMember("election-sep-chair@example.org")
                addMember(hostCommittee, chair, CommitteeRole.CHAIR)
                val executiveBoardMember = createTestMember("election-sep-board@example.org")
                addMember(targetExecutiveBoard, executiveBoardMember, CommitteeRole.MEMBER)
                val others = (1..3).map { createTestMember("election-sep-other$it@example.org") }

                val meetingId = createTestMeeting(hostCommittee, LocalDateTime(2026, 7, 1, 18, 0))
                val motionId = createTerminierterMotion(hostCommittee, meetingId, chair)
                val electionId =
                    client
                        .post("/test/open-election/$motionId/SINGLE_CHOICE?targetCommitteeId=$targetExecutiveBoard&seatCount=1") {
                            header("X-Member-Id", chair.toString())
                        }.bodyAsText()
                        .substringBefore(":")

                val memberIds = (others + executiveBoardMember).joinToString(",")
                val rejected =
                    client.post(
                        "/test/appoint-election-board/$electionId?memberIds=$memberIds",
                    ) { header("X-Member-Id", chair.toString()) }
                rejected.status shouldBe HttpStatusCode.Conflict

                val allowed =
                    client
                        .post("/test/appoint-election-board/$electionId?memberIds=${others.joinToString(",")}") {
                            header("X-Member-Id", chair.toString())
                        }.bodyAsText()
                allowed shouldBe "3"
            }
        }

        test("authorization: non-Committee-leadership member cannot openElection; ineligible member cannot castElectionBallot") {
            testApplication {
                application {
                    install(StatusPages) { installElectionExceptionHandlers() }
                    routing { registerElectionTestRoutes() }
                }

                val committeeId = createTestCommittee("Auth Executive Board")
                val chair = createTestMember("election-auth-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val plainMember = createTestMember("election-auth-plain@example.org")
                addMember(committeeId, plainMember, CommitteeRole.MEMBER)
                val outsider = createTestMember("election-auth-outsider@example.org")
                val electionBoardMembers = (1..3).map { createTestMember("election-auth-wv$it@example.org") }

                val meetingId = createTestMeeting(committeeId, LocalDateTime(2026, 8, 1, 18, 0))
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                val forbiddenOpen =
                    client.post("/test/open-election/$motionId/YES_NO") { header("X-Member-Id", plainMember.toString()) }
                forbiddenOpen.status shouldBe HttpStatusCode.Forbidden

                val electionId =
                    client
                        .post("/test/open-election/$motionId/YES_NO") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                client.post("/test/appoint-election-board/$electionId?memberIds=${electionBoardMembers.joinToString(",")}") {
                    header("X-Member-Id", chair.toString())
                }
                client.post("/test/open-voting/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }

                // outsider is not in the eligibility snapshot (not a Committee member).
                val forbiddenVote =
                    client.post(
                        "/test/cast-election-ballot/$electionId?answer=YES",
                    ) { header("X-Member-Id", outsider.toString()) }
                forbiddenVote.status shouldBe HttpStatusCode.Forbidden

                val unauthenticated = client.post("/test/cast-election-ballot/$electionId?answer=YES")
                unauthenticated.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test(
            "secret ballot secrecy: election_ballot.cast_at is decoupled from election_participation.voted_at, " +
                "not a bit-identical join key back to the voter",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installElectionExceptionHandlers() }
                    routing { registerElectionTestRoutes() }
                }

                val committeeId = createTestCommittee("Secret Timestamp Executive Board")
                val chair = createTestMember("election-ts-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val voter = createTestMember("election-ts-voter@example.org")
                addMember(committeeId, voter, CommitteeRole.MEMBER)
                val electionBoardMembers = (1..3).map { createTestMember("election-ts-wv$it@example.org") }

                val meetingId = createTestMeeting(committeeId, LocalDateTime(2026, 9, 1, 18, 0))
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)
                val electionId =
                    client
                        .post("/test/open-election/$motionId/YES_NO?secret=true") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                client.post("/test/appoint-election-board/$electionId?memberIds=${electionBoardMembers.joinToString(",")}") {
                    header("X-Member-Id", chair.toString())
                }
                client.post("/test/open-voting/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }
                client.post("/test/cast-election-ballot/$electionId?answer=YES") { header("X-Member-Id", voter.toString()) }

                val votedAt =
                    transaction {
                        ElectionParticipationTable
                            .selectAll()
                            .where {
                                (ElectionParticipationTable.electionId eq Uuid.parse(electionId)) and
                                    (ElectionParticipationTable.memberId eq voter)
                            }.single()[ElectionParticipationTable.votedAt]
                    }
                val castAt =
                    transaction {
                        ElectionBallotTable
                            .selectAll()
                            .where { ElectionBallotTable.electionId eq Uuid.parse(electionId) }
                            .single()[ElectionBallotTable.castAt]
                    }
                // The bug: both columns were written from the same `now` value, so a trivial
                // `voted_at = cast_at` join re-linked every secret ballot to its voter. The fix:
                // cast_at is coarsened to the calendar date (time-of-day zeroed) for a secret
                // Election, so it is never bit-identical to voted_at's full-precision timestamp.
                castAt shouldNotBe votedAt
                castAt.hour shouldBe 0
                castAt.minute shouldBe 0
                castAt.second shouldBe 0
                castAt.date shouldBe votedAt.date
            }
        }

        test("listElectionBallots hides selectedOptionLabels for a secret Election until TALLIED (no mid-vote running-tally leak)") {
            testApplication {
                application {
                    install(StatusPages) { installElectionExceptionHandlers() }
                    routing { registerElectionTestRoutes() }
                }

                val committeeId = createTestCommittee("Secret Transparency Executive Board")
                val chair = createTestMember("election-transparency-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val voter1 = createTestMember("election-transparency-v1@example.org")
                val voter2 = createTestMember("election-transparency-v2@example.org")
                addMember(committeeId, voter1, CommitteeRole.MEMBER)
                addMember(committeeId, voter2, CommitteeRole.MEMBER)
                val electionBoardMembers = (1..3).map { createTestMember("election-transparency-wv$it@example.org") }

                val meetingId = createTestMeeting(committeeId, LocalDateTime(2026, 9, 15, 18, 0))
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)
                val electionId =
                    client
                        .post("/test/open-election/$motionId/YES_NO?secret=true") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                client.post("/test/appoint-election-board/$electionId?memberIds=${electionBoardMembers.joinToString(",")}") {
                    header("X-Member-Id", chair.toString())
                }
                client.post("/test/open-voting/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }
                client.post("/test/cast-election-ballot/$electionId?answer=YES") { header("X-Member-Id", voter1.toString()) }
                client.post("/test/cast-election-ballot/$electionId?answer=NO") { header("X-Member-Id", voter2.toString()) }
                client.post("/test/close-voting/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }

                // OPEN/CLOSED, pre-tally: any authenticated member must not be able to read
                // the plaintext choices and compute a running tally themselves.
                val beforeTally =
                    client
                        .get("/test/list-ballots/$electionId") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                beforeTally.split(";").forEach { entry -> entry.substringAfter(":") shouldBe "" }

                client.post("/test/release-tally/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }
                client.post("/test/release-tally/$electionId") { header("X-Member-Id", electionBoardMembers[1].toString()) }
                client.post("/test/tally/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }

                // TALLIED: labels are now revealed.
                val afterTally =
                    client
                        .get("/test/list-ballots/$electionId") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                afterTally.split(";").any { entry -> entry.substringAfter(":").isNotBlank() } shouldBe true
            }
        }

        test(
            "SINGLE_CHOICE requires an absolute majority, not mere plurality: a sub-majority plurality winner " +
                "resolves to POSTPONED (Stichelection signal) and nobody is seated",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installElectionExceptionHandlers() }
                    routing { registerElectionTestRoutes() }
                }

                val hostCommittee = createTestCommittee("MV Einzelelection Mehrheit", CommitteeType.GENERAL_ASSEMBLY)
                val targetCommittee = createTestCommittee("Executive Board Mehrheit Ziel")
                val chair = createTestMember("election-mehrheit-chair@example.org")
                addMember(hostCommittee, chair, CommitteeRole.CHAIR)

                val candidateA = createTestMember("election-mehrheit-candA@example.org")
                val candidateB = createTestMember("election-mehrheit-candB@example.org")
                val candidateC = createTestMember("election-mehrheit-candC@example.org")
                val voters = (1..7).map { createTestMember("election-mehrheit-voter$it@example.org") }
                val electionBoardMembers = (1..3).map { createTestMember("election-mehrheit-wv$it@example.org") }

                val meetingId = createTestMeeting(hostCommittee, LocalDateTime(2026, 10, 1, 18, 0))
                val motionId = createTerminierterMotion(hostCommittee, meetingId, chair)

                val electionId =
                    client
                        .post("/test/open-election/$motionId/SINGLE_CHOICE?targetCommitteeId=$targetCommittee&seatCount=1&secret=false") {
                            header("X-Member-Id", chair.toString())
                        }.bodyAsText()
                        .substringBefore(":")

                client.post("/test/appoint-election-board/$electionId?memberIds=${electionBoardMembers.joinToString(",")}") {
                    header("X-Member-Id", chair.toString())
                }

                val candidacyA =
                    client.post("/test/submit-candidacy/$electionId") { header("X-Member-Id", candidateA.toString()) }.bodyAsText()
                val candidacyB =
                    client.post("/test/submit-candidacy/$electionId") { header("X-Member-Id", candidateB.toString()) }.bodyAsText()
                val candidacyC =
                    client.post("/test/submit-candidacy/$electionId") { header("X-Member-Id", candidateC.toString()) }.bodyAsText()

                client.post("/test/release-kandidatenliste/$electionId") { header("X-Member-Id", chair.toString()) }
                client.post("/test/open-voting/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }

                fun optionIdFor(candidacyId: String): String =
                    transaction {
                        ElectionOptionTable
                            .selectAll()
                            .where {
                                (ElectionOptionTable.electionId eq Uuid.parse(electionId)) and
                                    (ElectionOptionTable.candidacyId eq Uuid.parse(candidacyId))
                            }.single()[ElectionOptionTable.id]
                    }.toString()
                val optionAId = optionIdFor(candidacyA)
                val optionBId = optionIdFor(candidacyB)
                val optionCId = optionIdFor(candidacyC)

                // 3 votes for A, 2 for B, 2 for C: A is the plurality winner (3/7 ~= 42.9%) but
                // fails the default 50% absolute-majority requirement -- must not be seated.
                voters.take(3).forEach { v ->
                    client.post(
                        "/test/cast-election-ballot/$electionId?selectedOptionIds=$optionAId",
                    ) { header("X-Member-Id", v.toString()) }
                }
                voters.drop(3).take(2).forEach { v ->
                    client.post(
                        "/test/cast-election-ballot/$electionId?selectedOptionIds=$optionBId",
                    ) { header("X-Member-Id", v.toString()) }
                }
                voters.drop(5).take(2).forEach { v ->
                    client.post(
                        "/test/cast-election-ballot/$electionId?selectedOptionIds=$optionCId",
                    ) { header("X-Member-Id", v.toString()) }
                }

                client.post("/test/close-voting/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }
                client.post("/test/release-tally/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }
                client.post("/test/release-tally/$electionId") { header("X-Member-Id", electionBoardMembers[1].toString()) }
                val ergebnis =
                    client.post("/test/tally/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }.bodyAsText()
                // winnerOptionIds empty, tie=true (majority-not-met is reported as a tie), majorityMet blank (personnel Election).
                ergebnis shouldBe ":true:"

                val motionStatus =
                    transaction { MotionTable.selectAll().where { MotionTable.id eq motionId }.single()[MotionTable.status] }
                motionStatus shouldBe MotionStatus.POSTPONED

                val seated =
                    transaction {
                        CommitteeMembershipTable
                            .selectAll()
                            .where {
                                (CommitteeMembershipTable.committeeId eq targetCommittee) and
                                    (CommitteeMembershipTable.memberId eq candidateA)
                            }.count()
                    }
                seated shouldBe 0L
            }
        }

        test(
            "tally seating an incumbent (already an active member of targetCommittee) closes the " +
                "existing active membership instead of leaving two concurrent active rows",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installElectionExceptionHandlers() }
                    routing { registerElectionTestRoutes() }
                }

                val hostCommittee = createTestCommittee("MV Einzelelection Wiederelection", CommitteeType.GENERAL_ASSEMBLY)
                val targetCommittee = createTestCommittee("Executive Board Wiederelection Ziel")
                val chair = createTestMember("election-wiederelection-chair@example.org")
                addMember(hostCommittee, chair, CommitteeRole.CHAIR)

                // Incumbent: already an active MEMBER of targetCommittee before the Election even opens.
                val incumbent = createTestMember("election-wiederelection-incumbent@example.org")
                addMember(targetCommittee, incumbent, CommitteeRole.MEMBER)
                val voter = createTestMember("election-wiederelection-voter@example.org")
                val electionBoardMembers = (1..3).map { createTestMember("election-wiederelection-wv$it@example.org") }

                val meetingId = createTestMeeting(hostCommittee, LocalDateTime(2026, 11, 1, 18, 0))
                val motionId = createTerminierterMotion(hostCommittee, meetingId, chair)

                // Re-elected into a different Role (CHAIR) on the same Committee -- the "sitting
                // MEMBER elected to CHAIR" case from the review finding, not just plain re-election.
                val opened =
                    client
                        .post(
                            "/test/open-election/$motionId/SINGLE_CHOICE?targetCommitteeId=$targetCommittee&seatCount=1&targetRole=CHAIR&secret=false",
                        ) { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                val electionId = opened.substringBefore(":")

                client.post("/test/appoint-election-board/$electionId?memberIds=${electionBoardMembers.joinToString(",")}") {
                    header("X-Member-Id", chair.toString())
                }

                val candidacyIncumbent =
                    client.post("/test/submit-candidacy/$electionId") { header("X-Member-Id", incumbent.toString()) }.bodyAsText()
                client.post("/test/release-kandidatenliste/$electionId") { header("X-Member-Id", chair.toString()) }
                client.post("/test/open-voting/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }

                val optionId =
                    transaction {
                        ElectionOptionTable
                            .selectAll()
                            .where {
                                (ElectionOptionTable.electionId eq Uuid.parse(electionId)) and
                                    (ElectionOptionTable.candidacyId eq Uuid.parse(candidacyIncumbent))
                            }.single()[ElectionOptionTable.id]
                    }.toString()
                client.post(
                    "/test/cast-election-ballot/$electionId?selectedOptionIds=$optionId",
                ) { header("X-Member-Id", voter.toString()) }

                client.post("/test/close-voting/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }
                client.post("/test/release-tally/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }
                client.post("/test/release-tally/$electionId") { header("X-Member-Id", electionBoardMembers[1].toString()) }
                client.post("/test/tally/$electionId") { header("X-Member-Id", electionBoardMembers[0].toString()) }

                val rows =
                    transaction {
                        CommitteeMembershipTable
                            .selectAll()
                            .where {
                                (CommitteeMembershipTable.committeeId eq targetCommittee) and
                                    (CommitteeMembershipTable.memberId eq incumbent)
                            }.map { it[CommitteeMembershipTable.role] to (it[CommitteeMembershipTable.until] == null) }
                    }
                // Exactly one row (the pre-existing MEMBER membership) is closed, and exactly one
                // (the freshly seated CHAIR membership) is active -- never two concurrent active rows.
                rows.size shouldBe 2
                rows.count { (_, active) -> active } shouldBe 1
                val activeRole = rows.single { (_, active) -> active }.first
                activeRole shouldBe CommitteeRole.CHAIR
            }
        }
    })

private fun StatusPagesConfig.installElectionExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
    }
    exception<ForbiddenException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Forbidden)
    }
    exception<NotFoundException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.NotFound)
    }
    exception<ConflictException> { call, cause ->
        call.respondText(cause.message, status = HttpStatusCode.Conflict)
    }
}

/**
 * Shared throwaway routes for [ElectionServiceTest] -- string encodings are kept deliberately
 * simple/parseable, same house style as [GovernanceServiceTest]'s own test routes.
 */
private fun Route.registerElectionTestRoutes() {
    post("/test/open-election/{motionId}/{electionType}") {
        val service = ElectionService(call)
        val q = call.request.queryParameters
        val w =
            service.openElection(
                ElectionOpenInput(
                    motionId = call.parameters["motionId"]!!,
                    electionType = ElectionType.valueOf(call.parameters["electionType"]!!),
                    secret = q["secret"]?.toBoolean() ?: true,
                    seatCount = q["seatCount"]?.toInt() ?: 1,
                    targetCommitteeId = q["targetCommitteeId"],
                    targetRole = q["targetRole"]?.let { CommitteeRole.valueOf(it) },
                    requiredMajorityPercent = q["requiredMajorityPercent"]?.toInt() ?: 50,
                    tallyThreshold = q["tallyThreshold"]?.toInt() ?: 2,
                ),
            )
        call.respondText("${w.id}:${w.status}")
    }
    post("/test/appoint-election-board/{electionId}") {
        val service = ElectionService(call)
        val memberIds = call.request.queryParameters["memberIds"]!!.split(",")
        val list = service.appointElectionBoard(call.parameters["electionId"]!!, memberIds)
        call.respondText(list.size.toString())
    }
    post("/test/submit-candidacy/{electionId}") {
        val service = ElectionService(call)
        val k = service.submitCandidacy(call.parameters["electionId"]!!, CandidacyInput(motivationText = "Motivation"))
        call.respondText(k.id)
    }
    post("/test/withdraw-candidacy/{id}") {
        val service = ElectionService(call)
        val k = service.withdrawCandidacy(call.parameters["id"]!!)
        call.respondText((k.withdrawnAt != null).toString())
    }
    post("/test/release-kandidatenliste/{electionId}") {
        val service = ElectionService(call)
        val w = service.releaseCandidateList(call.parameters["electionId"]!!)
        call.respondText("${w.status}:${w.options.size}")
    }
    post("/test/open-voting/{electionId}") {
        val service = ElectionService(call)
        val w = service.openVoting(call.parameters["electionId"]!!)
        call.respondText(w.status.name)
    }
    post("/test/cast-election-ballot/{electionId}") {
        val service = ElectionService(call)
        val q = call.request.queryParameters
        val answer = q["answer"]?.let { ElectionAnswer.valueOf(it) }
        val selectedOptionIds = q["selectedOptionIds"]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val result =
            service.castElectionBallot(
                ElectionBallotInput(
                    electionId = call.parameters["electionId"]!!,
                    answer = answer,
                    selectedOptionIds = selectedOptionIds,
                ),
            )
        call.respondText("${result.id}:${result.receiptCode ?: ""}")
    }
    post("/test/close-voting/{electionId}") {
        val service = ElectionService(call)
        val w = service.closeVoting(call.parameters["electionId"]!!)
        call.respondText(w.status.name)
    }
    post("/test/release-tally/{electionId}") {
        val service = ElectionService(call)
        service.approveTally(call.parameters["electionId"]!!)
        call.respondText("ok")
    }
    post("/test/tally/{electionId}") {
        val service = ElectionService(call)
        val e = service.tally(call.parameters["electionId"]!!)
        call.respondText("${e.winnerOptionIds.joinToString(",")}:${e.tie}:${e.majorityMet ?: ""}")
    }
    post("/test/abort-election/{electionId}") {
        val service = ElectionService(call)
        val w = service.abortElection(call.parameters["electionId"]!!)
        call.respondText(w.status.name)
    }
    get("/test/get-election/{electionId}") {
        val service = ElectionService(call)
        val w = service.getElection(call.parameters["electionId"]!!)
        call.respondText("${w.status}:${w.options.size}:${w.resolutionId ?: ""}")
    }
    get("/test/verify-receipt/{electionId}") {
        val service = ElectionService(call)
        val receiptCode = call.request.queryParameters["receiptCode"]!!
        val r = service.verifyReceipt(call.parameters["electionId"]!!, receiptCode)
        call.respondText("${r.found}:${r.optionLabel ?: ""}")
    }
    get("/test/list-ballots/{electionId}") {
        val service = ElectionService(call)
        val list = service.listElectionBallots(call.parameters["electionId"]!!)
        call.respondText(list.joinToString(";") { "${it.id}:${it.selectedOptionLabels.joinToString("|")}" })
    }
}

/**
 * Hard-deletes every row this Spec created, child-before-parent -- same discipline as
 * [cleanUpGovernanceTestData]. Election child tables are deleted before [ElectionTable] itself; [ElectionTable]
 * and [ResolutionTable] are mutually FK-linked (`election.resolution_id` -> `resolution.id`,
 * `resolution.election_id` -> `election.id`), so both FKs are nulled out before either table's rows are
 * deleted, mirroring [cleanUpGovernanceTestData]'s `vote`/`resolution` cycle-breaking.
 */
private fun cleanUpElectionTestData(
    committeeIds: List<Uuid>,
    memberIds: List<Uuid>,
) {
    if (committeeIds.isEmpty() && memberIds.isEmpty()) return
    transaction {
        // V0.5.3 GoBD audit log: ElectionService.tally now writes AuditLogEntryTable rows
        // (RESOLUTION + BOARD_MEMBERSHIP) referencing the tallying member via a real FK
        // (actor_member_id) -- null it out first (audit_log_entry rows themselves are never
        // deleted, see AuditLogRecorder KDoc) so the MemberTable delete below does not violate
        // that FK, same fix cleanUpGovernanceTestData already applies.
        if (memberIds.isNotEmpty()) {
            AuditLogEntryTable.update({ AuditLogEntryTable.actorMemberId inList memberIds }) {
                it[actorMemberId] = null
            }
        }

        val meetingIds =
            if (committeeIds.isEmpty()) {
                emptyList()
            } else {
                MeetingTable.selectAll().where { MeetingTable.committeeId inList committeeIds }.map { it[MeetingTable.id] }
            }

        val electionCondition =
            when {
                meetingIds.isNotEmpty() && memberIds.isNotEmpty() ->
                    (ElectionTable.meetingId inList meetingIds) or (ElectionTable.openedBy inList memberIds)
                meetingIds.isNotEmpty() -> ElectionTable.meetingId inList meetingIds
                memberIds.isNotEmpty() -> ElectionTable.openedBy inList memberIds
                else -> null
            }
        val electionIds =
            if (electionCondition != null) {
                ElectionTable.selectAll().where { electionCondition }.map { it[ElectionTable.id] }
            } else {
                emptyList()
            }
        if (electionIds.isNotEmpty()) {
            ElectionTable.update({ ElectionTable.id inList electionIds }) { it[resolutionId] = null }
            ResolutionTable.update({ ResolutionTable.electionId inList electionIds }) { it[ResolutionTable.electionId] = null }
            val ballotIds =
                ElectionBallotTable
                    .selectAll()
                    .where {
                        ElectionBallotTable.electionId inList electionIds
                    }.map { it[ElectionBallotTable.id] }
            if (ballotIds.isNotEmpty()) {
                ElectionBallotSelectionTable.deleteWhere { ElectionBallotSelectionTable.ballotId inList ballotIds }
            }
            ElectionBallotTable.deleteWhere { ElectionBallotTable.electionId inList electionIds }
            ElectionParticipationTable.deleteWhere { ElectionParticipationTable.electionId inList electionIds }
            ElectionTallyApprovalTable.deleteWhere { ElectionTallyApprovalTable.electionId inList electionIds }
            ElectionEligibleVoterTable.deleteWhere { ElectionEligibleVoterTable.electionId inList electionIds }
            ElectionBoardMemberTable.deleteWhere { ElectionBoardMemberTable.electionId inList electionIds }
            ElectionOptionTable.deleteWhere { ElectionOptionTable.electionId inList electionIds }
            ElectionCandidacyTable.deleteWhere { ElectionCandidacyTable.electionId inList electionIds }
            ElectionTable.deleteWhere { ElectionTable.id inList electionIds }
        }

        if (committeeIds.isNotEmpty() || memberIds.isNotEmpty()) {
            val motionCondition =
                when {
                    committeeIds.isNotEmpty() && memberIds.isNotEmpty() ->
                        (MotionTable.targetCommitteeId inList committeeIds) or
                            (MotionTable.submitterMemberId inList memberIds) or
                            (MotionTable.reviewedBy inList memberIds)
                    committeeIds.isNotEmpty() -> MotionTable.targetCommitteeId inList committeeIds
                    else -> (MotionTable.submitterMemberId inList memberIds) or (MotionTable.reviewedBy inList memberIds)
                }
            MotionTable.deleteWhere { motionCondition }
        }
        if (meetingIds.isNotEmpty()) {
            ResolutionTable.deleteWhere { ResolutionTable.meetingId inList meetingIds }
            MeetingTable.deleteWhere { MeetingTable.id inList meetingIds }
        }
        if (committeeIds.isNotEmpty()) {
            CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId inList committeeIds }
            CommitteeTable.deleteWhere { CommitteeTable.id inList committeeIds }
        }
        if (memberIds.isNotEmpty()) {
            // V0.5.2: personnel Electionen with an EXECUTIVE_BOARD targetCommittee (the default in
            // this file's own createTestCommittee helper) auto-create board_membership/
            // transparenzregister_reminder rows for the winner via
            // BoardMembershipEvents.recordBoardJoin (ElectionService.tally) -- both carry a
            // member_id FK that must be cleared before MemberTable rows are deleted.
            TransparenzregisterReminderTable.update({ TransparenzregisterReminderTable.resolvedBy inList memberIds }) {
                it[resolvedBy] = null
            }
            TransparenzregisterReminderTable.deleteWhere { TransparenzregisterReminderTable.memberId inList memberIds }
            BoardMembershipTable.deleteWhere { BoardMembershipTable.memberId inList memberIds }
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
    }
}
