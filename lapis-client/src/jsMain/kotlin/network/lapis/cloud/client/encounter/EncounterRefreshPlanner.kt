package network.lapis.cloud.client.encounter

/**
 * V1.9.62 Begegnungsraum (B2) -- spaces out refreshes of the presence list. The server's `list` budget (60 per minute and member) is shared
 * by `listSpaces`, `getSpace`, `getEntryInfo` and `listPresent`; twenty people entering within seconds must not each fire a refresh
 * storm. Every roster event only ASKS ([request]); at most one refresh is scheduled at a time and two runs are at least [minGapMs] apart.
 * Pure and clock-injected, DOM-free.
 */
internal class EncounterRefreshPlanner(
    private val now: () -> Double,
    private val minGapMs: Double = 5_000.0,
) {
    private var lastRun = Double.NEGATIVE_INFINITY
    private var scheduled = false

    /** Milliseconds to wait before the refresh may run, or `null` when one is already scheduled (the request is merged into it). */
    fun request(): Double? {
        if (scheduled) return null
        scheduled = true
        return maxOf(0.0, lastRun + minGapMs - now())
    }

    /** Call when the scheduled refresh actually starts. */
    fun started() {
        scheduled = false
        lastRun = now()
    }
}
