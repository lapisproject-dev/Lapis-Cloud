package network.lapis.cloud.server.rpc

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.TravelExpenseLineInput
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportInput
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import java.io.File

/**
 * Shared throwaway test-route registrar + pipe-string encoding for [TravelExpenseService]/
 * [ITravelExpenseService] -- mirrors [ContributionReliefTestRoutes]'s house style ("throwaway
 * routes calling the service class directly, no wire format to reverse-engineer") so every
 * `TravelExpense*Test` file in this package can share one set of routes.
 */
internal fun TravelExpenseReportDto.toPipeString(): String =
    listOf(
        id,
        subjectMemberId,
        status.name,
        purpose,
        travelFrom.toString(),
        travelTo.toString(),
        totalAmount.toString(),
        lines.size.toString(),
        requestedBy,
        submittedAt?.toString() ?: "-",
        decidedBy ?: "-",
        decisionNote ?: "-",
        executedAt?.toString() ?: "-",
        postedJournalEntryId ?: "-",
        executionError ?: "-",
    ).joinToString("|")

/**
 * Uses a throwaway `build/test-travel-expense-storage` directory -- never the real deploy path.
 * `internal` (not `private`) so [network.lapis.cloud.server.routes.TravelExpenseReceiptRoutesTest]
 * can register [network.lapis.cloud.server.routes.registerTravelExpenseReceiptRoutes] against the
 * SAME root [TravelExpenseService] itself reads/writes through these test routes.
 */
internal val TEST_RECEIPT_STORAGE_ROOT = File("build/test-travel-expense-storage")

internal fun Route.registerTravelExpenseTestRoutes() {
    post("/test/travelexpense/rates") {
        val service = TravelExpenseService(call = call, receiptStorageRoot = TEST_RECEIPT_STORAGE_ROOT)
        val q = call.request.queryParameters
        val dto =
            service.updateTravelExpenseRates(
                mileageRatePerKm = q["mileage"]?.toBigDecimal(),
                perDiemRate = q["perDiem"]?.toBigDecimal(),
            )
        call.respondText(
            "${dto.mileageRatePerKm ?: "-"}|${dto.perDiemRate ?: "-"}|${dto.expenseAccountConfigured}|${dto.bankAccountConfigured}",
        )
    }
    get("/test/travelexpense/rates") {
        val service = TravelExpenseService(call = call, receiptStorageRoot = TEST_RECEIPT_STORAGE_ROOT)
        val dto = service.getTravelExpenseRates()
        call.respondText(
            "${dto.mileageRatePerKm ?: "-"}|${dto.perDiemRate ?: "-"}|${dto.expenseAccountConfigured}|${dto.bankAccountConfigured}",
        )
    }
    post("/test/travelexpense/create") {
        val service = TravelExpenseService(call = call, receiptStorageRoot = TEST_RECEIPT_STORAGE_ROOT)
        val q = call.request.queryParameters
        val input =
            TravelExpenseReportInput(
                purpose = q["purpose"] ?: "Testreise",
                travelFrom = LocalDate.parse(q["from"]!!),
                travelTo = LocalDate.parse(q["to"]!!),
            )
        val dto = service.createDraft(subjectMemberId = q["subjectMemberId"]!!, input = input)
        call.respondText(dto.toPipeString())
    }
    post("/test/travelexpense/update") {
        val service = TravelExpenseService(call = call, receiptStorageRoot = TEST_RECEIPT_STORAGE_ROOT)
        val q = call.request.queryParameters
        val input =
            TravelExpenseReportInput(
                purpose = q["purpose"] ?: "Testreise",
                travelFrom = LocalDate.parse(q["from"]!!),
                travelTo = LocalDate.parse(q["to"]!!),
            )
        val dto = service.updateDraft(reportId = q["id"]!!, input = input)
        call.respondText(dto.toPipeString())
    }
    post("/test/travelexpense/addline") {
        val service = TravelExpenseService(call = call, receiptStorageRoot = TEST_RECEIPT_STORAGE_ROOT)
        val q = call.request.queryParameters
        val kind = TravelExpenseLineKind.valueOf(q["kind"]!!)
        val input =
            TravelExpenseLineInput(
                kind = kind,
                description = q["description"] ?: "Testzeile",
                kilometers = q["kilometers"]?.toBigDecimal(),
                days = q["days"]?.toInt(),
                amount = q["amount"]?.toBigDecimal(),
            )
        val dto = service.addLine(reportId = q["reportId"]!!, input = input)
        call.respondText(dto.toPipeString())
    }
    post("/test/travelexpense/removeline") {
        val service = TravelExpenseService(call = call, receiptStorageRoot = TEST_RECEIPT_STORAGE_ROOT)
        val dto = service.removeLine(call.request.queryParameters["lineId"]!!)
        call.respondText(dto.toPipeString())
    }
    post("/test/travelexpense/submit") {
        val service = TravelExpenseService(call = call, receiptStorageRoot = TEST_RECEIPT_STORAGE_ROOT)
        val dto = service.submitReport(call.request.queryParameters["id"]!!)
        call.respondText(dto.toPipeString())
    }
    get("/test/travelexpense/mine") {
        val service = TravelExpenseService(call = call, receiptStorageRoot = TEST_RECEIPT_STORAGE_ROOT)
        call.respondText(service.listMyReports().joinToString(";") { it.toPipeString() })
    }
    post("/test/travelexpense/withdraw") {
        val service = TravelExpenseService(call = call, receiptStorageRoot = TEST_RECEIPT_STORAGE_ROOT)
        val dto = service.withdrawReport(call.request.queryParameters["id"]!!)
        call.respondText(dto.toPipeString())
    }
    get("/test/travelexpense/list") {
        val service = TravelExpenseService(call = call, receiptStorageRoot = TEST_RECEIPT_STORAGE_ROOT)
        val q = call.request.queryParameters
        val list =
            service.listReports(
                status = q["status"]?.let { TravelExpenseReportStatus.valueOf(it) },
                afterSubmittedAt = q["afterSubmittedAt"]?.let { LocalDateTime.parse(it) },
                afterId = q["afterId"],
            )
        call.respondText(list.joinToString(";") { it.toPipeString() })
    }
    post("/test/travelexpense/decide") {
        val service = TravelExpenseService(call = call, receiptStorageRoot = TEST_RECEIPT_STORAGE_ROOT)
        val q = call.request.queryParameters
        val dto = service.decideReport(reportId = q["id"]!!, approve = q["approve"]!!.toBoolean(), note = q["note"])
        call.respondText(dto.toPipeString())
    }
    post("/test/travelexpense/retry") {
        val service = TravelExpenseService(call = call, receiptStorageRoot = TEST_RECEIPT_STORAGE_ROOT)
        val dto = service.retryPosting(call.request.queryParameters["id"]!!)
        call.respondText(dto.toPipeString())
    }
}

internal fun StatusPagesConfig.installTravelExpenseExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}
