package network.lapis.cloud.server.rpc

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.conference.NoOpSecretBallotStreamGuard
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.CommitteeMembershipTable
import network.lapis.cloud.server.db.generated.CommitteeTable
import network.lapis.cloud.server.db.generated.MeetingTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.MotionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusOptionTable
import network.lapis.cloud.server.db.generated.SystemicConsensusTable
import network.lapis.cloud.server.testdb.PostgresConfigured
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.CommitteeRole
import network.lapis.cloud.shared.domain.CommitteeType
import network.lapis.cloud.shared.domain.MeetingFormat
import network.lapis.cloud.shared.domain.MeetingStatus
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.MotionStatus
import network.lapis.cloud.shared.domain.SystemicConsensusOpenInput
import network.lapis.cloud.shared.domain.SystemicConsensusOptionInput
import network.lapis.cloud.shared.domain.SystemicConsensusStatus
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.util.concurrent.CyclicBarrier
import kotlin.uuid.Uuid

/** Throwaway fixture factory shared by the rationale specs (own committee, members, motion, Systemic Consensus with one proposal). */
internal class SystemicConsensusRationaleTestData {
    val committeeIds = mutableListOf<Uuid>()
    val memberIds = mutableListOf<Uuid>()

    fun cleanUp() = cleanUpSystemicConsensusTestData(committeeIds = committeeIds, memberIds = memberIds)

    fun member(email: String): Uuid {
        val id = Uuid.random()
        transaction {
            MemberTable.insert {
                it[MemberTable.id] = id
                it[displayName] = "Rationale Testmitglied"
                it[MemberTable.email] = email
                it[status] = MemberStatus.ACTIVE
                it[joinedAt] = LocalDate(2026, 1, 1)
                it[membershipTierId] = null
            }
            AccountTable.insert {
                it[AccountTable.id] = Uuid.random()
                it[memberId] = id
                it[role] = AccountRole.MEMBER
            }
        }
        memberIds += id
        return id
    }

    class Fixture(
        val chair: Uuid,
        val proposer: Uuid,
        val other: Uuid,
        val outsider: Uuid,
        val consensusId: String,
        val optionId: String,
        val statusQuoId: String,
    )

