package network.lapis.cloud.server.mcp.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import network.lapis.cloud.server.ai.retrieval.KnowledgeRetriever
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
}

private class McpResultTooLargeException : Exception("Tool result exceeds the configured response size limit")

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
) {
    private val searchStatuteTool = SearchStatuteTool(retriever = retriever)

    suspend fun dispatch(
        name: String,
        arguments: JsonObject?,
        principal: McpPrincipal,
    ): McpToolCallResult {
        val startedAt = Clock.System.now()

        val rateDecision = rateLimiter.checkAndRecord(memberId = principal.memberId, tokenId = principal.tokenId)
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

        if (McpToolCatalog.TOOLS.none { it.name == name }) {
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
        } catch (e: McpResultTooLargeException) {
            audit(principal = principal, toolName = name, outcome = McpToolCallOutcome.ERROR, startedAt = startedAt)
            McpToolCallResult.InternalError(reason = "Result too large")
        } catch (e: Exception) {
            audit(principal = principal, toolName = name, outcome = McpToolCallOutcome.ERROR, startedAt = startedAt)
            McpToolCallResult.InternalError(reason = "Internal error")
        }
    }

    private fun executeTool(
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
