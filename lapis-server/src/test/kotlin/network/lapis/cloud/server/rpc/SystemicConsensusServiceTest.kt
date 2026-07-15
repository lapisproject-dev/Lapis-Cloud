package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.ResolutionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusBallotTable
import network.lapis.cloud.server.db.generated.SystemicConsensusEligibleVoterTable
import network.lapis.cloud.server.db.generated.SystemicConsensusOptionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusParticipationTable
import network.lapis.cloud.server.db.generated.SystemicConsensusResistanceTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import network.lapis.cloud.server.security.ForbiddenException
import network.lapis.cloud.server.security.UnauthenticatedException
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.ResolutionMode
import network.lapis.cloud.shared.domain.ResolutionStatus
import network.lapis.cloud.shared.domain.SystemicConsensusBallotInput
import network.lapis.cloud.shared.domain.SystemicConsensusBindingness
import network.lapis.cloud.shared.domain.SystemicConsensusOpenInput
import network.lapis.cloud.shared.domain.SystemicConsensusOptionInput
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
 * Exercises [SystemicConsensusService] end to end -- same "throwaway routes calling the service class
 * directly" house style as [ElectionServiceTest]/[GovernanceServiceTest]. Uses its own freshly created
 * Gremien/Meetingen/Antraege/members throughout. [afterSpec] hard-deletes every row this file
 * created.
 */
