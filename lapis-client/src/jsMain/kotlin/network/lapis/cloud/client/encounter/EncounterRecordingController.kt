package network.lapis.cloud.client.encounter

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import network.lapis.cloud.client.AppScope
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.shared.domain.EncounterRecordingSignal
import network.lapis.cloud.shared.domain.PulpitRecordingStateDto
import network.lapis.cloud.shared.domain.PulpitRecordingTextsDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IPulpitRecordingService
import network.lapis.cloud.shared.rpc.PulpitRecordingAwaitingConfirmationException
import network.lapis.cloud.shared.rpc.PulpitRecordingFloorActiveException
import network.lapis.cloud.shared.rpc.PulpitRecordingLimitReachedException
import network.lapis.cloud.shared.rpc.PulpitRecordingUnavailableException

/** What the controller needs from the room (a seam for tests). */
internal interface EncounterRecordingHost {
    /** A short, fixed sentence for the person who pressed the button. */
    fun notify(message: String)

    /** The dialog lives at body level: the room leaves the full screen before it opens one. */
    fun leaveFullscreen()

    /** The state changed: the owner redraws the toggle (label, `aria-pressed`, disabled). */
    fun changed()

    fun isDisposed(): Boolean
}

/**
 * V1.9.143 -- the client side of the pulpit recording: which state the room shows, and the three things a person can do about it (start/stop as
 * PULPIT/STEWARD, agree or decline as PULPIT). It owns NO truth: [active] follows the server's packets and [PulpitRecordingStateDto]; the server
 * decides everything, and "off" is only ever shown after the server said so (a lost packet leaves the banner ON rather than off).
 *
 * The text of a caught exception is never read (Kilua transmits only the type); each distinguishable refusal is one fixed sentence of
 * [EncounterTerms]. Calls run one at a time ([busy]).
 */
