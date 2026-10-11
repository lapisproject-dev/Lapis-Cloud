package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeCandidateSearch
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MemberPickDto
import network.lapis.cloud.shared.domain.MemberPickPageDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.BadRequestException
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

private const val ADMIN_ACTOR = "00000000-0000-0000-0000-000000000001"
private const val BOARD_ACTOR = "00000000-0000-0000-0000-000000000002"

/**
 * V1.9.101 -- [GovernanceService.countGeneralAssemblyEligible] (N must equal the quorum's eligible set) and
 * [GovernanceService.searchCommitteeCandidates] (minimum length, paging, literal LIKE, only id + name, right before data, rate limit).
 * Names carry a per-run token so no other spec's member can match, and are ASCII only (H2 and Postgres collate differently).
 */
class GovernanceGeneralAssemblyTest :
    FunSpec({
        val createdCommitteeIds = mutableListOf<Uuid>()
        val createdMemberIds = mutableListOf<Uuid>()
        val runToken =
            "q" +
                Uuid
                    .random()
                    .toString()
                    .filter { it.isLetter() }
                    .take(6) + "z"

        beforeSpec {
            DatabaseConfig.connect()
            DevSeedData.seedIfEmpty(force = true)
        }

        afterSpec {
            transaction {
                if (createdCommitteeIds.isNotEmpty()) {
                    CommitteeMembershipTable.deleteWhere { CommitteeMembershipTable.committeeId inList createdCommitteeIds }
                    CommitteeTable.deleteWhere { CommitteeTable.id inList createdCommitteeIds }
                }
                if (createdMemberIds.isNotEmpty()) {
                    AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                    MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
                }
            }
        }

        fun createCommittee(type: CommitteeType): Uuid {
            val id = Uuid.random()
            transaction {
                CommitteeTable.insert {
                    it[CommitteeTable.id] = id
                    it[name] = "GA-Test ${type.name}"
                    it[CommitteeTable.type] = type
                    it[description] = "V1.9.101"
                    it[active] = true
                    it[quorumPercent] = 50
                    it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
                }
            }
            createdCommitteeIds += id
            return id
        }

        fun createMember(
            displayName: String,
            status: MemberStatus = MemberStatus.ACTIVE,
            anonymized: Boolean = false,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[MemberTable.displayName] = displayName
                    it[email] = "ga-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2026, 1, 1)
                    it[membershipTierId] = null
                    if (anonymized) it[anonymizedAt] = LocalDateTime(2026, 2, 1, 0, 0)
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[role] = AccountRole.MEMBER
                }
            }
            createdMemberIds += id
            return id
        }

        fun seat(
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

        fun Application.installErrors() {
            install(StatusPages) {
                exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
                exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
                exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
                exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
                exception<SearchRateLimitedException> {
                    call,
                    cause,
                    ->
                    call.respondText(cause.message, status = HttpStatusCode.TooManyRequests)
                }
            }
        }

        fun newLimiter(max: Int = 1000) = FederationInboxRateLimiter(maxRequests = max, window = 1.minutes)

        val json = Json { ignoreUnknownKeys = false }

        // ── countGeneralAssemblyEligible ────────────────────────────────────────────────────────────────────

        test("countGeneralAssemblyEligible equals the quorum's eligible set (status mix, anonymized ACTIVE, manual MEMBER rows)") {
            val assembly = createCommittee(CommitteeType.GENERAL_ASSEMBLY)
            val workingGroup = createCommittee(CommitteeType.WORKING_GROUP)
            val active1 = createMember("${runToken}count Eins")
            createMember("${runToken}count Zwei")
            createMember("${runToken}count Beworben", status = MemberStatus.APPLICATION)
            createMember("${runToken}count Ausgetreten", status = MemberStatus.WITHDRAWN)
            createMember("${runToken}count Verstorben", status = MemberStatus.DECEASED)
            createMember("${runToken}count Anonym", anonymized = true)
            // manual rows at the assembly change nothing about N (membership is automatic)
            seat(assembly, active1, CommitteeRole.MEMBER)

            testApplication {
                application {
                    installErrors()
                    routing {
                        get("/test/count/{committeeId}") {
                            call.respondText(
                                GovernanceService(call = call).countGeneralAssemblyEligible(call.parameters["committeeId"]!!).toString(),
                            )
                        }
                    }
                }
                val expected =
                    transaction {
                        eligibleMemberIds(
                            committeeRow = CommitteeTable.selectAll().where { CommitteeTable.id eq assembly }.single(),
                            scheduledDate = LocalDate(2026, 6, 1),
                        ).size
                    }
                val response = client.get("/test/count/$assembly") { header("X-Member-Id", ADMIN_ACTOR) }
                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().toInt() shouldBe expected

                // a plain member may ask, too (any authenticated member)
                val plain = createMember("${runToken}count Plain")
                client.get("/test/count/$assembly") { header("X-Member-Id", plain.toString()) }.status shouldBe HttpStatusCode.OK

                client.get("/test/count/$workingGroup") { header("X-Member-Id", ADMIN_ACTOR) }.status shouldBe HttpStatusCode.BadRequest
                client.get("/test/count/${Uuid.random()}") { header("X-Member-Id", ADMIN_ACTOR) }.status shouldBe HttpStatusCode.NotFound
                client.get("/test/count/$assembly").status shouldBe HttpStatusCode.Unauthorized
            }
        }

        // ── searchCommitteeCandidates ───────────────────────────────────────────────────────────────────────

        fun io.ktor.server.routing.Route.searchRoute(limiter: FederationInboxRateLimiter) {
            get("/test/search/{committeeId}") {
                val q = call.request.queryParameters
                val page =
                    GovernanceService(call = call, candidateSearchRateLimiter = limiter)
                        .searchCommitteeCandidates(
                            committeeId = call.parameters["committeeId"]!!,
                            query = q["q"] ?: "",
                            page = q["page"]?.toInt() ?: 0,
                        )
                call.respondText(Json.encodeToString(MemberPickPageDto.serializer(), page))
            }
        }

        fun parse(body: String): MemberPickPageDto = json.decodeFromString(MemberPickPageDto.serializer(), body)

        test("search: bounds of query length and page, trimming, and the page size / hasMore flag") {
            val assembly = createCommittee(CommitteeType.GENERAL_ASSEMBLY)
            // 45 names with a shared token: Page 0 and 1 are full, page 2 has 5 and no more
            (1..45).forEach { createMember("${runToken}pg %02d".format(it)) }

            testApplication {
                application {
                    installErrors()
                    routing { searchRoute(newLimiter()) }
                }

                suspend fun get(
                    q: String,
                    page: Int,
                ) = client.get("/test/search/$assembly?q=${java.net.URLEncoder.encode(q, "UTF-8")}&page=$page") {
                    header("X-Member-Id", ADMIN_ACTOR)
                }

                get("a", 0).status shouldBe HttpStatusCode.BadRequest
                get(" a ", 0).status shouldBe HttpStatusCode.BadRequest
                get("a".repeat(CommitteeCandidateSearch.MAX_QUERY_LENGTH + 1), 0).status shouldBe HttpStatusCode.BadRequest
                get("${runToken}pg", -1).status shouldBe HttpStatusCode.BadRequest
                get("${runToken}pg", CommitteeCandidateSearch.MAX_PAGE + 1).status shouldBe HttpStatusCode.BadRequest

                val p0 = parse(get("  ${runToken}pg  ", 0).also { it.status shouldBe HttpStatusCode.OK }.bodyAsText())
                p0.items.size shouldBe CommitteeCandidateSearch.PAGE_SIZE
                p0.hasMore shouldBe true
                val p1 = parse(get("${runToken}pg", 1).bodyAsText())
                p1.items.size shouldBe CommitteeCandidateSearch.PAGE_SIZE
                p1.hasMore shouldBe true
                val p2 = parse(get("${runToken}pg", 2).bodyAsText())
                p2.items.size shouldBe 5
                p2.hasMore shouldBe false
                val all = (p0.items + p1.items + p2.items).map { it.id }
                all.toSet().size shouldBe 45
                // sorted by name: the pages continue each other
                (p0.items + p1.items + p2.items).map { it.displayName } shouldBe (1..45).map { "${runToken}pg %02d".format(it) }
            }
        }

        test("search: exactly one more than the page size and exactly the page size are told apart by hasMore") {
            val assembly = createCommittee(CommitteeType.GENERAL_ASSEMBLY)
            (1..20).forEach { createMember("${runToken}twenty %02d".format(it)) }
            (1..21).forEach { createMember("${runToken}tw1 %02d".format(it)) }
            testApplication {
                application {
                    installErrors()
                    routing { searchRoute(newLimiter()) }
                }
                val twenty =
                    parse(
                        client.get("/test/search/$assembly?q=${runToken}twenty&page=0") { header("X-Member-Id", ADMIN_ACTOR) }.bodyAsText(),
                    )
                twenty.items.size shouldBe 20
                twenty.hasMore shouldBe false
                val twentyOne =
                    parse(client.get("/test/search/$assembly?q=${runToken}tw1&page=0") { header("X-Member-Id", ADMIN_ACTOR) }.bodyAsText())
                twentyOne.items.size shouldBe 20
                twentyOne.hasMore shouldBe true
            }
        }

        test("search: % and _ match only literally; case-insensitive; non-ACTIVE and anonymized members never appear") {
            val assembly = createCommittee(CommitteeType.GENERAL_ASSEMBLY)
            val underscore = createMember("${runToken}Anna_B")
            val percent = createMember("${runToken}Anna%C")
            createMember("${runToken}AnnaXB")
            createMember("${runToken}Anna Beworben", status = MemberStatus.APPLICATION)
            createMember("${runToken}Anna Ausgetreten", status = MemberStatus.WITHDRAWN)
            createMember("${runToken}Anna Anonym", anonymized = true)
            testApplication {
                application {
                    installErrors()
                    routing { searchRoute(newLimiter()) }
                }

                suspend fun ids(q: String) =
                    parse(
                        client
                            .get("/test/search/$assembly?q=${java.net.URLEncoder.encode(q, "UTF-8")}&page=0") {
                                header("X-Member-Id", ADMIN_ACTOR)
                            }.bodyAsText(),
                    ).items.map { it.id }.toSet()

                ids("${runToken}Anna_B") shouldBe setOf(underscore.toString())
                ids("${runToken}Anna%C") shouldBe setOf(percent.toString())
                ids("${runToken.uppercase()}ANNA_B") shouldBe setOf(underscore.toString())
                // the plain prefix finds the three ACTIVE, non-anonymized Annas only
                ids("${runToken}Anna").size shouldBe 3
            }
        }

        test("search: the answer carries only id and name (no e-mail, no address) -- by type and by wire text") {
            val assembly = createCommittee(CommitteeType.GENERAL_ASSEMBLY)
            val person = createMember("${runToken}wire Person")
            transaction {
                MemberTable.update({ MemberTable.id eq person }) {
                    it[street] = "Geheimweg 7"
                    it[city] = "Geheimstadt"
                }
            }
            MemberPickDto::class.java.declaredFields
                .filter {
                    !it.isSynthetic &&
                        !java.lang.reflect.Modifier
                            .isStatic(it.modifiers)
                }.map { it.name }
                .toSet() shouldBe setOf("id", "displayName")
            testApplication {
                application {
                    installErrors()
                    routing { searchRoute(newLimiter()) }
                }
                val body = client.get("/test/search/$assembly?q=${runToken}wire&page=0") { header("X-Member-Id", ADMIN_ACTOR) }.bodyAsText()
                body shouldNotContain "example.org"
                body shouldNotContain "Geheim"
                body shouldNotContain "email"
                body shouldNotContain "street"
                parse(body).items.single().displayName shouldBe "${runToken}wire Person"
            }
        }

        test("search: the right is checked BEFORE any member data is read and before the limiter is touched") {
            val assembly = createCommittee(CommitteeType.GENERAL_ASSEMBLY)
            val other = createCommittee(CommitteeType.WORKING_GROUP)
            val plain = createMember("${runToken}rights Plain")
            val chairOfOther = createMember("${runToken}rights ChairOther")
            val secretaryOfAssembly = createMember("${runToken}rights SecretaryGa")
            val chairOfAssembly = createMember("${runToken}rights ChairGa")
            seat(other, chairOfOther, CommitteeRole.CHAIR)
            seat(assembly, secretaryOfAssembly, CommitteeRole.SECRETARY)
            seat(assembly, chairOfAssembly, CommitteeRole.CHAIR)
            // a limiter of exactly ONE request: if a refused caller consumed budget, the legitimate call afterwards would be limited
            val limiter = newLimiter(max = 1)
            testApplication {
                application {
                    installErrors()
                    routing { searchRoute(limiter) }
                }

                suspend fun call(actor: String) =
                    client.get("/test/search/$assembly?q=${runToken}rights&page=0") { header("X-Member-Id", actor) }

                call(plain.toString()).status shouldBe HttpStatusCode.Forbidden
                call(chairOfOther.toString()).status shouldBe HttpStatusCode.Forbidden
                // an unknown committee reveals nothing to an unauthorized caller either: 403, not 404
                client
                    .get("/test/search/${Uuid.random()}?q=${runToken}rights&page=0") { header("X-Member-Id", plain.toString()) }
                    .status shouldBe HttpStatusCode.Forbidden
                client.get("/test/search/$assembly?q=${runToken}rights&page=0").status shouldBe HttpStatusCode.Unauthorized
                // the refused calls above did not spend the single request of the limiter
                call(secretaryOfAssembly.toString()).status shouldBe HttpStatusCode.OK
                call(secretaryOfAssembly.toString()).status shouldBe HttpStatusCode.TooManyRequests
                // the limit is per acting member: another member with the right still has budget
                call(chairOfAssembly.toString()).status shouldBe HttpStatusCode.OK
                // privileged callers (BOARD/ADMIN) may search
                client
                    .get("/test/search/$assembly?q=${runToken}rights&page=0") { header("X-Member-Id", BOARD_ACTOR) }
                    .status shouldBe HttpStatusCode.OK
            }
        }

        test("search: the limiter allows maxRequests calls and answers the next with the typed rate-limit error") {
            val assembly = createCommittee(CommitteeType.GENERAL_ASSEMBLY)
            createMember("${runToken}limit Person")
            val limiter = newLimiter(max = 3)
            testApplication {
                application {
                    installErrors()
                    routing { searchRoute(limiter) }
                }
                repeat(3) {
                    client
                        .get("/test/search/$assembly?q=${runToken}limit&page=0") { header("X-Member-Id", ADMIN_ACTOR) }
                        .status shouldBe HttpStatusCode.OK
                }
                client
                    .get("/test/search/$assembly?q=${runToken}limit&page=0") { header("X-Member-Id", ADMIN_ACTOR) }
                    .status shouldBe HttpStatusCode.TooManyRequests
            }
        }
    })
