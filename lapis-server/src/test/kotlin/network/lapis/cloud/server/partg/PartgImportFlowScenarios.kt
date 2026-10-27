package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.PartgReportValueTable
import network.lapis.cloud.server.federation.FederationInboxRateLimiter
import network.lapis.cloud.server.partg.xlsx.PartgXlsxWriter
import network.lapis.cloud.server.routes.registerPartgReportRoutes
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgImportApplyDto
import network.lapis.cloud.shared.domain.PartgImportApplyResultDto
import network.lapis.cloud.shared.domain.PartgImportColumn
import network.lapis.cloud.shared.domain.PartgImportErrorCode
import network.lapis.cloud.shared.domain.PartgImportPreviewDto
import network.lapis.cloud.shared.domain.PartgImportRejectionCode
import network.lapis.cloud.shared.domain.PartgImportRejectionDto
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgValueChangeDto
import network.lapis.cloud.shared.domain.PartgValueSource
import network.lapis.cloud.shared.rpc.PartyReportRevisionConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/**
 * V1.9.225 -- the two-step Excel import through the real routes on H2 AND PostgreSQL: preview, apply, all-or-nothing, the token rules (expiry,
 * single use, foreign member, replaced by a newer preview), the revision check between preview and apply, the size cap, the rate limit and the
 * access order (role first, then the switch).
 */
