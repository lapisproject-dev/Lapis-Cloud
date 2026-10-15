package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.VideoOverlayItemDto
import network.lapis.cloud.shared.domain.VideoOverlayKind
import network.lapis.cloud.shared.domain.VideoOverlayRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.129 -- the pure logic of the video overlays: settings, persistence, resolution against the library, the scene rule, the state machine. */
class ConferenceVideoOverlaysTest {
    private val logoId = "a1111111-1111-4111-8111-111111111111"
    private val frameId = "b2222222-2222-4222-8222-222222222222"
    private val token43 = "A".repeat(43)

    private fun logo(
        id: String = logoId,
        name: String = "Logo",
    ) = VideoOverlayItemDto(id, VideoOverlayKind.LOGO, name, 600, 300, "/api/video-overlays/$token43")

    private fun frame(id: String = frameId) =
        VideoOverlayItemDto(id, VideoOverlayKind.FRAME, "Rahmen", 1920, 1080, "/api/video-overlays/${"B".repeat(43)}")

    private val instanceLogo =
        VideoOverlayItemDto(
            VideoOverlayRules.INSTANCE_LOGO_ID,
            VideoOverlayKind.LOGO,
            "PdV",
            0,
            0,
            "/api/branding/logo",
            instanceLogo = true,
        )

    private val sample =
        VideoOverlaySettings(
            enabled = true,
            corners =
                mapOf(
                    OverlayCorner.TOP_RIGHT to CornerSlot(OverlayContent.Library(logoId), OverlaySize.LARGE, OverlayOpacity.STRONG),
                    OverlayCorner.BOTTOM_RIGHT to CornerSlot(OverlayContent.OwnName, OverlaySize.SMALL, OverlayOpacity.LIGHT),
                    OverlayCorner.TOP_LEFT to CornerSlot(OverlayContent.Library(VideoOverlayRules.INSTANCE_LOGO_ID)),
                ),
            frame = FrameSlot(frameId, OverlayOpacity.STRONG),
        )

    // ── persistence ───────────────────────────────────────────────────────────────

    @Test
    fun persistence_roundTripsEveryField() {
        val raw = videoOverlayPersistValue(sample)
        assertNotNull(raw)
        assertEquals(sample, parseStoredVideoOverlaySettings(raw))
    }

    @Test
    fun persistence_theDefaultRemovesTheKey() {
        assertNull(videoOverlayPersistValue(VideoOverlaySettings()))
        assertNull(videoOverlayPersistValue(VideoOverlaySettings(enabled = false, corners = mapOf(OverlayCorner.TOP_LEFT to CornerSlot()))))
        assertEquals(VideoOverlaySettings(), parseStoredVideoOverlaySettings(null))
    }

    @Test
    fun persistence_switchedOffButConfiguredIsKept() {
        val off = sample.copy(enabled = false)
        assertEquals(off, parseStoredVideoOverlaySettings(videoOverlayPersistValue(off)))
    }

    @Test
    fun persistence_everyManipulatedValueIsTheDefault() {
        val bad =
            listOf(
                "",
                "not json",
                "[]",
                "{}",
                """{"v":2,"on":true}""",
                """{"v":1}""",
                """{"v":1,"on":"yes"}""",
                """{"v":1,"on":true,"c":{"xx":{"k":"name","s":"m","o":"100"}}}""",
                """{"v":1,"on":true,"c":{"tl":{"k":"name","s":"huge","o":"100"}}}""",
                """{"v":1,"on":true,"c":{"tl":{"k":"name","s":"m","o":"55"}}}""",
                """{"v":1,"on":true,"c":{"tl":{"k":"lib","a":"${logoId.uppercase()}","s":"m","o":"100"}}}""",
                """{"v":1,"on":true,"c":{"tl":{"k":"lib","a":"javascript:alert(1)","s":"m","o":"100"}}}""",
                """{"v":1,"on":true,"c":{"tl":{"k":"lib","a":"/etc/passwd","s":"m","o":"100"}}}""",
                """{"v":1,"on":true,"c":{"tl":{"k":"lib","a":"https://evil.example/x.png","s":"m","o":"100"}}}""",
                """{"v":1,"on":true,"c":{"tl":{"k":"lib","a":"instance-logo ","s":"m","o":"100"}}}""",
                """{"v":1,"on":true,"c":{"tl":{"k":"lib","s":"m","o":"100"}}}""",
                """{"v":1,"on":true,"c":{"tl":"x"}}""",
                """{"v":1,"on":true,"c":[]}""",
                """{"v":1,"on":true,"f":{"a":"../x","o":"100"}}""",
                """{"v":1,"on":true,"f":{"a":"$frameId","o":"1"}}""",
            )
        for (raw in bad) assertEquals(VideoOverlaySettings(), parseStoredVideoOverlaySettings(raw), "for: $raw")
    }

