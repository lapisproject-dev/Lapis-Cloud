package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.PartgReportAnnexTable
import network.lapis.cloud.server.db.generated.PartgReportLargeDonorTable
import network.lapis.cloud.server.dsgvo.PartgReportPersonalData
import network.lapis.cloud.server.dsgvo.PersonalDataRegistry
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgUnitLevel
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.293 (R5b) -- the access request of a member lists the rows of statement B that are linked to THEM (year, unit, kind, amount: their own data),
 * never a row of another member and never a row linked to an external donor or entered by hand; on erasure the row stays.
 */
class PartgReportR5bPersonalDataTest :
    FunSpec({
        val fx = PartgFixtures()
        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }
        beforeTest { fx.setParty(true) }
        afterTest { fx.cleanUp() }

        val at = LocalDateTime(2026, 1, 1, 0, 0)

        fun annexOf(
            by: Uuid,
            year: Int,
        ): Uuid =
            transaction {
                val id = Uuid.random()
                PartgReportAnnexTable.insert {
                    it[PartgReportAnnexTable.id] = id
                    it[fiscalYear] = year
                    it[unitLevel] = PartgUnitLevel.FEDERAL
                    it[unitKey] = "FEDERAL"
                    it[revision] = 0L
                    it[updatedAt] = at
                    it[updatedBy] = by
                }
                id
            }

        fun row(
            annex: Uuid,
            by: Uuid,
            name: String,
            key: String?,
            cents: Long,
            kind: PartgContributionKind = PartgContributionKind.DONATION,
        ) = transaction {
            PartgReportLargeDonorTable.insert {
                it[id] = Uuid.random()
                it[annexId] = annex
                it[personKind] = PartgPersonKind.NATURAL
                it[PartgReportLargeDonorTable.name] = name
                it[country] = "DE"
                it[contributionKind] = kind
                it[amountCents] = cents
                it[createdAt] = at
                it[createdBy] = by
                it[updatedAt] = at
                it[updatedBy] = by
                it[sourceDonorKey] = key
                it[sourceAppliedAt] = if (key != null) at else null
            }
        }

        test("the export of a member lists the rows linked to them with year, unit, kind and amount, and no other row") {
            val board = fx.member()
            val donor = fx.member(label = "donor")
            val other = fx.member(label = "other")
            val annex = annexOf(board, 2071)
            row(annex, board, "Fremdname Eins", key = "M:$donor", cents = 1_234_500L, kind = PartgContributionKind.BOTH)
            row(annex, board, "Fremdname Zwei", key = "M:$other", cents = 7_777_700L)
            row(annex, board, "Fremdname Drei", key = "X:${Uuid.random()}", cents = 8_888_800L)
            row(annex, board, "Fremdname Vier", key = null, cents = 9_999_900L)
            val json = transaction { PartgReportPersonalData.exportMember(donor).toString() }
            json shouldContain "listedInStatementB"
            json shouldContain "1234500"
            json shouldContain "BOTH"
            json shouldContain "2071"
            listOf("7777700", "8888800", "9999900", "Fremdname", other.toString()).forEach { json shouldNotContain it }
            // the other member sees only their own row; a member without a row gets an empty list
            val otherJson = transaction { PartgReportPersonalData.exportMember(other).toString() }
            otherJson shouldContain "7777700"
            otherJson shouldNotContain "1234500"
            val none = transaction { PartgReportPersonalData.exportMember(board).toString() }
            none shouldNotContain "1234500"
        }

        test("erasure of the donor member keeps the row and its key; the registry still lists the third-party gap") {
            val board = fx.member()
            val donor = fx.member(label = "donor")
            val annex = annexOf(board, 2072)
            row(annex, board, "Fremdname Eins", key = "M:$donor", cents = 1_234_500L)
            for (mode in ErasureMode.entries) {
                transaction { PartgReportPersonalData.eraseMember(memberId = donor, mode = mode) }
            }
            transaction {
                PartgReportLargeDonorTable
                    .selectAll()
                    .where {
                        PartgReportLargeDonorTable.annexId eq annex
                    }.single()[PartgReportLargeDonorTable.sourceDonorKey]
            } shouldBe "M:$donor"
            PersonalDataRegistry.knownUncoveredSubjectRoots.getValue("partg_report_large_donor") shouldContain "listedInStatementB"
            PersonalDataRegistry.nonMemberPiiTables.contains("partg_report_large_donor") shouldBe true
        }
    })
