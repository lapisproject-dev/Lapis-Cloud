package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceAvailabilityDto
import network.lapis.cloud.shared.domain.ConferenceRole
import network.lapis.cloud.shared.domain.ConferenceRoomDto
import network.lapis.cloud.shared.domain.EventConferenceParticipation
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.RunningEventConferenceDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IConferenceService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.295 -- running event conferences in the member lobby: information plus a link to the event page, never a join button; an old server
 * (route missing) or a failing request leaves the room list untouched and silent.
 */
class ConferenceLobbyEventConferenceDomTest {
    private val timeoutMs = 15_000

    private val eventIds =
        listOf("0a1b2c3d-0000-4000-8000-000000000001", "0a1b2c3d-0000-4000-8000-000000000002", "0a1b2c3d-0000-4000-8000-000000000003")

    private fun session() =
        SessionInfoDto(
            memberId = "member-1",
            displayName = "Dana Keller",
            role = AccountRole.MEMBER,
            status = MemberStatus.ACTIVE,
            expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
            isGuest = false,
        )

    private fun room() =
        ConferenceRoomDto(
            id = "room-1",
            title = "Gewöhnliche Besprechung",
            description = "",
            livekitRoomName = "lk-room-1",
            createdByMemberId = "member-1",
            createdByDisplayName = "Dana Keller",
            createdAt = LocalDateTime(2026, 10, 3, 10, 0),
            endedAt = null,
            active = true,
            maxParticipants = 10,
            liveParticipantCount = 0,
            myRole = ConferenceRole.MODERATOR,
        )

    private fun entries() =
        EventConferenceParticipation.entries.mapIndexed { i, participation ->
            RunningEventConferenceDto(
                eventId = eventIds[i],
                eventTitle = "Tagung ${i + 1}",
                startsAt = LocalDateTime(2030, 6, 15, 18, 0),
                endsAt = LocalDateTime(2030, 6, 15, 20, 0),
                participation = participation,
            )
        }

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun HTMLElement.count(text: String): Int = textContent.orEmpty().split(text).size - 1