    @Test
    fun assetIds_onlyCanonicalUuidOrTheInstanceLogo() {
        assertTrue(isValidOverlayAssetId(logoId))
        assertTrue(isValidOverlayAssetId(VideoOverlayRules.INSTANCE_LOGO_ID))
        for (bad in listOf("", " $logoId", "$logoId ", logoId.uppercase(), "instance-logo ", "x", "../$logoId", "custom:$logoId")) {
            assertFalse(isValidOverlayAssetId(bad), bad)
        }
    }

    // ── the one place a URL is made ──────────────────────────────────────────────

    @Test
    fun assetPath_onlyTheTokenPathAndTheBrandingLogo() {
        assertEquals("/api/video-overlays/$token43", videoOverlayAssetPath(logo()))
        assertEquals("/api/branding/logo", videoOverlayAssetPath(instanceLogo))
        assertNull(videoOverlayAssetPath(logo().copy(imagePath = "https://evil.example/a.png")))
        assertNull(videoOverlayAssetPath(logo().copy(imagePath = "javascript:alert(1)")))
        assertNull(videoOverlayAssetPath(logo().copy(imagePath = "/api/video-overlays/../../etc/passwd")))
        assertNull(videoOverlayAssetPath(logo().copy(imagePath = "/api/video-overlays/${"A".repeat(42)}")))
        assertNull(videoOverlayAssetPath(logo().copy(imagePath = "/api/video-overlays/${"A".repeat(44)}")))
        assertNull(videoOverlayAssetPath(logo().copy(imagePath = "//evil.example/api/video-overlays/$token43")))
        assertNull(videoOverlayAssetPath(logo().copy(id = "not-an-id")))
        // The instance logo id with a stored path, and a stored element claiming to be the instance logo, are both refused.
        assertNull(videoOverlayAssetPath(instanceLogo.copy(imagePath = "/api/video-overlays/$token43")))
        assertNull(videoOverlayAssetPath(logo().copy(instanceLogo = true)))
    }

    // ── resolution against the library ───────────────────────────────────────────

    @Test
    fun resolve_unknownElementsBecomeEmptyAndAMissingFrameDisappears() {
        val resolved = resolveVideoOverlaySettings(sample, listOf(logo()))
        assertEquals(OverlayContent.Library(logoId), resolved.slot(OverlayCorner.TOP_RIGHT).content)
        assertEquals(OverlayContent.Empty, resolved.slot(OverlayCorner.TOP_LEFT).content) // the instance logo is not in this library
        assertEquals(OverlayContent.OwnName, resolved.slot(OverlayCorner.BOTTOM_RIGHT).content)
        assertNull(resolved.frame)
        val withAll = resolveVideoOverlaySettings(sample, listOf(logo(), frame(), instanceLogo))
        assertEquals(OverlayContent.Library(VideoOverlayRules.INSTANCE_LOGO_ID), withAll.slot(OverlayCorner.TOP_LEFT).content)
        assertEquals(frameId, withAll.frame?.assetId)
    }

    @Test
    fun resolve_aKindMismatchIsTreatedAsMissing() {
        // A frame id in a corner and a logo id as the frame are no valid choices.
        val crossed =
            VideoOverlaySettings(
                enabled = true,
                corners = mapOf(OverlayCorner.TOP_LEFT to CornerSlot(OverlayContent.Library(frameId))),
                frame = FrameSlot(logoId),
            )
        val resolved = resolveVideoOverlaySettings(crossed, listOf(logo(), frame()))
        assertEquals(OverlayContent.Empty, resolved.slot(OverlayCorner.TOP_LEFT).content)
        assertNull(resolved.frame)
    }

    // ── the scene rule ───────────────────────────────────────────────────────────

    private fun scene(
        settings: VideoOverlaySettings = sample,
        library: List<VideoOverlayItemDto> = listOf(logo(), frame(), instanceLogo),
        offered: Boolean = true,
        availability: ConferenceBackgroundAvailability = ConferenceBackgroundAvailability.AVAILABLE,
        name: String = "Erika Mustermann",
    ) = effectiveVideoOverlayScene(settings, library, offered, availability, name)

    @Test
    fun scene_isNullWheneverNothingWouldBeDrawn() {
        assertNotNull(scene())
        assertNull(scene(offered = false))
        assertNull(scene(settings = sample.copy(enabled = false)))
        assertNull(scene(availability = ConferenceBackgroundAvailability.UNSUPPORTED_BROWSER))
        assertNull(scene(availability = ConferenceBackgroundAvailability.UNSUPPORTED_IN_APP_WEBVIEW))
        assertNull(scene(settings = VideoOverlaySettings(enabled = true)))
        assertNull(scene(settings = VideoOverlaySettings(enabled = true, corners = mapOf(OverlayCorner.TOP_LEFT to CornerSlot()))))
        assertNull(scene(library = emptyList(), settings = sample.copy(corners = emptyMap(), frame = null)))
    }

