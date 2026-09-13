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
import network.lapis.cloud.shared.domain.VolunteerAllowanceCapAcknowledgmentInput
import network.lapis.cloud.shared.domain.VolunteerAllowanceCategory
import network.lapis.cloud.shared.domain.VolunteerAllowanceDeclarationSource
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentDto
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentInput
import network.lapis.cloud.shared.domain.VolunteerAllowancePaymentStatus
import network.lapis.cloud.shared.domain.VolunteerAllowanceSelfDeclarationDto
import network.lapis.cloud.shared.domain.VolunteerAllowanceSelfDeclarationInput
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import java.math.BigDecimal

/**
 * Shared throwaway test-route registrar + pipe-string encoding for [VolunteerAllowanceService]/
 * [network.lapis.cloud.shared.rpc.IVolunteerAllowanceService] -- mirrors [TravelExpenseTestRoutes]'s
 * house style ("throwaway routes calling the service class directly, no wire format to
 * reverse-engineer") so every `VolunteerAllowance*Test` file in this package can share one set of
 * routes.
 */
internal fun VolunteerAllowancePaymentDto.toPipeString(): String =
    listOf(
        id,
        subjectMemberId,
        status.name,
        category.name,
        amount.toString(),
        activityDescription,
        paymentDate.toString(),
        requestedBy,
        submittedAt?.toString() ?: "-",
        decidedBy ?: "-",
        decisionNote ?: "-",
        executedAt?.toString() ?: "-",
        postedJournalEntryId ?: "-",
        executionError ?: "-",
        priorTotalSnapshot?.toString() ?: "-",
        freeAmountSnapshot?.toString() ?: "-",
        exceedingAmountSnapshot?.toString() ?: "-",
        capDisclaimerVersion ?: "-",
        capAcknowledgedAt?.toString() ?: "-",
    ).joinToString("|")

internal fun VolunteerAllowanceSelfDeclarationDto.toPipeString(): String =
    listOf(
        id,
        memberId,
        category.name,
        calendarYear.toString(),
        source.name,
        signedOn?.toString() ?: "-",
        recordedByDisplayName,
    ).joinToString("|")

