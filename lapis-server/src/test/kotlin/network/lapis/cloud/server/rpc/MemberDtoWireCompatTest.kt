package network.lapis.cloud.server.rpc

import dev.kilua.rpc.RpcSerialization
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import network.lapis.cloud.shared.domain.BeneficialOwnerDataGapDto
import network.lapis.cloud.shared.domain.MemberAddressDataDto
import network.lapis.cloud.shared.domain.MemberDto
import java.io.File

/**
 * Welle V1.9.121 -- Kilua RPC compatibility of browser tabs that stay open across a deploy: the new DTO fields are additive with defaults
 * (an older server's JSON still decodes, an older client ignores the new keys), and the new RPC methods are appended at the END of
 * `IMemberService` (Kilua numbers its routes by declaration order -- inserting earlier would shift the route of every later method).
 */
class MemberDtoWireCompatTest :
    FunSpec({
        val base =
            """"id":"m","displayName":"M","email":"m@example.org","status":"ACTIVE","joinedAt":"2026-01-01","role":"MEMBER","country":"Polen""""

        @Serializable
        data class OldClientMember(
            val id: String,
            val displayName: String,
            val country: String? = null,
        )

        test("a MemberDto from an older server (no code fields) decodes to the defaults") {
            val dto = RpcSerialization.getJson().decodeFromString(MemberDto.serializer(), "{$base}")
            dto.country shouldBe "Polen"
            dto.countryCode shouldBe null
            dto.nationalityCodes shouldBe emptyList()
            dto.countryLegacy shouldBe null
            dto.nationalityLegacy shouldBe null
        }

        test("an older client reads a MemberDto of the new server: the unknown keys are ignored") {
            val json = RpcSerialization.getJson()
            val newServer =
                json.encodeToString(
                    MemberDto.serializer(),
                    json
                        .decodeFromString(
                            MemberDto.serializer(),
                            "{$base}",
                        ).copy(countryCode = "PL", nationalityCodes = listOf("DE", "PL")),
                )
            newServer.contains("nationalityCodes") shouldBe true
            val old = json.decodeFromString(OldClientMember.serializer(), newServer)
            old.country shouldBe "Polen"
        }

        test("MemberAddressDataDto and the data-gap DTO decode from the older shape") {
            val json = RpcSerialization.getJson()
            val address =
                json.decodeFromString(
                    MemberAddressDataDto.serializer(),
                    """{"memberId":"m","displayName":"M","street":null,"postalCode":null,"city":null,"country":"X","dateOfBirth":null,"nationality":null,"dateOfDeath":null}""",
                )
            address.countryCode shouldBe null
            address.nationalityCodes shouldBe emptyList()
            val gap =
                json.decodeFromString(
                    BeneficialOwnerDataGapDto.serializer(),
                    """{"memberId":"m","memberDisplayName":"M","committeeRole":"CHAIR","missingDateOfBirth":true,"missingNationality":false}""",
                )
            gap.nationalityUnclear shouldBe false
        }

        test("the new IMemberService methods are the LAST declared ones, in order (Kilua route indices stay stable)") {
            val source =
                listOf(File("../lapis-shared"), File("lapis-shared"))
                    .first { it.exists() }
                    .resolve("src/commonMain/kotlin/network/lapis/cloud/shared/rpc/IMemberService.kt")
                    .readText()
            val methods = Regex("""(?m)^\s+suspend fun (\w+)\(""").findAll(source).map { it.groupValues[1] }.toList()
            methods.takeLast(5) shouldContainExactly
                listOf(
                    "updateMemberStreetAddress",
                    "updateMemberCountry",
                    "updateMemberNationalities",
                    "previewCountryLegacyMapping",
                    "applyCountryLegacyMapping",
                )
            // the method right before the appended block is the previous last one: nothing was inserted in the middle
            methods[methods.size - 6] shouldBe "listMembersForSelection"
        }
    })
