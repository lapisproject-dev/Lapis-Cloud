package network.lapis.cloud.client.encounter

import kotlinx.browser.document
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.client.RecordedRequest
import network.lapis.cloud.client.StubResponse
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.answerWith
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.buttonNamed
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.jsonOf
import network.lapis.cloud.client.livekit.DisconnectCause
import network.lapis.cloud.client.mountedForm
import network.lapis.cloud.client.routeOf
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.client.serviceExceptionResult
import network.lapis.cloud.client.withFetchStub
import network.lapis.cloud.shared.domain.EncounterEntryDto
import network.lapis.cloud.shared.domain.EncounterEntryInfoDto
import network.lapis.cloud.shared.domain.EncounterPresentDto
import network.lapis.cloud.shared.domain.EncounterSpaceDto
import network.lapis.cloud.shared.rpc.IEncounterSpaceService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.62 -- the three phases of one room (closed, entry, inside) and the teardown: leaving the screen disconnects the session and
 * tells the server, in a REAL mounted root (the "late hooks" rule: the teardown is a destroy hook registered before the root is added).
 */
class EncounterServiceViewDomTest {
    private class Routes(
        val entryInfo: String,
        val enter: String,
        val leave: String,
        val open: String,
        val present: String,
        val getSpace: String,
    )

    private suspend fun routes() =
        Routes(
            entryInfo = routeOf { rpcService<IEncounterSpaceService>().getEntryInfo("s") },
            enter = routeOf { rpcService<IEncounterSpaceService>().enterSpace("s", null) },
            leave = routeOf { rpcService<IEncounterSpaceService>().leaveSpace("s") },
            open = routeOf { rpcService<IEncounterSpaceService>().openSpace("s") },
            present = routeOf { rpcService<IEncounterSpaceService>().listPresent("s") },
            getSpace = routeOf { rpcService<IEncounterSpaceService>().getSpace("s") },
        )

    private class Stage(
        val info: EncounterEntryInfoDto,
        val entry: EncounterEntryDto = testEntry(),
        val enterAnswer: (RecordedRequest, Routes) -> StubResponse? = { _, _ -> null },
        /** What `getSpace` answers (the state read after an unexpected disconnect). */
        val spaceNow: () -> EncounterSpaceDto = { testSpace(open = true) },
        val clock: () -> Double = { 1_000_000.0 },
    )

    private suspend fun withView(
        stage: Stage,
        opener: EncounterSessionOpener,
        block: suspend (HTMLElement, List<RecordedRequest>, Routes, io.kvision.panel.Root) -> Unit,
    ) {
        val r = routes()
        withFetchStub(
            respond = { request ->
                when {
                    !request.isRpc -> StubResponse()
                    request.rpcRoute == r.entryInfo -> request.answerWith(jsonOf(EncounterEntryInfoDto.serializer(), stage.info))
                    request.rpcRoute == r.enter ->
                        stage.enterAnswer(request, r)
                            ?: request.answerWith(jsonOf(EncounterEntryDto.serializer(), stage.entry))
                    request.rpcRoute == r.present ->
                        request.answerWith(
                            jsonOf(ListSerializer(EncounterPresentDto.serializer()), listOf(testPerson("me", name = "Ich Selbst"))),
                        )
                    request.rpcRoute == r.open -> request.answerWith(jsonOf(EncounterSpaceDto.serializer(), testSpace(open = true)))
                    request.rpcRoute == r.getSpace -> request.answerWith(jsonOf(EncounterSpaceDto.serializer(), stage.spaceNow()))
                    else -> request.answerWith("null")
                }
            },
        ) { requests ->
            mountedForm("encounter-service-view") { root, element ->
                renderEncounterServiceView(container = root, spaceId = "space-1", opener = opener, clock = stage.clock)
                block(element(), requests, r, root)
            }
        }
    }

    private fun neverOpened(): EncounterSessionOpener = { _, _ -> error("no session may be created in this phase") }

