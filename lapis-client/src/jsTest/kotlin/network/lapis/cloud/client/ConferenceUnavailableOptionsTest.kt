package network.lapis.cloud.client

import dev.kilua.rpc.RpcSerialization
import network.lapis.cloud.shared.domain.ConferenceEventCaptureState
import network.lapis.cloud.shared.domain.ConferenceRecordingStatus
import network.lapis.cloud.shared.domain.ConferenceRoomDto
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.DocumentAccessLevel
import network.lapis.cloud.shared.rpc.ConferenceRecordingAlreadyActiveException
import network.lapis.cloud.shared.rpc.ConferenceRoomEndedException
import network.lapis.cloud.shared.rpc.EventRoomCaptureBlockedByAdminException
import network.lapis.cloud.shared.rpc.EventRoomCaptureBlockedByOrganizerException
import network.lapis.cloud.shared.rpc.EventRoomRecordingAccessLevelNotAllowedException
import network.lapis.cloud.shared.rpc.ForbiddenException
import network.lapis.cloud.shared.rpc.RateLimitedException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.158 -- the pure core of "an unavailable option never fails silently" on the call screen. */
class ConferenceUnavailableOptionsTest {
    private fun text(value: String?) = assertNotNull(value).removePrefix("###KvI18nS###")

    @Test
    fun anOrdinaryRoom_offersEveryAccessLevel_andAnEventRoomOnlyBoardAndAdministration() {
        assertEquals(DocumentAccessLevel.entries, conferenceRecordingAccessOptions(ConferenceEventCaptureState.NONE))
        listOf(
            ConferenceEventCaptureState.EVENT_ROOM,
            ConferenceEventCaptureState.EVENT_ROOM_CAPTURE_AVAILABLE,
            ConferenceEventCaptureState.EVENT_ROOM_CAPTURE_BLOCKED_BY_ADMIN,
            ConferenceEventCaptureState.EVENT_ROOM_CAPTURE_BLOCKED_BY_ORGANIZER,
        ).forEach { state ->
            assertEquals(
                listOf(DocumentAccessLevel.BOARD_ONLY, DocumentAccessLevel.ADMIN_ONLY),
                conferenceRecordingAccessOptions(state),
                state.name,
            )
        }
    }

    @Test
    fun onlyTheTwoBlockedStatesHaveAReason_andTheyDiffer() {
        assertNull(conferenceEventCaptureBlockedReason(ConferenceEventCaptureState.NONE))
        assertNull(conferenceEventCaptureBlockedReason(ConferenceEventCaptureState.EVENT_ROOM))
        assertNull(conferenceEventCaptureBlockedReason(ConferenceEventCaptureState.EVENT_ROOM_CAPTURE_AVAILABLE))
        val admin = text(conferenceEventCaptureBlockedReason(ConferenceEventCaptureState.EVENT_ROOM_CAPTURE_BLOCKED_BY_ADMIN))
        val organizer = text(conferenceEventCaptureBlockedReason(ConferenceEventCaptureState.EVENT_ROOM_CAPTURE_BLOCKED_BY_ORGANIZER))
        assertTrue(admin.contains("Administration"))
        assertTrue(organizer.contains("Organisatoren"))
        assertFalse(admin == organizer)
    }

    @Test
    fun setupRows_existOnlyInAnOrdinaryRoom() {
        assertTrue(conferenceSetupRowsVisible(ConferenceEventCaptureState.NONE))
        assertFalse(conferenceSetupRowsVisible(ConferenceEventCaptureState.EVENT_ROOM))
        assertFalse(conferenceSetupRowsVisible(ConferenceEventCaptureState.EVENT_ROOM_CAPTURE_BLOCKED_BY_ADMIN))
    }

