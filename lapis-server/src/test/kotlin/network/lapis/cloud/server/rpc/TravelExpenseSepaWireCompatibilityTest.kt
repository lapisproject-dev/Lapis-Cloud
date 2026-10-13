package network.lapis.cloud.server.rpc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseSnapshot

/** Welle V1.9.123 -- every new DTO field has a default: a payload from before the wave still deserializes. */
class TravelExpenseSepaWireCompatibilityTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = false }

        test("a TravelExpenseReportDto payload without any V1.9.123 field decodes with the defaults") {
            val old =
                """
                {"id":"1","subjectMemberId":"2","subjectDisplayName":"A","status":"EXECUTED","purpose":"P",
                 "travelFrom":"2026-09-01","travelTo":"2026-09-02","totalAmount":"10.00","createdAt":"2026-09-03T10:00:00",
                 "requestedBy":"2","requestedByDisplayName":"A","advanceCents":0,"payoutCents":1000}
                """.trimIndent()
            val dto = json.decodeFromString(TravelExpenseReportDto.serializer(), old)
            dto.refundIbanLast4 shouldBe null
            dto.refundAccountHolder shouldBe null
            dto.refundAccountChangedAfterSubmit shouldBe false
            dto.sepaExportedAt shouldBe null
            dto.sepaExportBatchId shouldBe null
            dto.sepaReexportCount shouldBe 0
            dto.sepaBlocker shouldBe null
            dto.sepaReference shouldBe null
        }

        test("an old audit snapshot decodes with the new counters at their defaults") {
            val old =
                """
                {"reportId":"1","subjectMemberId":"2","status":"EXECUTED","travelFrom":"2026-09-01","travelTo":"2026-09-02",
                 "lineCount":1,"lineKinds":["OTHER"],"totalAmount":"10.00"}
                """.trimIndent()
            val snapshot = json.decodeFromString(TravelExpenseSnapshot.serializer(), old)
            snapshot.sepaExportBatchId shouldBe null
            snapshot.sepaReexportCount shouldBe 0
            snapshot.refundAccountChanged shouldBe false
        }
    })