    fun fixture(tag: String): Fixture {
        val committeeId = Uuid.random()
        transaction {
            CommitteeTable.insert {
                it[id] = committeeId
                it[name] = "SK Rationale $tag"
                it[type] = CommitteeType.EXECUTIVE_BOARD
                it[description] = "Testcommittee"
                it[active] = true
                it[quorumPercent] = 50
                it[createdAt] = LocalDateTime(2026, 1, 1, 0, 0)
            }
        }
        committeeIds += committeeId
        val chair = member("sk-why-chair-$tag@example.org")
        val proposer = member("sk-why-proposer-$tag@example.org")
        val other = member("sk-why-other-$tag@example.org")
        val outsider = member("sk-why-outsider-$tag@example.org")
        transaction {
            listOf(chair to CommitteeRole.CHAIR, proposer to CommitteeRole.MEMBER, other to CommitteeRole.MEMBER).forEach { (m, r) ->
                CommitteeMembershipTable.insert {
                    it[id] = Uuid.random()
                    it[CommitteeMembershipTable.committeeId] = committeeId
                    it[memberId] = m
                    it[role] = r
                    it[since] = LocalDate(2020, 1, 1)
                    it[until] = null
                }
            }
        }
        val meetingId = Uuid.random()
        val motionId = Uuid.random()
        transaction {
            MeetingTable.insert {
                it[id] = meetingId
                it[MeetingTable.committeeId] = committeeId
                it[title] = "Testmeeting"
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
            MotionTable.insert {
                it[id] = motionId
                it[targetCommitteeId] = committeeId
                it[title] = "Testmotion"
                it[rationale] = "Rationale"
                it[text] = "Motionstext"
                it[submitterMemberId] = chair
                it[status] = MotionStatus.SCHEDULED
                it[submittedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                it[reviewedBy] = chair
                it[reviewedAt] = LocalDateTime(2026, 1, 1, 0, 0)
                it[reviewNote] = null
                it[MotionTable.meetingId] = meetingId
                it[agendaItemId] = null
                it[resolutionId] = null
                it[withdrawnAt] = null
            }
        }
        var consensusId = ""
        var optionId = ""
        var statusQuoId = ""
        testApplication {
            application {
                install(StatusPages) { installSystemicConsensusExceptionHandlers() }
                routing { registerRationaleTestRoutes() }
            }
            consensusId =
                client.post("/test/open/$motionId") { header("X-Member-Id", chair.toString()) }.bodyAsText()
            optionId =
                client.post("/test/add/$consensusId") { header("X-Member-Id", proposer.toString()) }.bodyAsText()
        }
        statusQuoId =
            transaction {
                SystemicConsensusOptionTable
                    .selectAll()
                    .where {
                        (SystemicConsensusOptionTable.systemicConsensusId eq Uuid.parse(consensusId)) and
                            (SystemicConsensusOptionTable.isStatusQuoOption eq true)
                    }.single()[SystemicConsensusOptionTable.id]
            }.toString()
        return Fixture(
            chair = chair,
            proposer = proposer,
            other = other,
            outsider = outsider,
            consensusId = consensusId,
            optionId = optionId,
            statusQuoId = statusQuoId,
        )
    }
}

/**
 * V1.9.39 -- the rationale of a Systemic-Consensus proposal: roles, phases, validation, no echo of the input, deterministic
 * ordering, and the `FOR UPDATE` race against `freezeOptions`. Runs on H2 ([SystemicConsensusOptionRationaleTest]) and on
 * PostgreSQL ([SystemicConsensusOptionRationalePostgresTest]) with the same assertions.
 */
abstract class SystemicConsensusOptionRationaleScenarios(
    db: TestDatabase,
) : FunSpec({
        val data = SystemicConsensusRationaleTestData()

        beforeSpec {
            db.activate()
            DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db)
        afterSpec {
            data.cleanUp()
            db.deactivate()
        }

        fun storedRationale(optionId: String): String? =
            transaction {
                SystemicConsensusOptionTable
                    .selectAll()
                    .where { SystemicConsensusOptionTable.id eq Uuid.parse(optionId) }
                    .single()[SystemicConsensusOptionTable.rationale]
            }

        fun setStatus(
            consensusId: String,
            status: SystemicConsensusStatus,
        ) {
            transaction {
                SystemicConsensusTable.update({ SystemicConsensusTable.id eq Uuid.parse(consensusId) }) {
                    it[SystemicConsensusTable.status] =
                        status
                }
            }
        }

        test("roles: proposer and manager may set; another member and an outsider are forbidden; the status quo option is a conflict") {
            val f = data.fixture("roles")
            testApplication {
                application {
                    install(StatusPages) { installSystemicConsensusExceptionHandlers() }
                    routing { registerRationaleTestRoutes() }
                }

                suspend fun set(
                    who: Uuid,
                    option: String,
                    text: String,
                ) = client.post("/test/rationale/$option") {
                    header("X-Member-Id", who.toString())
                    setBody(text)
                }

                set(f.proposer, f.optionId, "von mir").status shouldBe HttpStatusCode.OK
                storedRationale(f.optionId) shouldBe "von mir"
                set(f.chair, f.optionId, "vom Vorsitz").status shouldBe HttpStatusCode.OK
                storedRationale(f.optionId) shouldBe "vom Vorsitz"
                set(f.other, f.optionId, "fremd").status shouldBe HttpStatusCode.Forbidden
                set(f.outsider, f.optionId, "fremd").status shouldBe HttpStatusCode.Forbidden
                storedRationale(f.optionId) shouldBe "vom Vorsitz"
                set(f.chair, f.statusQuoId, "nie").status shouldBe HttpStatusCode.Conflict
                storedRationale(f.statusQuoId) shouldBe null
            }
        }

        test("phases: only COLLECTION accepts a rationale; RATING, CLOSED, EVALUATED and ABORTED are conflicts") {
            val f = data.fixture("phases")
            testApplication {
                application {
                    install(StatusPages) { installSystemicConsensusExceptionHandlers() }
                    routing { registerRationaleTestRoutes() }
                }
                for (status in SystemicConsensusStatus.entries) {
                    setStatus(f.consensusId, status)
                    val r =
                        client.post("/test/rationale/${f.optionId}") {
                            header("X-Member-Id", f.proposer.toString())
                            setBody("Phase $status")
                        }
                    r.status shouldBe if (status == SystemicConsensusStatus.COLLECTION) HttpStatusCode.OK else HttpStatusCode.Conflict
                }
                storedRationale(f.optionId) shouldBe "Phase COLLECTION"
            }
        }

        test("validation: set, change, remove with null/blank; 1000 ok, 1001 conflict; control characters conflict; CRLF normalised") {
            val f = data.fixture("validation")
            val secret = "GEHEIMER-BEGRUENDUNGSTEXT-‮"
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            root.addAppender(appender)
            try {
                testApplication {
                    application {
                        install(StatusPages) { installSystemicConsensusExceptionHandlers() }
                        routing { registerRationaleTestRoutes() }
                    }

                    suspend fun set(
                        text: String?,
                        nullBody: Boolean = false,
                    ) = client.post("/test/rationale/${f.optionId}" + if (nullBody) "?null=true" else "") {
                        header("X-Member-Id", f.proposer.toString())
                        setBody(text ?: "")
                    }

                    set("erste").status shouldBe HttpStatusCode.OK
                    set("zweite").status shouldBe HttpStatusCode.OK
                    storedRationale(f.optionId) shouldBe "zweite"
                    set(null, nullBody = true).status shouldBe HttpStatusCode.OK
                    storedRationale(f.optionId) shouldBe null
                    set("x").status shouldBe HttpStatusCode.OK
                    set("   ").status shouldBe HttpStatusCode.OK
                    storedRationale(f.optionId) shouldBe null

                    set("x".repeat(1000)).status shouldBe HttpStatusCode.OK
                    set("y".repeat(1001)).status shouldBe HttpStatusCode.Conflict
                    storedRationale(f.optionId) shouldBe "x".repeat(1000)

                    set("a\r\nb").status shouldBe HttpStatusCode.OK
                    storedRationale(f.optionId) shouldBe "a\nb"

                    val rejected = set(secret)
                    rejected.status shouldBe HttpStatusCode.Conflict
                    rejected.bodyAsText() shouldNotContain "GEHEIMER"
                    rejected.bodyAsText() shouldNotContain f.optionId
                    storedRationale(f.optionId) shouldBe "a\nb"
                    val forbidden =
                        client.post("/test/rationale/${f.optionId}") {
                            header("X-Member-Id", f.other.toString())
                            setBody(secret)
                        }
                    forbidden.status shouldBe HttpStatusCode.Forbidden
                    forbidden.bodyAsText() shouldNotContain "GEHEIMER"
                }
            } finally {
                root.detachAppender(appender)
            }
            appender.list.none { it.formattedMessage.contains("GEHEIMER") } shouldBe true
        }

        test("addOption stores a rationale; an invalid one is a conflict and creates no row") {
            val f = data.fixture("add")
            testApplication {
                application {
                    install(StatusPages) { installSystemicConsensusExceptionHandlers() }
                    routing { registerRationaleTestRoutes() }
                }
                val ok =
                    client.post("/test/add/${f.consensusId}?why=Weil+es+geht") { header("X-Member-Id", f.other.toString()) }
                ok.status shouldBe HttpStatusCode.OK
                storedRationale(ok.bodyAsText()) shouldBe "Weil es geht"
                val before = transaction { SystemicConsensusOptionTable.selectAll().count() }
                val bad =
                    client.post("/test/add/${f.consensusId}?why=${"z".repeat(1001)}") { header("X-Member-Id", f.other.toString()) }
                bad.status shouldBe HttpStatusCode.Conflict
                transaction { SystemicConsensusOptionTable.selectAll().count() } shouldBe before
            }
        }

        test("reads carry the rationale and list options in a deterministic (position, id) order") {
            val f = data.fixture("reads")
            // two options with the SAME position, inserted with ids in reverse order
            val low = Uuid.parse("00000000-0000-0000-0000-000000000001")
            val high = Uuid.parse("ffffffff-ffff-ffff-ffff-ffffffffffff")
            transaction {
                for (id in listOf(high, low)) {
                    SystemicConsensusOptionTable.insert {
                        it[SystemicConsensusOptionTable.id] = id
                        it[systemicConsensusId] = Uuid.parse(f.consensusId)
                        it[label] = "same-position"
                        it[position] = 77
                        it[isStatusQuoOption] = false
                        it[createdBy] = f.proposer
                        it[rationale] = "r-$id"
                    }
                }
            }
            testApplication {
                application {
                    install(StatusPages) { installSystemicConsensusExceptionHandlers() }
                    routing { registerRationaleTestRoutes() }
                }
                val listed = client.get("/test/list/${f.consensusId}") { header("X-Member-Id", f.other.toString()) }.bodyAsText()
                val ids = listed.split(";").map { it.substringBefore("|") }
                (ids.indexOf(low.toString()) < ids.indexOf(high.toString())) shouldBe true
                listed.contains("r-$low") shouldBe true
                val whole = client.get("/test/get/${f.consensusId}") { header("X-Member-Id", f.other.toString()) }.bodyAsText()
                val wholeIds = whole.split(";").map { it.substringBefore("|") }
                (wholeIds.indexOf(low.toString()) < wholeIds.indexOf(high.toString())) shouldBe true
                whole.contains("r-$high") shouldBe true
            }
        }

        test("race: setOptionRationale vs freezeOptions -- the stored rationale always equals what the freeze saw") {
            repeat(4) { round ->
                val f = data.fixture("race$round")
                testApplication {
                    application {
                        install(StatusPages) { installSystemicConsensusExceptionHandlers() }
                        routing { registerRationaleTestRoutes() }
                    }
                    val barrier = CyclicBarrier(2)
                    val (setStatus, frozen) =
                        coroutineScope {
                            val setter =
                                async(Dispatchers.IO) {
                                    barrier.await()
                                    runCatching {
                                        client
                                            .post("/test/rationale/${f.optionId}") {
                                                header("X-Member-Id", f.proposer.toString())
                                                setBody("race-$round")
                                            }.status
                                    }.getOrNull()
                                }
                            val freezer =
                                async(Dispatchers.IO) {
                                    barrier.await()
                                    runCatching {
                                        client
                                            .post("/test/freeze/${f.consensusId}?option=${f.optionId}") {
                                                header("X-Member-Id", f.chair.toString())
                                            }.bodyAsText()
                                    }.getOrNull()
                                }
                            setter.await() to freezer.await()
                        }
                    val stored = storedRationale(f.optionId)
                    if (setStatus != HttpStatusCode.OK) stored shouldBe null
                    if (frozen != null && frozen.startsWith("RATING")) {
                        // what the freeze transaction saw on the option is what is stored now: nothing was written after it
                        val seen = frozen.substringAfter("RATING|").ifEmpty { null }
                        stored shouldBe seen
                    }
                }
            }
        }
    })

private fun Route.registerRationaleTestRoutes() {
    fun io.ktor.server.application.ApplicationCall.service() =
        SystemicConsensusService(call = this, streamGuard = NoOpSecretBallotStreamGuard)
    post("/test/open/{motionId}") {
        val k = call.service().openSystemicConsensus(SystemicConsensusOpenInput(motionId = call.parameters["motionId"]!!))
        call.respondText(k.id)
    }
    post("/test/add/{consensusId}") {
        val why = call.request.queryParameters["why"]
        val o =
            call.service().addOption(
                systemicConsensusId = call.parameters["consensusId"]!!,
                input = SystemicConsensusOptionInput(label = "Vorschlag", rationale = why),
            )
        call.respondText(o.id)
    }
    post("/test/rationale/{optionId}") {
        val body = call.receiveText()
        val text = if (call.request.queryParameters["null"] == "true") null else body
        val o = call.service().setOptionRationale(optionId = call.parameters["optionId"]!!, rationale = text)
        call.respondText(o.rationale ?: "")
    }
    post("/test/freeze/{consensusId}") {
        val k = call.service().freezeOptions(call.parameters["consensusId"]!!)
        val optionId = call.request.queryParameters["option"]
        val seen =
            k.options
                .firstOrNull { it.id == optionId }
                ?.rationale
                .orEmpty()
        call.respondText("${k.status}|$seen")
    }
    get("/test/list/{consensusId}") {
        val list = call.service().listOptions(call.parameters["consensusId"]!!)
        call.respondText(list.joinToString(";") { "${it.id}|${it.rationale ?: ""}" })
    }
    get("/test/get/{consensusId}") {
        val k = call.service().getSystemicConsensus(call.parameters["consensusId"]!!)
        call.respondText(k.options.joinToString(";") { "${it.id}|${it.rationale ?: ""}" })
    }
}

/** The unchanged H2 run (normal `test` task). */
class SystemicConsensusOptionRationaleTest : SystemicConsensusOptionRationaleScenarios(TestDatabase.H2)

/** The same scenarios on a fresh PostgreSQL database (`postgresTest` task). */
@Tags("Postgres")
@EnabledIf(PostgresConfigured::class)
class SystemicConsensusOptionRationalePostgresTest : SystemicConsensusOptionRationaleScenarios(TestDatabase.Postgres())
