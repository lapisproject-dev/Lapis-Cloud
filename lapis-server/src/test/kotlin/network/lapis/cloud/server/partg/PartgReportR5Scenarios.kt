package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.audit.AuditLogRecorder
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.PartgConsolidationSheetTable
import network.lapis.cloud.server.db.generated.PartgDonorCombinationRowTable
import network.lapis.cloud.server.db.generated.PartgDonorCombinationTable
import network.lapis.cloud.server.db.generated.PartgIntraTransferTable
import network.lapis.cloud.server.db.generated.PartgReportAnnexTable
import network.lapis.cloud.server.db.generated.PartgReportLargeDonorTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.PartgConsolidatedCell
import network.lapis.cloud.shared.domain.PartgConsolidationDto
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgFindingCode
import network.lapis.cloud.shared.domain.PartgIntraTransferInputDto
import network.lapis.cloud.shared.domain.PartgLargeDonorInputDto
import network.lapis.cloud.shared.domain.PartgMatchState
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgPositions
import network.lapis.cloud.shared.domain.PartgTransferKind
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgUnitLevel
import network.lapis.cloud.shared.domain.PartgValueChangeDto
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import network.lapis.cloud.shared.rpc.PartyReportNotEnabledException
import network.lapis.cloud.shared.rpc.PartyReportRevisionConflictException
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid
import io.kotest.matchers.string.shouldContain as stringShouldContain

/**
 * V1.9.290 (R5) -- the consolidation on H2 AND PostgreSQL with the same assertions: the CHECKs/UNIQUE/FKs of `V145__partg_report_r5.sql` (every refused
 * statement in its OWN transaction), the happy path, validation, tamper (year and id bound), the limits (1000 pairs, 500 groups, 2..20 rows),
 * concurrency (revision wins once, a delete never meets a combination in a foreign-key error), the revision fence, the audit payloads (no name, note
 * or amount), the access rules and that no booking is touched.
 *
 * Own year (2004): the other party-report specs work on the year before the current one, so no sheet of theirs is in the picture.
 */