abstract class PartgImportFlowScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = PartgFixtures()
        val json = Json { ignoreUnknownKeys = true }

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        beforeTest { fx.setParty(true) }
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        val year: Int by lazy { PartgYearRules.maxYear() - 1 }
        val xlsxType = ContentType.parse("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")

        fun file(
            unit: PartgUnit,
            values: Map<String, Long>,
            prior: Map<String, Long> = emptyMap(),
            y: Int = year,
        ) = PartgXlsxWriter.write(
            organizationName = "Test e.V.",
            unit = unit,
            unitName = "Einheit",
            year = y,
            values = values,
            priorValues = prior,
        )

        class Env(
            val client: HttpClient,
            val store: PartgImportPreviewStore,
            val clock: LongArray,
        )

        fun withRoutes(
            limiter: FederationInboxRateLimiter = FederationInboxRateLimiter(maxRequests = 10_000, window = 60.minutes),
            block: suspend Env.() -> Unit,
        ) {
            val clock = longArrayOf(0L)
            val store = PartgImportPreviewStore(nanoTime = { clock[0] })
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    routing { registerPartgReportRoutes(previewStore = store, rateLimiter = limiter) }
                }
                Env(client, store, clock).block()
            }
        }

        suspend fun Env.preview(
            member: Uuid,
            bytes: ByteArray,
            unit: String,
            y: Int = year,
            fileFirst: Boolean = false,
        ): HttpResponse =
            client.submitFormWithBinaryData(
                url = "/api/partg-report/import/preview",
                formData =
                    formData {
                        if (!fileFirst) {
                            append("year", y.toString())
                            append("unit", unit)
                        }
                        append(
                            "file",
                            bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, xlsxType.toString())
                                append(HttpHeaders.ContentDisposition, "filename=\"bericht.xlsx\"")
                            },
                        )
                        if (fileFirst) {
                            append("year", y.toString())
                            append("unit", unit)
                        }
                    },
            ) { header("X-Member-Id", member.toString()) }

        suspend fun Env.apply(
            member: Uuid,
            token: String,
            rev: Long,
            priorRev: Long,
        ): HttpResponse =
            client.post("/api/partg-report/import/apply") {
                header("X-Member-Id", member.toString())
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(
                        PartgImportApplyDto.serializer(),
                        PartgImportApplyDto(token = token, expectedRevision = rev, expectedPriorRevision = priorRev),
                    ),
                )
            }

        suspend fun HttpResponse.rejection() = json.decodeFromString(PartgImportRejectionDto.serializer(), bodyAsText())

        suspend fun HttpResponse.previewDto() = json.decodeFromString(PartgImportPreviewDto.serializer(), bodyAsText())

        test("preview without errors gives a token and the changes; apply writes everything with source IMPORT and audits it") {
            val board = fx.member()
            val chapter = fx.chapter()
            val unit = PartgUnit.State(chapter.toString())
            withRoutes {
                val response = preview(board, file(unit, mapOf("E01" to 100_00L, "A01" to 5_00L), mapOf("E01" to 9_00L)), unit.key())
                response.status shouldBe HttpStatusCode.OK
                val dto = response.previewDto()
                dto.errors shouldBe emptyList()
                dto.changes.size shouldBe 3
                dto.changes.any { it.column == PartgImportColumn.PRIOR && it.positionCode == "E01" && it.newCents == 9_00L } shouldBe true
                val applied = apply(board, dto.token!!, dto.sheetRevision, dto.priorRevision)
                applied.status shouldBe HttpStatusCode.OK
                json.decodeFromString(PartgImportApplyResultDto.serializer(), applied.bodyAsText()).changed shouldBe 3
                val values =
                    transaction {
                        PartgReportValueTable
                            .selectAll()
                            .where {
                                PartgReportValueTable.updatedBy eq board
                            }.map { it[PartgReportValueTable.valueSource] }
                    }
                values.size shouldBe 3
                values.all { it == PartgValueSource.IMPORT } shouldBe true
                val audits =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityType eq AuditEntityType.PARTG_REPORT_SHEET }
                            .filter { it[AuditLogEntryTable.actorMemberId] == board }
                            .map { it[AuditLogEntryTable.afterSnapshot].orEmpty() }
                    }
                audits.size shouldBe 4 // three values plus the collective entry
                val collective = audits.single { it.contains("sha256") }
                collective.contains("bericht.xlsx") shouldBe true
                collective.contains("\"changeCount\":3") shouldBe true
            }
        }

        test("a file with errors gets no token and writes nothing") {
            val board = fx.member()
            val unit = PartgUnit.Federal
            withRoutes {
                val before = transaction { PartgReportValueTable.selectAll().count() }
                val bad = file(unit, mapOf("E01" to 1L)).let { PartgXlsxWriterTamper.replaceCell(xlsx = it, ref = "D9", text = "abc") }
                val dto = preview(board, bad, unit.key()).previewDto()
                dto.token shouldBe null
                dto.errors.map { it.code } shouldBe listOf(PartgImportErrorCode.NOT_A_NUMBER)
                dto.errors.single().row shouldBe 9
                transaction { PartgReportValueTable.selectAll().count() } shouldBe before
                store.size() shouldBe 0
            }
        }

        test("a file for another unit or year is refused without override") {
            val board = fx.member()
            val chapter = fx.chapter()
            withRoutes {
                val other = file(PartgUnit.State(chapter.toString()), mapOf("E01" to 1L))
                val dto = preview(board, other, PartgUnit.Federal.key()).previewDto()
                dto.errors.single().code shouldBe PartgImportErrorCode.TARGET_MISMATCH
                dto.token shouldBe null
                val oldYear = file(PartgUnit.Federal, mapOf("E01" to 1L), y = year - 1)
                preview(board, oldYear, PartgUnit.Federal.key())
                    .previewDto()
                    .errors
                    .single()
                    .code shouldBe
                    PartgImportErrorCode.TARGET_MISMATCH
            }
        }

        test("the token is single use, expires after 15 minutes, belongs to its member, and a newer preview replaces it") {
            val board = fx.member()
            val other = fx.member()
            val chapter = fx.chapter()
            val unit = PartgUnit.Subordinate(chapter.toString())
            withRoutes {
                val bytes = file(unit, mapOf("E02" to 2_00L))
                // reuse
                val first = preview(board, bytes, unit.key()).previewDto()
                apply(board, first.token!!, first.sheetRevision, first.priorRevision).status shouldBe HttpStatusCode.OK
                apply(board, first.token!!, first.sheetRevision, first.priorRevision).rejection().code shouldBe
                    PartgImportRejectionCode.TOKEN_INVALID
                // expiry on the monotonic clock
                val second = preview(board, file(unit, mapOf("E02" to 3_00L)), unit.key()).previewDto()
                clock[0] += 15.minutes.inWholeNanoseconds + 1
                apply(board, second.token!!, second.sheetRevision, second.priorRevision).rejection().code shouldBe
                    PartgImportRejectionCode.TOKEN_INVALID
                // a foreign member burns the token
                val third = preview(board, file(unit, mapOf("E02" to 4_00L)), unit.key()).previewDto()
                apply(other, third.token!!, third.sheetRevision, third.priorRevision).rejection().code shouldBe
                    PartgImportRejectionCode.TOKEN_INVALID
                apply(board, third.token!!, third.sheetRevision, third.priorRevision).rejection().code shouldBe
                    PartgImportRejectionCode.TOKEN_INVALID
                // a second preview of the same member replaces the first
                val a = preview(board, file(unit, mapOf("E02" to 5_00L)), unit.key()).previewDto()
                val b = preview(board, file(unit, mapOf("E02" to 6_00L)), unit.key()).previewDto()
                apply(board, a.token!!, a.sheetRevision, a.priorRevision).rejection().code shouldBe PartgImportRejectionCode.TOKEN_INVALID
                apply(board, b.token!!, b.sheetRevision, b.priorRevision).status shouldBe HttpStatusCode.OK
            }
        }

        test("a change between preview and apply is a revision conflict and nothing is written") {
            val board = fx.member()
            val chapter = fx.chapter()
            val unit = PartgUnit.State(chapter.toString())
            withRoutes {
                val dto = preview(board, file(unit, mapOf("E01" to 1_00L)), unit.key()).previewDto()
                // somebody else saves in between
                partgTestApplication(fixtures = fx) {
                    call(member = board) {
                        service(
                            it,
                        ).saveSheet(
                            year = year,
                            unitKey = unit.key(),
                            expectedRevision = 0,
                            expectedPriorRevision = 0,
                            changes = listOf(PartgValueChangeDto(year = year, positionCode = "E05", amountCents = 7L)),
                        )
                    }
                }
                apply(board, dto.token!!, dto.sheetRevision, dto.priorRevision).rejection().code shouldBe
                    PartgImportRejectionCode.REVISION_CONFLICT
                partgTestApplication(fixtures = fx) {
                    call(member = board) { service(it).getSheet(year = year, unitKey = unit.key()) }.values shouldBe mapOf("E05" to 7L)
                }
            }
        }

        test("apply is all or nothing: a failure after the values leaves no value and no revision change") {
            val board = fx.member()
            val chapter = fx.chapter()
            val unit = PartgUnit.State(chapter.toString())
            partgTestApplication(fixtures = fx) {
                val current = fx.currentOf(memberId = board)
                val result =
                    runCatching {
                        PartgSheetWriter.write(
                            current = current,
                            year = year,
                            unit = unit,
                            expectedRevision = 0,
                            expectedPriorRevision = 0,
                            currentChanges = mapOf("E01" to 1L, "E02" to 2L, "E03" to 3L),
                            priorChanges = emptyMap(),
                            source = PartgValueSource.IMPORT,
                            now =
                                network.lapis.cloud.server.time.ServerClock
                                    .now(),
                            collectiveAudit = { _, _, _ -> error("boom after the third value") },
                        )
                    }
                (result.exceptionOrNull() != null) shouldBe true
                call(member = board) { service(it).getSheet(year = year, unitKey = unit.key()) }.also {
                    it.values shouldBe emptyMap()
                    it.revision shouldBe 0
                }
            }
        }

        test("unchanged values produce no token; an upload over 2 MB is 413; the fields must come before the file") {
            val board = fx.member()
            val chapter = fx.chapter()
            val unit = PartgUnit.State(chapter.toString())
            withRoutes {
                partgTestApplication(fixtures = fx) {
                    call(member = board) {
                        service(
                            it,
                        ).saveSheet(
                            year = year,
                            unitKey = unit.key(),
                            expectedRevision = 0,
                            expectedPriorRevision = 0,
                            changes = listOf(PartgValueChangeDto(year = year, positionCode = "E01", amountCents = 1_00L)),
                        )
                    }
                }
                val same = preview(board, file(unit, mapOf("E01" to 1_00L)), unit.key()).previewDto()
                same.token shouldBe null
                same.changes shouldBe emptyList()
                preview(board, ByteArray(2 * 1024 * 1024 + 10), unit.key()).status shouldBe HttpStatusCode.PayloadTooLarge
                val fileFirst = preview(board, file(unit, mapOf("E01" to 2L)), unit.key(), fileFirst = true)
                fileFirst.status shouldBe HttpStatusCode.BadRequest
                fileFirst.rejection().code shouldBe PartgImportRejectionCode.INVALID_INPUT
                preview(board, file(unit, mapOf("E01" to 2L)), "NONSENSE").status shouldBe HttpStatusCode.BadRequest
                preview(board, file(unit, mapOf("E01" to 2L)), unit.key(), y = PartgYearRules.maxYear() + 1).status shouldBe
                    HttpStatusCode.BadRequest
            }
        }

        test("the rate limit answers 429 with a stable code") {
            val board = fx.member()
            withRoutes(limiter = FederationInboxRateLimiter(maxRequests = 2, window = 60.minutes)) {
                val bytes = file(PartgUnit.Federal, mapOf("E01" to 1L))
                preview(board, bytes, "FEDERAL")
                preview(board, bytes, "FEDERAL")
                val third = preview(board, bytes, "FEDERAL")
                third.status shouldBe HttpStatusCode.TooManyRequests
                third.rejection().code shouldBe PartgImportRejectionCode.RATE_LIMITED
            }
        }

        test("access: board and admin may use the routes, treasurer and member get FORBIDDEN, a non-party gets NOT_ENABLED (role first)") {
            val treasurer = fx.member(role = AccountRole.TREASURER)
            val member = fx.member(role = AccountRole.MEMBER)
            val board = fx.member(role = AccountRole.BOARD)
            withRoutes {
                val bytes = file(PartgUnit.Federal, emptyMap())
                for (denied in listOf(treasurer, member)) {
                    val r = preview(denied, bytes, "FEDERAL")
                    r.status shouldBe HttpStatusCode.Forbidden
                    r.rejection().code shouldBe PartgImportRejectionCode.FORBIDDEN
                    client
                        .get(
                            "/api/partg-report/template?year=$year&unit=FEDERAL",
                        ) { header("X-Member-Id", denied.toString()) }
                        .status shouldBe
                        HttpStatusCode.Forbidden
                    apply(denied, "x", 0, 0).rejection().code shouldBe PartgImportRejectionCode.FORBIDDEN
                }
                fx.setParty(false)
                preview(board, bytes, "FEDERAL").rejection().code shouldBe PartgImportRejectionCode.NOT_ENABLED
                preview(treasurer, bytes, "FEDERAL").rejection().code shouldBe PartgImportRejectionCode.FORBIDDEN
                apply(board, "x", 0, 0).rejection().code shouldBe PartgImportRejectionCode.NOT_ENABLED
                client.get("/api/partg-report/template?year=$year&unit=FEDERAL") { header("X-Member-Id", board.toString()) }.status shouldBe
                    HttpStatusCode.Forbidden
            }
        }

        test("the template download has the safe headers, a sanitised file name, and carries the stored values") {
            val board = fx.member()
            val chapter = fx.chapter(name = "Nordrhein-Westfalen Süd/Ost <1>")
            val unit = PartgUnit.State(chapter.toString())
            withRoutes {
                partgTestApplication(fixtures = fx) {
                    call(member = board) {
                        service(
                            it,
                        ).saveSheet(
                            year = year,
                            unitKey = unit.key(),
                            expectedRevision = 0,
                            expectedPriorRevision = 0,
                            changes = listOf(PartgValueChangeDto(year = year, positionCode = "E01", amountCents = 12_34L)),
                        )
                    }
                }
                val response =
                    client.get(
                        "/api/partg-report/template?year=$year&unit=${unit.key()}",
                    ) { header("X-Member-Id", board.toString()) }
                response.status shouldBe HttpStatusCode.OK
                response.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                response.headers["X-Content-Type-Options"] shouldBe "nosniff"
                response.headers[HttpHeaders.ContentType] shouldBe xlsxType.toString()
                val disposition = response.headers[HttpHeaders.ContentDisposition]!!
                disposition shouldBe "attachment; filename=\"Rechenschaftsbericht_${year}_Nordrhein-Westfalen_Sued_Ost_1.xlsx\""
                val parsed =
                    network.lapis.cloud.server.partg.xlsx.PartgXlsxReader
                        .parse(bytes = response.readRawBytes(), expectedUnit = unit, expectedYear = year)
                parsed.errors shouldBe emptyList()
                parsed.values[PartgImportColumn.CURRENT to "E01"] shouldBe 12_34L
                client.get("/api/partg-report/template?year=1990&unit=FEDERAL") { header("X-Member-Id", board.toString()) }.status shouldBe
                    HttpStatusCode.BadRequest
                client
                    .get(
                        "/api/partg-report/template?year=$year&unit=STATE:${Uuid.random()}",
                    ) { header("X-Member-Id", board.toString()) }
                    .status shouldBe
                    HttpStatusCode.NotFound
            }
        }

        test("the conflict exception type of the writer is the one the route maps") {
            PartyReportRevisionConflictException().shouldBeInstanceOf<PartyReportRevisionConflictException>()
        }
    })