internal class EncounterRecordingController(
    private val spaceId: String,
    private val terms: EncounterTerms,
    /** PULPIT or STEWARD: may start and stop. */
    val officer: Boolean,
    /** PULPIT only: may agree to being recorded. */
    private val pulpit: Boolean,
    private val banner: EncounterRecordingBanner,
    private val host: EncounterRecordingHost,
    private val service: () -> IPulpitRecordingService = { rpcService() },
    /** How often a waiting officer asks the server whether the request is still open -- a seam for tests. */
    private val awaitingPollMs: Long = AWAITING_POLL_MS,
    /** How long a waiting officer keeps asking before the lock is lifted regardless -- a seam for tests. */
    private val awaitingMaxMs: Long = AWAITING_MAX_MS,
    private val showConsent: (EncounterTerms, PulpitRecordingTextsDto, (PulpitRecordingTextsDto) -> Unit, () -> Unit) -> Unit =
        { t, texts, accept, decline -> showPulpitRecordingConsent(t, texts, accept, decline) },
) {
    var active: Boolean = false
        private set
    var awaiting: Boolean = false
        private set
    var busy: Boolean = false
        private set
    var maxReached: Boolean = false
        private set
    private var consentOpen = false
    private var awaitingWatch: Job? = null

    /** Whether the server said a recording can be started right now (only meaningful for an officer). */
    var startable: Boolean = false
        private set

    /** The start state the room already knows (the space DTO of the entry), until the first answer of the server. */
    fun seed(startable: Boolean) {
        this.startable = startable
    }

    /** The room was entered while a recording runs (the server's entry answer): the banner shows from the first moment, a later packet corrects it. */
    fun seedActive(active: Boolean) {
        if (active) setActive(true)
    }

    /** A packet of the server. [EncounterRecordingSignal.ask] opens the consent dialog for the pulpit. */
    fun onSignal(signal: EncounterRecordingSignal) {
        setActive(signal.active)
        if (signal.ask && pulpit) openConsent()
    }

    /** Asks the server for the state (an officer only; a listener learns it from the packets). */
    fun refresh() {
        if (!officer || host.isDisposed()) return
        AppScope.launch {
            try {
                apply(service().pulpitRecordingState(spaceId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Refused or unreachable: the control stays as it is; the packets still drive the banner.
            }
        }
    }

    fun toggle() {
        if (!officer || busy || host.isDisposed()) return
        val stopping = active
        busy = true
        host.changed()
        AppScope.launch {
            try {
                val dto = if (stopping) service().stopPulpitRecording(spaceId) else service().startPulpitRecording(spaceId)
                apply(dto)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: PulpitRecordingAwaitingConfirmationException) {
                awaiting = true
                host.notify(terms.recordingAskedNote())
                watchAwaiting()
            } catch (e: PulpitRecordingFloorActiveException) {
                host.notify(terms.recordingFloorNote())
            } catch (e: PulpitRecordingUnavailableException) {
                host.notify(terms.recordingUnavailableNote())
            } catch (e: PulpitRecordingLimitReachedException) {
                host.notify(terms.recordingMaxReached())
            } catch (e: ConflictException) {
                host.notify(terms.recordingNobodyThereNote())
            } catch (_: Throwable) {
                host.notify(terms.recordingFailedNote())
                refresh()
            } finally {
                busy = false
                if (!host.isDisposed()) host.changed()
            }
        }
    }

    /**
     * While the start waits for the pulpit, the server sends the officer nothing when the request ends (the pulpit declines, or it expires after two
     * minutes): the officer asks now and then, so the button is never locked for good. The server's state is the truth; after the longest validity
     * of a request the lock is lifted regardless (the server refuses a start that cannot be made, with its own fixed sentence).
     */
    private fun watchAwaiting() {
        if (awaitingWatch?.isActive == true) return
        awaitingWatch =
            AppScope.launch {
                var waited = 0L
                while (awaiting && !host.isDisposed() && waited < awaitingMaxMs) {
                    delay(awaitingPollMs)
                    waited += awaitingPollMs
                    if (!awaiting || host.isDisposed()) return@launch
                    try {
                        apply(service().pulpitRecordingState(spaceId))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        // Unreachable or refused: try again until the request must have run out.
                    }
                }
                if (awaiting && !host.isDisposed()) {
                    awaiting = false
                    host.changed()
                }
            }
    }

    private fun apply(dto: PulpitRecordingStateDto) {
        if (host.isDisposed()) return
        val maxWasReached = maxReached
        startable = dto.startable
        awaiting = dto.pendingConfirmation
        maxReached = dto.maxReached
        setActive(dto.active)
        if (maxReached && !maxWasReached) host.notify(terms.recordingMaxReached())
        host.changed()
    }

    private fun setActive(value: Boolean) {
        if (host.isDisposed()) return
        if (value) awaiting = false
        active = value
        banner.render(value)
        host.changed()
    }

    // ── the pulpit's answer ─────────────────────────────────────────────

    private fun openConsent() {
        if (consentOpen || host.isDisposed()) return
        consentOpen = true
        AppScope.launch {
            val texts =
                try {
                    service().pulpitRecordingTexts(spaceId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    consentOpen = false
                    return@launch
                }
            if (host.isDisposed()) {
                consentOpen = false
                return@launch
            }
            host.leaveFullscreen()
            showConsent(terms, texts, { accepted -> answer(accepted) }, { decline() })
        }
    }

    private fun answer(texts: PulpitRecordingTextsDto) {
        AppScope.launch {
            try {
                apply(service().confirmPulpitRecording(spaceId, texts.pulpitVersionLabel, texts.pulpitSha256))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                host.notify(terms.recordingFailedNote())
            } finally {
                consentOpen = false
            }
        }
    }

    private fun decline() {
        AppScope.launch {
            try {
                service().declinePulpitRecording(spaceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Declining is never an error the person has to deal with.
            } finally {
                consentOpen = false
            }
        }
    }
}

/** How often a waiting officer asks the server whether the start request is still open. */
internal const val AWAITING_POLL_MS = 3_000L

/** A start request is valid for two minutes on the server; the officer's lock never outlives that by much. */
internal const val AWAITING_MAX_MS = 130_000L
