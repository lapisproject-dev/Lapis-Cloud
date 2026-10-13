package network.lapis.cloud.client

import dev.kilua.rpc.RpcSerialization
import network.lapis.cloud.shared.domain.ConferenceAvailabilityDto
import network.lapis.cloud.shared.domain.ConferenceJoinTokenDto
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V1.9.124 -- wire compatibility of the `noiseSuppressionDefault` field on the three DTOs that carry it, with the JSON configuration Kilua
 * RPC really uses: an answer from an older server (no field) means "off", a new server's value round-trips.
 */
class NoiseSuppressionDefaultWireCompatTest {
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
        assertFalse(json.decodeFromString(ConferenceJoinTokenDto.serializer(), "{$token}").noiseSuppressionDefault)
        assertFalse(json.decodeFromString(ConferenceAvailabilityDto.serializer(), "{$availability}").noiseSuppressionDefault)
        assertFalse(json.decodeFromString(OrganizationSettingsDto.serializer(), "{$settings}").noiseSuppressionDefault)
    }

    @Test
    fun aNewServersValue_isRead_andRoundTrips() {
        val t = json.decodeFromString(ConferenceJoinTokenDto.serializer(), "{$token,\"noiseSuppressionDefault\":true}")
        val a = json.decodeFromString(ConferenceAvailabilityDto.serializer(), "{$availability,\"noiseSuppressionDefault\":true}")
        val o = json.decodeFromString(OrganizationSettingsDto.serializer(), "{$settings,\"noiseSuppressionDefault\":true}")
        assertTrue(t.noiseSuppressionDefault && a.noiseSuppressionDefault && o.noiseSuppressionDefault)
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
}
