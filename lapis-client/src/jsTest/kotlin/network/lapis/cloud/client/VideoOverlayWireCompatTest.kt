package network.lapis.cloud.client

import dev.kilua.rpc.RpcSerialization
import network.lapis.cloud.shared.domain.ConferenceAvailabilityDto
import network.lapis.cloud.shared.domain.ConferenceJoinTokenDto
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import network.lapis.cloud.shared.domain.VideoOverlayItemDto
import network.lapis.cloud.shared.domain.VideoOverlayKind
import network.lapis.cloud.shared.domain.VideoOverlayLibraryDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.129 -- wire compatibility of the `videoOverlaysOffered` field on the three DTOs that carry it, with the JSON configuration Kilua RPC
 * really uses: an answer from an older server (no field) means "off", a new server's value round-trips. Plus the library DTO itself.
 */
class VideoOverlayWireCompatTest {
    private val json get() = RpcSerialization.getJson()

    private val token =
        """"roomId":"r","livekitRoomName":"lc-1","serverUrl":"wss://x","token":"t","identity":"m","displayName":"M",""" +
            """"role":"PARTICIPANT","expiresAt":"2026-10-13T10:00:00""""
    private val availability = """"enabled":true,"serverUrl":"wss://x","maxParticipants":25"""
    private val settings =
        """"id":"o","name":"V","street":null,"postalCode":null,"city":null,"country":null,"bankIban":null,"bankBic":null,""" +
            """"taxExemptionAuthority":null,"taxExemptionDate":null"""

    @Test
    fun answersFromAnOlderServer_meanOff() {
        assertFalse(json.decodeFromString(ConferenceJoinTokenDto.serializer(), "{$token}").videoOverlaysOffered)
        assertFalse(json.decodeFromString(ConferenceAvailabilityDto.serializer(), "{$availability}").videoOverlaysOffered)
        assertFalse(json.decodeFromString(OrganizationSettingsDto.serializer(), "{$settings}").videoOverlaysOffered)
    }

    @Test
    fun aNewServersValue_isRead_andRoundTrips() {
        val t = json.decodeFromString(ConferenceJoinTokenDto.serializer(), "{$token,\"videoOverlaysOffered\":true}")
        val a = json.decodeFromString(ConferenceAvailabilityDto.serializer(), "{$availability,\"videoOverlaysOffered\":true}")
        val o = json.decodeFromString(OrganizationSettingsDto.serializer(), "{$settings,\"videoOverlaysOffered\":true}")
        assertTrue(t.videoOverlaysOffered && a.videoOverlaysOffered && o.videoOverlaysOffered)
        assertEquals(
            t,
            json.decodeFromString(ConferenceJoinTokenDto.serializer(), json.encodeToString(ConferenceJoinTokenDto.serializer(), t)),
        )
        assertEquals(
            a,
            json.decodeFromString(ConferenceAvailabilityDto.serializer(), json.encodeToString(ConferenceAvailabilityDto.serializer(), a)),
        )
        assertEquals(
            o,
            json.decodeFromString(OrganizationSettingsDto.serializer(), json.encodeToString(OrganizationSettingsDto.serializer(), o)),
        )
    }

    @Test
    fun theLibraryDto_roundTrips_andTheInstanceFlagDefaultsToFalse() {
        val item =
            VideoOverlayItemDto(
                "11111111-1111-4111-8111-111111111111",
                VideoOverlayKind.FRAME,
                "Rahmen",
                1920,
                1080,
                "/api/video-overlays/${"A".repeat(43)}",
            )
        val library = VideoOverlayLibraryDto(offered = true, items = listOf(item))
        assertEquals(
            library,
            json.decodeFromString(VideoOverlayLibraryDto.serializer(), json.encodeToString(VideoOverlayLibraryDto.serializer(), library)),
        )
        val plain = """{"id":"x","kind":"LOGO","name":"n","width":1,"height":1,"imagePath":"/p"}"""
        assertFalse(json.decodeFromString(VideoOverlayItemDto.serializer(), plain).instanceLogo)
    }
}
