package network.lapis.cloud.server.payment.fints

/**
 * Test-only stand-in for [FinTsSetupClient] -- a programmable queue of [FinTsSetupOutcome]s, one
 * consumed per [begin]/[submitTan] call, plus a call log so tests can assert exactly what
 * [FinTsCredentials] reached this fake (e.g. that the PIN never leaks anywhere else). Never touches
 * hbci4j or a network. Default (no outcomes queued) is [FinTsSetupOutcome.Failed] with
 * [FinTsErrorCode.BANK_UNAVAILABLE] -- a test that forgets to queue an outcome fails loudly instead
 * of silently "succeeding".
 */
internal class FakeFinTsSetupClient(
    outcomes: List<FinTsSetupOutcome> = emptyList(),
) : FinTsSetupClient {
    private val queue = outcomes.toMutableList()

    val beginCalls = mutableListOf<FinTsCredentials>()
    val submitTanCalls = mutableListOf<Pair<String, String>>()
    val cancelCalls = mutableListOf<String>()

    override fun begin(credentials: FinTsCredentials): FinTsSetupOutcome {
        beginCalls += credentials
        return nextOutcome()
    }

    override fun submitTan(
        handle: String,
        tan: String,
    ): FinTsSetupOutcome {
        submitTanCalls += handle to tan
        return nextOutcome()
    }

    override fun cancel(handle: String) {
        cancelCalls += handle
    }

    private fun nextOutcome(): FinTsSetupOutcome =
        if (queue.isNotEmpty()) queue.removeAt(0) else FinTsSetupOutcome.Failed(FinTsErrorCode.BANK_UNAVAILABLE)
}
