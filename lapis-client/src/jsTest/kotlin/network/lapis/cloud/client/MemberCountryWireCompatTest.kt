package network.lapis.cloud.client

import dev.kilua.rpc.RpcSerialization
import kotlinx.serialization.Serializable
import network.lapis.cloud.shared.domain.BeneficialOwnerDataGapDto
import network.lapis.cloud.shared.domain.MemberAddressDataDto
import network.lapis.cloud.shared.domain.MemberAdminQuery
import network.lapis.cloud.shared.domain.MemberAdminRowDto
import network.lapis.cloud.shared.domain.MemberDto
import network.lapis.cloud.shared.domain.RegistrationInput
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

    // ---- V1.9.141: registration input, roster row / query, board gap ----

    @Serializable
    private data class OldClientRegistration(
        val displayName: String,
        val email: String,
    )

    private val oldRegistration =
        """"displayName":"N","email":"n@example.org","password":"pw","agreementVersion":"v1","agreementSha256":"abc""""

    @Test
    fun aRegistrationFromAnOlderClient_decodesWithoutCountryAndNationalities() {
        val input = RpcSerialization.getJson().decodeFromString(RegistrationInput.serializer(), "{$oldRegistration}")
        assertNull(input.countryCode)
        assertTrue(input.nationalityCodes.isEmpty())
    }

    @Test
    fun aRegistrationFromTheNewClient_carriesTheCodes_andAnOldServerModelIgnoresThem() {
        val json = RpcSerialization.getJson()
        val body = "{$oldRegistration,\"countryCode\":\"PL\",\"nationalityCodes\":[\"DE\",\"PL\"]}"
        val input = json.decodeFromString(RegistrationInput.serializer(), body)
        assertEquals("PL", input.countryCode)
        assertEquals(listOf("DE", "PL"), input.nationalityCodes)
        // An older server model without the fields still reads the same body (unknown keys are ignored).
        assertEquals("N", json.decodeFromString(OldClientRegistration.serializer(), body).displayName)
    }

    @Test
    fun aRosterRowAndQueryFromTheOlderShape_decodeToNullCountry() {
        val json = RpcSerialization.getJson()
        val row =
            json.decodeFromString(
                MemberAdminRowDto.serializer(),
                """{"id":"m","displayName":"M","email":"m@example.org","status":"ACTIVE","role":null,"joinedAt":"2026-01-01"}""",
            )
        assertNull(row.countryCode)
        assertNull(json.decodeFromString(MemberAdminQuery.serializer(), "{}").countryCode)
        val withCountry =
            json.decodeFromString(
                MemberAdminRowDto.serializer(),
                """{"id":"m","displayName":"M","email":"m@example.org","status":"ACTIVE","role":null,"joinedAt":"2026-01-01","countryCode":"PL"}""",
            )
        assertEquals("PL", withCountry.countryCode)
        assertEquals("DE", json.decodeFromString(MemberAdminQuery.serializer(), """{"countryCode":"DE"}""").countryCode)
    }

    @Test
    fun aBoardGapFromTheOlderShape_hasNoMissingCountry() {
        val gap =
            RpcSerialization.getJson().decodeFromString(
                BeneficialOwnerDataGapDto.serializer(),
                """{"memberId":"m","memberDisplayName":"M","committeeRole":"CHAIR","missingDateOfBirth":false,"missingNationality":false}""",
            )
        assertFalse(gap.missingCountry)
    }
}
