package network.lapis.cloud.server.mcp.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import network.lapis.cloud.server.events.EventParticipant
import network.lapis.cloud.server.events.EventRegistrationResult
import network.lapis.cloud.server.events.EventRegistrationSubmission
import network.lapis.cloud.server.mcp.auth.McpPrincipal
import network.lapis.cloud.server.rpc.EventReads
import network.lapis.cloud.server.rpc.MemberReads
import network.lapis.cloud.shared.rpc.ForbiddenException
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.uuid.Uuid

/**
 * Welle V1.8.2b -- thrown when `register_for_event`'s target event has a non-zero fee: payment can
 * never be completed through this tool (there is no agent-facing checkout flow), so registering
 * for a paid event is not offered at all, see class KDoc "No payment through MCP". Mapped by
 * `McpToolDispatcher` to a dedicated `event_requires_payment` `ToolError` -- the ONE other
 * deliberately non-opaque rejection in this dispatcher besides `draft_limit_reached`, because
 * "this event costs money" is a PUBLIC property already exposed by `list_upcoming_events`
 * (`requiresPayment`/`feeAmount`), not something this rejection would newly reveal.
 */
internal class McpEventRequiresPaymentException : Exception("Event requires payment -- not available through MCP")

/**
 * `register_for_event` -- Welle V1.8.2's first write tool. A **class**, not an `object` like every
 * read tool (`McpToolDispatcher` owns the one instance, constructed with the SAME
 * [EventRegistrationSubmission] the ordinary member-facing `EventService.registerSelf` RPC path
 * already uses -- one shared fachlogik, see that class' own KDoc for the full step-by-step
 * contract this tool relies on unchanged).
 *
 * **No payment through MCP (Welle V1.8.2b)**: a fee-bearing event is rejected UP FRONT, via
 * [EventReads.findPublishedEventFee] -- BEFORE [MemberReads.getDisplayNameAndEmail] or
 * [EventRegistrationSubmission.submit] ever run -- as [McpEventRequiresPaymentException]. There is
 * no code path left in this tool that can produce [EventRegistrationResult.PaymentRequired]; the
 * only remaining "cannot register" outcomes are the free-event ones. A non-existent/non-PUBLISHED
 * event is reported as the SAME generic [ForbiddenException] a fee check failure never produces
 * (see [execute] -- deliberately no existence oracle: an agent must not be able to distinguish
 * "does not exist" from "requires payment" from the id lookup alone; only an EXISTING, PUBLISHED,
 * fee-bearing event produces `event_requires_payment`).
 *
 * **No oracle across the four remaining "cannot register" outcomes**: `EventNotAvailable`/
 * `WaitlistFull`/`GatewayUnavailable`/`PaymentFailed` all become the SAME [ForbiddenException],
 * caught by `McpToolDispatcher` and reported to the agent as the same generic "Not permitted for
 * this account" text -- an agent (or whatever prompted it) must never be able to distinguish
 * "event is full" from "registration is closed" from "payment gateway misconfigured" through this
 * tool. [ForbiddenException]/[McpEventRequiresPaymentException] together ARE the full, deliberately
 * two-tier oracle this tool exposes: "not permitted" vs. "requires payment", nothing finer.
 *
 * **Idempotent by construction**: [EventRegistrationSubmission.submit] itself de-duplicates via
 * `active_participant_key` (member id, in this tool's case) -- a second call for the same event
 * returns [EventRegistrationResult.AlreadyRegistered] rather than a second row. That variant alone
 * carries neither a status nor a waitlist position (see its own KDoc), so this tool does one
 * additional, narrowly-scoped read of the caller's OWN registration
 * ([EventReads.findOwnRegistrationStatus]) to fill both in.
 */
