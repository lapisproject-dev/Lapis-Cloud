package network.lapis.cloud.client

/** V1.9.114 -- a filter chain in the hands of a DOM test: records every call, answers `attach` as told. No audio, no worklet. */
internal class FakeNoiseSuppressionChain : NoiseSuppressionChain {
    var attachResult: NoiseSuppressionUnavailable? = null
    var attachCalls = 0
    var detachCalls = 0
    var prepareCalls = 0
    val browserNoiseSuppression = mutableListOf<Boolean>()

    override fun prepare() {
        prepareCalls++
    }

    override suspend fun attach(): NoiseSuppressionUnavailable? {
        attachCalls++
        return attachResult
    }

    override suspend fun detach() {
        detachCalls++
    }

    override suspend fun setBrowserNoiseSuppression(enabled: Boolean) {
        browserNoiseSuppression += enabled
    }
}

/** An in-memory remembered choice. */
internal class FakeNoiseSuppressionPreference(
    var stored: Boolean = false,
) : NoiseSuppressionPreference {
    val writes = mutableListOf<Boolean>()

    override fun read(): Boolean = stored

    override fun write(on: Boolean) {
        writes += on
        stored = on
    }
}
