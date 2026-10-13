package network.lapis.cloud.server.dsgvo

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.datetime.LocalDate
import network.lapis.cloud.server.db.DatabaseConfig
import network.lapis.cloud.server.db.generated.TravelExpenseReportTable
import network.lapis.cloud.server.db.generated.TravelExpenseSepaBatchTable
import network.lapis.cloud.server.rpc.MEMBER_IBAN_1
import network.lapis.cloud.server.rpc.PollTestData
import network.lapis.cloud.server.rpc.SEPA_TEST_KEY
import network.lapis.cloud.server.rpc.TravelExpenseSepaExport
import network.lapis.cloud.server.rpc.TravelExpenseSepaTestData
import network.lapis.cloud.server.security.CurrentMember
import network.lapis.cloud.server.time.TimeTestSupport
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ErasureMode
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/** Welle V1.9.123 -- erasure and Art. 15 export of the refund account and the export mark. */
class TravelExpenseSepaPersonalDataTest :
    FunSpec({
        val data = PollTestData()
        val sepa = TravelExpenseSepaTestData(data)

        beforeSpec {
            DatabaseConfig.connect()
            sepa.configureOrganization()
        }
        afterSpec {
            sepa.cleanUp()
            data.cleanUp()
        }

        test("erasing the subject removes the IBAN data of a booked and exported report but keeps the export metadata") {
            val subject = data.member(label = "dsgvo-s", role = AccountRole.MEMBER)
            val admin = data.member(label = "dsgvo-a", role = AccountRole.ADMIN)
            val bank = sepa.bankAccount(createdBy = admin)
            val report = sepa.executedReport(subject = subject)
            val result =
                TimeTestSupport.withServerClock(instant = "2026-10-13T10:00:00Z") {
                    TravelExpenseSepaExport(SEPA_TEST_KEY).export(
                        actor = CurrentMember(memberId = admin, role = AccountRole.ADMIN, status = MemberStatus.ACTIVE),
                        reportIds = listOf(report),
                        debtorBankAccountId = bank,
                        executionDate = LocalDate(2026, 10, 14),
                    )
                }

            val outcomes = transaction { TravelExpensePersonalData.eraseMember(memberId = subject, mode = ErasureMode.ANONYMIZE) }

            val row = sepa.report(report)
            row[TravelExpenseReportTable.refundIbanCiphertext] shouldBe null
            row[TravelExpenseReportTable.refundIbanLast4] shouldBe null
            row[TravelExpenseReportTable.refundAccountHolder] shouldBe null
            row[TravelExpenseReportTable.status] shouldBe TravelExpenseReportStatus.EXECUTED
            row[TravelExpenseReportTable.sepaExportBatchId] shouldBe result.batchId
            (row[TravelExpenseReportTable.sepaExportedAt] != null) shouldBe true
            transaction {
                TravelExpenseSepaBatchTable.selectAll().where { TravelExpenseSepaBatchTable.id eq result.batchId }.count()
            } shouldBe
                1L
            outcomes.single { it.table == "travel_expense_sepa_batch_item" }.rowsRetained shouldBe 1
        }

        test("the Art. 15 export shows the masked account, the holder and the export mark -- never the ciphertext or the full IBAN") {
            val subject = data.member(label = "dsgvo-s2", role = AccountRole.MEMBER)
            val report = sepa.executedReport(subject = subject, holder = "Erika Mustermann")
            val text = transaction { TravelExpensePersonalData.exportMember(subject).toString() }
            text shouldContain "\"refundIbanLast4\":\"2051\""
            text shouldContain "\"refundAccountHolder\":\"Erika Mustermann\""
            text shouldContain "\"sepaReexportCount\":0"
            text shouldNotContain MEMBER_IBAN_1
            text shouldNotContain "v1:"
            text shouldNotContain (sepa.report(report)[TravelExpenseReportTable.refundIbanCiphertext] ?: "")
        }
    })
