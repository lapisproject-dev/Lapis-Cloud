package network.lapis.cloud.client

import network.lapis.cloud.client.livekit.ConferenceModerationSignal
import network.lapis.cloud.client.livekit.conferenceModerationPacket
import network.lapis.cloud.client.livekit.microphoneAllowedByPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** V1.9.108 -- the pure rules of the moderator audio controls: states, the roster action, the confirmation texts, the packet decoder. */
class ConferenceModerationAudioTest {
    private val creator = "11111111-1111-1111-1111-111111111111"
    private val me = "22222222-2222-2222-2222-222222222222"
    private val other = "33333333-3333-3333-3333-333333333333"

    private fun bytes(text: String) = text.encodeToByteArray()

    /** `tr(...)` yields a marker-prefixed string that KVision resolves while rendering; a test reads the plain text. */
    private fun resolved(text: String) = text.removePrefix("###KvI18nS###")

    // -- the state of one microphone --------------------------------------------------------------------------------------------------

    @Test
    fun micState_openOnlyWithAnOpenTrack() {
        assertEquals(ConferenceMicState.OPEN, conferenceMicState(hasMicTrack = true, micMuted = false, mutedByModeration = false))
        // a stale moderation mark never hides an open microphone
        assertEquals(ConferenceMicState.OPEN, conferenceMicState(hasMicTrack = true, micMuted = false, mutedByModeration = true))
    }

    @Test
    fun micState_mutedAndMutedByModeration() {
        assertEquals(ConferenceMicState.MUTED, conferenceMicState(hasMicTrack = true, micMuted = true, mutedByModeration = false))
        assertEquals(ConferenceMicState.MUTED, conferenceMicState(hasMicTrack = false, micMuted = false, mutedByModeration = false))
        assertEquals(
            ConferenceMicState.MUTED_BY_MODERATION,
            conferenceMicState(hasMicTrack = true, micMuted = true, mutedByModeration = true),
        )
        assertEquals(
            ConferenceMicState.MUTED_BY_MODERATION,
            conferenceMicState(hasMicTrack = false, micMuted = false, mutedByModeration = true),
        )
    }

    @Test
    fun micStateTexts_areTheThreeWordings() {
        assertEquals("Mikrofon an", resolved(conferenceMicStateText(ConferenceMicState.OPEN)))
        assertEquals("Stumm", resolved(conferenceMicStateText(ConferenceMicState.MUTED)))
        assertEquals("Von der Moderation stummgeschaltet", resolved(conferenceMicStateText(ConferenceMicState.MUTED_BY_MODERATION)))
    }

    // -- the roster action ------------------------------------------------------------------------------------------------------------

    private fun action(
        canModerate: Boolean = true,
        target: String = other,
        targetPrivileged: Boolean? = null,
        callerPrivileged: Boolean = false,
        state: ConferenceMicState = ConferenceMicState.OPEN,
    ) = conferenceModerationRowAction(canModerate, target, me, creator, targetPrivileged, callerPrivileged, state)

    @Test
    fun rowAction_onlyForTheModeration() {
        assertEquals(ConferenceRosterAction.NONE, action(canModerate = false))
        assertEquals(ConferenceRosterAction.MUTE, action())
    }

    @Test
    fun rowAction_muteOnlyWithAnOpenMicrophone_askOtherwise() {
        assertEquals(ConferenceRosterAction.MUTE, action(state = ConferenceMicState.OPEN))
        assertEquals(ConferenceRosterAction.ASK, action(state = ConferenceMicState.MUTED))
        assertEquals(ConferenceRosterAction.ASK, action(state = ConferenceMicState.MUTED_BY_MODERATION))
    }

    @Test
    fun rowAction_neverForTheCreatorOrOneself() {
        assertEquals(ConferenceRosterAction.NONE, action(target = creator))
        assertEquals(ConferenceRosterAction.NONE, action(target = me))
    }

    @Test
    fun rowAction_peerRule_isKnownOnlyWhenTheClientKnows() {
        // unknown (the usual case: no role oracle): the button is shown, the server refuses uniformly
        assertEquals(ConferenceRosterAction.MUTE, action(targetPrivileged = null))
        assertEquals(ConferenceRosterAction.NONE, action(targetPrivileged = true, callerPrivileged = false))
        assertEquals(ConferenceRosterAction.MUTE, action(targetPrivileged = true, callerPrivileged = true))
        assertEquals(ConferenceRosterAction.MUTE, action(targetPrivileged = false, callerPrivileged = false))
    }

    // -- the "Alle stummschalten" hint and texts --------------------------------------------------------------------------------------

    @Test
    fun muteAllCandidates_countsOpenMicrophonesWithoutCreatorAndSelf() {
        val states =
            mapOf(
                creator to ConferenceMicState.OPEN,
                me to ConferenceMicState.OPEN,
                other to ConferenceMicState.OPEN,
                "44444444-4444-4444-4444-444444444444" to ConferenceMicState.MUTED,
                "55555555-5555-5555-5555-555555555555" to ConferenceMicState.MUTED_BY_MODERATION,
                "66666666-6666-6666-6666-666666666666" to ConferenceMicState.OPEN,
            )
        assertEquals(2, conferenceMuteAllCandidates(states, creator, me))
        assertEquals(0, conferenceMuteAllCandidates(emptyMap(), creator, me))
    }

    @Test
    fun confirmTexts_areSingularAndPlural() {
        assertEquals("1 Person stummschalten? Moderatoren sind ausgenommen.", muteAllConfirmText(1))
        assertEquals("7 Personen stummschalten? Moderatoren sind ausgenommen.", muteAllConfirmText(7))
        assertEquals("Das offene Mikrofon von 1 Person wird stummgeschaltet.", lockOpenMicrophonesText(1))
        assertEquals("Die offenen Mikrofone von 4 Personen werden stummgeschaltet.", lockOpenMicrophonesText(4))
    }