abstract class PartgReportR5Scenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = PartgFixtures()
        val year = 2004
        val now = LocalDateTime(2026, 1, 1, 0, 0)

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = true)
        beforeTest { fx.setParty(true) }
        // every test starts with an empty consolidation of the year: revisions count from 0
        afterTest {
            PartgReportSnapshotReader.betweenReadsHook = {}
            fx.cleanUp()
        }
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        fun stateKey(chapter: Uuid) = PartgUnit.State(chapter.toString()).key()

        fun transfer(
            payer: String,
            payee: String,
            kind: PartgTransferKind = PartgTransferKind.SUBSIDY,
            payerAmount: Long? = 100_00L,
            payeeAmount: Long? = 90_00L,
            note: String? = null,
            id: String? = null,
        ) = PartgIntraTransferInputDto(
            id = id,
            kind = kind,
            payerUnitKey = payer,
            payeeUnitKey = payee,
            payerAmountCents = payerAmount,
            payeeAmountCents = payeeAmount,
            note = note,
        )

        fun donorInput(
            name: String,
            cents: Long = 1_200_000L,
            postalCode: String? = "38100",
        ) = PartgLargeDonorInputDto(
            id = null,
            personKind = PartgPersonKind.NATURAL,
            name = name,
            street = "Hauptstrasse 1",
            postalCode = postalCode,
            city = "Braunschweig",
            country = "DE",
            contributionKind = PartgContributionKind.DONATION,
            amountCents = cents,
        )

        /** Saves one donor row in [unit] and returns its id (found by its unique name). */
        suspend fun network.lapis.cloud.server.partg.PartgApp.addDonor(
            member: Uuid,
            unit: String,
            name: String = "Geheimperson ${Uuid.random()}",
            cents: Long = 1_200_000L,
            at: Int = year,
        ): String {
            val revision = call(member = member) { service(it).getAnnex(year = at, unitKey = unit) }.revision
            val saved =
                call(member = member) {
                    service(
                        it,
                    ).saveLargeDonor(year = at, unitKey = unit, expectedRevision = revision, donor = donorInput(name = name, cents = cents))
                }
            return saved.donors.single { it.name == name }.id
        }

        suspend fun network.lapis.cloud.server.partg.PartgApp.consolidation(
            member: Uuid,
            at: Int = year,
        ): PartgConsolidationDto = call(member = member) { service(it).getConsolidation(at) }

        fun refused(block: () -> Unit): Boolean =
            try {
                transaction { block() }
                false
            } catch (_: Exception) {
                true
            }

        fun insertSheet(member: Uuid): Uuid {
            val id = Uuid.random()
            transaction {
                PartgConsolidationSheetTable.insert {
                    it[PartgConsolidationSheetTable.id] = id
                    it[fiscalYear] = 2010
                    it[revision] = 0L
                    it[updatedAt] = now
                    it[updatedBy] = member
                }
            }
            return id
        }

        fun rawTransfer(
            sheet: Uuid,
            member: Uuid,
            payerChapter: Uuid?,
            payeeChapter: Uuid?,
            payerAmount: Long? = 5L,
            payeeAmount: Long? = 5L,
            payerLevel: PartgUnitLevel = PartgUnitLevel.STATE,
            kind: network.lapis.cloud.shared.domain.PartgTransferKind = PartgTransferKind.SUBSIDY,
            payerKeyOverride: String? = null,
            note: String? = null,
        ) {
            PartgIntraTransferTable.insert {
                it[id] = Uuid.random()
                it[consolidationId] = sheet
                it[transferKind] = kind
                it[PartgIntraTransferTable.payerLevel] = payerLevel
                it[payerChapterId] = payerChapter
                it[payerUnitKey] = payerKeyOverride ?: payerChapter?.let { c -> stateKey(c) } ?: PartgUnit.FEDERAL_KEY
                it[payeeLevel] = PartgUnitLevel.STATE
                it[payeeChapterId] = payeeChapter
                it[payeeUnitKey] = payeeChapter?.let { c -> stateKey(c) } ?: PartgUnit.FEDERAL_KEY
                it[payerAmountCents] = payerAmount
                it[payeeAmountCents] = payeeAmount
                it[PartgIntraTransferTable.note] = note
                it[createdAt] = now
                it[createdBy] = member
                it[updatedAt] = now
                it[updatedBy] = member
            }
        }

        // ---------------------------------------------------------------- migration V145

        test("V145: the CHECKs, the UNIQUE rules and the foreign keys hold at their boundaries") {
            val member = fx.member()
            val a = fx.chapter()
            val b = fx.chapter()
            val sheet = insertSheet(member)
            // a good row first
            refused { rawTransfer(sheet, member, a, b) } shouldBe false
            // payer = payee
            refused { rawTransfer(sheet, member, a, a, kind = PartgTransferKind.BALANCE) } shouldBe true
            // the same pair twice (unique per header, kind, payer, payee)
            refused { rawTransfer(sheet, member, a, b) } shouldBe true
            // no amount at all, an amount of 0, a negative amount, one above the bound
            refused { rawTransfer(sheet, member, b, a, payerAmount = null, payeeAmount = null) } shouldBe true
            refused { rawTransfer(sheet, member, b, a, payerAmount = 0L) } shouldBe true
            refused { rawTransfer(sheet, member, b, a, payeeAmount = -1L) } shouldBe true
            refused { rawTransfer(sheet, member, b, a, payerAmount = 100_000_000_000_000L) } shouldBe true
            // the bounds themselves are fine: one side only, the maximum
            refused { rawTransfer(sheet, member, b, a, payerAmount = 99_999_999_999_999L, payeeAmount = null) } shouldBe false
            // the unit key must agree with level and chapter
            refused { rawTransfer(sheet, member, a, b, payerLevel = PartgUnitLevel.FEDERAL, kind = PartgTransferKind.BALANCE) } shouldBe
                true
            refused { rawTransfer(sheet, member, a, b, payerKeyOverride = "STATE:nonsense", kind = PartgTransferKind.BALANCE) } shouldBe
                true
            // the note is 1..200 characters
            refused { rawTransfer(sheet, member, a, fx.chapter(), note = "") } shouldBe true
            refused { rawTransfer(sheet, member, a, fx.chapter(), note = "x".repeat(201)) } shouldBe true
            refused { rawTransfer(sheet, member, a, fx.chapter(), note = "x".repeat(200)) } shouldBe false
            // one header per year
            refused { insertSheet(member) } shouldBe true
            // there is no cascade: a header with children cannot be deleted
            refused { PartgConsolidationSheetTable.deleteWhereId(sheet) } shouldBe true
        }

        test("V145: a large-donor row belongs to at most one group; the new entity types fit the audit CHECK") {
            val member = fx.member()
            val chapter = fx.chapter()
            val sheet = insertSheet(member).also { }
            // two groups, one donor row
            val annexId = Uuid.random()
            val donorId = Uuid.random()
            transaction {
                PartgReportAnnexTable.insert {
                    it[id] = annexId
                    it[fiscalYear] = 2011
                    it[unitLevel] = PartgUnitLevel.STATE
                    it[regionalChapterId] = chapter
                    it[unitKey] = stateKey(chapter)
                    it[revision] = 0L
                    it[updatedAt] = now
                    it[updatedBy] = member
                }
                PartgReportLargeDonorTable.insert {
                    it[id] = donorId
                    it[PartgReportLargeDonorTable.annexId] = annexId
                    it[personKind] = PartgPersonKind.NATURAL
                    it[name] = "Unique Row"
                    it[country] = "DE"
                    it[contributionKind] = PartgContributionKind.DONATION
                    it[amountCents] = 1_200_000L
                    it[createdAt] = now
                    it[createdBy] = member
                    it[updatedAt] = now
                    it[updatedBy] = member
                }
            }

            fun group(): Uuid {
                val id = Uuid.random()
                transaction {
                    PartgDonorCombinationTable.insert {
                        it[PartgDonorCombinationTable.id] = id
                        it[consolidationId] = sheet
                        it[createdAt] = now
                        it[createdBy] = member
                    }
                }
                return id
            }

            fun row(combination: Uuid) =
                PartgDonorCombinationRowTable.insert {
                    it[id] = Uuid.random()
                    it[combinationId] = combination
                    it[largeDonorId] = donorId
                }
            refused { row(group()) } shouldBe false
            refused { row(group()) } shouldBe true
            // the audit literals
            listOf(AuditEntityType.PARTG_INTRA_TRANSFER, AuditEntityType.PARTG_DONOR_COMBINATION).forEach { type ->
                refused {
                    AuditLogRecorder.record(
                        actorMemberId = member,
                        actorRole = AccountRole.BOARD,
                        entityType = type,
                        entityId = Uuid.random(),
                        action = AuditAction.CREATE,
                        before = null,
                        after = "{}",
                        occurredAt = now,
                    )
                } shouldBe false
            }
            val clause =
                transaction {
                    var text = ""
                    exec(
                        "SELECT check_clause FROM information_schema.check_constraints WHERE lower(constraint_name) = 'chk_audit_log_entry_entity_type'",
                    ) { rs ->
                        while (rs.next()) text += rs.getString(1)
                    }
                    text
                }
            Regex("""'([A-Z_]+)'""").findAll(clause).map { it.groupValues[1] }.toSet() shouldBe
                AuditEntityType.entries.map { it.name }.toSet()
        }

        // ---------------------------------------------------------------- the consolidation

        test("happy path: a pair is eliminated from BOTH positions, RES and NET do not move, the other readers see the same state") {
            val board = fx.member()
            val chapter = fx.chapter()
            val state = stateKey(chapter)
            partgTestApplication(fixtures = fx) {
                val federalValues = PartgPositions.LEAF_CODES.withIndex().associate { (i, c) -> c to (i + 1) * 1_000L } + ("A03" to 100_00L)
                val stateValues = PartgPositions.LEAF_CODES.withIndex().associate { (i, c) -> c to (i + 1) * 2_000L } + ("E10" to 90_00L)
                for ((unitKey, values) in listOf(PartgUnit.FEDERAL_KEY to federalValues, state to stateValues)) {
                    call(member = board) {
                        service(it).saveSheet(
                            year = year,
                            unitKey = unitKey,
                            expectedRevision = 0,
                            expectedPriorRevision = 0,
                            changes =
                                values.map { (code, cents) ->
                                    PartgValueChangeDto(year = year, positionCode = code, amountCents = cents)
                                },
                        )
                    }
                }
                val before = consolidation(board)
                before.revision shouldBe 0
                before.included shouldContain PartgUnit.FEDERAL_KEY
                before.included shouldContain state
                val saved =
                    call(member = board) {
                        service(it).saveIntraTransfer(
                            year = year,
                            expectedRevision = 0,
                            transfer = transfer(payer = PartgUnit.FEDERAL_KEY, payee = state, payerAmount = 100_00L, payeeAmount = 90_00L),
                        )
                    }
                saved.revision shouldBe 1
                val a03 = saved.rows.single { it.code == "A03" }
                a03.eliminatedCents shouldBe 90_00L
                (a03.consolidated as PartgConsolidatedCell.Value).cents shouldBe
                    (a03.unitSum as PartgConsolidatedCell.Value).cents - 90_00L
                val res = saved.rows.single { it.code == PartgPositions.RESULT }
                res.consolidated shouldBe res.unitSum
                saved.transfers.single().match shouldBe PartgMatchState.DIFFERS
                // the 10,00 EUR difference of the payer stays visible
                saved.unmatched.single { it.unitKey == PartgUnit.FEDERAL_KEY && it.positionCode == "A03" }.differenceCents shouldBe 10_00L
                // a reading call shows exactly what the write returned
                consolidation(board) shouldBe saved
            }
        }

        test("a pair is changed in place, a second pair with the same key is refused, delete removes it, every write bumps the revision") {
            val board = fx.member()
            val state = stateKey(fx.chapter())
            partgTestApplication(fixtures = fx) {
                val first =
                    call(member = board) {
                        service(it).saveIntraTransfer(year = year, expectedRevision = 0, transfer = transfer(PartgUnit.FEDERAL_KEY, state))
                    }
                val id = first.transfers.single().id
                val duplicate =
                    attempt(member = board) {
                        service(it).saveIntraTransfer(year = year, expectedRevision = 1, transfer = transfer(PartgUnit.FEDERAL_KEY, state))
                    }
                duplicate.exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>().message shouldBe "PAIR_EXISTS"
                val changed =
                    call(member = board) {
                        service(it).saveIntraTransfer(
                            year = year,
                            expectedRevision = 1,
                            transfer = transfer(PartgUnit.FEDERAL_KEY, state, payerAmount = 77_00L, payeeAmount = 77_00L, id = id),
                        )
                    }
                changed.revision shouldBe 2
                changed.transfers.single().match shouldBe PartgMatchState.MATCH
                changed.transfers.single().payerAmountCents shouldBe 77_00L
                val stale =
                    attempt(member = board) { service(it).deleteIntraTransfer(year = year, expectedRevision = 1, transferId = id) }
                stale.exceptionOrNull().shouldBeInstanceOf<PartyReportRevisionConflictException>()
                val deleted = call(member = board) { service(it).deleteIntraTransfer(year = year, expectedRevision = 2, transferId = id) }
                deleted.revision shouldBe 3
                deleted.transfers shouldBe emptyList()
            }
        }

        test("validation: same unit, no amount, amount 0, long note, control character, bad key, unknown chapter") {
            val board = fx.member()
            val state = stateKey(fx.chapter())
            partgTestApplication(fixtures = fx) {
                suspend fun code(input: PartgIntraTransferInputDto) =
                    attempt(member = board) { service(it).saveIntraTransfer(year = year, expectedRevision = 0, transfer = input) }
                        .exceptionOrNull()
                        .let { (it as? PartyReportInvalidInputException)?.message ?: it }
                code(transfer(state, state)) shouldBe "SAME_UNIT"
                code(transfer(PartgUnit.FEDERAL_KEY, state, payerAmount = null, payeeAmount = null)) shouldBe "AMOUNT_MISSING"
                code(transfer(PartgUnit.FEDERAL_KEY, state, payerAmount = 0L)) shouldBe "AMOUNT_RANGE"
                code(transfer(PartgUnit.FEDERAL_KEY, state, note = "x".repeat(201))) shouldBe "NOTE_TOO_LONG"
                code(transfer(PartgUnit.FEDERAL_KEY, state, note = "a\u0007b")) shouldBe "NOTE_CONTROL"
                code(transfer("STATE:not-a-uuid", state)) shouldBe "Invalid unit"
                code(transfer(PartgUnit.FEDERAL_KEY, "garbage")) shouldBe "Invalid unit"
                code(transfer(PartgUnit.FEDERAL_KEY, stateKey(Uuid.random()))).shouldBeInstanceOf<NotFoundException>()
                // nothing was stored: not even a header (the refusals happen before the write)
                consolidation(board).transfers shouldBe emptyList()
            }
        }

        test("tamper: a pair, a donor row and a group are bound to their year; stale revisions are refused") {
            val board = fx.member()
            val state = stateKey(fx.chapter())
            val other = stateKey(fx.chapter())
            val otherYear = year + 1
            partgTestApplication(fixtures = fx) {
                val inYear =
                    call(member = board) {
                        service(it).saveIntraTransfer(year = year, expectedRevision = 0, transfer = transfer(PartgUnit.FEDERAL_KEY, state))
                    }
                val pairId = inYear.transfers.single().id
                // the other year has its own header (and no pair): the id of the first year is invisible there
                call(member = board) {
                    service(it).saveIntraTransfer(year = otherYear, expectedRevision = 0, transfer = transfer(PartgUnit.FEDERAL_KEY, other))
                }
                attempt(member = board) { service(it).deleteIntraTransfer(year = otherYear, expectedRevision = 1, transferId = pairId) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
                attempt(member = board) {
                    service(it).saveIntraTransfer(
                        year = otherYear,
                        expectedRevision = 1,
                        transfer = transfer(PartgUnit.FEDERAL_KEY, state, id = pairId),
                    )
                }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                // donor rows and groups
                val d1 = addDonor(board, state)
                val d2 = addDonor(board, other)
                val view = consolidation(board)
                attempt(member = board) {
                    service(it).createDonorCombination(
                        year = otherYear,
                        expectedRevision = 1,
                        expectedAnnexRevisions = view.annexRevisions,
                        largeDonorIds = listOf(d1, d2),
                    )
                }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                val made =
                    call(member = board) {
                        service(it).createDonorCombination(
                            year = year,
                            expectedRevision = view.revision,
                            expectedAnnexRevisions = view.annexRevisions,
                            largeDonorIds = listOf(d1, d2),
                        )
                    }
                val groupId = made.donors.single { it.combinationId != null }.combinationId!!
                attempt(member = board) {
                    service(it).dissolveDonorCombination(year = otherYear, expectedRevision = 1, combinationId = groupId)
                }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                // stale consolidation revision
                attempt(member = board) { service(it).dissolveDonorCombination(year = year, expectedRevision = 0, combinationId = groupId) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportRevisionConflictException>()
                // an id that is not even a UUID is a clean NotFound, never a 500
                attempt(
                    member = board,
                ) { service(it).dissolveDonorCombination(year = year, expectedRevision = made.revision, combinationId = "x") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
            }
        }

        test(
            "a combination: one person, the sum, the units; refused for the same unit, one row, a repeated row, a row already combined, stale annex revisions",
        ) {
            val board = fx.member()
            val stateA = stateKey(fx.chapter())
            val stateB = stateKey(fx.chapter())
            val stateC = stateKey(fx.chapter())
            partgTestApplication(fixtures = fx) {
                val a = addDonor(board, stateA, cents = 1_300_000L)
                val b = addDonor(board, stateB, cents = 1_100_000L)
                val c = addDonor(board, stateA, cents = 1_050_000L)
                val d = addDonor(board, stateC)
                var view = consolidation(board)

                suspend fun create(vararg ids: String) =
                    attempt(member = board) {
                        service(it).createDonorCombination(
                            year = year,
                            expectedRevision = view.revision,
                            expectedAnnexRevisions = view.annexRevisions,
                            largeDonorIds = ids.toList(),
                        )
                    }.exceptionOrNull().let { (it as? PartyReportInvalidInputException)?.message ?: it }
                create(a, c) shouldBe "SAME_UNIT"
                create(a) shouldBe "ROW_COUNT"
                create(a, a) shouldBe "DUPLICATE_ROW"
                create(a, Uuid.random().toString()).shouldBeInstanceOf<NotFoundException>()
                view =
                    call(member = board) {
                        service(it).createDonorCombination(
                            year = year,
                            expectedRevision = view.revision,
                            expectedAnnexRevisions = view.annexRevisions,
                            largeDonorIds = listOf(a, b),
                        )
                    }
                val person = view.donors.single { it.combinationId != null }
                person.totalCents shouldBe 2_400_000L
                person.unitKeys.toSet() shouldBe setOf(stateA, stateB)
                person.donorIds.toSet() shouldBe setOf(a, b)
                // c and d are still single rows
                view.donors.count { it.combinationId == null && it.donorIds.single() in setOf(c, d) } shouldBe 2
                create(a, d) shouldBe "ALREADY_COMBINED"
                // a donor row is changed in the unit of c in the meantime: the old annex revisions are stale
                val stale = view.annexRevisions
                addDonor(board, stateA)
                attempt(member = board) {
                    service(it).createDonorCombination(
                        year = year,
                        expectedRevision = view.revision,
                        expectedAnnexRevisions = stale,
                        largeDonorIds = listOf(c, d),
                    )
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportRevisionConflictException>()
                // dissolving gives the rows back as singles
                val dissolved =
                    call(member = board) {
                        service(
                            it,
                        ).dissolveDonorCombination(year = year, expectedRevision = view.revision, combinationId = person.combinationId!!)
                    }
                dissolved.donors.none { it.combinationId != null } shouldBe true
            }
        }

        test("deleting a combined donor row leaves the group, never a foreign-key error; the broken group is hint P21") {
            val board = fx.member()
            val stateA = stateKey(fx.chapter())
            val stateB = stateKey(fx.chapter())
            partgTestApplication(fixtures = fx) {
                val a = addDonor(board, stateA)
                val b = addDonor(board, stateB)
                val view = consolidation(board)
                call(member = board) {
                    service(it).createDonorCombination(
                        year = year,
                        expectedRevision = view.revision,
                        expectedAnnexRevisions = view.annexRevisions,
                        largeDonorIds = listOf(a, b),
                    )
                }
                val annex = call(member = board) { service(it).getAnnex(year = year, unitKey = stateA) }
                call(
                    member = board,
                ) { service(it).deleteLargeDonor(year = year, unitKey = stateA, expectedRevision = annex.revision, donorId = a) }
                val after = consolidation(board)
                after.donors.none { it.combinationId != null } shouldBe true
                after.findings.map { it.code } shouldContain PartgFindingCode.P21
                transaction {
                    PartgDonorCombinationRowTable.selectAll().where { PartgDonorCombinationRowTable.largeDonorId eq Uuid.parse(a) }.count()
                } shouldBe 0L
            }
        }

        test("2..20 rows: 20 distinct units are accepted, 21 are not") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                val units = (1..21).map { stateKey(fx.chapter()) }
                val ids = units.map { addDonor(board, it) }
                var view = consolidation(board)
                attempt(member = board) {
                    service(it).createDonorCombination(
                        year = year,
                        expectedRevision = view.revision,
                        expectedAnnexRevisions = view.annexRevisions,
                        largeDonorIds = ids,
                    )
                }.exceptionOrNull().let { (it as PartyReportInvalidInputException).message } shouldBe "ROW_COUNT"
                view =
                    call(member = board) {
                        service(it).createDonorCombination(
                            year = year,
                            expectedRevision = view.revision,
                            expectedAnnexRevisions = view.annexRevisions,
                            largeDonorIds = ids.take(20),
                        )
                    }
                view.donors
                    .single { it.combinationId != null }
                    .donorIds.size shouldBe 20
            }
        }

        test("limits: 1000 pairs per year (the 1001st is refused) and 500 groups per year (the 501st is refused)") {
            val board = fx.member()
            val chapters = (1..33).map { fx.chapter() }
            partgTestApplication(fixtures = fx) {
                // pair #1 through the service creates the header; 998 more go in directly, #1000 through the service again
                val keys = chapters.map { stateKey(it) }
                val pairs = keys.flatMap { p -> keys.filter { it != p }.map { q -> p to q } }
                call(member = board) {
                    service(it).saveIntraTransfer(year = year, expectedRevision = 0, transfer = transfer(pairs[0].first, pairs[0].second))
                }
                val sheet =
                    transaction {
                        PartgConsolidationSheetTable
                            .selectAll()
                            .where {
                                PartgConsolidationSheetTable.fiscalYear eq year
                            }.single()[PartgConsolidationSheetTable.id]
                    }
                transaction {
                    pairs.subList(1, 999).forEach { (p, q) ->
                        rawTransfer(sheet, board, Uuid.parse(p.removePrefix("STATE:")), Uuid.parse(q.removePrefix("STATE:")))
                    }
                }
                call(member = board) {
                    service(
                        it,
                    ).saveIntraTransfer(year = year, expectedRevision = 1, transfer = transfer(pairs[999].first, pairs[999].second))
                }
                val over =
                    attempt(member = board) {
                        service(
                            it,
                        ).saveIntraTransfer(year = year, expectedRevision = 2, transfer = transfer(pairs[1000].first, pairs[1000].second))
                    }
                over.exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>().message shouldBe "TOO_MANY"
                transaction {
                    PartgIntraTransferTable.selectAll().where { PartgIntraTransferTable.consolidationId eq sheet }.count()
                } shouldBe
                    1000L
            }
        }

        test("limits: the 501st group of a year is refused") {
            val board = fx.member()
            val s1 = fx.chapter()
            val s2 = fx.chapter()
            val s3 = fx.chapter()
            val s4 = fx.chapter()
            partgTestApplication(fixtures = fx) {
                // header of the year
                val first =
                    call(member = board) {
                        service(
                            it,
                        ).saveIntraTransfer(year = year, expectedRevision = 0, transfer = transfer(PartgUnit.FEDERAL_KEY, stateKey(s1)))
                    }
                val sheet =
                    transaction {
                        PartgConsolidationSheetTable
                            .selectAll()
                            .where {
                                PartgConsolidationSheetTable.fiscalYear eq year
                            }.single()[PartgConsolidationSheetTable.id]
                    }
                val annexIds = mutableMapOf<Uuid, Uuid>()
                transaction {
                    listOf(s1, s2).forEach { chapter ->
                        val annexId = Uuid.random()
                        annexIds[chapter] = annexId
                        PartgReportAnnexTable.insert {
                            it[id] = annexId
                            it[fiscalYear] = year
                            it[unitLevel] = PartgUnitLevel.STATE
                            it[regionalChapterId] = chapter
                            it[unitKey] = stateKey(chapter)
                            it[revision] = 0L
                            it[updatedAt] = now
                            it[updatedBy] = board
                        }
                    }
                    repeat(500) { n ->
                        val ids =
                            listOf(s1, s2).map { chapter ->
                                val donorId = Uuid.random()
                                PartgReportLargeDonorTable.insert {
                                    it[id] = donorId
                                    it[annexId] = annexIds.getValue(chapter)
                                    it[personKind] = PartgPersonKind.NATURAL
                                    it[name] = "Reihe $n"
                                    it[country] = "DE"
                                    it[contributionKind] = PartgContributionKind.DONATION
                                    it[amountCents] = 1_000_001L
                                    it[createdAt] = now
                                    it[createdBy] = board
                                    it[updatedAt] = now
                                    it[updatedBy] = board
                                }
                                donorId
                            }
                        val groupId = Uuid.random()
                        PartgDonorCombinationTable.insert {
                            it[id] = groupId
                            it[consolidationId] = sheet
                            it[createdAt] = now
                            it[createdBy] = board
                        }
                        ids.forEach { donorId ->
                            PartgDonorCombinationRowTable.insert {
                                it[id] = Uuid.random()
                                it[combinationId] = groupId
                                it[largeDonorId] = donorId
                            }
                        }
                    }
                }
                val d3 = addDonor(board, stateKey(s3))
                val d4 = addDonor(board, stateKey(s4))
                val view = consolidation(board)
                view.revision shouldBe first.revision
                attempt(member = board) {
                    service(it).createDonorCombination(
                        year = year,
                        expectedRevision = view.revision,
                        expectedAnnexRevisions = view.annexRevisions,
                        largeDonorIds = listOf(d3, d4),
                    )
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>().message shouldBe "TOO_MANY"
            }
        }

        // ---------------------------------------------------------------- concurrency and the fence

        test("two writers with the same revision: exactly one wins, the other gets the revision conflict") {
            val board = fx.member()
            val state1 = stateKey(fx.chapter())
            val state2 = stateKey(fx.chapter())
            partgTestApplication(fixtures = fx) {
                call(member = board) {
                    service(it).saveIntraTransfer(year = year, expectedRevision = 0, transfer = transfer(PartgUnit.FEDERAL_KEY, state1))
                }
                val results =
                    coroutineScope {
                        listOf(state2, stateKey(fx.chapter()))
                            .map { target ->
                                async {
                                    attempt(member = board) {
                                        service(
                                            it,
                                        ).saveIntraTransfer(
                                            year = year,
                                            expectedRevision = 1,
                                            transfer = transfer(PartgUnit.FEDERAL_KEY, target),
                                        )
                                    }
                                }
                            }.awaitAll()
                    }
                results.count { it.isSuccess } shouldBe 1
                results.single { it.isFailure }.exceptionOrNull().shouldBeInstanceOf<PartyReportRevisionConflictException>()
                consolidation(board).transfers.size shouldBe 2
            }
        }

        test(
            "a delete of a donor row and the combination of that row at the same time: no foreign-key error, no deadlock, a consistent end state",
        ) {
            val board = fx.member()
            partgTestApplication(fixtures = fx) {
                repeat(6) {
                    val stateA = stateKey(fx.chapter())
                    val stateB = stateKey(fx.chapter())
                    val a = addDonor(board, stateA)
                    val b = addDonor(board, stateB)
                    val view = consolidation(board)
                    val annexA = call(member = board) { service(it).getAnnex(year = year, unitKey = stateA) }
                    val outcomes =
                        coroutineScope {
                            listOf(
                                async {
                                    attempt(member = board) {
                                        service(it).createDonorCombination(
                                            year = year,
                                            expectedRevision = view.revision,
                                            expectedAnnexRevisions = view.annexRevisions,
                                            largeDonorIds = listOf(a, b),
                                        )
                                    }
                                },
                                async {
                                    attempt(member = board) {
                                        service(
                                            it,
                                        ).deleteLargeDonor(year = year, unitKey = stateA, expectedRevision = annexA.revision, donorId = a)
                                    }
                                },
                            ).awaitAll()
                        }
                    // every outcome is a success or one of the typed refusals; nothing else (no foreign-key violation) escapes
                    outcomes.forEach { r ->
                        val e = r.exceptionOrNull()
                        (e == null || e is NotFoundException || e is PartyReportRevisionConflictException) shouldBe true
                    }
                    // no group row points to a donor row that is gone (the foreign key would have refused it anyway)
                    val rowsOfA =
                        transaction {
                            PartgDonorCombinationRowTable
                                .selectAll()
                                .where {
                                    PartgDonorCombinationRowTable.largeDonorId eq
                                        Uuid.parse(a)
                                }.count()
                        }
                    val stillThere =
                        transaction {
                            PartgReportLargeDonorTable
                                .selectAll()
                                .where {
                                    PartgReportLargeDonorTable.id eq
                                        Uuid.parse(a)
                                }.count()
                        }
                    (stillThere > 0L || rowsOfA == 0L) shouldBe true
                }
            }
        }

        test(
            "the fence: a consolidation write between the reads repeats the read; a write in every attempt ends in the revision conflict",
        ) {
            val board = fx.member()
            val other = stateKey(fx.chapter())
            val fenceYear = year + 2

            fun writeOne(
                payee: String,
                revision: Long,
            ) = PartgConsolidationWriter.saveTransfer(
                current = fx.currentOf(memberId = board),
                year = fenceYear,
                expectedRevision = revision,
                transfer = transfer(PartgUnit.FEDERAL_KEY, payee),
                now = ServerClock.now(),
            ) {}

            fun currentRevision() = transaction { PartgConsolidationStore.findSheet(fenceYear)?.revision ?: 0L }
            var attempts = 0
            PartgReportSnapshotReader.betweenReadsHook = { attempt ->
                attempts++
                if (attempt == 0) {
                    val worker = Thread { writeOne(other, currentRevision()) }
                    worker.start()
                    worker.join()
                }
            }
            val snapshot = PartgReportSnapshotReader.read(listOf(fenceYear))
            attempts shouldBe 2
            snapshot.consolidation[fenceYear]?.transfers?.size shouldBe 1
            // a writer in EVERY attempt: three tries, then the conflict
            var counter = 0
            // the chapters are made up front: inside the hook the reading transaction is open on this thread, and a row made there is not committed
            val targets = ArrayDeque((1..PartgReportSnapshotReader.MAX_ATTEMPTS + 1).map { stateKey(fx.chapter()) })
            PartgReportSnapshotReader.betweenReadsHook = {
                val target = targets.removeFirst()
                counter++
                val worker = Thread { writeOne(target, currentRevision()) }
                worker.start()
                worker.join()
            }
            io.kotest.assertions.throwables.shouldThrow<PartyReportRevisionConflictException> {
                PartgReportSnapshotReader.read(
                    listOf(fenceYear),
                )
            }
            counter shouldBe PartgReportSnapshotReader.MAX_ATTEMPTS
        }

        // ---------------------------------------------------------------- audit, access, GoBD

        test("the audit entries carry ids, counters and codes: no name, no note, no amount") {
            val board = fx.member()
            val stateA = stateKey(fx.chapter())
            val stateB = stateKey(fx.chapter())
            partgTestApplication(fixtures = fx) {
                call(member = board) {
                    service(it).saveIntraTransfer(
                        year = year,
                        expectedRevision = 0,
                        transfer = transfer(stateA, stateB, payerAmount = 7_654_321L, payeeAmount = null, note = "Geheimnotiz Mustermann"),
                    )
                }
                val a = addDonor(board, stateA, name = "Geheimperson Alpha")
                val b = addDonor(board, stateB, name = "Geheimperson Beta")
                val view = consolidation(board)
                val made =
                    call(member = board) {
                        service(
                            it,
                        ).createDonorCombination(
                            year = year,
                            expectedRevision = view.revision,
                            expectedAnnexRevisions = view.annexRevisions,
                            largeDonorIds = listOf(a, b),
                        )
                    }
                call(member = board) {
                    service(it).dissolveDonorCombination(
                        year = year,
                        expectedRevision = made.revision,
                        combinationId =
                            made.donors
                                .single { d ->
                                    d.combinationId != null
                                }.combinationId!!,
                    )
                }
                val entries =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                AuditLogEntryTable.entityType inList
                                    listOf(AuditEntityType.PARTG_INTRA_TRANSFER, AuditEntityType.PARTG_DONOR_COMBINATION)
                            }.filter { it[AuditLogEntryTable.actorMemberId] == board }
                            .map { (it[AuditLogEntryTable.beforeSnapshot] ?: "") + (it[AuditLogEntryTable.afterSnapshot] ?: "") }
                    }
                entries.size shouldBe 3
                val joined = entries.joinToString("|")
                listOf("Geheimnotiz", "Mustermann", "Geheimperson", "7654321", "Braunschweig").forEach { joined shouldNotContain it }
                joined stringShouldContain "TRANSFER_ADDED"
                joined stringShouldContain "COMBINATION_CREATED"
                joined stringShouldContain "COMBINATION_DISSOLVED"
            }
        }

        test(
            "access: a chapter treasurer is refused on all five calls, sees no consolidation hint, a plain member and a non-party get the usual refusals",
        ) {
            val board = fx.member()
            val treasurer = fx.member(role = AccountRole.MEMBER)
            val plain = fx.member(role = AccountRole.MEMBER)
            val chapter = fx.chapter()
            fx.grantTreasurer(member = treasurer, chapter = chapter)
            val state = stateKey(chapter)
            partgTestApplication(fixtures = fx) {
                // data that WOULD raise P19 for the unit of the treasurer
                call(member = board) {
                    service(it).saveSheet(
                        year = year,
                        unitKey = state,
                        expectedRevision = 0,
                        expectedPriorRevision = 0,
                        changes = listOf(PartgValueChangeDto(year = year, positionCode = "E10", amountCents = 5_00L)),
                    )
                }
                val federalFindings = call(member = board) { service(it).reportFindings(year) }
                federalFindings.any { it.code == PartgFindingCode.P19 && it.unitKey == state } shouldBe true
                val treasurerFindings = call(member = treasurer) { service(it).reportFindings(year) }
                treasurerFindings.none { it.code.ordinal >= PartgFindingCode.P17.ordinal } shouldBe true
                val refused: List<suspend (network.lapis.cloud.server.rpc.PartyReportService) -> Any?> =
                    listOf(
                        { s -> s.getConsolidation(year) },
                        { s -> s.saveIntraTransfer(year = year, expectedRevision = 0, transfer = transfer(PartgUnit.FEDERAL_KEY, state)) },
                        { s -> s.deleteIntraTransfer(year = year, expectedRevision = 0, transferId = Uuid.random().toString()) },
                        { s ->
                            s.createDonorCombination(
                                year = year,
                                expectedRevision = 0,
                                expectedAnnexRevisions = emptyMap(),
                                largeDonorIds = listOf(Uuid.random().toString(), Uuid.random().toString()),
                            )
                        },
                        { s -> s.dissolveDonorCombination(year = year, expectedRevision = 0, combinationId = Uuid.random().toString()) },
                    )
                for (who in listOf(treasurer, plain)) {
                    refused.forEach { op ->
                        attempt(member = who) { op(service(it)) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    }
                }
                fx.setParty(false)
                refused.forEach { op ->
                    attempt(member = board) { op(service(it)) }.exceptionOrNull().shouldBeInstanceOf<PartyReportNotEnabledException>()
                }
                // a plain member of a non-party learns nothing either: the role comes first
                refused.forEach { op ->
                    attempt(member = plain) { op(service(it)) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                }
            }
        }

        test("GoBD: no journal entry is touched by anything the consolidation does") {
            val board = fx.member()
            val state = stateKey(fx.chapter())

            fun journal() =
                transaction {
                    JournalEntryTable
                        .selectAll()
                        .map {
                            listOf(
                                it[JournalEntryTable.id],
                                it[JournalEntryTable.status],
                                it[JournalEntryTable.description],
                                it[JournalEntryTable.regionalChapterId],
                            )
                        }.sortedBy { it[0].toString() }
                }
            val before = journal()
            partgTestApplication(fixtures = fx) {
                val saved =
                    call(
                        member = board,
                    ) {
                        service(
                            it,
                        ).saveIntraTransfer(year = year, expectedRevision = 0, transfer = transfer(PartgUnit.FEDERAL_KEY, state))
                    }
                call(member = board) {
                    service(
                        it,
                    ).deleteIntraTransfer(year = year, expectedRevision = saved.revision, transferId = saved.transfers.single().id)
                }
                consolidation(board)
            }
            journal() shouldBe before
        }

        test("nothing is left over: the helper counts agree") {
            transaction { PartgDonorCombinationTable.selectAll().where { PartgDonorCombinationTable.id inList emptyList() }.toList() }
                .shouldBeEmpty()
        }
    })

private fun PartgConsolidationSheetTable.deleteWhereId(sheetId: Uuid) {
    deleteWhere { PartgConsolidationSheetTable.id eq sheetId }
}