    @Test
    fun aClosedRoom_showsItsNotice_andOnlyAModeratorSeesTheOpenButton(): Promise<Unit> =
        formTest {
            val closed = testSpace(open = false, closedNotice = "Beginnt um 10:15 Uhr", canModerate = false)
            withView(Stage(EncounterEntryInfoDto(closed, false, null)), neverOpened()) { element, _, _, _ ->
                awaitUntil("the notice is shown") { element.textContent.orEmpty().contains("Beginnt um 10:15 Uhr") }
                assertFalse(element.allOf("button").any { it.textContent.orEmpty().trim() == "Türen öffnen" })
                assertFalse(element.allOf("button").any { it.textContent.orEmpty().trim() == "Eintreten" }, "nobody enters a closed room")
            }
        }

    @Test
    fun aClosedRoom_withoutANotice_saysItIsClosed_andAModeratorOpensTheDoors(): Promise<Unit> =
        formTest {
            val closed = testSpace(open = false, closedNotice = null, canModerate = true)
            withView(Stage(EncounterEntryInfoDto(closed, false, null)), neverOpened()) { element, requests, r, _ ->
                awaitUntil("the closed text is shown") { element.textContent.orEmpty().contains("Der Raum ist geschlossen.") }
                element.buttonNamed("Türen öffnen").click()
                awaitUntil("openSpace was called") { requests.any { it.isRpc && it.rpcRoute == r.open } }
                assertEquals("space-1", requests.first { it.rpcRoute == r.open }.rpcParam(0).toString())
            }
        }

    @Test
    fun anUntrustedNotice_isText(): Promise<Unit> =
        formTest {
            val closed = testSpace(open = false, closedNotice = "<img src=x onerror=alert(1)>")
            withView(Stage(EncounterEntryInfoDto(closed, false, null)), neverOpened()) { element, _, _, _ ->
                awaitUntil("the notice is shown") { element.textContent.orEmpty().contains("<img src=x onerror=alert(1)>") }
                assertNull(element.querySelector("img"))
            }
        }

    @Test
    fun anArchivedRoom_isSaidToBeArchived_andOffersNoEntry(): Promise<Unit> =
        formTest {
            withView(Stage(EncounterEntryInfoDto(testSpace(archived = true), false, null)), neverOpened()) { element, _, _, _ ->
                awaitUntil("the archived text is shown") { element.textContent.orEmpty().contains("Dieser Raum wurde archiviert.") }
                assertFalse(element.allOf("button").any { it.textContent.orEmpty().trim() == "Eintreten" })
            }
        }

    @Test
    fun enteringAnOpenRoom_buildsTheRoom_connectsOnce_andLeavingTheScreenDisconnectsAndTellsTheServer(): Promise<Unit> =
        formTest {
            val session = FakeListenerSession()
            var opened: EncounterEntryDto? = null
            val opener: EncounterSessionOpener = { entry, _ ->
                opened = entry
                session
            }
            withView(Stage(EncounterEntryInfoDto(testSpace(open = true), false, null)), opener) { element, requests, r, root ->
                awaitUntil("the entry panel is shown") { element.textContent.orEmpty().contains("Bevor Sie eintreten") }
                element.buttonNamed("Eintreten").click()
                awaitUntil("the room is built and connected") { session.connects == 1 && element.querySelector(".lapis-encounter") != null }
                assertEquals("me", opened?.join?.identity)
                assertNull(requests.first { it.isRpc && it.rpcRoute == r.enter }.rpcParam(1), "a member sends no consent")
                assertTrue(element.barControlNames().contains("Verlassen"), "the one way out, in the bar")
                assertEquals(
                    emptyList(),
                    element.allOf(".lapis-page-header button").filter { it.barName() == "Verlassen" },
                    "V1.9.74: no second 'Verlassen' in the header",
                )
                assertEquals(0, requests.count { it.isRpc && it.rpcRoute == r.leave })

                root.removeAll() // the route changes: the screen goes away
                awaitUntil("the session was disconnected") { session.disconnects == 1 }
                awaitUntil("the server was told") { requests.count { it.isRpc && it.rpcRoute == r.leave } == 1 }
            }
        }

