package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DevSeedData
import network.lapis.cloud.server.db.generated.PartgReportAnnexTable
import network.lapis.cloud.server.db.generated.PartgReportHoldingTable
import network.lapis.cloud.server.db.generated.PartgReportLargeDonorTable
import network.lapis.cloud.server.db.generated.PartgReportNoteTable
import network.lapis.cloud.server.db.generated.PartgReportNotesSheetTable
import network.lapis.cloud.server.db.generated.RegionalChapterTable
import network.lapis.cloud.server.testdb.TestDatabase
import network.lapis.cloud.server.testdb.installLaneGuards
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgNoteBlock
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgTriState
import network.lapis.cloud.shared.domain.PartgUnit
import network.lapis.cloud.shared.domain.PartgUnitLevel
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.269 -- the CHECKs, UNIQUE and FOREIGN KEY rules of `V139__partg_report_r3.sql`, on H2 AND PostgreSQL with the same assertions. Every refused
 * statement runs in its OWN transaction (on PostgreSQL a failed statement aborts the whole transaction).
 */
abstract class PartgReportR3MigrationScenarios(
    private val db: TestDatabase,
) : FunSpec({
        val fx = PartgFixtures()
        val now = LocalDateTime(2026, 1, 1, 0, 0)

        beforeSpec {
            db.activate()
            if (!db.isPostgres) DevSeedData.seedIfEmpty(force = true)
        }
        installLaneGuards(db = db, checkDeadlocks = false)
        afterSpec {
            fx.cleanUp()
            db.deactivate()
        }

        fun refused(block: () -> Unit): Boolean =
            try {
                transaction { block() }
                false
            } catch (_: Exception) {
                true
            }

        fun insertAnnex(
            year: Int,
            member: Uuid,
            level: PartgUnitLevel = PartgUnitLevel.FEDERAL,
            chapter: Uuid? = null,
            key: String = "FEDERAL",
            deduction: Long? = null,
            members: Int? = null,
            revision: Long = 0L,
        ): Uuid =
            transaction {
                val id = Uuid.random()
                PartgReportAnnexTable.insert {
                    it[PartgReportAnnexTable.id] = id
                    it[fiscalYear] = year
                    it[unitLevel] = level
                    it[regionalChapterId] = chapter
                    it[unitKey] = key
                    it[PartgReportAnnexTable.revision] = revision
                    it[deductionOver3300Cents] = deduction
                    it[memberCount] = members
                    it[updatedAt] = now
                    it[updatedBy] = member
                }
                id
            }

        fun insertDonor(
            annex: Uuid,
            member: Uuid,
            cents: Long,
            name: String = "Erika Beispiel",
            kind: PartgPersonKind = PartgPersonKind.NATURAL,
            country: String = "DE",
        ) = transaction {
            PartgReportLargeDonorTable.insert {
                it[id] = Uuid.random()
                it[annexId] = annex
                it[personKind] = kind
                it[PartgReportLargeDonorTable.name] = name
                it[PartgReportLargeDonorTable.country] = country
                it[contributionKind] = PartgContributionKind.DONATION
                it[amountCents] = cents
                it[createdAt] = now
                it[createdBy] = member
                it[updatedAt] = now
                it[updatedBy] = member
            }
        }

        fun insertNotes(
            year: Int,
            member: Uuid,
        ): Uuid =
            transaction {
                val id = Uuid.random()
                PartgReportNotesSheetTable.insert {
                    it[PartgReportNotesSheetTable.id] = id
                    it[fiscalYear] = year
                    it[revision] = 0L
                    it[updatedAt] = now
                    it[updatedBy] = member
                }
                id
            }

        fun insertNote(
            sheet: Uuid,
            member: Uuid,
            block: PartgNoteBlock,
            abs1: PartgTriState? = null,
            abs3: PartgTriState? = null,
        ) = transaction {
            PartgReportNoteTable.insert {
                it[id] = Uuid.random()
                it[notesSheetId] = sheet
                it[blockCode] = block
                it[included] = true
                it[body] = "x"
                it[reliefAbs1] = abs1
                it[reliefAbs3] = abs3
                it[updatedAt] = now
                it[updatedBy] = member
            }
        }

        fun insertHolding(
            sheet: Uuid,
            member: Uuid,
            order: Int,
            share: Int? = null,
            result: Long? = null,
            capital: Long? = null,
        ) = transaction {
            PartgReportHoldingTable.insert {
                it[id] = Uuid.random()
                it[notesSheetId] = sheet
                it[sortOrder] = order
                it[name] = "Beteiligung GmbH"
                it[shareBasisPoints] = share
                it[resultCents] = result
                it[capitalCents] = capital
                it[updatedAt] = now
                it[updatedBy] = member
            }
        }

        test("a federal, a state and a subordinate annex are accepted; not entered (NULL) and zero are both valid") {
            val member = fx.member()
            val chapter = fx.chapter()
            insertAnnex(2020, member)
            insertAnnex(2020, member, PartgUnitLevel.STATE, chapter, PartgUnit.State(chapter.toString()).key(), deduction = 0L, members = 0)
            insertAnnex(2020, member, PartgUnitLevel.SUBORDINATE, chapter, PartgUnit.Subordinate(chapter.toString()).key())
        }

        test("the annex unit key must agree with level and chapter; year, revision, figures and the foreign keys are bounded") {
            val member = fx.member()
            val chapter = fx.chapter()
            refused { insertAnnex(2021, member, PartgUnitLevel.FEDERAL, chapter, "FEDERAL") } shouldBe true
            refused { insertAnnex(2021, member, PartgUnitLevel.STATE, chapter, "STATE:${Uuid.random()}") } shouldBe true
            refused { insertAnnex(2021, member, PartgUnitLevel.STATE, null, "STATE:$chapter") } shouldBe true
            refused { insertAnnex(1999, member) } shouldBe true
            refused { insertAnnex(2101, member) } shouldBe true
            refused { insertAnnex(2021, member, revision = -1) } shouldBe true
            refused { insertAnnex(2021, member, deduction = -1) } shouldBe true
            refused { insertAnnex(2021, member, deduction = 100_000_000_000_000L) } shouldBe true
            refused { insertAnnex(2021, member, members = -1) } shouldBe true
            refused { insertAnnex(2021, member, members = 10_000_001) } shouldBe true
            refused { insertAnnex(2021, Uuid.random()) } shouldBe true
            insertAnnex(2021, member, members = 10_000_000, deduction = 99_999_999_999_999L)
            refused { insertAnnex(2021, member) } shouldBe true // unique per year and unit
        }

        test("a large donor needs MORE than 10,000.00 EUR: 1,000,000 cents is refused, 1,000,001 accepted") {
            val member = fx.member()
            val annex = insertAnnex(2022, member)
            refused { insertDonor(annex, member, 1_000_000L) } shouldBe true
            refused { insertDonor(annex, member, 0L) } shouldBe true
            refused { insertDonor(annex, member, -5L) } shouldBe true
            refused { insertDonor(annex, member, 100_000_000_000_000L) } shouldBe true
            insertDonor(annex, member, 1_000_001L)
            insertDonor(annex, member, 99_999_999_999_999L, kind = PartgPersonKind.LEGAL)
        }

        test("large donor: name length 1..200, country exactly two characters, known annex and member") {
            val member = fx.member()
            val annex = insertAnnex(2023, member)
            refused { insertDonor(annex, member, 2_000_000L, name = "") } shouldBe true
            refused { insertDonor(annex, member, 2_000_000L, name = "x".repeat(201)) } shouldBe true
            insertDonor(annex, member, 2_000_000L, name = "x".repeat(200))
            refused { insertDonor(annex, member, 2_000_000L, country = "D") } shouldBe true
            refused { insertDonor(annex, member, 2_000_000L, country = "DEU") } shouldBe true
            refused { insertDonor(Uuid.random(), member, 2_000_000L) } shouldBe true
            refused { insertDonor(annex, Uuid.random(), 2_000_000L) } shouldBe true
        }

        test("the notes sheet is unique per year; a block is unique per sheet; relief fields only in RELIEF_28") {
            val member = fx.member()
            val sheet = insertNotes(2024, member)
            refused { insertNotes(2024, member) } shouldBe true
            refused { insertNotes(1999, member) } shouldBe true
            insertNote(sheet, member, PartgNoteBlock.GENERAL_ACCOUNTING)
            refused { insertNote(sheet, member, PartgNoteBlock.GENERAL_ACCOUNTING) } shouldBe true
            refused { insertNote(sheet, member, PartgNoteBlock.HOLDINGS, abs1 = PartgTriState.YES) } shouldBe true
            refused { insertNote(sheet, member, PartgNoteBlock.MEDIA_PRODUCTS, abs3 = PartgTriState.NO) } shouldBe true
            insertNote(sheet, member, PartgNoteBlock.RELIEF_28, abs1 = PartgTriState.YES, abs3 = PartgTriState.UNSPECIFIED)
            refused { insertNote(Uuid.random(), member, PartgNoteBlock.REAL_ESTATE_VALUATION) } shouldBe true
        }

        test("holdings: share 0..10000 basis points, a negative result is allowed, order unique per sheet") {
            val member = fx.member()
            val sheet = insertNotes(2025, member)
            insertHolding(sheet, member, 0, share = 10_000, result = -500_00L, capital = 0L)
            refused { insertHolding(sheet, member, 1, share = 10_001) } shouldBe true
            refused { insertHolding(sheet, member, 1, share = -1) } shouldBe true
            refused { insertHolding(sheet, member, 1, capital = -1L) } shouldBe true
            refused { insertHolding(sheet, member, 1, result = -100_000_000_000_000L) } shouldBe true
            refused { insertHolding(sheet, member, 0) } shouldBe true
            insertHolding(sheet, member, 1)
        }

        test("an annex cannot be deleted while a donor row points to it, and a chapter cannot while an annex does") {
            val member = fx.member()
            val chapter = fx.chapter()
            val annex = insertAnnex(2019, member, PartgUnitLevel.STATE, chapter, PartgUnit.State(chapter.toString()).key())
            insertDonor(annex, member, 2_000_000L)
            refused { PartgReportAnnexTable.deleteWhere { PartgReportAnnexTable.id eq annex } } shouldBe true
            refused { RegionalChapterTable.deleteWhere { RegionalChapterTable.id eq chapter } } shouldBe true
        }

        test("the audit CHECK names the two new entity types and still all older ones") {
            val clause =
                transaction {
                    var text = ""
                    exec(
                        "SELECT check_clause FROM information_schema.check_constraints WHERE constraint_name = 'chk_audit_log_entry_entity_type'",
                    ) { rs -> while (rs.next()) text += rs.getString(1) }
                    text
                }
            listOf(
                "PARTG_REPORT_ANNEX",
                "PARTG_REPORT_NOTES",
                "PARTG_REPORT_EXPORT",
                "PARTG_REPORT_SHEET",
                "ACTIVISM_SETTINGS",
                "ATTACHED_IMAGE",
            ).forEach { clause.contains(it) shouldBe true }
        }
    })
