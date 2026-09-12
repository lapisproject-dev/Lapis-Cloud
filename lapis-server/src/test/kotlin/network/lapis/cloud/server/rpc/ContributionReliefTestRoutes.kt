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
import network.lapis.cloud.shared.domain.ContributionReliefKind
import network.lapis.cloud.shared.domain.ContributionReliefReason
import network.lapis.cloud.shared.domain.ContributionReliefRequestDto
import network.lapis.cloud.shared.domain.ContributionReliefRequestInput
import network.lapis.cloud.shared.domain.ContributionReliefStatus
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException

/**
 * Shared throwaway test-route registrar + pipe-string encoding for [ContributionReliefService] --
 * mirrors [AuctionServiceTest]'s house style ("throwaway routes calling the service class
 * directly, no wire format to reverse-engineer") so every `ContributionRelief*Test` file in this
 * package can share one set of routes instead of re-declaring them.
 */
internal fun ContributionReliefRequestDto.toPipeString(): String =
    listOf(
        id,
        subjectMemberId,
        kind.name,
        status.name,
        reasonCategory.name,
        reasonText ?: "-",
        deferralContributionId ?: "-",
        deferralNewDueDate?.toString() ?: "-",
        deferralPreviousDueDate?.toString() ?: "-",
        exemptionFrom?.toString() ?: "-",
        exemptionUntil?.toString() ?: "-",
        reductionTargetTierId ?: "-",
        requestedBy,
        decidedBy ?: "-",
        decisionNote ?: "-",
        executedAt?.toString() ?: "-",
        executionError ?: "-",
        requestedAt.toString(),
    ).joinToString("|")

internal fun Route.registerContributionReliefTestRoutes() {
    post("/test/relief/request") {
        val service = ContributionReliefService(call = call)
        val q = call.request.queryParameters
        val kind = ContributionReliefKind.valueOf(q["kind"]!!)
        val input =
            ContributionReliefRequestInput(
                kind = kind,
                reasonCategory = ContributionReliefReason.valueOf(q["reason"] ?: "OTHER"),
                reasonText = q["reasonText"],
                deferralContributionId = q["contributionId"],
                deferralNewDueDate = q["newDueDate"]?.let { LocalDate.parse(it) },
                exemptionFrom = q["exemptFrom"]?.let { LocalDate.parse(it) },
                exemptionUntil = q["exemptUntil"]?.let { LocalDate.parse(it) },
                reductionTargetTierId = q["targetTierId"],
                reviewDueOn = q["reviewDueOn"]?.let { LocalDate.parse(it) },
            )
        val dto = service.requestRelief(subjectMemberId = q["subjectMemberId"]!!, input = input)
        call.respondText(dto.toPipeString())
    }
    get("/test/relief/mine") {
        val service = ContributionReliefService(call = call)
        val list = service.listMyReliefRequests()
        call.respondText(list.joinToString(";") { it.toPipeString() })
    }
    post("/test/relief/withdraw") {
        val service = ContributionReliefService(call = call)
        val dto = service.withdrawReliefRequest(call.request.queryParameters["id"]!!)
        call.respondText(dto.toPipeString())
    }
    get("/test/relief/list") {
        val service = ContributionReliefService(call = call)
        val q = call.request.queryParameters
        val list =
            service.listReliefRequests(
                status = q["status"]?.let { ContributionReliefStatus.valueOf(it) },
                kind = q["kind"]?.let { ContributionReliefKind.valueOf(it) },
                reviewDueOnly = q["reviewDueOnly"]?.toBoolean() ?: false,
                afterRequestedAt = q["afterRequestedAt"]?.let { LocalDateTime.parse(it) },
                afterId = q["afterId"],
            )
        call.respondText(list.joinToString(";") { it.toPipeString() })
    }
    post("/test/relief/decide") {
        val service = ContributionReliefService(call = call)
        val q = call.request.queryParameters
        val dto = service.decideReliefRequest(requestId = q["id"]!!, approve = q["approve"]!!.toBoolean(), note = q["note"])
        call.respondText(dto.toPipeString())
    }
    post("/test/relief/retry") {
        val service = ContributionReliefService(call = call)
        val dto = service.retryReliefExecution(call.request.queryParameters["id"]!!)
        call.respondText(dto.toPipeString())
    }
    get("/test/relief/exemption") {
        val service = ContributionReliefService(call = call)
        val dto = service.getExemptionState(call.request.queryParameters["memberId"]!!)
        call.respondText("${dto.memberId}|${dto.exemptFrom ?: "-"}|${dto.exemptUntil ?: "-"}|${dto.sourceRequestId ?: "-"}")
    }
}

internal fun StatusPagesConfig.installContributionReliefExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
}