internal class RegisterForEventTool(
    private val submission: EventRegistrationSubmission,
) {
    companion object {
        val definition =
            McpToolDefinition(
                name = "register_for_event",
                title = "Für eine Veranstaltung anmelden",
                description =
                    "Meldet Sie selbst für eine veröffentlichte, GEBÜHRENFREIE Veranstaltung an. Kostenpflichtige " +
                        "Veranstaltungen sind über dieses Werkzeug nicht möglich -- melden Sie sich dafür in der " +
                        "Weboberfläche an. Ein zweiter Aufruf für dieselbe Veranstaltung legt KEINE zweite Anmeldung an.",
                inputSchema =
                    buildJsonObject {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("eventId") {
                                put("type", "string")
                                put("description", "Die UUID der Veranstaltung (siehe list_upcoming_events).")
                            }
                        }
                        putJsonArray("required") { add(JsonPrimitive("eventId")) }
                    },
                writing = true,
            )
    }

    suspend fun execute(
        principal: McpPrincipal,
        arguments: JsonObject?,
    ): JsonElement {
        // R4 (McpLayerBoundary): no argument named memberId is ever read here -- identity is
        // exclusively principal.memberId, resolved by McpTokenAuth.
        val args = arguments ?: JsonObject(emptyMap())
        val eventIdRaw = args.requiredStringArg(key = "eventId", minLength = 1, maxLength = 200)
        val eventId =
            runCatching { Uuid.parse(eventIdRaw) }
                .getOrElse { throw McpInvalidToolArgumentsException("'eventId' must be a valid UUID") }

        // Welle V1.8.2b -- "No payment through MCP" (see class KDoc): checked FIRST, before any
        // other read or the submission itself. A missing/non-PUBLISHED event is the SAME generic
        // ForbiddenException every other "cannot register" outcome below produces -- no existence
        // oracle. signum() != 0, NEVER `!= BigDecimal.ZERO` -- `0.00.equals(0)` is false (differing
        // scale), which would let a `0.00`-fee event wrongly reach this branch.
        val event =
            transaction { EventReads.findPublishedEventFee(eventId = eventId) }
                ?: throw ForbiddenException("Not permitted for this account")
        if (event.feeAmount.signum() != 0) throw McpEventRequiresPaymentException()

        val (displayName, email) =
            transaction { MemberReads.getDisplayNameAndEmail(memberId = principal.memberId) }
                ?: throw ForbiddenException("Not permitted for this account")

        val result =
            submission.submit(
                eventId = eventId,
                participant = EventParticipant.Member(memberId = principal.memberId, displayName = displayName, email = email),
            )

        // One read fewer than before (V1.8.2b) -- the title was already read by findPublishedEventFee above.
        val eventTitle = event.title

        return when (result) {
            is EventRegistrationResult.Confirmed ->
                buildJsonObject {
                    put("registered", true)
                    put("alreadyRegistered", false)
                    put("status", "CONFIRMED")
                    put("eventTitle", eventTitle)
                }
            is EventRegistrationResult.Waitlisted ->
                buildJsonObject {
                    put("registered", true)
                    put("alreadyRegistered", false)
                    put("status", "WAITLISTED")
                    put("waitlistPosition", result.position)
                    put("eventTitle", eventTitle)
                }
            // Welle V1.8.2b -- unreachable in the normal case (the fee pre-check above already
            // rejected a fee-bearing event as McpEventRequiresPaymentException before submit() was
            // ever called), kept only as a defense-in-depth backstop against a fee raised from zero
            // to non-zero in the race window between that pre-check and submission.submit's own
            // lock-time re-read: never `paymentUrl` (removed for good, see class KDoc), always the
            // SAME opaque ForbiddenException as every other "cannot register" outcome here.
            is EventRegistrationResult.PaymentRequired -> throw ForbiddenException("Not permitted for this account")
            EventRegistrationResult.AlreadyRegistered -> {
                // AlreadyRegistered itself carries neither a status nor a waitlist position (see its
                // own KDoc) -- one additional, self-scoped read fills both in.
                val own = transaction { EventReads.findOwnRegistrationStatus(eventId = eventId, memberId = principal.memberId) }
                buildJsonObject {
                    put("registered", true)
                    put("alreadyRegistered", true)
                    put("status", own?.status?.name ?: "CONFIRMED")
                    if (own?.waitlistPosition != null) put("waitlistPosition", own.waitlistPosition)
                    put("eventTitle", eventTitle)
                }
            }
            EventRegistrationResult.EventNotAvailable,
            EventRegistrationResult.WaitlistFull,
            EventRegistrationResult.GatewayUnavailable,
            is EventRegistrationResult.PaymentFailed,
            -> throw ForbiddenException("Not permitted for this account")
        }
    }
}
