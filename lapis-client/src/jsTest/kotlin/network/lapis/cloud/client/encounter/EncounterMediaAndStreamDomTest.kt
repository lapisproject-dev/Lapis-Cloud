package network.lapis.cloud.client.encounter

import kotlinx.browser.document
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.client.AppState
import network.lapis.cloud.client.RecordedRequest
import network.lapis.cloud.client.StubResponse
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.answerWith
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.buttonNamed
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.jsonOf
import network.lapis.cloud.client.livekit.Track
import network.lapis.cloud.client.livekit.TrackPublication
import network.lapis.cloud.client.routeOf
import network.lapis.cloud.client.rpcService
import network.lapis.cloud.client.serviceExceptionResult
import network.lapis.cloud.client.tick
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceStreamAvailabilityDto
import network.lapis.cloud.shared.domain.ConferenceStreamDto
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetDto
import network.lapis.cloud.shared.domain.EncounterPresenceRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IConferenceStreamingService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.62 -- the office holders' media tiles, the transmission panel (pulpit only, no destination created) and the LIVE badge. */
class EncounterMediaAndStreamDomTest {
    private var clock = 2_000_000.0

    private fun fakeTrack(
        kind: String,
        element: HTMLElement,
    ): Track {
        val track: dynamic = js("({})")
        track.kind = kind
        track.attach = { element }
        track.detach = { arrayOf<HTMLElement>(element) }
        return track.unsafeCast<Track>()
    }

    private fun fakePublication(source: String): TrackPublication {
        val publication: dynamic = js("({})")
        publication.source = source
        publication.trackSid = "sid"
        publication.isSubscribed = true
        return publication.unsafeCast<TrackPublication>()
    }

    private fun stream(status: ConferenceStreamStatus = ConferenceStreamStatus.LIVE) =
        ConferenceStreamDto(
            id = "stream-1",
            roomId = "room-1",
            roomTitle = "Sonntag",
            status = status,
            layout = ConferenceStreamLayout.SINGLE_PARTICIPANT,
            latencyMode = ConferenceStreamLatencyMode.STANDARD,
            startedByMemberId = "p1",
            startedByDisplayName = "Pfarrer Paul",
            startedAt = LocalDateTime(2026, 10, 4, 10, 0),
            pausedAt = null,
            endedAt = null,
            restartCount = 0,
            targets = emptyList(),
            failureReason = null,
            pauseReason = null,
        )

    private fun session(role: AccountRole) =
        SessionInfoDto(
            memberId = "me",
            displayName = "Ich Selbst",
            role = role,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        )

    // ── media tiles ──────────────────────────────────────────────────────────────

    @Test
    fun aPulpitVideo_staysTheSameElement_whenTheTilesAreRebuilt_andIsRemovedWhenTheTrackGoes(): Promise<Unit> =
        formTest {
            var people = listOf(testPerson("p1", EncounterPresenceRole.PULPIT, "Pfarrer Paul"), testPerson("c1"))
            withEncounterRoom(entry = testEntry(), peopleOf = { people }, clock = { clock }) { rig, element ->
                val video = document.createElement("video") as HTMLElement
                val track = fakeTrack("video", video)
                rig.room.callbacks.onRemoteTrack("p1", "Pfarrer Paul", track, fakePublication("camera"))
                awaitUntil("the video sits in the pulpit tile") { element.querySelector(".lapis-encounter-pulpit video") === video }
                // a second pulpit person appears: the pulpit tiles are rebuilt, the video element must survive in the document
                people = people + testPerson("p2", EncounterPresenceRole.PULPIT, "Zweite Kanzel")
                rig.room.callbacks.onParticipantJoined("p2", "Zweite Kanzel")
                awaitUntil("two pulpit tiles") { element.allOf(".lapis-encounter-pulpit .lapis-encounter-tile").size == 2 }
                assertTrue(video.isConnected, "the <video> element is still in the document")
                assertTrue(element.querySelector(".lapis-encounter-pulpit video") === video, "and it is the SAME element, not a copy")
                rig.room.callbacks.onRemoteTrackGone("p1", track, fakePublication("camera"))
                awaitUntil("the video is gone") { element.querySelector(".lapis-encounter-pulpit video") == null }
            }
        }

    @Test
    fun aScreenShareOrAnUnknownSource_isNotShownAsAPulpitVideo(): Promise<Unit> =
        formTest {
            val people = listOf(testPerson("p1", EncounterPresenceRole.PULPIT, "Pfarrer Paul"))
            withEncounterRoom(entry = testEntry(), peopleOf = { people }, clock = { clock }) { rig, element ->
                val video = document.createElement("video") as HTMLElement
                rig.room.callbacks.onRemoteTrack("p1", "Pfarrer Paul", fakeTrack("video", video), fakePublication("screen_share"))
                delay(150)
                assertNull(element.querySelector(".lapis-encounter-pulpit video"))
            }
        }

    // ── LIVE badge ───────────────────────────────────────────────────────────────

