package network.lapis.cloud.client

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.ConferenceAvailabilityDto
import network.lapis.cloud.shared.domain.ConferenceRoomDto
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import network.lapis.cloud.shared.rpc.IConferenceService
import org.w3c.dom.HTMLElement
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.126 -- the conference screen picks its lobby from the session STATUS: a FRIEND (`isGuest == false`) must get the guest
 * lobby and never trigger `listActiveRooms` (ACTIVE-only, would toast "Keine Berechtigung").
 */
class ConferenceLobbyDecisionDomTest {
    private val timeoutMs = 15_000

    private fun session(
        status: MemberStatus,
        isGuest: Boolean,
    ) = SessionInfoDto(
        memberId = "member-1",
        displayName = "Dana Keller",
        role = AccountRole.MEMBER,
        status = status,
        expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        isGuest = isGuest,
    )

    private fun HTMLElement.shows(text: String): Boolean = textContent.orEmpty().contains(text)

    private fun scenario(
        name: String,
        status: MemberStatus,
        isGuest: Boolean,
        expectGuestLobby: Boolean,
        expectedIntro: String,
    ): Promise<Unit> =
        formTest {
            AppState.setSession(session(status, isGuest))
            val availability = routeOf { rpcService<IConferenceService>().getAvailability() }
            val list = routeOf { rpcService<IConferenceService>().listActiveRooms() }
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
                            request.rpcRoute == list ->
                                request.answerWith(jsonOf(ListSerializer(ConferenceRoomDto.serializer()), emptyList()))
                            else -> request.answerWith("[]")
                        }
                    },
                ) { calls ->
                    mountedForm("v19126-lobby-$name") { root, element ->
                        renderConferenceScreen(root)
                        if (expectGuestLobby) {
                            awaitUntil("[$name] the guest lobby is shown", timeoutMs) { element().shows("Als Gast beitreten") }
                            assertTrue(element().shows(expectedIntro), "[$name] intro sentence")
                            delay(300)
                            assertEquals(0, calls.toRoute(list).size, "[$name] listActiveRooms is never called")
                            assertFalse(element().shows("Aktive Besprechungen"), "[$name] no member lobby")
                        } else {
                            awaitUntil("[$name] the member lobby is shown", timeoutMs) { element().shows("Aktive Besprechungen") }
                            // The heading is rendered before the list request is on the wire (the stub records it after reading the body):
                            // wait for the request instead of checking at the moment the heading shows (V1.9.218, failed in the full run).
                            awaitUntil("[$name] listActiveRooms is called", timeoutMs) { calls.toRoute(list).isNotEmpty() }
                            assertFalse(element().shows("Als Gast beitreten"), "[$name] no guest lobby")
                        }
                    }
                }
            } finally {
                ConferenceDock.resetForTest()
                AppState.setSession(null)
            }
        }

    @Test
    fun friend_getsTheGuestLobby_withoutHomeServerWording_andNeverListsRooms(): Promise<Unit> =
        scenario("friend", MemberStatus.FRIEND, isGuest = false, expectGuestLobby = true, expectedIntro = "Freund-Konto")

    @Test
    fun guest_getsTheGuestLobby_withTheHomeServerWording(): Promise<Unit> =
        scenario("guest", MemberStatus.GUEST, isGuest = true, expectGuestLobby = true, expectedIntro = "über Ihren eigenen Heimserver")

    @Test
    fun active_getsTheMemberLobby_andListsRooms(): Promise<Unit> =
        scenario("active", MemberStatus.ACTIVE, isGuest = false, expectGuestLobby = false, expectedIntro = "")
}
