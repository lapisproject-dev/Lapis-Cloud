package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.dsgvo.PartgReportPersonalData
import network.lapis.cloud.server.dsgvo.PersonalDataRegistry
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.PartgContributionKind
import network.lapis.cloud.shared.domain.PartgIntraTransferInputDto
import network.lapis.cloud.shared.domain.PartgLargeDonorInputDto
import network.lapis.cloud.shared.domain.PartgPersonKind
import network.lapis.cloud.shared.domain.PartgTransferKind
import network.lapis.cloud.shared.domain.PartgUnit
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/** V1.9.290 (R5) -- pairs and donor combinations in the data subject export and erasure: "changed by you" without name, note, amount or donor id. */
class PartgReportR5PersonalDataTest :
    FunSpec({
        val fx = PartgFixtures()
        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }
        beforeTest { fx.setParty(true) }
        afterTest { fx.cleanUp() }

        val year = 2006

        fun donor(name: String) =
            PartgLargeDonorInputDto(
                id = null,
                personKind = PartgPersonKind.NATURAL,
                name = name,
                street = "Geheimweg 7",
                postalCode = "38100",
                city = "Geheimstadt",
                country = "DE",
                contributionKind = PartgContributionKind.DONATION,
                amountCents = 7_654_321L,
            )

        suspend fun PartgApp.seed(board: Uuid) {
            val a = PartgUnit.State(fx.chapter().toString()).key()
            val b = PartgUnit.State(fx.chapter().toString()).key()
            call(member = board) {
                service(it).saveIntraTransfer(
                    year = year,
                    expectedRevision = 0,
                    transfer =
                        PartgIntraTransferInputDto(
                            id = null,
                            kind = PartgTransferKind.SUBSIDY,
                            payerUnitKey = a,
                            payeeUnitKey = b,
                            payerAmountCents = 8_765_432L,
                            payeeAmountCents = null,
                            note = "Auskunft Geheimnotiz",
                        ),
                )
            }
            val ids =
                listOf(a to "Auskunft Alpha", b to "Auskunft Beta").map { (unit, name) ->
                    val revision = call(member = board) { service(it).getAnnex(year = year, unitKey = unit) }.revision
                    call(member = board) {
                        service(it).saveLargeDonor(year = year, unitKey = unit, expectedRevision = revision, donor = donor(name))
                    }.donors.single().id
                }
            val view = call(member = board) { service(it).getConsolidation(year) }
            call(member = board) {
                service(
                    it,
                ).createDonorCombination(
                    year = year,
                    expectedRevision = view.revision,
                    expectedAnnexRevisions = view.annexRevisions,
                    largeDonorIds = ids,
                )
            }
        }

        test("the export lists the year of the changes but never a name, a note, an amount or a donor id") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) { seed(board) }
            val json = transaction { PartgReportPersonalData.exportMember(board).toString() }
            json shouldContain "consolidationChangedByYou"
            json shouldContain "intraTransfersChangedByYou"
            json shouldContain "donorCombinationsChangedByYou"
            json shouldContain "2006"
            listOf("Geheim", "Auskunft", "8765432", "7654321").forEach { json shouldNotContain it }
        }

        test("erasure retains the rows, names the reason, and deletes nothing") {
            val board = fx.member()
            partgTestApplication(fixtures = fx) { seed(board) }
            for (mode in ErasureMode.entries) {
                val outcomes = transaction { PartgReportPersonalData.eraseMember(memberId = board, mode = mode) }.associateBy { it.table }
                outcomes.getValue("partg_consolidation_sheet").rowsRetained shouldBe 1
                outcomes.getValue("partg_intra_transfer").rowsRetained shouldBe 1
                outcomes.getValue("partg_donor_combination").rowsRetained shouldBe 1
                outcomes.getValue("partg_intra_transfer").retentionReason?.contains("§ 24 Abs. 2") shouldBe true
            }
            partgTestApplication(fixtures = fx) {
                val view = call(member = board) { service(it).getConsolidation(year) }
                view.transfers.size shouldBe 1
                view.donors.count { it.combinationId != null } shouldBe 1
            }
        }

        test("the four tables are covered by the contributor; the combination row holds no personal data of its own") {
            val names = PartgReportPersonalData.coveredTables.map { it.tableName }.toSet()
            listOf("partg_consolidation_sheet", "partg_intra_transfer", "partg_donor_combination", "partg_donor_combination_row").forEach {
                (it in names) shouldBe true
            }
            // the donor rows themselves stay the documented gap (third-party data without a member key)
            PersonalDataRegistry.nonMemberPiiTables.contains("partg_report_large_donor") shouldBe true
            PersonalDataRegistry.nonMemberPiiTables.contains("partg_donor_combination_row") shouldBe false
        }
    })