    private fun scenario(
        name: String,
        rooms: List<ConferenceRoomDto>,
        eventAnswer: (RecordedRequest) -> StubResponse,
        check: suspend (element: () -> HTMLElement) -> Unit,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session())
            val availability = routeOf { rpcService<IConferenceService>().getAvailability() }
            val list = routeOf { rpcService<IConferenceService>().listActiveRooms() }
            val running = routeOf { rpcService<IConferenceService>().listRunningEventConferences() }
            try {
                withFetchStub(
                    respond = { request ->
                        when {
                            !request.isRpc -> StubResponse()
                            request.rpcRoute == availability ->
                                request.answerWith(
                                    jsonOf(
                                        ConferenceAvailabilityDto.serializer(),
                                        ConferenceAvailabilityDto(enabled = true, serverUrl = "ws://127.0.0.1:9", maxParticipants = 10),
                                    ),
                                )
                            request.rpcRoute == list -> request.answerWith(jsonOf(ListSerializer(ConferenceRoomDto.serializer()), rooms))
                            request.rpcRoute == running -> eventAnswer(request)
                            else -> request.answerWith("[]")
                        }
                    },
                ) { _ ->
                    mountedForm("v19295-lobby-$name") { root, element ->
                        renderConferenceScreen(root)
                        check { element() }
                    }
                }
            } finally {
                ConferenceDock.resetForTest()
                AppState.setSession(null)
            }
        }

    @Test
    fun runningEventConferences_showAsInformationRowsWithALinkAndNoJoinButton(): Promise<Unit> =
        scenario(
            name = "rows",
            rooms = listOf(room()),
            eventAnswer = { it.answerWith(jsonOf(ListSerializer(RunningEventConferenceDto.serializer()), entries())) },
        ) { element ->
            awaitUntil("[rows] the event rows are shown", timeoutMs) {
                element().shows("Tagung 3") &&
                    element().shows("Gewöhnliche Besprechung")
            }
            val text = element().textContent.orEmpty()
            assertTrue(text.indexOf("Tagung 1") < text.indexOf("Gewöhnliche Besprechung"), "event rows come before the room card")
            assertTrue(element().count("Veranstaltung") >= 3, "each row has the badge")
            assertTrue(element().shows("Die Videokonferenz läuft. Sie nehmen über die Veranstaltungsseite teil."))
            assertTrue(element().shows("Teilnehmen kann nur, wer zur Veranstaltung angemeldet und bestätigt ist."))
            assertTrue(element().shows("Die Teilnahme öffnet auf der Veranstaltungsseite."))
            eventIds.forEachIndexed { i, id ->
                val links = element().querySelectorAll("a[href='#/events/$id']")
                assertEquals(1, links.length, "exactly one link for event $id")
                val label = (links.item(0) as HTMLElement).getAttribute("aria-label").orEmpty()
                assertTrue(label.contains("Tagung ${i + 1}"), "aria-label names the event: $label")
            }
            // the only join button belongs to the ordinary room
            assertEquals(1, element().count("Beitreten"), "one join button, on the room card")
        }

    @Test
    fun aMissingRoute_leavesTheRoomListInPlace_andShowsNoError(): Promise<Unit> =
        scenario(
            name = "old-server",
            rooms = listOf(room()),
            eventAnswer = { StubResponse(status = 404, text = "Not Found") },
        ) { element ->
            awaitUntil("[old-server] the room card is shown", timeoutMs) { element().shows("Gewöhnliche Besprechung") }
            assertFalse(element().shows("Zur Veranstaltung"), "no event rows")
            assertEquals(0, element().querySelectorAll("a[href^='#/events/']").length)
        }

    @Test
    fun bothListsEmpty_showTheEmptyHintOnce(): Promise<Unit> =
        scenario(
            name = "empty",
            rooms = emptyList(),
            eventAnswer = { it.answerWith(jsonOf(ListSerializer(RunningEventConferenceDto.serializer()), emptyList())) },
        ) { element ->
            awaitUntil("[empty] the hint is shown", timeoutMs) { element().shows("Derzeit keine aktive Besprechung.") }
            assertEquals(1, element().count("Derzeit keine aktive Besprechung."))
        }

    @Test
    fun onlyEventConferences_noEmptyHint(): Promise<Unit> =
        scenario(
            name = "only-events",
            rooms = emptyList(),
            eventAnswer = { it.answerWith(jsonOf(ListSerializer(RunningEventConferenceDto.serializer()), entries().take(1))) },
        ) { element ->
            awaitUntil("[only-events] the row is shown", timeoutMs) { element().shows("Tagung 1") }
            assertFalse(element().shows("Derzeit keine aktive Besprechung."))
        }

    @Test
    fun aTitleWithAMarker_isSanitized_andARefreshPaintsNoDuplicates(): Promise<Unit> =
        scenario(
            name = "refresh",
            rooms = emptyList(),
            eventAnswer = {
                it.answerWith(
                    jsonOf(
                        ListSerializer(RunningEventConferenceDto.serializer()),
                        entries().take(1).map { e -> e.copy(eventTitle = KV_I18N_MARKER + "Eingeschleust") },
                    ),
                )
            },
        ) { element ->
            awaitUntil("[refresh] the row is shown", timeoutMs) { element().querySelectorAll("a[href^='#/events/']").length == 1 }
            assertFalse(element().shows(KV_I18N_MARKER), "the forged marker is neutralised")
            val refresh = element().querySelectorAll("button")
            var button: HTMLElement? = null
            for (i in 0 until refresh.length) {
                val b = refresh.item(i) as HTMLElement
                if (b.textContent.orEmpty().contains("Aktualisieren")) button = b
            }
            val target = button ?: error("refresh button missing")
            target.click()
            target.click()
            kotlinx.coroutines.delay(600)
            assertEquals(1, element().querySelectorAll("a[href^='#/events/']").length, "no duplicate rows after two quick refreshes")
        }
}
