package network.lapis.cloud.client

import network.lapis.cloud.shared.domain.ConferenceStreamPlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.207 -- the stream destination preset catalog and the pure selection / switch / label helpers. */
class ConferenceStreamPresetsTest {
    @Test
    fun catalog_hasTheSixPresetsInOrder_withUniqueIds() {
        val ids = conferenceStreamPresets.map { it.id }
        assertEquals(listOf("facebook", "telegram", "linkedin", "rumble", "kick", "vimeo"), ids)
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it == it.lowercase() })
    }

    @Test
    fun everyPreset_hasAValidServerUrlOrIsPerEvent_andAGenderFreeHint() {
        conferenceStreamPresets.forEach { p ->
            val url = p.serverUrl
            assertTrue(
                (url != null && conferenceStreamUrlLooksValid(url)) || (p.perEventUrl && url == null),
                p.id,
            )
            val hint = p.keyHint()
            assertTrue(hint.isNotBlank(), p.id)
            listOf("*innen", ":innen", "_innen", "/innen").forEach { assertFalse(hint.contains(it), "${p.id}: $it") }
        }
    }

    @Test
    fun presetForUrl_matchesByHost_caseInsensitive_andTelegramDataCenters() {
        assertEquals("facebook", conferenceStreamPresetForUrl("rtmps://live-api-s.facebook.com:443/rtmp/")?.id)
        assertEquals("telegram", conferenceStreamPresetForUrl("rtmps://dc2-1.rtmp.t.me/s/")?.id)
        assertEquals("telegram", conferenceStreamPresetForUrl("RTMPS://DC4-1.RTMP.T.ME/s/")?.id)
        assertNull(conferenceStreamPresetForUrl("rtmps://ingest.example.org/live"))
        assertNull(conferenceStreamPresetForUrl("https://live-api-s.facebook.com/"))
        assertNull(conferenceStreamPresetForUrl("rtmps://evil.example.org/dc2-1.rtmp.t.me"))
    }

    @Test
    fun destinationLabel_usesTheBrandOnlyForGenericRtmp() {
        assertEquals(
            "Facebook Live",
            conferenceStreamDestinationLabel(ConferenceStreamPlatform.GENERIC_RTMP, "rtmps://live-api-s.facebook.com/rtmp/"),
        )
        assertEquals(
            "Generisches RTMP",
            conferenceStreamDestinationLabel(ConferenceStreamPlatform.GENERIC_RTMP, "rtmps://ingest.example.org/x"),
        )
        assertEquals("X", conferenceStreamDestinationLabel(ConferenceStreamPlatform.X, "rtmps://live-api-s.facebook.com/rtmp/"))
    }

    @Test
    fun selection_mapsPresetEnumAndUnknownValues() {
        val (fbPlatform, fb) = conferenceStreamSelection("preset:facebook")
        assertEquals(ConferenceStreamPlatform.GENERIC_RTMP, fbPlatform)
        assertEquals("facebook", assertNotNull(fb).id)
        assertEquals(ConferenceStreamPlatform.YOUTUBE to null, conferenceStreamSelection("YOUTUBE"))
        assertEquals(ConferenceStreamPlatform.GENERIC_RTMP to null, conferenceStreamSelection("preset:nope"))
        assertEquals(ConferenceStreamPlatform.GENERIC_RTMP to null, conferenceStreamSelection("BOGUS"))
        assertEquals(ConferenceStreamPlatform.GENERIC_RTMP to null, conferenceStreamSelection(null))
    }

    @Test
    fun options_listPresetsFirst_thenThePlatformsInFixedOrder() {
        val values = conferenceStreamPlatformOptions().map { it.first }
        assertEquals(
            listOf(
                "preset:facebook",
                "preset:telegram",
                "preset:linkedin",
                "preset:rumble",
                "preset:kick",
                "preset:vimeo",
                "YOUTUBE",
                "TWITCH",
                "X",
                "PEERTUBE",
                "OWNCAST",
                "GENERIC_RTMP",
            ),
            values,
        )
    }

    @Test
    fun switch_replacesPrefills_keepsTypedUrls_andClearsForPerEventPresets() {
        val facebook = assertNotNull(conferenceStreamPresetById("facebook"))
        val youtube = "rtmp://a.rtmp.youtube.com/live2"
        // a platform prefill is cleared when a per-event preset is chosen
        assertEquals("", conferenceStreamUrlAfterSelectionSwitch(youtube, ConferenceStreamPlatform.GENERIC_RTMP, facebook))
        // a typed address is never touched
        assertNull(
            conferenceStreamUrlAfterSelectionSwitch("rtmps://ingest.example.org/live", ConferenceStreamPlatform.GENERIC_RTMP, facebook),
        )
        // empty field and a per-event preset: nothing to prefill
        assertNull(conferenceStreamUrlAfterSelectionSwitch("", ConferenceStreamPlatform.GENERIC_RTMP, facebook))
        // from a preset back to YouTube: the platform prefill is applied to an empty field
        assertEquals(youtube, conferenceStreamUrlAfterSelectionSwitch("", ConferenceStreamPlatform.YOUTUBE, null))
        // the old entry point behaves exactly as before
        assertEquals("", conferenceStreamUrlAfterPlatformSwitch(youtube, ConferenceStreamPlatform.X))
    }

    @Test
    fun placeholder_isShownForXAndPerEventPresets() {
        val facebook = assertNotNull(conferenceStreamPresetById("facebook"))
        assertEquals("rtmps://…", conferenceStreamUrlPlaceholder(ConferenceStreamPlatform.GENERIC_RTMP, facebook))
        assertEquals("rtmps://…", conferenceStreamUrlPlaceholder(ConferenceStreamPlatform.X))
        assertNull(conferenceStreamUrlPlaceholder(ConferenceStreamPlatform.GENERIC_RTMP))
    }
}
