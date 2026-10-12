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
import kotlinx.datetime.LocalTime
import network.lapis.cloud.shared.domain.MultiDayRule
import network.lapis.cloud.shared.domain.TravelExpenseContactDto
import network.lapis.cloud.shared.domain.TravelExpenseDayInput
import network.lapis.cloud.shared.domain.TravelExpenseLineInput
import network.lapis.cloud.shared.domain.TravelExpenseLineKind
import network.lapis.cloud.shared.domain.TravelExpensePayoutChoice
import network.lapis.cloud.shared.domain.TravelExpenseReportDto
import network.lapis.cloud.shared.domain.TravelExpenseReportInput
import network.lapis.cloud.shared.domain.TravelExpenseReportStatus
import network.lapis.cloud.shared.domain.TravelExpenseSettingsInput
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
        advanceCents.toString(),
        payoutChoice?.name ?: "-",
        payoutCents.toString(),
        deductionCents.toString(),
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
    get("/test/travelexpense/ratesinfo") {
        val service = TravelExpenseService(call = call, receiptStorageRoot = TEST_RECEIPT_STORAGE_ROOT)
        val dto = service.getTravelExpenseRates()
        call.respondText(
            listOf(
                dto.canEditSettings,
                dto.contact.name ?: "-",
                dto.contact.email ?: "-",
                dto.advanceAccountId ?: "-",
                dto.donationWaiverAccountId ?: "-",
                dto.rateSet?.tierHours ?: "-",
            ).joinToString("|"),
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
                advanceCents = q["advance"]?.toLong(),
                // Default PAYOUT so the pre-V1.9.122 tests keep submitting; payout=none leaves it unset.
                payoutChoice =
                    when (val p = q["payout"] ?: "PAYOUT") {
                        "none" -> null
                        else -> TravelExpensePayoutChoice.valueOf(p)
                    },
            )
        var dto = service.createDraft(subjectMemberId = q["subjectMemberId"]!!, input = input)
        // Default: fill the day grid with "uninteresting" times (1 h absence = below the first tier = 0 EUR),
        // so a middle day (24 h) is the only thing that carries money. fillDays=false leaves the grid empty.
        if (q["fillDays"] != "false") {
            val days = MultiDayRule.travelDays(from = input.travelFrom, to = input.travelTo)
            dto =
                service.saveDays(
                    reportId = dto.id,
                    days =
                        days.mapIndexed { i, d ->
                            when {
                                days.size == 1 -> TravelExpenseDayInput(dayDate = d, timeFrom = LocalTime(8, 0), timeTo = LocalTime(9, 0))
                                i == 0 -> TravelExpenseDayInput(dayDate = d, timeFrom = LocalTime(23, 0))
                                i == days.lastIndex -> TravelExpenseDayInput(dayDate = d, timeTo = LocalTime(1, 0))
                                else -> TravelExpenseDayInput(dayDate = d)
                            }
                        },
                )
        }
        call.respondText(dto.toPipeString())
    }
    post("/test/travelexpense/savedays") {
        val service = TravelExpenseService(call = call, receiptStorageRoot = TEST_RECEIPT_STORAGE_ROOT)
        val q = call.request.queryParameters
        // days=2026-10-14@08:00-17:00@bl@Berlin,2026-10-15@-@... : date@from-to@flags(b/l/d)@destination ('-' = none)
        val days =
            q["days"]!!.split(",").map { item ->
                val parts = item.split("@")
                val times = parts[1].split("-")
                TravelExpenseDayInput(
                    dayDate = LocalDate.parse(parts[0]),
                    timeFrom = times.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { LocalTime.parse(it) },
                    timeTo = times.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { LocalTime.parse(it) },
                    breakfastProvided = parts.getOrNull(2)?.contains('b') == true,
                    lunchProvided = parts.getOrNull(2)?.contains('l') == true,
                    dinnerProvided = parts.getOrNull(2)?.contains('d') == true,
                    destination = parts.getOrNull(3)?.takeIf { it != "-" && it.isNotBlank() },
                )
            }
        call.respondText(service.saveDays(reportId = q["id"]!!, days = days).toPipeString())
    }
    post("/test/travelexpense/updateline") {
        val service = TravelExpenseService(call = call, receiptStorageRoot = TEST_RECEIPT_STORAGE_ROOT)
        val q = call.request.queryParameters
        val input =
            TravelExpenseLineInput(
                kind = TravelExpenseLineKind.valueOf(q["kind"]!!),
                description = q["description"] ?: "Testzeile",
                kilometers = q["kilometers"]?.toBigDecimal(),
                amount = q["amount"]?.toBigDecimal(),
                amountCents = q["amountCents"]?.toLong(),
                passengerCount = q["passengers"]?.toInt(),
                dayDate = q["dayDate"]?.let { LocalDate.parse(it) },
                nights = q["nights"]?.toInt(),
                breakfastIncluded = q["breakfastIncluded"]?.toBoolean(),
            )
        call.respondText(service.updateLine(lineId = q["lineId"]!!, input = input).toPipeString())
    }
    post("/test/travelexpense/settings") {
        val service = TravelExpenseService(call = call, receiptStorageRoot = TEST_RECEIPT_STORAGE_ROOT)
        val q = call.request.queryParameters
        val dto =
            service.updateTravelExpenseSettings(
                TravelExpenseSettingsInput(
                    tierHours = (q["hours"] ?: "8,14,24").split(",").map { it.toInt() },
                    tierCents = (q["cents"] ?: "600,1200,2800").split(",").map { it.toLong() },
                    breakfastPercent = q["b"]?.toInt() ?: 20,
                    lunchPercent = q["l"]?.toInt() ?: 40,
                    dinnerPercent = q["d"]?.toInt() ?: 40,
                    mileageCentsPerKm = q["mileage"]?.toLong(),
                    passengerCentsPerKm = q["passenger"]?.toLong() ?: 2,
                    perDiemLegacyRate = q["perDiem"]?.toBigDecimal(),
                    contact =
                        TravelExpenseContactDto(
                            name = q["name"],
                            email = q["email"],
                            phone = q["phone"],
                            hint = q["hint"],
                        ),
                    advanceAccountId = q["advanceAccount"],
                    donationWaiverAccountId = q["waiverAccount"],
                ),
            )
        call.respondText("${dto.canEditSettings}|${dto.rateSet?.tierCents}|${dto.contact.name ?: "-"}")
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
                amountCents = q["amountCents"]?.toLong(),
                passengerCount = q["passengers"]?.toInt(),
                dayDate = q["dayDate"]?.let { LocalDate.parse(it) },
                nights = q["nights"]?.toInt(),
                breakfastIncluded = q["breakfastIncluded"]?.toBoolean(),
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
