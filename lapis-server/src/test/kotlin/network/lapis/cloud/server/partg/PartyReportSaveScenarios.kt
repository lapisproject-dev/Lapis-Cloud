package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.PartgReportSheetTable
import network.lapis.cloud.server.db.generated.PartgReportValueTable
import network.lapis.cloud.server.rpc.PartyReportService
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgValueChangeDto
import network.lapis.cloud.shared.domain.PartgValueSource
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import network.lapis.cloud.shared.rpc.PartyReportRevisionConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.225 -- saving report values on H2 AND PostgreSQL: happy path, optimistic locking, 0 versus "not entered", the prior year, input
 * validation, the parallel creation of one sheet (savepoint on the unique violation), and ascending lock order without deadlocks.
 */
abstract class PartyReportSaveScenarios(
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
        val federal = PartgUnit.Federal.key()

        fun change(
            code: String,
            cents: Long?,
            y: Int = year,
        ) = PartgValueChangeDto(year = y, positionCode = code, amountCents = cents)

        fun valueRows(sheetUnit: String) =
            transaction {
                val sheets = PartgReportSheetTable.selectAll().where { PartgReportSheetTable.unitKey eq sheetUnit }.toList()
                sheets.flatMap { s ->
                    PartgReportValueTable.selectAll().where { PartgReportValueTable.sheetId eq s[PartgReportSheetTable.id] }.toList()
                }
            }

        test("happy path: values are stored, the revision becomes 1, the prior revision stays 0") {
            val board = fx.member()
            val chapter = fx.chapter()
            val unit = PartgUnit.State(chapter.toString()).key()
            partgTestApplication(fixtures = fx) {
                val result =
                    call(member = board) {
                        service(
                            it,
                        ).saveSheet(
                            year = year,
                            unitKey = unit,
                            expectedRevision = 0,
                            expectedPriorRevision = 0,
                            changes = listOf(change("E01", 100_00), change("A01", 40_00)),
                        )
                    }
                result.changed shouldBe 2
                result.revision shouldBe 1
                result.priorRevision shouldBe 0
                val sheet = call(member = board) { service(it).getSheet(year = year, unitKey = unit) }
                sheet.values shouldBe mapOf("E01" to 100_00L, "A01" to 40_00L)
                sheet.revision shouldBe 1
                sheet.unit.chapterName?.isNotBlank() shouldBe true
                sheet.priorYear shouldBe year - 1
                sheet.priorValues shouldBe emptyMap()
                valueRows(unit).all { it[PartgReportValueTable.valueSource] == PartgValueSource.MANUAL } shouldBe true
            }
        }

        test("a stale revision is a conflict, the same values again are a no-op that keeps the revision") {
            val board = fx.member()
            val chapter = fx.chapter()
            val unit = PartgUnit.Subordinate(chapter.toString()).key()
            partgTestApplication(fixtures = fx) {
                call(member = board) {
                    service(
                        it,
                    ).saveSheet(
                        year = year,
                        unitKey = unit,
                        expectedRevision = 0,
                        expectedPriorRevision = 0,
                        changes = listOf(change("E02", 5_00)),
                    )
                }
                attempt(member = board) {
                    service(
                        it,
                    ).saveSheet(
                        year = year,
                        unitKey = unit,
                        expectedRevision = 0,
                        expectedPriorRevision = 0,
                        changes = listOf(change("E02", 6_00)),
                    )
                }.exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportRevisionConflictException>()
                val same =
                    call(member = board) {
                        service(
                            it,
                        ).saveSheet(
                            year = year,
                            unitKey = unit,
                            expectedRevision = 1,
                            expectedPriorRevision = 0,
                            changes = listOf(change("E02", 5_00)),
                        )
                    }
                same.changed shouldBe 0
                same.revision shouldBe 1
                call(member = board) { service(it).getSheet(year = year, unitKey = unit) }.values shouldBe mapOf("E02" to 5_00L)
            }
        }

        test("0 creates a value row, null removes it, and the overview counts only entered positions") {
            val board = fx.member()
            val chapter = fx.chapter()
            val unit = PartgUnit.State(chapter.toString()).key()
            partgTestApplication(fixtures = fx) {
                call(member = board) {
                    service(
                        it,
                    ).saveSheet(
                        year = year,
                        unitKey = unit,
                        expectedRevision = 0,
                        expectedPriorRevision = 0,
                        changes = listOf(change("E01", 0), change("E03", 70_00)),
                    )
                }
                val row = call(member = board) { service(it).overview(year) }.single { it.unit.unitKey == unit }
                row.filledCount shouldBe 2
                row.leafCount shouldBe 35
                row.incomeTotalCents shouldBe 70_00L
                call(member = board) {
                    service(
                        it,
                    ).saveSheet(
                        year = year,
                        unitKey = unit,
                        expectedRevision = 1,
                        expectedPriorRevision = 0,
                        changes = listOf(change("E01", null)),
                    )
                }
                call(member = board) { service(it).getSheet(year = year, unitKey = unit) }.values shouldBe mapOf("E03" to 70_00L)
                // deleting a value that was never entered changes nothing
                call(member = board) {
                    service(
                        it,
                    ).saveSheet(
                        year = year,
                        unitKey = unit,
                        expectedRevision = 2,
                        expectedPriorRevision = 0,
                        changes = listOf(change("A01", null)),
                    )
                }.changed shouldBe
                    0
                val empty =
                    call(member = board) { service(it).overview(year) }.single {
                        it.unit.unitKey == federal ||
                            it.unit.chapterId == null
                    }
                empty.incomeTotalCents shouldBe call(member = board) { service(it).overview(year) }.first().incomeTotalCents
            }
        }

        test("the prior year is written to its own sheet with its own revision") {
            val board = fx.member()
            val chapter = fx.chapter()
            val unit = PartgUnit.State(chapter.toString()).key()
            partgTestApplication(fixtures = fx) {
                val r1 =
                    call(member = board) {
                        service(it).saveSheet(
                            year = year,
                            unitKey = unit,
                            expectedRevision = 0,
                            expectedPriorRevision = 0,
                            changes =
                                listOf(
                                    change("E01", 1_00),
                                    change(
                                        "E01",
                                        9_00,
                                        y =
                                            year - 1,
                                    ),
                                ),
                        )
                    }
                r1.revision shouldBe 1
                r1.priorRevision shouldBe 1
                val sheet = call(member = board) { service(it).getSheet(year = year, unitKey = unit) }
                sheet.values shouldBe mapOf("E01" to 1_00L)
                sheet.priorValues shouldBe mapOf("E01" to 9_00L)
                // only the prior year changes: the revision of the report year stays
                val r2 =
                    call(member = board) {
                        service(it).saveSheet(
                            year = year,
                            unitKey = unit,
                            expectedRevision = 1,
                            expectedPriorRevision = 1,
                            changes =
                                listOf(
                                    change(
                                        "E02",
                                        2_00,
                                        y =
                                            year - 1,
                                    ),
                                ),
                        )
                    }
                r2.revision shouldBe 1
                r2.priorRevision shouldBe 2
                // a stale PRIOR revision conflicts although the report year is current
                attempt(member = board) {
                    service(
                        it,
                    ).saveSheet(
                        year = year,
                        unitKey = unit,
                        expectedRevision = 1,
                        expectedPriorRevision = 1,
                        changes = listOf(change("E02", 3_00)),
                    )
                }.exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportRevisionConflictException>()
            }
        }

        test("invalid input is refused with the typed exception and writes nothing") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                val beforeSheets = fx.sheetCount()
                val bad: List<Triple<String, Int, List<PartgValueChangeDto>>> =
                    listOf(
                        Triple("future year", PartgYearRules.maxYear() + 1, listOf(change("E01", 1, y = PartgYearRules.maxYear() + 1))),
                        Triple("year 2000", 2000, listOf(change("E01", 1, y = 2000))),
                        Triple("sum code", year, listOf(change("E11", 1))),
                        Triple("unknown code", year, listOf(change("E99", 1))),
                        Triple("foreign year", year, listOf(change("E01", 1, y = year - 5))),
                        Triple("negative", year, listOf(change("E01", -1))),
                        Triple("too large", year, listOf(change("E01", 100_000_000_000_000L))),
                        Triple("duplicate", year, listOf(change("E01", 1), change("E01", 2))),
                        Triple("too many", year, (1..71).map { change("E01", it.toLong()) }),
                    )
                for ((label, y, changes) in bad) {
                    val ex =
                        attempt(member = board) {
                            service(
                                it,
                            ).saveSheet(year = y, unitKey = federal, expectedRevision = 0, expectedPriorRevision = 0, changes = changes)
                        }.exceptionOrNull()
                    (label to ex).second.shouldBeInstanceOf<PartyReportInvalidInputException>()
                }
                attempt(member = board) {
                    service(
                        it,
                    ).saveSheet(
                        year = year,
                        unitKey = "STATE:not-a-uuid",
                        expectedRevision = 0,
                        expectedPriorRevision = 0,
                        changes = emptyList(),
                    )
                }.exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportInvalidInputException>()
                attempt(member = board) {
                    service(
                        it,
                    ).saveSheet(
                        year = year,
                        unitKey = "STATE:${Uuid.random()}",
                        expectedRevision = 0,
                        expectedPriorRevision = 0,
                        changes = listOf(change("E01", 1)),
                    )
                }.exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
                fx.sheetCount() shouldBe beforeSheets
            }
        }

        test("the same sheet created by eight callers at once: exactly one wins, the others see a conflict") {
            val board = fx.member()
            val chapter = fx.chapter()
            val unit = PartgUnit.State(chapter.toString()).key()
            partgTestApplication(fixtures = fx) {
                val results =
                    withContext(Dispatchers.IO) {
                        (1..8)
                            .map { n ->
                                async {
                                    attempt(member = board) {
                                        service(it).saveSheet(
                                            year = year,
                                            unitKey = unit,
                                            expectedRevision = 0,
                                            expectedPriorRevision = 0,
                                            changes =
                                                listOf(
                                                    change(
                                                        "E01",
                                                        n * 100L,
                                                    ),
                                                ),
                                        )
                                    }
                                }
                            }.awaitAll()
                    }
                results.count { it.isSuccess } shouldBe 1
                results.filter { it.isFailure }.all { it.exceptionOrNull() is PartyReportRevisionConflictException } shouldBe true
                call(member = board) { service(it).getSheet(year = year, unitKey = unit) }.revision shouldBe 1
                transaction {
                    PartgReportSheetTable.selectAll().where { PartgReportSheetTable.unitKey eq unit }.count()
                } shouldBe 1L
            }
        }

        test("parallel saves of neighbouring years lock in ascending order and never deadlock") {
            val board = fx.member()
            val chapter = fx.chapter()
            val unit = PartgUnit.State(chapter.toString()).key()
            partgTestApplication(fixtures = fx) {
                val results =
                    withContext(Dispatchers.IO) {
                        (1..6)
                            .map { n ->
                                async {
                                    val y = if (n % 2 == 0) year else year - 1
                                    attempt(
                                        member = board,
                                    ) {
                                        service(it).saveSheet(
                                            year = y,
                                            unitKey = unit,
                                            expectedRevision = 0,
                                            expectedPriorRevision = 0,
                                            changes =
                                                listOf(
                                                    change(
                                                        "E0${(n % 5) + 1}",
                                                        n * 100L,
                                                        y = y,
                                                    ),
                                                ),
                                        )
                                    }
                                }
                            }.awaitAll()
                    }
                results.all { it.isSuccess || it.exceptionOrNull() is PartyReportRevisionConflictException } shouldBe true
                results.any { it.isSuccess } shouldBe true
            }
        }

        test("every effective value writes one audit entry with before, after and source; a no-op writes none") {
            val board = fx.member()
            val chapter = fx.chapter()
            val unit = PartgUnit.State(chapter.toString()).key()
            partgTestApplication(fixtures = fx) {
                fun entries() =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityType eq AuditEntityType.PARTG_REPORT_SHEET }
                            .filter { it[AuditLogEntryTable.actorMemberId] == board }
                            .sortedBy { it[AuditLogEntryTable.sequenceNumber] }
                            .map {
                                Triple(
                                    it[AuditLogEntryTable.action],
                                    it[AuditLogEntryTable.beforeSnapshot],
                                    it[AuditLogEntryTable.afterSnapshot],
                                )
                            }
                    }
                call(member = board) {
                    service(
                        it,
                    ).saveSheet(
                        year = year,
                        unitKey = unit,
                        expectedRevision = 0,
                        expectedPriorRevision = 0,
                        changes = listOf(change("E01", 10_00)),
                    )
                }
                call(member = board) {
                    service(
                        it,
                    ).saveSheet(
                        year = year,
                        unitKey = unit,
                        expectedRevision = 1,
                        expectedPriorRevision = 0,
                        changes = listOf(change("E01", 20_00)),
                    )
                }
                call(member = board) {
                    service(
                        it,
                    ).saveSheet(
                        year = year,
                        unitKey = unit,
                        expectedRevision = 2,
                        expectedPriorRevision = 0,
                        changes = listOf(change("E01", 20_00)),
                    )
                }
                call(member = board) {
                    service(
                        it,
                    ).saveSheet(
                        year = year,
                        unitKey = unit,
                        expectedRevision = 2,
                        expectedPriorRevision = 0,
                        changes = listOf(change("E01", null)),
                    )
                }
                val all = entries()
                all.map { it.first } shouldContainExactly listOf(AuditAction.CREATE, AuditAction.UPDATE, AuditAction.VOID)
                all[0].second shouldBe null
                all[0].third!!.contains("\"cents\":1000") shouldBe true
                all[0].third!!.contains("MANUAL") shouldBe true
                all[1].second!!.contains("\"cents\":1000") shouldBe true
                all[1].third!!.contains("\"cents\":2000") shouldBe true
                all[2].third shouldBe null
            }
        }

        test("the position hint is trimmed, validated and audited; blank clears it") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                call(member = board) { service(it).updatePositionHint(code = "E01", hint = "  Hinweis \n zweite Zeile ") }.orgHint shouldBe
                    "Hinweis \n zweite Zeile"
                call(member = board) { service(it).listPositions() }.first { it.code == "E01" }.orgHint shouldBe "Hinweis \n zweite Zeile"
                attempt(member = board) { service(it).updatePositionHint(code = "E01", hint = "x".repeat(501)) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportInvalidInputException>()
                attempt(member = board) { service(it).updatePositionHint(code = "E01", hint = "a\u0007b") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportInvalidInputException>()
                attempt(member = board) { service(it).updatePositionHint(code = "NOPE", hint = "x") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportInvalidInputException>()
                call(member = board) { service(it).updatePositionHint(code = "E01", hint = "   ") }.orgHint shouldBe null
                val audits =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityType eq AuditEntityType.PARTG_REPORT_POSITION }
                            .filter { it[AuditLogEntryTable.actorMemberId] == board }
                            .size
                    }
                audits shouldBe 2
            }
        }

        test("the write limit answers with the typed rate-limit refusal") {
            val limited =
                PartgFixtures(
                    limiter =
                        network.lapis.cloud.server.federation
                            .FederationInboxRateLimiter(maxRequests = 2),
                )
            val board = limited.member()
            limited.setParty(true)
            partgTestApplication(fixtures = limited) {
                val outcomes =
                    (1..4).map {
                        attempt(
                            member = board,
                        ) { call ->
                            PartyReportService(
                                call = call,
                                writeRateLimiter = limited.limiter,
                            ).updatePositionHint(code = "E01", hint = "x")
                        }
                    }
                outcomes.count { it.isSuccess } shouldBe 2
                outcomes.last().exceptionOrNull().shouldBeInstanceOf<network.lapis.cloud.shared.rpc.RateLimitedException>()
            }
            limited.cleanUp()
        }

        test("positions of the catalogue are served in layout order") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                call(member = board) { service(it).listPositions() }.map { it.code } shouldBe PartgPositions.ALL.map { it.code }
            }
        }
    })
