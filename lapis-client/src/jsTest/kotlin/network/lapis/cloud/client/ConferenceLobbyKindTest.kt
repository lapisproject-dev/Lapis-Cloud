package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.AccountRole
import network.lapis.cloud.shared.domain.MemberStatus
import network.lapis.cloud.shared.domain.SessionInfoDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** V1.9.126 -- the lobby decision follows the session STATUS (GUEST and FRIEND = non-member), never `isGuest`. */
class ConferenceLobbyKindTest {
    private fun session(
        status: MemberStatus,
        isGuest: Boolean = false,
    ) = SessionInfoDto(
        memberId = "m-1",
        displayName = "Testperson",
        role = AccountRole.MEMBER,
        expiresAt = LocalDateTime(2099, 1, 1, 0, 0),
        isGuest = isGuest,
        status = status,
    )

    @Test
    fun noSession_isMemberLobby() {
        assertEquals(ConferenceLobbyKind.MEMBER_LOBBY, conferenceLobbyKindOf(null))
        assertFalse(isConferenceNonMember(null))
    }

    @Test
    fun active_isMemberLobby() {
        assertEquals(ConferenceLobbyKind.MEMBER_LOBBY, conferenceLobbyKindOf(session(MemberStatus.ACTIVE)))
    }

    @Test
    fun guest_isGuestLobby() {
        assertEquals(ConferenceLobbyKind.GUEST_LOBBY, conferenceLobbyKindOf(session(MemberStatus.GUEST, isGuest = true)))
    }

    @Test
    fun friend_isGuestLobby_evenThoughIsGuestIsFalse() {
        assertEquals(ConferenceLobbyKind.GUEST_LOBBY, conferenceLobbyKindOf(session(MemberStatus.FRIEND, isGuest = false)))
    }

    @Test
    fun everyStatus_onlyGuestAndFriendGetTheGuestLobby() {
        val nonMembers = setOf(MemberStatus.GUEST, MemberStatus.FRIEND)
        MemberStatus.entries.forEach { status ->
            val isNonMember = status in nonMembers
            val expected = if (isNonMember) ConferenceLobbyKind.GUEST_LOBBY else ConferenceLobbyKind.MEMBER_LOBBY
            assertEquals(expected, conferenceLobbyKindOf(session(status)), "status $status")
            assertEquals(isNonMember, isConferenceNonMember(session(status)), "status $status")
        }
    }

    @Test
    fun lobbyIntro_friendHasNoHomeServer_guestUnchanged() {
        val friend = conferenceGuestLobbyIntro(session(MemberStatus.FRIEND))
        assertTrue(friend.contains("Freund-Konto"))
        assertFalse(friend.contains("Heimserver"))
        val guest = conferenceGuestLobbyIntro(session(MemberStatus.GUEST, isGuest = true))
        assertTrue(guest.contains("über Ihren eigenen Heimserver angemeldet"))
    }

    @Test
    fun postDisconnectRoomLookup_isSkippedForNonMembers_only() {
        assertFalse(conferenceRoomLookupAfterDisconnectAllowed(session(MemberStatus.FRIEND)))
        assertFalse(conferenceRoomLookupAfterDisconnectAllowed(session(MemberStatus.GUEST, isGuest = true)))
        assertTrue(conferenceRoomLookupAfterDisconnectAllowed(session(MemberStatus.ACTIVE)))
    }
}
