package network.lapis.cloud.server.mcp.tools

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import network.lapis.cloud.server.ai.retrieval.KnowledgeRetriever
import network.lapis.cloud.server.events.EventRegistrationSubmission
import network.lapis.cloud.server.mcp.audit.McpToolCallAuditEntry
import network.lapis.cloud.server.mcp.audit.McpToolCallAuditRecorder
import network.lapis.cloud.server.mcp.audit.McpToolCallOutcome
import network.lapis.cloud.server.mcp.auth.McpPrincipal
import network.lapis.cloud.server.mcp.ratelimit.McpRateLimitDecision
import network.lapis.cloud.server.mcp.ratelimit.McpToolCallRateLimiter
import network.lapis.cloud.shared.rpc.ForbiddenException
import kotlin.time.Clock

/** Result of one `tools/call` dispatch -- see `routes.McpRoutes` for how each variant maps onto the JSON-RPC wire response. */
internal sealed interface McpToolCallResult {
    data class Success(
        val content: JsonElement,
    ) : McpToolCallResult

    data class RateLimited(
        val retryAfterSeconds: Int,
    ) : McpToolCallResult

    data object UnknownTool : McpToolCallResult

    data class InvalidArguments(
        val reason: String,
    ) : McpToolCallResult

    data object Forbidden : McpToolCallResult

    data object Timeout : McpToolCallResult

    data class InternalError(
        val reason: String,
    ) : McpToolCallResult

    /**
     * Welle V1.8.2 -- a TYPED tool-side error, distinct from the generic [Forbidden]/
     * [InternalError] catch-alls: right now the only producer is
     * [CreatePostDraftTool.execute]'s [McpDraftLimitReachedException], mapped here with
     * `code = "draft_limit_reached"` and `data = {"openDraftCount": N}` so the caller can see WHY,
     * unlike every other rejection in this dispatcher which is deliberately opaque. `routes
     * .McpRoutes` renders this as a `tools/call` result with `isError: true`, never a new JSON-RPC
     * error code.
     */
    data class ToolError(
        val code: String,
        val data: JsonObject,
    ) : McpToolCallResult
}

private class McpResultTooLargeException : Exception("Tool result exceeds the configured response size limit")

private val logger = KotlinLogging.logger {}

/**
 * Executes exactly one `tools/call`: rate-limits, dispatches by name against
 * [McpToolCatalog.TOOLS], runs the tool on [Dispatchers.IO] under [toolTimeoutMs], caps the
 * serialized result at [maxResponseBytes], and audits every outcome that actually reached a tool
 * -- success, unknown-tool, invalid arguments, forbidden, timeout, error (see
 * `audit.McpToolCallAuditRecorder` KDoc). The one deliberate exception is a rate-limited call,
 * which is never audited -- see the comment at its `return` below. Never lets a tool's own
 * exception escape as an unhandled 500 -- every failure mode becomes a typed [McpToolCallResult].
 */
