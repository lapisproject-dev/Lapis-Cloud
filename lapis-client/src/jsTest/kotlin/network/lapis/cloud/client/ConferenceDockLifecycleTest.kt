package network.lapis.cloud.client

import io.kvision.panel.VPanel
import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.SessionInfoDto
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A call as the dock sees it: records what the dock asks of it. */
private class FakeDockableSession : DockableSession {
    override val kind = DockSessionKind.CONFERENCE
    var attaches = 0
    var detaches = 0
    var leaves = 0
    val terminations = mutableListOf<DockTerminateReason>()

    override fun attach() {
        attaches++
    }

    override fun detach() {
        detaches++
    }

    override fun leave() {
        leaves++
    }

    override suspend fun terminate(reason: DockTerminateReason) {
        terminations += reason
    }

    override fun toggleMic() = Unit

    override fun toggleCamera() = Unit

    override fun stopScreenShare() = Unit
}

/**
 * V1.9.70 -- the dock as a whole (state, observers, call-presence flag, body class, hard termination), driven with a fake session.
 * The real LiveKit call cannot run in Karma: that "audio and camera really keep running across a route change" is a manual check
 * (see the CHANGELOG).
 */
class ConferenceDockLifecycleTest {
    private val snap =
        DockSnapshot(
            micOn = true,
            cameraOn = true,
            screenSharing = false,
            recording = false,
            streaming = false,
            streamPaused = false,
            voteOpen = false,
            transitioning = null,
        )

    private fun session(
        id: String,
        expiresAt: LocalDateTime = LocalDateTime(2099, 1, 1, 0, 0),
        guest: Boolean = false,
    ) = SessionInfoDto(
        memberId = id,
        displayName = "Testperson $id",
        role = AccountRole.MEMBER,
        expiresAt = expiresAt,
        isGuest = guest,
    )

    @BeforeTest
    fun reset() {
        ConferenceDock.resetForTest()
        ConferenceLobbyPort.resetForTest()
    }

    @AfterTest
    fun cleanup() {
        ConferenceDock.resetForTest()
        ConferenceLobbyPort.resetForTest()
        document.title = ""
    }

    private fun startLiveCall(fake: FakeDockableSession = FakeDockableSession()): FakeDockableSession {
        assertTrue(ConferenceDock.beginJoin("room-1"))
        ConferenceDock.register(fake)
        ConferenceDock.dispatch(DockEvent.Connected(snap))
        return fake
    }