internal fun Route.registerVolunteerAllowanceTestRoutes() {
    get("/test/volunteerallowance/config") {
        val service = VolunteerAllowanceService(call = call)
        val dto = service.getVolunteerAllowanceConfig()
        call.respondText("${dto.instructorCap}|${dto.honoraryCap}|${dto.expenseAccountConfigured}|${dto.bankAccountConfigured}")
    }
    get("/test/volunteerallowance/cap-disclaimer") {
        val service = VolunteerAllowanceService(call = call)
        val dto = service.getCapDisclaimer()
        call.respondText("${dto.version}|${dto.sha256}")
    }
    get("/test/volunteerallowance/year-status") {
        val service = VolunteerAllowanceService(call = call)
        val q = call.request.queryParameters
        val dto =
            service.getYearStatus(
                memberId = q["memberId"]!!,
                category = VolunteerAllowanceCategory.valueOf(q["category"]!!),
                year = q["year"]!!.toInt(),
            )
        call.respondText(
            "${dto.annualCap}|${dto.postedTotalInThisOrganization}|${dto.remainingInThisOrganization}|" +
                (dto.declaration?.let { "1" } ?: "0"),
        )
    }
    post("/test/volunteerallowance/declare-self") {
        val service = VolunteerAllowanceService(call = call)
        val q = call.request.queryParameters
        val dto = service.declareSelf(category = VolunteerAllowanceCategory.valueOf(q["category"]!!), calendarYear = q["year"]!!.toInt())
        call.respondText(dto.toPipeString())
    }
    post("/test/volunteerallowance/record-paper-declaration") {
        val service = VolunteerAllowanceService(call = call)
        val q = call.request.queryParameters
        val input =
            VolunteerAllowanceSelfDeclarationInput(
                memberId = q["memberId"]!!,
                category = VolunteerAllowanceCategory.valueOf(q["category"]!!),
                calendarYear = q["year"]!!.toInt(),
                source = VolunteerAllowanceDeclarationSource.ON_PAPER,
                signedOn = q["signedOn"]?.let { LocalDate.parse(it) },
            )
        val dto = service.recordPaperDeclaration(input)
        call.respondText(dto.toPipeString())
    }
    get("/test/volunteerallowance/declarations") {
        val service = VolunteerAllowanceService(call = call)
        val q = call.request.queryParameters
        val list = service.listDeclarations(memberId = q["memberId"], calendarYear = q["year"]?.toInt())
        call.respondText(list.joinToString(";") { it.toPipeString() })
    }
    post("/test/volunteerallowance/void-paper-declaration") {
        val service = VolunteerAllowanceService(call = call)
        val result = service.voidPaperDeclaration(call.request.queryParameters["id"]!!)
        call.respondText(result.toString())
    }
    post("/test/volunteerallowance/create") {
        val service = VolunteerAllowanceService(call = call)
        val q = call.request.queryParameters
        val input =
            VolunteerAllowancePaymentInput(
                category = VolunteerAllowanceCategory.valueOf(q["category"] ?: "HONORARY"),
                amount = q["amount"]?.toBigDecimal() ?: BigDecimal("50.00"),
                activityDescription = q["description"] ?: "Testtätigkeit",
                paymentDate = LocalDate.parse(q["date"]!!),
            )
        val dto = service.createDraft(subjectMemberId = q["subjectMemberId"]!!, input = input)
        call.respondText(dto.toPipeString())
    }
    post("/test/volunteerallowance/update") {
        val service = VolunteerAllowanceService(call = call)
        val q = call.request.queryParameters
        val input =
            VolunteerAllowancePaymentInput(
                category = VolunteerAllowanceCategory.valueOf(q["category"] ?: "HONORARY"),
                amount = q["amount"]?.toBigDecimal() ?: BigDecimal("50.00"),
                activityDescription = q["description"] ?: "Testtätigkeit",
                paymentDate = LocalDate.parse(q["date"]!!),
            )
        val dto = service.updateDraft(paymentId = q["id"]!!, input = input)
        call.respondText(dto.toPipeString())
    }
    post("/test/volunteerallowance/submit") {
        val service = VolunteerAllowanceService(call = call)
        val dto = service.submitPayment(call.request.queryParameters["id"]!!)
        call.respondText(dto.toPipeString())
    }
    post("/test/volunteerallowance/withdraw") {
        val service = VolunteerAllowanceService(call = call)
        val dto = service.withdrawPayment(call.request.queryParameters["id"]!!)
        call.respondText(dto.toPipeString())
    }
    get("/test/volunteerallowance/mine") {
        val service = VolunteerAllowanceService(call = call)
        call.respondText(service.listMyPayments().joinToString(";") { it.toPipeString() })
    }
    get("/test/volunteerallowance/list") {
        val service = VolunteerAllowanceService(call = call)
        val q = call.request.queryParameters
        val list =
            service.listPayments(
                status = q["status"]?.let { VolunteerAllowancePaymentStatus.valueOf(it) },
                afterSubmittedAt = q["afterSubmittedAt"]?.let { LocalDateTime.parse(it) },
                afterId = q["afterId"],
            )
        call.respondText(list.joinToString(";") { it.toPipeString() })
    }
    post("/test/volunteerallowance/decide") {
        val service = VolunteerAllowanceService(call = call)
        val q = call.request.queryParameters
        val capAck =
            if (q["capVersion"] != null && q["capSha256"] != null) {
                VolunteerAllowanceCapAcknowledgmentInput(disclaimerVersion = q["capVersion"]!!, disclaimerSha256 = q["capSha256"]!!)
            } else {
                null
            }
        val dto =
            service.decidePayment(
                paymentId = q["id"]!!,
                approve = q["approve"]!!.toBoolean(),
                note = q["note"],
                capAcknowledgment = capAck,
            )
        call.respondText(dto.toPipeString())
    }
    post("/test/volunteerallowance/retry") {
        val service = VolunteerAllowanceService(call = call)
        val dto = service.retryPosting(call.request.queryParameters["id"]!!)
        call.respondText(dto.toPipeString())
    }
}

internal fun StatusPagesConfig.installVolunteerAllowanceExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}
