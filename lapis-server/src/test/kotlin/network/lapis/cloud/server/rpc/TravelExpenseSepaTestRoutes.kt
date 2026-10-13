package network.lapis.cloud.server.rpc

import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import network.lapis.cloud.shared.domain.TravelExpenseReportDto

/** Throwaway routes of the V1.9.123 service methods; `nokey=1` builds the service WITHOUT an encryption key. */
internal fun Route.registerTravelExpenseSepaTestRoutes() {
    fun io.ktor.server.routing.RoutingContext.service(): TravelExpenseService {
        val key = if (call.request.queryParameters["nokey"] == "1") null else SEPA_TEST_KEY
        return TravelExpenseService(call = call, receiptStorageRoot = TEST_RECEIPT_STORAGE_ROOT, secretEncryptionKey = key)
    }

    fun TravelExpenseReportDto.sepaPipe(): String =
        listOf(
            id,
            status.name,
            refundIbanLast4 ?: "-",
            refundAccountHolder ?: "-",
            refundAccountChangedAfterSubmit.toString(),
            sepaReference ?: "-",
            sepaBlocker?.name ?: "-",
            sepaExportedAt?.toString() ?: "-",
        ).joinToString("|")

    post("/test/sepa/refund") {
        val q = call.request.queryParameters
        val dto = service().setRefundAccount(reportId = q["id"]!!, iban = q["iban"], accountHolder = q["holder"])
        call.respondText(dto.sepaPipe())
    }
    get("/test/sepa/prefill") {
        val dto = service().getRefundAccountPrefill(call.request.queryParameters["id"]!!)
        call.respondText("${dto.iban ?: "-"}|${dto.accountHolder ?: "-"}")
    }
    get("/test/sepa/candidates") {
        call.respondText(service().listSepaExportCandidates().joinToString(";") { it.sepaPipe() })
    }
    get("/test/sepa/batches") {
        val batches = service().listSepaBatches(limit = call.request.queryParameters["limit"]?.toInt() ?: 50)
        call.respondText(
            batches.joinToString(";") {
                "${it.id}|${it.transactionCount}|${it.controlSumCents}|${it.debtorIbanLast4}|${it.isReexport}|${it.reportIds.size}"
            },
        )
    }
    get("/test/sepa/context") {
        val ctx = service().getSepaExportContext()
        call.respondText(
            "${ctx.bankAccounts.size}|${ctx.defaultExecutionDate}|${ctx.missingSetup.joinToString(",")}|${ctx.maxTransactions}",
        )
    }
}