internal class McpToolDispatcher(
    private val rateLimiter: McpToolCallRateLimiter,
    private val toolTimeoutMs: Long,
    private val maxResponseBytes: Int,
    retriever: KnowledgeRetriever,
    /** Welle V1.8.2 -- the SAME instance `EventService.registerSelf` uses, see `RegisterForEventTool` KDoc. */
    registrationSubmission: EventRegistrationSubmission,
    /**
     * Welle V1.8.2b -- mirrors `McpConfig.isWriteOperational`, passed explicitly (not read from a
     * global), same posture as every other config value this class already takes as a constructor
     * parameter. THE actual enforcement point for the operator's write switch (see [dispatch] KDoc
     * "resolution order") -- `routes.McpRoutes`' `tools/list` catalog filter is only advertising,
     * never the gate: a write-capable token minted before the switch was flipped off loses its
     * effect the moment this flag flips, without needing to be revoked.
     */
    private val writeEnabled: Boolean = false,
) {
    private val searchStatuteTool = SearchStatuteTool(retriever = retriever)
    private val registerForEventTool = RegisterForEventTool(submission = registrationSubmission)

    /**
     * Welle V1.8.2 -- resolution order is DELIBERATE and a review blocker to reorder: resolve the
     * tool definition first (cheap, in-memory; also needed to know `.writing`) -> **rate limit**
     * (now keyed by `toolName`, see `McpToolCallRateLimiter` KDoc "Welle V1.8.2 amendment") ->
     * **write-scope check** -> execute. An UNKNOWN tool name still passes through the rate limiter
     * exactly as before (it simply matches no per-tool write quota, so it only counts against the
     * three global windows) -- audited as `UNKNOWN_TOOL` only AFTER that check, preserving the
     * original "shed load before any DB write" property. The member's own kill-switch is NOT
     * checked here at all -- it already ran in `McpTokenAuth.resolve` before this dispatcher was
     * ever reached (a blocked member never gets a `Valid` principal), so `routes.McpRoutes`'
     * `POST /mcp` handler is the correct place to verify that ordering, not this class.
     *
     * **Write-scope `Forbidden` review fix (V1.8.2 wave 2)**: the FIRST implementation audited this
     * rejection (one `audit()` DB write per call) BEFORE the rate limiter ever ran -- so a read-only
     * (or leaked) token in a `tools/call` loop against a writing tool produced one `mcp_tool_call
     * _audit` INSERT per request with no cap at all, none of the three global rate-limit windows
     * ([McpToolCallRateLimiter]) ever engaging. That broke this very dispatcher's own documented
     * "shed load before any DB write" property. Fixed the same way the [McpRateLimitDecision]
     * rejection below already handles it: **no `audit()` call, no DB write** -- see that branch's
     * comment for why silently declining to audit a request that never reached a tool is the
     * correct posture, not a gap.
     *
     * **Write-scope rate-limit-bypass security fix (V1.8.2 wave 3)**: the wave-2 fix above moved the
     * *audit write* after the rate limiter, but the rejection itself -- the `return
     * McpToolCallResult.Forbidden` -- still ran BEFORE [rateLimiter.checkAndRecord], so a read-only
     * (or write-disabled) token hammering a writing tool never touched ANY of the three global
     * windows either: only the (deliberately unaudited) `Forbidden` branch ran, forever, uncapped.
     * That is the exact unbounded-request DoS shape this dispatcher's "shed load before any DB
     * write" property exists to prevent -- it just moved from the audit table to the rate limiter
     * itself. Fixed by calling [rateLimiter.checkAndRecord] FIRST, unconditionally, before either
     * rejection is returned -- exactly like an unknown tool name already did. The budget-preservation
     * intent from wave 2 ("a read-only token's OWN per-tool quota is never burned by a call it could
     * never succeed at") is kept by choosing the `toolName` argument based on whether the write gate
     * would reject the call: `null` (global windows only, matching [McpRateLimitDecision]'s
     * documented behaviour for an unmatched name) when it would, the real tool name (global windows
     * + that tool's own per-tool quota) when it would not. Exactly one `checkAndRecord` call happens
     * per dispatch either way, so a legitimate call's own three global windows are never double-
     * charged.
     */
    suspend fun dispatch(
        name: String,
        arguments: JsonObject?,
        principal: McpPrincipal,
    ): McpToolCallResult {
        val startedAt = Clock.System.now()

        val tool = McpToolCatalog.TOOLS.find { it.name == name }

        // Welle V1.8.2b -- `!writeEnabled` joins `!principal.canWrite` in the SAME unaudited
        // rejection, not a separate branch: both are "this call could never have succeeded" cases,
        // and neither must cost a DB write (see the class KDoc's "review fix" paragraphs). A
        // write-capable token loses its effect the instant the operator flips writeEnabled off,
        // without needing a revoke.
        val writeGateForbidden = tool != null && tool.writing && (!writeEnabled || !principal.canWrite)

        // Welle V1.8.2 wave 3 security fix -- runs BEFORE the write-gate rejection below (see class
        // KDoc), so a call that can never pass the write gate still counts against the three global
        // windows, exactly like a real call would. `toolName = null` when `writeGateForbidden` keeps
        // that specific tool's own per-tool quota untouched -- this call could never have consumed
        // it anyway -- while `checkAndRecord`'s documented "unmatched name" behaviour still applies
        // the three global windows.
        val rateDecision =
            rateLimiter.checkAndRecord(
                memberId = principal.memberId,
                tokenId = principal.tokenId,
                toolName = if (writeGateForbidden) null else name,
            )
        if (rateDecision !is McpRateLimitDecision.Allowed) {
            // Deliberately NOT audited (no DB write here) -- the whole point of this in-memory
            // limiter is to shed load before it reaches the database; auditing every rejection
            // would mean a caller hammering a single valid token still produces one DB INSERT per
            // request, exactly the load the limiter exists to prevent. Every call that actually
            // reaches a tool -- success, timeout, forbidden, error, unknown-tool -- is still
            // audited unconditionally below, see `audit.McpToolCallAuditRecorder` KDoc.
            val retryAfter =
                when (rateDecision) {
                    is McpRateLimitDecision.TokenLimited -> rateDecision.retryAfterSeconds
                    is McpRateLimitDecision.MemberLimited -> rateDecision.retryAfterSeconds
                    is McpRateLimitDecision.ServerLimited -> rateDecision.retryAfterSeconds
                    McpRateLimitDecision.Allowed -> 0
                }
            return McpToolCallResult.RateLimited(retryAfterSeconds = retryAfter)
        }

        if (writeGateForbidden) {
            // Deliberately NOT audited (no DB write here) -- see the class KDoc's "Write-scope
            // `Forbidden` review fix" paragraphs: a request that never reached a tool must not cost
            // one unconditional DB INSERT, or this exact branch becomes the unbounded-write DoS
            // vector it exists to avoid recreating. Unlike wave 2, this rejection now runs AFTER
            // `checkAndRecord` above, so it is still bounded by the three global rate-limit windows.
            return McpToolCallResult.Forbidden
        }

        if (tool == null) {
            audit(principal = principal, toolName = name, outcome = McpToolCallOutcome.UNKNOWN_TOOL, startedAt = startedAt)
            return McpToolCallResult.UnknownTool
        }

        return try {
            val content =
                withTimeout(toolTimeoutMs) {
                    withContext(Dispatchers.IO) { executeTool(name = name, arguments = arguments, principal = principal) }
                }
            val serializedSize = content.toString().toByteArray(Charsets.UTF_8).size
            if (serializedSize > maxResponseBytes) throw McpResultTooLargeException()
            audit(principal = principal, toolName = name, outcome = McpToolCallOutcome.OK, startedAt = startedAt)
            McpToolCallResult.Success(content = content)
        } catch (e: TimeoutCancellationException) {
            audit(principal = principal, toolName = name, outcome = McpToolCallOutcome.TIMEOUT, startedAt = startedAt)
            McpToolCallResult.Timeout
        } catch (e: McpInvalidToolArgumentsException) {
            audit(principal = principal, toolName = name, outcome = McpToolCallOutcome.ERROR, startedAt = startedAt)
            McpToolCallResult.InvalidArguments(reason = e.message)
        } catch (e: ForbiddenException) {
            audit(principal = principal, toolName = name, outcome = McpToolCallOutcome.FORBIDDEN, startedAt = startedAt)
            McpToolCallResult.Forbidden
        } catch (e: McpDraftLimitReachedException) {
            audit(principal = principal, toolName = name, outcome = McpToolCallOutcome.ERROR, startedAt = startedAt)
            McpToolCallResult.ToolError(
                code = "draft_limit_reached",
                data = buildJsonObject { put("openDraftCount", e.openDraftCount) },
            )
        } catch (e: McpEventRequiresPaymentException) {
            // Welle V1.8.2b -- "No payment through MCP" (see RegisterForEventTool KDoc). Same
            // non-opaque-ToolError treatment as draft_limit_reached above; `data` deliberately empty
            // -- the fee amount itself is public via list_upcoming_events, but this rejection does
            // not need to repeat it.
            audit(principal = principal, toolName = name, outcome = McpToolCallOutcome.ERROR, startedAt = startedAt)
            McpToolCallResult.ToolError(code = "event_requires_payment", data = buildJsonObject {})
        } catch (e: McpResultTooLargeException) {
            audit(principal = principal, toolName = name, outcome = McpToolCallOutcome.ERROR, startedAt = startedAt)
            McpToolCallResult.InternalError(reason = "Result too large")
        } catch (e: Exception) {
            // MINOR-6 (Welle V1.8.2b): only the tool NAME -- never arguments, content, member id, or
            // token id -- see class KDoc's Least-Privilege/logging posture. The stacktrace itself is
            // fine to log (it is our own code, not caller-supplied data).
            logger.error(e) { "MCP tool '$name' failed with an unexpected exception" }
            audit(principal = principal, toolName = name, outcome = McpToolCallOutcome.ERROR, startedAt = startedAt)
            McpToolCallResult.InternalError(reason = "Internal error")
        }
    }

    private suspend fun executeTool(
        name: String,
        arguments: JsonObject?,
        principal: McpPrincipal,
    ): JsonElement =
        when (name) {
            "get_my_contribution_status" -> GetMyContributionStatusTool.execute(principal = principal)
            "get_my_ltr_balance" -> GetMyLtrBalanceTool.execute(principal = principal, arguments = arguments)
            "search_statute" -> searchStatuteTool.execute(principal = principal, arguments = arguments)
            "list_upcoming_events" -> ListUpcomingEventsTool.execute(principal = principal, arguments = arguments)
            "get_my_ballots" -> GetMyBallotsTool.execute(principal = principal, arguments = arguments)
            "register_for_event" -> registerForEventTool.execute(principal = principal, arguments = arguments)
            "create_post_draft" -> CreatePostDraftTool.execute(principal = principal, arguments = arguments)
            else -> throw IllegalStateException("Unreachable -- tool name '$name' was already validated against McpToolCatalog")
        }

    private fun audit(
        principal: McpPrincipal,
        toolName: String,
        outcome: McpToolCallOutcome,
        startedAt: kotlin.time.Instant,
    ) {
        val durationMs = (Clock.System.now() - startedAt).inWholeMilliseconds.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        McpToolCallAuditRecorder.record(
            entry =
                McpToolCallAuditEntry(
                    memberId = principal.memberId,
                    tokenId = principal.tokenId,
                    toolName = toolName,
                    outcome = outcome,
                    durationMs = durationMs,
                ),
        )
    }
}