    @Test
    fun theLeaveButton_endsTheVisit_andShowsTheEntryPanelAgain(): Promise<Unit> =
        formTest {
            val session = FakeListenerSession()
            withView(Stage(EncounterEntryInfoDto(testSpace(open = true), false, null)), { _, _ -> session }) { element, requests, r, _ ->
                awaitUntil("the entry panel is shown") { element.textContent.orEmpty().contains("Bevor Sie eintreten") }
                element.buttonNamed("Eintreten").click()
                awaitUntil("inside") { element.querySelector(".lapis-encounter") != null }
                element.barControl("Verlassen").click()
                awaitUntil("the session was disconnected") { session.disconnects == 1 }
                awaitUntil("the server was told") { requests.count { it.isRpc && it.rpcRoute == r.leave } == 1 }
                awaitUntil("the entry panel is back") {
                    element.querySelector(".lapis-encounter") == null &&
                        element.textContent.orEmpty().contains("Bevor Sie eintreten")
                }
            }
        }

    @Test
    fun aRefusedEntry_createsNoSession_andStaysOnTheEntryPanel(): Promise<Unit> =
        formTest {
            var created = 0
            val stage =
                Stage(
                    info = EncounterEntryInfoDto(testSpace(open = true), false, null),
                    enterAnswer = {
                        request,
                        _,
                        ->
                        serviceExceptionResult(request.json.id as Int, "network.lapis.cloud.shared.rpc.ConflictException")
                    },
                )
            withView(stage, { _, _ ->
                created++
                FakeListenerSession()
            }) { element, requests, r, _ ->
                awaitUntil("the entry panel is shown") { element.textContent.orEmpty().contains("Bevor Sie eintreten") }
                element.buttonNamed("Eintreten").click()
                awaitUntil("the entry was attempted") { requests.any { it.isRpc && it.rpcRoute == r.enter } }
                delay(300)
                assertEquals(0, created, "no session without an entry")
                assertNull(element.querySelector(".lapis-encounter"))
                assertNotNull(element.allOf("button").firstOrNull { it.textContent.orEmpty().trim() == "Eintreten" })
            }
        }

    @Test
    fun aFailedConnection_leavesTheRoom_andTellsTheServer(): Promise<Unit> =
        formTest {
            val session = FakeListenerSession(connectFailure = network.lapis.cloud.client.livekit.ConferenceConnectFailure.OTHER)
            withView(Stage(EncounterEntryInfoDto(testSpace(open = true), false, null)), { _, _ -> session }) { element, requests, r, _ ->
                awaitUntil("the entry panel is shown") { element.textContent.orEmpty().contains("Bevor Sie eintreten") }
                element.buttonNamed("Eintreten").click()
                awaitUntil("the server was told after the failed connect") { requests.count { it.isRpc && it.rpcRoute == r.leave } == 1 }
                assertEquals(1, session.disconnects)
                awaitUntil("the entry panel is back") { element.querySelector(".lapis-encounter") == null }
            }
        }

    @Test
    fun leavingWhileConnectIsStillPending_leavesNoListenerNoTimerNoRpcAndNoErrorToast(): Promise<Unit> =
        formTest {
            val gate = CompletableDeferred<network.lapis.cloud.client.livekit.ConferenceConnectFailure?>()
            val session =
                object : FakeListenerSession() {
                    override suspend fun connect(): network.lapis.cloud.client.livekit.ConferenceConnectFailure? {
                        connects++
                        return gate.await()
                    }
                }
            // Count the document-level visibilitychange listeners added minus removed while this visit lives (other tests' rooms may leak
            // their own into the shared document, so firing the event and counting requests would be polluted).
            var liveVisibilityListeners = 0
            val doc = document.asDynamic()
            val realAdd = doc.addEventListener
            val realRemove = doc.removeEventListener
            doc.addEventListener = { type: dynamic, l: dynamic, o: dynamic ->
                if (type == "visibilitychange") liveVisibilityListeners++
                realAdd.call(document, type, l, o)
            }
            doc.removeEventListener = { type: dynamic, l: dynamic, o: dynamic ->
                if (type == "visibilitychange") liveVisibilityListeners--
                realRemove.call(document, type, l, o)
            }
            try {
                withView(
                    Stage(EncounterEntryInfoDto(testSpace(open = true), false, null)),
                    { _, _ -> session },
                ) { element, requests, r, _ ->
                    awaitUntil("the entry panel is shown") { element.textContent.orEmpty().contains("Bevor Sie eintreten") }
                    val viewOwnListeners = liveVisibilityListeners // the screen's own listener, not the room's
                    element.buttonNamed("Eintreten").click()
                    awaitUntil("connect is pending") { session.connects == 1 }
                    element.barControl("Verlassen").click()
                    awaitUntil("the server was told") { requests.count { it.isRpc && it.rpcRoute == r.leave } == 1 }
                    gate.complete(null) // the connect finishes only after the visit has ended
                    delay(300)
                    assertEquals(0, requests.count { it.isRpc && it.rpcRoute == r.present }, "the presence list was never loaded")
                    assertEquals(viewOwnListeners, liveVisibilityListeners, "no visibilitychange listener survives a left room")
                    assertNull(element.querySelector(".lapis-encounter"))
                }
            } finally {
                doc.addEventListener = realAdd
                doc.removeEventListener = realRemove
            }
        }

