package network.lapis.cloud.server.partg

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.PartgReportEnterpriseTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgEnterpriseCheckStatus
import network.lapis.cloud.shared.domain.PartgEnterpriseInputDto
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgValueChangeDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import network.lapis.cloud.shared.rpc.PartyReportNotEnabledException
import network.lapis.cloud.shared.rpc.PartyReportRevisionConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.287 (R3c) -- statement D (enterprise activity) through the RPC service on H2 AND PostgreSQL: roles, add/change/delete, NULL against
 * zero, the cap of 50 under the lock, the sort order, foreign ids, the revision fence, the adjustment status against R1 (and that R1 is never
 * written), and that neither the audit entries nor the log carry a name or an amount.
 */
abstract class PartyReportEnterpriseServiceScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = PartgFixtures()

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = true)
        beforeTest { fx.setParty(true) }
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        val year: Int by lazy { PartgYearRules.maxYear() - 1 }

        fun freshUnit() = PartgUnit.State(fx.chapter().toString()).key()

        fun input(
            name: String = "Betrieb GmbH",
            id: String? = null,
            income: Long? = null,
            expenses: Long? = null,
            share: Int? = null,
        ) = PartgEnterpriseInputDto(
            id = id,
            name = name,
            legalForm = null,
            seat = null,
            shareBasisPoints = share,
            incomeCents = income,
            expensesCents = expenses,
        )

        test("the role is checked first (treasurer and member: Forbidden), then the party switch (NotEnabled)") {
            val unit = freshUnit()
            partgTestApplication(fixtures = fx) {
                listOf(AccountRole.TREASURER, AccountRole.MEMBER).forEach { role ->
                    val member = fx.member(role = role)
                    attempt(member = member) {
                        service(it).saveEnterprise(year = year, unitKey = unit, expectedRevision = 0, enterprise = input())
                    }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    attempt(member = member) {
                        service(
                            it,
                        ).deleteEnterprise(year = year, unitKey = unit, expectedRevision = 0, enterpriseId = Uuid.random().toString())
                    }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                }
                fx.setParty(false)
                val board = fx.member()
                attempt(member = board) {
                    service(it).saveEnterprise(year = year, unitKey = unit, expectedRevision = 0, enterprise = input())
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportNotEnabledException>()
            }
            fx.setParty(true)
        }

        test("add, change (the id is kept), delete; the revision counts; NULL stays NULL and is not zero") {
            val board = fx.member()
            val unit = freshUnit()
            partgTestApplication(fixtures = fx) {
                var dto =
                    call(member = board) {
                        service(
                            it,
                        ).saveEnterprise(
                            year = year,
                            unitKey = unit,
                            expectedRevision = 0,
                            enterprise = input(income = null, expenses = 0L),
                        )
                    }
                dto.revision shouldBe 1
                val e = dto.enterprises.single()
                e.incomeCents shouldBe null
                e.expensesCents shouldBe 0L
                e.rowNumber shouldBe 1
                dto =
                    call(member = board) {
                        service(it).saveEnterprise(
                            year = year,
                            unitKey = unit,
                            expectedRevision = dto.revision,
                            enterprise = input(id = e.id, name = "Neu", income = 1_000L, expenses = null, share = 10_000),
                        )
                    }
                val changed = dto.enterprises.single()
                changed.id shouldBe e.id
                changed.name shouldBe "Neu"
                changed.incomeCents shouldBe 1_000L
                changed.expensesCents shouldBe null
                changed.shareBasisPoints shouldBe 10_000
                dto =
                    call(member = board) {
                        service(it).deleteEnterprise(year = year, unitKey = unit, expectedRevision = dto.revision, enterpriseId = e.id)
                    }
                dto.enterprises shouldBe emptyList()
                dto.revision shouldBe 3
            }
        }

        test("refused inputs carry a code and no input") {
            val board = fx.member()
            val unit = freshUnit()
            partgTestApplication(fixtures = fx) {
                suspend fun code(enterprise: PartgEnterpriseInputDto) =
                    attempt(member = board) {
                        service(it).saveEnterprise(year = year, unitKey = unit, expectedRevision = 0, enterprise = enterprise)
                    }.exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>().message
                code(input(name = "  ")) shouldBe "NAME_EMPTY"
                code(input(share = 10_001)) shouldBe "OUT_OF_RANGE"
                code(input(income = -1L)) shouldBe "OUT_OF_RANGE"
                code(input(name = "a\nb")) shouldBe "CONTROL_CHARACTER"
                code(input(name = "x".repeat(201))) shouldBe "FIELD_TOO_LONG"
            }
        }

        test("a stale revision is refused for saving and deleting") {
            val board = fx.member()
            val unit = freshUnit()
            partgTestApplication(fixtures = fx) {
                val created =
                    call(
                        member = board,
                    ) { service(it).saveEnterprise(year = year, unitKey = unit, expectedRevision = 0, enterprise = input()) }
                attempt(member = board) {
                    service(it).saveEnterprise(year = year, unitKey = unit, expectedRevision = 0, enterprise = input(name = "Stale"))
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportRevisionConflictException>()
                attempt(member = board) {
                    service(it).deleteEnterprise(
                        year = year,
                        unitKey = unit,
                        expectedRevision = 0,
                        enterpriseId = created.enterprises.single().id,
                    )
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportRevisionConflictException>()
                call(member = board) { service(it).getAnnex(year = year, unitKey = unit) }.enterprises.size shouldBe 1
            }
        }

        test("an id of another unit or year, or no UUID at all: NotFound, nothing changes") {
            val board = fx.member()
            val unitA = freshUnit()
            val unitB = freshUnit()
            partgTestApplication(fixtures = fx) {
                val a =
                    call(member = board) {
                        service(it).saveEnterprise(year = year, unitKey = unitA, expectedRevision = 0, enterprise = input(name = "In A"))
                    }
                val b =
                    call(member = board) {
                        service(it).saveEnterprise(year = year, unitKey = unitB, expectedRevision = 0, enterprise = input(name = "In B"))
                    }
                val aId = a.enterprises.single().id
                attempt(member = board) {
                    service(it).saveEnterprise(
                        year = year,
                        unitKey = unitB,
                        expectedRevision = b.revision,
                        enterprise = input(id = aId, name = "Hijack"),
                    )
                }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                attempt(member = board) {
                    service(it).deleteEnterprise(year = year, unitKey = unitB, expectedRevision = b.revision, enterpriseId = aId)
                }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                attempt(member = board) {
                    service(it).deleteEnterprise(year = year - 1, unitKey = unitA, expectedRevision = 0, enterpriseId = aId)
                }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                attempt(member = board) {
                    service(it).deleteEnterprise(year = year, unitKey = unitA, expectedRevision = a.revision, enterpriseId = "kein-uuid")
                }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                call(member = board) { service(it).getAnnex(year = year, unitKey = unitA) }.enterprises.single().name shouldBe "In A"
                call(member = board) { service(it).getAnnex(year = year, unitKey = unitB) }.enterprises.single().name shouldBe "In B"
            }
        }

        test("the cap: 50 are possible, the 51st is refused with TOO_MANY; a change at the cap still works") {
            val board = fx.member()
            val unit = freshUnit()
            partgTestApplication(fixtures = fx) {
                var revision = 0L
                var lastId = ""
                repeat(50) { i ->
                    val dto =
                        call(member = board) {
                            service(
                                it,
                            ).saveEnterprise(year = year, unitKey = unit, expectedRevision = revision, enterprise = input(name = "E$i"))
                        }
                    revision = dto.revision
                    lastId = dto.enterprises.last().id
                }
                attempt(member = board) {
                    service(it).saveEnterprise(year = year, unitKey = unit, expectedRevision = revision, enterprise = input(name = "E51"))
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>().message shouldBe "TOO_MANY"
                val changed =
                    call(member = board) {
                        service(it).saveEnterprise(
                            year = year,
                            unitKey = unit,
                            expectedRevision = revision,
                            enterprise = input(id = lastId, name = "Geaendert"),
                        )
                    }
                changed.enterprises.size shouldBe 50
            }
        }

        test("sorted by creation time, then id: the order does not follow the typed name") {
            val board = fx.member()
            val unit = freshUnit()
            partgTestApplication(fixtures = fx) {
                var revision = 0L
                val names = listOf("Zeta", "Alpha", "Mitte")
                var dto = call(member = board) { service(it).getAnnex(year = year, unitKey = unit) }
                names.forEach { n ->
                    dto =
                        call(member = board) {
                            service(
                                it,
                            ).saveEnterprise(year = year, unitKey = unit, expectedRevision = revision, enterprise = input(name = n))
                        }
                    revision = dto.revision
                }
                // the rows were created in this order; equal time stamps fall back to the id, so only a set comparison is stable here
                dto.enterprises.map { it.name }.toSet() shouldBe names.toSet()
                dto.enterprises.map { it.rowNumber } shouldContainExactly listOf(1, 2, 3)
                val stored =
                    transaction {
                        PartgReportEnterpriseTable.selectAll().where { PartgReportEnterpriseTable.createdBy eq board }.count()
                    }
                stored shouldBe 3L
            }
        }

        test("the adjustment status against R1: no row, position not entered, matches at 100 cents, differs at 101; R1 is never written") {
            val board = fx.member()
            val unit = freshUnit()
            partgTestApplication(fixtures = fx) {
                call(member = board) { service(it).getAnnex(year = year, unitKey = unit) }.enterpriseChecks shouldBe emptyList()
                var dto =
                    call(member = board) {
                        service(it).saveEnterprise(
                            year = year,
                            unitKey = unit,
                            expectedRevision = 0,
                            enterprise = input(income = 100_000L, expenses = 50_000L),
                        )
                    }
                dto.enterpriseChecks.map { it.positionCode to it.status } shouldContainExactly
                    listOf(
                        "E05" to PartgEnterpriseCheckStatus.POSITION_NOT_ENTERED,
                        "A02F" to PartgEnterpriseCheckStatus.POSITION_NOT_ENTERED,
                    )
                val sheet =
                    call(member = board) {
                        service(it).saveSheet(
                            year = year,
                            unitKey = unit,
                            expectedRevision = 0,
                            expectedPriorRevision = 0,
                            changes =
                                listOf(
                                    PartgValueChangeDto(year = year, positionCode = "E05", amountCents = 100_100L),
                                    PartgValueChangeDto(year = year, positionCode = "A02F", amountCents = 50_101L),
                                ),
                        )
                    }
                dto = call(member = board) { service(it).getAnnex(year = year, unitKey = unit) }
                dto.enterpriseChecks.map { it.status } shouldContainExactly
                    listOf(PartgEnterpriseCheckStatus.MATCHES, PartgEnterpriseCheckStatus.DIFFERS)
                // a further D write does not touch the values of R1
                val before = call(member = board) { service(it).getSheet(year = year, unitKey = unit) }
                call(member = board) {
                    service(
                        it,
                    ).saveEnterprise(year = year, unitKey = unit, expectedRevision = dto.revision, enterprise = input(name = "Zwei"))
                }
                val after = call(member = board) { service(it).getSheet(year = year, unitKey = unit) }
                after.revision shouldBe before.revision
                after.values shouldBe before.values
                after.values shouldBe mapOf("E05" to 100_100L, "A02F" to 50_101L)
                sheet.revision shouldBe before.revision
                // a second row without figures makes the status not comparable
                call(member = board) { service(it).getAnnex(year = year, unitKey = unit) }
                    .enterpriseChecks
                    .map { it.status }
                    .toSet() shouldBe setOf(PartgEnterpriseCheckStatus.NOT_COMPARABLE)
            }
        }

        test("the write rate limit applies") {
            val limited = PartgFixtures(limiter = FederationInboxRateLimiter(maxRequests = 1, window = 60.minutes))
            try {
                val board = limited.member()
                val unit = PartgUnit.State(limited.chapter().toString()).key()
                partgTestApplication(fixtures = limited) {
                    call(
                        member = board,
                    ) { service(it).saveEnterprise(year = year, unitKey = unit, expectedRevision = 0, enterprise = input()) }
                    attempt(member = board) {
                        service(it).saveEnterprise(year = year, unitKey = unit, expectedRevision = 1, enterprise = input(name = "Zwei"))
                    }.exceptionOrNull().shouldBeInstanceOf<network.lapis.cloud.shared.rpc.RateLimitedException>()
                }
            } finally {
                limited.cleanUp()
            }
        }

        test("the audit entries and the log carry no name or amount; the change codes are the ENTERPRISE ones") {
            val board = fx.member()
            val unit = freshUnit()
            val appender = ListAppender<ILoggingEvent>().also { it.start() }
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            root.addAppender(appender)
            try {
                partgTestApplication(fixtures = fx) {
                    var dto =
                        call(member = board) {
                            service(it).saveEnterprise(
                                year = year,
                                unitKey = unit,
                                expectedRevision = 0,
                                enterprise = input(name = "Geheimbetrieb", income = 7_654_321L, expenses = 1_234_567L),
                            )
                        }
                    dto =
                        call(member = board) {
                            service(it).saveEnterprise(
                                year = year,
                                unitKey = unit,
                                expectedRevision = dto.revision,
                                enterprise = input(id = dto.enterprises.single().id, name = "Geheimbetrieb2", income = 7_654_322L),
                            )
                        }
                    dto =
                        call(member = board) {
                            service(it).deleteEnterprise(
                                year = year,
                                unitKey = unit,
                                expectedRevision = dto.revision,
                                enterpriseId = dto.enterprises.single().id,
                            )
                        }
                    val entries =
                        transaction {
                            AuditLogEntryTable
                                .selectAll()
                                .where { AuditLogEntryTable.entityType inList listOf(AuditEntityType.PARTG_REPORT_ANNEX) }
                                .filter { it[AuditLogEntryTable.actorMemberId] == board }
                                .map { (it[AuditLogEntryTable.beforeSnapshot] ?: "") + (it[AuditLogEntryTable.afterSnapshot] ?: "") }
                        }
                    entries.size shouldBe 3
                    val joined = entries.joinToString("|")
                    listOf("Geheim", "7654321", "7654322", "1234567").forEach { joined shouldNotContain it }
                    listOf("ENTERPRISE_ADDED", "ENTERPRISE_UPDATED", "ENTERPRISE_REMOVED").forEach { (joined.contains(it)) shouldBe true }
                    appender.list.joinToString("|") { it.formattedMessage }.let { log ->
                        listOf("Geheim", "7654321").forEach { log shouldNotContain it }
                    }
                    dto.enterprises shouldBe emptyList()
                }
            } finally {
                root.detachAppender(appender)
            }
        }

        if (db.isPostgres) {
            test("two concurrent adds with the same revision: exactly one wins") {
                val board = fx.member()
                val unit = freshUnit()
                partgTestApplication(fixtures = fx) {
                    val results =
                        kotlinx.coroutines.coroutineScope {
                            (1..2)
                                .map { n ->
                                    async {
                                        attempt(member = board) {
                                            service(it).saveEnterprise(
                                                year = year,
                                                unitKey = unit,
                                                expectedRevision = 0,
                                                enterprise = input(name = "Parallel $n"),
                                            )
                                        }
                                    }
                                }.awaitAll()
                        }
                    results.count { it.isSuccess } shouldBe 1
                    results.count { it.exceptionOrNull() is PartyReportRevisionConflictException } shouldBe 1
                }
            }
        }
    })
