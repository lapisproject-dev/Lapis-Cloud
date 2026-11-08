package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.LedgerAccountTable
import network.lapis.cloud.server.db.generated.PartgAccountMappingTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PartgBookkeepingMode
import network.lapis.cloud.shared.domain.PartgFindingCode
import network.lapis.cloud.shared.domain.PartgMappingChangeDto
import network.lapis.cloud.shared.domain.PartgProposalPickDto
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgValueChangeDto
import network.lapis.cloud.shared.domain.PartgValueSource
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.StandardPartyChart
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import network.lapis.cloud.shared.rpc.PartyReportModeConfirmationRequiredException
import network.lapis.cloud.shared.rpc.PartyReportNotEnabledException
import network.lapis.cloud.shared.rpc.PartyReportProposalNotAvailableException
import network.lapis.cloud.shared.rpc.PartyReportProposalStaleException
import network.lapis.cloud.shared.rpc.PartyReportRevisionConflictException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * V1.9.289 (R4a) -- accounts to positions, the bookkeeping mode, the proposal from the bookings, the hints P14..P16, the chapter treasurer inside the
 * party report, and the party chart, on H2 AND PostgreSQL. The proposal is recomputed under the lock: a tampered or stale amount is refused.
 */
abstract class PartgLedgerProposalScenarios(
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

        fun posting(
            account: Uuid,
            side: PostingSide,
            amount: String,
        ) = PostingInput(
            ledgerAccountId = account.toString(),
            side = side,
            amount = BigDecimal(amount),
            sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
        )

        fun entry(
            on: LocalDate,
            chapter: Uuid?,
            debit: Uuid,
            credit: Uuid,
            amount: String,
            description: String = "Buchung",
        ) = JournalEntryInput(
            entryDate = on,
            description = description,
            postings = listOf(posting(debit, PostingSide.DEBIT, amount), posting(credit, PostingSide.CREDIT, amount)),
            regionalChapterId = chapter?.toString(),
        )

        fun map(
            accountId: Uuid,
            code: String?,
            excluded: Boolean = false,
        ) = PartgMappingChangeDto(
            accountId = accountId.toString(),
            expectedMapped = false,
            newPositionCode = code,
            newExcluded = excluded,
        )

        class World {
            val chapter = fx.chapter("Land ${Uuid.random()}")
            val other = fx.chapter("Land ${Uuid.random()}")
            val unitKey = PartgUnit.State(chapter.toString()).key()
            val otherKey = PartgUnit.State(other.toString()).key()
            val subKey = PartgUnit.Subordinate(chapter.toString()).key()
            val board = fx.member(label = "board", role = AccountRole.BOARD)
            val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
            val treasurer = fx.member(label = "treasurer", role = AccountRole.TREASURER)
            val kw = fx.member(label = "kw", role = AccountRole.MEMBER)
            val bank = fx.ledgerAccount(type = LedgerAccountType.ASSET)
            val income = fx.ledgerAccount(type = LedgerAccountType.INCOME)
            val expense = fx.ledgerAccount(type = LedgerAccountType.EXPENSE)
            val loan = fx.ledgerAccount(type = LedgerAccountType.LIABILITY)
            val equity = fx.ledgerAccount(type = LedgerAccountType.EQUITY)
            val unmapped = fx.ledgerAccount(type = LedgerAccountType.INCOME)

            init {
                fx.grantTreasurer(member = kw, chapter = chapter)
            }
        }

        suspend fun PartgApp.book(
            w: World,
            on: LocalDate,
            debit: Uuid,
            credit: Uuid,
            amount: String,
            chapter: Uuid? = w.chapter,
        ) = call(member = w.admin) { fx.accounting(it).postJournalEntry(entry(on, chapter, debit, credit, amount)) }

        suspend fun PartgApp.mapAll(w: World) {
            call(member = w.board) {
                fx.service(it).saveAccountMappings(
                    year = year,
                    unitKey = w.unitKey,
                    changes =
                        listOf(
                            map(w.bank, "BP07"),
                            map(w.income, "E01"),
                            map(w.expense, "A02A"),
                            map(w.loan, "SP05"),
                            map(w.equity, null, excluded = true),
                        ),
                )
            }
        }

        suspend fun PartgApp.setMode(
            w: World,
            mode: PartgBookkeepingMode,
            unit: String = w.unitKey,
        ) {
            val count = call(member = w.board) { fx.service(it).overview(year) }.single { it.unit.unitKey == unit }.bookingCount
            call(member = w.board) { fx.service(it).setUnitMode(year = year, unitKey = unit, mode = mode, confirmedBookingCount = count) }
        }

        test(
            "the proposal: flows for income and expense, balances at 31 December for assets and liabilities; unmapped and excluded accounts are ignored",
        ) {
            val w = World()
            partgTestApplication(fixtures = fx) {
                mapAll(w)
                setMode(w, PartgBookkeepingMode.A)
                book(w, LocalDate(year - 1, 12, 20), w.bank, w.income, "20.00") // the year before: a balance, no flow
                book(w, LocalDate(year, 3, 1), w.bank, w.income, "100.00")
                book(w, LocalDate(year, 4, 1), w.expense, w.bank, "30.00")
                book(w, LocalDate(year, 5, 1), w.bank, w.loan, "50.00")
                book(w, LocalDate(year, 6, 1), w.bank, w.unmapped, "11.00")
                book(w, LocalDate(year + 1, 1, 5), w.bank, w.income, "1000.00") // after the closing date: not in the balance
                book(w, LocalDate(year, 7, 1), w.bank, w.income, "500.00", chapter = w.other) // another unit

                val proposal = call(member = w.board) { fx.service(it).proposeFromLedger(year = year, unitKey = w.unitKey) }
                proposal.mode shouldBe PartgBookkeepingMode.A
                proposal.revision shouldBe 0
                proposal.rows.associate { it.positionCode to it.proposedCents } shouldBe
                    mapOf("E01" to 10_000L, "A02A" to 3_000L, "BP07" to 15_100L, "SP05" to 5_000L)
                proposal.rows.all { it.preselected && it.transferable } shouldBe true
                proposal.unmappedWithActivityCount shouldBe 1

                val picks = proposal.rows.map { PartgProposalPickDto(positionCode = it.positionCode, proposedCents = it.proposedCents) }
                val saved =
                    call(member = w.board) {
                        fx
                            .service(
                                it,
                            ).applyLedgerProposal(
                                year = year,
                                unitKey = w.unitKey,
                                expectedRevision = 0,
                                expectedPriorRevision = 0,
                                picks = picks,
                            )
                    }
                saved.changed shouldBe 4
                saved.revision shouldBe 1
                val sheet = call(member = w.board) { fx.service(it).getSheet(year = year, unitKey = w.unitKey) }
                sheet.values["E01"] shouldBe 10_000L
                sheet.valueSources.values.toSet() shouldBe setOf(PartgValueSource.LEDGER)
                // nothing left to propose
                call(member = w.board) { fx.service(it).proposeFromLedger(year = year, unitKey = w.unitKey) }.rows shouldBe emptyList()
                // the year before is never touched
                sheet.priorValues shouldBe emptyMap()
                // audit: one entry per value plus the collective one, without an amount in the collective one
                val collective =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.PARTG_REPORT_SHEET) and
                                    (AuditLogEntryTable.actorMemberId eq w.board)
                            }.mapNotNull { it[AuditLogEntryTable.afterSnapshot] }
                            .filter { "codes" in it }
                    }
                collective.size shouldBe 1
                (collective.single().contains("cents")) shouldBe false
            }
        }

        test(
            "a hand change turns the value into a manual one; a value typed in is never pre-selected; a negative proposal cannot be taken over",
        ) {
            val w = World()
            partgTestApplication(fixtures = fx) {
                mapAll(w)
                setMode(w, PartgBookkeepingMode.B)
                book(w, LocalDate(year, 3, 1), w.bank, w.income, "100.00")
                // a credit on the expense account: the signed position is negative
                book(w, LocalDate(year, 3, 2), w.bank, w.expense, "5.00")
                val proposal = call(member = w.board) { fx.service(it).proposeFromLedger(year = year, unitKey = w.unitKey) }
                val negative = proposal.rows.single { it.positionCode == "A02A" }
                negative.proposedCents shouldBe -500L
                negative.transferable shouldBe false
                negative.preselected shouldBe false
                attempt(member = w.board) {
                    fx
                        .service(
                            it,
                        ).applyLedgerProposal(
                            year = year,
                            unitKey = w.unitKey,
                            expectedRevision = 0,
                            expectedPriorRevision = 0,
                            picks = listOf(PartgProposalPickDto(positionCode = "A02A", proposedCents = -500L)),
                        )
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportProposalStaleException>()
                call(member = w.board) {
                    fx
                        .service(
                            it,
                        ).applyLedgerProposal(
                            year = year,
                            unitKey = w.unitKey,
                            expectedRevision = 0,
                            expectedPriorRevision = 0,
                            picks = listOf(PartgProposalPickDto(positionCode = "E01", proposedCents = 10_000L)),
                        )
                }
                // typed in by hand: MANUAL, then never pre-selected again
                var sheet = call(member = w.board) { fx.service(it).getSheet(year = year, unitKey = w.unitKey) }
                call(member = w.board) {
                    fx
                        .service(
                            it,
                        ).saveSheet(
                            year = year,
                            unitKey = w.unitKey,
                            expectedRevision = sheet.revision,
                            expectedPriorRevision = sheet.priorRevision,
                            changes = listOf(PartgValueChangeDto(year = year, positionCode = "E01", amountCents = 9_000L)),
                        )
                }
                sheet = call(member = w.board) { fx.service(it).getSheet(year = year, unitKey = w.unitKey) }
                sheet.valueSources["E01"] shouldBe PartgValueSource.MANUAL
                val again = call(member = w.board) { fx.service(it).proposeFromLedger(year = year, unitKey = w.unitKey) }
                again.rows.single { it.positionCode == "E01" }.let {
                    it.preselected shouldBe false
                    it.enteredSource shouldBe PartgValueSource.MANUAL
                    it.enteredCents shouldBe 9_000L
                }
            }
        }

        test("a tampered amount, a stale proposal, a wrong revision, mode C and a sub-unit are all refused") {
            val w = World()
            partgTestApplication(fixtures = fx) {
                mapAll(w)
                setMode(w, PartgBookkeepingMode.A)
                book(w, LocalDate(year, 3, 1), w.bank, w.income, "100.00")
                val proposal = call(member = w.board) { fx.service(it).proposeFromLedger(year = year, unitKey = w.unitKey) }
                val e01 = proposal.rows.single { it.positionCode == "E01" }.proposedCents
                // a manipulated amount
                attempt(member = w.board) {
                    fx.service(it).applyLedgerProposal(
                        year = year,
                        unitKey = w.unitKey,
                        expectedRevision = 0,
                        expectedPriorRevision = 0,
                        picks =
                            listOf(
                                PartgProposalPickDto(
                                    positionCode = "E01",
                                    proposedCents =
                                        e01 + 1,
                                ),
                            ),
                    )
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportProposalStaleException>()
                // an unknown position code
                attempt(member = w.board) {
                    fx
                        .service(
                            it,
                        ).applyLedgerProposal(
                            year = year,
                            unitKey = w.unitKey,
                            expectedRevision = 0,
                            expectedPriorRevision = 0,
                            picks = listOf(PartgProposalPickDto(positionCode = "E11", proposedCents = 1L)),
                        )
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>()
                // a booking after the proposal was loaded makes the picks stale
                book(w, LocalDate(year, 3, 2), w.bank, w.income, "1.00")
                attempt(member = w.board) {
                    fx
                        .service(
                            it,
                        ).applyLedgerProposal(
                            year = year,
                            unitKey = w.unitKey,
                            expectedRevision = 0,
                            expectedPriorRevision = 0,
                            picks = listOf(PartgProposalPickDto(positionCode = "E01", proposedCents = e01)),
                        )
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportProposalStaleException>()
                // a wrong revision
                attempt(member = w.board) {
                    fx.service(it).applyLedgerProposal(
                        year = year,
                        unitKey = w.unitKey,
                        expectedRevision = 5,
                        expectedPriorRevision = 0,
                        picks =
                            listOf(
                                PartgProposalPickDto(
                                    positionCode = "E01",
                                    proposedCents =
                                        e01 + 100,
                                ),
                            ),
                    )
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportRevisionConflictException>()
                // nothing was written
                call(member = w.board) { fx.service(it).getSheet(year = year, unitKey = w.unitKey) }.values shouldBe emptyMap()
                // mode C: no proposal, no apply
                setMode(w, PartgBookkeepingMode.C)
                attempt(member = w.board) { fx.service(it).proposeFromLedger(year = year, unitKey = w.unitKey) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportProposalNotAvailableException>()
                attempt(member = w.board) {
                    fx.service(it).applyLedgerProposal(
                        year = year,
                        unitKey = w.unitKey,
                        expectedRevision = 0,
                        expectedPriorRevision = 0,
                        picks =
                            listOf(
                                PartgProposalPickDto(
                                    positionCode = "E01",
                                    proposedCents =
                                        e01 + 100,
                                ),
                            ),
                    )
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportProposalNotAvailableException>()
                // a summary of sub-units is always C
                attempt(member = w.board) { fx.service(it).proposeFromLedger(year = year, unitKey = w.subKey) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportProposalNotAvailableException>()
                attempt(member = w.board) {
                    fx.service(it).setUnitMode(year = year, unitKey = w.subKey, mode = PartgBookkeepingMode.A, confirmedBookingCount = 0)
                }.exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportInvalidInputException>()
            }
        }

        test(
            "the mapping rules: leaf of the matching section only, equity only excluded, the expected old state, the cap, unknown accounts",
        ) {
            val w = World()
            partgTestApplication(fixtures = fx) {
                suspend fun bad(change: PartgMappingChangeDto) =
                    attempt(
                        member = w.board,
                    ) { fx.service(it).saveAccountMappings(year = year, unitKey = w.unitKey, changes = listOf(change)) }
                bad(map(w.income, "E11")).exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>() // a SUM
                bad(map(w.income, "A01")).exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>() // wrong section
                bad(map(w.equity, "BP01")).exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>() // equity to a position
                bad(map(w.income, null)).exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>() // no target
                bad(map(Uuid.random(), "E01")).exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                val tooMany = (1..2001).map { map(w.income, "E01").copy(accountId = Uuid.random().toString()) }
                attempt(member = w.board) { fx.service(it).saveAccountMappings(year = year, unitKey = w.unitKey, changes = tooMany) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportInvalidInputException>()
                // a valid change, then the same change with a stale expected state is a conflict
                call(
                    member = w.board,
                ) { fx.service(it).saveAccountMappings(year = year, unitKey = w.unitKey, changes = listOf(map(w.income, "E01"))) }
                bad(map(w.income, "E02")).exceptionOrNull().shouldBeInstanceOf<PartyReportRevisionConflictException>()
                val mapped =
                    PartgMappingChangeDto(
                        accountId = w.income.toString(),
                        expectedMapped = true,
                        expectedPositionCode = "E01",
                        expectedExcluded = false,
                        newPositionCode = "E02",
                    )
                call(member = w.board) { fx.service(it).saveAccountMappings(year = year, unitKey = w.unitKey, changes = listOf(mapped)) }
                    .rows
                    .single { it.accountId == w.income.toString() }
                    .positionCode shouldBe "E02"
                // all or nothing: a good and a bad change together write nothing
                attempt(member = w.board) {
                    fx
                        .service(
                            it,
                        ).saveAccountMappings(
                            year = year,
                            unitKey = w.unitKey,
                            changes = listOf(map(w.expense, "A02A"), map(w.loan, "E01")),
                        )
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>()
                transaction {
                    PartgAccountMappingTable
                        .selectAll()
                        .where {
                            PartgAccountMappingTable.ledgerAccountId inList
                                listOf(
                                    w.expense,
                                    w.loan,
                                )
                        }.count()
                } shouldBe 0
                // clear
                val clear =
                    PartgMappingChangeDto(
                        accountId = w.income.toString(),
                        expectedMapped = true,
                        expectedPositionCode = "E02",
                        expectedExcluded = false,
                        clear = true,
                    )
                call(member = w.board) { fx.service(it).saveAccountMappings(year = year, unitKey = w.unitKey, changes = listOf(clear)) }
                    .rows
                    .single { it.accountId == w.income.toString() }
                    .positionCode shouldBe null
                // the audit entries name the account number and codes, never an amount or a name
                val audits =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityType eq AuditEntityType.PARTG_ACCOUNT_MAPPING }
                            .mapNotNull { it[AuditLogEntryTable.afterSnapshot] ?: it[AuditLogEntryTable.beforeSnapshot] }
                    }
                audits.isNotEmpty() shouldBe true
                audits.none { "cents" in it || "balance" in it || "name" in it } shouldBe true
                // the template suggestion is offered for unmapped accounts only, and only a matching target
                val view = call(member = w.board) { fx.service(it).getAccountMappings(year = year, unitKey = w.unitKey) }
                view.editable shouldBe true
            }
        }

        test("the bookkeeping mode: confirm the number of bookings, audit with the count, overview with mode, count and editability") {
            val w = World()
            partgTestApplication(fixtures = fx) {
                book(w, LocalDate(year, 3, 1), w.bank, w.income, "10.00")
                book(w, LocalDate(year, 3, 2), w.bank, w.income, "10.00")
                attempt(member = w.board) {
                    fx.service(it).setUnitMode(year = year, unitKey = w.unitKey, mode = PartgBookkeepingMode.A, confirmedBookingCount = 0)
                }.exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportModeConfirmationRequiredException>()
                attempt(member = w.board) {
                    fx.service(it).setUnitMode(year = year, unitKey = w.unitKey, mode = PartgBookkeepingMode.A, confirmedBookingCount = 3)
                }.exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportModeConfirmationRequiredException>()
                val row =
                    call(member = w.board) {
                        fx
                            .service(
                                it,
                            ).setUnitMode(year = year, unitKey = w.unitKey, mode = PartgBookkeepingMode.B, confirmedBookingCount = 2)
                    }
                row.mode shouldBe PartgBookkeepingMode.B
                row.bookingCount shouldBe 2
                row.modeEditable shouldBe true
                // a unit without bookings needs no confirmation
                call(member = w.board) {
                    fx.service(it).setUnitMode(year = year, unitKey = w.otherKey, mode = PartgBookkeepingMode.A, confirmedBookingCount = 0)
                }.mode shouldBe
                    PartgBookkeepingMode.A
                val modeAudits =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where { AuditLogEntryTable.entityType eq AuditEntityType.PARTG_UNIT_MODE }
                            .mapNotNull { it[AuditLogEntryTable.afterSnapshot] }
                    }
                modeAudits.any { "\"bookingCount\":2" in it } shouldBe true
                // only the federal roles change a mode
                listOf(w.kw, w.treasurer).forEach { member ->
                    attempt(member = member) {
                        fx
                            .service(
                                it,
                            ).setUnitMode(year = year, unitKey = w.unitKey, mode = PartgBookkeepingMode.C, confirmedBookingCount = 2)
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<ForbiddenException>()
                }
                // the chapter treasurer sees the mode, cannot edit it, and sees only the units of the granted chapter
                val own = call(member = w.kw) { fx.service(it).overview(year) }
                own.map { it.unit.unitKey } shouldContainExactlyInAnyOrder listOf(w.unitKey, w.subKey)
                own.single { it.unit.unitKey == w.unitKey }.let {
                    it.mode shouldBe PartgBookkeepingMode.B
                    it.modeEditable shouldBe false
                }
                call(member = w.kw) { fx.service(it).listUnits() }.map { it.unitKey } shouldContainExactlyInAnyOrder
                    listOf(w.unitKey, w.subKey)
            }
        }

        test("the chapter treasurer inside the party report: own units yes, everything else no; the gate order is role, grant, party") {
            val w = World()
            partgTestApplication(fixtures = fx) {
                // own unit: read and enter values, read the mapping, read the proposal
                val sheet = call(member = w.kw) { fx.service(it).getSheet(year = year, unitKey = w.unitKey) }
                call(member = w.kw) {
                    fx
                        .service(
                            it,
                        ).saveSheet(
                            year = year,
                            unitKey = w.unitKey,
                            expectedRevision = sheet.revision,
                            expectedPriorRevision = sheet.priorRevision,
                            changes = listOf(PartgValueChangeDto(year = year, positionCode = "E01", amountCents = 100L)),
                        )
                }.changed shouldBe 1
                call(member = w.kw) { fx.service(it).getAccountMappings(year = year, unitKey = w.unitKey) }.editable shouldBe false
                // other units: 404 on reading, 403 on writing; the federal unit too
                listOf(w.otherKey, "FEDERAL").forEach { key ->
                    attempt(
                        member = w.kw,
                    ) { fx.service(it).getSheet(year = year, unitKey = key) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    attempt(
                        member = w.kw,
                    ) {
                        fx
                            .service(
                                it,
                            ).getAccountMappings(year = year, unitKey = key)
                    }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    attempt(
                        member = w.kw,
                    ) {
                        fx
                            .service(
                                it,
                            ).proposeFromLedger(year = year, unitKey = key)
                    }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                    attempt(member = w.kw) {
                        fx
                            .service(
                                it,
                            ).saveSheet(
                                year = year,
                                unitKey = key,
                                expectedRevision = 0,
                                expectedPriorRevision = 0,
                                changes = listOf(PartgValueChangeDto(year = year, positionCode = "E01", amountCents = 1L)),
                            )
                    }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    attempt(member = w.kw) {
                        fx
                            .service(
                                it,
                            ).applyLedgerProposal(
                                year = year,
                                unitKey = key,
                                expectedRevision = 0,
                                expectedPriorRevision = 0,
                                picks = emptyList(),
                            )
                    }.exceptionOrNull()
                        .shouldBeInstanceOf<ForbiddenException>()
                }
                // the federal-only methods
                attempt(member = w.kw) { fx.service(it).saveAccountMappings(year = year, unitKey = w.unitKey, changes = emptyList()) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ForbiddenException>()
                attempt(member = w.kw) { fx.service(it).listPositions() }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                attempt(
                    member = w.kw,
                ) { fx.service(it).getAnnex(year = year, unitKey = w.unitKey) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                attempt(member = w.kw) { fx.service(it).getNotes(year) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                attempt(
                    member = w.kw,
                ) { fx.service(it).reportCompleteness(year) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                // the findings carry only the own units, never an organisation-wide hint
                call(
                    member = w.kw,
                ) { fx.service(it).reportFindings(year) }.all { it.unitKey == w.unitKey || it.unitKey == w.subKey } shouldBe
                    true
                // a plain member without a grant: Forbidden BEFORE the party check; the chapter treasurer of a non-party: NotEnabled
                val plain = fx.member(label = "plain", role = AccountRole.MEMBER)
                fx.setParty(false)
                attempt(member = plain) { fx.service(it).overview(year) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                attempt(
                    member = w.kw,
                ) { fx.service(it).overview(year) }.exceptionOrNull().shouldBeInstanceOf<PartyReportNotEnabledException>()
                fx.setParty(true)
            }
        }

        test("the hints P14, P15 and P16 carry counts and codes only") {
            val w = World()
            partgTestApplication(fixtures = fx) {
                mapAll(w)
                book(w, LocalDate(year, 3, 1), w.bank, w.income, "100.00")
                book(w, LocalDate(year, 3, 2), w.bank, w.unmapped, "5.00")
                // mode C (the default) with bookings: P15 with the count
                val p15 = call(member = w.board) { fx.service(it).reportFindings(year) }.filter { it.unitKey == w.unitKey }
                p15.single { it.code == PartgFindingCode.P15 }.count shouldBe 2
                // mode A: P14 for the unmapped account with postings, and P16 once a value from the bookings is out of date
                setMode(w, PartgBookkeepingMode.A)
                val proposal = call(member = w.board) { fx.service(it).proposeFromLedger(year = year, unitKey = w.unitKey) }
                call(member = w.board) {
                    fx.service(it).applyLedgerProposal(
                        year = year,
                        unitKey = w.unitKey,
                        expectedRevision = 0,
                        expectedPriorRevision = 0,
                        picks =
                            proposal.rows
                                .filter { r ->
                                    r.positionCode == "E01"
                                }.map { r -> PartgProposalPickDto(positionCode = r.positionCode, proposedCents = r.proposedCents) },
                    )
                }
                book(w, LocalDate(year, 3, 3), w.bank, w.income, "1.00")
                val findings = call(member = w.board) { fx.service(it).reportFindings(year) }.filter { it.unitKey == w.unitKey }
                findings.none { it.code == PartgFindingCode.P15 } shouldBe true
                findings.single { it.code == PartgFindingCode.P14 }.count shouldBe 1
                findings.single { it.code == PartgFindingCode.P16 }.positionCode shouldBe "E01"
                findings.all { it.rowNumbers.isEmpty() } shouldBe true
            }
        }

        test(
            "the party chart is created once, idempotently; a number with another name is reported and never renamed; a non-party is refused; one audit entry",
        ) {
            val w = World()
            partgTestApplication(fixtures = fx) {
                // a clash: the number exists with another name
                val clashNumber =
                    StandardPartyChart.accounts
                        .first { it.account.accountNumber == "40100" }
                        .account.accountNumber
                val clashId = Uuid.random()
                transaction {
                    if (LedgerAccountTable.selectAll().where { LedgerAccountTable.accountNumber eq clashNumber }.count() == 0L) {
                        LedgerAccountTable.insert {
                            it[id] = clashId
                            it[accountNumber] = clashNumber
                            it[name] = "Eigener Name"
                            it[accountClass] = 4
                            it[type] = LedgerAccountType.INCOME
                            it[active] = true
                            it[reserveType] = null
                            it[isCashRegister] = false
                        }
                        fx.accountIds += clashId
                    }
                }
                val first = call(member = w.treasurer) { fx.accounting(it).createStandardPartyChart() }
                transaction {
                    LedgerAccountTable.selectAll().where { LedgerAccountTable.accountNumber inList first.created }.forEach {
                        fx.accountIds += it[LedgerAccountTable.id]
                    }
                }
                (clashNumber in first.conflicting) shouldBe true
                val second = call(member = w.admin) { fx.accounting(it).createStandardPartyChart() }
                second.created shouldBe emptyList()
                (clashNumber in second.conflicting) shouldBe true
                transaction {
                    LedgerAccountTable
                        .selectAll()
                        .where {
                            LedgerAccountTable.accountNumber eq clashNumber
                        }.single()[LedgerAccountTable.name]
                } shouldBe "Eigener Name"
                // no mapping is created by the chart
                transaction {
                    PartgAccountMappingTable
                        .selectAll()
                        .where {
                            PartgAccountMappingTable.updatedBy inList
                                listOf(
                                    w.treasurer,
                                    w.admin,
                                )
                        }.count()
                } shouldBe 0
                // a non-party
                fx.setParty(false)
                attempt(
                    member = w.treasurer,
                ) { fx.accounting(it).createStandardPartyChart() }.exceptionOrNull().shouldBeInstanceOf<BadRequestException>()
                fx.setParty(true)
            }
        }
    })