    @Test
    fun scene_aBlankNameAsTheOnlyContentDrawsNothing() {
        val onlyName = VideoOverlaySettings(enabled = true, corners = mapOf(OverlayCorner.TOP_LEFT to CornerSlot(OverlayContent.OwnName)))
        assertNotNull(scene(settings = onlyName, name = "Erika"))
        assertNull(scene(settings = onlyName, name = "   "))
        assertNull(scene(settings = onlyName, name = ""))
        assertEquals(
            "Erika",
            (scene(settings = onlyName, name = "  Erika  ")!!.corners.getValue(OverlayCorner.TOP_LEFT) as SceneCorner.Name).text,
        )
    }

    @Test
    fun scene_carriesOnlyWhatIsResolvable() {
        val s = scene(library = listOf(logo(), instanceLogo))!!
        assertEquals(setOf(OverlayCorner.TOP_LEFT, OverlayCorner.TOP_RIGHT, OverlayCorner.BOTTOM_RIGHT), s.corners.keys)
        assertNull(s.frame) // the frame is not in this library
        val full = scene()!!
        assertEquals(OverlayOpacity.STRONG, full.frame?.opacity)
        val topRight = full.corners.getValue(OverlayCorner.TOP_RIGHT) as SceneCorner.Image
        assertEquals(OverlaySize.LARGE, topRight.size)
        assertEquals(OverlayOpacity.STRONG, topRight.opacity)
    }

    @Test
    fun scene_anItemWithABadPathIsNeverDrawn() {
        val evil = logo().copy(imagePath = "https://evil.example/x.png")
        val s =
            scene(
                library = listOf(evil, frame()),
                settings =
                    sample.copy(
                        corners =
                            mapOf(
                                OverlayCorner.TOP_RIGHT to CornerSlot(OverlayContent.Library(logoId)),
                            ),
                    ),
            )!!
        assertTrue(s.corners.isEmpty())
        assertNotNull(s.frame)
    }

    // ── state machine ────────────────────────────────────────────────────────────

    @Test
    fun reducer_everyEventAndAFailureIsNotifiedOncePerCause() {
        var state = VideoOverlayState()
        state = videoOverlayReduce(state, VideoOverlayEvent.SceneRequested)
        assertEquals(VideoOverlayPhase.APPLYING, state.phase)
        state = videoOverlayReduce(state, VideoOverlayEvent.SceneActive)
        assertEquals(VideoOverlayPhase.ACTIVE, state.phase)
        assertTrue(videoOverlayShouldNotify(state, VideoOverlayFailure.LOAD_FAILED))
        state = videoOverlayReduce(state, VideoOverlayEvent.Failed(VideoOverlayFailure.LOAD_FAILED))
        assertEquals(VideoOverlayPhase.FAILED_FALLBACK, state.phase)
        assertFalse(videoOverlayShouldNotify(state, VideoOverlayFailure.LOAD_FAILED))
        assertTrue(videoOverlayShouldNotify(state, VideoOverlayFailure.DRAW_FAILED))
        state = videoOverlayReduce(state, VideoOverlayEvent.SceneCleared)
        assertEquals(VideoOverlayPhase.OFF, state.phase)
        assertFalse(videoOverlayShouldNotify(state, VideoOverlayFailure.LOAD_FAILED)) // survives a clear: one message per session
    }

    // ── processor plan ───────────────────────────────────────────────────────────

    @Test
    fun processorKind_offWithoutOverlayIsNone_offWithOverlayIsOverlayOnly_anyEffectIsSegmenting() {
        assertEquals(VideoProcessorKind.NONE, videoProcessorKindFor(CONFERENCE_BACKGROUND_OFF, pipelineActive = false))
        assertEquals(VideoProcessorKind.LIGHTWEIGHT, videoProcessorKindFor(CONFERENCE_BACKGROUND_OFF, pipelineActive = true))
        for (effect in ConferenceBackgroundEffect.entries.filter { it != ConferenceBackgroundEffect.OFF }) {
            val choice = ConferenceBackgroundChoice.BuiltIn(effect)
            assertEquals(VideoProcessorKind.SEGMENTING, videoProcessorKindFor(choice, pipelineActive = false))
            assertEquals(VideoProcessorKind.SEGMENTING, videoProcessorKindFor(choice, pipelineActive = true))
        }
        assertEquals(VideoProcessorKind.SEGMENTING, videoProcessorKindFor(ConferenceBackgroundChoice.Custom(logoId), pipelineActive = true))
    }
}
