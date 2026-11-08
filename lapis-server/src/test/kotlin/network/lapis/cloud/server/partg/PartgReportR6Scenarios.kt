package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.PartgReportDeadlineTable
import network.lapis.cloud.server.db.generated.PartgReportDeclarationTable
import network.lapis.cloud.server.db.generated.PartgReportVersionCounterTable
import network.lapis.cloud.server.db.generated.PartgReportVersionTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgDeadlineLevel
import network.lapis.cloud.shared.domain.PartgDraftDeviation
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgValueChangeDto
import network.lapis.cloud.shared.domain.PartgVersionStatus
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import network.lapis.cloud.shared.rpc.PartyReportNotEnabledException
import network.lapis.cloud.shared.rpc.PartyReportVersionDeletionStaleException
import network.lapis.cloud.shared.rpc.PartyReportVersionIntegrityException
import network.lapis.cloud.shared.rpc.PartyReportVersionStateException
import network.lapis.cloud.shared.rpc.PartyReportVersionTooLargeException
import network.lapis.cloud.shared.rpc.PartyReportVersionUnchangedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * V1.9.291 (R6) -- kept versions, the assurance, the internal final mark, deadlines and the deletion routine on H2 AND PostgreSQL with the same
 * assertions: the CHECKs/UNIQUE of `V146__partg_report_r6.sql` (every refused statement in its OWN transaction), the happy path, the refusal of an
 * unchanged keep, the 200 cap, comment rules, the scope, the deviation, tamper (content changed behind the back), the state machine, number
 * continuity across a deletion, the deletion window, access, the audit payloads (no comment, no name) and that no booking is touched.
 *
 * Own report year (2003): the other party-report specs work on other years.
 */
