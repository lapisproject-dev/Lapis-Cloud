package network.lapis.cloud.client.encounter

import kotlinx.coroutines.delay
import network.lapis.cloud.client.allOf
import network.lapis.cloud.client.awaitUntil
import network.lapis.cloud.client.buttonNamed
import network.lapis.cloud.client.formTest
import network.lapis.cloud.client.mountedForm
import network.lapis.cloud.client.tick
import network.lapis.cloud.shared.domain.EncounterConsentDisclaimerDto
import network.lapis.cloud.shared.domain.EncounterConsentInput
import network.lapis.cloud.shared.domain.EncounterEntryInfoDto
import network.lapis.cloud.shared.domain.EncounterSpaceRole
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** V1.9.62 -- the entry panel: three plain sentences, the Art. 9 remark, the consent of a non-member, no platform link. */
class EncounterEntryDomTest {
    private val disclaimer =
        EncounterConsentDisclaimerDto(
            version = "encounter-consent-v1",
            headline = "<b>Einwilligung</b> zur Teilnahme",
            keyPoints = listOf("Sie nehmen an einem Gottesdienst teil.", "<img src=x onerror=alert(1)>"),
            text = "Der vollständige Text der Einwilligung.",
            sha256 = "abc123def456",
        )

    private fun info(
        consentRequired: Boolean,
        myRole: EncounterSpaceRole? = null,
    ) = EncounterEntryInfoDto(
        space = testSpace(myRole = myRole),
        consentRequired = consentRequired,
        disclaimer = if (consentRequired) disclaimer else null,
    )

    @Test
    fun aMember_seesTheThreeSentencesAndTheArt9Remark_andNoPlatformLink(): Promise<Unit> =
        formTest {
            var consent: EncounterConsentInput? = EncounterConsentInput("unset", "unset")
            var calls = 0
            mountedForm("encounter-entry-member") { root, element ->
                root.encounterEntryPanel(info(consentRequired = false)) {
                    consent = it
                    calls++
                }
                awaitUntil("the panel is shown") { element().textContent.orEmpty().contains("Bevor Sie eintreten") }
                val panel = element()
                assertEquals(3, panel.allOf(".lapis-encounter-notes li").size, "exactly three sentences")
                val text = panel.textContent.orEmpty()
                assertTrue(text.contains("Dieser Hinweis ist keine Rechtsberatung."))
                assertTrue(text.contains("Art. 9 DSGVO"))
                assertFalse(text.contains("YouTube", ignoreCase = true), "no platform is promised or linked")
                assertEquals(0, panel.allOf("a").size, "no link at all")
                assertNull(panel.querySelector("details"), "a member has no consent text")
                val enter = panel.buttonNamed("Eintreten")
                assertFalse(enter.hasAttribute("disabled"), "a member may enter at once")
                enter.click()
                awaitUntil("the entry was requested") { calls == 1 }
                assertNull(consent, "a member sends no consent")
            }
        }

    @Test
    fun anOfficeHolder_isToldWhichOfficeTheyEnterWith(): Promise<Unit> =
        formTest {
            mountedForm("encounter-entry-office") { root, element ->
                root.showEntryFor(EncounterSpaceRole.PULPIT)
                awaitUntil("the pulpit button") {
                    element().allOf("button").any {
                        it.textContent.orEmpty().trim() ==
                            "Eintreten und Kanzel übernehmen"
                    }
                }
                root.removeAll()
                root.showEntryFor(EncounterSpaceRole.STEWARD)
                awaitUntil(
                    "the steward button",
                ) { element().allOf("button").any { it.textContent.orEmpty().trim() == "Eintreten als Ordner" } }
            }
        }

    private fun io.kvision.panel.SimplePanel.showEntryFor(role: EncounterSpaceRole) {
        encounterEntryPanel(info(consentRequired = false, myRole = role)) { }
    }

    @Test
    fun aNonMember_mustTickTheConsent_andItsVersionAndHashTravelUnchanged(): Promise<Unit> =
        formTest {
            var consent: EncounterConsentInput? = null
            mountedForm("encounter-entry-guest") { root, element ->
                root.encounterEntryPanel(info(consentRequired = true)) { consent = it }
                awaitUntil("the consent text is shown") { element().textContent.orEmpty().contains("Vollständigen Text anzeigen") }
                val panel = element()
                assertNotNull(panel.querySelector("details"), "the full text sits behind a disclosure")
                assertTrue(panel.textContent.orEmpty().contains("Der vollständige Text der Einwilligung."))
                assertNull(panel.querySelector("img"), "an untrusted key point is text")
                assertNull(panel.querySelector("b"), "an untrusted headline is text")
                assertTrue(panel.textContent.orEmpty().contains("<img src=x onerror=alert(1)>"))
                val enter = panel.buttonNamed("Eintreten")
                assertTrue(enter.hasAttribute("disabled"), "disabled until the box is ticked")
                panel.tick("Ich habe den Hinweis gelesen und möchte eintreten.")
                awaitUntil("the button is enabled") { !enter.hasAttribute("disabled") }
                enter.click()
                awaitUntil("the entry carried the consent") { consent != null }
                assertEquals(EncounterConsentInput(consentVersion = "encounter-consent-v1", consentSha256 = "abc123def456"), consent)
                delay(50)
            }
        }
}