    private fun promiseTest(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    @Test
    fun beginJoin_isTrueOnce_andAnEndStateBlocksAnotherJoin() {
        assertTrue(ConferenceDock.canJoin())
        assertTrue(ConferenceDock.beginJoin("room-1"))
        assertFalse(ConferenceDock.canJoin())
        assertFalse(ConferenceDock.beginJoin("room-2"), "the second click of a double click must not start a second connection")
        assertEquals("room-1", ConferenceDock.roomId)
        assertTrue(ConferenceDock.state is DockState.Joining)
    }

    @Test
    fun detachingTheView_neverTerminates_andAStaleScreenCannotDetachANewerOne() {
        val fake = startLiveCall()
        val oldScreen = Any()
        val newScreen = Any()
        ConferenceDock.attachView(oldScreen, fromBar = false)
        // the route is opened again (sidebar click): the new screen attaches BEFORE the old one's destroy hook runs
        ConferenceDock.attachView(newScreen, fromBar = false)
        ConferenceDock.detachView(oldScreen)
        assertEquals(0, fake.detaches, "a late destroy hook of an older screen must not detach the newer one")
        assertTrue(ConferenceDock.state.isAttached)

        ConferenceDock.detachView(newScreen)
        assertEquals(1, fake.detaches)
        assertTrue(fake.terminations.isEmpty(), "leaving the route never ends the call")
        assertFalse(ConferenceDock.state.isAttached)
        assertTrue(ConferenceDock.state is DockState.Live)
        assertTrue(ConferenceCallPresence.live, "the call is still live")
        assertTrue(fake.attaches >= 1, "attaching a screen reaches the call (idempotent)")
    }

    @Test
    fun theBodyClass_isSetExactlyWhileTheBarIsShown() {
        assertFalse(document.body!!.classList.contains(DOCK_BODY_CLASS))
        startLiveCall()
        val screen = Any()
        ConferenceDock.attachView(screen, fromBar = false)
        assertFalse(document.body!!.classList.contains(DOCK_BODY_CLASS), "attached: no bar")
        ConferenceDock.detachView(screen)
        assertTrue(document.body!!.classList.contains(DOCK_BODY_CLASS), "undocked: bar space reserved")
        ConferenceDock.attachView(screen, fromBar = true)
        assertFalse(document.body!!.classList.contains(DOCK_BODY_CLASS))
        ConferenceDock.detachView(screen)
        ConferenceDock.dispatch(DockEvent.Terminated(DockTerminateReason.LEAVE))
        assertFalse(document.body!!.classList.contains(DOCK_BODY_CLASS), "idle: no bar")
    }

    @Test
    fun theCallPresenceFlag_followsTheDock() {
        assertFalse(ConferenceCallPresence.live)
        ConferenceDock.beginJoin("room-1")
        assertTrue(ConferenceCallPresence.live, "joining counts as a live call")
        ConferenceDock.dispatch(DockEvent.Connected(snap))
        ConferenceDock.dispatch(DockEvent.DisconnectResolving)
        assertTrue(ConferenceCallPresence.live, "resolving a hand-over counts as live")
        ConferenceDock.dispatch(DockEvent.Stopped(DockStopReason.ENDED))
        assertFalse(ConferenceCallPresence.live, "an end state is not a live call")
    }

    @Test
    fun terminate_callsTheSessionOnce_andLeavesNothingBehind(): Promise<Unit> =
        promiseTest {
            val fake = startLiveCall()
            ConferenceDock.callPanel.add(io.kvision.html.Div("Chatzeile, Teilnehmer, Quittung"))
            assertTrue(ConferenceDock.callPanel.getChildren().isNotEmpty())
            val generation = ConferenceDock.generation
            val intent = ConferenceDock.lastDeviceIntent
            assertNotNull(intent)

            ConferenceDock.terminate(DockTerminateReason.LOGOUT)
            ConferenceDock.terminate(DockTerminateReason.LOGOUT) // idempotent

            assertEquals(listOf(DockTerminateReason.LOGOUT), fake.terminations)
            assertEquals(DockState.Idle, ConferenceDock.state)
            assertNull(ConferenceDock.activeSession)
            assertNull(ConferenceDock.session)
            assertNull(ConferenceDock.roomId)
            assertNull(ConferenceDock.lastDeviceIntent)
            assertTrue(ConferenceDock.callPanel.getChildren().isEmpty(), "no chat, roster or receipt DOM may survive")
            assertFalse(ConferenceCallPresence.live)
            assertFalse(ConferenceDock.isCurrent(generation), "an in-flight join of the ended call must not switch a device on")
            assertFalse(document.body!!.classList.contains(DOCK_BODY_CLASS))
        }

    @Test
    fun aSignOut_endsTheCall_aNewIdentityToo_aRefreshOfTheSameIdentityDoesNot(): Promise<Unit> =
        promiseTest {
            val a = session("a")

            val signOut = startLiveCall()
            ConferenceDock.onAuthSessionChanged(a, null)
            awaitUntil("sign-out ends the call") { ConferenceDock.state is DockState.Idle }
            assertEquals(listOf(DockTerminateReason.LOGOUT), signOut.terminations)

            val switched = startLiveCall()
            ConferenceDock.onAuthSessionChanged(a, session("b"))
            awaitUntil("an account switch ends the call") { ConferenceDock.state is DockState.Idle }
            assertEquals(listOf(DockTerminateReason.ACCOUNT_SWITCH), switched.terminations)

            val refreshed = startLiveCall()
            ConferenceDock.onAuthSessionChanged(a, session("a", expiresAt = LocalDateTime(2099, 6, 1, 0, 0)))
            assertTrue(ConferenceDock.state is DockState.Live, "a session refresh (new expiry) keeps the call")
            assertTrue(refreshed.terminations.isEmpty())
            // the same member as a federation guest is another identity
            ConferenceDock.onAuthSessionChanged(a, session("a", guest = true))
            awaitUntil("guest vs member is another identity") { ConferenceDock.state is DockState.Idle }
        }

    @Test
    fun aNewRootHost_endsARunningCall_andAnIdleDockAdoptsTheHost(): Promise<Unit> =
        promiseTest {
            val idleHost = VPanel()
            ConferenceDock.bindHost(idleHost)
            assertTrue(idleHost.getChildren().contains(ConferenceDock.callPanel))

            val fake = startLiveCall()
            ConferenceDock.bindHost(VPanel())
            awaitUntil("a root restart ends the call") { ConferenceDock.state is DockState.Idle }
            assertEquals(listOf(DockTerminateReason.ROOT_RESTART), fake.terminations)
        }

    @Test
    fun theHost_isShownOnlyWhileAttachedAndNotIdle() {
        val host = VPanel()
        ConferenceDock.bindHost(host)
        assertTrue(host.hasCssClass(DOCK_HIDDEN_CLASS), "idle: hidden")
        startLiveCall()
        val screen = Any()
        ConferenceDock.attachView(screen, fromBar = false)
        assertFalse(host.hasCssClass(DOCK_HIDDEN_CLASS), "attached: the view is shown")
        ConferenceDock.detachView(screen)
        assertTrue(host.hasCssClass(DOCK_HIDDEN_CLASS), "undocked: hidden, not torn down")
    }

    @Test
    fun theTitleMarker_isSetWhileRecordingOrStreaming_andRemovedWhenIdle() {
        assertEquals("Seite", ConferenceDock.decorateTitle("Seite"))
        startLiveCall()
        assertEquals("Seite", ConferenceDock.decorateTitle("Seite"))
        ConferenceDock.publish(snap.copy(recording = true))
        assertEquals("● Seite", ConferenceDock.decorateTitle("Seite"))
        ConferenceDock.publish(snap.copy(recording = false, streaming = true))
        assertEquals("● Seite", ConferenceDock.decorateTitle("Seite"))
        document.title = "● Seite"
        ConferenceDock.dispatch(DockEvent.Terminated(DockTerminateReason.LEAVE))
        assertEquals("Seite", document.title, "the marker does not outlive the call")
    }

    @Test
    fun theDeviceWish_followsTheLiveSnapshot_notTheResolvingOne() {
        startLiveCall()
        assertEquals(ConferenceDeviceIntent(mic = true, camera = true), ConferenceDock.lastDeviceIntent)
        ConferenceDock.publish(snap.copy(micOn = true, cameraOn = false))
        assertEquals(ConferenceDeviceIntent(mic = true, camera = false), ConferenceDock.lastDeviceIntent)
        ConferenceDock.dispatch(DockEvent.DisconnectResolving)
        ConferenceDock.publish(snap.copy(micOn = false, cameraOn = false))
        assertEquals(
            ConferenceDeviceIntent(mic = true, camera = false),
            ConferenceDock.lastDeviceIntent,
            "what the stopping tracks report during a hand-over is not a wish of the person",
        )
    }

    @Test
    fun theLobbyPort_isClearedOnlyByItsOwner() {
        val first = Any()
        val second = Any()
        var shown = 0
        ConferenceLobbyPort.set(first) { shown++ }
        ConferenceLobbyPort.set(second) { shown += 10 }
        ConferenceLobbyPort.clear(first)
        ConferenceLobbyPort.current?.invoke()
        assertEquals(10, shown, "a late destroy hook of the older screen must not clear the newer screen's port")
        ConferenceLobbyPort.clear(second)
        assertNull(ConferenceLobbyPort.current)
    }

    @Test
    fun anEndStateNobodyLookedAt_isDismissedWhenTheRouteOpens_theOtherEndStatesStay() {
        startLiveCall()
        ConferenceDock.dispatch(DockEvent.Stopped(DockStopReason.ENDED))
        ConferenceDock.prepareForRoute()
        assertEquals(DockState.Idle, ConferenceDock.state)

        startLiveCall()
        ConferenceDock.dispatch(DockEvent.Stopped(DockStopReason.DUPLICATE_IDENTITY))
        ConferenceDock.prepareForRoute()
        assertTrue(ConferenceDock.state is DockState.Stopped, "the displaced card still offers 'continue here'")
    }

    @Test
    fun dockIdentityKey_distinguishesMemberGuestAndHomeserver_andIsNullWithoutASession() {
        assertNull(dockIdentityKey(null))
        assertEquals(dockIdentityKey(session("a")), dockIdentityKey(session("a", expiresAt = LocalDateTime(2050, 1, 1, 0, 0))))
        assertTrue(dockIdentityKey(session("a")) != dockIdentityKey(session("b")))
        assertTrue(dockIdentityKey(session("a")) != dockIdentityKey(session("a", guest = true)))
    }

    @Test
    fun leaveFromBar_beforeTheSessionRegisters_isAppliedOnRegister() {
        assertTrue(ConferenceDock.beginJoin("room-1"))
        ConferenceDock.leaveFromBar()
        val fake = FakeDockableSession()
        ConferenceDock.register(fake)
        assertEquals(1, fake.leaves, "the early 'Verlassen' must not be lost")
    }

    @Test
    fun leaveFromBar_withoutAJoin_leavesNothingPending() {
        ConferenceDock.leaveFromBar()
        assertTrue(ConferenceDock.beginJoin("room-1"))
        val fake = FakeDockableSession()
        ConferenceDock.register(fake)
        assertEquals(0, fake.leaves)
    }

    @Test
    fun recordingChangeWhileUndocked_updatesTheTabTitleMarker() {
        PageTitle.reset()
        PageTitle.set("Mitglieder", null)
        startLiveCall()
        ConferenceDock.dispatch(DockEvent.ViewDetached)
        assertFalse(document.title.startsWith("● "))
        ConferenceDock.publish(snap.copy(recording = true))
        assertTrue(document.title.startsWith("● "), "marker appears without a route change")
        ConferenceDock.publish(snap.copy(recording = false))
        assertFalse(document.title.startsWith("● "), "marker disappears when the recording ends")
        PageTitle.reset()
    }

    @Test
    fun autoJoin_isDroppedWhenACallAlreadyRuns() {
        assertNull(conferenceEffectiveAutoJoin(true, "room-1"))
        assertEquals("room-1", conferenceEffectiveAutoJoin(false, "room-1"))
        assertNull(conferenceEffectiveAutoJoin(false, null))
    }
}