    // ── an unexpected disconnect ─────────────────────────────────────────────────

    @Test
    fun anUnexpectedDisconnect_ofAClosedRoom_endsTheVisit_withTheServiceHasEndedNotice(): Promise<Unit> =
        formTest {
            var callbacks: EncounterSessionCallbacks? = null
            val session = FakeListenerSession()
            val stage =
                Stage(
                    info = EncounterEntryInfoDto(testSpace(open = true), false, null),
                    spaceNow = { testSpace(open = false, closedNotice = "Beginnt um 10:15 Uhr") },
                )
            withView(stage, { _, cb ->
                callbacks = cb
                session
            }) { element, requests, r, _ ->
                awaitUntil("the entry panel is shown") { element.textContent.orEmpty().contains("Bevor Sie eintreten") }
                element.buttonNamed("Eintreten").click()
                awaitUntil("inside") { element.querySelector(".lapis-encounter") != null }
                callbacks?.onDisconnected?.invoke(DisconnectCause.Other)
                awaitUntil("the room is left") { element.querySelector(".lapis-encounter") == null }
                awaitUntil(
                    "the notice says the service has ended",
                ) { element.textContent.orEmpty().contains("Der Gottesdienst ist beendet.") }
                assertEquals(1, requests.count { it.isRpc && it.rpcRoute == r.enter }, "no re-entry into a closed room")
            }
        }

    @Test
    fun anUnexpectedDisconnect_ofAnOpenRoom_triggersOneAutomaticReEntry(): Promise<Unit> =
        formTest {
            var callbacks: EncounterSessionCallbacks? = null
            var opened = 0
            val stage = Stage(info = EncounterEntryInfoDto(testSpace(open = true), false, null))
            withView(stage, { _, cb ->
                callbacks = cb
                opened++
                FakeListenerSession()
            }) { element, requests, r, _ ->
                awaitUntil("the entry panel is shown") { element.textContent.orEmpty().contains("Bevor Sie eintreten") }
                element.buttonNamed("Eintreten").click()
                awaitUntil("inside") { opened == 1 && element.querySelector(".lapis-encounter") != null }
                callbacks?.onDisconnected?.invoke(DisconnectCause.Other)
                awaitUntil("the second session was opened by the automatic re-entry") { opened == 2 }
                awaitUntil("inside again") { element.querySelector(".lapis-encounter") != null }
                assertEquals(2, requests.count { it.isRpc && it.rpcRoute == r.enter })
                assertNull(requests.last { it.isRpc && it.rpcRoute == r.enter }.rpcParam(1), "the automatic re-entry sends no consent")
            }
        }

