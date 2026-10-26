package network.lapis.cloud.server.dashboard

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.datetime.LocalDate
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.McpPostDraftTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.rpc.DASHBOARD_RATE_MAX
import network.lapis.cloud.server.rpc.DashboardService
import network.lapis.cloud.server.time.MutableTestClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.DashboardModuleKeys
import network.lapis.cloud.shared.domain.DashboardPriority
import network.lapis.cloud.shared.domain.DashboardReasonKeys
import network.lapis.cloud.shared.domain.DashboardSignalDto
import network.lapis.cloud.shared.domain.McpPostDraftStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.NavCountsDto
import network.lapis.cloud.shared.domain.SocialPostVisibility
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** A source that counts its runs and answers one fixed signal, so the tests can see whether the cache or the limiter let a call through. */
private class CountingSource : DashboardSignalSource {
    override val moduleKey: String = DashboardModuleKeys.AI
    var runs = 0

    override fun collect(ctx: SignalContext): List<DashboardSignalDto> {
        runs++
        return listOfNotNull(
            signal(module = moduleKey, reason = DashboardReasonKeys.AI_DRAFTS_WAITING, count = runs, priority = DashboardPriority.INFO),
        )
    }
}

/**
 * V1.9.224 -- [DashboardService] through a throwaway route (house style of `OnlineCountsServiceTest`): the authentication boundary,
 * the fresh
 * status, the 20 / 30 second caches, the per-member limit with its stale answer, and that a cache hit costs nothing.
 */
