package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.AuditLogEntryTable
import network.lapis.cloud.server.db.generated.ExternalDonorTable
import network.lapis.cloud.server.db.generated.JournalEntryTable
import network.lapis.cloud.server.db.generated.MemberTable
import network.lapis.cloud.server.db.generated.PartgReportAnnexTable
import network.lapis.cloud.server.db.generated.PartgReportLargeDonorTable
import network.lapis.cloud.server.db.generated.PostingTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.AuditEntityType
import network.lapis.cloud.shared.domain.DonorCategory
import network.lapis.cloud.shared.domain.DonorType
import network.lapis.cloud.shared.domain.GemeinnuetzigkeitSphere
import network.lapis.cloud.shared.domain.JournalEntryStatus
import network.lapis.cloud.shared.domain.LedgerAccountType
import network.lapis.cloud.shared.domain.PartgAnnexDto
import network.lapis.cloud.shared.domain.PartgBookkeepingMode
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgDonorProposalAction
import network.lapis.cloud.shared.domain.PartgDonorProposalAuditSnapshot
import network.lapis.cloud.shared.domain.PartgDonorProposalAvailability
import network.lapis.cloud.shared.domain.PartgDonorProposalBlock
import network.lapis.cloud.shared.domain.PartgDonorProposalDto
import network.lapis.cloud.shared.domain.PartgDonorProposalPickDto
import network.lapis.cloud.shared.domain.PartgDonorProposalRowDto
import network.lapis.cloud.shared.domain.PartgDonorProposalStatus
import network.lapis.cloud.shared.domain.PartgDraftDeviation
import network.lapis.cloud.shared.domain.PartgFindingCode
import network.lapis.cloud.shared.domain.PartgLargeDonorInputDto
import network.lapis.cloud.shared.domain.PartgLargeDonorOrigin
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgValueChangeDto
import network.lapis.cloud.shared.domain.PostingSide
import network.lapis.cloud.shared.domain.VatRate
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.PartyReportInvalidInputException
import network.lapis.cloud.shared.rpc.PartyReportNotEnabledException
import network.lapis.cloud.shared.rpc.PartyReportProposalNotAvailableException
import network.lapis.cloud.shared.rpc.PartyReportProposalStaleException
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

/**
 * V1.9.293 (R5b) -- the proposal for statement B from the bookings of the FEDERAL unit, on H2 AND PostgreSQL with the same assertions: yearly sums per
 * donor strictly above 10,000.00 EUR, the statuses and actions, the take-over (create, replace the amount, link), the recomputation under the lock
 * (stale), manipulated picks, the cap, access, master-data problems, the audit payload, that no booking is touched, the effect on kept versions
 * and the consolidation hints, parity with the donation duty report and two parallel take-overs.
 *
 * Own report year (2005); bookings are inserted directly (the posting rules are tested elsewhere).
 */
