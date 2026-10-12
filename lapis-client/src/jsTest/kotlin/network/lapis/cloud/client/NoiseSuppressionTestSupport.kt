package network.lapis.cloud.client

/** V1.9.114 -- a filter chain in the hands of a DOM test: records every call, answers `attach` as told. No audio, no worklet. */
internal class FakeNoiseSuppressionChain : NoiseSuppressionChain {
    var attachResult: NoiseSuppressionUnavailable? = null

    /** V1.9.119: a failure with details (wins over [attachResult]); one entry is used per `attach` call, the last one stays. */
    var attachFailures: List<NoiseSuppressionFailure?>? = null
    var attachBehavior: suspend () -> Unit = {}
    var runtimeListener: ((NoiseSuppressionFailure) -> Unit)? = null
    val diagnosticsTriggers = mutableListOf<String>()
    var diagnosticsAnswer: NoiseSuppressionDiagnostics? = null
    var attachCalls = 0
    var detachCalls = 0
    var prepareCalls = 0
    val browserNoiseSuppression = mutableListOf<Boolean>()

    override fun prepare() {
        prepareCalls++
    }

    override suspend fun attach(): NoiseSuppressionFailure? {
        attachCalls++
        attachBehavior()
        val scripted = attachFailures
        if (scripted != null) return scripted[minOf(attachCalls - 1, scripted.lastIndex)]
        return attachResult?.let { NoiseSuppressionFailure(it) }
    }

    override fun diagnostics(trigger: String): NoiseSuppressionDiagnostics {
        diagnosticsTriggers += trigger
        return diagnosticsAnswer?.copy(trigger = trigger) ?: NoiseSuppressionDiagnostics(trigger)
    }

    override fun setRuntimeFailureListener(listener: ((NoiseSuppressionFailure) -> Unit)?) {
        runtimeListener = listener
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

/** V1.9.119 -- collects the console lines. */
internal class RecordingNoiseSuppressionLogger : NoiseSuppressionLogger {
    val lines = mutableListOf<String>()

    override fun warn(line: String) {
        lines += line
    }
}

/** V1.9.119 -- a "next gesture" hook the test fires by hand; counts registrations and removals. */
internal class FakeGestureHook(
    private val onRegister: () -> Unit = {},
) : GestureHook {
    var registrations = 0
    var removals = 0
    private var pending: (() -> Unit)? = null

    val registered: Boolean get() = pending != null

    override fun registerOnce(onGesture: () -> Unit): () -> Unit {
        registrations++
        pending = onGesture
        onRegister()
        return {
            if (pending != null) {
                pending = null
                removals++
            }
        }
    }

    /** The person's gesture: the hook is gone, the callback runs synchronously (like a real event listener). */
    fun fire() {
        val callback = pending ?: return
        pending = null
        callback()
    }
}
