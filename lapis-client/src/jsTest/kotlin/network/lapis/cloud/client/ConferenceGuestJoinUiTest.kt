package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.ConferenceGuestConsentDisclaimerDto
import network.lapis.cloud.shared.domain.ConferenceGuestJoinInfoDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.0 Videokonferenzen, Wave 5 "Föderations-Gastbeitritt" -- covers the pure, DOM-independent
 * functions factored out of `ConferenceScreen.kt`'s guest-join flow
 * ([conferenceGuestJoinBlockedReason], [conferenceGuestConsentInputOf], [conferenceInviteText],
 * [conferenceTileLabel], [Validation.looksLikeRoomId]). Same DOM-free unit-test posture as
 * [ConferenceScreenTest]/[GuestBadgeTest] -- no rendering harness exists in this module, so
 * `renderGuestLobby`/`conferenceGuestConsentModal`/the raw-DOM tile pill are out of scope here.
 */
class ConferenceGuestJoinUiTest {
    private fun disclaimer(
        version: String = "2026-08-10.v1",
        sha256: String = "abc123",
    ) = ConferenceGuestConsentDisclaimerDto(
        version = version,
        headline = "Sie treten als Gast eines anderen Servers bei.",
        keyPoints = listOf("Punkt 1", "Punkt 2"),
        text = "Vollständiger Hinweistext.",
        sha256 = sha256,
    )

    private fun joinInfo(
        roomActive: Boolean = true,
        allowsFederationGuests: Boolean = true,
        title: String = "Vorstandssitzung",
    ) = ConferenceGuestJoinInfoDto(
        roomId = "11111111-1111-1111-1111-111111111111",
        title = title,
        allowsFederationGuests = allowsFederationGuests,
        roomActive = roomActive,
        organizationName = "Partei der Vernunft",
        createdByMemberId = "22222222-2222-2222-2222-222222222222",
        createdByDisplayName = "Erika Musterfrau",
        callerIsGuest = true,
        disclaimer = disclaimer(),
    )

    // ── conferenceGuestJoinBlockedReason ─────────────────────────────────

    @Test
    fun blockedReason_null_whenJoinable() {
        assertNull(conferenceGuestJoinBlockedReason(joinInfo()))
    }

    @Test
    fun blockedReason_endedRoom() {
        val reason = conferenceGuestJoinBlockedReason(joinInfo(roomActive = false))
        assertEquals("Die Besprechung „Vorstandssitzung\" ist bereits beendet.", reason)
    }

    @Test
    fun blockedReason_notOptedIn() {
        val reason = conferenceGuestJoinBlockedReason(joinInfo(allowsFederationGuests = false))
        assertTrue(reason!!.contains("Vorstandssitzung"))
        assertTrue(reason.contains("lässt derzeit keine Gäste anderer Server zu"))
    }

    @Test
    fun blockedReason_endedTakesPrecedenceOverNotOptedIn() {
        val reason = conferenceGuestJoinBlockedReason(joinInfo(roomActive = false, allowsFederationGuests = false))
        assertTrue(reason!!.contains("bereits beendet"))
    }

    // ── conferenceGuestConsentInputOf ────────────────────────────────────

    @Test
    fun consentInputOf_roundTripsVersionAndSha256Verbatim() {
        val d = disclaimer(version = "2099-01-01.v7", sha256 = "deadbeef")
        val input = conferenceGuestConsentInputOf(d)
        assertEquals("2099-01-01.v7", input.consentVersion)
        assertEquals("deadbeef", input.consentSha256)
    }

    @Test
    fun consentInputOf_readsFromArgument_notConstants() {
        val first = conferenceGuestConsentInputOf(disclaimer(version = "v1", sha256 = "hash1"))
        val second = conferenceGuestConsentInputOf(disclaimer(version = "v2", sha256 = "hash2"))
        assertEquals("v1", first.consentVersion)
        assertEquals("v2", second.consentVersion)
        assertEquals("hash1", first.consentSha256)
        assertEquals("hash2", second.consentSha256)
    }

    // ── conferenceTileLabel (design review D12 -- no isGuest parameter) ─

    @Test
    fun tileLabel_plainRemoteParticipant() {
        assertEquals("Anna Muster", conferenceTileLabel("Anna Muster", isLocal = false, isModerator = false))
    }

    @Test
    fun tileLabel_local() {
        assertEquals("Anna Muster (Sie)", conferenceTileLabel("Anna Muster", isLocal = true, isModerator = false))
    }

    @Test
    fun tileLabel_moderator() {
        assertEquals("Anna Muster · Moderator", conferenceTileLabel("Anna Muster", isLocal = false, isModerator = true))
    }

    @Test
    fun tileLabel_localModerator_bothSuffixesAppearOnce() {
        val label = conferenceTileLabel("Anna Muster", isLocal = true, isModerator = true)
        assertEquals("Anna Muster (Sie) · Moderator", label)
        assertEquals(1, Regex("\\(Sie\\)").findAll(label).count())
    }

    // ── conferenceInviteText ──────────────────────────────────────────────

    @Test
    fun inviteText_containsRoomIdTitleOrgAndRoute() {
        val text =
            conferenceInviteText(
                origin = "https://cloud.example.org",
                roomId = "11111111-1111-1111-1111-111111111111",
                roomTitle = "Vorstandssitzung",
                organizationName = "Partei der Vernunft",
            )
        assertTrue(text.contains("11111111-1111-1111-1111-111111111111"))
        assertTrue(text.contains("Vorstandssitzung"))
        assertTrue(text.contains("Partei der Vernunft"))
        assertTrue(text.contains("#/conference"))
        assertTrue(text.contains("https://cloud.example.org"))
    }

    /**
     * Security-audit fix: a `null` (or blank) [organizationName] must drop the "bei {org}" clause
     * entirely, never fall back to the room's own title -- the original bug (before this fix)
     * substituted `room.title` for a missing organization name, producing "Sie sind zur Besprechung
     * „X" bei X eingeladen." for every invite.
     */
    @Test
    fun inviteText_withNullOrganizationName_omitsHostedByClauseAndNeverSubstitutesTheRoomTitle() {
        val text =
            conferenceInviteText(
                origin = "https://cloud.example.org",
                roomId = "11111111-1111-1111-1111-111111111111",
                roomTitle = "Vorstandssitzung",
                organizationName = null,
            )
        assertTrue(text.contains("Vorstandssitzung"))
        assertFalse(text.contains(" bei "))

        val blankText =
            conferenceInviteText(
                origin = "https://cloud.example.org",
                roomId = "11111111-1111-1111-1111-111111111111",
                roomTitle = "Vorstandssitzung",
                organizationName = "  ",
            )
        assertFalse(blankText.contains(" bei "))
    }

    // ── Validation.looksLikeRoomId ───────────────────────────────────────

    @Test
    fun looksLikeRoomId_validUuid() {
        assertTrue(Validation.looksLikeRoomId("11111111-1111-1111-1111-111111111111"))
    }

    @Test
    fun looksLikeRoomId_toleratesSurroundingWhitespace() {
        assertTrue(Validation.looksLikeRoomId("  11111111-1111-1111-1111-111111111111  "))
    }

    @Test
    fun looksLikeRoomId_rejectsMalformed() {
        assertFalse(Validation.looksLikeRoomId(""))
        assertFalse(Validation.looksLikeRoomId("not-a-uuid"))
        assertFalse(Validation.looksLikeRoomId("11111111-1111-1111-1111-11111111111")) // one hex short
        assertFalse(Validation.looksLikeRoomId("11111111111111111111111111111111")) // no dashes
    }
}
