package network.lapis.cloud.server.rpc

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import network.lapis.cloud.shared.domain.BillingInterval
import network.lapis.cloud.shared.domain.MembershipTierDto
import network.lapis.cloud.shared.domain.MembershipTierInput
import network.lapis.cloud.shared.rpc.BadRequestException
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.MembershipTierClosedException
import network.lapis.cloud.shared.rpc.MembershipTierIntervalLockedException
import network.lapis.cloud.shared.rpc.MembershipTierNameTakenException
import network.lapis.cloud.shared.rpc.NotFoundException
import network.lapis.cloud.shared.rpc.UnauthenticatedException
import java.math.BigDecimal

/**
 * Welle V1.9.18 -- throwaway test routes calling [ContributionService] directly (the house style of
 * `ContributionReliefTestRoutes`): no wire format to reverse-engineer, the HTTP status carries the
 * exception type, the body carries a stable token for the three typed tier exceptions.
 */
internal fun MembershipTierDto.toTierPipeString(): String =
    listOf(id, name, contributionAmount.toPlainString(), billingInterval.name, active, paymentTermDays).joinToString("|")

internal fun StatusPagesConfig.installMembershipTierExceptionHandlers() {
    exception<UnauthenticatedException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Unauthorized) }
    exception<ForbiddenException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Forbidden) }
    exception<NotFoundException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.NotFound) }
    exception<ConflictException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.Conflict) }
    exception<BadRequestException> { call, cause -> call.respondText(cause.message, status = HttpStatusCode.BadRequest) }
    exception<MembershipTierNameTakenException> { call, _ -> call.respondText("NAME_TAKEN", status = HttpStatusCode.Conflict) }
    exception<MembershipTierIntervalLockedException> { call, _ -> call.respondText("INTERVAL_LOCKED", status = HttpStatusCode.Conflict) }
    exception<MembershipTierClosedException> { call, _ -> call.respondText("TIER_CLOSED", status = HttpStatusCode.Conflict) }
}

private fun io.ktor.http.Parameters.toTierInput(): MembershipTierInput =
    MembershipTierInput(
        name = this["name"] ?: "",
        description = this["description"] ?: "",
        contributionAmount = BigDecimal(this["amount"] ?: "10.00"),
        billingInterval = BillingInterval.valueOf(this["interval"] ?: "MONTHLY"),
        active = this["active"]?.toBooleanStrict() ?: true,
        paymentTermDays = this["term"]?.toInt() ?: 14,
    )

internal fun Route.registerMembershipTierTestRoutes() {
    get("/test/tier/list") {
        call.respondText(ContributionService(call).listMembershipTiers().joinToString(";") { it.toTierPipeString() })
    }
    get("/test/tier/overview") {
        val o = ContributionService(call).listMembershipTierOverview()
        val counts = o.memberCounts.entries.joinToString(",") { "${it.key}=${it.value}" }
        call.respondText("${o.tiers.size}#$counts#${o.activeMembersWithoutTier}")
    }
    post("/test/tier/create") {
        call.respondText(ContributionService(call).createMembershipTier(call.request.queryParameters.toTierInput()).toTierPipeString())
    }
    post("/test/tier/update") {
        val q = call.request.queryParameters
        call.respondText(ContributionService(call).updateMembershipTier(id = q["id"]!!, input = q.toTierInput()).toTierPipeString())
    }
    post("/test/tier/generate") {
        val q = call.request.queryParameters
        val count =
            ContributionService(call).generateContributionsForPeriod(
                membershipTierId = q["tierId"]!!,
                periodStart = kotlinx.datetime.LocalDate.parse(q["from"]!!),
                periodEnd = kotlinx.datetime.LocalDate.parse(q["to"]!!),
            )
        call.respondText(count.toString())
    }
    get("/test/tier/verify") {
        val q = call.request.queryParameters
        val r = AuditLogService(call).verifyChainIntegrity(fromSequenceNumber = q["from"]?.toLong(), toSequenceNumber = q["to"]?.toLong())
        call.respondText("${r.valid}:${r.checkedCount}")
    }
}
