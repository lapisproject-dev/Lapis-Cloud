package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.ElectionEligibleVoterTable
import network.lapis.cloud.server.db.generated.ElectionTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.SystemicConsensusEligibleVoterTable
import network.lapis.cloud.server.db.generated.VoteEligibleVoterTable
import network.lapis.cloud.server.db.generated.VoteTable
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.QuorumResultDto
import network.lapis.cloud.shared.domain.ResolutionDto
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.VoteBallotInput
import network.lapis.cloud.shared.domain.VoteDto
import network.lapis.cloud.shared.domain.VoteOpenInput
import network.lapis.cloud.shared.domain.VoteStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Welle V1.9.112 -- the electorate of a vote is frozen when the vote is opened: nobody who joins afterwards may vote and the denominator of
 * the quorum does not move; somebody who leaves the circle (the rule or the seat) afterwards keeps the right but the live membership guard
 * still applies; a vote from before V1.9.112 keeps the live set; the meeting's quorum follows its FIRST vote (aborted or not); an election
 * and a consensus count their own snapshot. Holds for a rule committee, a manual committee and the General Assembly alike.
 */
class VoteElectorateFreezeTest :
    FunSpec({
        val fx = CommitteeSeatFixture()
        val rc = RuleCommitteeFixture(fx)
        val json = Json { ignoreUnknownKeys = false }
        lateinit var board: Uuid

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            board = fx.member(role = AccountRole.BOARD, displayName = "Frost Vorstandsperson")
        }
        afterSpec {
            rc.cleanUp()
            fx.cleanUp()
        }

        fun Application.installRoutes() {
            install(StatusPages) {
                exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
                exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
                exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
            }
            routing {
                post("/t/open/{motionId}") {
                    val dto = GovernanceService(call = call).openVote(VoteOpenInput(motionId = call.parameters["motionId"]!!))
                    call.respondText(json.encodeToString(VoteDto.serializer(), dto))
                }
                post("/t/cast/{voteId}/{optionId}") {
                    GovernanceService(call = call).castVoteBallot(
                        VoteBallotInput(
                            voteId = call.parameters["voteId"]!!,
                            optionId = call.parameters["optionId"]!!,
                            stakeLtr = BigDecimal("1.00"),
                        ),
                    )
                    call.respondText("ok")
                }
                post(
                    "/t/abort/{voteId}",
                ) { call.respondText(GovernanceService(call = call).abortVote(call.parameters["voteId"]!!).status.name) }
                post(
                    "/t/close/{voteId}",
                ) { call.respondText(GovernanceService(call = call).closeVote(call.parameters["voteId"]!!).status.name) }
                get("/t/quorum/{meetingId}") {
                    val q = GovernanceService(call = call).checkQuorum(call.parameters["meetingId"]!!)
                    call.respondText(json.encodeToString(QuorumResultDto.serializer(), q))
                }
                get("/t/resolutions/{meetingId}") {
                    val list = GovernanceService(call = call).listResolutions(meetingId = call.parameters["meetingId"]!!)
                    call.respondText(json.encodeToString(ListSerializer(ResolutionDto.serializer()), list))
                }
            }
        }

        suspend fun HttpClient.asMember(
            member: Uuid,
            path: String,
            post: Boolean = false,
        ): HttpResponse =
            if (post) {
                post(path) { header("X-Member-Id", member.toString()) }
            } else {
                get(path) { header("X-Member-Id", member.toString()) }
            }

        fun voteOf(body: String): VoteDto = json.decodeFromString(VoteDto.serializer(), body)

        fun quorumOf(body: String): QuorumResultDto = json.decodeFromString(QuorumResultDto.serializer(), body)

        /** [count] ACTIVE members seated in a manual committee, each with LTR to stake. */
        fun seatedGroup(count: Int): Pair<Uuid, List<Uuid>> {
            val manual = fx.committee()
            val people = (1..count).map { fx.member(displayName = "Frost Mitglied").also { m -> rc.mintLtr(member = m) } }
            people.forEach { fx.seat(committee = manual, member = it) }
            return manual to people
        }

        /** Opens a vote on a fresh SCHEDULED motion of [committee] in [meeting] (as BOARD); returns the vote. */
        suspend fun HttpClient.open(
            committee: Uuid,
            meeting: Uuid,
        ): VoteDto {
            val motion = rc.motion(committee = committee, meeting = meeting, submitter = board)
            val response = asMember(board, "/t/open/$motion", post = true)
            response.status shouldBe HttpStatusCode.OK
            return voteOf(response.bodyAsText())
        }

        suspend fun HttpClient.cast(
            who: Uuid,
            vote: VoteDto,
        ): HttpStatusCode = asMember(who, "/t/cast/${vote.id}/${vote.options.first().id}", post = true).status

        // ── the three kinds of committee ───────────────────────────────────────────────────────────────────

        test("rule committee: the circle is frozen at the opening; a newcomer cannot vote, a leaver keeps the right, N does not move") {
            val (base, people) = seatedGroup(3)
            val ruleBased = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            val meeting = rc.meeting(committee = ruleBased)
            testApplication {
                application { installRoutes() }
                val before = quorumOf(client.asMember(board, "/t/quorum/$meeting").bodyAsText())
                before.eligibleMemberCount shouldBe 3
                before.frozen shouldBe false // nothing frozen yet: the live set

                val vote = client.open(ruleBased, meeting)
                rc.frozenElectorate(Uuid.parse(vote.id)) shouldContainExactlyInAnyOrder people
                (
                    transaction {
                        VoteTable
                            .selectAll()
                            .where {
                                VoteTable.id eq
                                    Uuid.parse(
                                        vote.id,
                                    )
                            }.single()[VoteTable.electorateFrozenAt]
                    } !=
                        null
                ) shouldBe
                    true

                // after the opening: a newcomer joins the rule's circle, an old member's seat ends
                val newcomer = fx.member(displayName = "Frost Neuling").also { rc.mintLtr(member = it) }
                fx.seat(committee = base, member = newcomer)
                transaction {
                    CommitteeMembershipTable.update({
                        (CommitteeMembershipTable.memberId eq people[0]) and (CommitteeMembershipTable.committeeId eq base)
                    }) { it[until] = rc.today }
                }
                val after = quorumOf(client.asMember(board, "/t/quorum/$meeting").bodyAsText())
                after.eligibleMemberCount shouldBe 3
                after.frozen shouldBe true

                client.cast(newcomer, vote) shouldBe HttpStatusCode.Forbidden
                client.cast(people[1], vote) shouldBe HttpStatusCode.OK
                client.cast(people[2], vote) shouldBe HttpStatusCode.OK
            }
        }

        test("a member who stops being ACTIVE after the opening stays in the denominator but can no longer vote (live membership guard)") {
            val (base, people) = seatedGroup(2)
            val ruleBased = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            val meeting = rc.meeting(committee = ruleBased)
            testApplication {
                application { installRoutes() }
                val vote = client.open(ruleBased, meeting)
                transaction { MemberTable.update({ MemberTable.id eq people[0] }) { it[status] = MemberStatus.WITHDRAWN } }
                quorumOf(client.asMember(board, "/t/quorum/$meeting").bodyAsText()).eligibleMemberCount shouldBe 2
                client.cast(people[0], vote) shouldBe HttpStatusCode.Forbidden
                client.cast(people[1], vote) shouldBe HttpStatusCode.OK
            }
        }

        test("a vote with an empty electorate is frozen as empty: the marker, not the rows, says 'frozen'; nobody can join it later") {
            val emptyBase = fx.committee()
            val ruleBased = rc.ruleCommittee(ruleJson = rc.seatedIn(emptyBase))
            val meeting = rc.meeting(committee = ruleBased)
            testApplication {
                application { installRoutes() }
                val vote = client.open(ruleBased, meeting)
                rc.frozenElectorate(Uuid.parse(vote.id)) shouldBe emptySet()
                val late = fx.member().also { rc.mintLtr(member = it) }
                fx.seat(committee = emptyBase, member = late)
                val q = quorumOf(client.asMember(board, "/t/quorum/$meeting").bodyAsText())
                q.eligibleMemberCount shouldBe 0
                q.frozen shouldBe true
                client.cast(late, vote) shouldBe HttpStatusCode.Forbidden
            }
        }

        test("manual committee: the same freeze applies") {
            val (manual, people) = seatedGroup(2)
            val meeting = rc.meeting(committee = manual)
            testApplication {
                application { installRoutes() }
                val vote = client.open(manual, meeting)
                rc.frozenElectorate(Uuid.parse(vote.id)) shouldContainExactlyInAnyOrder people
                val newcomer = fx.member().also { rc.mintLtr(member = it) }
                fx.seat(committee = manual, member = newcomer)
                client.cast(newcomer, vote) shouldBe HttpStatusCode.Forbidden
                client.cast(people[0], vote) shouldBe HttpStatusCode.OK
                quorumOf(client.asMember(board, "/t/quorum/$meeting").bodyAsText()).eligibleMemberCount shouldBe 2
            }
        }

        test("General Assembly: a member who becomes ACTIVE after the opening is not in the electorate, N stays") {
            val assembly = fx.committee(type = CommitteeType.GENERAL_ASSEMBLY)
            val early = fx.member().also { rc.mintLtr(member = it) }
            val meeting = rc.meeting(committee = assembly)
            testApplication {
                application { installRoutes() }
                val n0 = quorumOf(client.asMember(board, "/t/quorum/$meeting").bodyAsText()).eligibleMemberCount
                val vote = client.open(assembly, meeting)
                val late = fx.member().also { rc.mintLtr(member = it) }
                quorumOf(client.asMember(board, "/t/quorum/$meeting").bodyAsText()).eligibleMemberCount shouldBe n0
                client.cast(late, vote) shouldBe HttpStatusCode.Forbidden
                client.cast(early, vote) shouldBe HttpStatusCode.OK
                (early in rc.frozenElectorate(Uuid.parse(vote.id))) shouldBe true
                (late in rc.frozenElectorate(Uuid.parse(vote.id))) shouldBe false
            }
        }

        // ── older votes, the meeting's quorum, resolutions ─────────────────────────────────────────────────

        test(
            "a vote without the marker (opened before V1.9.112) keeps the live electorate; the first frozen vote then fixes the meeting's circle",
        ) {
            val (manual, people) = seatedGroup(2)
            val meeting = rc.meeting(committee = manual)
            val motion = rc.motion(committee = manual, meeting = meeting, submitter = board)
            val old = rc.voteRow(meeting = meeting, motion = motion, opener = board, status = VoteStatus.OPEN)
            val row = transaction { CommitteeTable.selectAll().where { CommitteeTable.id eq manual }.single() }
            val newcomer = fx.member()
            transaction {
                val live = frozenOrLiveElectorate(meetingId = meeting, committeeRow = row, scheduledDate = rc.today, voteId = old)
                live.frozen shouldBe false
                live.memberIds shouldContainExactlyInAnyOrder people
            }
            fx.seat(committee = manual, member = newcomer)
            transaction {
                val live = frozenOrLiveElectorate(meetingId = meeting, committeeRow = row, scheduledDate = rc.today, voteId = old)
                live.memberIds shouldContainExactlyInAnyOrder people + newcomer // still live
            }
            // a frozen vote of the same meeting (marker + rows) fixes the circle for the meeting
            val frozenVote =
                rc.voteRow(
                    meeting = meeting,
                    motion = rc.motion(committee = manual, meeting = meeting, submitter = board),
                    opener = board,
                    status = VoteStatus.ABORTED,
                )
            transaction {
                VoteTable.update({ VoteTable.id eq frozenVote }) { it[electorateFrozenAt] = DbClock.nowLocalDateTime() }
                people.forEach { member ->
                    VoteEligibleVoterTable.insert {
                        it[id] = Uuid.random()
                        it[voteId] = frozenVote
                        it[memberId] = member
                    }
                }
                val fixed = frozenOrLiveElectorate(meetingId = meeting, committeeRow = row, scheduledDate = rc.today)
                fixed.frozen shouldBe true
                fixed.memberIds shouldContainExactlyInAnyOrder people
                // the vote without the marker has no snapshot of its own: its quorum falls back to the meeting's rule (the first frozen vote)
                frozenOrLiveElectorate(meetingId = meeting, committeeRow = row, scheduledDate = rc.today, voteId = old).frozen shouldBe true
            }
        }

        test("the meeting's quorum follows its FIRST vote, even an aborted one; a later vote has its own circle") {
            val (base, people) = seatedGroup(2)
            val ruleBased = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            val meeting = rc.meeting(committee = ruleBased)
            testApplication {
                application { installRoutes() }
                val first = client.open(ruleBased, meeting)
                client.asMember(board, "/t/abort/${first.id}", post = true).bodyAsText() shouldBe VoteStatus.ABORTED.name
                val newcomer = fx.member().also { rc.mintLtr(member = it) }
                fx.seat(committee = base, member = newcomer)
                val second = client.open(ruleBased, meeting)
                rc.frozenElectorate(Uuid.parse(second.id)) shouldContainExactlyInAnyOrder people + newcomer
                // the meeting-level figure does not move with the second vote
                quorumOf(client.asMember(board, "/t/quorum/$meeting").bodyAsText()).eligibleMemberCount shouldBe 2
                client.cast(newcomer, second) shouldBe HttpStatusCode.OK
            }
        }

        test("a resolution written when a vote closes takes quorumMet from the frozen electorate, not from the live set") {
            val (base, people) = seatedGroup(3)
            val ruleBased = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            val meeting = rc.meeting(committee = ruleBased)
            // two of the three are present: 2 >= ceil(3 * 50%) = 2 -> met on the frozen N
            people.take(2).forEach { rc.attend(meeting = meeting, member = it) }
            testApplication {
                application { installRoutes() }
                val vote = client.open(ruleBased, meeting)
                // three more join the circle after the opening: live N = 6 would need 3 present
                repeat(3) { fx.seat(committee = base, member = fx.member()) }
                client.asMember(board, "/t/close/${vote.id}", post = true).status shouldBe HttpStatusCode.OK
                val resolutions =
                    json.decodeFromString(
                        ListSerializer(ResolutionDto.serializer()),
                        client.asMember(board, "/t/resolutions/$meeting").bodyAsText(),
                    )
                resolutions.single().quorumMet shouldBe true
            }
        }

        // ── the participation card of the conference room ─────────────────────────────────────────────────

        test("VoteOwnParticipation answers per vote from the frozen circle, and from the live set for a vote without the marker") {
            val (base, people) = seatedGroup(2)
            val ruleBased = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            val meeting = rc.meeting(committee = ruleBased)
            testApplication {
                application { installRoutes() }
                val frozen = client.open(ruleBased, meeting)
                val oldVote =
                    rc.voteRow(
                        meeting = meeting,
                        motion = rc.motion(committee = ruleBased, meeting = meeting, submitter = board),
                        opener = board,
                        status = VoteStatus.OPEN,
                    )
                val newcomer = fx.member()
                fx.seat(committee = base, member = newcomer)

                fun flags(member: Uuid) =
                    transaction {
                        val rows = VoteTable.selectAll().where { VoteTable.meetingId eq meeting }.toList()
                        VoteOwnParticipation.load(voteRows = rows, memberId = member, memberStatus = MemberStatus.ACTIVE)
                    }
                // in the frozen vote the newcomer is out, in the pre-marker vote (live) he is in; an early member is in both
                flags(newcomer).getValue(Uuid.parse(frozen.id)).eligible shouldBe false
                flags(newcomer).getValue(oldVote).eligible shouldBe true
                flags(people[0]).getValue(Uuid.parse(frozen.id)).eligible shouldBe true
                flags(people[0]).getValue(oldVote).eligible shouldBe true
            }
        }

        // ── elections and consensus count their own snapshot ───────────────────────────────────────────────

        test("an election's and a consensus's quorum come from their own snapshot; only without one does the meeting's rule apply") {
            val (manual, people) = seatedGroup(3)
            val meeting = rc.meeting(committee = manual)
            val row = transaction { CommitteeTable.selectAll().where { CommitteeTable.id eq manual }.single() }
            val election =
                rc.electionRow(
                    meeting = meeting,
                    motion = rc.motion(committee = manual, meeting = meeting, submitter = board),
                    opener = board,
                    target = null,
                    status = ElectionStatus.OPEN,
                )
            val consensus =
                rc.consensusRow(
                    meeting = meeting,
                    motion = rc.motion(committee = manual, meeting = meeting, submitter = board),
                    opener = board,
                    status = SystemicConsensusStatus.RATING,
                )
            transaction {
                // not yet opened for voting: no snapshot, the live set counts
                frozenOrLiveElectorate(
                    meetingId = meeting,
                    committeeRow = row,
                    scheduledDate = rc.today,
                    electionId = election,
                ).frozen shouldBe
                    false
                // opened for voting with a snapshot of two
                ElectionTable.update({ ElectionTable.id eq election }) {
                    it[votingOpenedAt] = DbClock.nowLocalDateTime()
                }
                people.take(2).forEach { member ->
                    ElectionEligibleVoterTable.insert {
                        it[id] = Uuid.random()
                        it[electionId] = election
                        it[memberId] = member
                    }
                }
                val e = frozenOrLiveElectorate(meetingId = meeting, committeeRow = row, scheduledDate = rc.today, electionId = election)
                e.frozen shouldBe true
                e.memberIds shouldContainExactlyInAnyOrder people.take(2)

                // consensus: round 1 snapshot of two, round 2 snapshot of three -> the latest round counts
                listOf(1 to people.take(2), 2 to people).forEach { (round, members) ->
                    members.forEach { member ->
                        SystemicConsensusEligibleVoterTable.insert {
                            it[id] = Uuid.random()
                            it[systemicConsensusId] = consensus
                            it[SystemicConsensusEligibleVoterTable.round] = round
                            it[memberId] = member
                        }
                    }
                }
                val c = frozenOrLiveElectorate(meetingId = meeting, committeeRow = row, scheduledDate = rc.today, consensusId = consensus)
                c.frozen shouldBe true
                c.memberIds shouldContainExactlyInAnyOrder people
            }
        }
    })
