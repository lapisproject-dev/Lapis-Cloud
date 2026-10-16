package network.lapis.cloud.client.encounter

import io.kvision.panel.SimplePanel
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.formTest
import network.lapis.cloud.shared.domain.EncounterProfile
import network.lapis.cloud.shared.domain.EncounterRecordingSignal
import network.lapis.cloud.shared.domain.PulpitRecordingDocumentDto
import network.lapis.cloud.shared.domain.PulpitRecordingStateDto
import network.lapis.cloud.shared.domain.PulpitRecordingTextsDto
import network.lapis.cloud.shared.rpc.ConflictException
import network.lapis.cloud.shared.rpc.IPulpitRecordingService
import network.lapis.cloud.shared.rpc.PulpitRecordingAwaitingConfirmationException
import network.lapis.cloud.shared.rpc.PulpitRecordingFloorActiveException
import network.lapis.cloud.shared.rpc.PulpitRecordingLimitReachedException
import network.lapis.cloud.shared.rpc.PulpitRecordingUnavailableException
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.143 -- the client logic of the pulpit recording without a room: what the banner shows (from the server's packets and answers only), who is asked,
 * what is sent when the pulpit agrees or declines, and the one fixed sentence of every refused call.
 */
class EncounterRecordingControllerTest {
    private val terms = termsFor(EncounterProfile.CHURCH_SERVICE)
    private val texts =
        PulpitRecordingTextsDto(
            pulpitVersionLabel = "2026-10-16.rec-pulpit.v1",
            pulpitSha256 = "a".repeat(64),
            pulpitText = "Ihre Stimme wird aufgezeichnet.",
            roomText = "Die Kanzel wird aufgezeichnet.",
        )

    private class FakeService(
        var startAnswer: () -> PulpitRecordingStateDto,
        var texts: PulpitRecordingTextsDto,
    ) : IPulpitRecordingService {
        val calls = mutableListOf<String>()

        /** What a read of the state answers; by default nothing is pending and nothing runs. */
        var stateAnswer: () -> PulpitRecordingStateDto = {
            PulpitRecordingStateDto(
                active = false,
                startable = true,
                confirmationRequired = true,
                confirmedByMe = false,
                pendingConfirmation = false,
                maxReached = false,
            )
        }

        override suspend fun pulpitRecordingState(spaceId: String): PulpitRecordingStateDto {
            calls += "state"
            return stateAnswer()
        }

        override suspend fun pulpitRecordingTexts(spaceId: String): PulpitRecordingTextsDto {
            calls += "texts"
            return texts
        }

        override suspend fun startPulpitRecording(spaceId: String): PulpitRecordingStateDto {
            calls += "start"
            return startAnswer()
        }

        override suspend fun stopPulpitRecording(spaceId: String): PulpitRecordingStateDto {
            calls += "stop"
            return PulpitRecordingStateDto(
                active = false,
                startable = true,
                confirmationRequired = true,
                confirmedByMe = false,
                pendingConfirmation = false,
                maxReached = false,
            )
        }

        override suspend fun confirmPulpitRecording(
            spaceId: String,
            versionLabel: String,
            sha256: String,
        ): PulpitRecordingStateDto {
            calls += "confirm:$versionLabel:$sha256"
            return PulpitRecordingStateDto(
                active = true,
                startable = false,
                confirmationRequired = true,
                confirmedByMe = true,
                pendingConfirmation = false,
                maxReached = false,
            )
        }

        override suspend fun declinePulpitRecording(spaceId: String) {
            calls += "decline"
        }

        override suspend fun listPulpitRecordings(spaceId: String?): List<PulpitRecordingDocumentDto> = emptyList()

        override suspend fun deletePulpitRecording(documentId: String): Boolean = true
    }

    private class Rig(
        val controller: EncounterRecordingController,
        val service: FakeService,
        val banner: EncounterRecordingBanner,
        val notes: MutableList<String>,
        val announced: MutableList<String>,
        val consents: MutableList<Pair<(PulpitRecordingTextsDto) -> Unit, () -> Unit>>,
        val changes: IntArray,
    )

    private fun rig(
        officer: Boolean = true,
        pulpit: Boolean = true,
        startAnswer: () -> PulpitRecordingStateDto = { active() },
        awaitingPollMs: Long = AWAITING_POLL_MS,
        awaitingMaxMs: Long = AWAITING_MAX_MS,
    ): Rig {
        val notes = mutableListOf<String>()
        val announced = mutableListOf<String>()
        val consents = mutableListOf<Pair<(PulpitRecordingTextsDto) -> Unit, () -> Unit>>()
        val changes = intArrayOf(0)
        val service = FakeService(startAnswer, texts)
        val banner = EncounterRecordingBanner(host = SimplePanel(), terms = terms, roomNotice = null, announce = { announced += it })
        val controller =
            EncounterRecordingController(
                spaceId = "space-1",
                terms = terms,
                officer = officer,
                pulpit = pulpit,
                banner = banner,
                host =
                    object : EncounterRecordingHost {
                        override fun notify(message: String) {
                            notes += message
                        }

                        override fun leaveFullscreen() = Unit

                        override fun changed() {
                            changes[0]++
                        }

                        override fun isDisposed(): Boolean = false
                    },
                service = { service },
                awaitingPollMs = awaitingPollMs,
                awaitingMaxMs = awaitingMaxMs,
                showConsent = { _, _, accept, decline -> consents += accept to decline },
            )
        return Rig(controller, service, banner, notes, announced, consents, changes)
    }

    private fun active() =
        PulpitRecordingStateDto(
            active = true,
            startable = false,
            confirmationRequired = false,
            confirmedByMe = false,
            pendingConfirmation = false,
            maxReached = false,
        )

    @Test
    fun aPacketSwitchesTheBannerAndIsAnnouncedOnlyWhenTheStateChanges() {
        val r = rig(officer = false, pulpit = false)
        assertFalse(r.banner.visible)
        r.controller.onSignal(EncounterRecordingSignal(active = true))
        assertTrue(r.banner.visible)
        assertTrue(r.controller.active)
        r.controller.onSignal(EncounterRecordingSignal(active = true))
        r.controller.onSignal(EncounterRecordingSignal(active = false))
        assertFalse(r.banner.visible)
        assertEquals(listOf("Aufzeichnung gestartet", "Aufzeichnung beendet"), r.announced)
    }

    @Test
    fun onlyThePulpitIsAskedToConfirm_andOnlyOnce(): Promise<Unit> =
        formTest {
            val steward = rig(pulpit = false)
            steward.controller.onSignal(EncounterRecordingSignal(active = false, ask = true))
            assertEquals(emptyList(), steward.service.calls, "a steward is never asked")
            val pulpit = rig()
            pulpit.controller.onSignal(EncounterRecordingSignal(active = false, ask = true))
            pulpit.controller.onSignal(EncounterRecordingSignal(active = false, ask = true))
            awaitUntil("the dialog is requested") { pulpit.consents.isNotEmpty() }
            assertEquals(1, pulpit.consents.size)
            assertEquals(listOf("texts"), pulpit.service.calls)
        }

    @Test
    fun agreeingSendsTheLabelAndHashExactlyAsTheServerDelivered(): Promise<Unit> =
        formTest {
            val r = rig()
            r.controller.onSignal(EncounterRecordingSignal(active = false, ask = true))
            awaitUntil("the dialog is requested") { r.consents.isNotEmpty() }
            r.consents.single().first(texts)
            awaitUntil("the answer is applied") { r.controller.active }
            assertTrue("confirm:${texts.pulpitVersionLabel}:${texts.pulpitSha256}" in r.service.calls)
            assertTrue(r.banner.visible)
        }

    @Test
    fun decliningTellsTheServerAndChangesNothingElse(): Promise<Unit> =
        formTest {
            val r = rig()
            r.controller.onSignal(EncounterRecordingSignal(active = false, ask = true))
            awaitUntil("the dialog is requested") { r.consents.isNotEmpty() }
            r.consents.single().second()
            awaitUntil("the decline is sent") { "decline" in r.service.calls }
            assertFalse(r.controller.active)
            assertFalse(r.banner.visible)
            assertEquals(emptyList(), r.notes)
        }

    @Test
    fun startingShowsTheServersAnswer_andARefusalIsOneFixedSentence(): Promise<Unit> =
        formTest {
            val ok = rig()
            ok.controller.toggle()
            awaitUntil("started") { ok.controller.active }
            assertTrue(ok.banner.visible)
            val refusals =
                listOf(
                    PulpitRecordingAwaitingConfirmationException() to terms.recordingAskedNote(),
                    PulpitRecordingFloorActiveException() to terms.recordingFloorNote(),
                    PulpitRecordingUnavailableException() to terms.recordingUnavailableNote(),
                    PulpitRecordingLimitReachedException() to terms.recordingMaxReached(),
                    ConflictException("x") to terms.recordingNobodyThereNote(),
                    IllegalStateException("anything else") to terms.recordingFailedNote(),
                )
            refusals.forEach { (error, sentence) ->
                val r = rig(startAnswer = { throw error })
                r.controller.toggle()
                awaitUntil("the refusal is shown: $sentence") { r.notes.contains(sentence) }
                assertFalse(r.controller.active, "a refused start is not a recording")
                assertFalse(r.banner.visible)
                assertEquals(listOf(sentence), r.notes.distinct().take(1))
            }
        }

    @Test
    fun aWaitingStartIsRemembered_andARunningRecordingClearsIt(): Promise<Unit> =
        formTest {
            val r = rig(startAnswer = { throw PulpitRecordingAwaitingConfirmationException() })
            r.controller.toggle()
            awaitUntil("waiting") { r.controller.awaiting }
            r.controller.onSignal(EncounterRecordingSignal(active = true))
            assertFalse(r.controller.awaiting)
            assertTrue(r.controller.active)
        }

    private fun pending(waiting: Boolean) =
        PulpitRecordingStateDto(
            active = false,
            startable = true,
            confirmationRequired = true,
            confirmedByMe = false,
            pendingConfirmation = waiting,
            maxReached = false,
        )

    @Test
    fun aDeclinedOrExpiredRequestUnlocksTheStartAgain(): Promise<Unit> =
        formTest {
            // The server sends the officer no packet when the pulpit says no (or the two minutes run out): only the state read tells.
            val r = rig(startAnswer = { throw PulpitRecordingAwaitingConfirmationException() }, awaitingPollMs = 20)
            var stillOpen = true
            r.service.stateAnswer = { pending(waiting = stillOpen) }
            r.controller.toggle()
            awaitUntil("waiting") { r.controller.awaiting }
            awaitUntil("the officer asked the server") { "state" in r.service.calls }
            assertTrue(r.controller.awaiting, "an open request keeps the lock")
            stillOpen = false
            awaitUntil("the lock is lifted") { !r.controller.awaiting }
            assertFalse(r.controller.active)
            r.controller.toggle()
            awaitUntil("a new start is possible") { r.service.calls.count { it == "start" } == 2 }
        }

    @Test
    fun aRequestThatNeverAnswersDoesNotLockForever(): Promise<Unit> =
        formTest {
            // An unreachable server: the lock is lifted after the longest validity of a request.
            val r = rig(startAnswer = { throw PulpitRecordingAwaitingConfirmationException() }, awaitingPollMs = 20, awaitingMaxMs = 100)
            r.service.stateAnswer = { throw IllegalStateException("unreachable") }
            r.controller.toggle()
            awaitUntil("waiting") { r.controller.awaiting }
            awaitUntil("the lock is lifted after the validity") { !r.controller.awaiting }
        }

    @Test
    fun enteringARunningRecordingShowsTheBannerAtOnce() {
        val r = rig(officer = false, pulpit = false)
        r.controller.seedActive(false)
        assertFalse(r.banner.visible)
        r.controller.seedActive(true)
        assertTrue(r.banner.visible)
        r.controller.onSignal(EncounterRecordingSignal(active = false))
        assertFalse(r.banner.visible, "a later packet corrects the seed")
    }

    @Test
    fun aListenerNeverStartsAnything(): Promise<Unit> =
        formTest {
            val r = rig(officer = false, pulpit = false)
            r.controller.toggle()
            r.controller.refresh()
            kotlinx.coroutines.delay(30)
            assertEquals(emptyList(), r.service.calls)
        }
}