    @Test
    fun theLiveBadge_isShownToEverybodyPresent_whileThePulpitIsTransmitted(): Promise<Unit> =
        formTest {
            val activeRoute = routeOf { rpcService<IConferenceStreamingService>().getActiveStream("room-1") }
            withEncounterRoom(
                entry = testEntry(),
                peopleOf = { listOf(testPerson("c1")) },
                clock = { clock },
                extraRespond = { request ->
                    if (request.rpcRoute ==
                        activeRoute
                    ) {
                        request.answerWith(jsonOf(ListSerializer(ConferenceStreamDto.serializer()), listOf(stream())))
                    } else {
                        null
                    }
                },
            ) { rig, element ->
                awaitUntil("the badge is shown") { rig.room.liveBadgeView.isShown }
                assertTrue(element.textContent.orEmpty().contains("Live (nur Kanzel)"))
            }
        }

    @Test
    fun whenStreamingIsNotAvailable_theBadgeStaysHidden_andPollingStops(): Promise<Unit> =
        formTest {
            val activeRoute = routeOf { rpcService<IConferenceStreamingService>().getActiveStream("room-1") }
            withEncounterRoom(
                entry = testEntry(),
                peopleOf = { listOf(testPerson("c1")) },
                clock = { clock },
                extraRespond = { request ->
                    if (request.rpcRoute ==
                        activeRoute
                    ) {
                        serviceExceptionResult(request.json.id as Int, "network.lapis.cloud.shared.rpc.ConflictException")
                    } else {
                        null
                    }
                },
            ) { rig, _ ->
                awaitUntil("the poll was answered with a conflict") { !rig.room.liveBadgeView.available }
                assertFalse(rig.room.liveBadgeView.isShown)
                val before = rig.requests.count { it.isRpc && it.rpcRoute == activeRoute }
                rig.room.liveBadgeView.poll()
                assertEquals(
                    before,
                    rig.requests.count { it.isRpc && it.rpcRoute == activeRoute },
                    "no further request once streaming is off",
                )
            }
        }

    // ── transmission panel ───────────────────────────────────────────────────────

    private class StreamRoutes(
        val availability: String,
        val targets: String,
        val active: String,
        val start: String,
    )

    private suspend fun streamRoutes() =
        StreamRoutes(
            availability = routeOf { rpcService<IConferenceStreamingService>().getStreamingAvailability() },
            targets = routeOf { rpcService<IConferenceStreamingService>().listStreamTargets() },
            active = routeOf { rpcService<IConferenceStreamingService>().getActiveStream("r") },
            start =
                routeOf {
                    rpcService<IConferenceStreamingService>().startStream(
                        roomId = "r",
                        destinationIds = emptyList(),
                        layout = ConferenceStreamLayout.GRID,
                        latencyMode = ConferenceStreamLatencyMode.STANDARD,
                        participantIdentity = null,
                    )
                },
        )

    private fun respondStreaming(
        routes: StreamRoutes,
        enabled: Boolean = true,
        targets: List<ConferenceStreamTargetDto>,
    ): (RecordedRequest) -> StubResponse? =
        { request ->
            when (request.rpcRoute) {
                routes.availability ->
                    request.answerWith(
                        jsonOf(
                            ConferenceStreamAvailabilityDto.serializer(),
                            ConferenceStreamAvailabilityDto(
                                enabled = enabled,
                                encryptionConfigured = true,
                                maxDestinations = 2,
                                configuredDestinationCount = targets.size,
                            ),
                        ),
                    )
                routes.targets -> request.answerWith(jsonOf(ListSerializer(ConferenceStreamTargetDto.serializer()), targets))
                routes.active -> request.answerWith("[]")
                routes.start -> request.answerWith(jsonOf(ConferenceStreamDto.serializer(), stream(ConferenceStreamStatus.STARTING)))
                else -> null
            }
        }

    private val steward = testEntry(role = EncounterPresenceRole.STEWARD)

    @Test
    fun theTransmissionTab_existsOnlyForPeopleWhoModerate(): Promise<Unit> =
        formTest {
            withEncounterRoom(entry = testEntry(), peopleOf = { listOf(testPerson("c1")) }, clock = { clock }) { _, element ->
                element.barControl("Chat").click()
                awaitUntil("the panel is open") { element.querySelector("[role=tab]") != null }
                assertEquals(listOf("Chat", "Anwesende"), element.allOf("[role=tab]").map { it.textContent.orEmpty().trim() })
            }
            withEncounterRoom(entry = steward, peopleOf = {
                listOf(testPerson("me", EncounterPresenceRole.STEWARD))
            }, clock = { clock }) { _, element ->
                element.barControl("Chat").click()
                awaitUntil("the panel is open") { element.querySelector("[role=tab]") != null }
                assertEquals(
                    listOf("Chat", "Anwesende", "Übertragung"),
                    element.allOf("[role=tab]").map { it.textContent.orEmpty().trim() },
                )
            }
        }

