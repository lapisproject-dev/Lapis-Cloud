package network.lapis.cloud.client

import dev.kilua.rpc.RpcSerialization
import kotlinx.serialization.Serializable
import network.lapis.cloud.shared.domain.BeneficialOwnerDataGapDto
import network.lapis.cloud.shared.domain.MemberAddressDataDto
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.SessionInfoDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V1.9.121 -- wire compatibility of the new country / nationality fields, with the JSON configuration Kilua RPC really uses (the way
 * `EncounterWireCompatTest` pins it): a new client reads an older server's answer (no new fields -> defaults), an old client reads a new
 * server's answer (unknown keys are ignored). No version gate is needed.
 */
class MemberCountryWireCompatTest {
    private val member =
        """"id":"m","displayName":"M","email":"m@example.org","status":"ACTIVE","joinedAt":"2026-01-01","role":"MEMBER","country":"Polen""""

    @Serializable
    private data class OldClientMember(
        val id: String,
        val displayName: String,
        val country: String? = null,
    )

    @Test
    fun aMemberFromAnOlderServer_decodesToTheEmptyDefaults() {
        val dto = RpcSerialization.getJson().decodeFromString(MemberDto.serializer(), "{$member}")
        assertEquals("Polen", dto.country)
        assertNull(dto.countryCode)
        assertTrue(dto.nationalityCodes.isEmpty())
        assertNull(dto.countryLegacy)
        assertNull(dto.nationalityLegacy)
    }

    @Test
    fun aMemberFromTheNewServer_carriesTheCodes() {
        val dto =
            RpcSerialization.getJson().decodeFromString(
                MemberDto.serializer(),
                "{$member,\"countryCode\":\"PL\",\"nationalityCodes\":[\"DE\",\"PL\"],\"countryLegacy\":null,\"nationalityLegacy\":null}",
            )
        assertEquals("PL", dto.countryCode)
        assertEquals(listOf("DE", "PL"), dto.nationalityCodes)
    }

    @Test
    fun anOldClient_readsTheNewServersMember_becauseUnknownKeysAreIgnored() {
        val old =
            RpcSerialization.getJson().decodeFromString(
                OldClientMember.serializer(),
                "{$member,\"countryCode\":\"PL\",\"nationalityCodes\":[\"DE\"],\"countryLegacy\":null,\"nationalityLegacy\":null}",
            )
        assertEquals("Polen", old.country)
    }

    @Test
    fun theAddressDataTheGapAndTheSession_decodeFromTheOlderShape() {
        val json = RpcSerialization.getJson()
        val address =
            json.decodeFromString(
                MemberAddressDataDto.serializer(),
                """{"memberId":"m","displayName":"M","street":null,"postalCode":null,"city":null,"country":"X","dateOfBirth":null,"nationality":null,"dateOfDeath":null}""",
            )
        assertTrue(address.nationalityCodes.isEmpty())
        val gap =
            json.decodeFromString(
                BeneficialOwnerDataGapDto.serializer(),
                """{"memberId":"m","memberDisplayName":"M","committeeRole":"CHAIR","missingDateOfBirth":true,"missingNationality":false}""",
            )
        assertFalse(gap.nationalityUnclear)
        val session =
            json.decodeFromString(
                SessionInfoDto.serializer(),
                """{"memberId":"m","displayName":"M","role":"MEMBER","expiresAt":"2099-01-01T00:00:00"}""",
            )
        assertNull(session.organizationCountryCode)
    }
}