abstract class PartgReportR5bScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = PartgFixtures()
        val year = 2005
        val federal = PartgUnit.FEDERAL_KEY
        val externalIds = mutableListOf<Uuid>()
        val bookedAt = LocalDateTime(2005, 6, 1, 12, 0)

        fun cleanAll() {
            PartgAnnexWriter.afterLockHook = {}
            fx.cleanUp()
            transaction { ExternalDonorTable.deleteWhere { ExternalDonorTable.id inList externalIds } }
            externalIds.clear()
        }

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = true)
        beforeTest { fx.setParty(true) }
        afterTest { cleanAll() }
        afterSpec {
            cleanAll()
            db.deactivate()
        }

        class World {
            val board = fx.member(label = "board", role = AccountRole.BOARD)
            val admin = fx.member(label = "admin", role = AccountRole.ADMIN)
            val bank = fx.ledgerAccount(type = LedgerAccountType.ASSET)
            val income = fx.ledgerAccount(type = LedgerAccountType.INCOME)
        }

        /** A member who can be a donor; the display name is "Partg-Test <label>". */
        fun donorMember(
            label: String,
            postal: String? = "38100",
            country: String? = "DE",
        ): Uuid {
            val id = fx.member(label = label, role = AccountRole.MEMBER)
            transaction {
                MemberTable.update({ MemberTable.id eq id }) {
                    it[street] = "Hauptstraße 1"
                    it[postalCode] = postal
                    it[city] = "Braunschweig"
                    it[countryCode] = country
                }
            }
            return id
        }

        fun externalDonor(
            name: String,
            category: DonorCategory = DonorCategory.GERMAN_COMPANY_OR_ORGANIZATION,
            country: String? = "DE",
            postal: String? = "10115",
        ): Uuid {
            val id = Uuid.random()
            transaction {
                ExternalDonorTable.insert {
                    it[ExternalDonorTable.id] = id
                    it[displayName] = name
                    it[donorCategory] = category
                    it[street] = "Industriestraße 5"
                    it[postalCode] = postal
                    it[city] = "Berlin"
                    it[ExternalDonorTable.country] = country
                    it[active] = true
                }
            }
            externalIds += id
            return id
        }

        /** One POSTED (or [status]) donation of [cents] at the federal level; [reversal] swaps the sides (a storno). */
        fun book(
            w: World,
            cents: Long,
            member: Uuid? = null,
            external: Uuid? = null,
            category: DonorCategory = DonorCategory.GERMAN_NATURAL_PERSON,
            on: LocalDate = LocalDate(year, 6, 1),
            chapter: Uuid? = null,
            status: JournalEntryStatus = JournalEntryStatus.POSTED,
            reversal: Boolean = false,
        ) {
            val entry = Uuid.random()
            val amount = BigDecimal(cents).movePointLeft(2)
            transaction {
                JournalEntryTable.insert {
                    it[id] = entry
                    it[entryDate] = on
                    it[description] = "Spende"
                    it[createdBy] = w.admin
                    it[JournalEntryTable.status] = status
                    it[postedAt] = if (status == JournalEntryStatus.POSTED) bookedAt else null
                    it[createdAt] = bookedAt
                    it[donorMemberId] = member
                    it[donorCategory] = category
                    it[externalDonorId] = external
                    it[regionalChapterId] = chapter
                }
                listOf(
                    w.bank to (if (reversal) PostingSide.CREDIT else PostingSide.DEBIT),
                    w.income to (if (reversal) PostingSide.DEBIT else PostingSide.CREDIT),
                ).forEach { (account, postingSide) ->
                    PostingTable.insert {
                        it[id] = Uuid.random()
                        it[side] = postingSide
                        it[PostingTable.amount] = amount
                        it[sphere] = GemeinnuetzigkeitSphere.IDEELLER_BEREICH
                        it[vatRate] = VatRate.UNCLASSIFIED
                        it[vatAmount] = BigDecimal.ZERO
                        it[journalEntryId] = entry
                        it[ledgerAccountId] = account
                    }
                }
            }
        }

        fun input(
            id: String? = null,
            name: String,
            cents: Long = 1_100_000L,
            postal: String? = "38100",
            kind: PartgContributionKind = PartgContributionKind.DONATION,
            personKind: PartgPersonKind = PartgPersonKind.NATURAL,
        ) = PartgLargeDonorInputDto(
            id = id,
            personKind = personKind,
            name = name,
            street = "Hauptstraße 1",
            postalCode = postal,
            city = "Braunschweig",
            country = "DE",
            contributionKind = kind,
            amountCents = cents,
        )

        suspend fun PartgApp.setModeA(w: World) {
            call(
                member = w.board,
            ) { service(it).setUnitMode(year = year, unitKey = federal, mode = PartgBookkeepingMode.A, confirmedBookingCount = 0) }
        }

        suspend fun PartgApp.proposal(member: Uuid) = call(member = member) { service(it).largeDonorProposal(year) }

        suspend fun PartgApp.take(
            member: Uuid,
            p: PartgDonorProposalDto,
            picks: List<PartgDonorProposalPickDto>,
        ): PartgAnnexDto =
            call(member = member) { service(it).applyLargeDonorProposal(year = year, expectedChecksum = p.checksum, picks = picks) }

        suspend fun PartgApp.addHandRow(
            member: Uuid,
            donor: PartgLargeDonorInputDto,
        ): PartgAnnexDto {
            val revision = call(member = member) { service(it).getAnnex(year = year, unitKey = federal) }.revision
            return call(
                member = member,
            ) { service(it).saveLargeDonor(year = year, unitKey = federal, expectedRevision = revision, donor = donor) }
        }

        fun annexRevision(): Long =
            transaction {
                PartgReportAnnexTable
                    .selectAll()
                    .where { (PartgReportAnnexTable.fiscalYear eq year) and (PartgReportAnnexTable.unitKey eq federal) }
                    .single()[PartgReportAnnexTable.revision]
            }

        /** Saves an edit of one entered row of the federal annex (as the board of [w]). */
        suspend fun PartgApp.editRow(
            w: World,
            id: String,
            donor: PartgLargeDonorInputDto,
        ): PartgAnnexDto {
            val revision = call(member = w.board) { service(it).getAnnex(year = year, unitKey = federal) }.revision
            return call(
                member = w.board,
            ) { service(it).saveLargeDonor(year = year, unitKey = federal, expectedRevision = revision, donor = donor) }
                .also { check(it.donors.any { d -> d.id == id }) }
        }

        fun PartgDonorProposalDto.of(name: String): PartgDonorProposalRowDto =
            rows.single { it.ref != null && it.name == "Partg-Test $name" }

        fun create(row: PartgDonorProposalRowDto) = PartgDonorProposalPickDto(ref = row.ref!!, action = PartgDonorProposalAction.CREATE)

        fun federalRows(): List<ResultRow> =
            transaction {
                (PartgReportLargeDonorTable innerJoin PartgReportAnnexTable)
                    .selectAll()
                    .where { (PartgReportAnnexTable.fiscalYear eq year) and (PartgReportAnnexTable.unitKey eq federal) }
                    .toList()
            }

        fun rowSnapshot(row: ResultRow): List<Any?> = PartgReportLargeDonorTable.columns.map { row[it] }

        fun annexCount(): Long =
            transaction { PartgReportAnnexTable.selectAll().where { PartgReportAnnexTable.fiscalYear eq year }.count() }

        fun proposalAudits(actor: Uuid): List<String> =
            transaction {
                AuditLogEntryTable
                    .selectAll()
                    .where { AuditLogEntryTable.entityType eq AuditEntityType.PARTG_LARGE_DONOR_PROPOSAL }
                    .filter { it[AuditLogEntryTable.actorMemberId] == actor }
                    .map { it[AuditLogEntryTable.afterSnapshot].orEmpty() }
            }

        fun ledgerRows(): List<List<Any?>> =
            transaction {
                JournalEntryTable.selectAll().orderBy(JournalEntryTable.id).map { r -> JournalEntryTable.columns.map { r[it] } } +
                    PostingTable.selectAll().orderBy(PostingTable.id).map { r -> PostingTable.columns.map { r[it] } }
            }

        // ------------------------------------------------------------------ the sums

        test(
            "6,000 + 6,000 EUR from one member is one NEW row of 1,200,000 cents; exactly 10,000.00 is not listed, 10,000.01 is; reversals lower the sum",
        ) {
            val w = World()
            val a = donorMember("Anna")
            val b = donorMember("Bernd")
            val c = donorMember("Carla")
            val d = donorMember("Dora")
            val f = donorMember("Fritz")
            val g = donorMember("Gerd")
            val h = donorMember("Hanna")
            val i = donorMember("Ida")
            val chapter = fx.chapter()
            partgTestApplication(fixtures = fx) {
                setModeA(w)
                book(w, 600_000L, member = a)
                book(w, 600_000L, member = a)
                book(w, 1_000_000L, member = b)
                book(w, 1_000_001L, member = c)
                book(w, 800_000L, member = d)
                book(w, 800_000L, member = d)
                book(w, 800_000L, member = d, reversal = true) // 8,000 left: not listed
                book(w, 700_000L, member = f)
                book(w, 700_000L, member = f)
                book(w, 300_000L, member = f, reversal = true) // 11,000 left
                book(w, 2_000_000L, member = g, status = JournalEntryStatus.DRAFT)
                book(w, 2_000_000L, member = h, on = LocalDate(year - 1, 6, 1))
                book(w, 2_000_000L, category = DonorCategory.ANONYMOUS)
                book(w, 2_000_000L, member = i, chapter = chapter)
                book(w, 2_000_000L) // a donation booking without any donor
                val p = proposal(w.board)
                p.rows.map { it.name } shouldContainExactly listOf("Partg-Test Anna", "Partg-Test Fritz", "Partg-Test Carla")
                p.of("Anna").bookedCents shouldBe 1_200_000L
                p.of("Fritz").bookedCents shouldBe 1_100_000L
                p.of("Carla").bookedCents shouldBe 1_000_001L
                p.rows.all { it.status == PartgDonorProposalStatus.NEW && it.preselected == PartgDonorProposalAction.CREATE } shouldBe true
                p.withoutDonorCount shouldBe 1
                p.anonymisedCount shouldBe 0
                p.annexRevision shouldBe 0L
                p.checksum.length shouldBe 64
                // nothing is written by reading
                annexCount() shouldBe 0L
            }
        }

        test("the booking reader refuses more entries than its cap with a clean error") {
            val w = World()
            val a = donorMember("Anna")
            book(w, 100L, member = a)
            book(w, 100L, member = a)
            book(w, 100L, member = a)
            runCatching { transaction { PartgLedgerReader.federalDonationTotals(year = year, maxEntries = 2) } }
                .exceptionOrNull()
                .let { (it as? PartyReportInvalidInputException)?.message } shouldBe "TOO_MANY_BOOKINGS"
            transaction { PartgLedgerReader.federalDonationTotals(year = year, maxEntries = 3) }.totals.size shouldBe 1
        }

        // ------------------------------------------------------------------ create, replace, link

        test(
            "CREATE takes name and address from the master data, sets the key and the time; a hand change turns it into 'changed afterwards' and keeps the key",
        ) {
            val w = World()
            val a = donorMember("Anna")
            partgTestApplication(fixtures = fx) {
                setModeA(w)
                book(w, 600_000L, member = a)
                book(w, 600_000L, member = a)
                val p = proposal(w.board)
                val row = p.of("Anna")
                row.personKind shouldBe PartgPersonKind.NATURAL
                row.street shouldBe "Hauptstraße 1"
                row.postalCode shouldBe "38100"
                row.city shouldBe "Braunschweig"
                row.country shouldBe "DE"
                row.ref shouldBe "M:$a"
                val annex = take(w.board, p, listOf(create(row)))
                annex.revision shouldBe 1L
                annex.donorProposal shouldBe PartgDonorProposalAvailability.AVAILABLE
                val donor = annex.donors.single()
                donor.origin shouldBe PartgLargeDonorOrigin.LEDGER
                donor.sourceAppliedAt shouldNotBe null
                donor.contributionKind shouldBe PartgContributionKind.DONATION
                donor.amountCents shouldBe 1_200_000L
                donor.name shouldBe "Partg-Test Anna"
                federalRows().single()[PartgReportLargeDonorTable.sourceDonorKey] shouldBe "M:$a"
                proposal(w.board).rows.single().status shouldBe PartgDonorProposalStatus.UNCHANGED
                Thread.sleep(30)
                val edited = editRow(w, donor.id, input(id = donor.id, name = "Partg-Test Anna", cents = 1_150_000L))
                val after = edited.donors.single()
                after.origin shouldBe PartgLargeDonorOrigin.LEDGER_EDITED
                after.sourceAppliedAt shouldBe donor.sourceAppliedAt
                federalRows().single()[PartgReportLargeDonorTable.sourceDonorKey] shouldBe "M:$a"
            }
        }

        test("REPLACE_AMOUNT sets the amount and the take-over time; a row with fees is not transferable and the server refuses the pick") {
            val w = World()
            val a = donorMember("Anna")
            partgTestApplication(fixtures = fx) {
                setModeA(w)
                book(w, 600_000L, member = a)
                book(w, 600_000L, member = a)
                take(w.board, proposal(w.board), listOf(create(proposal(w.board).of("Anna"))))
                book(w, 100_000L, member = a)
                val p = proposal(w.board)
                val row = p.of("Anna")
                row.status shouldBe PartgDonorProposalStatus.DIFFERENT
                row.bookedCents shouldBe 1_300_000L
                row.enteredCents shouldBe 1_200_000L
                row.allowedActions shouldBe setOf(PartgDonorProposalAction.REPLACE_AMOUNT)
                row.preselected shouldBe null
                Thread.sleep(30)
                val annex =
                    take(w.board, p, listOf(PartgDonorProposalPickDto(ref = row.ref!!, action = PartgDonorProposalAction.REPLACE_AMOUNT)))
                annex.donors.single().amountCents shouldBe 1_300_000L
                annex.donors.single().origin shouldBe PartgLargeDonorOrigin.LEDGER
                annex.revision shouldBe 2L
                // a row with fees: the amount is not replaced
                Thread.sleep(30)
                val id = annex.donors.single().id
                editRow(w, id, input(id = id, name = "Partg-Test Anna", cents = 1_250_000L, kind = PartgContributionKind.BOTH))
                val blocked = proposal(w.board)
                blocked.of("Anna").status shouldBe PartgDonorProposalStatus.NOT_TRANSFERABLE
                blocked.of("Anna").block shouldBe PartgDonorProposalBlock.CONTAINS_FEES
                attempt(member = w.board) {
                    service(it).applyLargeDonorProposal(
                        year = year,
                        expectedChecksum = blocked.checksum,
                        picks =
                            listOf(
                                PartgDonorProposalPickDto(ref = blocked.of("Anna").ref!!, action = PartgDonorProposalAction.REPLACE_AMOUNT),
                            ),
                    )
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>()
                federalRows().single()[PartgReportLargeDonorTable.amountCents] shouldBe 1_250_000L
            }
        }

        test("LINK sets only the key: amount, updated_at and updated_by stay; an empty postal code finds no similar row") {
            val w = World()
            val bea = donorMember("Bea")
            val cleo = donorMember("Cleo")
            partgTestApplication(fixtures = fx) {
                setModeA(w)
                book(w, 1_200_000L, member = bea)
                book(w, 1_200_000L, member = cleo)
                addHandRow(w.board, input(name = "Partg-Test Bea", cents = 1_100_000L))
                addHandRow(w.board, input(name = "Partg-Test Cleo", cents = 1_100_000L, postal = null))
                val hand = federalRows().first { it[PartgReportLargeDonorTable.name] == "Partg-Test Bea" }
                val before = rowSnapshot(hand)
                val p = proposal(w.board)
                p.of("Cleo").status shouldBe PartgDonorProposalStatus.NEW
                val row = p.of("Bea")
                row.status shouldBe PartgDonorProposalStatus.SIMILAR
                row.similarRowIds shouldContainExactly listOf(hand[PartgReportLargeDonorTable.id].toString())
                row.allowedActions shouldBe setOf(PartgDonorProposalAction.LINK, PartgDonorProposalAction.CREATE)
                row.preselected shouldBe null
                val revisionBefore = annexRevision()
                Thread.sleep(30)
                take(
                    w.board,
                    p,
                    listOf(
                        PartgDonorProposalPickDto(
                            ref = row.ref!!,
                            action = PartgDonorProposalAction.LINK,
                            targetRowId = row.similarRowIds.single(),
                        ),
                    ),
                )
                val after = federalRows().first { it[PartgReportLargeDonorTable.id] == hand[PartgReportLargeDonorTable.id] }
                after[PartgReportLargeDonorTable.sourceDonorKey] shouldBe "M:$bea"
                after[PartgReportLargeDonorTable.sourceAppliedAt] shouldBe null
                after[PartgReportLargeDonorTable.amountCents] shouldBe 1_100_000L
                after[PartgReportLargeDonorTable.updatedAt] shouldBe
                    before[PartgReportLargeDonorTable.columns.indexOf(PartgReportLargeDonorTable.updatedAt)]
                after[PartgReportLargeDonorTable.updatedBy] shouldBe hand[PartgReportLargeDonorTable.updatedBy]
                annexRevision() shouldBe revisionBefore + 1
                val next = proposal(w.board)
                next.of("Bea").status shouldBe PartgDonorProposalStatus.DIFFERENT
                next.of("Bea").linkedRowId shouldBe hand[PartgReportLargeDonorTable.id].toString()
            }
        }

        test(
            "rows entered by hand are never changed or deleted by a take-over; a linked row whose bookings fell below the threshold is shown as such",
        ) {
            val w = World()
            val a = donorMember("Anna")
            val z = donorMember("Zoe")
            partgTestApplication(fixtures = fx) {
                setModeA(w)
                addHandRow(w.board, input(name = "Handperson Eins", cents = 3_000_000L))
                addHandRow(w.board, input(name = "Handperson Zwei", cents = 1_500_000L, postal = null))
                book(w, 600_000L, member = a)
                book(w, 600_000L, member = a)
                val hands = federalRows().map(::rowSnapshot).toSet()
                val p = proposal(w.board)
                p.rows.filter { it.status == PartgDonorProposalStatus.ENTERED_ONLY }.map { it.name } shouldContain "Handperson Eins"
                p.rows
                    .filter {
                        it.status == PartgDonorProposalStatus.ENTERED_ONLY
                    }.all { it.ref == null && it.allowedActions.isEmpty() } shouldBe
                    true
                take(w.board, p, listOf(create(p.of("Anna"))))
                federalRows().map(::rowSnapshot).toSet().containsAll(hands) shouldBe true
                federalRows().size shouldBe 3
                // the bookings of the linked row drop below the threshold
                book(w, 600_000L, member = a, reversal = true)
                val low = proposal(w.board)
                val shown = low.rows.single { it.name == "Partg-Test Anna" }
                shown.status shouldBe PartgDonorProposalStatus.ENTERED_ONLY
                shown.linkedBelowThreshold shouldBe true
                z shouldNotBe a
            }
        }

        // ------------------------------------------------------------------ stale, tamper, cap

        test("a changed proposal is stale: nothing is written, not even a new annex") {
            val w = World()
            val a = donorMember("Anna")
            val b = donorMember("Bernd")
            partgTestApplication(fixtures = fx) {
                setModeA(w)
                book(w, 1_200_000L, member = a)
                val p = proposal(w.board)
                annexCount() shouldBe 0L
                book(w, 1_200_000L, member = b) // a new donation between loading and taking over
                attempt(member = w.board) {
                    service(it).applyLargeDonorProposal(year = year, expectedChecksum = p.checksum, picks = listOf(create(p.of("Anna"))))
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportProposalStaleException>()
                annexCount() shouldBe 0L
                federalRows().shouldBeEmpty()
                proposalAudits(w.board).shouldBeEmpty()
                // an entered row that changes in the meantime has the same effect
                val fresh = proposal(w.board)
                take(w.board, fresh, listOf(create(fresh.of("Anna"))))
                val second = proposal(w.board)
                addHandRow(w.board, input(name = "Handperson", cents = 2_000_000L))
                val rows = federalRows().size
                val revision = annexRevision()
                attempt(member = w.board) {
                    service(
                        it,
                    ).applyLargeDonorProposal(year = year, expectedChecksum = second.checksum, picks = listOf(create(second.of("Bernd"))))
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportProposalStaleException>()
                federalRows().size shouldBe rows
                annexRevision() shouldBe revision
                proposalAudits(w.board).size shouldBe 1
            }
        }

        test("manipulated picks are refused with InvalidInput and nothing is written") {
            val w = World()
            val a = donorMember("Anna")
            val b = donorMember("Bernd")
            val c = donorMember("Carla")
            val d = donorMember("Dora")
            val chapter = fx.chapter()
            partgTestApplication(fixtures = fx) {
                setModeA(w)
                book(w, 1_200_000L, member = a) // NEW
                book(w, 1_200_000L, member = b) // becomes UNCHANGED
                take(w.board, proposal(w.board), listOf(create(proposal(w.board).of("Bernd"))))
                book(w, 1_200_000L, member = c) // SIMILAR to a hand row
                book(w, 1_200_000L, member = d) // SIMILAR to the same hand row
                addHandRow(w.board, input(name = "Partg-Test Carla", cents = 1_000_100L))
                // the names of C and D are different, so make D look like C: a second donor with the same name is not possible, use the same hand row
                transaction { MemberTable.update({ MemberTable.id eq d }) { it[displayName] = "Partg-Test Carla" } }
                // a row of another annex (a state unit) as a foreign target
                val stateKey = PartgUnit.State(chapter.toString()).key()
                val foreign =
                    call(member = w.board) {
                        service(it).saveLargeDonor(year = year, unitKey = stateKey, expectedRevision = 0, donor = input(name = "Fremd"))
                    }.donors.single().id
                val p = proposal(w.board)
                val rowA = p.of("Anna")
                val rowB = p.of("Bernd")
                val similar = p.rows.filter { it.status == PartgDonorProposalStatus.SIMILAR }
                similar.size shouldBe 2
                val target = similar.first().similarRowIds.single()
                val rowsBefore = federalRows().map(::rowSnapshot).toSet()
                val revisionBefore = annexRevision()
                val unknownRef = "M:" + Uuid.random()

                suspend fun refused(
                    vararg picks: PartgDonorProposalPickDto,
                    checksum: String = p.checksum,
                ) {
                    attempt(member = w.board) {
                        service(it).applyLargeDonorProposal(year = year, expectedChecksum = checksum, picks = picks.toList())
                    }.exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>()
                }
                refused(PartgDonorProposalPickDto(ref = unknownRef, action = PartgDonorProposalAction.CREATE))
                refused(PartgDonorProposalPickDto(ref = rowA.ref!!, action = PartgDonorProposalAction.REPLACE_AMOUNT))
                refused(PartgDonorProposalPickDto(ref = rowA.ref!!, action = PartgDonorProposalAction.LINK, targetRowId = target))
                refused(PartgDonorProposalPickDto(ref = rowB.ref!!, action = PartgDonorProposalAction.CREATE))
                refused(PartgDonorProposalPickDto(ref = similar.first().ref!!, action = PartgDonorProposalAction.LINK))
                refused(
                    PartgDonorProposalPickDto(ref = similar.first().ref!!, action = PartgDonorProposalAction.LINK, targetRowId = foreign),
                )
                refused(
                    PartgDonorProposalPickDto(
                        ref = similar.first().ref!!,
                        action = PartgDonorProposalAction.LINK,
                        targetRowId = Uuid.random().toString(),
                    ),
                )
                refused(create(rowA), create(rowA))
                refused(
                    PartgDonorProposalPickDto(ref = similar[0].ref!!, action = PartgDonorProposalAction.LINK, targetRowId = target),
                    PartgDonorProposalPickDto(ref = similar[1].ref!!, action = PartgDonorProposalAction.LINK, targetRowId = target),
                )
                refused(
                    *(0..1000)
                        .map {
                            PartgDonorProposalPickDto(
                                ref = "M:" + Uuid.random(),
                                action = PartgDonorProposalAction.CREATE,
                            )
                        }.toTypedArray(),
                )
                refused()
                refused(create(rowA), checksum = "x".repeat(64))
                refused(create(rowA), checksum = p.checksum.uppercase())
                refused(PartgDonorProposalPickDto(ref = "not-a-ref", action = PartgDonorProposalAction.CREATE))
                federalRows().map(::rowSnapshot).toSet() shouldBe rowsBefore
                annexRevision() shouldBe revisionBefore
                proposalAudits(w.board).size shouldBe 1 // the first, valid take-over only
            }
        }

        test("the cap of 500 rows: 499 rows plus two new ones is refused and nothing is written") {
            val w = World()
            val a = donorMember("Anna")
            val b = donorMember("Bernd")
            partgTestApplication(fixtures = fx) {
                setModeA(w)
                val annexId = Uuid.random()
                transaction {
                    PartgReportAnnexTable.insert {
                        it[id] = annexId
                        it[fiscalYear] = year
                        it[unitLevel] = network.lapis.cloud.shared.domain.PartgUnitLevel.FEDERAL
                        it[unitKey] = federal
                        it[revision] = 0L
                        it[updatedAt] = bookedAt
                        it[updatedBy] = w.board
                    }
                    repeat(499) { n ->
                        PartgReportLargeDonorTable.insert {
                            it[id] = Uuid.random()
                            it[PartgReportLargeDonorTable.annexId] = annexId
                            it[personKind] = PartgPersonKind.NATURAL
                            it[name] = "Masse $n"
                            it[country] = "DE"
                            it[contributionKind] = PartgContributionKind.DONATION
                            it[amountCents] = 1_000_001L + n
                            it[createdAt] = bookedAt
                            it[createdBy] = w.board
                            it[updatedAt] = bookedAt
                            it[updatedBy] = w.board
                        }
                    }
                }
                book(w, 1_200_000L, member = a)
                book(w, 1_200_000L, member = b)
                val p = proposal(w.board)
                attempt(member = w.board) {
                    service(it).applyLargeDonorProposal(
                        year = year,
                        expectedChecksum = p.checksum,
                        picks = listOf(create(p.of("Anna")), create(p.of("Bernd"))),
                    )
                }.exceptionOrNull().let { (it as? PartyReportInvalidInputException)?.message } shouldBe "TOO_MANY"
                federalRows().size shouldBe 499
                // one of them fits
                take(w.board, p, listOf(create(p.of("Anna"))))
                federalRows().size shouldBe 500
            }
        }

        // ------------------------------------------------------------------ access

        test("mode C, a switched-off party report and every role below the board") {
            val w = World()
            val a = donorMember("Anna")
            val treasurer = fx.member(label = "treasurer", role = AccountRole.TREASURER)
            val plain = fx.member(label = "plain", role = AccountRole.MEMBER)
            val kw = fx.member(label = "kw", role = AccountRole.MEMBER)
            fx.grantTreasurer(member = kw, chapter = fx.chapter())
            partgTestApplication(fixtures = fx) {
                book(w, 1_200_000L, member = a)
                val ref = "M:$a"
                val picks = listOf(PartgDonorProposalPickDto(ref = ref, action = PartgDonorProposalAction.CREATE))
                // mode C is the default
                attempt(member = w.board) { service(it).largeDonorProposal(year) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportProposalNotAvailableException>()
                attempt(
                    member = w.board,
                ) { service(it).applyLargeDonorProposal(year = year, expectedChecksum = "a".repeat(64), picks = picks) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportProposalNotAvailableException>()
                annexCount() shouldBe 0L
                call(member = w.board) { service(it).getAnnex(year = year, unitKey = federal) }.donorProposal shouldBe
                    PartgDonorProposalAvailability.MODE_C
                call(member = w.board) {
                    service(it).getAnnex(year = year, unitKey = PartgUnit.State(fx.chapter().toString()).key())
                }.donorProposal shouldBe PartgDonorProposalAvailability.NOT_APPLICABLE
                listOf(treasurer, plain, kw).forEach { who ->
                    attempt(
                        member = who,
                    ) { service(it).largeDonorProposal(year) }.exceptionOrNull().shouldBeInstanceOf<ForbiddenException>()
                    attempt(
                        member = who,
                    ) { service(it).applyLargeDonorProposal(year = year, expectedChecksum = "a".repeat(64), picks = picks) }
                        .exceptionOrNull()
                        .shouldBeInstanceOf<ForbiddenException>()
                }
                fx.setParty(false)
                attempt(member = w.board) { service(it).largeDonorProposal(year) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportNotEnabledException>()
                attempt(
                    member = w.board,
                ) { service(it).applyLargeDonorProposal(year = year, expectedChecksum = "a".repeat(64), picks = picks) }
                    .exceptionOrNull()
                    .shouldBeInstanceOf<PartyReportNotEnabledException>()
            }
        }

        // ------------------------------------------------------------------ master data

        test(
            "master data: anonymised members are counted only, a country name is no country, a long name is invalid, mixed categories are unclear",
        ) {
            val w = World()
            val anon = donorMember("Anonym")
            val mixed = donorMember("Gemischt")
            val long = externalDonor("L".repeat(201))
            val land = externalDonor("Firma Deutschland", country = "Deutschland")
            val shortPostal = donorMember("PlzLang", postal = "123456789012")
            transaction { MemberTable.update({ MemberTable.id eq anon }) { it[anonymizedAt] = bookedAt } }
            partgTestApplication(fixtures = fx) {
                setModeA(w)
                book(w, 1_200_000L, member = anon)
                book(w, 600_000L, member = mixed)
                book(w, 600_000L, member = mixed, category = DonorCategory.GERMAN_COMPANY_OR_ORGANIZATION)
                book(w, 1_200_000L, external = long, category = DonorCategory.GERMAN_COMPANY_OR_ORGANIZATION)
                book(w, 1_200_000L, external = land, category = DonorCategory.GERMAN_COMPANY_OR_ORGANIZATION)
                book(w, 1_200_000L, member = shortPostal)
                val p = proposal(w.board)
                p.anonymisedCount shouldBe 1
                Json.encodeToString(PartgDonorProposalDto.serializer(), p) shouldNotContain "Anonym"
                p.of("Gemischt").block shouldBe PartgDonorProposalBlock.KIND_UNCLEAR
                p.rows.single { it.name == "L".repeat(201) }.block shouldBe PartgDonorProposalBlock.MASTER_DATA_INVALID
                p.of("PlzLang").block shouldBe PartgDonorProposalBlock.MASTER_DATA_INVALID
                p.rows.single { it.name == "Firma Deutschland" }.block shouldBe PartgDonorProposalBlock.COUNTRY_MISSING
                p.rows.all { it.status == PartgDonorProposalStatus.NOT_TRANSFERABLE && it.allowedActions.isEmpty() } shouldBe true
                // nothing of this can be picked
                attempt(member = w.board) {
                    service(it).applyLargeDonorProposal(
                        year = year,
                        expectedChecksum = p.checksum,
                        picks = listOf(create(p.of("Gemischt"))),
                    )
                }.exceptionOrNull().shouldBeInstanceOf<PartyReportInvalidInputException>()
            }
        }

        test("an anonymised member with a linked row: the row is no ENTERED_ONLY row and the donor is only counted") {
            val w = World()
            val bea = donorMember("Bea")
            partgTestApplication(fixtures = fx) {
                setModeA(w)
                book(w, 1_200_000L, member = bea)
                addHandRow(w.board, input(name = "Partg-Test Bea", cents = 1_100_000L))
                val hand = federalRows().first { it[PartgReportLargeDonorTable.name] == "Partg-Test Bea" }
                val row = proposal(w.board).of("Bea")
                take(
                    w.board,
                    proposal(w.board),
                    listOf(
                        PartgDonorProposalPickDto(
                            ref = row.ref!!,
                            action = PartgDonorProposalAction.LINK,
                            targetRowId = hand[PartgReportLargeDonorTable.id].toString(),
                        ),
                    ),
                )
                transaction { MemberTable.update({ MemberTable.id eq bea }) { it[anonymizedAt] = bookedAt } }
                val p = proposal(w.board)
                p.anonymisedCount shouldBe 1
                p.rows.none { it.linkedBelowThreshold } shouldBe true
                p.rows.none { it.linkedRowId == hand[PartgReportLargeDonorTable.id].toString() } shouldBe true
            }
        }

        // ------------------------------------------------------------------ audit, bookings, versions, hints

        test("one audit entry with counters and revisions only; the bookings are untouched") {
            val w = World()
            val a = donorMember("Anna")
            val b = donorMember("Bernd")
            partgTestApplication(fixtures = fx) {
                setModeA(w)
                book(w, 600_000L, member = a)
                book(w, 600_000L, member = a)
                book(w, 1_200_000L, member = b)
                addHandRow(w.board, input(name = "Partg-Test Bernd", cents = 1_100_000L))
                val ledgerBefore = ledgerRows()
                val p = proposal(w.board)
                val bRow = p.of("Bernd")
                take(
                    w.board,
                    p,
                    listOf(
                        create(p.of("Anna")),
                        PartgDonorProposalPickDto(
                            ref = bRow.ref!!,
                            action = PartgDonorProposalAction.LINK,
                            targetRowId = bRow.similarRowIds.single(),
                        ),
                    ),
                )
                ledgerRows() shouldBe ledgerBefore
                val audits = proposalAudits(w.board)
                audits.size shouldBe 1
                val snapshot = Json.decodeFromString(PartgDonorProposalAuditSnapshot.serializer(), audits.single())
                snapshot.created shouldBe 1
                snapshot.replaced shouldBe 0
                snapshot.linked shouldBe 1
                snapshot.unitKey shouldBe federal
                snapshot.annexRevisionAfter shouldBe snapshot.annexRevisionBefore + 1
                snapshot.annexRevisionAfter shouldBe annexRevision()
                listOf("Partg-Test", "M:", "X:", a.toString(), b.toString(), "1200000", "12000", "checksum").forEach {
                    audits.single() shouldNotContain it
                }
            }
        }

        test("kept versions: LINK leaves 'version unchanged', REPLACE shows a deviation") {
            val w = World()
            val a = donorMember("Anna")
            val b = donorMember("Bernd")
            partgTestApplication(fixtures = fx) {
                setModeA(w)
                // the report needs a value to be kept
                val sheet = call(member = w.board) { service(it).getSheet(year = year, unitKey = federal) }
                call(member = w.board) {
                    service(it).saveSheet(
                        year = year,
                        unitKey = federal,
                        expectedRevision = sheet.revision,
                        expectedPriorRevision = sheet.priorRevision,
                        changes = listOf(PartgValueChangeDto(year = year, positionCode = "E01", amountCents = 100_00L)),
                    )
                }
                book(w, 1_200_000L, member = a)
                book(w, 1_200_000L, member = b)
                addHandRow(w.board, input(name = "Partg-Test Bernd", cents = 1_100_000L))
                val first = proposal(w.board)
                take(w.board, first, listOf(create(first.of("Anna"))))
                call(member = w.board) { service(it).freezeVersion(year = year, scopeKey = "ALL", comment = null) }
                call(member = w.board) { service(it).versionOverview(year) }.draftDeviation shouldBe PartgDraftDeviation.SAME
                val p = proposal(w.board)
                val bRow = p.of("Bernd")
                take(
                    w.board,
                    p,
                    listOf(
                        PartgDonorProposalPickDto(
                            ref = bRow.ref!!,
                            action = PartgDonorProposalAction.LINK,
                            targetRowId = bRow.similarRowIds.single(),
                        ),
                    ),
                )
                call(member = w.board) { service(it).versionOverview(year) }.draftDeviation shouldBe PartgDraftDeviation.SAME
                book(w, 100_000L, member = a)
                val q = proposal(w.board)
                take(
                    w.board,
                    q,
                    listOf(PartgDonorProposalPickDto(ref = q.of("Anna").ref!!, action = PartgDonorProposalAction.REPLACE_AMOUNT)),
                )
                call(member = w.board) { service(it).versionOverview(year) }.draftDeviation shouldBe PartgDraftDeviation.DIFFERS
            }
        }

        test("hints: a new federal row is a combination candidate (P20); REPLACE on a combined row is P21, LINK is not") {
            val w = World()
            val dora = donorMember("Dora")
            val emil = donorMember("Emil")
            val stateA = PartgUnit.State(fx.chapter().toString()).key()
            val stateB = PartgUnit.State(fx.chapter().toString()).key()
            partgTestApplication(fixtures = fx) {
                setModeA(w)

                suspend fun stateRow(
                    unit: String,
                    name: String,
                ): String =
                    call(member = w.board) {
                        service(it).saveLargeDonor(year = year, unitKey = unit, expectedRevision = 0, donor = input(name = name))
                    }.donors.single { it.name == name }.id

                suspend fun consolidation() = call(member = w.board) { service(it).getConsolidation(year) }

                val doraState = stateRow(stateA, "Partg-Test Dora")
                val emilState = stateRow(stateB, "Partg-Test Emil")
                consolidation().findings.map { it.code } shouldNotContain PartgFindingCode.P20
                book(w, 1_200_000L, member = dora)
                val p = proposal(w.board)
                take(w.board, p, listOf(create(p.of("Dora"))))
                consolidation().findings.map { it.code } shouldContain PartgFindingCode.P20
                val doraFederal =
                    federalRows()
                        .single {
                            it[PartgReportLargeDonorTable.name] == "Partg-Test Dora"
                        }[PartgReportLargeDonorTable.id]
                        .toString()
                // combine Dora's two rows; then Emil: a hand row in the federal unit and his state row
                var view = consolidation()
                call(member = w.board) {
                    service(
                        it,
                    ).createDonorCombination(
                        year = year,
                        expectedRevision = view.revision,
                        expectedAnnexRevisions = view.annexRevisions,
                        largeDonorIds = listOf(doraState, doraFederal),
                    )
                }
                addHandRow(w.board, input(name = "Partg-Test Emil", cents = 1_100_000L))
                val emilHand =
                    federalRows()
                        .single { it[PartgReportLargeDonorTable.name] == "Partg-Test Emil" }[PartgReportLargeDonorTable.id]
                        .toString()
                view = consolidation()
                call(member = w.board) {
                    service(
                        it,
                    ).createDonorCombination(
                        year = year,
                        expectedRevision = view.revision,
                        expectedAnnexRevisions = view.annexRevisions,
                        largeDonorIds = listOf(emilState, emilHand),
                    )
                }
                consolidation().findings.map { it.code } shouldNotContain PartgFindingCode.P21
                Thread.sleep(30)
                // LINK of Emil's hand row changes nothing the combination depends on
                book(w, 1_200_000L, member = emil)
                val q = proposal(w.board)
                val emilRow = q.of("Emil")
                take(
                    w.board,
                    q,
                    listOf(
                        PartgDonorProposalPickDto(
                            ref = emilRow.ref!!,
                            action = PartgDonorProposalAction.LINK,
                            targetRowId = emilRow.similarRowIds.single(),
                        ),
                    ),
                )
                consolidation().findings.map { it.code } shouldNotContain PartgFindingCode.P21
                // REPLACE of Dora's federal row changes a row of a combination
                book(w, 100_000L, member = dora)
                val r = proposal(w.board)
                take(
                    w.board,
                    r,
                    listOf(PartgDonorProposalPickDto(ref = r.of("Dora").ref!!, action = PartgDonorProposalAction.REPLACE_AMOUNT)),
                )
                consolidation().findings.map { it.code } shouldContain PartgFindingCode.P21
            }
        }

        test("parity: the rows of the proposal are the donors of the donation duty report that must be disclosed") {
            val w = World()
            val m1 = donorMember("Anna")
            val m2 = donorMember("Bernd")
            val m3 = donorMember("Carla")
            val company = externalDonor("Firma Test", country = "DE")
            partgTestApplication(fixtures = fx) {
                setModeA(w)
                book(w, 600_000L, member = m1)
                book(w, 600_000L, member = m1)
                book(w, 1_000_000L, member = m2)
                book(w, 1_500_000L, external = company, category = DonorCategory.GERMAN_COMPANY_OR_ORGANIZATION)
                book(w, 400_000L, member = m3)
                val duty = call(member = w.admin) { fx.accounting(it).getDonationDutyReport(year) }
                val dutyKeys =
                    duty.donorDuties
                        .filter { it.annualDisclosureRequired }
                        .map { (if (it.donorType == DonorType.MEMBER) "M:" else "X:") + it.donorId }
                        .toSet()
                val proposed = proposal(w.board).rows.mapNotNull { it.ref }.toSet()
                proposed shouldBe dutyKeys
                proposed.size shouldBe 2
            }
        }

        test("two parallel take-overs with the same checksum: exactly one wins, the other is stale, no key is doubled") {
            val w = World()
            val a = donorMember("Anna")
            partgTestApplication(fixtures = fx) {
                setModeA(w)
                book(w, 1_200_000L, member = a)
                val p = proposal(w.board)
                val runs = AtomicInteger()
                val reached = CountDownLatch(1)
                val proceed = CountDownLatch(1)
                var first = true
                PartgAnnexWriter.afterLockHook = {
                    runs.incrementAndGet()
                    val mine = synchronized(this) { first.also { first = false } }
                    if (mine) {
                        reached.countDown()
                        proceed.await(30, TimeUnit.SECONDS)
                    }
                }
                val results =
                    coroutineScope {
                        val one =
                            async(Dispatchers.IO) {
                                attempt(
                                    member = w.board,
                                ) {
                                    service(
                                        it,
                                    ).applyLargeDonorProposal(
                                        year = year,
                                        expectedChecksum = p.checksum,
                                        picks = listOf(create(p.of("Anna"))),
                                    )
                                }
                            }
                        reached.await(30, TimeUnit.SECONDS)
                        val two =
                            async(Dispatchers.IO) {
                                attempt(
                                    member = w.board,
                                ) {
                                    service(
                                        it,
                                    ).applyLargeDonorProposal(
                                        year = year,
                                        expectedChecksum = p.checksum,
                                        picks = listOf(create(p.of("Anna"))),
                                    )
                                }
                            }
                        Thread.sleep(300)
                        proceed.countDown()
                        listOf(one.await(), two.await())
                    }
                results.count { it.isSuccess } shouldBe 1
                results.single { it.isFailure }.exceptionOrNull().shouldBeInstanceOf<PartyReportProposalStaleException>()
                runs.get() shouldBe 2 // each take-over ran its block once: no hidden retry
                federalRows().size shouldBe 1
                proposalAudits(w.board).size shouldBe 1
            }
        }
    })
