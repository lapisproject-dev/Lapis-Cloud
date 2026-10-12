package network.lapis.cloud.server.rpc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MailingListTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rules.RuleReferences
import network.lapis.cloud.server.security.canManageCommittee
import network.lapis.cloud.server.time.OrganizationTimeZone
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.CommitteeDto
import network.lapis.cloud.shared.domain.CommitteeInput
import network.lapis.cloud.shared.domain.CommitteeMembershipDto
import network.lapis.cloud.shared.domain.CommitteeMembershipInput
import network.lapis.cloud.shared.domain.CommitteeMembershipMode
import network.lapis.cloud.shared.domain.CommitteeModeChangeBlock
import network.lapis.cloud.shared.domain.CommitteeModeChangeImpactDto
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeRulePreviewDto
import network.lapis.cloud.shared.domain.CommitteeSeatAutoEndReason
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.ElectionStatus
import network.lapis.cloud.shared.domain.IncumbentPolicy
import network.lapis.cloud.shared.domain.MailingListKind
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import network.lapis.cloud.shared.domain.rules.MemberRuleCodec
import network.lapis.cloud.shared.domain.rules.RuleContext
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.SearchRateLimitedException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.9.112 -- [GovernanceService]'s rule-mode surface end to end: who may switch / preview / look, what a switch does to the seats, the
 * seven reasons a switch is refused, strict rule validation (no reference to itself, a rule committee or the General Assembly), that
 * `updateCommittee` never changes the mode, that a rule committee takes no manual seat and no election, the visibility of the rule, the rate
 * limits (checked AFTER the permission) and that the COMMITTEE audit entry carries counts and ids of committees only -- never a person.
 */
