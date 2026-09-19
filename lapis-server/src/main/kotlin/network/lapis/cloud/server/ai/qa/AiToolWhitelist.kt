package network.lapis.cloud.server.ai.qa

/** The only tool the AI layer knows: a read-only knowledge search. */
internal enum class AiTool {
    KNOWLEDGE_SEARCH,
}

/**
 * Hard-coded tool whitelist (R4): exactly one entry, guarded by `AiModuleBoundaryTest`. Note that
 * the model is never even handed a tool definition -- retrieval is called by our own code -- so
 * this whitelist documents and pins the surface rather than dispatching anything. Adding a second
 * entry is a conscious, reviewed change (and must extend the boundary test).
 */
internal object AiToolWhitelist {
    val ENTRIES: Set<AiTool> = setOf(AiTool.KNOWLEDGE_SEARCH)

    fun isAllowed(tool: AiTool): Boolean = tool in ENTRIES
}

/**
 * Per-question cap on tool invocations. [max] is `min(config.maxToolCalls, HARD_MAX_TOOL_CALLS)`;
 * exceeding it throws, which the pipeline never catches -- a bug that loops on retrieval fails
 * loudly instead of silently amplifying cost.
 */
internal class ToolCallBudget(
    private val max: Int,
) {
    var used: Int = 0
        private set

    fun consume(tool: AiTool) {
        check(AiToolWhitelist.isAllowed(tool)) { "Tool is not whitelisted" }
        check(used < max) { "Tool-call budget exhausted" }
        used++
    }
}