    @Test
    fun theAutomaticReEntry_isLimitedToThreePerFiveMinutes_thenTheEntryPanelComesBack(): Promise<Unit> =
        formTest {
            var callbacks: EncounterSessionCallbacks? = null
            var opened = 0
            val stage = Stage(info = EncounterEntryInfoDto(testSpace(open = true), false, null))
            withView(stage, { _, cb ->
                callbacks = cb
                opened++
                FakeListenerSession()
            }) { element, _, _, _ ->
                awaitUntil("the entry panel is shown") { element.textContent.orEmpty().contains("Bevor Sie eintreten") }
                element.buttonNamed("Eintreten").click()
                awaitUntil("inside") { opened == 1 && element.querySelector(".lapis-encounter") != null }
                repeat(3) { round ->
                    callbacks?.onDisconnected?.invoke(DisconnectCause.Other)
                    awaitUntil("re-entry number ${round + 1}") { opened == round + 2 && element.querySelector(".lapis-encounter") != null }
                }
                callbacks?.onDisconnected?.invoke(DisconnectCause.Other) // the fourth within the window: no automatic re-entry
                awaitUntil("the entry panel is back") {
                    element.querySelector(".lapis-encounter") == null &&
                        element.textContent.orEmpty().contains("Bevor Sie eintreten")
                }
                assertEquals(4, opened, "the sessions: the first entry and three automatic re-entries")
                assertTrue(element.textContent.orEmpty().contains("Die Verbindung wurde unterbrochen."))
            }
        }

    // ── the same account on a second device (V1.9.69) ───────────────────────────

    @Test
    fun aDuplicateIdentityDisconnect_showsTheCard_andNeitherReEntersNorReadsTheSpaceNorLeaves(): Promise<Unit> =
        formTest {
            var callbacks: EncounterSessionCallbacks? = null
            var opened = 0
            val stage = Stage(info = EncounterEntryInfoDto(testSpace(open = true), false, null))
            withView(stage, { _, cb ->
                callbacks = cb
                opened++
                FakeListenerSession()
            }) { element, requests, r, _ ->
                awaitUntil("the entry panel is shown") { element.textContent.orEmpty().contains("Bevor Sie eintreten") }
                element.buttonNamed("Eintreten").click()
                awaitUntil("inside") { opened == 1 && element.querySelector(".lapis-encounter") != null }
                val getSpaceBefore = requests.count { it.isRpc && it.rpcRoute == r.getSpace }
                callbacks?.onDisconnected?.invoke(DisconnectCause.DuplicateIdentity)
                awaitUntil("the card is shown") { element.querySelector(".lapis-connection-stopped") != null }
                kotlinx.coroutines.delay(100)
                assertEquals(1, opened, "no automatic re-entry")
                assertEquals(1, requests.count { it.isRpc && it.rpcRoute == r.enter })
                assertEquals(
                    getSpaceBefore,
                    requests.count { it.isRpc && it.rpcRoute == r.getSpace },
                    "no getSpace for the lost connection",
                )
                assertEquals(0, requests.count { it.isRpc && it.rpcRoute == r.leave }, "leaveSpace would throw the other device out")
                assertTrue(element.textContent.orEmpty().contains("Auf einem anderen Gerät verbunden"))
            }
        }

    @Test
    fun theDuplicateIdentityCard_resumeEntersAgainOnClick(): Promise<Unit> =
        formTest {
            var callbacks: EncounterSessionCallbacks? = null
            var opened = 0
            val stage = Stage(info = EncounterEntryInfoDto(testSpace(open = true), false, null))
            withView(stage, { _, cb ->
                callbacks = cb
                opened++
                FakeListenerSession()
            }) { element, requests, r, _ ->
                awaitUntil("the entry panel is shown") { element.textContent.orEmpty().contains("Bevor Sie eintreten") }
                element.buttonNamed("Eintreten").click()
                awaitUntil("inside") { opened == 1 && element.querySelector(".lapis-encounter") != null }
                callbacks?.onDisconnected?.invoke(DisconnectCause.DuplicateIdentity)
                awaitUntil("the card is shown") { element.querySelector(".lapis-connection-stopped") != null }
                element.buttonNamed("Hier fortsetzen").click()
                awaitUntil("the second session was opened by the click") { opened == 2 }
                awaitUntil("the card is gone and the room is back") {
                    element.querySelector(".lapis-connection-stopped") == null && element.querySelector(".lapis-encounter") != null
                }
                assertEquals(2, requests.count { it.isRpc && it.rpcRoute == r.enter })
            }
        }
}