class CommitteeRuleModeServiceTest :
    FunSpec({
        val fx = CommitteeSeatFixture()
        val rc = RuleCommitteeFixture(fx)
        val elections = ElectionSuccessionFixture(fx)
        val mailingListIds = mutableListOf<Uuid>()
        val json = Json { ignoreUnknownKeys = false }
        lateinit var board: Uuid
        lateinit var plain: Uuid
        lateinit var chair: Uuid
        val previewLimiter = FederationInboxRateLimiter(maxRequests = 1_000, window = 1.minutes)
        val countLimiter = FederationInboxRateLimiter(maxRequests = 1_000, window = 1.minutes)

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
            board = fx.member(role = AccountRole.BOARD, displayName = "Regelgremium Vorstandsperson")
            plain = fx.member(role = AccountRole.MEMBER, displayName = "Regelgremium Normalperson")
            chair = fx.member(role = AccountRole.MEMBER, displayName = "Regelgremium Vorsitzperson")
        }
        afterSpec {
            transaction { MailingListTable.deleteWhere { MailingListTable.id inList mailingListIds } }
            elections.cleanUp()
            rc.cleanUp()
            fx.cleanUp()
        }

        fun Application.installErrors() {
            install(StatusPages) {
                exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
                exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
                exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                exception<SearchRateLimitedException> { call, cause ->
                    call.respondText(cause.message, status = HttpStatusCode.TooManyRequests)
                }
            }
        }

        fun service(
            call: ApplicationCall,
            preview: FederationInboxRateLimiter = previewLimiter,
            count: FederationInboxRateLimiter = countLimiter,
        ) = GovernanceService(call = call, rulePreviewRateLimiter = preview, eligibleCountRateLimiter = count)

        fun Application.installRoutes(
            preview: FederationInboxRateLimiter = previewLimiter,
            count: FederationInboxRateLimiter = countLimiter,
        ) {
            installErrors()
            routing {
                post("/t/set/{id}/{mode}") {
                    val body = call.receiveText().ifBlank { null }
                    val dto =
                        service(call, preview, count).setCommitteeMembershipRule(
                            committeeId = call.parameters["id"]!!,
                            mode = CommitteeMembershipMode.valueOf(call.parameters["mode"]!!),
                            ruleJson = body,
                        )
                    call.respondText(json.encodeToString(CommitteeDto.serializer(), dto))
                }
                get("/t/impact/{id}/{mode}") {
                    val dto =
                        service(call, preview, count).getCommitteeModeChangeImpact(
                            committeeId = call.parameters["id"]!!,
                            targetMode = CommitteeMembershipMode.valueOf(call.parameters["mode"]!!),
                        )
                    call.respondText(json.encodeToString(CommitteeModeChangeImpactDto.serializer(), dto))
                }
                post("/t/preview") {
                    val dto =
                        service(call, preview, count).previewCommitteeRule(
                            committeeId = call.request.queryParameters["committeeId"],
                            ruleJson = call.receiveText(),
                        )
                    call.respondText(json.encodeToString(CommitteeRulePreviewDto.serializer(), dto))
                }
                get(
                    "/t/count/{id}",
                ) { call.respondText(service(call, preview, count).countCommitteeEligible(call.parameters["id"]!!).toString()) }
                get("/t/members/{id}") {
                    val list = service(call, preview, count).listCommitteeMembers(committeeId = call.parameters["id"]!!, activeOnly = false)
                    call.respondText(json.encodeToString(ListSerializer(CommitteeMembershipDto.serializer()), list))
                }
                get("/t/committees") {
                    val list = service(call, preview, count).listCommittees(activeOnly = false)
                    call.respondText(json.encodeToString(ListSerializer(CommitteeDto.serializer()), list))
                }
                post("/t/create") {
                    val dto =
                        service(
                            call,
                            preview,
                            count,
                        ).createCommittee(json.decodeFromString(CommitteeInput.serializer(), call.receiveText()))
                    call.respondText(json.encodeToString(CommitteeDto.serializer(), dto))
                }
                post("/t/update/{id}") {
                    val dto =
                        service(call, preview, count).updateCommittee(
                            id = call.parameters["id"]!!,
                            input = json.decodeFromString(CommitteeInput.serializer(), call.receiveText()),
                        )
                    call.respondText(json.encodeToString(CommitteeDto.serializer(), dto))
                }
                post("/t/add-member/{id}/{memberId}") {
                    val dto =
                        service(call, preview, count).addCommitteeMember(
                            committeeId = call.parameters["id"]!!,
                            input =
                                CommitteeMembershipInput(
                                    memberId = call.parameters["memberId"]!!,
                                    role = CommitteeRole.MEMBER,
                                    since = LocalDate(2026, 1, 1),
                                ),
                        )
                    call.respondText(json.encodeToString(CommitteeMembershipDto.serializer(), dto))
                }
            }
        }

        suspend fun HttpClient.asMember(
            member: Uuid?,
            path: String,
            body: String? = null,
        ): HttpResponse {
            val builder: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
                if (member != null) header("X-Member-Id", member.toString())
                if (body != null) setBody(body)
            }
            return if (body != null) post(path, builder) else get(path, builder)
        }

        fun committeeOf(response: String): CommitteeDto = json.decodeFromString(CommitteeDto.serializer(), response)

        fun impactOf(response: String): CommitteeModeChangeImpactDto =
            json.decodeFromString(CommitteeModeChangeImpactDto.serializer(), response)

        /** A manual committee with [count] seated members -- the isolating base of a test rule. */
        fun seatedGroup(count: Int): Pair<Uuid, List<Uuid>> {
            val manual = fx.committee()
            val people = (1..count).map { fx.member(displayName = "Regelgremium Mitglied") }
            people.forEach { fx.seat(committee = manual, member = it) }
            return manual to people
        }

        // ── permissions ────────────────────────────────────────────────────────────────────────────────────

        test("only BOARD/ADMIN may switch, look at the impact and preview; a committee chair and a plain member get 403, nobody gets 401") {
            val (base, _) = seatedGroup(1)
            val target = fx.committee()
            fx.seat(committee = target, member = chair, role = CommitteeRole.CHAIR)
            testApplication {
                application { installRoutes() }
                val rule = rc.seatedIn(base)
                listOf(plain, chair).forEach { who ->
                    client.asMember(who, "/t/set/$target/RULE", rule).status shouldBe HttpStatusCode.Forbidden
                    client.asMember(who, "/t/impact/$target/RULE").status shouldBe HttpStatusCode.Forbidden
                    client.asMember(who, "/t/preview", rule).status shouldBe HttpStatusCode.Forbidden
                }
                client.asMember(null, "/t/set/$target/RULE", rule).status shouldBe HttpStatusCode.Unauthorized
                // nothing happened
                rc.committeeRow(target)[CommitteeTable.membershipMode] shouldBe CommitteeMembershipMode.MANUAL
            }
        }

        // ── the switch ─────────────────────────────────────────────────────────────────────────────────────

        test(
            "MANUAL to RULE ends every open seat with RULE_MODE, writes ONE counts-only COMMITTEE audit, and the committee has no roster any more",
        ) {
            val (base, people) = seatedGroup(2)
            val committee = fx.committee()
            val a = fx.member(displayName = "Regelgremium Sitz Eins")
            val b = fx.member(displayName = "Regelgremium Sitz Zwei")
            val gone = fx.member(displayName = "Regelgremium Sitz Alt")
            val open = fx.seat(committee = committee, member = a)
            val scheduledEnd = fx.seat(committee = committee, member = b, until = rc.today.plus(30))
            val alreadyEnded = fx.seat(committee = committee, member = gone, until = rc.today.minus(5, DateTimeUnit.DAY))
            val mark = fx.auditMark()
            testApplication {
                application { installRoutes() }
                val impact = impactOf(client.asMember(board, "/t/impact/$committee/RULE").bodyAsText())
                impact shouldBe CommitteeModeChangeImpactDto(openSeats = 2, blocked = null)

                val response = client.asMember(board, "/t/set/$committee/RULE", rc.seatedIn(base))
                response.status shouldBe HttpStatusCode.OK
                val dto = committeeOf(response.bodyAsText())
                dto.membershipMode shouldBe CommitteeMembershipMode.RULE
                dto.ruleJson shouldBe rc.seatedIn(base)

                fx.seatRow(open)[CommitteeMembershipTable.until] shouldBe rc.today.minus(1, DateTimeUnit.DAY)
                fx.seatRow(open)[CommitteeMembershipTable.autoEndReason] shouldBe CommitteeSeatAutoEndReason.RULE_MODE
                fx.seatRow(scheduledEnd)[CommitteeMembershipTable.until] shouldBe rc.today.minus(1, DateTimeUnit.DAY)
                fx.seatRow(scheduledEnd)[CommitteeMembershipTable.autoEndReason] shouldBe CommitteeSeatAutoEndReason.RULE_MODE
                // a seat that had already ended is untouched
                fx.seatRow(alreadyEnded)[CommitteeMembershipTable.autoEndReason] shouldBe null

                // no roster: nobody is listed, the number comes from the rule
                json
                    .decodeFromString(
                        ListSerializer(CommitteeMembershipDto.serializer()),
                        client.asMember(plain, "/t/members/$committee").bodyAsText(),
                    ).shouldHaveSize(0)
                client.asMember(plain, "/t/count/$committee").bodyAsText().toInt() shouldBe people.size

                val audits = fx.auditSince(mark = mark, type = AuditEntityType.COMMITTEE)
                audits shouldHaveSize 1
                val after = audits.single()[network.lapis.cloud.server.db.generated.AuditLogEntryTable.afterSnapshot]!!
                after shouldContain "\"event\":\"COMMITTEE_MODE_CHANGED\""
                after shouldContain "\"endedSeats\":2"
                after shouldContain "\"eligibleCount\":${people.size}"
                // counts and committee ids only: no name, no member id, no address
                (people + listOf(a, b, gone, board, plain)).forEach { after shouldNotContain it.toString() }
                listOf("Regelgremium", "@example.org").forEach { after shouldNotContain it }
                fx.chainProblemSince(mark) shouldBe null
            }
        }

        test("switch to RULE ends seats yesterday: a future-dated CHAIR seat and a current one grant no leadership on the switch day") {
            val (base, _) = seatedGroup(1)
            val committee = fx.committee()
            val futureChair = fx.member(displayName = "Regelgremium Kuenftige Leitung")
            val currentChair = fx.member(displayName = "Regelgremium Aktuelle Leitung")
            val future = fx.seat(committee = committee, member = futureChair, role = CommitteeRole.CHAIR, since = rc.today.plus(10))
            val current = fx.seat(committee = committee, member = currentChair, role = CommitteeRole.CHAIR)
            val todayChair = fx.member(displayName = "Regelgremium Heute Endende Leitung")
            val endsToday = fx.seat(committee = committee, member = todayChair, role = CommitteeRole.CHAIR, until = rc.today)

            fun manager(id: kotlin.uuid.Uuid) =
                network.lapis.cloud.server.security.CurrentMember(
                    memberId = id,
                    role = AccountRole.MEMBER,
                    status = network.lapis.cloud.shared.domain.MemberStatus.ACTIVE,
                )
            testApplication {
                application { installRoutes() }
                client.asMember(board, "/t/set/$committee/RULE", rc.seatedIn(base)).status shouldBe HttpStatusCode.OK
            }
            val yesterday = rc.today.minus(1, DateTimeUnit.DAY)
            fx.seatRow(future)[CommitteeMembershipTable.until] shouldBe yesterday
            fx.seatRow(current)[CommitteeMembershipTable.until] shouldBe yesterday
            fx.seatRow(endsToday)[CommitteeMembershipTable.until] shouldBe yesterday
            manager(futureChair).canManageCommittee(committee) shouldBe false
            manager(currentChair).canManageCommittee(committee) shouldBe false
            manager(todayChair).canManageCommittee(committee) shouldBe false
        }

        test("RULE to MANUAL leaves an empty committee; the same rule again is a no-op; a changed rule is audited once") {
            val (base, _) = seatedGroup(2)
            val (otherBase, _) = seatedGroup(1)
            val committee = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            val mark = fx.auditMark()
            testApplication {
                application { installRoutes() }
                // identical rule: nothing written
                client.asMember(board, "/t/set/$committee/RULE", rc.seatedIn(base)).status shouldBe HttpStatusCode.OK
                fx.auditSince(mark = mark, type = AuditEntityType.COMMITTEE) shouldHaveSize 0
                // different rule: one audit, event RULE_SET (the mode did not change)
                val changed = committeeOf(client.asMember(board, "/t/set/$committee/RULE", rc.seatedIn(otherBase)).bodyAsText())
                changed.ruleJson shouldBe rc.seatedIn(otherBase)
                val audits = fx.auditSince(mark = mark, type = AuditEntityType.COMMITTEE)
                audits shouldHaveSize 1
                audits.single()[network.lapis.cloud.server.db.generated.AuditLogEntryTable.afterSnapshot]!! shouldContain
                    "COMMITTEE_RULE_SET"
                // back to manual: no rule, no seats were created
                val manual = committeeOf(client.asMember(board, "/t/set/$committee/MANUAL", "").bodyAsText())
                manual.membershipMode shouldBe CommitteeMembershipMode.MANUAL
                manual.ruleJson shouldBe null
                transaction {
                    CommitteeMembershipTable.selectAll().where { CommitteeMembershipTable.committeeId eq committee }.count()
                } shouldBe
                    0
                // MANUAL with a rule text, RULE without one, and a garbage rule are all 400
                client.asMember(board, "/t/set/$committee/MANUAL", rc.seatedIn(base)).status shouldBe HttpStatusCode.BadRequest
                client.asMember(board, "/t/set/$committee/RULE", "").status shouldBe HttpStatusCode.BadRequest
                client
                    .asMember(
                        board,
                        "/t/set/$committee/RULE",
                        """{"v":1,"op":"ALL","conditions":[{"type":"STATUS","values":["ACTIVE"]}]}""",
                    ).status shouldBe
                    HttpStatusCode.BadRequest
                client.asMember(board, "/t/set/${Uuid.random()}/RULE", rc.seatedIn(base)).status shouldBe HttpStatusCode.NotFound
            }
        }

        // ── the refusals ───────────────────────────────────────────────────────────────────────────────────

        test("a meeting that has begun (PLANNED and due) blocks; a future meeting and a held one do not") {
            val (base, _) = seatedGroup(1)
            val wallNow = OrganizationTimeZone.wallNow()
            testApplication {
                application { installRoutes() }
                val committee = fx.committee()
                rc.meeting(committee = committee, status = MeetingStatus.PLANNED, scheduledAt = LocalDateTime(2999, 1, 1, 18, 0))
                rc.meeting(committee = committee, status = MeetingStatus.HELD, scheduledAt = LocalDateTime(2026, 1, 1, 18, 0))
                rc.meeting(committee = committee, status = MeetingStatus.CANCELLED, scheduledAt = LocalDateTime(2026, 1, 2, 18, 0))
                impactOf(client.asMember(board, "/t/impact/$committee/RULE").bodyAsText()).blocked shouldBe null

                rc.meeting(committee = committee, status = MeetingStatus.PLANNED, scheduledAt = LocalDateTime(wallNow.year, 1, 1, 0, 0))
                impactOf(client.asMember(board, "/t/impact/$committee/RULE").bodyAsText()).blocked shouldBe
                    CommitteeModeChangeBlock.MEETING_IN_PROGRESS
                val refused = client.asMember(board, "/t/set/$committee/RULE", rc.seatedIn(base))
                refused.status shouldBe HttpStatusCode.Conflict
                refused.bodyAsText() shouldBe CommitteeModeChangeBlock.MEETING_IN_PROGRESS.name
                rc.committeeRow(committee)[CommitteeTable.membershipMode] shouldBe CommitteeMembershipMode.MANUAL
            }
        }

        test("an open vote, an unfinished election and an unfinished consensus of the committee block; finished ones do not") {
            val (base, _) = seatedGroup(1)
            testApplication {
                application { installRoutes() }

                suspend fun blockedOf(committee: Uuid) = impactOf(client.asMember(board, "/t/impact/$committee/RULE").bodyAsText()).blocked

                val withVote = fx.committee()
                val m1 = rc.meeting(committee = withVote)
                val a1 = rc.motion(committee = withVote, meeting = m1, submitter = board)
                rc.voteRow(meeting = m1, motion = a1, opener = board, status = network.lapis.cloud.shared.domain.VoteStatus.CLOSED)
                blockedOf(withVote) shouldBe null
                rc.voteRow(
                    meeting = m1,
                    motion = rc.motion(committee = withVote, meeting = m1, submitter = board),
                    opener = board,
                    status = network.lapis.cloud.shared.domain.VoteStatus.OPEN,
                )
                blockedOf(withVote) shouldBe CommitteeModeChangeBlock.OPEN_DECISION

                val withElection = fx.committee()
                val m2 = rc.meeting(committee = withElection)
                rc.electionRow(
                    meeting = m2,
                    motion = rc.motion(committee = withElection, meeting = m2, submitter = board),
                    opener = board,
                    target = null,
                    status = ElectionStatus.TALLIED,
                )
                rc.electionRow(
                    meeting = m2,
                    motion = rc.motion(committee = withElection, meeting = m2, submitter = board),
                    opener = board,
                    target = null,
                    status = ElectionStatus.ABORTED,
                )
                blockedOf(withElection) shouldBe null
                rc.electionRow(
                    meeting = m2,
                    motion = rc.motion(committee = withElection, meeting = m2, submitter = board),
                    opener = board,
                    target = null,
                    status = ElectionStatus.PREPARATION,
                )
                blockedOf(withElection) shouldBe CommitteeModeChangeBlock.OPEN_DECISION

                val withConsensus = fx.committee()
                val m3 = rc.meeting(committee = withConsensus)
                rc.consensusRow(
                    meeting = m3,
                    motion = rc.motion(committee = withConsensus, meeting = m3, submitter = board),
                    opener = board,
                    status = SystemicConsensusStatus.EVALUATED,
                )
                rc.consensusRow(
                    meeting = m3,
                    motion = rc.motion(committee = withConsensus, meeting = m3, submitter = board),
                    opener = board,
                    status = SystemicConsensusStatus.ABORTED,
                )
                blockedOf(withConsensus) shouldBe null
                rc.consensusRow(
                    meeting = m3,
                    motion = rc.motion(committee = withConsensus, meeting = m3, submitter = board),
                    opener = board,
                    status = SystemicConsensusStatus.RATING,
                )
                blockedOf(withConsensus) shouldBe CommitteeModeChangeBlock.OPEN_DECISION
                client.asMember(board, "/t/set/$withConsensus/RULE", rc.seatedIn(base)).status shouldBe HttpStatusCode.Conflict
            }
        }

        test("an unfinished election that targets the committee blocks; a tallied one does not") {
            val (base, _) = seatedGroup(1)
            val target = fx.committee()
            val host = fx.committee()
            val meeting = rc.meeting(committee = host)
            testApplication {
                application { installRoutes() }
                rc.electionRow(
                    meeting = meeting,
                    motion = rc.motion(committee = host, meeting = meeting, submitter = board),
                    opener = board,
                    target = target,
                    status = ElectionStatus.TALLIED,
                )
                impactOf(client.asMember(board, "/t/impact/$target/RULE").bodyAsText()).blocked shouldBe null
                rc.electionRow(
                    meeting = meeting,
                    motion = rc.motion(committee = host, meeting = meeting, submitter = board),
                    opener = board,
                    target = target,
                    status = ElectionStatus.OPEN,
                )
                impactOf(client.asMember(board, "/t/impact/$target/RULE").bodyAsText()).blocked shouldBe
                    CommitteeModeChangeBlock.ELECTION_TARGETS_COMMITTEE
                client.asMember(board, "/t/set/$target/RULE", rc.seatedIn(base)).bodyAsText() shouldBe
                    CommitteeModeChangeBlock.ELECTION_TARGETS_COMMITTEE.name
            }
        }

        test("a rule committee and a dynamic mailing list that reference the committee block the switch into rule mode") {
            val (base, _) = seatedGroup(1)
            val referenced = fx.committee()
            val viaMailing = fx.committee()
            rc.ruleCommittee(ruleJson = rc.seatedIn(referenced))
            val listId = Uuid.random()
            transaction {
                MailingListTable.insert {
                    it[id] = listId
                    it[name] = "Regel-Blockade ${Uuid.random()}"
                    it[createdBy] = board
                    it[kind] = MailingListKind.DYNAMIC
                    it[ruleJson] =
                        MemberRuleCodec.encode(MemberRuleCodec.parse(text = rc.seatedIn(viaMailing), context = RuleContext.MAILING))
                    it[mandatory] = false
                }
            }
            mailingListIds += listId
            testApplication {
                application { installRoutes() }
                listOf(referenced, viaMailing).forEach { committee ->
                    impactOf(client.asMember(board, "/t/impact/$committee/RULE").bodyAsText()).blocked shouldBe
                        CommitteeModeChangeBlock.REFERENCED_BY_RULE
                    client.asMember(board, "/t/set/$committee/RULE", rc.seatedIn(base)).status shouldBe HttpStatusCode.Conflict
                }
                // switching a committee that is NOT referenced is fine
                client.asMember(board, "/t/set/${fx.committee()}/RULE", rc.seatedIn(base)).status shouldBe HttpStatusCode.OK
            }
        }

        test("the General Assembly and the executive board can never be switched") {
            val (base, _) = seatedGroup(1)
            testApplication {
                application { installRoutes() }
                listOf(CommitteeType.GENERAL_ASSEMBLY, CommitteeType.EXECUTIVE_BOARD).forEach { type ->
                    val committee = fx.committee(type = type)
                    impactOf(client.asMember(board, "/t/impact/$committee/RULE").bodyAsText()).blocked shouldBe
                        CommitteeModeChangeBlock.TYPE_NOT_ALLOWED
                    client.asMember(board, "/t/set/$committee/RULE", rc.seatedIn(base)).bodyAsText() shouldBe
                        CommitteeModeChangeBlock.TYPE_NOT_ALLOWED.name
                }
            }
        }

        // ── rule validation ────────────────────────────────────────────────────────────────────────────────

        test(
            "a rule may not reference the committee itself, a rule committee or the General Assembly; unknown and inactive committees fail too",
        ) {
            val (base, _) = seatedGroup(1)
            val self = fx.committee()
            val ruleBased = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            val assembly = fx.committee(type = CommitteeType.GENERAL_ASSEMBLY)
            val inactive = fx.committee(active = false)
            testApplication {
                application { installRoutes() }

                suspend fun attempt(ruleJson: String) = client.asMember(board, "/t/set/$self/RULE", ruleJson)

                listOf(self, ruleBased, assembly).forEach { bad ->
                    val response = attempt(rc.seatedIn(bad))
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.bodyAsText() shouldBe "COMMITTEE_REFERENCE_NOT_MANUAL"
                    client.asMember(board, "/t/preview?committeeId=$self", rc.seatedIn(bad)).status shouldBe HttpStatusCode.BadRequest
                }
                attempt(rc.seatedIn(Uuid.random())).status shouldBe HttpStatusCode.BadRequest
                attempt(rc.seatedIn(inactive)).status shouldBe HttpStatusCode.BadRequest
                rc.committeeRow(self)[CommitteeTable.membershipMode] shouldBe CommitteeMembershipMode.MANUAL
            }
        }

        test(
            "the shared validator also refuses a rule committee and the General Assembly for a MAILING rule, but accepts a manual committee",
        ) {
            val (base, _) = seatedGroup(1)
            val ruleBased = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            val assembly = fx.committee(type = CommitteeType.GENERAL_ASSEMBLY)

            fun check(committee: Uuid) =
                transaction {
                    RuleReferences.lockAndValidate(
                        rule = MemberRuleCodec.parse(text = rc.seatedIn(committee), context = RuleContext.MAILING),
                        context = RuleContext.MAILING,
                        selfCommitteeId = null,
                    )
                }
            shouldThrow<BadRequestException> { check(ruleBased) }.message shouldBe "COMMITTEE_REFERENCE_NOT_MANUAL"
            shouldThrow<BadRequestException> { check(assembly) }.message shouldBe "COMMITTEE_REFERENCE_NOT_MANUAL"
            check(base)
        }

        // ── create / update / read ─────────────────────────────────────────────────────────────────────────

        test("createCommittee: a rule committee is created with its canonical rule and audited; invalid combinations are 400") {
            val (base, people) = seatedGroup(2)
            val mark = fx.auditMark()
            testApplication {
                application { installRoutes() }

                suspend fun create(input: CommitteeInput) =
                    client.asMember(board, "/t/create", json.encodeToString(CommitteeInput.serializer(), input))

                val ok =
                    create(
                        CommitteeInput(
                            name = "Regelgremium neu",
                            type = CommitteeType.COMMISSION,
                            description = "d",
                            membershipMode = CommitteeMembershipMode.RULE,
                            ruleJson = rc.seatedIn(base),
                        ),
                    )
                ok.status shouldBe HttpStatusCode.OK
                val dto = committeeOf(ok.bodyAsText())
                fx.committeeIds += Uuid.parse(dto.id)
                dto.membershipMode shouldBe CommitteeMembershipMode.RULE
                dto.ruleJson shouldBe rc.seatedIn(base)
                client.asMember(plain, "/t/count/${dto.id}").bodyAsText().toInt() shouldBe people.size
                val audit = fx.auditSince(mark = mark, type = AuditEntityType.COMMITTEE).single()
                audit[network.lapis.cloud.server.db.generated.AuditLogEntryTable.afterSnapshot]!! shouldContain
                    "\"eligibleCount\":${people.size}"

                val base0 = CommitteeInput(name = "x", type = CommitteeType.WORKING_GROUP, description = "d")
                create(base0.copy(ruleJson = rc.seatedIn(base))).status shouldBe HttpStatusCode.BadRequest // manual with a rule
                // rule mode without a rule
                create(base0.copy(membershipMode = CommitteeMembershipMode.RULE)).status shouldBe HttpStatusCode.BadRequest
                create(
                    base0.copy(
                        type = CommitteeType.GENERAL_ASSEMBLY,
                        membershipMode = CommitteeMembershipMode.RULE,
                        ruleJson = rc.seatedIn(base),
                    ),
                ).status shouldBe HttpStatusCode.BadRequest
                create(
                    base0.copy(
                        type = CommitteeType.EXECUTIVE_BOARD,
                        membershipMode = CommitteeMembershipMode.RULE,
                        ruleJson = rc.seatedIn(base),
                    ),
                ).status shouldBe HttpStatusCode.BadRequest
                create(base0.copy(membershipMode = CommitteeMembershipMode.RULE, ruleJson = "{}")).status shouldBe HttpStatusCode.BadRequest
                client.asMember(plain, "/t/create", json.encodeToString(CommitteeInput.serializer(), base0)).status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test("updateCommittee never changes the mode or the rule, and a rule committee cannot become the assembly or the board") {
            val (base, _) = seatedGroup(1)
            val committee = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            val manual = fx.committee()
            testApplication {
                application { installRoutes() }

                suspend fun update(
                    id: Uuid,
                    input: CommitteeInput,
                ) = client.asMember(board, "/t/update/$id", json.encodeToString(CommitteeInput.serializer(), input))

                val renamed =
                    update(
                        committee,
                        CommitteeInput(
                            name = "Regelgremium umbenannt",
                            type = CommitteeType.COMMISSION,
                            description = "d2",
                            membershipMode = CommitteeMembershipMode.MANUAL,
                            ruleJson = null,
                        ),
                    )
                renamed.status shouldBe HttpStatusCode.OK
                val dto = committeeOf(renamed.bodyAsText())
                dto.name shouldBe "Regelgremium umbenannt"
                dto.membershipMode shouldBe CommitteeMembershipMode.RULE
                dto.ruleJson shouldBe rc.seatedIn(base)
                // the other way round: a manual committee stays manual, whatever the input claims
                val stays =
                    committeeOf(
                        update(
                            manual,
                            CommitteeInput(
                                name = "Handgremium",
                                type = CommitteeType.WORKING_GROUP,
                                description = "d",
                                membershipMode = CommitteeMembershipMode.RULE,
                                ruleJson = rc.seatedIn(base),
                            ),
                        ).bodyAsText(),
                    )
                stays.membershipMode shouldBe CommitteeMembershipMode.MANUAL
                stays.ruleJson shouldBe null
                listOf(CommitteeType.GENERAL_ASSEMBLY, CommitteeType.EXECUTIVE_BOARD).forEach { type ->
                    update(committee, CommitteeInput(name = "x", type = type, description = "d")).status shouldBe HttpStatusCode.Conflict
                }
            }
        }

        test("the rule text only reaches BOARD/ADMIN; a plain member sees the mode but never the rule") {
            val (base, _) = seatedGroup(1)
            val committee = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            testApplication {
                application { installRoutes() }

                fun find(body: String) =
                    json.decodeFromString(ListSerializer(CommitteeDto.serializer()), body).single {
                        it.id ==
                            committee.toString()
                    }
                val asBoard = find(client.asMember(board, "/t/committees").bodyAsText())
                asBoard.ruleJson shouldBe rc.seatedIn(base)
                val asPlain = find(client.asMember(plain, "/t/committees").bodyAsText())
                asPlain.membershipMode shouldBe CommitteeMembershipMode.RULE
                asPlain.ruleJson shouldBe null
            }
        }

        // ── seats and elections ────────────────────────────────────────────────────────────────────────────

        test("a rule committee takes no manual seat; the manual committee next to it still does") {
            val (base, _) = seatedGroup(1)
            val ruleBased = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            val manual = fx.committee()
            val newcomer = fx.member()
            testApplication {
                application { installRoutes() }
                client.asMember(board, "/t/add-member/$ruleBased/$newcomer", "").status shouldBe HttpStatusCode.Conflict
                transaction {
                    CommitteeMembershipTable.selectAll().where { CommitteeMembershipTable.committeeId eq ruleBased }.count()
                } shouldBe
                    0
                client.asMember(board, "/t/add-member/$manual/$newcomer", "").status shouldBe HttpStatusCode.OK
            }
        }

        test("an election cannot seat anybody into a rule committee: the succession and the tally fail closed") {
            val (base, people) = seatedGroup(2)
            val ruleBased = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            shouldThrow<ConflictException> { transaction { requireNotRuleCommittee(ruleBased) } }
            shouldThrow<ConflictException> {
                transaction {
                    ElectionSeatSuccession.apply(
                        electionId = Uuid.random(),
                        committeeId = ruleBased,
                        committeeType = CommitteeType.WORKING_GROUP,
                        role = CommitteeRole.MEMBER,
                        policy = IncumbentPolicy.KEEP,
                        winnerMemberIds = listOf(people.first()),
                        stichtag = rc.today,
                        now =
                            network.lapis.cloud.server.db.DbClock
                                .nowLocalDateTime(),
                        actorMemberId = null,
                        actorRole = null,
                    )
                }
            }
            // a CLOSED election that (by a data race) targets the rule committee: the tally refuses instead of writing seat rows
            val seeded =
                elections.seed(
                    committee = ruleBased,
                    role = CommitteeRole.MEMBER,
                    policy = IncumbentPolicy.KEEP,
                    candidates = listOf(people[0] to 2, people[1] to 1),
                )
            shouldThrow<ConflictException> { elections.tally(seeded) }
            transaction {
                CommitteeMembershipTable.selectAll().where { CommitteeMembershipTable.committeeId eq ruleBased }.count()
            } shouldBe
                0
        }

        // ── counting and rate limits ───────────────────────────────────────────────────────────────────────

        test("countCommitteeEligible: the General Assembly and a rule committee answer, a manual committee is 400, an unknown one 404") {
            val (base, people) = seatedGroup(3)
            val ruleBased = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            val assembly = fx.committee(type = CommitteeType.GENERAL_ASSEMBLY)
            testApplication {
                application { installRoutes() }
                client.asMember(plain, "/t/count/$ruleBased").bodyAsText().toInt() shouldBe people.size
                (client.asMember(plain, "/t/count/$assembly").bodyAsText().toInt() >= people.size) shouldBe true
                client.asMember(plain, "/t/count/$base").status shouldBe HttpStatusCode.BadRequest
                client.asMember(plain, "/t/count/${Uuid.random()}").status shouldBe HttpStatusCode.NotFound
                client.asMember(null, "/t/count/$ruleBased").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        test("previewCommitteeRule returns a number only; both limiters answer 429 only AFTER the permission check") {
            val (base, people) = seatedGroup(2)
            val ruleBased = rc.ruleCommittee(ruleJson = rc.seatedIn(base))
            val tightPreview = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes)
            val tightCount = FederationInboxRateLimiter(maxRequests = 2, window = 1.minutes)
            testApplication {
                application { installRoutes(preview = tightPreview, count = tightCount) }
                val first = client.asMember(board, "/t/preview", rc.seatedIn(base))
                first.status shouldBe HttpStatusCode.OK
                first.bodyAsText() shouldBe """{"eligible":${people.size}}"""
                client.asMember(board, "/t/preview", rc.seatedIn(base)).status shouldBe HttpStatusCode.OK
                client.asMember(board, "/t/preview", rc.seatedIn(base)).status shouldBe HttpStatusCode.TooManyRequests
                // a caller without the right never burns budget and always gets the same 403
                repeat(5) { client.asMember(plain, "/t/preview", rc.seatedIn(base)).status shouldBe HttpStatusCode.Forbidden }
                // the board's budget is spent
                client.asMember(board, "/t/preview", "not json").status shouldBe HttpStatusCode.TooManyRequests

                repeat(2) { client.asMember(plain, "/t/count/$ruleBased").status shouldBe HttpStatusCode.OK }
                client.asMember(plain, "/t/count/$ruleBased").status shouldBe HttpStatusCode.TooManyRequests
            }
        }

        test("the preview of an invalid rule is a fixed 400 without the rule text") {
            testApplication {
                application { installRoutes() }
                val response = client.asMember(board, "/t/preview", """{"v":1,"op":"ALL","conditions":[{"type":"TOPSECRET"}]}""")
                response.status shouldBe HttpStatusCode.BadRequest
                response.bodyAsText() shouldNotContain "TOPSECRET"
            }
        }
    })

private fun LocalDate.plus(days: Int): LocalDate = kotlinx.datetime.LocalDate.fromEpochDays(toEpochDays() + days)
