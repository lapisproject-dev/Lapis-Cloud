package network.lapis.cloud.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.promise
import kotlinx.datetime.LocalDate
import network.lapis.cloud.shared.domain.OrganizationSettingsDto
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals

/** V1.9.336 -- the instance mail language that preselects the decision dialogs: normalisation and the quiet loader. */
class DecisionMailLanguageLoadTest {
    private fun test(block: suspend () -> Unit): Promise<Unit> = CoroutineScope(SupervisorJob()).promise { block() }

    private fun settings(mailLanguage: String) =
        OrganizationSettingsDto(
            id = "org-1",
            name = "Verein Testverein e.V.",
            street = null,
            postalCode = null,
            city = null,
            country = null,
            bankIban = null,
            bankBic = null,
            taxExemptionAuthority = null,
            taxExemptionDate = null as LocalDate?,
            mailLanguage = mailLanguage,
        )

    @Test
    fun decisionMailLanguageOf_normalises() {
        assertEquals("de", decisionMailLanguageOf(null))
        assertEquals("de", decisionMailLanguageOf(""))
        assertEquals("de", decisionMailLanguageOf("xx"))
        assertEquals("pl", decisionMailLanguageOf(" pl "))
        assertEquals("pl", decisionMailLanguageOf("pl"))
    }

    @Test
    fun loadDecisionMailLanguage_usesTheSetting(): Promise<Unit> =
        test {
            assertEquals("fr", loadDecisionMailLanguage { settings("fr") })
        }

    @Test
    fun loadDecisionMailLanguage_unknownValueFallsBackToGerman(): Promise<Unit> =
        test {
            assertEquals("de", loadDecisionMailLanguage { settings("xx") })
        }

    @Test
    fun loadDecisionMailLanguage_failingLoaderFallsBackToGerman(): Promise<Unit> =
        test {
            assertEquals("de", loadDecisionMailLanguage { error("boom") })
        }
}
