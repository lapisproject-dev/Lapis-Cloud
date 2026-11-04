package network.lapis.cloud.client

import kotlinx.datetime.LocalDateTime
import network.lapis.cloud.shared.domain.ConferenceStreamDto
import network.lapis.cloud.shared.domain.ConferenceStreamFailureKind
import network.lapis.cloud.shared.domain.ConferenceStreamKeyHint
import network.lapis.cloud.shared.domain.ConferenceStreamKeyKind
import network.lapis.cloud.shared.domain.ConferenceStreamLatencyMode
import network.lapis.cloud.shared.domain.ConferenceStreamLayout
import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import network.lapis.cloud.shared.domain.ConferenceStreamStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatus
import network.lapis.cloud.shared.domain.ConferenceStreamTargetStatusDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun streamTarget(
    id: String = "d1",
    label: String = "Facebook",
    status: ConferenceStreamTargetStatus = ConferenceStreamTargetStatus.ACTIVE,
    failureKind: ConferenceStreamFailureKind? = null,
    attempt: Int = 0,
    keyKind: ConferenceStreamKeyKind = ConferenceStreamKeyKind.PER_BROADCAST,
    keyHint: ConferenceStreamKeyHint = ConferenceStreamKeyHint.FACEBOOK,
    failureReason: String? = null,
) = ConferenceStreamTargetStatusDto(
    destinationId = id,
    label = label,
    platform = ConferenceStreamPlatform.GENERIC_RTMP,
    status = status,
    retries = 0,
    failureReason = failureReason,
    keyKind = keyKind,
    failureKind = failureKind,
    autoRetryAttempt = attempt,
    keyHint = keyHint,
)

internal fun streamOf(
    status: ConferenceStreamStatus,
    vararg targets: ConferenceStreamTargetStatusDto,
) = ConferenceStreamDto(
    id = "s1",
    roomId = "r1",
    roomTitle = "Raum",
    status = status,
    layout = ConferenceStreamLayout.GRID,
    latencyMode = ConferenceStreamLatencyMode.STANDARD,
    startedByMemberId = "m1",
    startedByDisplayName = "Ada",
    startedAt = LocalDateTime(2026, 11, 4, 9, 0),
    pausedAt = null,
    endedAt = null,
    restartCount = 0,
    targets = targets.toList(),
    failureReason = null,
    pauseReason = null,
)

/** V1.9.275 -- the pure decisions of the target panel: state class, reason text, hint, who sees the button, the headline. */
class StreamTargetsPanelTest {
    @Test
    fun theStateClass_makesTheRetryAStateOfItsOwn() {
        assertEquals(StreamTargetStateClass.CONNECTING, streamTargetStateClass(streamTarget(status = ConferenceStreamTargetStatus.PENDING)))
        assertEquals(
            StreamTargetStateClass.RETRYING,
            streamTargetStateClass(streamTarget(status = ConferenceStreamTargetStatus.PENDING, attempt = 2)),
        )
        assertEquals(StreamTargetStateClass.LIVE, streamTargetStateClass(streamTarget()))
        assertEquals(StreamTargetStateClass.FAILED, streamTargetStateClass(streamTarget(status = ConferenceStreamTargetStatus.FAILED)))
        assertEquals(StreamTargetStateClass.FINISHED, streamTargetStateClass(streamTarget(status = ConferenceStreamTargetStatus.FINISHED)))
    }

    @Test
    fun theReasonText_followsTheKind_andNamesTheTarget() {
        fun text(kind: ConferenceStreamFailureKind?) =
            streamTargetFailureText(
                streamTarget(
                    status = ConferenceStreamTargetStatus.FAILED,
                    failureKind = kind,
                    failureReason = "Stream-Schlüssel dieser Sendung fehlt.",
                ),
                "Facebook",
            )
        assertTrue(assertNotNull(text(ConferenceStreamFailureKind.REJECTED)).startsWith("Facebook hat die Verbindung abgelehnt."))
        assertEquals("Facebook hat nicht rechtzeitig geantwortet. Verbinden Sie neu.", text(ConferenceStreamFailureKind.TIMEOUT))
        assertEquals("Die Verbindung zu Facebook ist fehlgeschlagen. Verbinden Sie neu.", text(ConferenceStreamFailureKind.CONNECTION))
        assertEquals("Die Übertragung zu Facebook ist abgebrochen. Verbinden Sie neu.", text(ConferenceStreamFailureKind.UNKNOWN))
        // no kind: the server's fixed text (e.g. "key missing"), sanitised
        assertEquals("Stream-Schlüssel dieser Sendung fehlt.", text(null))
    }