class DashboardServiceTest :
    FunSpec({
        val memberIds = mutableListOf<Uuid>()

        beforeSpec { DatabaseConfig.connect() }
        afterEach {
            if (memberIds.isNotEmpty()) {
                transaction {
                    McpPostDraftTable.deleteWhere { McpPostDraftTable.memberId inList memberIds }
                    AccountTable.deleteWhere { AccountTable.memberId inList memberIds }
                    MemberTable.deleteWhere { MemberTable.id inList memberIds }
                }
                memberIds.clear()
            }
        }

        fun member(
            status: MemberStatus = MemberStatus.ACTIVE,
            role: AccountRole = AccountRole.MEMBER,
        ): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Dashboard-Dienst ${id.toString().take(6)}"
                    it[email] = "dashboard-service-$id@example.org"
                    it[MemberTable.status] = status
                    it[joinedAt] = LocalDate(2020, 1, 1)
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            memberIds += id
            return id
        }

        fun draft(memberId: Uuid) {
            transaction {
                McpPostDraftTable.insert {
                    it[McpPostDraftTable.id] = Uuid.random()
                    it[McpPostDraftTable.memberId] = memberId
                    it[agentLabel] = "agent"
                    it[content] = "x"
                    it[visibility] = SocialPostVisibility.MEMBERS_ONLY
                    it[status] = McpPostDraftStatus.OPEN
                    it[createdAt] = kotlinx.datetime.LocalDateTime(2030, 1, 1, 0, 0)
                    it[updatedAt] = kotlinx.datetime.LocalDateTime(2030, 1, 1, 0, 0)
                }
            }
        }

        class Harness(
            val clock: MutableTestClock = MutableTestClock("2030-06-10T12:00:00Z"),
            val caches: DashboardCaches = DashboardCaches(now = clock::now),
            val signalsLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = DASHBOARD_RATE_MAX),
            val navLimiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = DASHBOARD_RATE_MAX),
            val source: CountingSource = CountingSource(),
            val useRealSources: Boolean = false,
        )

        fun run(
            harness: Harness,
            block: suspend (suspend (String, Uuid?) -> HttpResponse) -> Unit,
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<UnauthenticatedException> {
                            call,
                            cause,
                            ->
                            call.respondText(cause.message, status = HttpStatusCode.Unauthorized)
                        }
                        exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
                    }
                    routing {
                        fun service(call: io.ktor.server.application.ApplicationCall) =
                            DashboardService(
                                call = call,
                                caches = harness.caches,
                                signalsRateLimiter = harness.signalsLimiter,
                                navCountsRateLimiter = harness.navLimiter,
                                conferenceEnabled = true,
                                mcpEnabled = true,
                                sources = if (harness.useRealSources) DashboardSources.all else listOf(harness.source),
                            )
                        get("/test/signals") {
                            val body =
                                Json.encodeToString(
                                    ListSerializer(DashboardSignalDto.serializer()),
                                    service(call).getDashboardSignals(),
                                )
                            call.respondText(body)
                        }
                        get("/test/nav") {
                            call.respondText(Json.encodeToString(NavCountsDto.serializer(), service(call).getNavCounts()))
                        }
                    }
                }
                block { path, who -> client.get(path) { who?.let { header("X-Member-Id", it.toString()) } } }
            }
        }

        test("an anonymous caller is rejected by both methods before anything else happens") {
            val harness = Harness()
            run(harness) { get ->
                get("/test/signals", null).status shouldBe HttpStatusCode.Unauthorized
                get("/test/nav", null).status shouldBe HttpStatusCode.Unauthorized
            }
            harness.source.runs shouldBe 0
        }

        test("a guest gets a conference of 0/0 and no signals of the member modules") {
            val guest = member(status = MemberStatus.GUEST)
            run(Harness(useRealSources = true)) { get ->
                get("/test/nav", guest).bodyAsText() shouldBe
                    """{"conferenceActive":0,"conferenceTotal":0,"encounterActive":0,"encounterTotal":${encounterTotalForGuests()}}"""
                get("/test/signals", guest).bodyAsText() shouldBe "[]"
            }
        }

        test("the status is read from the database: a member who has left gets nothing, at once") {
            val id = member()
            draft(id)
            val harness = Harness(useRealSources = true)
            run(harness) { get ->
                val before = Json.decodeFromString(ListSerializer(DashboardSignalDto.serializer()), get("/test/signals", id).bodyAsText())
                before.map { it.reasonKey } shouldBe listOf(DashboardReasonKeys.AI_DRAFTS_WAITING)
                transaction { MemberTable.update({ MemberTable.id eq id }) { it[status] = MemberStatus.WITHDRAWN } }
                // within the 20 seconds: the key contains the status, so the old answer is not served
                get("/test/signals", id).bodyAsText() shouldBe "[]"
            }
        }

        test("a cache hit costs no source run and no budget; after 20 seconds the next call computes again") {
            val id = member()
            val harness = Harness()
            run(harness) { get ->
                repeat(30) { get("/test/signals", id).status shouldBe HttpStatusCode.OK }
                harness.source.runs shouldBe 1
                harness.clock.advance(21.seconds)
                get("/test/signals", id).status shouldBe HttpStatusCode.OK
                harness.source.runs shouldBe 2
            }
        }

        test("the counters have their own 30 second cache") {
            val id = member()
            val harness = Harness()
            run(harness) { get ->
                val first = get("/test/nav", id).bodyAsText()
                harness.clock.advance(25.seconds)
                get("/test/nav", id).bodyAsText() shouldBe first
                harness.caches.navCounts.size() shouldBe 1
                harness.clock.advance(10.seconds)
                get("/test/nav", id).status shouldBe HttpStatusCode.OK
            }
        }

        test("a changed role gets a new cache key, not the answer computed for the old role") {
            val id = member(role = AccountRole.BOARD)
            val harness = Harness()
            run(harness) { get ->
                get("/test/signals", id)
                transaction { AccountTable.update({ AccountTable.memberId eq id }) { it[role] = AccountRole.MEMBER } }
                get("/test/signals", id)
                harness.source.runs shouldBe 2
            }
        }

        test("over the limit: the last answer (up to ten minutes old) is served; without one the call is a conflict") {
            val id = member()
            val limiter = FederationInboxRateLimiter(maxRequests = DASHBOARD_RATE_MAX)
            val harness = Harness(signalsLimiter = limiter)
            run(harness) { get ->
                repeat(DASHBOARD_RATE_MAX) {
                    harness.clock.advance(1.minutes) // every call is a cache miss, none of them is limited
                    get("/test/signals", id).status shouldBe HttpStatusCode.OK
                }
                harness.clock.advance(1.minutes)
                val limited = get("/test/signals", id)
                limited.status shouldBe HttpStatusCode.OK
                harness.source.runs shouldBe DASHBOARD_RATE_MAX // the 13th call did not reach the source: the stale answer was served
                // a fresh process (empty caches) with the exhausted limiter has no stale answer to give
                val noStale = Harness(signalsLimiter = limiter)
                run(noStale) { getAgain -> getAgain("/test/signals", id).status shouldBe HttpStatusCode.Conflict }
            }
        }

        test("the cache is bounded: the eldest entries go once the maximum is exceeded") {
            val clock = MutableTestClock("2030-06-10T12:00:00Z")
            val cache = TtlCache<Int, String>(ttl = 20.seconds, maxEntries = 100, now = clock::now)
            repeat(250) { cache.put(key = it, value = "v$it") }
            cache.size() shouldBe 100
            cache.get(0) shouldBe null
            cache.get(249) shouldBe "v249"
            clock.advance(21.seconds)
            cache.get(249) shouldBe null
            cache.getStale(key = 249, maxAge = 10.minutes) shouldBe "v249"
        }
    })

/** Spaces a guest sees: only the ones that admit non-members; read inside a transaction with the same predicate the counter uses. */
private fun encounterTotalForGuests(): Int =
    transaction {
        NavCountsQuery.count(status = MemberStatus.GUEST, conferenceEnabled = true).encounterTotal
    }