    // -- the microphone button --------------------------------------------------------------------------------------------------------

    @Test
    fun micButton_lockedIsDisabledWithTheLockAndTheReason() {
        val locked = conferenceMicButtonView(micEnabled = false, permitted = false)
        assertEquals("fas fa-lock", locked.iconCss)
        assertEquals("Die Moderation hat das Einschalten gesperrt", resolved(locked.label))
        assertEquals(true, locked.disabled)
        // a lock wins over a stale "on"
        assertEquals(true, conferenceMicButtonView(micEnabled = true, permitted = false).disabled)
        val on = conferenceMicButtonView(micEnabled = true, permitted = true)
        assertEquals("fas fa-microphone", on.iconCss)
        assertEquals(false, on.disabled)
        val off = conferenceMicButtonView(micEnabled = false, permitted = true)
        assertEquals("fas fa-microphone-slash", off.iconCss)
        assertEquals(true, off.danger)
    }

    @Test
    fun enteringFullscreenWhileRenaming_returnsToTheViewState() {
        assertEquals(true, conferenceTitleReturnsToView(fullscreen = true, editing = true))
        assertEquals(false, conferenceTitleReturnsToView(fullscreen = true, editing = false))
        assertEquals(false, conferenceTitleReturnsToView(fullscreen = false, editing = true))
    }

    // -- the packet decoder -----------------------------------------------------------------------------------------------------------

    @Test
    fun packet_acceptsExactlyTheFiveServerKinds() {
        assertEquals(ConferenceModerationSignal.Muted, conferenceModerationPacket(false, bytes("""{"v":1,"k":"muted"}""")))
        assertEquals(ConferenceModerationSignal.UnmuteRequest, conferenceModerationPacket(false, bytes("""{"v":1,"k":"unmute_request"}""")))
        assertEquals(ConferenceModerationSignal.Lock, conferenceModerationPacket(false, bytes("""{"v":1,"k":"lock"}""")))
        assertEquals(ConferenceModerationSignal.Unlock, conferenceModerationPacket(false, bytes("""{"v":1,"k":"unlock"}""")))
        assertEquals(
            ConferenceModerationSignal.MutedPeer(other),
            conferenceModerationPacket(false, bytes("""{"v":1,"k":"muted_peer","id":"$other"}""")),
        )
    }

    @Test
    fun packet_neverFromAParticipant() {
        // participants hold can_publish_data: anything on the topic with a sender is a forgery
        assertNull(conferenceModerationPacket(true, bytes("""{"v":1,"k":"muted"}""")))
        assertNull(conferenceModerationPacket(true, bytes("""{"v":1,"k":"unmute_request"}""")))
    }

    @Test
    fun packet_rejectsTooLongEmptyAndMalformed() {
        assertNull(conferenceModerationPacket(false, ByteArray(0)))
        assertNull(conferenceModerationPacket(false, ByteArray(129) { 'a'.code.toByte() }))
        assertNull(conferenceModerationPacket(false, bytes("not json")))
        assertNull(conferenceModerationPacket(false, bytes("[1,2]")))
        assertNull(conferenceModerationPacket(false, bytes("{}")))
        // exactly 128 bytes of padding inside a valid object is still too loose (an extra key)
        assertNull(conferenceModerationPacket(false, bytes("""{"v":1,"k":"muted","x":"${"y".repeat(100)}"}""")))
    }

    @Test
    fun packet_rejectsUnknownKindWrongVersionAndExtraKeys() {
        assertNull(conferenceModerationPacket(false, bytes("""{"v":1,"k":"unmute"}""")))
        assertNull(conferenceModerationPacket(false, bytes("""{"v":1,"k":"force_unmute"}""")))
        assertNull(conferenceModerationPacket(false, bytes("""{"v":2,"k":"muted"}""")))
        assertNull(conferenceModerationPacket(false, bytes("""{"v":"1","k":"muted"}""")))
        assertNull(conferenceModerationPacket(false, bytes("""{"k":"muted"}""")))
        assertNull(conferenceModerationPacket(false, bytes("""{"v":1,"k":"muted","id":"$other"}""")))
        assertNull(conferenceModerationPacket(false, bytes("""{"v":1,"k":3}""")))
    }

    @Test
    fun packet_mutedPeerNeedsAWellFormedUuid() {
        assertNull(conferenceModerationPacket(false, bytes("""{"v":1,"k":"muted_peer"}""")))
        assertNull(conferenceModerationPacket(false, bytes("""{"v":1,"k":"muted_peer","id":"not-a-uuid"}""")))
        assertNull(conferenceModerationPacket(false, bytes("""{"v":1,"k":"muted_peer","id":"<script>"}""")))
        assertNull(conferenceModerationPacket(false, bytes("""{"v":1,"k":"muted_peer","id":7}""")))
    }

    // -- the permission ---------------------------------------------------------------------------------------------------------------

    @Test
    fun permission_microphoneIsSourceTwoAndAnEmptyListMeansEverything() {
        // TrackSource of @livekit/protocol: UNKNOWN 0, CAMERA 1, MICROPHONE 2, SCREEN_SHARE 3, SCREEN_SHARE_AUDIO 4
        assertEquals(true, microphoneAllowedByPermission(canPublish = true, publishSources = emptyList()))
        assertEquals(true, microphoneAllowedByPermission(canPublish = true, publishSources = listOf(1, 2, 3, 4)))
        assertEquals(false, microphoneAllowedByPermission(canPublish = true, publishSources = listOf(1, 3, 4)))
        assertEquals(false, microphoneAllowedByPermission(canPublish = false, publishSources = emptyList()))
    }
}