class SystemicConsensusServiceTest :
    FunSpec({
        val createdCommitteeIds = mutableListOf<Uuid>()
        val createdMemberIds = mutableListOf<Uuid>()

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec { cleanUpSystemicConsensusTestData(createdCommitteeIds, createdMemberIds) }

        fun createTestMember(
            email: String,
            status: MemberStatus = MemberStatus.AKTIV,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "SystemicConsensus Testmitglied"
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
            "full lifecycle: open (secret, status quo option auto-add) -> participants add options -> freeze -> " +
                "castResistanceBallot -> close -> evaluate (ADVISORY writes no Resolution)",
        ) {
            testApplication {
                application {
                    install(StatusPages) { installSystemicConsensusExceptionHandlers() }
                    routing { registerSystemicConsensusTestRoutes() }
                }

                val committeeId = createTestCommittee("SK Executive Board")
                val chair = createTestMember("sk-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val voter1 = createTestMember("sk-v1@example.org")
                val voter2 = createTestMember("sk-v2@example.org")
                addMember(committeeId, voter1, CommitteeRole.MEMBER)
                addMember(committeeId, voter2, CommitteeRole.MEMBER)

                val meetingId = createTestMeeting(committeeId, LocalDateTime(2026, 3, 1, 18, 0))
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                val opened =
                    client
                        .post("/test/open-systemic_consensus/$motionId") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                val parts = opened.split(":")
                val systemicConsensusId = parts[0]
                parts[1] shouldBe "COLLECTION"
                parts[2] shouldBe "1" // status quo option auto-added

                val optionAId =
                    client
                        .post("/test/add-option/$systemicConsensusId?label=Option+A") { header("X-Member-Id", voter1.toString()) }
                        .bodyAsText()
                val optionBId =
                    client
                        .post("/test/add-option/$systemicConsensusId?label=Option+B") { header("X-Member-Id", voter2.toString()) }
                        .bodyAsText()

                val listed = client.get("/test/list-options/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                listed.split(";").size shouldBe 3

                val frozen =
                    client.post("/test/freeze-optionen/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                frozen shouldBe "RATING:3"

                val passivloesungId =
                    transaction {
                        SystemicConsensusOptionTable
                            .selectAll()
                            .where {
                                (SystemicConsensusOptionTable.systemicConsensusId eq Uuid.parse(systemicConsensusId)) and
                                    (SystemicConsensusOptionTable.isStatusQuoOption eq true)
                            }.single()[SystemicConsensusOptionTable.id]
                    }.toString()

                fun resistancesParam(
                    passiv: Int,
                    a: Int,
                    b: Int,
                ) = "$passivloesungId:$passiv,$optionAId:$a,$optionBId:$b"

                client.post("/test/cast-resistance/$systemicConsensusId?resistances=${resistancesParam(8, 2, 5)}") {
                    header("X-Member-Id", chair.toString())
                }
                client.post("/test/cast-resistance/$systemicConsensusId?resistances=${resistancesParam(9, 1, 6)}") {
                    header("X-Member-Id", voter1.toString())
                }
                client.post("/test/cast-resistance/$systemicConsensusId?resistances=${resistancesParam(7, 3, 4)}") {
                    header("X-Member-Id", voter2.toString())
                }

                val closed =
                    client
                        .post(
                            "/test/close-rating/$systemicConsensusId",
                        ) { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                closed shouldBe "CLOSED"

                val ergebnis = client.post("/test/evaluate/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                // Option A has the lowest cumulative resistance (2+1+3=6) of the three.
                ergebnis shouldBe "$optionAId:false:false"

                val final =
                    client.get("/test/get-systemic_consensus/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val finalParts = final.split(":")
                finalParts[0] shouldBe "EVALUATED"
                finalParts[2] shouldBe "" // ADVISORY (default bindingness) writes no Resolution
            }
        }

        test("bindingness=BINDING writes a Resolution tagged SYSTEMIC_CONSENSUS and transitions the Motion") {
            testApplication {
                application {
                    install(StatusPages) { installSystemicConsensusExceptionHandlers() }
                    routing { registerSystemicConsensusTestRoutes() }
                }

                val committeeId = createTestCommittee("SK Resolution Executive Board")
                val chair = createTestMember("sk-resolution-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val voter = createTestMember("sk-resolution-voter@example.org")
                addMember(committeeId, voter, CommitteeRole.MEMBER)

                val meetingId = createTestMeeting(committeeId, LocalDateTime(2026, 3, 15, 18, 0))
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                val systemicConsensusId =
                    client
                        .post("/test/open-systemic_consensus/$motionId?bindingness=BINDING&statusQuoOptionAuto=false") {
                            header("X-Member-Id", chair.toString())
                        }.bodyAsText()
                        .substringBefore(":")

                val optionId =
                    client
                        .post("/test/add-option/$systemicConsensusId?label=Einzige+Option") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                client.post("/test/freeze-optionen/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }

                client.post(
                    "/test/cast-resistance/$systemicConsensusId?resistances=$optionId:2",
                ) { header("X-Member-Id", chair.toString()) }
                client.post(
                    "/test/cast-resistance/$systemicConsensusId?resistances=$optionId:4",
                ) { header("X-Member-Id", voter.toString()) }

                client.post("/test/close-rating/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }
                client.post("/test/evaluate/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }

                val final =
                    client.get("/test/get-systemic_consensus/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val resolutionId = final.split(":")[2]
                resolutionId.isBlank() shouldBe false

                val resolution =
                    transaction { ResolutionTable.selectAll().where { ResolutionTable.id eq Uuid.parse(resolutionId) }.single() }
                transaction { resolution[ResolutionTable.resolutionMode] } shouldBe ResolutionMode.SYSTEMIC_CONSENSUS
                transaction { resolution[ResolutionTable.status] } shouldBe ResolutionStatus.ADOPTED
                transaction { resolution[ResolutionTable.systemicConsensusId]?.toString() } shouldBe systemicConsensusId

                val motionStatus = transaction { MotionTable.selectAll().where { MotionTable.id eq motionId }.single()[MotionTable.status] }
                motionStatus shouldBe MotionStatus.RESOLVED
            }
        }

        test("reopenRating is rejected once a binding Resolution has been recorded (bindingness=BINDING)") {
            testApplication {
                application {
                    install(StatusPages) { installSystemicConsensusExceptionHandlers() }
                    routing { registerSystemicConsensusTestRoutes() }
                }

                val committeeId = createTestCommittee("SK Reopen-Resolution Executive Board")
                val chair = createTestMember("sk-reopen-resolution-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val voter = createTestMember("sk-reopen-resolution-voter@example.org")
                addMember(committeeId, voter, CommitteeRole.MEMBER)

                val meetingId = createTestMeeting(committeeId, LocalDateTime(2026, 3, 20, 18, 0))
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                val systemicConsensusId =
                    client
                        .post("/test/open-systemic_consensus/$motionId?bindingness=BINDING&statusQuoOptionAuto=false&maxRounds=3") {
                            header("X-Member-Id", chair.toString())
                        }.bodyAsText()
                        .substringBefore(":")

                val optionId =
                    client
                        .post("/test/add-option/$systemicConsensusId?label=Einzige+Option") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                client.post("/test/freeze-optionen/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }

                client.post(
                    "/test/cast-resistance/$systemicConsensusId?resistances=$optionId:2",
                ) { header("X-Member-Id", chair.toString()) }
                client.post(
                    "/test/cast-resistance/$systemicConsensusId?resistances=$optionId:4",
                ) { header("X-Member-Id", voter.toString()) }

                client.post("/test/close-rating/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }
                client.post("/test/evaluate/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }

                val beforeReopen =
                    client.get("/test/get-systemic_consensus/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val resolutionIdBefore = beforeReopen.split(":")[2]
                resolutionIdBefore.isBlank() shouldBe false

                // The binding Resolution is already recorded -- reopening must be rejected so the
                // resolution book record is never orphaned/duplicated.
                val reopenAttempt =
                    client.post("/test/reopen-rating/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }
                reopenAttempt.status shouldBe HttpStatusCode.Conflict

                // SystemicConsensus is unchanged: still EVALUATED, still round 1, same Resolution.
                val afterReopen =
                    client.get("/test/get-systemic_consensus/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                val afterParts = afterReopen.split(":")
                afterParts[0] shouldBe "EVALUATED"
                afterParts[2] shouldBe resolutionIdBefore
                afterParts[3] shouldBe "1"

                val resolutionCount =
                    transaction {
                        ResolutionTable
                            .selectAll()
                            .where { ResolutionTable.systemicConsensusId eq Uuid.parse(systemicConsensusId) }
                            .count()
                    }
                resolutionCount shouldBe 1L
            }
        }

        test("one-rating-per-member-per-round: a second castResistanceBallot attempt is rejected on both the secret and non-secret path") {
            testApplication {
                application {
                    install(StatusPages) { installSystemicConsensusExceptionHandlers() }
                    routing { registerSystemicConsensusTestRoutes() }
                }

                val committeeId = createTestCommittee("SK DoubleRating Executive Board")
                val chair = createTestMember("sk-double-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val voter = createTestMember("sk-double-voter@example.org")
                addMember(committeeId, voter, CommitteeRole.MEMBER)

                listOf(true, false).forEach { secret ->
                    val meetingId = createTestMeeting(committeeId, LocalDateTime(2026, 5, 1, 18, 0))
                    val motionId = createTerminierterMotion(committeeId, meetingId, chair)
                    val systemicConsensusId =
                        client
                            .post("/test/open-systemic_consensus/$motionId?secret=$secret&statusQuoOptionAuto=false") {
                                header("X-Member-Id", chair.toString())
                            }.bodyAsText()
                            .substringBefore(":")
                    val optionId =
                        client
                            .post("/test/add-option/$systemicConsensusId?label=Option") { header("X-Member-Id", chair.toString()) }
                            .bodyAsText()
                    client.post("/test/freeze-optionen/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }

                    val first =
                        client.post(
                            "/test/cast-resistance/$systemicConsensusId?resistances=$optionId:3",
                        ) { header("X-Member-Id", voter.toString()) }
                    first.status shouldBe HttpStatusCode.OK
                    val second =
                        client.post(
                            "/test/cast-resistance/$systemicConsensusId?resistances=$optionId:7",
                        ) { header("X-Member-Id", voter.toString()) }
                    second.status shouldBe HttpStatusCode.Conflict
                }
            }
        }

        test("concurrency: N simultaneous castResistanceBallot calls for the same member -- exactly one succeeds") {
            testApplication {
                application {
                    install(StatusPages) { installSystemicConsensusExceptionHandlers() }
                    routing { registerSystemicConsensusTestRoutes() }
                }

                val committeeId = createTestCommittee("SK Concurrency Executive Board")
                val chair = createTestMember("sk-concurrency-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val voter = createTestMember("sk-concurrency-voter@example.org")
                addMember(committeeId, voter, CommitteeRole.MEMBER)

                val meetingId = createTestMeeting(committeeId, LocalDateTime(2026, 6, 1, 18, 0))
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)
                val systemicConsensusId =
                    client
                        .post(
                            "/test/open-systemic_consensus/$motionId?statusQuoOptionAuto=false",
                        ) { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                val optionId =
                    client
                        .post(
                            "/test/add-option/$systemicConsensusId?label=Option",
                        ) { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                client.post("/test/freeze-optionen/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }

                val attempts = 8
                val results =
                    coroutineScope {
                        (1..attempts)
                            .map {
                                async {
                                    client
                                        .post("/test/cast-resistance/$systemicConsensusId?resistances=$optionId:5") {
                                            header("X-Member-Id", voter.toString())
                                        }.status
                                }
                            }.map { it.await() }
                    }
                results.count { it == HttpStatusCode.OK } shouldBe 1
                results.count { it == HttpStatusCode.Conflict } shouldBe attempts - 1

                val participationCount =
                    transaction {
                        SystemicConsensusParticipationTable
                            .selectAll()
                            .where {
                                (SystemicConsensusParticipationTable.systemicConsensusId eq Uuid.parse(systemicConsensusId)) and
                                    (SystemicConsensusParticipationTable.memberId eq voter)
                            }.count()
                    }
                participationCount shouldBe 1L
            }
        }

        test("listResistanceBallots hides values for a secret SystemicConsensus until EVALUATED (no mid-Rating running-tally leak)") {
            testApplication {
                application {
                    install(StatusPages) { installSystemicConsensusExceptionHandlers() }
                    routing { registerSystemicConsensusTestRoutes() }
                }

                val committeeId = createTestCommittee("SK Transparency Executive Board")
                val chair = createTestMember("sk-transparency-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val voter = createTestMember("sk-transparency-voter@example.org")
                addMember(committeeId, voter, CommitteeRole.MEMBER)

                val meetingId = createTestMeeting(committeeId, LocalDateTime(2026, 6, 15, 18, 0))
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)
                val systemicConsensusId =
                    client
                        .post("/test/open-systemic_consensus/$motionId?secret=true&statusQuoOptionAuto=false") {
                            header("X-Member-Id", chair.toString())
                        }.bodyAsText()
                        .substringBefore(":")
                val optionId =
                    client
                        .post(
                            "/test/add-option/$systemicConsensusId?label=Option",
                        ) { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                client.post("/test/freeze-optionen/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }
                client.post(
                    "/test/cast-resistance/$systemicConsensusId?resistances=$optionId:6",
                ) { header("X-Member-Id", voter.toString()) }

                val beforeTally =
                    client.get("/test/list-resistances/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                beforeTally.split(";").forEach { entry -> entry.substringAfter(":") shouldBe "0" }

                client.post("/test/close-rating/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }
                client.post("/test/evaluate/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }

                val afterTally =
                    client.get("/test/list-resistances/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                afterTally.split(";").any { entry -> entry.substringAfter(":") == "1" } shouldBe true
            }
        }

        test("reopenRating increments round and retains prior-round ballots; rejected once maxRounds is reached") {
            testApplication {
                application {
                    install(StatusPages) { installSystemicConsensusExceptionHandlers() }
                    routing { registerSystemicConsensusTestRoutes() }
                }

                val committeeId = createTestCommittee("SK Wiederholung Executive Board")
                val chair = createTestMember("sk-wiederholung-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val voter = createTestMember("sk-wiederholung-voter@example.org")
                addMember(committeeId, voter, CommitteeRole.MEMBER)

                val meetingId = createTestMeeting(committeeId, LocalDateTime(2026, 7, 1, 18, 0))
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)
                val systemicConsensusId =
                    client
                        .post("/test/open-systemic_consensus/$motionId?statusQuoOptionAuto=false&maxRounds=2") {
                            header("X-Member-Id", chair.toString())
                        }.bodyAsText()
                        .substringBefore(":")
                val optionId =
                    client
                        .post(
                            "/test/add-option/$systemicConsensusId?label=Option",
                        ) { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                client.post("/test/freeze-optionen/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }
                client.post(
                    "/test/cast-resistance/$systemicConsensusId?resistances=$optionId:8",
                ) { header("X-Member-Id", voter.toString()) }
                client.post("/test/close-rating/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }
                client.post("/test/evaluate/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }

                val reopened =
                    client.post("/test/reopen-rating/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
                reopened shouldBe "RATING:2"

                // Prior round's ballot is retained (DSGVO retention), not deleted.
                val round1Count =
                    transaction {
                        SystemicConsensusBallotTable
                            .selectAll()
                            .where {
                                (SystemicConsensusBallotTable.systemicConsensusId eq Uuid.parse(systemicConsensusId)) and
                                    (SystemicConsensusBallotTable.round eq 1)
                            }.count()
                    }
                round1Count shouldBe 1L

                // The same member may rate again in round 2 (fresh eligibility snapshot).
                client.post(
                    "/test/cast-resistance/$systemicConsensusId?resistances=$optionId:2",
                ) { header("X-Member-Id", voter.toString()) }
                client.post("/test/close-rating/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }
                client.post("/test/evaluate/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }

                // maxRounds=2 already reached (round=2) -- a further reopen must be rejected.
                val cappedReopen =
                    client.post("/test/reopen-rating/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }
                cappedReopen.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("status guards: castResistanceBallot while COLLECTION and freezeOptions with zero options are both rejected") {
            testApplication {
                application {
                    install(StatusPages) { installSystemicConsensusExceptionHandlers() }
                    routing { registerSystemicConsensusTestRoutes() }
                }

                val committeeId = createTestCommittee("SK Statusguard Executive Board")
                val chair = createTestMember("sk-statusguard-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)

                val meetingId = createTestMeeting(committeeId, LocalDateTime(2026, 8, 1, 18, 0))
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)
                val systemicConsensusId =
                    client
                        .post(
                            "/test/open-systemic_consensus/$motionId?statusQuoOptionAuto=false",
                        ) { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")

                val prematureCast =
                    client.post("/test/cast-resistance/$systemicConsensusId?resistances=") { header("X-Member-Id", chair.toString()) }
                prematureCast.status shouldBe HttpStatusCode.Conflict

                val emptyFreeze =
                    client.post("/test/freeze-optionen/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }
                emptyFreeze.status shouldBe HttpStatusCode.Conflict
            }
        }

        test("authorization: non-Committee-leadership member cannot openSystemicConsensus; ineligible member cannot castResistanceBallot") {
            testApplication {
                application {
                    install(StatusPages) { installSystemicConsensusExceptionHandlers() }
                    routing { registerSystemicConsensusTestRoutes() }
                }

                val committeeId = createTestCommittee("SK Auth Executive Board")
                val chair = createTestMember("sk-auth-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val plainMember = createTestMember("sk-auth-plain@example.org")
                addMember(committeeId, plainMember, CommitteeRole.MEMBER)
                val outsider = createTestMember("sk-auth-outsider@example.org")

                val meetingId = createTestMeeting(committeeId, LocalDateTime(2026, 9, 1, 18, 0))
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)

                val forbiddenOpen =
                    client.post("/test/open-systemic_consensus/$motionId") { header("X-Member-Id", plainMember.toString()) }
                forbiddenOpen.status shouldBe HttpStatusCode.Forbidden

                val systemicConsensusId =
                    client
                        .post(
                            "/test/open-systemic_consensus/$motionId?statusQuoOptionAuto=false",
                        ) { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                val optionId =
                    client
                        .post(
                            "/test/add-option/$systemicConsensusId?label=Option",
                        ) { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                client.post("/test/freeze-optionen/$systemicConsensusId") { header("X-Member-Id", chair.toString()) }

                // outsider is not in the eligibility snapshot (not a Committee member).
                val forbiddenCast =
                    client.post(
                        "/test/cast-resistance/$systemicConsensusId?resistances=$optionId:5",
                    ) { header("X-Member-Id", outsider.toString()) }
                forbiddenCast.status shouldBe HttpStatusCode.Forbidden

                val unauthenticated = client.post("/test/cast-resistance/$systemicConsensusId?resistances=$optionId:5")
                unauthenticated.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("removeOption never removes the status quo option option, and only the proposer or leadership may remove a regular option") {
            testApplication {
                application {
                    install(StatusPages) { installSystemicConsensusExceptionHandlers() }
                    routing { registerSystemicConsensusTestRoutes() }
                }

                val committeeId = createTestCommittee("SK RemoveOption Executive Board")
                val chair = createTestMember("sk-removeoption-chair@example.org")
                addMember(committeeId, chair, CommitteeRole.CHAIR)
                val proposer = createTestMember("sk-removeoption-proposer@example.org")
                addMember(committeeId, proposer, CommitteeRole.MEMBER)
                val otherMember = createTestMember("sk-removeoption-other@example.org")
                addMember(committeeId, otherMember, CommitteeRole.MEMBER)

                val meetingId = createTestMeeting(committeeId, LocalDateTime(2026, 9, 15, 18, 0))
                val motionId = createTerminierterMotion(committeeId, meetingId, chair)
                val systemicConsensusId =
                    client
                        .post("/test/open-systemic_consensus/$motionId") { header("X-Member-Id", chair.toString()) }
                        .bodyAsText()
                        .substringBefore(":")
                val passivloesungId =
                    transaction {
                        SystemicConsensusOptionTable
                            .selectAll()
                            .where {
                                (SystemicConsensusOptionTable.systemicConsensusId eq Uuid.parse(systemicConsensusId)) and
                                    (SystemicConsensusOptionTable.isStatusQuoOption eq true)
                            }.single()[SystemicConsensusOptionTable.id]
                    }.toString()
                val optionId =
                    client
                        .post("/test/add-option/$systemicConsensusId?label=Vorschlag") { header("X-Member-Id", proposer.toString()) }
                        .bodyAsText()

                val passivloesungRemoval =
                    client.post("/test/remove-option/$passivloesungId") { header("X-Member-Id", chair.toString()) }
                passivloesungRemoval.status shouldBe HttpStatusCode.Conflict

                val forbiddenRemoval =
                    client.post("/test/remove-option/$optionId") { header("X-Member-Id", otherMember.toString()) }
                forbiddenRemoval.status shouldBe HttpStatusCode.Forbidden

                val allowedRemoval =
                    client.post("/test/remove-option/$optionId") { header("X-Member-Id", proposer.toString()) }.bodyAsText()
                allowedRemoval shouldBe "COLLECTION:1"
            }
        }
    })

private fun StatusPagesConfig.installSystemicConsensusExceptionHandlers() {
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
 * Shared throwaway routes for [SystemicConsensusServiceTest] -- string encodings are kept
 * deliberately simple/parseable, same house style as [ElectionServiceTest]'s own test routes.
 */
private fun Route.registerSystemicConsensusTestRoutes() {
    post("/test/open-systemic_consensus/{motionId}") {
        val service = SystemicConsensusService(call)
        val q = call.request.queryParameters
        val k =
            service.openSystemicConsensus(
                SystemicConsensusOpenInput(
                    motionId = call.parameters["motionId"]!!,
                    secret = q["secret"]?.toBoolean() ?: true,
                    scaleMax = q["scaleMax"]?.toInt() ?: 10,
                    statusQuoOptionAuto = q["statusQuoOptionAuto"]?.toBoolean() ?: true,
                    bindingness =
                        q["bindingness"]?.let { SystemicConsensusBindingness.valueOf(it) } ?: SystemicConsensusBindingness.ADVISORY,
                    maxRounds = q["maxRounds"]?.toInt() ?: 3,
                ),
            )
        call.respondText("${k.id}:${k.status}:${k.options.size}")
    }
    post("/test/add-option/{systemicConsensusId}") {
        val service = SystemicConsensusService(call)
        val label = call.request.queryParameters["label"]!!
        val o = service.addOption(call.parameters["systemicConsensusId"]!!, SystemicConsensusOptionInput(label = label))
        call.respondText(o.id)
    }
    post("/test/remove-option/{optionId}") {
        val service = SystemicConsensusService(call)
        val k = service.removeOption(call.parameters["optionId"]!!)
        call.respondText("${k.status}:${k.options.size}")
    }
    get("/test/list-options/{systemicConsensusId}") {
        val service = SystemicConsensusService(call)
        val list = service.listOptions(call.parameters["systemicConsensusId"]!!)
        call.respondText(list.joinToString(";") { "${it.id}:${it.label}:${it.isStatusQuoOption}" })
    }
    post("/test/freeze-optionen/{systemicConsensusId}") {
        val service = SystemicConsensusService(call)
        val k = service.freezeOptions(call.parameters["systemicConsensusId"]!!)
        call.respondText("${k.status}:${k.options.size}")
    }
    post("/test/cast-resistance/{systemicConsensusId}") {
        val service = SystemicConsensusService(call)
        val param = call.request.queryParameters["resistances"] ?: ""
        val resistances =
            param
                .split(",")
                .filter { it.isNotBlank() }
                .associate { pair ->
                    val (optId, value) = pair.split(":")
                    optId to value.toInt()
                }
        val result =
            service.castResistanceBallot(
                SystemicConsensusBallotInput(systemicConsensusId = call.parameters["systemicConsensusId"]!!, resistances = resistances),
            )
        call.respondText("${result.id}:${result.receiptCode ?: ""}")
    }
    post("/test/close-rating/{systemicConsensusId}") {
        val service = SystemicConsensusService(call)
        val k = service.closeRating(call.parameters["systemicConsensusId"]!!)
        call.respondText(k.status.name)
    }
    post("/test/evaluate/{systemicConsensusId}") {
        val service = SystemicConsensusService(call)
        val e = service.evaluate(call.parameters["systemicConsensusId"]!!)
        call.respondText("${e.winnerOptionId ?: ""}:${e.tie}:${e.noRatings}")
    }
    post("/test/reopen-rating/{systemicConsensusId}") {
        val service = SystemicConsensusService(call)
        val k = service.reopenRating(call.parameters["systemicConsensusId"]!!)
        call.respondText("${k.status}:${k.round}")
    }
    post("/test/abort-systemic_consensus/{systemicConsensusId}") {
        val service = SystemicConsensusService(call)
        val k = service.abortSystemicConsensus(call.parameters["systemicConsensusId"]!!)
        call.respondText(k.status.name)
    }
    get("/test/get-systemic_consensus/{systemicConsensusId}") {
        val service = SystemicConsensusService(call)
        val k = service.getSystemicConsensus(call.parameters["systemicConsensusId"]!!)
        call.respondText("${k.status}:${k.options.size}:${k.resolutionId ?: ""}:${k.round}")
    }
    get("/test/list-resistances/{systemicConsensusId}") {
        val service = SystemicConsensusService(call)
        val list = service.listResistanceBallots(call.parameters["systemicConsensusId"]!!)
        call.respondText(list.joinToString(";") { "${it.id}:${it.resistances.size}" })
    }
}

/**
 * Hard-deletes every row this Spec created, child-before-parent -- same discipline as
 * [cleanUpElectionTestData]. [SystemicConsensusTable] and [ResolutionTable] are mutually FK-linked
 * (`systemic_consensus.resolution_id` -> `resolution.id`, `resolution.systemic_consensus_id` ->
 * `systemic_consensus.id`), so both FKs are nulled out before either table's rows are deleted.
 */
private fun cleanUpSystemicConsensusTestData(
    committeeIds: List<Uuid>,
    memberIds: List<Uuid>,
) {
    if (committeeIds.isEmpty() && memberIds.isEmpty()) return
    transaction {
        val meetingIds =
            if (committeeIds.isEmpty()) {
                emptyList()
            } else {
                MeetingTable.selectAll().where { MeetingTable.committeeId inList committeeIds }.map { it[MeetingTable.id] }
            }

        val systemicConsensusCondition =
            when {
                meetingIds.isNotEmpty() && memberIds.isNotEmpty() ->
                    (SystemicConsensusTable.meetingId inList meetingIds) or (SystemicConsensusTable.openedBy inList memberIds)
                meetingIds.isNotEmpty() -> SystemicConsensusTable.meetingId inList meetingIds
                memberIds.isNotEmpty() -> SystemicConsensusTable.openedBy inList memberIds
                else -> null
            }
        val systemicConsensusIds =
            if (systemicConsensusCondition != null) {
                SystemicConsensusTable.selectAll().where { systemicConsensusCondition }.map { it[SystemicConsensusTable.id] }
            } else {
                emptyList()
            }
        if (systemicConsensusIds.isNotEmpty()) {
            SystemicConsensusTable.update({ SystemicConsensusTable.id inList systemicConsensusIds }) { it[resolutionId] = null }
            ResolutionTable.update({ ResolutionTable.systemicConsensusId inList systemicConsensusIds }) {
                it[ResolutionTable.systemicConsensusId] =
                    null
            }
            val ballotIds =
                SystemicConsensusBallotTable
                    .selectAll()
                    .where { SystemicConsensusBallotTable.systemicConsensusId inList systemicConsensusIds }
                    .map { it[SystemicConsensusBallotTable.id] }
            if (ballotIds.isNotEmpty()) {
                SystemicConsensusResistanceTable.deleteWhere { SystemicConsensusResistanceTable.ballotId inList ballotIds }
            }
            SystemicConsensusBallotTable.deleteWhere { SystemicConsensusBallotTable.systemicConsensusId inList systemicConsensusIds }
            SystemicConsensusParticipationTable.deleteWhere {
                SystemicConsensusParticipationTable.systemicConsensusId inList
                    systemicConsensusIds
            }
            SystemicConsensusEligibleVoterTable.deleteWhere {
                SystemicConsensusEligibleVoterTable.systemicConsensusId inList
                    systemicConsensusIds
            }
            SystemicConsensusOptionTable.deleteWhere { SystemicConsensusOptionTable.systemicConsensusId inList systemicConsensusIds }
            SystemicConsensusTable.deleteWhere { SystemicConsensusTable.id inList systemicConsensusIds }
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
            AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
            MemberTable.deleteWhere { MemberTable.id inList memberIds }
        }
    }
}
