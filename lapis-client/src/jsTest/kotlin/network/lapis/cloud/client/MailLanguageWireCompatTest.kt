package network.lapis.cloud.client

import dev.kilua.rpc.RpcSerialization
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import kotlin.test.Test
import kotlin.test.assertEquals

/** V1.9.332 -- wire compatibility of `mailLanguage` with the JSON configuration Kilua RPC really uses: an older server (no field) means `de`. */
class MailLanguageWireCompatTest {
    private val json get() = RpcSerialization.getJson()

    private val settings =
        """"id":"o","name":"V","street":null,"postalCode":null,"city":null,"country":null,"bankIban":null,"bankBic":null,""" +
            """"taxExemptionAuthority":null,"taxExemptionDate":null"""

    @Test
    fun anAnswerFromAnOlderServer_meansGerman() {
        assertEquals("de", json.decodeFromString(OrganizationSettingsDto.serializer(), "{$settings}").mailLanguage)
    }

    @Test
    fun aNewServersValue_isRead_andRoundTrips() {
        val o = json.decodeFromString(OrganizationSettingsDto.serializer(), "{$settings,\"mailLanguage\":\"pl\"}")
        assertEquals("pl", o.mailLanguage)
        assertEquals(
            o,
            json.decodeFromString(OrganizationSettingsDto.serializer(), json.encodeToString(OrganizationSettingsDto.serializer(), o)),
        )
    }

    @Test
    fun theEightOfferedLanguages_areTheServersEight() {
        assertEquals(setOf("de", "en", "es", "fr", "it", "nl", "pl", "ru"), MAIL_LANGUAGES.map { it.first }.toSet())
        assertEquals(8, MAIL_LANGUAGES.map { it.second }.toSet().size)
    }
}
