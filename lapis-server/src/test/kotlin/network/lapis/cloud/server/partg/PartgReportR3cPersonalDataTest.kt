package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.dsgvo.PartgReportPersonalData
import network.lapis.cloud.server.dsgvo.PersonalDataRegistry
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.PartgEnterpriseInputDto
import network.lapis.cloud.shared.domain.PartgUnit
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** V1.9.287 (R3c) -- enterprises of statement D in the data subject export and erasure. */
class PartgReportR3cPersonalDataTest :
    FunSpec({
        val fx = PartgFixtures()
        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }
        beforeTest { fx.setParty(true) }

        val year: Int by lazy { PartgYearRules.maxYear() - 1 }

        suspend fun PartgApp.seed(
            board: kotlin.uuid.Uuid,
            unit: String,
        ) {
            call(member = board) {
                service(it).saveEnterprise(
                    year = year,
                    unitKey = unit,
                    expectedRevision = 0,
                    enterprise =
                        PartgEnterpriseInputDto(
                            id = null,
                            name = "Auskunft Geheimbetrieb",
                            legalForm = "Geheim-GmbH",
                            seat = "Geheimstadt",
                            shareBasisPoints = 4_321,
                            incomeCents = 8_765_432L,
                            expensesCents = 4_444_444L,
                        ),
                )
            }
        }

        test("the export lists year and unit of the changes but never a name, a seat or an amount") {
            val board = fx.member()
            val unit = PartgUnit.State(fx.chapter().toString()).key()
            partgTestApplication(fixtures = fx) { seed(board, unit) }
            val json = transaction { PartgReportPersonalData.exportMember(board).toString() }
            json shouldContain "enterprisesChangedByYou"
            json shouldContain "STATE:"
            listOf("Geheim", "8765432", "4444444", "4321", "Auskunft").forEach { json shouldNotContain it }
        }

        test("erasure retains the enterprise rows, names the reason, and deletes nothing") {
            val board = fx.member()
            val unit = PartgUnit.State(fx.chapter().toString()).key()
            partgTestApplication(fixtures = fx) { seed(board, unit) }
            for (mode in ErasureMode.entries) {
                val outcomes = transaction { PartgReportPersonalData.eraseMember(memberId = board, mode = mode) }.associateBy { it.table }
                outcomes.getValue("partg_report_enterprise").rowsRetained shouldBe 1
                outcomes.getValue("partg_report_enterprise").retentionReason?.contains("§ 24 Abs. 2") shouldBe true
            }
            partgTestApplication(fixtures = fx) {
                call(member = board) { service(it).getAnnex(year = year, unitKey = unit) }.enterprises.size shouldBe 1
            }
        }

        test("the table is covered by the contributor and is not registered as a third-party root (open question Q16 is documented)") {
            PartgReportPersonalData.coveredTables.any { it.tableName == "partg_report_enterprise" } shouldBe true
            PersonalDataRegistry.nonMemberPiiTables.contains("partg_report_enterprise") shouldBe false
            PersonalDataRegistry.knownUncoveredSubjectRoots.containsKey("partg_report_enterprise") shouldBe false
        }
    })