    @Test
    fun startingTheTransmission_isPulpitOnly_fixedLayout_withTheOnePulpitPersonAndTheChosenDestination(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val routes = streamRoutes()
            val targets = listOf(ConferenceStreamTargetDto("dest-a", "Gemeinde-Kanal", ConferenceStreamPlatform.GENERIC_RTMP))
            val people =
                listOf(testPerson("me", EncounterPresenceRole.STEWARD), testPerson("p1", EncounterPresenceRole.PULPIT, "Pfarrer Paul"))
            withEncounterRoom(
                entry = steward,
                peopleOf = { people },
                clock = { clock },
                session = FakeSpeakerSession(),
                extraRespond = respondStreaming(routes, targets = targets),
            ) { rig, element ->
                element.barControl("Übertragung").click()
                val panel = assertNotNull(element.querySelector(".lapis-encounter-stream") as? HTMLElement)
                awaitUntil("the start form is shown") { panel.textContent.orEmpty().contains("Gemeinde-Kanal") }
                assertTrue(panel.textContent.orEmpty().contains("Nur Kanzel"), "the layout is a fixed text")
                assertNull(panel.querySelector("select"), "there is no layout choice")
                assertFalse(panel.textContent.orEmpty().contains("YouTube", ignoreCase = true), "no platform is offered or created here")
                assertFalse(panel.textContent.orEmpty().contains("Wessen Bild"), "one pulpit person: nobody to choose")
                panel.tick("Gemeinde-Kanal")
                panel.buttonNamed("Übertragung starten").click()
                awaitUntil("the start reached the server") { rig.requests.any { it.isRpc && it.rpcRoute == routes.start } }
                val call = rig.requests.first { it.isRpc && it.rpcRoute == routes.start }
                assertEquals("room-1", call.rpcParam(0).toString())
                assertEquals(listOf("dest-a"), (call.rpcParam(1) as Array<dynamic>).map { it.toString() })
                assertEquals("SINGLE_PARTICIPANT", call.rpcParam(2).toString())
                assertEquals("STANDARD", call.rpcParam(3).toString())
                assertEquals("p1", call.rpcParam(4).toString())
            }
        }

    @Test
    fun withNoPulpitPerson_theStartButtonStaysDisabled_withTheReason(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.MEMBER))
            val routes = streamRoutes()
            val targets = listOf(ConferenceStreamTargetDto("dest-a", "Gemeinde-Kanal", ConferenceStreamPlatform.GENERIC_RTMP))
            withEncounterRoom(
                entry = steward,
                peopleOf = { listOf(testPerson("me", EncounterPresenceRole.STEWARD)) },
                clock = { clock },
                session = FakeSpeakerSession(),
                extraRespond = respondStreaming(routes, targets = targets),
            ) { _, element ->
                element.barControl("Übertragung").click()
                val panel = assertNotNull(element.querySelector(".lapis-encounter-stream") as? HTMLElement)
                awaitUntil("the start form is shown") { panel.textContent.orEmpty().contains("Gemeinde-Kanal") }
                assertTrue(panel.textContent.orEmpty().contains("Niemand ist auf der Kanzel."))
                assertTrue(panel.buttonNamed("Übertragung starten").hasAttribute("disabled"))
            }
        }

    @Test
    fun withoutADestination_theAdminLinkIsShownToAdministratorsOnly(): Promise<Unit> =
        formTest {
            val routes = streamRoutes()
            for ((role, expectLink) in listOf(AccountRole.MEMBER to false, AccountRole.ADMIN to true)) {
                AppState.setSession(session(role))
                withEncounterRoom(
                    entry = steward,
                    peopleOf = { listOf(testPerson("me", EncounterPresenceRole.STEWARD)) },
                    clock = { clock },
                    session = FakeSpeakerSession(),
                    extraRespond = respondStreaming(routes, targets = emptyList()),
                ) { _, element ->
                    element.barControl("Übertragung").click()
                    val panel = assertNotNull(element.querySelector(".lapis-encounter-stream") as? HTMLElement)
                    awaitUntil("the empty state is shown") { panel.textContent.orEmpty().contains("Kein Übertragungsziel eingerichtet.") }
                    assertEquals(expectLink, panel.textContent.orEmpty().contains("Übertragungsziele verwalten"), "role $role")
                }
            }
        }

    @Test
    fun whenStreamingIsNotSetUpOnTheInstance_thePanelSaysSo(): Promise<Unit> =
        formTest {
            AppState.setSession(session(AccountRole.ADMIN))
            val routes = streamRoutes()
            withEncounterRoom(
                entry = steward,
                peopleOf = { listOf(testPerson("me", EncounterPresenceRole.STEWARD)) },
                clock = { clock },
                session = FakeSpeakerSession(),
                extraRespond = respondStreaming(routes, enabled = false, targets = emptyList()),
            ) { _, element ->
                element.barControl("Übertragung").click()
                val panel = assertNotNull(element.querySelector(".lapis-encounter-stream") as? HTMLElement)
                awaitUntil(
                    "the unavailable text",
                ) { panel.textContent.orEmpty().contains("Übertragung ist auf dieser Instanz nicht eingerichtet.") }
            }
        }
}