abstract class PartgReportR6Scenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = PartgFixtures()
        val year = 2003
        val federal = PartgUnit.FEDERAL_KEY
        val all = "ALL"

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = true)
        beforeTest { fx.setParty(true) }
        afterTest {
            PartgVersionWriter.beforeWriteHook = {}
            PartgVersionWriter.maxJsonBytes = PartgVersionFormat.MAX_JSON_BYTES
            ServerClock.source = Clock.System
            fx.cleanUp()
        }
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        /** Sets E01 of the federal level for [year]; each call with another amount changes the report. */
        suspend fun PartgApp.setE01(
            member: Uuid,
            cents: Long,
        ) {
            val sheet = call(member = member) { service(it).getSheet(year = year, unitKey = federal) }
            call(member = member) {
                service(it).saveSheet(
                    year = year,
                    unitKey = federal,
                    expectedRevision = sheet.revision,
                    expectedPriorRevision = sheet.priorRevision,
                    changes = listOf(PartgValueChangeDto(year = year, positionCode = "E01", amountCents = cents)),
                )
            }
        }

        suspend fun PartgApp.freeze(
            member: Uuid,
            scope: String = all,
            comment: String? = null,
        ) = call(member = member) { service(it).freezeVersion(year = year, scopeKey = scope, comment = comment) }

        suspend fun PartgApp.overview(member: Uuid) = call(member = member) { service(it).versionOverview(year) }

        fun auditSnapshots(
            type: AuditEntityType,
            actor: Uuid,
        ): List<Pair<AuditAction, String>> =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.entityType eq type }
                    .filter { it[AuditLogEntryTable.actorMemberId] == actor }
                    .map {
                        it[AuditLogEntryTable.action] to
                            (it[AuditLogEntryTable.beforeSnapshot].orEmpty() + it[AuditLogEntryTable.afterSnapshot].orEmpty())
                    }
            }

        /** An old version inserted directly (the retention window needs years that cannot be created through the service). */
        fun insertOldVersion(
            by: Uuid,
            reportYear: Int,
            keptAt: LocalDateTime,
            scope: String = all,
            number: Int = 1,
            finalized: Boolean = false,
        ): Uuid =
            transaction {
                val counter = PartgVersionStore.lockCounter(year = reportYear, scopeKey = scope, actor = by, now = keptAt)
                val json = "{\"old\":$number}"
                val id =
                    PartgVersionStore.insertVersion(
                        counter = counter,
                        number = number,
                        formatVersion = PartgVersionFormat.CURRENT,
                        contentJson = json,
                        contentHash = PartgVersionFormat.hash(json),
                        byteSize = json.length,
                        hasThirdPartyData = false,
                        comment = null,
                        actor = by,
                        now = keptAt,
                    )
                PartgVersionStore.bumpCounter(counter = counter, number = number, actor = by, now = keptAt)
                if (finalized) PartgVersionStore.markFinal(id = id, actor = by, now = keptAt)
                id
            }

        // ------------------------------------------------------------------ the schema

        test("V146: the CHECKs and the unique index refuse bad rows, each in its own transaction") {
            val member = fx.member()
            val now = LocalDateTime(2026, 1, 1, 0, 0)
            val counterId = Uuid.random()
            transaction {
                PartgReportVersionCounterTable.insert {
                    it[id] = counterId
                    it[fiscalYear] = year
                    it[scopeKey] = "CHK"
                    it[lastNumber] = 0
                    it[updatedAt] = now
                    it[updatedBy] = member
                }
            }

            fun versionRow(
                number: Int = 1,
                hash: String = "a".repeat(64),
                size: Int = 10,
                comment: String? = null,
                finalizedAt: LocalDateTime? = null,
                finalizedBy: Uuid? = null,
            ) = transaction {
                PartgReportVersionTable.insert {
                    it[id] = Uuid.random()
                    it[PartgReportVersionTable.counterId] = counterId
                    it[fiscalYear] = year
                    it[scopeKey] = "CHK"
                    it[versionNumber] = number
                    it[formatVersion] = 1
                    it[contentJson] = "{}"
                    it[contentHash] = hash
                    it[byteSize] = size
                    it[hasThirdPartyData] = false
                    it[PartgReportVersionTable.comment] = comment
                    it[frozenAt] = now
                    it[frozenBy] = member
                    it[PartgReportVersionTable.finalizedAt] = finalizedAt
                    it[PartgReportVersionTable.finalizedBy] = finalizedBy
                }
            }
            versionRow(number = 1)
            val refused: List<() -> Unit> =
                listOf(
                    { versionRow(number = 1) },
                    { versionRow(number = 0) },
                    { versionRow(number = 2, hash = "a".repeat(63)) },
                    { versionRow(number = 2, size = 0) },
                    { versionRow(number = 2, size = 4_194_305) },
                    { versionRow(number = 2, comment = "") },
                    { versionRow(number = 2, comment = "x".repeat(501)) },
                    { versionRow(number = 2, finalizedAt = now) },
                    { versionRow(number = 2, finalizedBy = member) },
                )
            refused.forEach { attempt -> runCatching { attempt() }.isFailure shouldBe true }
            versionRow(number = 2, comment = "x".repeat(500), finalizedAt = now, finalizedBy = member)

            // the deadline range: the year of the date lies 1..3 years after the report year
            fun deadlineRow(
                reportYear: Int,
                date: LocalDate,
            ) = transaction {
                PartgReportDeadlineTable.insert {
                    it[id] = Uuid.random()
                    it[fiscalYear] = reportYear
                    it[deadline] = date
                    it[confirmedAt] = now
                    it[confirmedBy] = member
                }
            }
            runCatching { deadlineRow(year, LocalDate(year, 12, 31)) }.isFailure shouldBe true
            runCatching { deadlineRow(year, LocalDate(year + 4, 1, 1)) }.isFailure shouldBe true
            deadlineRow(year, LocalDate(year + 3, 12, 31))
            runCatching { deadlineRow(year, LocalDate(year + 1, 1, 1)) }.isFailure shouldBe true // unique per year
        }

        test("V146: the audit CHECK knows the three new entity types and still the old ones") {
            val member = fx.member()
            val types =
                listOf(
                    AuditEntityType.PARTG_REPORT_VERSION,
                    AuditEntityType.PARTG_REPORT_DECLARATION,
                    AuditEntityType.PARTG_REPORT_DEADLINE,
                    AuditEntityType.PARTG_DONOR_COMBINATION,
                    AuditEntityType.PARTG_REPORT_EXPORT,
                )
            types.forEach { type ->
                transaction {
                    AuditLogRecorder.record(
                        actorMemberId = member,
                        actorRole = AccountRole.BOARD,
                        entityType = type,
                        entityId = Uuid.random(),
                        action = AuditAction.CREATE,
                        after = "{}",
                    )
                }
            }
            types.forEach { type -> auditSnapshots(type, member).size shouldBe 1 }
        }

        // ------------------------------------------------------------------ keeping versions

        test("keeping: numbers 1, 2, 3 without a gap, newest first in the list; an unchanged report is refused") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                setE01(board, 100_00L)
                val v1 = freeze(board, comment = "  Erster Stand  ")
                v1.versionNumber shouldBe 1
                v1.comment shouldBe "Erster Stand"
                v1.status shouldBe PartgVersionStatus.FROZEN
                v1.scopeKey shouldBe all
                v1.contentHash.length shouldBe 64
                v1.frozenByName shouldBe "Partg-Test m"
                attempt(member = board) { service(it).freezeVersion(year = year, scopeKey = all, comment = null) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportVersionUnchangedException>()
                setE01(board, 200_00L)
                val v2 = freeze(board)
                setE01(board, 300_00L)
                val v3 = freeze(board)
                listOf(v2.versionNumber, v3.versionNumber) shouldBe listOf(2, 3)
                overview(board).versions.map { it.versionNumber } shouldBe listOf(3, 2, 1)
                overview(board).yearStatus shouldBe PartgVersionStatus.FROZEN
                // the same content as version 2 but not the NEWEST: a new version is allowed (the rule compares with the newest only)
                setE01(board, 200_00L)
                freeze(board).versionNumber shouldBe 4
            }
        }

        test("a unit scope is kept on its own counter and does not change the year status") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                setE01(board, 100_00L)
                val unit = freeze(board, scope = federal)
                unit.versionNumber shouldBe 1
                unit.scopeKey shouldBe federal
                freeze(board).versionNumber shouldBe 1
                overview(board).yearStatus shouldBe PartgVersionStatus.FROZEN
            }
        }

        test("comment rules and scope validation") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                setE01(board, 100_00L)
                for (bad in listOf("a".repeat(501), "zwei\nZeilen", "tab\there")) {
                    attempt(member = board) { service(it).freezeVersion(year = year, scopeKey = all, comment = bad) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<PartyReportInvalidInputException>()
                }
                for (badScope in listOf("", "all", "STATE", "FEDERAL ", "STATE:not-a-uuid")) {
                    attempt(member = board) { service(it).freezeVersion(year = year, scopeKey = badScope, comment = null) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<PartyReportInvalidInputException>()
                }
                // well formed but not a unit of this installation
                attempt(member = board) {
                    service(it).freezeVersion(year = year, scopeKey = PartgUnit.State(Uuid.random().toString()).key(), comment = null)
                }.exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
                attempt(member = board) { service(it).freezeVersion(year = 1999, scopeKey = all, comment = null) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportInvalidInputException>()
                transaction { PartgReportVersionTable.selectAll().where { PartgReportVersionTable.frozenBy eq board }.count() } shouldBe 0
            }
        }

        test("a report above the size budget is refused and nothing is stored") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                setE01(board, 100_00L)
                PartgVersionWriter.maxJsonBytes = 1_000
                attempt(member = board) { service(it).freezeVersion(year = year, scopeKey = all, comment = null) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportVersionTooLargeException>()
                transaction { PartgReportVersionTable.selectAll().where { PartgReportVersionTable.frozenBy eq board }.count() } shouldBe 0
            }
        }

        test("at most 200 versions per year and scope") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                setE01(board, 100_00L)
                repeat(199) { insertOldVersion(by = board, reportYear = year, keptAt = LocalDateTime(2026, 1, 1, 0, 0), number = it + 1) }
                freeze(board).versionNumber shouldBe 200
                setE01(board, 200_00L)
                attempt(member = board) { service(it).freezeVersion(year = year, scopeKey = all, comment = null) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportInvalidInputException>()
            }
        }

        test("the deviation: SAME after keeping, DIFFERS after a change, SAME after keeping again, NOT_CHECKED without a version") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                overview(board).draftDeviation shouldBe PartgDraftDeviation.NOT_CHECKED
                setE01(board, 100_00L)
                freeze(board)
                overview(board).draftDeviation shouldBe PartgDraftDeviation.SAME
                setE01(board, 200_00L)
                overview(board).draftDeviation shouldBe PartgDraftDeviation.DIFFERS
                freeze(board)
                overview(board).draftDeviation shouldBe PartgDraftDeviation.SAME
            }
        }

        test("two keeps of different states in parallel get two different numbers without a gap") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                setE01(board, 100_00L)
                val reached = CountDownLatch(1)
                val proceed = CountDownLatch(1)
                var first = true
                PartgVersionWriter.beforeWriteHook = {
                    val mine = synchronized(this) { first.also { first = false } }
                    if (mine) {
                        reached.countDown()
                        proceed.await(30, TimeUnit.SECONDS)
                    }
                }
                coroutineScope {
                    // thread 1 has built the document of the old state and waits; meanwhile the state changes and another keep completes
                    val slow = async(kotlinx.coroutines.Dispatchers.IO) { freeze(board) }
                    reached.await(30, TimeUnit.SECONDS)
                    setE01(board, 200_00L)
                    val fast = async(kotlinx.coroutines.Dispatchers.IO) { freeze(board) }
                    val fastVersion = fast.await()
                    proceed.countDown()
                    val numbers = listOf(slow.await().versionNumber, fastVersion.versionNumber)
                    numbers.sorted() shouldBe listOf(1, 2)
                }
                overview(board).versions.map { it.versionNumber } shouldContainExactly listOf(2, 1)
            }
        }

        // ------------------------------------------------------------------ declaration and finalisation

        test("the whole path: keep, declare, finalise; the status and the deadline follow") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                setE01(board, 100_00L)
                val v = freeze(board)
                val declared =
                    call(
                        member = board,
                    ) { service(it).declareVersion(versionId = v.id, expectedContentHash = v.contentHash, reviewed = true) }
                declared.status shouldBe PartgVersionStatus.DECLARED
                declared.declarationTemplateCode shouldBe "ENTWURF-V1"
                declared.declaredByName shouldBe "Partg-Test m"
                overview(board).yearStatus shouldBe PartgVersionStatus.DECLARED
                val final =
                    call(
                        member = board,
                    ) { service(it).finalizeVersion(versionId = v.id, typedVersionNumber = 1, expectedContentHash = v.contentHash) }
                final.status shouldBe PartgVersionStatus.FINAL
                final.finalizedByName shouldBe "Partg-Test m"
                val overview = overview(board)
                overview.yearStatus shouldBe PartgVersionStatus.FINAL
                overview.deadline.level shouldBe PartgDeadlineLevel.NONE
                // audits: kept (CREATE), declared (CREATE), finalised (UPDATE)
                auditSnapshots(AuditEntityType.PARTG_REPORT_VERSION, board).map { it.first } shouldContainExactly
                    listOf(AuditAction.CREATE, AuditAction.UPDATE)
                auditSnapshots(AuditEntityType.PARTG_REPORT_DECLARATION, board).size shouldBe 1
            }
        }

        test("the state machine refuses every wrong step") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                setE01(board, 100_00L)
                val v = freeze(board)
                val unit = freeze(board, scope = federal)

                // not reviewed, wrong checksum, a unit version
                attempt(
                    member = board,
                ) { service(it).declareVersion(versionId = v.id, expectedContentHash = v.contentHash, reviewed = false) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportVersionStateException>()
                attempt(
                    member = board,
                ) { service(it).declareVersion(versionId = v.id, expectedContentHash = "b".repeat(64), reviewed = true) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportVersionStateException>()
                attempt(
                    member = board,
                ) { service(it).declareVersion(versionId = unit.id, expectedContentHash = unit.contentHash, reviewed = true) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportVersionStateException>()
                // finalise before the declaration
                attempt(
                    member = board,
                ) { service(it).finalizeVersion(versionId = v.id, typedVersionNumber = 1, expectedContentHash = v.contentHash) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportVersionStateException>()
                call(member = board) { service(it).declareVersion(versionId = v.id, expectedContentHash = v.contentHash, reviewed = true) }
                // twice
                attempt(
                    member = board,
                ) { service(it).declareVersion(versionId = v.id, expectedContentHash = v.contentHash, reviewed = true) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportVersionStateException>()
                // wrong typed number, wrong checksum
                attempt(
                    member = board,
                ) { service(it).finalizeVersion(versionId = v.id, typedVersionNumber = 2, expectedContentHash = v.contentHash) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportInvalidInputException>()
                attempt(
                    member = board,
                ) { service(it).finalizeVersion(versionId = v.id, typedVersionNumber = 1, expectedContentHash = "b".repeat(64)) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportVersionStateException>()
                call(
                    member = board,
                ) { service(it).finalizeVersion(versionId = v.id, typedVersionNumber = 1, expectedContentHash = v.contentHash) }
                attempt(
                    member = board,
                ) { service(it).finalizeVersion(versionId = v.id, typedVersionNumber = 1, expectedContentHash = v.contentHash) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportVersionStateException>()
                // malformed and unknown ids
                attempt(
                    member = board,
                ) { service(it).declareVersion(versionId = "nope", expectedContentHash = v.contentHash, reviewed = true) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportInvalidInputException>()
                attempt(member = board) {
                    service(it).declareVersion(versionId = Uuid.random().toString(), expectedContentHash = v.contentHash, reviewed = true)
                }.exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
            }
        }

        test("tamper: content changed behind the back makes declare and finalise fail with the integrity error, nothing is written") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                setE01(board, 100_00L)
                val v = freeze(board)
                transaction {
                    PartgReportVersionTable.update({ PartgReportVersionTable.id eq Uuid.parse(v.id) }) {
                        it[contentJson] = "{\"tampered\":true}"
                    }
                }
                attempt(
                    member = board,
                ) { service(it).declareVersion(versionId = v.id, expectedContentHash = v.contentHash, reviewed = true) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportVersionIntegrityException>()
                transaction {
                    PartgReportDeclarationTable.selectAll().where { PartgReportDeclarationTable.declaredBy eq board }.count()
                } shouldBe
                    0
                attempt(member = board) { service(it).compareVersions(versionIdA = v.id, versionIdB = v.id) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportVersionIntegrityException>()
            }
        }

        test("compare: a changed figure shows up as CHANGED and equal versions have no cell; unknown id is 404") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                setE01(board, 100_00L)
                val a = freeze(board)
                setE01(board, 150_00L)
                val b = freeze(board)
                val diff = call(member = board) { service(it).compareVersions(versionIdA = a.id, versionIdB = b.id) }
                diff.comparable shouldBe true
                val cell =
                    diff.rows
                        .single { it.rowKey == federal }
                        .cells
                        .single { it.code == "E01" }
                cell.deltaCents shouldBe 50_00L
                call(
                    member = board,
                ) { service(it).compareVersions(versionIdA = a.id, versionIdB = a.id) }.rows.flatMap { it.cells }.shouldBeEmpty()
                attempt(member = board) { service(it).compareVersions(versionIdA = a.id, versionIdB = Uuid.random().toString()) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
            }
        }

        // ------------------------------------------------------------------ deadlines

        test("deadline: a proposal without a row, confirm, change; bad input is refused") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                val proposal = overview(board).deadline
                proposal.confirmed shouldBe false
                proposal.deadline shouldBe LocalDate(year + 1, 9, 30)
                val confirmed = call(member = board) { service(it).confirmDeadline(year = year, deadline = "${year + 1}-12-15") }
                confirmed.confirmed shouldBe true
                confirmed.deadline shouldBe LocalDate(year + 1, 12, 15)
                call(member = board) { service(it).confirmDeadline(year = year, deadline = "${year + 2}-01-31") }.deadline shouldBe
                    LocalDate(year + 2, 1, 31)
                overview(board).deadline.deadline shouldBe LocalDate(year + 2, 1, 31)
                for (bad in listOf("31.12.${year + 1}", "${year + 1}-13-01", "${year + 1}-02-30", " ${year + 1}-12-15", "")) {
                    attempt(member = board) { service(it).confirmDeadline(year = year, deadline = bad) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<PartyReportInvalidInputException>()
                }
                attempt(member = board) { service(it).confirmDeadline(year = year, deadline = "$year-12-31") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportInvalidInputException>()
                attempt(member = board) { service(it).confirmDeadline(year = year, deadline = "${year + 4}-01-01") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportInvalidInputException>()
                transaction {
                    PartgReportDeadlineTable.selectAll().where { PartgReportDeadlineTable.confirmedBy eq board }.count()
                } shouldBe
                    1
                auditSnapshots(AuditEntityType.PARTG_REPORT_DEADLINE, board).size shouldBe 2
            }
        }

        test("an overdue deadline is DANGER, a final year is quiet") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                // the clock stands in the year after the deadline
                ServerClock.source =
                    object : Clock {
                        override fun now(): Instant = Instant.parse("${year + 2}-03-01T10:00:00Z")
                    }
                overview(board).deadline.level shouldBe PartgDeadlineLevel.DANGER
                (overview(board).deadline.daysUntil < 0) shouldBe true
            }
        }

        // ------------------------------------------------------------------ the deletion routine

        test("deletion: only an ADMIN, only 10..30 years, both years must be past; the preview lists exactly what is deleted") {
            val admin = fx.member(role = AccountRole.ADMIN)
            val board = fx.member()
            val old = LocalDateTime(2003, 6, 1, 0, 0)
            val a = insertOldVersion(by = admin, reportYear = 2003, keptAt = old, scope = all, number = 1)
            // report year old but kept recently: not eligible
            val b = insertOldVersion(by = admin, reportYear = 2003, keptAt = LocalDateTime(2010, 1, 1, 0, 0), scope = federal, number = 1)
            // exactly N years back: not eligible
            val c = insertOldVersion(by = admin, reportYear = 2004, keptAt = old, scope = all, number = 1, finalized = true)
            partgTestApplication(fixtures = fx) {
                ServerClock.source =
                    object : Clock {
                        override fun now(): Instant = Instant.parse("2014-01-15T10:00:00Z")
                    }
                attempt(
                    member = board,
                ) { service(it).previewVersionDeletion(10) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                attempt(member = board) { service(it).deleteOldVersions(olderThanYears = 10, expectedVersionIds = listOf(a.toString())) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ForbiddenException>()
                for (n in listOf(9, 31)) {
                    attempt(member = admin) { service(it).previewVersionDeletion(n) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<PartyReportInvalidInputException>()
                    attempt(member = admin) { service(it).deleteOldVersions(olderThanYears = n, expectedVersionIds = emptyList()) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<PartyReportInvalidInputException>()
                }
                val preview = call(member = admin) { service(it).previewVersionDeletion(10) }
                preview.candidates.map { it.versionId } shouldContainExactly listOf(a.toString())
                // a stale list
                attempt(
                    member = admin,
                ) { service(it).deleteOldVersions(olderThanYears = 10, expectedVersionIds = listOf(a.toString(), b.toString())) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportVersionDeletionStaleException>()
                attempt(member = admin) { service(it).deleteOldVersions(olderThanYears = 10, expectedVersionIds = emptyList()) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportVersionDeletionStaleException>()
                call(
                    member = admin,
                ) { service(it).deleteOldVersions(olderThanYears = 10, expectedVersionIds = listOf(a.toString())) } shouldBe
                    1
                transaction {
                    PartgReportVersionTable
                        .selectAll()
                        .where { PartgReportVersionTable.id inList listOf(a, b, c) }
                        .map { it[PartgReportVersionTable.id] }
                        .toSet()
                } shouldBe setOf(b, c)
                // ONE audit entry with the count and the id, no content
                val audits = auditSnapshots(AuditEntityType.PARTG_REPORT_VERSION, admin).filter { it.first == AuditAction.VOID }
                audits.size shouldBe 1
                audits.single().second shouldBe "{\"olderThanYears\":10,\"count\":1,\"versionIds\":[\"$a\"]}"
                // the counter stays, so the number is never given twice
                transaction {
                    PartgReportVersionCounterTable
                        .selectAll()
                        .where { (PartgReportVersionCounterTable.fiscalYear eq 2003) and (PartgReportVersionCounterTable.scopeKey eq all) }
                        .single()[PartgReportVersionCounterTable.lastNumber]
                } shouldBe 1
                // a year later the next version of the same year/scope is NOT number 1
                val next =
                    transaction { PartgVersionStore.lockCounter(year = 2003, scopeKey = all, actor = admin, now = old).lastNumber + 1 }
                next shouldBe 2
            }
        }

        test("deletion boundary: the day the second condition turns true") {
            val admin = fx.member(role = AccountRole.ADMIN)
            val id = insertOldVersion(by = admin, reportYear = 2003, keptAt = LocalDateTime(2003, 12, 31, 23, 0))
            partgTestApplication(fixtures = fx) {
                fun clockAt(iso: String) {
                    ServerClock.source =
                        object : Clock {
                            override fun now(): Instant = Instant.parse(iso)
                        }
                }
                clockAt("2013-06-01T10:00:00Z")
                call(member = admin) { service(it).previewVersionDeletion(10) }.candidates.shouldBeEmpty()
                clockAt("2014-06-01T10:00:00Z")
                call(member = admin) { service(it).previewVersionDeletion(10) }.candidates.map { it.versionId } shouldBe
                    listOf(id.toString())
                // a longer period holds it back
                call(member = admin) { service(it).previewVersionDeletion(11) }.candidates.shouldBeEmpty()
            }
        }

        // ------------------------------------------------------------------ access, audit, GoBD

        test("access: member, treasurer and chapter treasurer are forbidden on all eight methods; a non-party board gets NOT_ENABLED") {
            val board = fx.member()
            val treasurer = fx.member(role = AccountRole.TREASURER)
            val plain = fx.member(role = AccountRole.MEMBER)
            val chapterTreasurer = fx.member(role = AccountRole.MEMBER)
            fx.grantTreasurer(member = chapterTreasurer, chapter = fx.chapter())
            val id = Uuid.random().toString()
            val hash = "a".repeat(64)
            val ops: List<suspend (network.lapis.cloud.server.rpc.PartyReportService) -> Any?> =
                listOf(
                    { s -> s.versionOverview(year) },
                    { s -> s.freezeVersion(year = year, scopeKey = all, comment = null) },
                    { s -> s.compareVersions(versionIdA = id, versionIdB = id) },
                    { s -> s.declareVersion(versionId = id, expectedContentHash = hash, reviewed = true) },
                    { s -> s.finalizeVersion(versionId = id, typedVersionNumber = 1, expectedContentHash = hash) },
                    { s -> s.confirmDeadline(year = year, deadline = "${year + 1}-12-15") },
                    { s -> s.previewVersionDeletion(10) },
                    { s -> s.deleteOldVersions(olderThanYears = 10, expectedVersionIds = emptyList()) },
                )
            partgTestApplication(fixtures = fx) {
                for (who in listOf(treasurer, plain, chapterTreasurer)) {
                    ops.forEach { op ->
                        attempt(member = who) { op(service(it)) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    }
                }
                fx.setParty(false)
                ops.forEach { op ->
                    attempt(member = board) { op(service(it)) }.exceptionOrNull().shouldBeInstanceOf<PartyReportNotEnabledException>()
                }
                ops.forEach { op -> attempt(member = plain) { op(service(it)) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>() }
            }
        }

        test("the audit entries carry ids, numbers and the checksum, never the comment, a name or an amount") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                setE01(board, 123_456_78L)
                val v = freeze(board, comment = "Max Mustermann hat geprüft")
                call(member = board) { service(it).declareVersion(versionId = v.id, expectedContentHash = v.contentHash, reviewed = true) }
                call(
                    member = board,
                ) { service(it).finalizeVersion(versionId = v.id, typedVersionNumber = 1, expectedContentHash = v.contentHash) }
                call(member = board) { service(it).confirmDeadline(year = year, deadline = "${year + 1}-12-15") }
                val all =
                    listOf(
                        AuditEntityType.PARTG_REPORT_VERSION,
                        AuditEntityType.PARTG_REPORT_DECLARATION,
                        AuditEntityType.PARTG_REPORT_DEADLINE,
                    ).flatMap { auditSnapshots(it, board) }.joinToString(" ") { it.second }
                all shouldNotContain "Mustermann"
                all shouldNotContain "Partg-Test"
                all shouldNotContain "12345678"
                all shouldNotContain "123456.78"
            }
        }

        test("GoBD: no journal entry is touched by anything the versions do") {
            val board = fx.member()

            fun journal() =
                transaction {
                    JournalEntryTable
                        .selectAll()
                        .map { listOf(it[JournalEntryTable.id], it[JournalEntryTable.status], it[JournalEntryTable.description]) }
                        .sortedBy { it[0].toString() }
                }
            val before = journal()
            partgTestApplication(fixtures = fx) {
                setE01(board, 100_00L)
                val v = freeze(board)
                call(member = board) { service(it).declareVersion(versionId = v.id, expectedContentHash = v.contentHash, reviewed = true) }
                call(
                    member = board,
                ) { service(it).finalizeVersion(versionId = v.id, typedVersionNumber = 1, expectedContentHash = v.contentHash) }
                coroutineScope { (1..3).map { n -> async { overview(board).versions.size + n } }.awaitAll() }
            }
            journal() shouldBe before
        }
    })
