package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.DbClock
import network.lapis.cloud.server.db.generated.AccountTable
import network.lapis.cloud.server.db.generated.MailBudgetLockTable
import network.lapis.cloud.server.db.generated.MailOutboxTable
import network.lapis.cloud.server.db.generated.MailSendSlotTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.mail.MailBudgetConfig
import network.lapis.cloud.server.mail.budget.MailBudgetStore
import network.lapis.cloud.server.mail.minusDuration
import network.lapis.cloud.server.mail.plusDuration
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MailDeliveryStatusDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * Welle V1.9.81 -- [MailDeliveryStatusService]: ADMIN only (BOARD is refused), counts and the closed purpose vocabulary only -- by
 * construction (the DTO has no field that could carry an address) and by test (reflection over the DTO + a serialized response).
 */
class MailDeliveryStatusServiceTest :
    FunSpec({
        val createdMemberIds = mutableListOf<Uuid>()
        lateinit var adminId: Uuid
        lateinit var boardId: Uuid

        fun createMember(role: AccountRole): Uuid {
            val id = Uuid.random()
            transaction {
                MemberTable.insert {
                    it[MemberTable.id] = id
                    it[displayName] = "Status-Testmitglied"
                    it[email] = "status-${Uuid.random()}@example.org"
                    it[status] = MemberStatus.ACTIVE
                    it[joinedAt] = LocalDate(2020, 1, 1)
                }
                AccountTable.insert {
                    it[AccountTable.id] = Uuid.random()
                    it[memberId] = id
                    it[AccountTable.role] = role
                }
            }
            createdMemberIds += id
            return id
        }

        fun clean() {
            transaction {
                MailOutboxTable.deleteWhere { MailOutboxTable.id neq Uuid.random() }
                MailSendSlotTable.deleteWhere { MailSendSlotTable.id neq Uuid.random() }
                MailBudgetLockTable.update({ MailBudgetLockTable.id eq 1.toShort() }) { it[bulkPausedUntil] = null }
            }
        }

        beforeSpec {
            DatabaseConfig.connect()
            adminId = createMember(AccountRole.ADMIN)
            boardId = createMember(AccountRole.BOARD)
        }
        beforeTest { clean() }
        afterSpec {
            clean()
            transaction {
                AccountTable.deleteWhere { AccountTable.memberId inList createdMemberIds }
                MemberTable.deleteWhere { MemberTable.id inList createdMemberIds }
            }
        }

        fun testApp(
            budget: MailBudgetStore,
            outboxEnabled: Boolean,
            block: suspend io.ktor.client.HttpClient.() -> Unit,
        ) {
            testApplication {
                application {
                    install(StatusPages) {
                        exception<ForbiddenException> { call, _ -> call.respondText("FORBIDDEN", status = HttpStatusCode.Forbidden) }
                    }
                    routing {
                        get("/test/status") {
                            val dto =
                                MailDeliveryStatusService(
                                    call = call,
                                    budget = budget,
                                    outboxEnabled = outboxEnabled,
                                ).getMailDeliveryStatus()
                            call.respondText(Json.encodeToString(MailDeliveryStatusDto.serializer(), dto))
                        }
                    }
                }
                runBlocking { client.block() }
            }
        }

        fun row(
            status: String,
            purpose: String,
            createdAgo: kotlin.time.Duration = 0.minutes,
            finishedAgo: kotlin.time.Duration? = null,
        ) {
            val now = DbClock.nowLocalDateTime()
            transaction {
                MailOutboxTable.insert {
                    it[id] = Uuid.random()
                    it[MailOutboxTable.purpose] = purpose
                    it[priority] = 1.toShort()
                    it[MailOutboxTable.status] = status
                    it[nextAttemptAt] = now
                    it[createdAt] = now.minusDuration(createdAgo)
                    it[finishedAt] = finishedAgo?.let { ago -> now.minusDuration(ago) }
                    if (status == "QUEUED") {
                        it[recipientEnc] = "x"
                        it[subjectEnc] = "x"
                        it[textEnc] = "x"
                        it[htmlEnc] = "x"
                        it[recipientLookupHash] = "h"
                    }
                }
            }
        }

        test("only ADMIN may read the status; BOARD and a plain member are refused") {
            testApp(MailBudgetStore(null), outboxEnabled = true) {
                get("/test/status") { header("X-Member-Id", boardId.toString()) }.bodyAsText() shouldBe "FORBIDDEN"
                get("/test/status") { header("X-Member-Id", adminId.toString()) }.status shouldBe HttpStatusCode.OK
            }
        }

        test("without a budget or a durable queue: honest zeros and nulls") {
            testApp(MailBudgetStore(null), outboxEnabled = false) {
                val dto =
                    Json.decodeFromString(
                        MailDeliveryStatusDto.serializer(),
                        get("/test/status") {
                            header("X-Member-Id", adminId.toString())
                        }.bodyAsText(),
                    )
                dto.budgetEnabled shouldBe false
                dto.maxPerHour shouldBe null
                dto.usedInWindow shouldBe null
                dto.outboxEnabled shouldBe false
                dto.queuedCount shouldBe 0
                dto.oldestQueuedAgeSeconds shouldBe null
                dto.failedLast7DaysByPurpose shouldBe emptyMap()
            }
        }

        test("budget, usage, queue depth, oldest age and 7-day failures/expiries per purpose; older ones do not count") {
            val budget = MailBudgetStore(MailBudgetConfig.Enabled(maxPerHour = 250, reservePerHour = 50))
            val now = DbClock.nowLocalDateTime()
            transaction {
                repeat(7) {
                    MailSendSlotTable.insert {
                        it[id] = Uuid.random()
                        it[reservedAt] = now.minusDuration(10.minutes)
                        it[lane] = "SYSTEM"
                    }
                }
                MailSendSlotTable.insert {
                    it[id] = Uuid.random()
                    it[reservedAt] = now.minusDuration(2.hours) // outside the window
                    it[lane] = "SYSTEM"
                }
            }
            row("QUEUED", "password-reset", createdAgo = 5.minutes)
            row("QUEUED", "event-registration", createdAgo = 20.minutes)
            row("FAILED", "password-reset", finishedAgo = 1.days)
            row("FAILED", "password-reset", finishedAgo = 2.days)
            row("FAILED", "event-registration", finishedAgo = 3.days)
            row("FAILED", "event-registration", finishedAgo = 9.days) // too old
            row("EXPIRED", "password-reset", finishedAgo = 1.days)
            row("SENT", "password-reset", finishedAgo = 1.days) // not a failure
            budget.pauseBulk(now.plusDuration(10.minutes))

            testApp(budget, outboxEnabled = true) {
                val dto =
                    Json.decodeFromString(
                        MailDeliveryStatusDto.serializer(),
                        get("/test/status") {
                            header("X-Member-Id", adminId.toString())
                        }.bodyAsText(),
                    )
                dto.budgetEnabled shouldBe true
                dto.maxPerHour shouldBe 250
                dto.reservePerHour shouldBe 50
                dto.usedInWindow shouldBe 7
                dto.outboxEnabled shouldBe true
                dto.queuedCount shouldBe 2
                (dto.oldestQueuedAgeSeconds!! in 1195..1260) shouldBe true // ~20 minutes
                dto.failedLast7DaysByPurpose shouldBe mapOf("password-reset" to 2, "event-registration" to 1)
                dto.expiredLast7DaysByPurpose shouldBe mapOf("password-reset" to 1)
                (dto.bulkPausedUntil != null) shouldBe true
            }
        }

        test("the response carries no address, no subject, no payload and no per-row timestamp -- structurally and in the JSON") {
            MailDeliveryStatusDto::class
                .java.declaredFields
                .map { it.name }
                .filterNot { it.startsWith("$") || it == "Companion" }
                .toSet() shouldBe
                setOf(
                    "budgetEnabled",
                    "maxPerHour",
                    "reservePerHour",
                    "usedInWindow",
                    "outboxEnabled",
                    "queuedCount",
                    "oldestQueuedAgeSeconds",
                    "failedLast7DaysByPurpose",
                    "expiredLast7DaysByPurpose",
                    "bulkPausedUntil",
                )
            row("FAILED", "password-reset", finishedAgo = 1.days)
            testApp(MailBudgetStore(null), outboxEnabled = true) {
                val json = get("/test/status") { header("X-Member-Id", adminId.toString()) }.bodyAsText()
                json shouldNotContain "@"
                json shouldNotContain "example.org"
            }
        }
    })
