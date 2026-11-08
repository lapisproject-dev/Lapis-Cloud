package network.lapis.cloud.server.partg

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.dsgvo.PartgReportPersonalData
import network.lapis.cloud.server.dsgvo.PersonalDataRegistry
import network.lapis.cloud.server.time.ServerClock
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.PartgReportScope
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * V1.9.291 (R6) -- versions, declarations and deadlines in the data subject export and erasure: "changed by you" with year, scope, number and time,
 * NEVER the content, the comment, the checksum or a name; every row is retained on erasure with the reason; the table with the copy of statements B and
 * C is the documented gap for third parties.
 */
class PartgReportR6PersonalDataTest :
    FunSpec({
        val fx = PartgFixtures()
        beforeSpec { DatabaseConfig.connect() }
        afterSpec { fx.cleanUp() }
        beforeTest { fx.setParty(true) }
        afterTest { fx.cleanUp() }

        val year = 2001

        fun seed(board: Uuid): Uuid {
            val current = fx.currentOf(memberId = board)
            val id =
                PartgVersionWriter.freeze(
                    current = current,
                    year = year,
                    scope = PartgReportScope.All,
                    comment = "Auskunft Geheimkommentar",
                    now = ServerClock.now(),
                )
            val hash = transaction { PartgVersionStore.versionMeta(id)!!.contentHash }
            PartgVersionWriter.declare(current = current, versionId = id, expectedHash = hash, reviewed = true, now = ServerClock.now())
            PartgVersionWriter.finalize(current = current, versionId = id, typedNumber = 1, expectedHash = hash, now = ServerClock.now())
            PartgVersionWriter.confirmDeadline(
                current = current,
                year = year,
                deadline = kotlinx.datetime.LocalDate(year + 1, 12, 15),
                now = ServerClock.now(),
            )
            return id
        }

        test("the export lists year, scope, number and time but never the content, the comment or the checksum") {
            val board = fx.member()
            val id = seed(board)
            val hash = transaction { PartgVersionStore.versionMeta(id)!!.contentHash }
            val json = transaction { PartgReportPersonalData.exportMember(board).toString() }
            json shouldContain "versionsChangedByYou"
            json shouldContain "versionDeclarationsByYou"
            json shouldContain "versionCountersChangedByYou"
            json shouldContain "deadlinesConfirmedByYou"
            json shouldContain "\"KEPT\""
            json shouldContain "\"FINALISED\""
            json shouldContain "2001"
            listOf("Geheim", "Auskunft", hash, "Test Partei", "organization").forEach { json shouldNotContain it }
        }

        test("erasure retains every row with the reason and deletes nothing") {
            val board = fx.member()
            seed(board)
            for (mode in ErasureMode.entries) {
                val outcomes = transaction { PartgReportPersonalData.eraseMember(memberId = board, mode = mode) }.associateBy { it.table }
                outcomes.getValue("partg_report_version").rowsRetained shouldBe 1
                outcomes.getValue("partg_report_version_counter").rowsRetained shouldBe 1
                outcomes.getValue("partg_report_declaration").rowsRetained shouldBe 1
                outcomes.getValue("partg_report_deadline").rowsRetained shouldBe 1
                outcomes.getValue("partg_report_version").retentionReason?.contains("§ 24 Abs. 2") shouldBe true
            }
            transaction { PartgVersionStore.latest(year = year, scopeKey = "ALL") }?.versionNumber shouldBe 1
        }

        test(
            "the registry: all four tables are covered; the copy of statements B and C is the documented gap, the others hold organisation data",
        ) {
            val names = PartgReportPersonalData.coveredTables.map { it.tableName }.toSet()
            listOf("partg_report_version_counter", "partg_report_version", "partg_report_declaration", "partg_report_deadline")
                .forEach { (it in names) shouldBe true }
            PersonalDataRegistry.nonMemberPiiTables.contains("partg_report_version") shouldBe true
            PersonalDataRegistry.knownUncoveredSubjectRoots.containsKey("partg_report_version") shouldBe true
            PersonalDataRegistry.knownUncoveredSubjectRoots.getValue("partg_report_version") shouldContain "Ausweise B und C"
            listOf("partg_report_declaration", "partg_report_deadline", "partg_report_version_counter").forEach {
                PersonalDataRegistry.nonMemberPiiTables.contains(it) shouldBe false
            }
        }
    })
