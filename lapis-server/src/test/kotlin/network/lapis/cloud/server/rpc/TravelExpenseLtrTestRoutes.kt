package network.lapis.cloud.server.rpc

import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** Throwaway routes of the V1.9.325 LTR refund service methods; used by the role-gate test only. */
internal fun Route.registerTravelExpenseLtrTestRoutes() {
    fun io.ktor.server.routing.RoutingContext.service() = TravelExpenseService(call = call, receiptStorageRoot = TEST_RECEIPT_STORAGE_ROOT)

    post("/test/ltr/candidates") { call.respondText(service().getLtrPayoutCandidates().size.toString()) }
    post("/test/ltr/preview") { call.respondText(service().previewLtrPayout(call.request.queryParameters["id"]!!).blocker?.name ?: "-") }
    post("/test/ltr/confirm") {
        val q = call.request.queryParameters
        service().confirmLtrPayoutRate(
            reportId = q["id"]!!,
            price = q["price"]!!.toBigDecimal(),
            evidenceText = "e",
            acknowledgeDeviation = false,
        )
        call.respondText("ok")
    }
    post("/test/ltr/execute") {
        val q = call.request.queryParameters
        service().executeLtrPayout(reportId = q["id"]!!, fingerprint = q["fp"] ?: "x", repeatReason = null)
        call.respondText("ok")
    }
    post("/test/ltr/reverse") {
        val q = call.request.queryParameters
        service().reverseLtrPayout(reportId = q["id"]!!, payoutId = q["payout"]!!, reason = "a reason that is long enough")
        call.respondText("ok")
    }
    post("/test/ltr/enable") {
        call.respondText(service().setLtrPayoutEnabled(call.request.queryParameters["enabled"] == "true").toString())
    }
}