    @Test
    fun aForgedMarkerInALabel_doesNotSurviveIntoTheText() {
        val text =
            streamTargetFailureText(
                streamTarget(status = ConferenceStreamTargetStatus.FAILED, failureKind = ConferenceStreamFailureKind.TIMEOUT),
                "A###KvI18nS###B",
            )
        assertFalse(assertNotNull(text).contains("###KvI18nS###"))
    }

    @Test
    fun thePlatformHint_onlyForAOneTimeKeyOfFacebookOrX_thatMayBeUsedUp() {
        fun hint(
            hint: ConferenceStreamKeyHint = ConferenceStreamKeyHint.FACEBOOK,
            kind: ConferenceStreamFailureKind? = ConferenceStreamFailureKind.REJECTED,
            keyKind: ConferenceStreamKeyKind = ConferenceStreamKeyKind.PER_BROADCAST,
        ) = streamTargetPlatformHint(
            streamTarget(status = ConferenceStreamTargetStatus.FAILED, failureKind = kind, keyHint = hint, keyKind = keyKind),
        )
        assertTrue(assertNotNull(hint()).contains("Facebook"))
        assertTrue(
            assertNotNull(hint(hint = ConferenceStreamKeyHint.X, kind = ConferenceStreamFailureKind.UNKNOWN)).contains("X Media Studio"),
        )
        assertNull(hint(keyKind = ConferenceStreamKeyKind.PERSISTENT))
        assertNull(hint(kind = ConferenceStreamFailureKind.TIMEOUT))
        assertNull(hint(kind = ConferenceStreamFailureKind.CONNECTION))
        assertNull(hint(hint = ConferenceStreamKeyHint.OTHER))
        assertNull(streamTargetPlatformHint(streamTarget(status = ConferenceStreamTargetStatus.ACTIVE)))
    }

    @Test
    fun theReconnectButton_matrix() {
        for (manager in listOf(true, false)) {
            for (streamStatus in listOf(ConferenceStreamStatus.LIVE, ConferenceStreamStatus.PAUSED, ConferenceStreamStatus.STARTING)) {
                for (targetStatus in ConferenceStreamTargetStatus.entries) {
                    val stream = streamOf(streamStatus, streamTarget(status = targetStatus))
                    val expected =
                        manager && streamStatus == ConferenceStreamStatus.LIVE && targetStatus == ConferenceStreamTargetStatus.FAILED
                    assertEquals(
                        expected,
                        streamReconnectVisible(stream, stream.targets.single(), manager),
                        "manager=$manager stream=$streamStatus target=$targetStatus",
                    )
                }
            }
        }
    }

    @Test
    fun theHeadline_onlyFromTwoTargetsOn_andNeverForAnAllLiveStream() {
        val ok = streamTarget("a")
        val bad = streamTarget("b", status = ConferenceStreamTargetStatus.FAILED)
        assertEquals("Live auf 1 von 2 Zielen", streamLiveHeadline(streamOf(ConferenceStreamStatus.LIVE, ok, bad)))
        assertNull(streamLiveHeadline(streamOf(ConferenceStreamStatus.LIVE, ok, streamTarget("c"))))
        assertNull(streamLiveHeadline(streamOf(ConferenceStreamStatus.LIVE, bad)))
        assertNull(streamLiveHeadline(streamOf(ConferenceStreamStatus.PAUSED, ok, bad)))
    }

    @Test
    fun theAnnouncements_existOnlyForTheClassesThatMatter() {
        assertEquals("Facebook ist live.", streamTargetAnnouncement("Facebook", StreamTargetStateClass.LIVE))
        assertEquals("Facebook wird erneut verbunden.", streamTargetAnnouncement("Facebook", StreamTargetStateClass.RETRYING))
        assertEquals("Facebook ist fehlgeschlagen.", streamTargetAnnouncement("Facebook", StreamTargetStateClass.FAILED))
        assertNull(streamTargetAnnouncement("Facebook", StreamTargetStateClass.CONNECTING))
        assertNull(streamTargetAnnouncement("Facebook", StreamTargetStateClass.FINISHED))
    }

    @Test
    fun theLimitsAreTheSharedOnes() {
        assertEquals(3, network.lapis.cloud.shared.domain.ConferenceStreamRetryLimits.MAX_AUTO_ATTEMPTS)
    }

    @Test
    fun aModeratorPollsALiveStream_everyoneElseDoesNot() {
        val live = streamOf(ConferenceStreamStatus.LIVE, streamTarget())
        assertFalse(conferenceStreamNeedsPoll(live))
        assertTrue(conferenceStreamNeedsPoll(live, canManage = true))
        assertFalse(conferenceStreamNeedsPoll(streamOf(ConferenceStreamStatus.PAUSED, streamTarget()), canManage = true))
        assertTrue(conferenceStreamNeedsPollWithBallot(live, secretBallotOpen = false, canManage = true))
        assertFalse(conferenceStreamNeedsPollWithBallot(live, secretBallotOpen = false))
    }
}