    @Test
    fun aBlockedCapture_canNeverStart_whateverElseIsTrue() {
        assertTrue(recordingCanStart(canModerate = true, recordingAvailable = true, activeRecording = null))
        assertFalse(recordingCanStart(canModerate = true, recordingAvailable = true, activeRecording = null, captureBlocked = true))
        assertTrue(conferenceStreamCanStart(canModerate = true, streamingAvailable = true, activeStream = null))
        assertFalse(conferenceStreamCanStart(canModerate = true, streamingAvailable = true, activeStream = null, captureBlocked = true))
    }

    @Test
    fun theToggleViews_keepABlockedControlFocusable_andCarryTheReason() {
        val recording = recordingToggleView(null, canStart = false, blockedReason = "Grund")
        assertFalse(recording.disabled, "an unavailable option is aria-disabled, not disabled")
        assertEquals("Grund", recording.unavailableReason)
        val stream = streamToggleView(null, null, canStart = false, blockedReason = "Grund")
        assertFalse(stream.disabled)
        assertEquals("Grund", stream.unavailableReason)
        // without a reason the old behaviour: disabled while it cannot start
        assertTrue(recordingToggleView(null, canStart = false).disabled)
        assertNull(recordingToggleView(null, canStart = false).unavailableReason)
        assertTrue(streamToggleView(null, null, canStart = false).disabled)
        // a running capture is never "unavailable"
        assertNull(recordingToggleView(ConferenceRecordingStatus.RECORDING, canStart = false, blockedReason = "Grund").unavailableReason)
        assertNull(streamToggleView(ConferenceStreamStatus.LIVE, null, canStart = false, blockedReason = "Grund").unavailableReason)
    }

    @Test
    fun everyNewRefusal_hasItsOwnFixedGermanText() {
        assertTrue(text(typedRefusalText(EventRoomRecordingAccessLevelNotAllowedException())).contains("Vorstand"))
        assertTrue(text(typedRefusalText(EventRoomCaptureBlockedByAdminException())).contains("Administration"))
        assertTrue(text(typedRefusalText(EventRoomCaptureBlockedByOrganizerException())).contains("Organisatoren"))
        assertEquals("Es läuft bereits eine Aufzeichnung.", text(typedRefusalText(ConferenceRecordingAlreadyActiveException())))
        assertEquals("Diese Konferenz ist bereits beendet.", text(typedRefusalText(ConferenceRoomEndedException())))
        // not the generic "Keine Berechtigung" / conflict text
        assertNull(typedRefusalText(ForbiddenException()))
    }

    @Test
    fun theGeneralRateLimit_isNotWordedAsAPreview() {
        val message = text(typedRefusalText(RateLimitedException()))
        assertEquals("Zu viele Anfragen – bitte kurz warten.", message)
        assertFalse(message.contains("Vorschau"))
    }

    @Test
    fun aRoomFromAnOlderServer_isAnOrdinaryRoom_andTheFieldRoundTrips() {
        val base =
            """"id":"r","title":"T","description":"","livekitRoomName":"lc-1","createdByMemberId":"m","createdByDisplayName":"M",""" +
                """"createdAt":"2026-10-12T10:00:00","endedAt":null,"active":true,"maxParticipants":25,"liveParticipantCount":0,"myRole":"MODERATOR""""
        val json = RpcSerialization.getJson()
        val old = json.decodeFromString(ConferenceRoomDto.serializer(), "{$base}")
        assertEquals(ConferenceEventCaptureState.NONE, old.eventCapture)
        val blocked =
            json.decodeFromString(
                ConferenceRoomDto.serializer(),
                "{$base,\"eventCapture\":\"EVENT_ROOM_CAPTURE_BLOCKED_BY_ORGANIZER\"}",
            )
        assertEquals(ConferenceEventCaptureState.EVENT_ROOM_CAPTURE_BLOCKED_BY_ORGANIZER, blocked.eventCapture)
        assertEquals(
            blocked,
            json.decodeFromString(ConferenceRoomDto.serializer(), json.encodeToString(ConferenceRoomDto.serializer(), blocked)),
        )
    }
}
