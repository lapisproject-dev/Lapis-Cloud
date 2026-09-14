package network.lapis.cloud.server.payment.fints

import kotlinx.datetime.LocalDate

/**
 * Test-only stand-in for [FinTsStatementFetcher] -- a programmable queue of [FinTsFetchResult]s,
 * one consumed per [fetch] call, plus a call log (including [from]/[to], so tests can assert the
 * poller's fetch-window advances correctly). Never touches hbci4j or a network. Default (no
 * results queued) is [FinTsFetchResult.Failed] with [FinTsErrorCode.BANK_UNAVAILABLE].
 */
internal class FakeFinTsStatementFetcher(
    results: List<FinTsFetchResult> = emptyList(),
) : FinTsStatementFetcher {
    private val queue = results.toMutableList()

    data class Call(
        val credentials: FinTsCredentials,
        val from: LocalDate,
        val to: LocalDate,
    )

    val calls = mutableListOf<Call>()

    override fun fetch(
        credentials: FinTsCredentials,
        from: LocalDate,
        to: LocalDate,
    ): FinTsFetchResult {
        calls += Call(credentials = credentials, from = from, to = to)
        return if (queue.isNotEmpty()) queue.removeAt(0) else FinTsFetchResult.Failed(FinTsErrorCode.BANK_UNAVAILABLE)
    }
}
