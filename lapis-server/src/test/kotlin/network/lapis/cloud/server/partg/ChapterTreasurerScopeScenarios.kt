package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.dsgvo.RegionalChapterPersonalData
import network.lapis.cloud.server.rpc.DsgvoService
import network.lapis.cloud.server.security.activeChapterTreasurerIds
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditAction
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.ErasureStatus
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryDto
import network.lapis.cloud.shared.domain.JournalEntryInput
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountInput
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.PostingInput
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.rpc.ChapterDonationFederalOnlyException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * V1.9.289 (R4a) -- the BOOKING SCOPE of the chapter treasurer on H2 AND PostgreSQL. A chapter treasurer (a plain member with an active grant) books
 * into and reads the journal of the granted chapters ONLY. The tests are the negative and tamper tests of the plan: a foreign chapter, the federal
 * level, an invented chapter id, donor fields, every other accounting method, a revoked grant, a changed status, the role winning first, several
 * grants, the cash check per (account, chapter), and that the audit payload of a federal entry did not change.
 */
abstract class ChapterTreasurerScopeScenarios(
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

        val day = LocalDate(2026, 3, 1)

        fun postings(
            debit: Uuid,
            credit: Uuid,
            amount: String = "10.00",
        ) = listOf(
            PostingInput(
                ledgerAccountId = debit.toString(),
                side = PostingSide.DEBIT,
                amount = BigDecimal(amount),
                sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
            ),
            PostingInput(
                ledgerAccountId = credit.toString(),
                side = PostingSide.CREDIT,
                amount = BigDecimal(amount),
                sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
            ),
        )

        fun input(
            debit: Uuid,
            credit: Uuid,
            chapter: Uuid?,
            description: String = "Buchung",
            voucher: String? = null,
            amount: String = "10.00",
            donorCategory: DonorCategory? = null,
        ) = JournalEntryInput(
            entryDate = day,
            description = description,
            voucherReference = voucher,
            postings = postings(debit, credit, amount),
            regionalChapterId = chapter?.toString(),
            donorCategory = donorCategory,
        )

        class World {
            val g1 = fx.chapter("Bereich Eins ${Uuid.random()}")
            val g2 = fx.chapter("Bereich Zwei ${Uuid.random()}")
            val g3 = fx.chapter("Bereich Drei ${Uuid.random()}")
            val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
            val treasurer = fx.member(label = "treasurer", role = AccountRole.TREASURER)
            val board = fx.member(label = "board", role = AccountRole.BOARD)
            val kw1 = fx.member(label = "kw1", role = AccountRole.MEMBER)
            val kw13 = fx.member(label = "kw13", role = AccountRole.MEMBER)
            val plain = fx.member(label = "plain", role = AccountRole.MEMBER)
            val bank = fx.ledgerAccount(type = LedgerAccountType.ASSET)
            val income = fx.ledgerAccount(type = LedgerAccountType.INCOME)
            val cash = fx.ledgerAccount(type = LedgerAccountType.ASSET, cashRegister = true)
            val grant1 = fx.grantTreasurer(member = kw1, chapter = g1)

            init {
                fx.grantTreasurer(member = kw13, chapter = g1)
                fx.grantTreasurer(member = kw13, chapter = g3)
            }
        }

        test("a chapter treasurer books into a granted chapter (posted and draft); the entry carries the chapter") {
            val w = World()
            partgTestApplication(fixtures = fx) {
                val posted = call(member = w.kw1) { fx.accounting(it).postJournalEntry(input(w.bank, w.income, w.g1, "KW1 gebucht")) }
                posted.regionalChapterId shouldBe w.g1.toString()
                posted.status shouldBe JournalEntryStatus.POSTED
                (posted.regionalChapterName != null) shouldBe true
                val draft = call(member = w.kw1) { fx.accounting(it).saveDraftEntry(input(w.bank, w.income, w.g1, "KW1 Entwurf")) }
                draft.status shouldBe JournalEntryStatus.DRAFT
                draft.regionalChapterId shouldBe w.g1.toString()
                val postedDraft = call(member = w.kw1) { fx.accounting(it).postDraftEntry(draft.id) }
                postedDraft.status shouldBe JournalEntryStatus.POSTED
            }
        }

        test("booking without a chapter, into the federal level or into a foreign chapter is Forbidden (posted and draft)") {
            val w = World()
            partgTestApplication(fixtures = fx) {
                listOf<Uuid?>(null, w.g2).forEach { chapter ->
                    attempt(member = w.kw1) { fx.accounting(it).postJournalEntry(input(w.bank, w.income, chapter)) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ForbiddenException>()
                    attempt(member = w.kw1) { fx.accounting(it).saveDraftEntry(input(w.bank, w.income, chapter)) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ForbiddenException>()
                }
                // an invented chapter id is also Forbidden (not in the grants), never a 500
                attempt(member = w.kw1) { fx.accounting(it).postJournalEntry(input(w.bank, w.income, Uuid.random())) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ForbiddenException>()
                // a member without any grant gets nothing at all
                attempt(member = w.plain) { fx.accounting(it).postJournalEntry(input(w.bank, w.income, w.g1)) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ForbiddenException>()
            }
        }

        test("donor fields are refused for a chapter treasurer") {
            val w = World()
            partgTestApplication(fixtures = fx) {
                attempt(member = w.kw1) {
                    fx.accounting(it).postJournalEntry(input(w.bank, w.income, w.g1, donorCategory = DonorCategory.ANONYMOUS))
                }.exceptionOrNull().shouldBeInstanceOf<ChapterDonationFederalOnlyException>()
            }
        }

        test(
            "the journal, a single entry, the draft post, the general ledger and the cash book never show a foreign chapter or the federal level",
        ) {
            val w = World()
            partgTestApplication(fixtures = fx) {
                val own =
                    call(member = w.admin) { fx.accounting(it).postJournalEntry(input(w.bank, w.income, w.g1, "g1-own", amount = "5.00")) }
                val foreign =
                    call(
                        member = w.admin,
                    ) { fx.accounting(it).postJournalEntry(input(w.bank, w.income, w.g2, "g2-foreign", amount = "7.00")) }
                val federal =
                    call(
                        member = w.treasurer,
                    ) { fx.accounting(it).postJournalEntry(input(w.bank, w.income, null, "federal", amount = "9.00")) }
                val foreignDraft = call(member = w.admin) { fx.accounting(it).saveDraftEntry(input(w.bank, w.income, w.g2, "g2-draft")) }

                val list = call(member = w.kw1) { fx.accounting(it).listJournal() }
                list.map { it.id }.toSet() shouldBe setOf(own.id)
                call(member = w.kw1) { fx.accounting(it).listJournal(unitFilter = w.g1.toString()) }.map { it.id } shouldBe listOf(own.id)
                listOf(w.g2.toString(), "FEDERAL", Uuid.random().toString(), "no-uuid").forEach { filter ->
                    attempt(member = w.kw1) { fx.accounting(it).listJournal(unitFilter = filter) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ForbiddenException>()
                }
                attempt(member = w.kw1) { fx.accounting(it).listJournal(donorMemberId = w.plain.toString()) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ForbiddenException>()

                // single entries and the draft: NotFound (nothing can be enumerated)
                listOf(foreign.id, federal.id).forEach { id ->
                    attempt(
                        member = w.kw1,
                    ) { fx.accounting(it).getJournalEntry(id) }.exceptionOrNull().shouldBeInstanceOf<NotFoundException>()
                }
                attempt(member = w.kw1) { fx.accounting(it).postDraftEntry(foreignDraft.id) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
                call(member = w.kw1) { fx.accounting(it).getJournalEntry(own.id) }.id shouldBe own.id

                // the general ledger: only the own chapter, the running balance too
                val ledger = call(member = w.kw1) { fx.accounting(it).getGeneralLedgerAccount(ledgerAccountId = w.bank.toString()) }
                ledger.lines.map { it.journalEntryId }.toSet() shouldBe setOf(own.id)
                ledger.closingBalance.compareTo(BigDecimal("5.00")) shouldBe 0
                attempt(
                    member = w.kw1,
                ) { fx.accounting(it).getGeneralLedgerAccount(ledgerAccountId = w.bank.toString(), unitFilter = w.g2.toString()) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ForbiddenException>()
                // the federal reader sees all three and can filter
                call(member = w.treasurer) {
                    fx.accounting(it).listJournal()
                }.map { it.id }.toSet().containsAll(setOf(own.id, foreign.id, federal.id)) shouldBe
                    true
                val federalOnly = call(member = w.treasurer) { fx.accounting(it).listJournal(unitFilter = "FEDERAL") }.map { it.id }.toSet()
                (federal.id in federalOnly) shouldBe true
                (own.id in federalOnly || foreign.id in federalOnly) shouldBe false
                val g2Only = call(member = w.board) { fx.accounting(it).listJournal(unitFilter = w.g2.toString()) }.map { it.id }.toSet()
                g2Only shouldBe setOf(foreign.id, foreignDraft.id)

                // the cash book
                call(
                    member = w.admin,
                ) { fx.accounting(it).postJournalEntry(input(w.cash, w.income, w.g1, voucher = "B1", amount = "20.00")) }
                call(
                    member = w.admin,
                ) { fx.accounting(it).postJournalEntry(input(w.cash, w.income, w.g2, voucher = "B2", amount = "40.00")) }
                val book = call(member = w.kw1) { fx.accounting(it).getKassenbuch(ledgerAccountId = w.cash.toString()) }
                book.lines.size shouldBe 1
                book.closingBalance.compareTo(BigDecimal("20.00")) shouldBe 0
                // the gapless number has ONE basis (per account and chapter) and must not depend on the view or the filter
                call(
                    member = w.admin,
                ) { fx.accounting(it).postJournalEntry(input(w.cash, w.income, w.g1, voucher = "B3", amount = "5.00")) }

                fun numbers(book: network.lapis.cloud.shared.domain.KassenbuchDto) =
                    book.lines.associate { (it.voucherReference ?: "") to it.kassenbuchNumber }
                val ownBook = call(member = w.kw1) { fx.accounting(it).getKassenbuch(ledgerAccountId = w.cash.toString()) }
                numbers(ownBook) shouldBe mapOf("B1" to 1, "B3" to 2)
                val federalAll = call(member = w.treasurer) { fx.accounting(it).getKassenbuch(ledgerAccountId = w.cash.toString()) }
                numbers(federalAll) shouldBe mapOf("B1" to 1, "B2" to 1, "B3" to 2)
                numbers(
                    call(member = w.treasurer) {
                        fx.accounting(it).getKassenbuch(ledgerAccountId = w.cash.toString(), unitFilter = w.g1.toString())
                    },
                ) shouldBe mapOf("B1" to 1, "B3" to 2)
                numbers(
                    call(member = w.treasurer) {
                        fx.accounting(it).getKassenbuch(ledgerAccountId = w.cash.toString(), unitFilter = w.g2.toString())
                    },
                ) shouldBe mapOf("B2" to 1)

                // every line names the unit its number belongs to, so the duplicate "1" of B1/B2 stays unambiguous
                fun units(book: network.lapis.cloud.shared.domain.KassenbuchDto) =
                    book.lines.associate { (it.voucherReference ?: "") to (it.regionalChapterId to it.unitName) }
                val g1Name = call(member = w.treasurer) { fx.accounting(it).listBookingUnits() }.first { it.id == w.g1.toString() }.name
                val g2Name = call(member = w.treasurer) { fx.accounting(it).listBookingUnits() }.first { it.id == w.g2.toString() }.name
                call(
                    member = w.admin,
                ) { fx.accounting(it).postJournalEntry(input(w.cash, w.income, null, voucher = "B4", amount = "1.00")) }
                val withFederal = call(member = w.treasurer) { fx.accounting(it).getKassenbuch(ledgerAccountId = w.cash.toString()) }
                units(withFederal) shouldBe
                    mapOf(
                        "B1" to (w.g1.toString() to g1Name),
                        "B2" to (w.g2.toString() to g2Name),
                        "B3" to (w.g1.toString() to g1Name),
                        "B4" to (null to null),
                    )
                numbers(withFederal) shouldBe mapOf("B1" to 1, "B2" to 1, "B3" to 2, "B4" to 1)
                attempt(member = w.kw1) { fx.accounting(it).getKassenbuch(ledgerAccountId = w.cash.toString(), unitFilter = "FEDERAL") }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ForbiddenException>()
            }
        }

        test("executeErasure revokes the chapter grant AND leaves an audit entry for the end of the booking right") {
            val w = World()
            val subject = fx.member(label = "erased", role = AccountRole.MEMBER)
            val grantId = fx.grantTreasurer(member = subject, chapter = w.g1)
            partgTestApplication(fixtures = fx) {
                val request =
                    call(member = w.admin) {
                        DsgvoService(call = it).requestErasure(subjectMemberId = subject.toString(), reason = "Testmotion Art. 17 DSGVO")
                    }
                call(member = w.admin) { DsgvoService(call = it).decideErasure(requestId = request.id, approve = true) }
                transaction { RegionalChapterPersonalData.activeTreasurerGrants(subject).map { it.grantId } shouldBe listOf(grantId) }
                val executed = call(member = w.admin) { DsgvoService(call = it).executeErasure(requestId = request.id) }
                executed.status shouldBe ErasureStatus.COMPLETED
                transaction {
                    RegionalChapterPersonalData.activeTreasurerGrants(subject) shouldBe emptyList()
                    val entries =
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.REGIONAL_CHAPTER_TREASURER) and
                                    (AuditLogEntryTable.entityId eq grantId) and
                                    (AuditLogEntryTable.action eq AuditAction.UPDATE)
                            }.toList()
                    entries.size shouldBe 1
                }
            }
        }

        test("a chapter treasurer is Forbidden in every other accounting method") {
            val w = World()
            partgTestApplication(fixtures = fx) {
                val from = LocalDate(2026, 1, 1)
                val to = LocalDate(2026, 12, 31)
                val denied: List<suspend (io.ktor.server.application.ApplicationCall) -> Any?> =
                    listOf(
                        { fx.accounting(it).getIncomeStatement(from = from, to = to) },
                        { fx.accounting(it).getBalanceSheet(to) },
                        { fx.accounting(it).getFourSphereIncomeStatement(from = from, to = to) },
                        { fx.accounting(it).getCostCenterReport(from = from, to = to) },
                        { fx.accounting(it).getDonationDutyReport(2026) },
                        { fx.accounting(it).getUseOfFundsStatement(fromFiscalYear = 2025, toFiscalYear = 2026) },
                        { fx.accounting(it).getAnnualFinancialStatement(2026) },
                        { fx.accounting(it).previewDatevExport(from = from, to = to) },
                        { fx.accounting(it).getVatReturnPreview(from = from, to = to) },
                        { fx.accounting(it).getFinanceSetupStatus() },
                        { fx.accounting(it).listExternalDonors() },
                        { fx.accounting(it).getExternalDonor(Uuid.random().toString()) },
                        { fx.accounting(it).createStandardChart() },
                        { fx.accounting(it).createStandardPartyChart() },
                        { fx.accounting(it).deactivateLedgerAccount(w.bank.toString()) },
                        {
                            fx
                                .accounting(
                                    it,
                                ).createLedgerAccount(
                                    LedgerAccountInput(
                                        accountNumber = "99999",
                                        name = "x",
                                        accountClass = 9,
                                        type = LedgerAccountType.ASSET,
                                    ),
                                )
                        },
                    )
                denied.forEachIndexed { index, op ->
                    attempt(member = w.kw1) { op(it) }.exceptionOrNull().let { failure ->
                        (failure is ForbiddenException) shouldBe true
                    }
                    if (index < 0) error("unreachable")
                }
            }
        }

        test("a revoke is effective at once, a status change away from member ends the access") {
            val w = World()
            partgTestApplication(fixtures = fx) {
                call(member = w.kw1) { fx.accounting(it).listJournal() }
                fx.revokeTreasurer(w.grant1)
                attempt(member = w.kw1) { fx.accounting(it).listJournal() }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                // a fresh grant, then the member leaves
                val other = fx.member(label = "kw-status", role = AccountRole.MEMBER)
                fx.grantTreasurer(member = other, chapter = w.g1)
                call(member = other) { fx.accounting(it).listJournal() }
                transaction { MemberTable.update({ MemberTable.id eq other }) { it[status] = MemberStatus.WITHDRAWN } }
                attempt(member = other) { fx.accounting(it).listJournal() }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
            }
        }

        test("the role wins first: TREASURER with a grant is federal; BOARD with a grant reads all but gains no write right") {
            val w = World()
            partgTestApplication(fixtures = fx) {
                fx.grantTreasurer(member = w.treasurer, chapter = w.g1)
                fx.grantTreasurer(member = w.board, chapter = w.g1)
                val federalPost =
                    call(member = w.treasurer) { fx.accounting(it).postJournalEntry(input(w.bank, w.income, null, "federal-by-treasurer")) }
                federalPost.regionalChapterId shouldBe null
                call(member = w.board) { fx.accounting(it).listJournal() }.any { it.id == federalPost.id } shouldBe true
                attempt(member = w.board) { fx.accounting(it).postJournalEntry(input(w.bank, w.income, w.g1)) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ForbiddenException>()
                // the session scopes of a BOARD/TREASURER stay empty
                transaction { fx.currentOf(memberId = w.board, role = AccountRole.BOARD).activeChapterTreasurerIds() } shouldBe emptySet()
                transaction { fx.currentOf(memberId = w.treasurer, role = AccountRole.TREASURER).activeChapterTreasurerIds() } shouldBe
                    emptySet()
            }
        }

        test("several grants show the union, nothing else; listBookingUnits lists exactly the granted chapters") {
            val w = World()
            partgTestApplication(fixtures = fx) {
                val e1 = call(member = w.admin) { fx.accounting(it).postJournalEntry(input(w.bank, w.income, w.g1, "u1")) }
                val e2 = call(member = w.admin) { fx.accounting(it).postJournalEntry(input(w.bank, w.income, w.g2, "u2")) }
                val e3 = call(member = w.admin) { fx.accounting(it).postJournalEntry(input(w.bank, w.income, w.g3, "u3")) }
                val ids = call(member = w.kw13) { fx.accounting(it).listJournal() }.map(JournalEntryDto::id).toSet()
                ids shouldBe setOf(e1.id, e3.id)
                (e2.id in ids) shouldBe false
                call(member = w.kw13) { fx.accounting(it).listBookingUnits() }.map { it.id } shouldContainExactlyInAnyOrder
                    listOf(w.g1.toString(), w.g3.toString())
                call(member = w.kw1) { fx.accounting(it).listBookingUnits() }.map { it.id } shouldBe listOf(w.g1.toString())
                call(member = w.treasurer) { fx.accounting(it).listBookingUnits() }.map { it.id }.toSet().containsAll(
                    setOf(w.g1.toString(), w.g2.toString(), w.g3.toString()),
                ) shouldBe true
                attempt(
                    member = w.plain,
                ) { fx.accounting(it).listBookingUnits() }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
            }
        }

        test("a federal booking into a chapter that does not exist is NotFound, never a foreign-key error") {
            val w = World()
            partgTestApplication(fixtures = fx) {
                attempt(member = w.treasurer) { fx.accounting(it).postJournalEntry(input(w.bank, w.income, Uuid.random())) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
                attempt(member = w.treasurer) { fx.accounting(it).saveDraftEntry(input(w.bank, w.income, Uuid.random())) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<NotFoundException>()
            }
        }

        test("the cash check counts per (account, chapter): the stock of one unit never covers a withdrawal of another") {
            val w = World()
            partgTestApplication(fixtures = fx) {
                // deposit 100 into the cash account of chapter 1
                call(
                    member = w.admin,
                ) { fx.accounting(it).postJournalEntry(input(w.cash, w.income, w.g1, voucher = "D1", amount = "100.00")) }
                // a federal withdrawal (credit of the cash account) is NOT covered by chapter 1's stock
                val withdrawal =
                    JournalEntryInput(
                        entryDate = day,
                        description = "Auszahlung Bund",
                        voucherReference = "A1",
                        postings =
                            listOf(
                                PostingInput(
                                    ledgerAccountId = w.income.toString(),
                                    side = PostingSide.DEBIT,
                                    amount = BigDecimal("30.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                                PostingInput(
                                    ledgerAccountId = w.cash.toString(),
                                    side = PostingSide.CREDIT,
                                    amount = BigDecimal("30.00"),
                                    sphere = GemeinnuetzigkeitSphere.IDEELLER_BEREICH,
                                ),
                            ),
                    )
                attempt(member = w.treasurer) { fx.accounting(it).postJournalEntry(withdrawal) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<ConflictException>()
                // the same withdrawal in chapter 1 is covered
                call(member = w.kw1) { fx.accounting(it).postJournalEntry(withdrawal.copy(regionalChapterId = w.g1.toString())) }
            }
        }

        test("the audit payload of a federal entry does not carry the chapter field (byte-identical to before); a chapter entry does") {
            val w = World()
            partgTestApplication(fixtures = fx) {
                val federal =
                    call(member = w.treasurer) { fx.accounting(it).postJournalEntry(input(w.bank, w.income, null, "audit-federal")) }
                val chapter = call(member = w.admin) { fx.accounting(it).postJournalEntry(input(w.bank, w.income, w.g1, "audit-chapter")) }

                fun after(entryId: String): String =
                    transaction {
                        AuditLogEntryTable
                            .selectAll()
                            .where {
                                (AuditLogEntryTable.entityType eq AuditEntityType.JOURNAL_ENTRY) and
                                    (AuditLogEntryTable.entityId eq Uuid.parse(entryId))
                            }.single()[AuditLogEntryTable.afterSnapshot]
                            .orEmpty()
                    }
                after(federal.id) shouldNotContain "regionalChapterId"
                after(chapter.id).contains("\"regionalChapterId\":\"${w.g1}\"") shouldBe true
                // the stored column
                transaction {
                    JournalEntryTable
                        .selectAll()
                        .where {
                            JournalEntryTable.id eq
                                Uuid.parse(
                                    federal.id,
                                )
                        }.single()[JournalEntryTable.regionalChapterId]
                } shouldBe null
            }
        }
    })
